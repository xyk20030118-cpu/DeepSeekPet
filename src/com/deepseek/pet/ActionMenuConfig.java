package com.deepseek.pet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 用户自定义的「动作菜单」分类（v7.0）。
 *
 * <h3>它解决什么</h3>
 * 三击桌宠弹出的动作菜单，分类以前是**按关键词硬编码**的（工作学习/吃东西/才艺表演…），
 * 用户没法把自己的动作归到自己想要的分类里。这里让用户自建分类（"我的最爱""摸鱼专用"…）
 * 并往里挑动作；自定义分类**排在菜单最前面**，已归入的动作不再出现在默认分类里（不重复）。
 *
 * <h3>存在哪</h3>
 * {@code filesDir/menus/<角色 id>.json} —— **按角色隔离**（切角色 = 换一套菜单分类），
 * 且内置角色（只读 assets）也能用（不写进角色包，写在应用私有目录）。
 *
 * <pre>{"version":1,"categories":[{"label":"我的最爱","clips":["吃Token","撸猫"]}]}</pre>
 */
public final class ActionMenuConfig {

    private static final String DIR = "menus";
    /** 分类数量上限（菜单是气泡，太多会滚动很长；超了直接不给加） */
    public static final int MAX_CATEGORIES = 12;

    private ActionMenuConfig() {}

    private static File fileFor(Context ctx, String charId) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return new File(d, sanitize(charId) + ".json");
    }

    /**
     * 角色 id 可能含 ':'（哨兵是 builtin:default）—— 落盘前统一净化。
     * <p>★ 轮次㉛ 起判据下沉到**纯类** {@link CharPicker#sanitizeId}（本类含 Android 依赖、
     * 进不了 fpstest）：这里只做转发，保证"角色 id 怎么净化"全工程仍然只有一份实现。
     */
    static String sanitize(String s) {
        return CharPicker.sanitizeId(s);
    }

    /**
     * 删掉某个角色的自定义菜单配置（删角色时一并清 —— 见 {@link CharacterStore#delete}）。
     * <p>不清的话，删掉角色再导入同名角色会"继承"上一份菜单，用户看不出原因。
     */
    public static void deleteFor(Context ctx, String charId) {
        try { fileFor(ctx, charId).delete(); } catch (Throwable ignore) {}
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
            o.put("categories", new JSONArray());
        } catch (Throwable ignore) {}
        return o;
    }

    /** 读配置（坏了/没有都返回空配置，绝不抛） */
    public static synchronized JSONObject load(Context ctx, String charId) {
        try {
            File f = fileFor(ctx, charId);
            if (!f.isFile() || f.length() <= 0) return emptyRoot();
            JSONObject o = new JSONObject(readText(f));
            if (o.optJSONArray("categories") == null) o.put("categories", new JSONArray());
            return o;
        } catch (Throwable t) {
            return emptyRoot();
        }
    }

    private static synchronized void save(Context ctx, String charId, JSONObject o) {
        try {
            writeText(fileFor(ctx, charId), o.toString(1));
        } catch (Throwable ignore) {}
    }

    /** 全部分类（保序）：分类名 → 动作名列表 */
    public static synchronized LinkedHashMap<String, List<String>> categories(Context ctx, String charId) {
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        JSONArray arr = load(ctx, charId).optJSONArray("categories");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String label = c.optString("label", "");
            if (label.isEmpty()) continue;
            List<String> clips = new ArrayList<>();
            JSONArray cs = c.optJSONArray("clips");
            if (cs != null) {
                for (int j = 0; j < cs.length(); j++) {
                    String n = cs.optString(j, null);
                    if (n != null && !n.isEmpty()) clips.add(n);
                }
            }
            out.put(label, clips);
        }
        return out;
    }

    public static synchronized boolean addCategory(Context ctx, String charId, String label) {
        if (label == null) return false;
        final String name = label.trim();
        if (name.isEmpty()) return false;
        JSONObject root = load(ctx, charId);
        JSONArray arr = root.optJSONArray("categories");
        if (arr == null) return false;
        if (arr.length() >= MAX_CATEGORIES) return false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c != null && name.equals(c.optString("label", ""))) return false;   // 重名
        }
        try {
            JSONObject c = new JSONObject();
            c.put("label", name);
            c.put("clips", new JSONArray());
            arr.put(c);
        } catch (Throwable t) {
            return false;
        }
        save(ctx, charId, root);
        return true;
    }

    public static synchronized void removeCategory(Context ctx, String charId, String label) {
        JSONObject root = load(ctx, charId);
        JSONArray arr = root.optJSONArray("categories");
        if (arr == null) return;
        JSONArray keep = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null || label == null || !label.equals(c.optString("label", ""))) keep.put(c);
        }
        try {
            root.put("categories", keep);
        } catch (Throwable ignore) {}
        save(ctx, charId, root);
    }

    /**
     * 按给定顺序**重排分类**（轮次㉚：拖拽排序）。
     *
     * <p>只动顺序、不动任何 {@code clips}；{@code labels} 里没提到的分类
     * 保持原相对顺序排在后面 —— 传进来的是一份可能过期的清单时，
     * 也绝不会把分类弄丢（拖拽对话框开着的时候用户是点不了别处的，
     * 但数据层不该依赖这个前提）。
     */
    public static synchronized void reorderCategories(Context ctx, String charId, List<String> labels) {
        JSONObject root = load(ctx, charId);
        JSONArray arr = root.optJSONArray("categories");
        if (arr == null) return;
        JSONArray out = new JSONArray();
        java.util.Set<String> used = new java.util.HashSet<String>();
        if (labels != null) {
            for (String label : labels) {
                if (label == null || used.contains(label)) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c != null && label.equals(c.optString("label", ""))) {
                        out.put(c);
                        used.add(label);
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            if (used.contains(c.optString("label", ""))) continue;
            out.put(c);
        }
        try {
            root.put("categories", out);
        } catch (Throwable ignore) {}
        save(ctx, charId, root);
    }

    public static synchronized void setClips(Context ctx, String charId, String label, List<String> clips) {
        JSONObject root = load(ctx, charId);
        JSONArray arr = root.optJSONArray("categories");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null || label == null || !label.equals(c.optString("label", ""))) continue;
            JSONArray cs = new JSONArray();
            if (clips != null) for (String n : clips) if (n != null && !n.isEmpty()) cs.put(n);
            try {
                c.put("clips", cs);
            } catch (Throwable ignore) {}
        }
        save(ctx, charId, root);
    }

    public static synchronized int count(Context ctx, String charId) {
        return categories(ctx, charId).size();
    }
}
