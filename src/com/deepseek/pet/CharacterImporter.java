package com.deepseek.pet;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 角色包导入器：把一个 zip 装成用户角色目录 {@code filesDir/characters/<id>/}。
 *
 * <h3>格式</h3>
 * 见工程根 {@code 角色包格式.md}（标准包：{@code character.json} + {@code anim/pack.json}
 * + {@code anim/clips/*.webp} + {@code memes/} + {@code persona.md}
 * + <b>{@code appearance/}（形象素材，待办 0m ①）</b>）。
 *
 * <h3>★ 待办 0j：能省的全都能省</h3>
 * 用户原话「角色动作包太麻烦了，有没有更简单的自定义导入方案」。现在**最少只要一堆动图**：
 * <ul>
 *   <li>{@code character.json} —— 可选（id/名字用兜底值）；</li>
 *   <li>动作文件放哪个目录 —— 无所谓（递归找同名文件）；</li>
 *   <li>台词 / 表情 / 性格 —— 可选；</li>
 *   <li><b>{@code anim/pack.json} —— 可选</b>：没有就按文件名现场合成一份 2.0 配置
 *       （{@link ClipNaming#synthesizePack}），文件名前缀决定场景
 *       （{@link ClipNaming#sceneOf}），不带前缀的进待机随机池。</li>
 * </ul>
 * 于是"把动图打成 zip 丢进来"就够了 —— 零 JSON、零目录要求。
 *
 * <h3>为什么由 App 自己解压写盘</h3>
 * 不只是"顺手"：Android 的 SELinux 要求应用目录下的文件带**本应用的 MLS 类别**
 * （实测：root 用 adb push 进去的文件是 {@code app_data_file:s0}，缺类别 ⇒ 应用读不到，
 * 表现为"导入成功但动作一个都没生效"）。由应用自己写就没有这个问题。
 */
public final class CharacterImporter {

    /** 体积/数量上限（防误选一个几百 MB 的包把手机撑爆） */
    // ★ 上限实测校准：官方「默认角色包」= 106 个动作 + 27 张表情 = **312.9 MB**，
    //   第一版把总上限写成 200 MB，结果连自家默认包都导不进来（设备实测报"包体超过上限"）。
    //   ★ public：导出器（{@link CharacterExporter}）也要挡"导出一个自己都导不回来的包"。
    public static final long MAX_TOTAL_BYTES = 600L * 1024 * 1024;
    private static final long MAX_CLIP_BYTES = 16L * 1024 * 1024;
    private static final int MAX_CLIPS = 400;

    public static class Result {
        public boolean ok;
        public String id = "";
        public String name = "";
        public String error = "";
        public int clips;
        /** pack.json 里引用了、但包里没有对应文件的动作 */
        public final List<String> missing = new ArrayList<String>();
        public File dir;
        /**
         * ★ 包内**没有** {@code anim/pack.json}，配置是导入器按文件名**现场合成**的
         * （见 {@link ClipNaming#synthesizePack}）。给界面提示用：
         * "没写配置也能用"这件事要让用户看得见，否则他不知道自己"少做了一步"。
         */
        public boolean synthesized;
        /** 合成配置里归出来的场景数（{@link #synthesized} 为真时才有意义） */
        public int synScenes;
        /**
         * ★ 待办 0m ①：包里带了形象素材并已装进这个角色的覆盖层
         * （{@code filesDir/appearance/<角色 id>/}）⇒ 对方看到的就是导出者那张脸。
         */
        public boolean appearance;
        /** 装进去的形象文件数（含 appearance.json） */
        public int appearanceFiles;
    }

    private CharacterImporter() {}

    /** 只校验不安装（给"导入前预览"用；目前 UI 没接） */
    public static Result inspect(Context ctx, InputStream in) {
        return run(ctx, in, true);
    }

    /** 解压 + 校验 + 安装到 filesDir/characters/<id>/。覆盖同名角色。 */
    public static Result install(Context ctx, InputStream in) {
        return run(ctx, in, false);
    }

    private static Result run(Context ctx, InputStream in, boolean dryRun) {
        return installInto(ctx.getFilesDir(), CharacterStore.root(ctx),
                ctx.getFilesDir(), in, dryRun);
    }

    /**
     * 安装核心（★ **不碰 Context**）：临时目录、角色根目录、形象覆盖层根目录都由调用方给。
     *
     * <p>为什么单独开这个口子：用户要求"导出的包再导入一遍，动作数必须一个不差"，
     * 而那条往返断言要在**桌面 JVM** 上跑（模拟器/真机不方便逐次跑），
     * 桌面侧造不出可用 Context（AssetManager 是 final 类、Context 有 104 个抽象方法）。
     * 于是把"与 Context 无关的全部逻辑"放到这里，Context 版本只剩两行取目录 ——
     * 往返断言跑的就是**同一份**解压/校验/落盘代码。
     *
     * @param filesDir 形象覆盖层的根（{@code filesDir/appearance/} 就在它下面）；
     *                 传 null = 不处理包里的 {@code appearance/}（老调用点保持旧行为）
     */
    public static Result installInto(File tmpBase, File charRoot, File filesDir,
                                     InputStream in, boolean dryRun) {
        Result r = new Result();
        File tmp = new File(tmpBase, "import_char_tmp");
        PetModel.deleteRecursive(tmp);
        if (!tmp.mkdirs()) { r.error = "无法创建临时目录"; return r; }
        try {
            long total = 0;
            int clips = 0;
            ZipInputStream zis = new ZipInputStream(in);
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (!safeName(name)) { r.error = "包内有非法路径：" + name; return r; }
                long size = e.getSize();
                boolean isClip = isClip(name);
                if (isClip) {
                    if (size > MAX_CLIP_BYTES) { r.error = "单个动作过大：" + name; return r; }
                    if (++clips > MAX_CLIPS) { r.error = "动作数量超过上限 " + MAX_CLIPS; return r; }
                }
                if (total > MAX_TOTAL_BYTES) { r.error = "包体超过上限 600 MB"; return r; }
                File dst = new File(tmp, name);
                File parent = dst.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) { r.error = "无法创建目录"; return r; }
                FileOutputStream out = new FileOutputStream(dst);
                int n;
                long wrote = 0;
                while ((n = zis.read(buf)) > 0) {
                    wrote += n;
                    total += n;                     // ★ 用**实际写入字节**计总量（zip 流式读取时 getSize() 常为 -1）
                    if (wrote > MAX_CLIP_BYTES && isClip) { out.close(); r.error = "单个动作过大：" + name; return r; }
                    if (total > MAX_TOTAL_BYTES) { out.close(); r.error = "包体超过上限 600 MB"; return r; }
                    out.write(buf, 0, n);
                }
                out.flush();
                out.close();
            }
            zis.close();

            // —— 找配置：标准位置 anim/pack.json；也允许根目录 pack.json ——
            //
            // ★ 待办 0j 的 A：**没有 pack.json 不再报错**。
            //   改造前这里直接 `r.error` 返回，而「角色包格式.md」第六节第 2 条
            //   早就写了"或至少有一个 clips 下的动画文件" —— 文档承诺了、代码没实现，
            //   这正是用户觉得"角色包太麻烦"的直接原因（要手写 18 个场景的 JSON）。
            //   现在改为：拿包里找到的动作文件现场合成一份 2.0 配置，
            //   并把它**写进角色目录**（角色包从此自带配置，导出/分享出去也是完整的）。
            //
            // ★ 动作文件的统计口径也统一走 collectClipFiles：它先看 anim/clips/、
            //   没有才全树扫并**跳过 memes/**。原来按"任意目录的图片文件"数，
            //   一个带 27 张表情的包会报成"133 个动作"（表情也被算成动作）。
            List<String> found = collectClipFiles(tmp);
            r.clips = found.size();
            if (found.isEmpty()) {
                r.error = "包里没有可用的动作文件（" + ClipNaming.extList() + "）";
                return r;
            }
            File pack = firstFile(tmp, "anim/pack.json", "pack.json");
            if (pack == null) {
                String json = ClipNaming.synthesizePack(found.toArray(new String[0]));
                File dst = new File(tmp, "anim/pack.json");
                File parent = dst.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    r.error = "无法创建 anim 目录"; return r;
                }
                writeText(dst, json);
                pack = dst;
                r.synthesized = true;
                r.synScenes = ClipNaming.scenesIn(found.toArray(new String[0])).length;
                Log.i("DSPChar", "自动合成 anim/pack.json: 动作=" + found.size()
                        + " 场景=" + r.synScenes);
            }

            // —— 元信息 ——
            File meta = firstFile(tmp, "character.json");
            String id = deriveId(meta, pack);
            r.id = id;
            r.name = deriveName(meta, id);

            // —— pack.json 引用但缺文件的动作 ——
            try {
                JSONObject root = new JSONObject(readText(pack));
                collectMissing(root.optJSONObject("scenes"), tmp, r.missing);
                collectMissing(root.optJSONObject("events"), tmp, r.missing);
            } catch (Throwable t) {
                r.error = "anim/pack.json 不是合法 JSON：" + t.getMessage();
                return r;
            }

            if (dryRun) { r.ok = true; return r; }

            // —— 安装 ——
            File dest = new File(charRoot, id);
            PetModel.deleteRecursive(dest);
            if (!dest.mkdirs()) { r.error = "无法创建角色目录"; return r; }
            if (!moveTree(tmp, dest)) { r.error = "写入角色目录失败"; return r; }
            // 目录名 = id：character.json 里若写了别的 id，落盘后以目录名为准
            r.dir = dest;

            // —— 形象（P5 的覆盖层，待办 0m ①）——
            //
            // ★ 包里有 appearance/ 才动它：**没有就别去删对方已有的形象**
            //   （老包、或导出时本来就没自定义形象）—— 包没提形象，就不该改变现状。
            // ★ 落点必须走 AppearanceStore（它是"形象在哪"的唯一真相）：
            //   目录名要过 ActionMenuConfig.sanitize，跟运行期读的是同一个路径。
            File appSrc = new File(tmp, PackFormat.APPEARANCE_DIR);
            if (filesDir != null && appSrc.isDirectory()) {
                File appDst = AppearanceStore.dirIn(filesDir, id);
                PetModel.deleteRecursive(appDst);          // 覆盖同名角色的旧形象
                File appParent = appDst.getParentFile();
                if (appParent != null && !appParent.isDirectory()) appParent.mkdirs();
                if (appDst.mkdirs() || appDst.isDirectory()) {
                    r.appearanceFiles = countFiles(appSrc);
                    r.appearance = moveTree(appSrc, appDst) && r.appearanceFiles > 0;
                }
                // 素材缺失 ⇒ 运行期 Appearance.resolve 会回退内置，这里如实记下来即可
                Log.i("DSPChar", "导入形象 -> char=" + id + " 文件=" + r.appearanceFiles
                        + " 来源=" + AppearanceStore.sourceIn(filesDir, id));
            }

            r.ok = true;
            return r;
        } catch (Throwable t) {
            r.error = "导入失败：" + t;
            return r;
        } finally {
            PetModel.deleteRecursive(tmp);
            try { in.close(); } catch (Throwable ignore) {}
        }
    }

    // ==================== 工具 ====================

    /** 防路径穿越：不允许绝对路径、不允许 ..、不允许反斜杠 */
    static boolean safeName(String n) {
        if (n == null || n.isEmpty()) return false;
        if (n.startsWith("/") || n.startsWith("\\")) return false;
        if (n.contains("..")) return false;
        if (n.indexOf('\\') >= 0) return false;
        return true;
    }

    /**
     * 是不是一个"动作素材"文件。
     *
     * <p>★ 待办 0j 的 D：原来只认 {@code .webp/.gif}，于是 App 内「添加动作」从相册里
     * 选到的 **PNG/JPG 会被"包里没有动作文件"拒掉**（选了图什么也没发生）。
     * 现在交给纯类 {@link ClipNaming#isImageName} —— 扩展名清单只有那一份，
     * 导入器与素材层不会各写一套（素材层那边同步能按静态图解码，见 {@link PetAnimPack}）。
     */
    static boolean isClip(String n) {
        return ClipNaming.isImageName(n);
    }

    /**
     * 包内**可当动作**的图片文件（相对 tmp 的路径，排序后返回）。
     *
     * <p>先看标准位置 {@code anim/clips/}；那里一个都没有才全树扫。
     * ★ 全树扫时跳过**辅助目录**（{@link PackFormat#isAuxDir}：{@code memes/} 与
     * {@code appearance/}）—— 它们里面也是 png，混进来就会变成"动作"，
     * 用户会看到自己的**表情包和脸**出现在动作库里。
     * ★ 判据集中在 {@link PackFormat#isAuxDir}：原来是这里硬编码 {@code "memes"}，
     * 本轮加了 {@code appearance/} 之后**必须同步**，否则导出的包再导回来会多出动作
     * （往返断言的"动作数一个不差"正好抓这个）。
     */
    private static List<String> collectClipFiles(File tmp) {
        List<String> out = new ArrayList<String>();
        File clips = new File(tmp, "anim/clips");
        if (clips.isDirectory()) collectInto(clips, tmp, out, false);
        if (out.isEmpty()) collectInto(tmp, tmp, out, true);
        java.util.Collections.sort(out);
        return out;
    }

    private static void collectInto(File dir, File base, List<String> out, boolean skipAux) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (skipAux && PackFormat.isAuxDir(f.getName())) continue;
                collectInto(f, base, out, skipAux);
            } else if (isClip(f.getName())) {
                out.add(f.getAbsolutePath().substring(base.getAbsolutePath().length() + 1));
            }
        }
    }

    /** 数一个目录里的文件个数（递归；形象装了几个文件要如实报给界面） */
    private static int countFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) n += f.isDirectory() ? countFiles(f) : 1;
        return n;
    }

    /** 写 UTF-8 文本（配置合成后落盘用） */
    private static void writeText(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes("UTF-8"));
        out.flush();
        out.close();
    }

    private static File firstFile(File base, String... rels) {
        for (String rel : rels) {
            File f = new File(base, rel);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    private static String readText(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** id：character.json 的 id → 净化；没有就用 zip 里的名字（clips 目录名兜底） */
    static String deriveId(File meta, File pack) {
        String raw = null;
        if (meta != null) {
            try {
                raw = new JSONObject(readText(meta)).optString("id", null);
            } catch (Throwable ignore) {}
        }
        if (raw == null || raw.trim().isEmpty()) raw = "char_" + System.currentTimeMillis() % 100000;
        return ActionMenuConfig.sanitize(raw.trim().toLowerCase());
    }

    static String deriveName(File meta, String id) {
        if (meta != null) {
            try {
                String n = new JSONObject(readText(meta)).optString("name", "");
                if (!n.trim().isEmpty()) return n.trim();
            } catch (Throwable ignore) {}
        }
        return id;
    }

    /** 递归复制（move 跨盘时不可靠，用"复制 + 删源"） */
    private static boolean moveTree(File src, File dst) {
        File[] fs = src.listFiles();
        if (fs == null) return false;
        for (File f : fs) {
            File to = new File(dst, f.getName());
            if (f.isDirectory()) {
                if (!to.mkdirs() && !to.isDirectory()) return false;
                if (!moveTree(f, to)) return false;
            } else {
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    FileOutputStream out = new FileOutputStream(to);
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    out.flush();
                    out.close();
                    in.close();
                } catch (Throwable t) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void collectMissing(JSONObject group, File tmp, List<String> missing) {
        if (group == null) return;
        java.util.Iterator<String> it = group.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object o = group.opt(key);
            JSONArray arr = (o instanceof JSONArray) ? (JSONArray) o
                    : (o instanceof JSONObject ? ((JSONObject) o).optJSONArray("clips") : null);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                Object e = arr.opt(i);
                String id = (e instanceof String) ? (String) e
                        : (e instanceof JSONObject
                           ? ((JSONObject) e).optString("id", ((JSONObject) e).optString("name", null))
                           : null);
                if (id == null || id.isEmpty()) continue;
                if (!clipExists(tmp, id) && !missing.contains(id)) missing.add(id);
            }
        }
    }

    /** 包内是否真有这个动作（clips/ 下，或任意目录同名文件） */
    private static boolean clipExists(File tmp, String id) {
        return findClip(tmp, id) != null;
    }

    private static File findClip(File dir, String id) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory()) {
                File r = findClip(f, id);
                if (r != null) return r;
            } else {
                String n = f.getName();
                int dot = n.lastIndexOf('.');
                if (dot > 0 && n.substring(0, dot).equals(id) && isClip(n)) return f;
            }
        }
        return null;
    }
}
