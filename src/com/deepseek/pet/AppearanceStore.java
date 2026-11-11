package com.deepseek.pet;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 形象素材的**按角色仓库**（P5：形象来源纳入角色模型）。
 *
 * <h3>它解决什么</h3>
 * 在 P5 之前，"这只桌宠长什么样"是**全局**的：四个 pref
 * （{@code use_custom_image / use_custom_anim / use_custom_model / use_custom_l2d}）
 * 加四个固定路径（{@code filesDir/custom_pet.png}、{@code custom_pet_anim.gif}、
 * {@code custom_pet_model/}、{@code custom_l2d_model/}）。切了角色，形象不跟着走 ——
 * 新角色顶着的还是上一个角色的脸。本类把形象收进
 * <b>{@code filesDir/appearance/<角色 id>/}</b>，每个角色一份：
 *
 * <pre>filesDir/appearance/&lt;角色 id&gt;/
 *   appearance.json   {"version":1,"source":"image"}   ← 用哪一种来源
 *   image.png         上传的静态图（固定名，重传即替换）
 *   anim.gif          上传的动图（GIF / 动态 WebP）
 *   model/            序列帧模型包（zip 解压后的目录）
 *   l2d/              Live2D 模型包（meta.json + 分层 PNG）</pre>
 *
 * <p>★ 为什么放在**覆盖层**（{@code appearance/}）而不是角色包的 {@code characters/<id>/} 里：
 * 与 P1 的场景覆盖层、㉑ 的用户素材同一个理由 —— 内置角色**没有目录**
 * （素材在 APK assets 里、只读），放覆盖层才能让内置与用户角色一视同仁，
 * 而且"恢复默认形象"就是删这个目录，不动角色包一个字节。
 *
 * <h3>★ 读路径一律不 mkdirs</h3>
 * 轮次㉒ 抓到过一个真 bug：删除路径用了会 {@code mkdirs()} 的取目录函数，
 * 于是"删完角色还留一个空壳目录"。所以这里 {@link #dirIn} <b>只算路径不建目录</b>，
 * 要建目录只有 {@link #ensureDirIn} 一个入口（写入前才调）。
 *
 * <h3>★ 老形象怎么搬过来（升级不能把用户的形象弄丢）</h3>
 * {@link #migrateLegacyIn} 把旧的全局文件**搬**（不是复制）进某个角色的目录，
 * 并按旧的 {@code petMode} 口径决定用哪一种；幂等（搬完旧文件就没了、且已有
 * {@code appearance.json} 时直接跳过）。
 */
public final class AppearanceStore {

    private static final String DIR = "appearance";
    private static final String F_JSON = "appearance.json";
    private static final String D_MODEL = "model";
    private static final String D_L2D = "l2d";
    /** 素材用**固定名**（与旧的 custom_pet.png / custom_pet_anim.gif 同风格）：重传即替换 */
    private static final String N_IMAGE = "image.png";
    private static final String N_ANIM = "anim.gif";

    // 旧全局素材（P5 之前的位置）—— 只用于迁移，运行期不再读写
    private static final String LEGACY_IMAGE = "custom_pet.png";
    private static final String LEGACY_ANIM = "custom_pet_anim.gif";
    private static final String LEGACY_MODEL = "custom_pet_model";
    private static final String LEGACY_L2D = "custom_l2d_model";

    private AppearanceStore() {}

    // ==================== 路径 ====================

    /** 形象根目录：{@code filesDir/appearance}（**不创建**） */
    static File rootIn(File filesDir) {
        return new File(filesDir, DIR);
    }

    /**
     * 某个角色的形象目录：{@code filesDir/appearance/<净化后的角色 id>/}。
     * <p>★ <b>不 mkdirs</b> —— 读/删路径上建目录会留下空壳（见类注释）。
     */
    static File dirIn(File filesDir, String charId) {
        return new File(rootIn(filesDir), ActionMenuConfig.sanitize(charId));
    }

    /** 写入前才建的版本（唯一会建目录的入口） */
    static File ensureDirIn(File filesDir, String charId) {
        File d = dirIn(filesDir, charId);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static File dirFor(Context ctx, String charId) {
        return dirIn(ctx.getFilesDir(), charId);
    }

    static File jsonIn(File filesDir, String charId) {
        return new File(dirIn(filesDir, charId), F_JSON);
    }

    static File imageIn(File filesDir, String charId) {
        return new File(dirIn(filesDir, charId), N_IMAGE);
    }

    static File animIn(File filesDir, String charId) {
        return new File(dirIn(filesDir, charId), N_ANIM);
    }

    static File modelDirIn(File filesDir, String charId) {
        return new File(dirIn(filesDir, charId), D_MODEL);
    }

    static File l2dDirIn(File filesDir, String charId) {
        return new File(dirIn(filesDir, charId), D_L2D);
    }

    // ———— 当前角色的便捷入口（{@link Prefs} 的门面就靠这几个）————

    static File imageCurrent(Context ctx) {
        return imageIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
    }

    static File animCurrent(Context ctx) {
        return animIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
    }

    static File modelDirCurrent(Context ctx) {
        return modelDirIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
    }

    static File l2dDirCurrent(Context ctx) {
        return l2dDirIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
    }

    // ———— 写入口专用：**建目录**的那一版 ————
    //
    // ★ 分工：`imageCurrent` 这类是**读/算路径**用的（绝不建目录），下面这四个是
    //   `Prefs.customImageFile(ctx)` 这类**写目标**用的 —— 调用方拿到路径就 new
    //   FileOutputStream，目录不在就会写失败（而旧实现的 filesDir 永远在，所以
    //   老代码没有"先建目录"这一步）。建目录只在这里发生，删除路径永远走 dirIn。

    static File ensureImageCurrent(Context ctx) {
        ensureDirIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
        return imageCurrent(ctx);
    }

    static File ensureAnimCurrent(Context ctx) {
        ensureDirIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
        return animCurrent(ctx);
    }

    static File ensureModelDirCurrent(Context ctx) {
        ensureDirIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
        return modelDirCurrent(ctx);
    }

    static File ensureL2dDirCurrent(Context ctx) {
        ensureDirIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
        return l2dDirCurrent(ctx);
    }

    // ==================== 素材在不在 ====================

    static boolean hasImageIn(File filesDir, String charId) {
        return imageIn(filesDir, charId).isFile();
    }

    static boolean hasAnimIn(File filesDir, String charId) {
        return animIn(filesDir, charId).isFile();
    }

    static boolean hasModelIn(File filesDir, String charId) {
        return modelDirIn(filesDir, charId).isDirectory();
    }

    /** Live2D 要能驱动才算数（与旧的 {@code useCustomL2D} 同一判据：meta.json 在） */
    static boolean hasL2dIn(File filesDir, String charId) {
        return new File(l2dDirIn(filesDir, charId), "meta.json").isFile();
    }

    static boolean hasImage(Context ctx) { return hasImageIn(ctx.getFilesDir(), CharacterStore.currentId(ctx)); }
    static boolean hasAnim(Context ctx)  { return hasAnimIn(ctx.getFilesDir(), CharacterStore.currentId(ctx)); }
    static boolean hasModel(Context ctx) { return hasModelIn(ctx.getFilesDir(), CharacterStore.currentId(ctx)); }
    static boolean hasL2d(Context ctx)   { return hasL2dIn(ctx.getFilesDir(), CharacterStore.currentId(ctx)); }

    // ==================== 来源读写 ====================

    /** 这个角色配置的形象来源（没配置 / 读不出来 ⇒ 内置） */
    static String sourceIn(File filesDir, String charId) {
        File f = jsonIn(filesDir, charId);
        if (!f.isFile()) return Appearance.DEFAULT;
        try {
            byte[] b = new byte[(int) f.length()];
            InputStream in = new FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r <= 0) break;
                off += r;
            }
            in.close();
            JSONObject j = new JSONObject(new String(b, "UTF-8"));
            return Appearance.normalize(j.optString("source", Appearance.DEFAULT));
        } catch (Throwable ignore) {
            return Appearance.DEFAULT;
        }
    }

    /** 配置某个角色的形象来源（写 json；目录按需创建） */
    static void setSourceIn(File filesDir, String charId, String src) {
        String s = Appearance.normalize(src);
        try {
            ensureDirIn(filesDir, charId);
            JSONObject j = new JSONObject();
            j.put("version", 1);
            j.put("source", s);
            OutputStream os = new FileOutputStream(jsonIn(filesDir, charId));
            os.write(j.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
        } catch (Throwable t) {
            android.util.Log.w("DSPAppear", "写形象来源失败 char=" + charId + " src=" + s + ": " + t);
        }
    }

    /** 当前角色配置的形象来源 */
    static String sourceFor(Context ctx) {
        return sourceIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
    }

    static void setSourceFor(Context ctx, String src) {
        setSourceIn(ctx.getFilesDir(), CharacterStore.currentId(ctx), src);
    }

    /**
     * 当前角色**真正该用**的来源（素材缺失就回退内置）。
     * <p>返回的字符串同时也是 {@code FloatingService.applyPetDrawable} 的 mode。
     */
    static String modeIn(File filesDir, String charId) {
        return Appearance.resolve(sourceIn(filesDir, charId),
                hasImageIn(filesDir, charId), hasAnimIn(filesDir, charId),
                hasModelIn(filesDir, charId), hasL2dIn(filesDir, charId));
    }

    static String modeFor(Context ctx) {
        return modeIn(ctx.getFilesDir(), CharacterStore.currentId(ctx));
    }

    /**
     * 把某个来源**关掉**：只有它正好是当前在用的那个才回内置
     * （判据在 {@link Appearance#afterDisable}，这里只负责落盘）。
     */
    static void disableFor(Context ctx, String which) {
        disableIn(ctx.getFilesDir(), CharacterStore.currentId(ctx), which);
    }

    /** {@link #disableFor} 的 Context-free 版本（离线断言用） */
    static void disableIn(File filesDir, String charId, String which) {
        String cur = sourceIn(filesDir, charId);
        String next = Appearance.afterDisable(cur, which);
        if (!next.equals(cur)) setSourceIn(filesDir, charId, next);
    }

    // ==================== 恢复默认 / 删除 ====================

    /** 某个角色是否已经有形象目录（迁移的幂等判据之一） */
    static boolean configuredIn(File filesDir, String charId) {
        return jsonIn(filesDir, charId).isFile();
    }

    /**
     * 「恢复默认形象」：回到内置，并**删掉这个角色上传的素材**
     * （只留一个空目录会让用户以为"还有东西"；素材也不该悄悄占着空间）。
     */
    public static void clearFor(Context ctx, String charId) {
        clearIn(ctx.getFilesDir(), charId);
    }

    /** {@link #clearFor} 的 Context-free 版本（离线断言用） */
    static void clearIn(File filesDir, String charId) {
        try { imageIn(filesDir, charId).delete(); } catch (Throwable ignore) {}
        try { animIn(filesDir, charId).delete(); } catch (Throwable ignore) {}
        try { PetModel.deleteRecursive(modelDirIn(filesDir, charId)); } catch (Throwable ignore) {}
        try { PetModel.deleteRecursive(l2dDirIn(filesDir, charId)); } catch (Throwable ignore) {}
        try { jsonIn(filesDir, charId).delete(); } catch (Throwable ignore) {}
        // 清完若只剩一个空壳目录，一并收掉（否则 files/appearance/ 下会攒一堆空目录）
        try {
            File d = dirIn(filesDir, charId);
            File[] rest = d.listFiles();
            if (d.isDirectory() && (rest == null || rest.length == 0)) d.delete();
        } catch (Throwable ignore) {}
    }

    /** 角色被删掉时连形象目录一起清（见 {@link CharacterStore#delete}） */
    public static void deleteFor(Context ctx, String charId) {
        deleteIn(ctx.getFilesDir(), charId);
    }

    /** {@link #deleteFor} 的 Context-free 版本（离线断言用） */
    static void deleteIn(File filesDir, String charId) {
        try { PetModel.deleteRecursive(dirIn(filesDir, charId)); } catch (Throwable ignore) {}
    }

    // ==================== 老形象迁移（P5 升级路径）====================

    /**
     * 把 P5 之前的**全局**形象素材搬进某个角色的形象目录。
     *
     * @param legacySource 旧的 {@code petMode} 口径（{@link Prefs#legacyPetMode}）：
     *                     决定搬完之后**用哪一种**；没有可用形象时传 {@code builtin}
     * @return 实际搬了几样素材（0 = 没有老形象，或已经搬过了）
     */
    static int migrateLegacyIn(File filesDir, String charId, String legacySource) {
        if (charId == null || charId.trim().isEmpty()) return 0;
        // ★ 旧口径是"内置"（用户当时没在用自定义形象）⇒ 一样都不搬：
        //   把用户**没启用**的旧文件搬过来会凭空"复活"一个形象（他升级后看到的脸变了）。
        if (Appearance.BUILTIN.equals(Appearance.normalize(legacySource))) return 0;
        // ★ 幂等：这个角色已经配置过形象就不再迁移
        //   （否则用户"恢复默认形象"之后，下次启动又被老文件搬回来一次）
        if (configuredIn(filesDir, charId)) return 0;

        File l2d = new File(filesDir, LEGACY_L2D);
        File model = new File(filesDir, LEGACY_MODEL);
        File anim = new File(filesDir, LEGACY_ANIM);
        File img = new File(filesDir, LEGACY_IMAGE);

        boolean any = (l2d.isDirectory() && new File(l2d, "meta.json").isFile())
                || model.isDirectory() || anim.isFile() || img.isFile();
        if (!any) return 0;

        ensureDirIn(filesDir, charId);
        int moved = 0;
        // 四样都搬（素材不留在旧的全局位置，否则下次启动还会被判成"有老形象"）
        if (l2d.isDirectory())  { if (moveDir(l2d, l2dDirIn(filesDir, charId))) moved++; }
        if (model.isDirectory()) { if (moveDir(model, modelDirIn(filesDir, charId))) moved++; }
        if (anim.isFile())      { if (moveFile(anim, animIn(filesDir, charId))) moved++; }
        if (img.isFile())       { if (moveFile(img, imageIn(filesDir, charId))) moved++; }

        // 来源按旧口径；但素材没搬成的话 resolve 会在运行期回退内置，不会留空白
        String src = Appearance.normalize(legacySource);
        if (!Appearance.available(src, hasImageIn(filesDir, charId), hasAnimIn(filesDir, charId),
                hasModelIn(filesDir, charId), hasL2dIn(filesDir, charId))) {
            src = Appearance.DEFAULT;
        }
        setSourceIn(filesDir, charId, src);
        android.util.Log.i("DSPAppear", "迁移老形象 -> char=" + charId
                + " 搬了 " + moved + " 样，来源=" + src);
        return moved;
    }

    /** 搬文件（rename 优先，跨设备/权限失败就复制） */
    static boolean moveFile(File from, File to) {
        if (from == null || to == null || !from.isFile()) return false;
        try {
            File parent = to.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            if (to.exists()) to.delete();
            if (from.renameTo(to)) return true;
            InputStream in = new FileInputStream(from);
            OutputStream out = new FileOutputStream(to);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            in.close();
            from.delete();
            return true;
        } catch (Throwable t) {
            android.util.Log.w("DSPAppear", "搬素材失败 " + from + " -> " + to + ": " + t);
            return false;
        }
    }

    /** 搬目录（逐个文件搬，`renameTo` 对目录在部分 ROM 上不可靠） */
    static boolean moveDir(File from, File to) {
        if (from == null || to == null || !from.isDirectory()) return false;
        if (!to.isDirectory() && !to.mkdirs()) return false;
        File[] fs = from.listFiles();
        boolean ok = true;
        if (fs != null) {
            for (File f : fs) {
                if (f == null) continue;
                if (f.isDirectory()) ok = moveDir(f, new File(to, f.getName())) && ok;
                else ok = moveFile(f, new File(to, f.getName())) && ok;
            }
        }
        try {
            File[] rest = from.listFiles();
            if (rest == null || rest.length == 0) from.delete();
        } catch (Throwable ignore) {}
        return ok;
    }
}
