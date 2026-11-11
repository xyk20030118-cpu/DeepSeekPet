package com.deepseek.pet;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 角色包导出：把**当前绑定**打成一个自洽的标准角色包（zip）。
 *
 * <h3>★ v7.0 轮次㉑ 改了什么（用户追加要求）</h3>
 * 原来是"把用户角色目录整个 walk 一遍"。那有两个硬伤：
 * <ol>
 *   <li>P1 的绑定覆盖层在 {@code filesDir/scenes/<角色 id>.json}，**不在角色目录里**
 *       ⇒ 导出的包**带不走绑定**（别人导入后还是角色包原来的那一套）；</li>
 *   <li>用户在 {@code filesDir/scenes/<角色 id>/clips/} 里新加的动作也带不走；</li>
 *   <li>内置角色干脆不给导出。</li>
 * </ol>
 * 现在改成"**按当前绑定打包**"：
 * <ul>
 *   <li><b>有效绑定</b> = 角色包 {@code anim/pack.json} 叠加 {@link SceneBindings} 覆盖层
 *       —— 直接复用 {@link ActionSystem#load} + {@link ActionSystem#applyOverrides}
 *       那套装配语义，**不另写一份合并逻辑**（写两份迟早不一致）；</li>
 *   <li>收集**全部 15 个状态场景 + 3 个事件场景**（含 {@code pool}）引用动作的并集；</li>
 *   <li>逐个解析真实文件，优先级 <b>用户素材目录 → 角色包 clips → APK assets</b>；
 *       解析不到的**列进 {@link Result#missing}**，绝不静默丢；</li>
 *   <li>产出标准包：{@code anim/pack.json}（= 有效绑定）、{@code anim/clips/}（只放被引用的）、
 *       {@code anim/lines.json}（只留被包含动作的台词）、{@code character.json} / {@code memes/} /
 *       {@code persona.md}（有就带上）、<b>{@code appearance/}（形象素材，有就带上）</b>。
 *       **导入器能原样吃回去**（见 {@code build/rt_test} 的往返断言）。</li>
 *   <li>★ <b>待办 0m ①：形象也要跟着走</b>。P5 把形象收进了覆盖层
 *       {@code filesDir/appearance/<角色 id>/}，而导出器原来只打角色目录 ⇒
 *       <b>把包发给别人，对方拿到的角色没有脸</b>。现在把整个形象目录搬进包里的
 *       {@code appearance/}（与运行期**同名同结构**），导入时再搬回覆盖层。</li>
 *   <li>★ <b>内置角色现在也允许导出</b>：按当前绑定导出不再是"只有 1 个动作的空包"
 *       （内置角色的有效绑定里有内置基底那套 + 用户加的动作）。
 *       调用方若想提醒"这个角色没有任何自定义改动"，那是**提示**，不是拦截。</li>
 * </ul>
 *
 * <h3>流式写</h3>
 * 全部动作可能有 300 MB 量级，所以逐文件 64 KB 缓冲边读边写，**绝不把整包读进内存**；
 * 写入总量同时累加，超过导入器上限（600 MB）时中止并报错，免得导出一个自己都导不回来的包。
 */
public final class CharacterExporter {

    /** 与 {@link CharacterImporter} 的上限保持一致（导出超过它 = 用户一定导不回来） */
    private static final long MAX_TOTAL_BYTES = CharacterImporter.MAX_TOTAL_BYTES;

    public static class Result {
        public boolean ok;
        public String error = "";
        public int files;
        public long bytes;
        /** 写进包的动作数 */
        public int clips;
        /** 解析不到素材、被跳过的动作名（★ 不静默丢：界面要如实提示） */
        public final List<String> missing = new ArrayList<String>();
        /** 有绑定的场景个数 */
        public int scenes;
        /** 角色包原始配置里没有、由用户新增/改过的场景个数（提示用） */
        public int overridden;
        /**
         * 一起带走的**形象素材**文件数（{@code appearance/} 下）。
         * <p>★ 待办 0m ①：以前导出不带形象 ⇒ 把包发给别人，对方拿到的角色**没有脸**
         * （用的是他自己上一次的形象或内置）。现在有就带上，界面也如实说。
         */
        public int appearanceFiles;

        /** 这个包有没有带形象 */
        public boolean hasAppearance() { return appearanceFiles > 0; }
    }

    /** 一个动作素材被解析到的形态：扩展名 + 数据流（调用方负责关闭） */
    public static final class Found {
        public final String ext;
        public final InputStream in;
        public Found(String ext, InputStream in) { this.ext = ext; this.in = in; }
    }

    /**
     * 素材解析器：动作名 → 真实素材。
     * <p>Android 侧的实现按"用户目录 → 角色包 clips → APK assets"三级找；
     * 离线断言里换成"只在给定目录里找"，于是往返断言不需要 Android 运行时。
     */
    public interface ClipSource {
        /** 找到返回 {@link Found}，找不到返回 null */
        Found open(String id);
    }

    /** 导出的"计划"：有效绑定的收集结果（可单独断言） */
    public static class Plan {
        public final LinkedHashMap<String, List<SceneBindings.Bind>> scenes =
                new LinkedHashMap<String, List<SceneBindings.Bind>>();
        public final LinkedHashMap<String, List<SceneBindings.Bind>> events =
                new LinkedHashMap<String, List<SceneBindings.Bind>>();
        /** 全部被引用的动作名（并集、保序、去重） */
        public final List<String> clipIds = new ArrayList<String>();

        /** 有绑定的场景数（状态 + 事件） */
        public int sceneCount() { return scenes.size() + events.size(); }

        /** 全部动作名（拷贝） */
        public List<String> clipList() { return new ArrayList<String>(clipIds); }
    }

    private CharacterExporter() {}

    // ==================== 收集 ====================

    /**
     * 按**有效绑定**收集（15 状态场景 + 3 事件场景，含 {@code pool}）。
     * <p>传入的 {@code as} 必须是"已叠加用户覆盖层"的装配（见 {@link ActionSystem#loadForDisplay}）。
     */
    public static Plan plan(ActionSystem as) {
        Plan p = new Plan();
        if (as == null) return p;
        for (String id : SceneRegistry.ids()) addGroup(p.scenes, id, as.bindsOf(id), p.clipIds);
        for (String id : SceneRegistry.eventIds()) addGroup(p.events, id, as.bindsOf(id), p.clipIds);
        return p;
    }

    private static void addGroup(LinkedHashMap<String, List<SceneBindings.Bind>> into, String scene,
                                 List<PetAnimPack.Clip> binds, List<String> union) {
        List<SceneBindings.Bind> out = new ArrayList<SceneBindings.Bind>();
        if (binds != null) {
            for (PetAnimPack.Clip c : binds) {
                if (c == null || c.id == null || c.id.isEmpty()) continue;
                out.add(new SceneBindings.Bind(c.id, Math.max(1, c.weight)));
            }
        }
        if (out.isEmpty()) return;                     // 没绑定的场景不写进包
        into.put(scene, out);
        for (SceneBindings.Bind b : out) if (!union.contains(b.id)) union.add(b.id);
    }

    // ==================== 导出 ====================

    /** 设置页入口：按当前角色 + 有效绑定导出 */
    public static Result export(Context ctx, PetCharacter ch, OutputStream out) {
        Result r = new Result();
        if (ch == null || out == null) {
            r.error = "没有可导出的角色";
            return r;
        }
        try {
            // ① 有效绑定：角色包配置 + 用户覆盖层（与运行期同一套装配语义）
            PetAnimPack pk = new PetAnimPack();
            pk.load(ctx.getApplicationContext(), ch.dir);
            ActionSystem as = new ActionSystem();
            as.attach(pk);
            as.load(pk.configRoot());
            as.applyOverrides(SceneBindings.loadRoot(ctx, ch.id));
            Plan plan = plan(as);
            r.overridden = as.overrideCount();
            // ② 素材解析：用户素材目录 → 角色包 clips → APK assets
            ClipSource src = withAssets(ctx, dirSource(
                    SceneBindings.clipsDirFor(ctx, ch.id),
                    ch.dir == null ? null : new File(ch.dir, PetCharacter.F_CLIPS)));
            // ③ 形象（P5 的覆盖层）：filesDir/appearance/<角色 id>/
            //    ★ 待办 0m ①：不带形象的话，别人导入后**没有脸**。
            File appearance = AppearanceStore.dirIn(ctx.getFilesDir(), ch.id);
            return write(out, plan, src, pk, ch.dir, appearance, ch.id, ch.displayName(), r);
        } catch (Throwable t) {
            r.error = "导出失败：" + t;
            return r;
        }
    }

    /**
     * 真正写包（**不碰 Context** —— 离线往返断言走的就是这个入口）。
     *
     * @param charDir       角色包目录（可为 null = 内置角色：不复制 memes/persona，character.json 现造一个）
     * @param appearanceDir 这个角色的**形象覆盖层**目录（{@code filesDir/appearance/<角色 id>/}，
     *                      可为 null 或不存在 = 没有自定义形象）；有就整棵搬进 {@code appearance/}
     */
    public static Result write(OutputStream out, Plan plan, ClipSource src, PetAnimPack pk,
                               File charDir, File appearanceDir, String charId, String charName,
                               Result r) {
        if (r == null) r = new Result();
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(out);
            zos.setLevel(6);
            // ① anim/pack.json —— 写**有效绑定**（不是角色包原文件）
            putText(zos, "anim/pack.json", PackFormat.packJson(
                    toItems(plan.scenes), toItems(plan.events)), r);
            r.scenes = plan.sceneCount();
            // ② anim/clips/ —— 只放被引用到的动作，边读边写
            byte[] buf = new byte[65536];
            for (String id : plan.clipIds) {
                Found f = null;
                try { f = (src == null) ? null : src.open(id); } catch (Throwable t) { f = null; }
                if (f == null) { r.missing.add(id); continue; }
                InputStream in = f.in;
                try {
                    zos.putNextEntry(new ZipEntry("anim/clips/" + id + "." + f.ext));
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                        r.bytes += n;
                        // ★ 上限判据只有一份（overCap）—— 动作/表情/形象三条路都走它，
                        //   否则"某一条路忘了查"就是导出一个自己都导不回来的包
                        if (overCap(r)) {
                            try { zos.closeEntry(); } catch (Throwable ignore) {}
                            return r;
                        }
                    }
                    zos.closeEntry();
                    r.clips++;
                    r.files++;
                } catch (Throwable t) {
                    r.missing.add(id);
                } finally {
                    try { in.close(); } catch (Throwable ignore) {}
                }
            }
            // ③ anim/lines.json —— 只留被包含动作的台词
            if (pk != null) {
                Map<String, List<String>> all = new LinkedHashMap<String, List<String>>();
                for (String id : plan.clipIds) {
                    List<String> ls = pk.linesOf(id);
                    if (ls != null) all.put(id, ls);
                }
                String lines = PackFormat.linesJson(plan.clipIds, all);
                if (lines != null) putText(zos, "anim/lines.json", lines, r);
            }
            // ④ 角色元信息 / 表情 / 性格（有就带上；内置角色现造一个 character.json）
            File meta = (charDir == null) ? null : new File(charDir, PetCharacter.F_CHARACTER);
            if (meta != null && meta.isFile() && meta.length() > 0) {
                putFile(zos, "character.json", meta, r);
            } else {
                putText(zos, "character.json", characterJson(charId, charName), r);
            }
            if (charDir != null) {
                putTree(zos, new File(charDir, "memes"), "memes", r);
                File persona = new File(charDir, PetCharacter.F_PERSONA);
                if (persona.isFile() && persona.length() > 0) putFile(zos, PetCharacter.F_PERSONA, persona, r);
            }
            if (!r.error.isEmpty()) return r;      // 撞到 600 MB 上限（见 overCap）
            // ⑤ 形象（P5 的覆盖层）：整棵搬进 appearance/
            //    ★ 只搬"当前角色的形象目录"，不解析来源 —— 素材全带上、来源声明在
            //      appearance.json 里，对方导入后**看到的就是同一张脸**；
            //      素材缺失时 Appearance.resolve 会在运行期回退内置（不留空白）。
            if (appearanceDir != null && appearanceDir.isDirectory()) {
                int before = r.files;
                putTree(zos, appearanceDir, PackFormat.APPEARANCE_DIR, r);
                r.appearanceFiles = r.files - before;
            }
            if (!r.error.isEmpty()) return r;      // putFile 撞到 600 MB 上限
            zos.finish();
            zos.flush();
            r.ok = true;
        } catch (Throwable t) {
            r.error = "导出失败：" + t;
            r.ok = false;
        } finally {
            try { if (zos != null) zos.close(); } catch (Throwable ignore) {}
        }
        return r;
    }

    private static LinkedHashMap<String, List<PackFormat.Item>> toItems(
            LinkedHashMap<String, List<SceneBindings.Bind>> src) {
        LinkedHashMap<String, List<PackFormat.Item>> out =
                new LinkedHashMap<String, List<PackFormat.Item>>();
        for (Map.Entry<String, List<SceneBindings.Bind>> e : src.entrySet()) {
            List<PackFormat.Item> l = new ArrayList<PackFormat.Item>();
            for (SceneBindings.Bind b : e.getValue()) l.add(new PackFormat.Item(b.id, b.weight));
            out.put(e.getKey(), l);
        }
        return out;
    }

    /** 内置角色（没有目录）也要有一份元信息，否则导入后名字是一片空白 */
    static String characterJson(String charId, String charName) {
        String id = (charId == null || charId.isEmpty()) ? "my_pet" : charId;
        String name = (charName == null || charName.isEmpty()) ? id : charName;
        return "{\n  \"id\": \"" + ClipNaming.jsonEscape(id) + "\",\n"
                + "  \"name\": \"" + ClipNaming.jsonEscape(name) + "\",\n"
                + "  \"author\": \"DeepSeekPet 导出\",\n"
                + "  \"note\": \"按导出时的当前绑定生成（配置 = 有效绑定，clips 只含被引用的动作）\"\n}\n";
    }

    // ==================== 素材解析 ====================

    /**
     * 目录优先级解析：按 {@code roots} 的顺序找 {@code <id>.<ext>}（扩展名按
     * {@link ClipNaming#IMAGE_EXTS} 的顺序）。找不到返回 null。
     * <p>Android 侧与离线断言共用这一个实现 —— 查找顺序只有一份。
     */
    public static ClipSource dirSource(final File... roots) {
        return new ClipSource() {
            @Override public Found open(String id) {
                if (id == null || roots == null) return null;
                for (File root : roots) {
                    if (root == null) continue;
                    for (String e : ClipNaming.IMAGE_EXTS) {
                        File f = new File(root, id + "." + e);
                        if (!f.isFile() || f.length() <= 0) continue;
                        try { return new Found(e, new FileInputStream(f)); } catch (Throwable ignore) {}
                    }
                }
                return null;
            }
        };
    }

    /** 在给定解析器后面再叠一层 APK {@code assets/webp/} 兜底（内置基底那 1 个动作在那里） */
    static ClipSource withAssets(final Context ctx, final ClipSource base) {
        return new ClipSource() {
            @Override public Found open(String id) {
                if (base != null) {
                    Found f = base.open(id);
                    if (f != null) return f;
                }
                if (ctx == null || id == null) return null;
                for (String e : ClipNaming.IMAGE_EXTS) {
                    try {
                        InputStream in = ctx.getAssets().open("webp/" + id + "." + e);
                        if (in != null) return new Found(e, in);
                    } catch (Throwable ignore) {}
                }
                return null;
            }
        };
    }

    // ==================== zip 小工具 ====================

    private static void putText(ZipOutputStream zos, String name, String text, Result r)
            throws Exception {
        if (overCap(r)) return;
        zos.putNextEntry(new ZipEntry(name));
        byte[] b = text.getBytes("UTF-8");
        zos.write(b, 0, b.length);
        zos.closeEntry();
        r.files++;
        r.bytes += b.length;
        overCap(r);
    }

    private static void putFile(ZipOutputStream zos, String name, File f, Result r) throws Exception {
        if (overCap(r)) return;
        zos.putNextEntry(new ZipEntry(name));
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            zos.write(buf, 0, n);
            r.bytes += n;
            if (overCap(r)) break;      // ★ 超上限立刻停：导出一个自己都导不回来的包没有意义
        }
        in.close();
        zos.closeEntry();
        r.files++;
    }

    /**
     * 累计字节超过上限？
     * <p>★ 动作那条路原来就查，但 {@code memes/} 与 {@code appearance/} 走的是 putTree/putFile ——
     * 它们**从来没查过**。形象素材（尤其 Live2D 模型包）比表情大得多，必须一起挡。
     */
    private static boolean overCap(Result r) {
        if (r.bytes > MAX_TOTAL_BYTES) {
            if (r.error.isEmpty()) r.error = "导出内容超过 600 MB 上限（导入器会拒绝）";
            return true;
        }
        return false;
    }

    /** 递归带上一个子目录（表情包）；空/不存在则跳过 */
    private static void putTree(ZipOutputStream zos, File dir, String prefix, Result r)
            throws Exception {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            String name = prefix + "/" + f.getName();
            if (f.isDirectory()) { putTree(zos, f, name, r); continue; }
            if (f.length() <= 0) continue;
            putFile(zos, name, f, r);
        }
    }

    /** 被引用动作的集合（给界面提示"这个包会包含 N 个动作"用） */
    public static LinkedHashSet<String> referenced(ActionSystem as) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        out.addAll(plan(as).clipIds);
        return out;
    }
}
