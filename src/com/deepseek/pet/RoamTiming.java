package com.deepseek.pet;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 漫游**前摇/后摇**的**按动作覆盖层**。
 *
 * <h3>它解决什么（轮次㊼ · 用户原话）</h3>
 * 「**漫游前后摇时间给的选项多一点 并且可以单独设置每个动作的前后摇用来适配不同角色不同动画的动作**」
 * —— 全局一个前摇/后摇挡不住这种差异：不是每个动作的"蓄力"都一样长，
 * 不同角色包里的动画更是各不相同（内置那个奔跑动作前 ~2 秒内容几乎不动、别的动作可能一开帧就在走）。
 * 所以这里加一层：{@code 动作名 → (前摇, 后摇)}，**按角色隔离**。
 *
 * <h3>解析顺序（FloatingService 里就按这个用）</h3>
 * <ol>
 *   <li>本动作在覆盖层里有记录 ⇒ 用它的；</li>
 *   <li>没有 ⇒ 用全局设置（{@code Prefs.wanderLeadMs / wanderTailMs}）。</li>
 * </ol>
 * ⇒ 用户只为"那个别扭的动作"单独调一次，其余动作继续吃全局值。
 *
 * <h3>存在哪</h3>
 * {@code filesDir/roam/<净化后的角色 id>.json} —— 与 {@code SceneBindings} 的
 * {@code filesDir/scenes/<角色 id>.json} **同风格**（连 id 净化都复用它），
 * 内置只读角色也能用（覆盖层写在应用私有目录里，不动角色包）。
 *
 * <pre>{"version":1,
 *  "clips":{"原地左转奔跑":{"lead":2000,"tail":1500},
 *           "螃蟹走路":{"lead":400,"tail":400}}}</pre>
 */
public final class RoamTiming {

    private static final String DIR = "roam";
    private static final String N_CLIPS = "clips";

    /** 单个延迟上限（ms）—— 与 {@code Prefs} 的夹紧一致（0~4 秒） */
    public static final int MAX_MS = 4000;

    private RoamTiming() {}

    /** 一个动作的前摇/后摇（ms） */
    public static final class Timing {
        public final int lead, tail;
        public Timing(int leadMs, int tailMs) {
            lead = clamp(leadMs);
            tail = clamp(tailMs);
        }
    }

    public static int clamp(int ms) {
        if (ms < 0) return 0;
        return Math.min(ms, MAX_MS);
    }

    // ==================== 纯解析（零 Android ⇒ 能在 fpstest 里做真断言）====================

    /** 解析覆盖层 JSON；坏数据一律跳过（绝不因为一行脏数据让漫游走不动） */
    public static LinkedHashMap<String, Timing> parse(JSONObject root) {
        LinkedHashMap<String, Timing> out = new LinkedHashMap<String, Timing>();
        if (root == null) return out;
        JSONObject clips = root.optJSONObject(N_CLIPS);
        if (clips == null) return out;
        Iterator<String> it = clips.keys();
        while (it.hasNext()) {
            String id = it.next();
            if (id == null || id.length() == 0) continue;
            JSONObject o = clips.optJSONObject(id);
            if (o == null) continue;
            // ★ 只认显式写了的字段：两个都没写 ⇒ 这条等于"没有覆盖"
            if (!o.has("lead") && !o.has("tail")) continue;
            int lead = o.optInt("lead", 1000);
            int tail = o.optInt("tail", 1000);
            out.put(id, new Timing(lead, tail));
        }
        return out;
    }

    /** 反向：覆盖层 → JSON（只写有覆盖的动作） */
    public static JSONObject toJson(Map<String, Timing> m) {
        JSONObject root = new JSONObject();
        JSONObject clips = new JSONObject();
        try {
            root.put("version", 1);
            if (m != null) {
                for (Map.Entry<String, Timing> e : m.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("lead", e.getValue().lead);
                    o.put("tail", e.getValue().tail);
                    clips.put(e.getKey(), o);
                }
            }
            root.put(N_CLIPS, clips);
        } catch (Throwable ignore) {}
        return root;
    }

    /** 取某个动作的覆盖（没有就 null ⇒ 调用方回退到全局值） */
    public static Timing pick(Map<String, Timing> m, String clipId) {
        if (m == null || clipId == null) return null;
        return m.get(clipId);
    }

    // ==================== 落盘 ====================

    public static File fileFor(Context ctx, String charId) {
        return fileIn(ctx.getFilesDir(), charId);
    }

    /** {@link #fileFor} 的 Context-free 版本（离线断言用） */
    static File fileIn(File filesDir, String charId) {
        return new File(root(filesDir), ActionMenuConfig.sanitize(charId) + ".json");
    }

    /** 覆盖层根目录：{@code filesDir/roam} */
    static File root(File filesDir) {
        File d = new File(filesDir, DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 读；没有文件/读坏了一律返回空表（= 全部走全局值） */
    public static LinkedHashMap<String, Timing> load(Context ctx, String charId) {
        return loadIn(ctx.getFilesDir(), charId);
    }

    /** {@link #load} 的 Context-free 版本（离线断言用） */
    static LinkedHashMap<String, Timing> loadIn(File filesDir, String charId) {
        File f = fileIn(filesDir, charId);
        if (!f.isFile()) return new LinkedHashMap<String, Timing>();
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String s = new String(bo.toByteArray(), "UTF-8").trim();
            if (s.length() == 0) return new LinkedHashMap<String, Timing>();
            return parse(new JSONObject(s));
        } catch (Throwable t) {
            return new LinkedHashMap<String, Timing>();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
        }
    }

    /** 写回（全量覆盖该角色的文件） */
    public static boolean save(Context ctx, String charId, Map<String, Timing> m) {
        return saveIn(ctx.getFilesDir(), charId, m);
    }

    /** {@link #save} 的 Context-free 版本（离线断言用） */
    static boolean saveIn(File filesDir, String charId, Map<String, Timing> m) {
        File f = fileIn(filesDir, charId);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(toJson(m).toString().getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) {}
        }
    }

    /** 给某个动作设前后摇；等于全局默认值时**删掉这条**（覆盖层只留"真的不一样"的） */
    public static boolean set(Context ctx, String charId, String clipId,
                              int lead, int tail) {
        LinkedHashMap<String, Timing> m = load(ctx, charId);
        if (clipId == null || clipId.length() == 0) return false;
        m.put(clipId, new Timing(lead, tail));
        return save(ctx, charId, m);
    }

    /** 撤销某个动作的覆盖（回到全局值） */
    public static boolean clear(Context ctx, String charId, String clipId) {
        LinkedHashMap<String, Timing> m = load(ctx, charId);
        if (m.remove(clipId) == null) return false;
        if (m.isEmpty()) {
            // 空表就不留文件（否则"已自定义"的判据会永远为真）
            File f = fileFor(ctx, charId);
            return !f.exists() || f.delete();
        }
        return save(ctx, charId, m);
    }

    /** 撤销该角色的全部覆盖 */
    public static boolean clearAll(Context ctx, String charId) {
        File f = fileFor(ctx, charId);
        return !f.exists() || f.delete();
    }

    /** 该角色有多少个动作被单独设过（设备断言/界面提示用） */
    public static int count(Context ctx, String charId) {
        return load(ctx, charId).size();
    }
}
