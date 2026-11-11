package com.deepseek.pet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ 离线断言：用户覆盖层 + "把新动作**追加**进某个场景"的真实行为。
 *
 * <p>为什么必须离线跑：用户的两条追加要求（「添加动作」/「场景内导入动画」）最容易犯的错是
 * **用新清单整场替换** —— 用户调好的 ×N 被抹掉、角色包原有的绑定被丢、重名把角色包动作
 * 静默盖掉。这些在设备上只能一个个点着试，而在桌面 JVM 上跑真代码可以穷举。
 *
 * <p>这里用到 {@link SceneBindings} 的 {@code *In(File, …)} 版本 —— 与 Context 版本是
 * **同一份实现**（Context 版本只是转发），所以断言的就是产品路径上的那套逻辑。
 *
 * <p>用法：{@code java com.deepseek.pet.BindingsCheck <工作根>}
 */
public class BindingsCheck {

    static int fails = 0;
    static int total = 0;

    public static void main(String[] args) throws Exception {
        File root = new File(args[0], "bindings");
        del(root);
        root.mkdirs();
        File files = new File(root, "files");
        files.mkdirs();

        // ① appendBinds：保留已有权重、去重、保序、空输入不崩
        List<SceneBindings.Bind> cur = new ArrayList<SceneBindings.Bind>();
        cur.add(new SceneBindings.Bind("A", 3));
        cur.add(new SceneBindings.Bind("B", 1));
        List<String> added = new ArrayList<String>();
        added.add("B");
        added.add("C");
        List<SceneBindings.Bind> merged = SceneBindings.appendBinds(cur, added);
        ok("追加保留已有权重（A 仍是 ×3）", weight(merged, "A") == 3, "A=" + weight(merged, "A"));
        ok("追加不重复（B 只出现一次）", count(merged, "B") == 1, "次数=" + count(merged, "B"));
        ok("新动作按权重 1 追加（C）", weight(merged, "C") == 1);
        ok("顺序：原有在前、新增在后",
                "A,B,C".equals(order(merged)), order(merged));
        ok("cur 为 null 时不崩（退化成：只有新动作）",
                SceneBindings.appendBinds(null, added).size() == 2);
        ok("added 为空时原样返回", SceneBindings.appendBinds(cur, null).size() == 2);
        ok("空名字被丢掉", SceneBindings.appendBinds(cur, list("", null)).size() == 2);

        // ② 真落盘 + 真读回（覆盖层：场景 → 绑定，权重写法两种）
        SceneBindings.setBindsIn(files, "rt_test", "drag", merged);
        List<SceneBindings.Bind> back = SceneBindings.bindsOfIn(files, "rt_test", "drag");
        ok("落盘再读回：条数与权重都在",
                back != null && back.size() == 3 && weight(back, "A") == 3 && weight(back, "C") == 1,
                back == null ? "null" : order(back) + " A=" + weight(back, "A"));
        ok("没改过的场景返回 null（调用方按：回退角色包 处理）",
                SceneBindings.bindsOfIn(files, "rt_test", "idle") == null);
        ok("覆盖计数只有被改过的场景",
                SceneBindings.overridesIn(files, "rt_test").size() == 1,
                "count=" + SceneBindings.overridesIn(files, "rt_test").size());

        // ③ 事件场景写进 events 节点（ActionSystem 是两张表）
        SceneBindings.setBindsIn(files, "rt_test", "whisper", binds("碎碎念动作"));
        String raw = read(new File(new File(files, "scenes"), "rt_test.json"));
        ok("★ 事件场景写进 events 节点（不是 scenes）",
                raw.contains("\"events\"") && raw.indexOf("\"whisper\"") > raw.indexOf("\"events\""),
                raw.replace('\n', ' '));

        // ④ 按角色隔离：另一个角色的覆盖互不影响
        SceneBindings.setBindsIn(files, "other", "drag", binds("别人的动作"));
        ok("★ 覆盖按角色隔离（charId 不同 ⇒ 文件不同）",
                SceneBindings.bindsOfIn(files, "other", "whisper") == null
                        && "别人的动作".equals(SceneBindings.clipsOfIn(files, "other", "drag").get(0)),
                String.valueOf(SceneBindings.clipsOfIn(files, "other", "drag")));

        // ⑤ 用户素材目录：路径约定 + 动作名（重名判断要用它）
        File clips = SceneBindings.clipsDirIn(files, "builtin:default");
        write(new File(clips, "我的猫.png"), "x");
        write(new File(clips, "另一个.webp"), "x");
        write(new File(clips, "忽略我.txt"), "x");
        ok("★ 用户素材目录 = filesDir/scenes/<净化 id>/clips（内置角色一视同仁）",
                clips.getAbsolutePath().replace('\\', '/').endsWith("files/scenes/builtin_default/clips"),
                clips.getAbsolutePath());
        ok("用户素材目录只数图片、返回干净的动作名",
                SceneBindings.userClipIdsIn(files, "builtin:default").size() == 2
                        && SceneBindings.userClipIdsIn(files, "builtin:default").contains("我的猫"),
                String.valueOf(SceneBindings.userClipIdsIn(files, "builtin:default")));

        // ⑥ 删角色的"清素材"这一步：必须连**空容器目录**一起清掉，且**不能凭空造目录**
        //
        //   ★ 产品 bug 复现（P3 设备实测抓到）：删除实现原来调的是 clipsDirFor(...)，
        //     而它会 mkdirs() —— 于是"删角色"先建出 files/scenes/<id>/clips、
        //     删掉 clips 之后留下一个空的 files/scenes/<id>/ 壳。
        //     用户看到的是"删了角色，scenes 目录下还挂着一个同名空目录"。
        File fake = new File(new File(new File(files, "scenes"), "victim"), "clips");
        write(new File(fake, "a.png"), "x");
        write(new File(fake, "b.webp"), "x");
        ok("前置：victim 角色的用户素材已就位", fake.isDirectory()
                && new File(fake, "a.png").isFile());
        SceneBindings.deleteUserClipsIn(files, "victim");
        ok("★ 删角色：用户素材 clips/ 被清掉", !fake.exists());
        ok("★ 删角色：连空容器目录 files/scenes/victim/ 一起清掉（不留空壳）",
                !fake.getParentFile().exists(), fake.getParentFile().getAbsolutePath());

        File ghost = new File(new File(files, "scenes"), "ghost");
        SceneBindings.deleteUserClipsIn(files, "ghost");
        ok("★ 删一个**没有素材**的角色不会凭空造目录（clipsDirFor 会 mkdirs，删除路径不能用它）",
                !ghost.exists() && !new File(ghost, "clips").exists(),
                ghost.getAbsolutePath());

        System.out.println();
        if (fails > 0) {
            System.out.println(fails + " / " + total + " 项失败");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED (" + total + " 项)");
    }

    // ---------- 小工具 ----------

    static List<String> list(String... s) {
        List<String> l = new ArrayList<String>();
        for (String x : s) l.add(x);
        return l;
    }

    static List<SceneBindings.Bind> binds(String... s) {
        List<SceneBindings.Bind> l = new ArrayList<SceneBindings.Bind>();
        for (String x : s) l.add(new SceneBindings.Bind(x, 1));
        return l;
    }

    static int weight(List<SceneBindings.Bind> l, String id) {
        for (SceneBindings.Bind b : l) if (id.equals(b.id)) return b.weight;
        return 0;
    }

    static int count(List<SceneBindings.Bind> l, String id) {
        int n = 0;
        for (SceneBindings.Bind b : l) if (id.equals(b.id)) n++;
        return n;
    }

    static String order(List<SceneBindings.Bind> l) {
        StringBuilder sb = new StringBuilder();
        for (SceneBindings.Bind b : l) { if (sb.length() > 0) sb.append(','); sb.append(b.id); }
        return sb.toString();
    }

    static void ok(String name, boolean cond) { ok(name, cond, ""); }

    static void ok(String name, boolean cond, String detail) {
        total++;
        if (!cond) fails++;
        System.out.println("BIND|" + (cond ? "OK" : "FAIL") + "|" + name
                + (detail.isEmpty() ? "" : (" :: " + detail)));
    }

    static String read(File f) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    static void write(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        out.write(s.getBytes("UTF-8"));
        out.close();
    }

    static void del(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) del(c);
        f.delete();
    }
}
