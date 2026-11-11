package com.deepseek.pet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 场景绑定的**用户覆盖层**（P1）。
 *
 * <h3>它解决什么</h3>
 * P0 之后"每个场景挂哪些动作"由**角色包**（{@code anim/pack.json}）决定，用户只能去改文件。
 * 这里让用户在设置页直接改：{@code 场景 → 改绑动作 / 调权重 / 预览 / 恢复角色包默认}。
 *
 * <h3>★ 为什么是"覆盖层"而不是"直接改角色包"</h3>
 * <ul>
 *   <li>角色包可能是**别人的**（导入的 zip），改它等于改别人的作品，也说不清"哪些是我改的"；</li>
 *   <li>覆盖层是**合并**上去的：只替换被改过的场景，其余场景照旧走角色包 ——
 *       所以用户改一个场景不会把另外 17 个弄丢（这条是 {@code ActionSystem.applyOverrides} 的语义）；</li>
 *   <li>"恢复角色包默认"就变成**删掉一条覆盖**，天然可逆、不需要备份原文件。</li>
 * </ul>
 *
 * <h3>存在哪</h3>
 * {@code filesDir/scenes/<角色 id>.json} —— **按角色隔离**（与 {@code ActionMenuConfig} 的
 * {@code filesDir/menus/<角色 id>.json} 同风格，连 id 净化都复用它），
 * 内置只读角色（{@code builtin:default}）同样能用 —— 覆盖层写在应用私有目录，不写进角色包。
 *
 * <p>★ 用户**新加的动作素材**也放在这一层：{@code filesDir/scenes/<角色 id>/clips/}
 * （见 {@link #clipsDirFor}）—— 绑定与它引用的素材同处一地，删目录就等于撤销全部自定义。
 *
 * <pre>{"version":1,
 *  "scenes":{"idle":[{"id":"待机呼吸休闲","weight":3}],"click":["蹭蹭你"]},
 *  "events":{"whisper":["碎碎念-发呆碎碎念"]}}</pre>
 *
 * 元素两种写法都吃：纯名字（权重 1）与 {@code {"id":…,"weight":…}}（与角色包 {@code pack.json} 一致）。
 */
public final class SceneBindings {

    private static final String DIR = "scenes";
    private static final String N_SCENES = "scenes";
    private static final String N_EVENTS = "events";

    /** 权重上限（防手滑写成天文数字，把其它动作饿死） */
    public static final int MAX_WEIGHT = 20;

    private SceneBindings() {}

    /** 一条覆盖绑定：动作名 + 权重（权重只在"随机抽取"的场景里起作用） */
    public static final class Bind {
        public final String id;
        public final int weight;
        public Bind(String id, int weight) {
            this.id = id;
            this.weight = clampWeight(weight);
        }
    }

    public static int clampWeight(int w) {
        if (w < 1) return 1;
        return Math.min(w, MAX_WEIGHT);
    }

    // ==================== 落盘 ====================

    /**
     * 覆盖层文件：{@code filesDir/scenes/<净化后的角色 id>.json}。
     * <p>净化直接复用 {@link ActionMenuConfig#sanitize(String)} —— 角色 id 可能含
     * {@code ':'}（内置角色是 {@code builtin:default}），两处若各写一份迟早会不一致。
     */
    public static File fileFor(Context ctx, String charId) {
        return fileIn(ctx.getFilesDir(), charId);
    }

    /** {@link #fileFor} 的 Context-free 版本（离线断言用：只要 filesDir 就行） */
    static File fileIn(File filesDir, String charId) {
        File d = scenesRoot(filesDir);
        return new File(d, ActionMenuConfig.sanitize(charId) + ".json");
    }

    /** 覆盖层根目录：{@code filesDir/scenes}（所有路径都从它派生，只有一份） */
    static File scenesRoot(File filesDir) {
        File d = new File(filesDir, DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * ★ 用户素材目录（与覆盖文件**同处用户覆盖层**）：
     * {@code filesDir/scenes/<净化后的角色 id>/clips/}。
     *
     * <h3>为什么不再往角色目录里塞</h3>
     * 用户角色当然有 {@code anim/clips/}，但**内置角色没有目录**（素材在 APK assets 里、只读）。
     * 一开始我给内置角色"落地"了一个同名用户角色目录，那会凭空多出一个假角色
     * （设置页的角色列表里冒出一条，P3 角色切换上线后更明显）。
     * 现在统一放在覆盖层里：**内置角色与用户角色一视同仁**，而且它天然是"用户自己的东西"
     * —— 清掉覆盖层目录就等于撤销全部自定义，不用去动角色包。
     *
     * <p>素材层按**当前角色**查这个目录，优先级高于角色包（见 {@link PetAnimPack}）：
     * 同名动作以用户加的为准。
     */
    public static File clipsDirFor(Context ctx, String charId) {
        return clipsDirIn(ctx.getFilesDir(), charId);
    }

    /** {@link #clipsDirFor} 的 Context-free 版本（离线断言用） */
    static File clipsDirIn(File filesDir, String charId) {
        File clips = new File(new File(scenesRoot(filesDir), ActionMenuConfig.sanitize(charId)), "clips");
        if (!clips.exists()) clips.mkdirs();
        return clips;
    }

    /**
     * 用户素材目录 —— <b>不创建</b>的那个版本。
     *
     * <p>★ 为什么必须有它：{@link #clipsDirFor} 会 {@code mkdirs()}（读路径时"顺手建目录"很方便），
     * 但**删除路径**上用它就成了 bug —— 删角色的实现里调一次 clipsDirFor，
     * 就会先把目录凭空建出来、再删掉里面的 clips，最后在 {@code files/scenes/} 下
     * 留下一个空的 {@code <角色 id>/} 壳。实测就是这样：删完角色，
     * {@code files/scenes/p3test/} 还在（空目录），而"删干净"是删角色的承诺。
     */
    static File clipsDirNoCreate(File filesDir, String charId) {
        return new File(new File(scenesRoot(filesDir), ActionMenuConfig.sanitize(charId)), "clips");
    }

    /**
     * 删掉某个角色的用户素材（连同它的**空容器目录**）—— 删角色时用。
     * <p>只删 {@code clips/} 会在 {@code files/scenes/} 下留一个空壳目录，
     * 看起来"没删干净"（而且下次导入同名角色时它会和旧目录混在一起）。
     */
    public static void deleteUserClips(Context ctx, String charId) {
        deleteUserClipsIn(ctx.getFilesDir(), charId);
    }

    /** {@link #deleteUserClips} 的 Context-free 版本（离线断言用） */
    static void deleteUserClipsIn(File filesDir, String charId) {
        File clips = clipsDirNoCreate(filesDir, charId);
        PetModel.deleteRecursive(clips);
        File holder = clips.getParentFile();
        try {
            File[] rest = (holder == null) ? null : holder.listFiles();
            if (holder != null && holder.isDirectory() && (rest == null || rest.length == 0)) {
                holder.delete();
            }
        } catch (Throwable ignore) {}
    }

    /** 用户素材目录里已有的动作名（去扩展名、去重、排序）——重名判断要用它 */
    public static List<String> userClipIds(Context ctx, String charId) {
        return userClipIdsIn(ctx.getFilesDir(), charId);
    }

    /** {@link #userClipIds} 的 Context-free 版本（离线断言用） */
    static List<String> userClipIdsIn(File filesDir, String charId) {
        return clipIdsIn(clipsDirIn(filesDir, charId));
    }

    /** 一个目录里的动作名（去扩展名、去重、排序） */
    static List<String> clipIdsIn(File dir) {
        List<String> out = new ArrayList<>();
        File[] fs = dir.listFiles();
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
     * 把一批新动作**追加**到某个场景的绑定里（保留已有权重、去重、保序）。
     *
     * <p>★ 抽成静态纯函数是为了能离线断言：这里最容易犯的错是"用新清单整场替换"
     * （用户调好的 ×N 被抹掉、角色包原有的绑定被丢），而在设备上只能靠一个个点着看。
     * 调用方：设置页「添加动作」与「导入动画到此场景」。
     *
     * @param cur   该场景**当前生效**的绑定（角色包默认或用户覆盖），null 视为空
     * @param added 要追加的动作名（去重；权重按 1）
     */
    public static List<Bind> appendBinds(List<Bind> cur, List<String> added) {
        List<Bind> out = new ArrayList<>();
        if (cur != null) {
            for (Bind b : cur) if (b != null && b.id != null && !b.id.isEmpty()) out.add(b);
        }
        if (added != null) {
            for (String id : added) {
                if (id == null || id.isEmpty()) continue;
                boolean has = false;
                for (Bind b : out) if (id.equals(b.id)) { has = true; break; }
                if (!has) out.add(new Bind(id, 1));
            }
        }
        return out;
    }

    private static String readText(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeText(File f, String s) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(s.getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignore) {}
    }

    private static JSONObject emptyRoot() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", 1);
            o.put(N_SCENES, new JSONObject());
            o.put(N_EVENTS, new JSONObject());
        } catch (Throwable ignore) {}
        return o;
    }

    /** 读覆盖层（坏了/没有都返回空配置，绝不抛） */
    public static synchronized JSONObject loadRoot(Context ctx, String charId) {
        return loadRootIn(ctx.getFilesDir(), charId);
    }

    /** {@link #loadRoot} 的 Context-free 版本（离线断言用）；下面所有 {@code *In} 同理 */
    static synchronized JSONObject loadRootIn(File filesDir, String charId) {
        try {
            File f = fileIn(filesDir, charId);
            if (!f.isFile() || f.length() <= 0) return emptyRoot();
            JSONObject o = new JSONObject(readText(f));
            if (o.optJSONObject(N_SCENES) == null) o.put(N_SCENES, new JSONObject());
            if (o.optJSONObject(N_EVENTS) == null) o.put(N_EVENTS, new JSONObject());
            return o;
        } catch (Throwable t) {
            return emptyRoot();
        }
    }

    private static synchronized void save(Context ctx, String charId, JSONObject o) {
        saveIn(ctx.getFilesDir(), charId, o);
    }

    private static synchronized void saveIn(File filesDir, String charId, JSONObject o) {
        try {
            writeText(fileIn(filesDir, charId), o.toString(1));
        } catch (Throwable ignore) {}
    }

    // ==================== 读 ====================

    /**
     * 全部覆盖（保持文件里的顺序）：场景 id → 绑定清单。
     * <p>事件场景（balance / whisper / workStatus）与状态场景合并进同一张表 ——
     * 对调用方来说"都是一条覆盖"，不必关心它写在哪个节点下。
     */
    public static synchronized LinkedHashMap<String, List<Bind>> overrides(Context ctx, String charId) {
        return overridesIn(ctx.getFilesDir(), charId);
    }

    static synchronized LinkedHashMap<String, List<Bind>> overridesIn(File filesDir, String charId) {
        LinkedHashMap<String, List<Bind>> out = new LinkedHashMap<>();
        JSONObject root = loadRootIn(filesDir, charId);
        collect(root.optJSONObject(N_SCENES), out);
        collect(root.optJSONObject(N_EVENTS), out);
        return out;
    }

    private static void collect(JSONObject obj, LinkedHashMap<String, List<Bind>> into) {
        if (obj == null) return;
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String scene = it.next();
            if (scene == null || scene.isEmpty()) continue;
            into.put(scene, parse(obj.opt(scene)));
        }
    }

    /** 一个场景的值 → 绑定清单（吃 简写数组 / {clips:[…]} / 元素为 {"id":…,"weight":…} 三种写法） */
    private static List<Bind> parse(Object v) {
        List<Bind> out = new ArrayList<>();
        JSONArray arr = null;
        if (v instanceof JSONArray) arr = (JSONArray) v;
        else if (v instanceof JSONObject) arr = ((JSONObject) v).optJSONArray("clips");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof String) {
                String s = (String) o;
                if (!s.isEmpty()) out.add(new Bind(s, 1));
            } else if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                String id = j.optString("id", j.optString("name", null));
                if (id != null && !id.isEmpty()) out.add(new Bind(id, j.optInt("weight", 1)));
            }
        }
        return out;
    }

    /** 某个场景的覆盖绑定；**返回 null = 这个场景没有覆盖**（调用方应按"回退角色包"处理） */
    public static synchronized List<Bind> bindsOf(Context ctx, String charId, String scene) {
        return bindsOfIn(ctx.getFilesDir(), charId, scene);
    }

    static synchronized List<Bind> bindsOfIn(File filesDir, String charId, String scene) {
        if (scene == null) return null;
        LinkedHashMap<String, List<Bind>> m = overridesIn(filesDir, charId);
        return m.get(scene);
    }

    /** {@link #bindsOf} 的取名版本；null = 没有覆盖 */
    public static synchronized List<String> clipsOf(Context ctx, String charId, String scene) {
        return clipsOfIn(ctx.getFilesDir(), charId, scene);
    }

    static synchronized List<String> clipsOfIn(File filesDir, String charId, String scene) {
        List<Bind> b = bindsOfIn(filesDir, charId, scene);
        if (b == null) return null;
        List<String> out = new ArrayList<>();
        for (Bind x : b) out.add(x.id);
        return out;
    }

    /** 覆盖里给某个动作设的权重；0 = 这个场景的覆盖里没有它（或整个场景没覆盖） */
    public static synchronized int weightOf(Context ctx, String charId, String scene, String clipId) {
        List<Bind> b = bindsOf(ctx, charId, scene);
        if (b == null || clipId == null) return 0;
        for (Bind x : b) if (clipId.equals(x.id)) return x.weight;
        return 0;
    }

    /** 这个场景被用户改过没有 */
    public static synchronized boolean isOverridden(Context ctx, String charId, String scene) {
        return bindsOf(ctx, charId, scene) != null;
    }

    /** 被改过的场景个数（设置页用它标"✎ 自定义"） */
    public static synchronized int overrideCount(Context ctx, String charId) {
        return overrides(ctx, charId).size();
    }

    // ==================== 写 ====================

    /**
     * 覆盖某个场景的绑定。
     * <p>★ 事件场景要写进 {@code events} 节点（{@code ActionSystem} 是两张表），其余写进 {@code scenes}。
     */
    public static synchronized void setBinds(Context ctx, String charId, String scene,
                                           List<Bind> binds) {
        setBindsIn(ctx.getFilesDir(), charId, scene, binds);
    }

    static synchronized void setBindsIn(File filesDir, String charId, String scene,
                                        List<Bind> binds) {
        if (scene == null || scene.isEmpty()) return;
        JSONObject root = loadRootIn(filesDir, charId);
        String node = SceneRegistry.isEvent(scene) ? N_EVENTS : N_SCENES;
        try {
            JSONObject obj = root.optJSONObject(node);
            if (obj == null) { obj = new JSONObject(); root.put(node, obj); }
            JSONArray arr = new JSONArray();
            if (binds != null) {
                for (Bind b : binds) {
                    if (b == null || b.id == null || b.id.isEmpty()) continue;
                    if (b.weight <= 1) {
                        arr.put(b.id);          // 权重 1 = 纯名字写法，文件更可读
                    } else {
                        JSONObject j = new JSONObject();
                        j.put("id", b.id);
                        j.put("weight", b.weight);
                        arr.put(j);
                    }
                }
            }
            obj.put(scene, arr);
        } catch (Throwable ignore) {}
        saveIn(filesDir, charId, root);
    }

    /** 便捷写法：全部按权重 1 覆盖（多选框"保存"走这条） */
    public static synchronized void setClips(Context ctx, String charId, String scene,
                                            List<String> clips) {
        List<Bind> binds = new ArrayList<>();
        if (clips != null) for (String s : clips) if (s != null && !s.isEmpty()) binds.add(new Bind(s, 1));
        setBinds(ctx, charId, scene, binds);
    }

    /** 只改一个动作的权重（该场景必须已有覆盖；没有覆盖时按"当前绑定"来不了 —— 调用方先 setBinds） */
    public static synchronized void setWeight(Context ctx, String charId, String scene,
                                             String clipId, int weight) {
        List<Bind> cur = bindsOf(ctx, charId, scene);
        if (cur == null || clipId == null) return;
        List<Bind> next = new ArrayList<>();
        boolean hit = false;
        for (Bind b : cur) {
            if (clipId.equals(b.id)) { next.add(new Bind(b.id, weight)); hit = true; }
            else next.add(b);
        }
        if (!hit) next.add(new Bind(clipId, weight));
        setBinds(ctx, charId, scene, next);
    }

    /** 恢复一个场景的角色包默认 = **删掉这条覆盖**（不是写一份"默认值"的快照 —— 那样角色包更新后就是旧的） */
    public static synchronized void clear(Context ctx, String charId, String scene) {
        if (scene == null || scene.isEmpty()) return;
        JSONObject root = loadRoot(ctx, charId);
        try {
            JSONObject obj = root.optJSONObject(SceneRegistry.isEvent(scene) ? N_EVENTS : N_SCENES);
            if (obj != null) obj.remove(scene);
        } catch (Throwable ignore) {}
        save(ctx, charId, root);
    }

    /** 清掉该角色的全部覆盖（"全部恢复角色包默认"） */
    public static synchronized void clearAll(Context ctx, String charId) {
        save(ctx, charId, emptyRoot());
    }
}
