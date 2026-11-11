package com.deepseek.pet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色仓库 —— 列出 / 切换 / **新增** / 删除角色。
 *
 * <h3>★ 轮次㉛ 起：不再预置任何角色</h3>
 * 用户要求「**不留任何角色，只靠导入**」+「可以新增角色」⇒
 * {@link #list} <b>只列用户自己新增或导入的角色，可以为空</b>；
 * 以前那个"内置默认角色"（{@link PetCharacter#BUILTIN_ID}）不再出现在列表里，
 * 它退化成两件事：
 * <ol>
 *   <li>prefs 里"**当前没有角色**"的哨兵值（{@link #currentId} 的默认值）；</li>
 *   <li>素材层的兜底（没有任何角色时，桌宠仍用 APK 里的内置素材显示 —— 见 {@link #current}）。</li>
 * </ol>
 * 于是"角色"这个概念只有一种：{@code filesDir/characters/<id>/} 下的真实目录，人人平等、都能删、都能导出。
 *
 * <p>当前角色这个唯一真相仍是 {@code Prefs.currentCharacterId}（{@code current_character}）。
 */
public final class CharacterStore {

    private CharacterStore() {}

    private static final String KEY_CURRENT = "current_character";

    /** 角色根目录：filesDir/characters/（用户角色） */
    public static File root(Context ctx) {
        File d = new File(ctx.getFilesDir(), "characters");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 某个用户角色的目录 */
    public static File dirOf(Context ctx, String id) {
        return new File(root(ctx), id);
    }

    /** 当前角色 id（默认内置） */
    public static String currentId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("deepseek_pet", Context.MODE_PRIVATE);
        return sp.getString(KEY_CURRENT, PetCharacter.BUILTIN_ID);
    }

    public static void setCurrentId(Context ctx, String id) {
        SharedPreferences sp = ctx.getSharedPreferences("deepseek_pet", Context.MODE_PRIVATE);
        sp.edit().putString(KEY_CURRENT, id == null ? PetCharacter.BUILTIN_ID : id).apply();
    }

    /**
     * 全部角色（**用户自己的**；一个都没有就返回空表 —— 轮次㉛ 起不再预置内置角色）。
     * <p>顺序 = 目录名排序（稳定，列表不会每次打开都换位置）。
     */
    public static List<PetCharacter> list(Context ctx) {
        List<PetCharacter> out = new ArrayList<>();
        File r = root(ctx);
        File[] ds = r.listFiles();
        if (ds != null) {
            java.util.Arrays.sort(ds);
            for (File d : ds) {
                if (!d.isDirectory()) continue;
                PetCharacter c = readExternal(ctx, d);
                if (c != null) out.add(c);
            }
        }
        return out;
    }

    /** 有没有角色（界面靠它决定显示"角色列表"还是"去新增/导入"的引导） */
    public static boolean hasAny(Context ctx) {
        return !list(ctx).isEmpty();
    }

    /** 全部角色 id（与 {@link #list} 同序） */
    public static List<String> listIds(Context ctx) {
        List<String> out = new ArrayList<>();
        for (PetCharacter c : list(ctx)) out.add(c.id);
        return out;
    }

    /**
     * 当前角色（**找不到就返回"空角色"**，绝不返回 null）。
     *
     * <p>★ 为什么是"空角色"而不是 null：全工程有几十处
     * {@code CharacterStore.current(this).displayName()}，返回 null 会让它们集体 NPE；
     * 而且"没有角色"在运行期本来就等价于"用内置素材兜底"（{@code dir == null}，
     * 素材层读 assets）。所以这里给一个**没有目录的合成角色**，语义清楚、调用方零改动。
     */
    public static PetCharacter current(Context ctx) {
        String id = currentId(ctx);
        if (PetCharacter.BUILTIN_ID.equals(id)) return none();
        File d = dirOf(ctx, id);
        PetCharacter c = d.isDirectory() ? readExternal(ctx, d) : null;
        return c != null ? c : none();
    }

    /** "没有角色"时的合成壳：没有目录（素材走 APK 内置），也不能删/不能导出 */
    public static PetCharacter none() {
        return new PetCharacter(PetCharacter.BUILTIN_ID, "（没有角色）", "", "", false, null);
    }

    // ★ 轮次㉛ 删掉了 builtin(Context)：内置角色不再是一个"角色"，
    //   它的元信息（assets/characters.json 里的名字/作者）已经没有任何调用方 ——
    //   留着一个没人用的"内置角色"工厂，下一个窗口会以为它还算一个角色。

    /** 读一个用户角色目录的 character.json（缺失也能用目录名兜底，方便手工放包调试） */
    private static PetCharacter readExternal(Context ctx, File dir) {
        File meta = new File(dir, PetCharacter.F_CHARACTER);
        String name = dir.getName(), author = "", ver = "";
        if (meta.isFile()) {
            try {
                byte[] b = new byte[(int) meta.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(meta);
                int off = 0;
                while (off < b.length) {
                    int r = in.read(b, off, b.length - off);
                    if (r <= 0) break;
                    off += r;
                }
                in.close();
                JSONObject j = new JSONObject(new String(b, "UTF-8"));
                name = j.optString("name", name);
                author = j.optString("author", "");
                ver = j.optString("version", "");
            } catch (Throwable ignore) {}
        }
        return new PetCharacter(dir.getName(), name, author, ver, false, dir);
    }

    // ==================== 新增（轮次㉛：可以新增角色）====================

    /**
     * 新增一个角色：建目录 + 写 {@code character.json}，**并立刻切成当前角色**。
     *
     * <p>用户要的流程是"新增角色 → 给它导入自定义动作 → 导出成角色包"，
     * 所以这里**不做"保存"这一步**（用户明确选了"不用单独保存"）：
     * 目录一建出来就是真的，后面 {@code ➕ 添加动作} / 形象导入都直接写进这个目录。
     *
     * <p>★ 新角色**没有动作**时不会是一片空白：素材层是"角色包优先、内置兜底"
     * （{@link PetAnimPack#charConfigFile()} + {@code mergeV2}），所以它先显示内置形象与待机动作，
     * 用户往里加的动作会一层层盖上去。
     *
     * @return 新角色；建不出来返回 null
     */
    public static PetCharacter create(Context ctx, String name) {
        String nm = CharPicker.clean(name);
        String id = CharPicker.newId(nm, listIds(ctx));
        File dir = dirOf(ctx, id);
        if (dir.exists() || !dir.mkdirs()) return null;
        boolean wrote = false;
        try {
            JSONObject j = new JSONObject();
            j.put("version", 1);
            j.put("id", id);
            j.put("name", nm);
            j.put("author", "");
            j.put("created", System.currentTimeMillis());
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new File(dir, PetCharacter.F_CHARACTER));
            out.write(j.toString(1).getBytes("UTF-8"));
            out.flush();
            out.close();
            wrote = true;
        } catch (Throwable t) {
            android.util.Log.w("DSPChar", "新增角色写 character.json 失败: " + t);
        }
        if (!wrote) {
            // ★ 写不成就不留半拉目录：否则列表里会多一个"名字是 id、内容全空"的鬼角色
            PetModel.deleteRecursive(dir);
            return null;
        }
        setCurrentId(ctx, id);
        android.util.Log.i("DSPChar", "新增角色 id=" + id + " name=" + nm);
        return readExternal(ctx, dir);
    }

    // ==================== 写入（App 内「添加动作」/ 场景导入，待办 0j 的 C）====================

    /**
     * 某个角色**角色包** {@code anim/clips/} 里已有的动作名（去扩展名、去重、排序）。
     * <p>内置角色没有目录 ⇒ 返回空（它的动作名要从素材层的可播名单里看）。
     */
    public static List<String> clipIds(PetCharacter ch) {
        List<String> out = new ArrayList<>();
        if (ch == null || ch.dir == null) return out;
        File[] fs = new File(ch.dir, PetCharacter.F_CLIPS).listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            if (!ClipNaming.isImageName(f.getName())) continue;
            String id = ClipNaming.baseNameOf(f.getName());
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * 把相册里选中的一个文件复制进**用户素材目录**
     * （{@link SceneBindings#clipsDirFor}，= {@code filesDir/scenes/<角色 id>/clips/}），
     * 返回落盘后的**动作名**（失败 null）。
     *
     * <p>★ 为什么不是写角色包的 {@code anim/clips/}：内置角色根本没有那个目录
     * （素材在 APK assets 里、只读）。放在覆盖层里内置/用户角色一视同仁，
     * 而且删掉它 = 撤销自定义，不动角色包一个字节。
     *
     * <p>★ 只复制、不转码（与视频背景同一取舍）。静态图（png/jpg）由 {@link PetAnimPack}
     * 当**单帧动作**播，GIF/WebP 仍按动图播。
     * <p>★ 重名一律加序号（{@link ClipNaming#uniqueId}），并且重名判断要**并上**角色包与
     * 有效配置里的动作名 —— 只比对用户目录的话，新动作会静默盖掉角色包里的同名动作
     * （素材层用户目录优先，盖掉之后表现成"角色包里的那个动作变了样"）。
     *
     * @param alsoTaken 还要避开的动作名（调用方一般传 {@code ActionSystem.allClipIds()}），可为 null
     */
    public static String addClipFromUri(Context ctx, PetCharacter ch, android.net.Uri uri,
                                        String displayName, String mime, List<String> alsoTaken) {
        if (ch == null || uri == null) return null;
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            String base = ClipNaming.safeClipId(displayName);
            if (base.isEmpty()) base = "新动作";
            String ext = ClipNaming.extFor(displayName, mime);
            File dir = SceneBindings.clipsDirFor(ctx, ch.id);
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            List<String> taken = new ArrayList<>();
            taken.addAll(SceneBindings.userClipIds(ctx, ch.id));
            for (String n : clipIds(ch)) if (!taken.contains(n)) taken.add(n);
            if (alsoTaken != null) for (String n : alsoTaken) {
                if (n != null && !n.isEmpty() && !taken.contains(n)) taken.add(n);
            }
            String id = ClipNaming.uniqueId(base, taken.toArray(new String[0]));
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            File dst = new File(dir, id + "." + ext);
            out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            if (total <= 0) { dst.delete(); return null; }
            android.util.Log.i("DSPChar", "添加动作 " + id + "." + ext + " " + total + " B -> " + dst);
            return id;
        } catch (Throwable t) {
            android.util.Log.w("DSPChar", "添加动作失败: " + t);
            return null;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) {}
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
        }
    }

    /** {@link #addClipFromUri} 的简写（不额外避让动作名） */
    public static String addClipFromUri(Context ctx, PetCharacter ch, android.net.Uri uri,
                                        String displayName, String mime) {
        return addClipFromUri(ctx, ch, uri, displayName, mime, null);
    }

    // ==================== 删除（P3 角色切换 UI）====================

    /**
     * 删掉一个角色（哨兵 {@link PetCharacter#BUILTIN_ID} 一律拒绝，见 {@link CharPicker#deletable}）。
     *
     * <p>★ 必须把**按角色派生的用户数据一起清掉**，否则会留下一堆孤儿：
     * <ul>
     *   <li>{@code characters/<id>/} —— 角色包本体（形象 + 动作 + 表情 + 性格）；</li>
     *   <li>{@code scenes/<id>.json} —— 这个角色改过的场景绑定（P1 的覆盖层）；</li>
     *   <li>{@code scenes/<id>/clips/} —— 用「➕ 添加动作」加进来的素材；
     *       ★ 轮次㉑ 起动作素材落在**这里**而不是角色包里；</li>
     *   <li>{@code menus/<id>.json} —— 自定义动作菜单。</li>
     *   <li>{@code appearance/<id>/} —— <b>P5</b>：这个角色自己的形象素材
     *       （上传的图片/动图/模型包/Live2D 包 + {@code appearance.json}）。
     *       不清的话，删掉角色再导入同名角色会连**上一份形象**一起继承。</li>
     * </ul>
     * 只删第一个的话，用户"删掉角色再重新导入同名角色"时会莫名其妙地继承上一份绑定与素材
     * （而且 {@code SceneBindings} 是按 id 取文件的，正好命中）。
     *
     * <p>★ 当前角色正好是被删的那个 ⇒ **回到内置**（见 {@link CharPicker#currentAfterDelete}）；
     * 否则 {@code currentId} 指向不存在的目录，重启后只能靠兜底，而 prefs 里那个悬空 id
     * 会一直留着。
     *
     * @return 真的删掉了才 true
     */
    public static boolean delete(Context ctx, String id) {
        if (!CharPicker.deletable(id)) return false;
        File dir = dirOf(ctx, id);
        boolean existed = dir.isDirectory();
        PetModel.deleteRecursive(dir);          // 该方法是 void（递归删，失败也不抛）
        boolean ok = existed && !dir.exists();
        try { SceneBindings.fileFor(ctx, id).delete(); } catch (Throwable ignore) {}
        // ★ 用 deleteUserClips 而不是 deleteRecursive(clipsDirFor(...))：
        //   clipsDirFor **会 mkdirs**，在删除路径上会把目录凭空建出来，
        //   结果留下一个空的 files/scenes/<id>/ 壳（实测踩到，见 SceneBindings.clipsDirNoCreate）。
        try { SceneBindings.deleteUserClips(ctx, id); } catch (Throwable ignore) {}
        try { ActionMenuConfig.deleteFor(ctx, id); } catch (Throwable ignore) {}
        // ★ P5：这个角色自己的形象素材（appearance/<id>/）—— 同上，不清就会继承上一份形象
        try { AppearanceStore.deleteFor(ctx, id); } catch (Throwable ignore) {}
        // ★ 轮次㉛：删完落到**剩下的第一个角色**；一个都不剩 ⇒ 回哨兵（= 没有角色，
        //   桌宠退回内置素材兜底）。见 CharPicker.currentAfterDelete 的说明。
        String next = CharPicker.currentAfterDelete(currentId(ctx), id, listIds(ctx));
        if (!next.equals(currentId(ctx))) setCurrentId(ctx, next);
        android.util.Log.i("DSPChar", "删除角色 id=" + id + " ok=" + ok
                + " 当前角色=" + next + " 剩余=" + listIds(ctx).size());
        return ok;
    }
}
