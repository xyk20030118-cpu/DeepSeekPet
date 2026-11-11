package com.deepseek.pet;

import java.io.File;

/**
 * ★ 离线断言：**形象来源按角色**的真实行为（P5）。
 *
 * <p>为什么必须离线跑：P5 最容易犯的错全是"文件系统层面"的，在设备上只能一个个点着试 ——
 * <ul>
 *   <li><b>读路径顺手建目录</b>：轮次㈡ 已经因此留过空壳目录（删除路径用了会 mkdirs 的函数）。
 *       P5 的读路径（{@code sourceIn / modeIn / hasImageIn}）也会在设置页每次刷新时被调到，
 *       一旦建目录，{@code files/appearance/} 下会攒出一堆空角色目录；</li>
 *   <li><b>按角色隔离</b>：A 角色传的图跑到 B 角色身上（这是 P5 要修的核心问题，
 *       反过来写错就是"隔离了个寂寞"）；</li>
 *   <li><b>关掉一个来源 ≠ 关掉全部</b>：老代码 {@code saveCustomPet} 是"先开 anim、
 *       再把 image 置 false"，顺序写反就变成"传了动图还是显示旧图片"；</li>
 *   <li><b>老形象迁移</b>：升级时把用户当时看到的形象搬进角色目录，且必须**幂等**
 *       （否则"恢复默认形象"之后下次启动又被搬回来）。</li>
 * </ul>
 *
 * <p>用的是 {@link AppearanceStore} 的 {@code *In(File, …)} 版本 —— 与 Context 版本是
 * **同一份实现**（Context 版本只是转发），所以断言的就是产品路径上的那套逻辑。
 *
 * <p>用法：{@code java com.deepseek.pet.AppearanceCheck <工作根>}
 */
public class AppearanceCheck {

    static int fails = 0;
    static int total = 0;

    public static void main(String[] args) throws Exception {
        File root = new File(args[0], "appearance");
        del(root);
        root.mkdirs();
        File files = new File(root, "files");
        files.mkdirs();

        final String A = "builtin:default";
        final String B = "p5test";

        // ① 路径约定：files/appearance/<净化后的角色 id>/
        File dirA = AppearanceStore.dirIn(files, A);
        ok("★ 形象目录 = filesDir/appearance/<净化 id>（内置角色一视同仁）",
                p(dirA).endsWith("files/appearance/builtin_default"), p(dirA));

        // ② ★ 读路径**不建目录**（P5 版"空壳目录"回归）
        ok("前置：此刻还没有任何形象目录", !dirA.exists());
        AppearanceStore.sourceIn(files, A);
        AppearanceStore.modeIn(files, A);
        AppearanceStore.hasImageIn(files, A);
        AppearanceStore.hasAnimIn(files, A);
        AppearanceStore.hasModelIn(files, A);
        AppearanceStore.hasL2dIn(files, A);
        AppearanceStore.configuredIn(files, A);
        ok("★ 读一遍来源/素材（设置页每次刷新都会调）不建目录",
                !dirA.exists() && !AppearanceStore.rootIn(files).exists(),
                p(AppearanceStore.rootIn(files)));
        ok("没配置过 ⇒ 内置", Appearance.BUILTIN.equals(AppearanceStore.sourceIn(files, A))
                && Appearance.BUILTIN.equals(AppearanceStore.modeIn(files, A)));

        // ③ 落盘 + 读回
        AppearanceStore.setSourceIn(files, A, Appearance.IMAGE);
        ok("写进去读回来", Appearance.IMAGE.equals(AppearanceStore.sourceIn(files, A)));
        ok("★ 脏值一律回内置（手改的 json / 老包不许让调用方拿到 null）",
                Appearance.BUILTIN.equals(AppearanceStore.sourceIn(files, "从没配过的角色")));
        write(AppearanceStore.jsonIn(files, B), "not a json {");
        ok("★ json 坏了也不崩、回内置", Appearance.BUILTIN.equals(AppearanceStore.sourceIn(files, B)));
        AppearanceStore.deleteIn(files, B);

        // ④ 素材缺失 ⇒ 回退内置（mode 是"真能播的那个"）
        ok("★ 选了图片但文件不在 ⇒ mode 回内置（不留空白）",
                Appearance.BUILTIN.equals(AppearanceStore.modeIn(files, A)));
        write(AppearanceStore.imageIn(files, A), "png");
        ok("素材到位 ⇒ mode 就是它", Appearance.IMAGE.equals(AppearanceStore.modeIn(files, A)));

        // ⑤ ★ 按角色隔离（P5 的核心）
        ok("★ 别的角色不受影响（B 还是内置，也没有 A 的图）",
                Appearance.BUILTIN.equals(AppearanceStore.modeIn(files, B))
                        && !AppearanceStore.hasImageIn(files, B));
        AppearanceStore.setSourceIn(files, B, Appearance.ANIM);
        write(AppearanceStore.animIn(files, B), "gif");
        ok("★ 两个角色各用各的（A=image / B=anim）",
                Appearance.IMAGE.equals(AppearanceStore.modeIn(files, A))
                        && Appearance.ANIM.equals(AppearanceStore.modeIn(files, B)));

        // ⑥ ★ 关掉一个来源 ≠ 关掉全部（老代码的顺序 bug）
        AppearanceStore.disableIn(files, B, Appearance.IMAGE);
        ok("★ 关掉**不是当前**的那个 ⇒ B 还在用动图（顺序无关）",
                Appearance.ANIM.equals(AppearanceStore.modeIn(files, B)),
                AppearanceStore.modeIn(files, B));
        AppearanceStore.disableIn(files, B, Appearance.ANIM);
        ok("★ 关掉的正好是当前那个 ⇒ 回内置",
                Appearance.BUILTIN.equals(AppearanceStore.modeIn(files, B)));
        ok("（动图文件还在 —— 关掉来源不等于删素材）",
                AppearanceStore.hasAnimIn(files, B));

        // ⑦ 恢复默认形象：素材 + json 一起清，且不留空壳目录
        AppearanceStore.clearIn(files, B);
        ok("★ 恢复默认：素材与 json 都清掉", !AppearanceStore.hasAnimIn(files, B)
                && !AppearanceStore.configuredIn(files, B));
        ok("★ 恢复默认：不留空壳目录", !AppearanceStore.dirIn(files, B).exists(),
                p(AppearanceStore.dirIn(files, B)));
        ok("（A 角色完全没被牵连）", AppearanceStore.hasImageIn(files, A)
                && Appearance.IMAGE.equals(AppearanceStore.modeIn(files, A)));

        // ⑧ 删角色：连形象目录一起清
        AppearanceStore.deleteIn(files, A);
        ok("★ 删角色 ⇒ 形象目录整个清掉", !AppearanceStore.dirIn(files, A).exists());
        ok("（清一个本来就不存在的角色不崩、也不造目录）",
                !AppearanceStore.dirIn(files, "ghost").exists());

        // ⑨ ★ 老形象迁移（升级路径）
        File f2 = new File(root, "files2");
        f2.mkdirs();
        write(new File(f2, "custom_pet.png"), "old image");
        write(new File(f2, "custom_pet_anim.gif"), "old anim");
        write(new File(f2, "custom_pet_model/idle_0.png"), "frame");
        write(new File(f2, "custom_l2d_model/meta.json"), "{}");
        ok("前置：四样老全局素材都在",
                new File(f2, "custom_pet.png").isFile()
                        && new File(f2, "custom_l2d_model/meta.json").isFile());

        int moved = AppearanceStore.migrateLegacyIn(f2, A, Appearance.ANIM);
        ok("★ 老形象搬进当前角色的形象目录（四样都搬）", moved == 4, "moved=" + moved);
        ok("★ 搬完用**旧的来源**（用户升级后看到的脸不变）",
                Appearance.ANIM.equals(AppearanceStore.modeIn(f2, A)),
                AppearanceStore.modeIn(f2, A));
        ok("素材真的落到角色目录里",
                AppearanceStore.hasImageIn(f2, A) && AppearanceStore.hasAnimIn(f2, A)
                        && AppearanceStore.hasModelIn(f2, A) && AppearanceStore.hasL2dIn(f2, A));
        ok("★ 旧位置的文件已经搬走（不会下次启动又被当成「有老形象」）",
                !new File(f2, "custom_pet.png").exists()
                        && !new File(f2, "custom_pet_anim.gif").exists()
                        && !new File(f2, "custom_l2d_model").exists());
        ok("★ 幂等：再迁一次什么都不做",
                AppearanceStore.migrateLegacyIn(f2, A, Appearance.ANIM) == 0);

        // 老口径是"内置"（用户当时没在用自定义形象）⇒ 一样都不搬
        File f3 = new File(root, "files3");
        f3.mkdirs();
        write(new File(f3, "custom_pet.png"), "stale");
        ok("★ 旧口径是内置 ⇒ 不搬（不凭空复活一个用户没启用的形象）",
                AppearanceStore.migrateLegacyIn(f3, A, Appearance.BUILTIN) == 0
                        && !AppearanceStore.configuredIn(f3, A));
        ok("（没搬的旧文件原地不动）", new File(f3, "custom_pet.png").isFile());

        // 已经配置过形象的角色不再被迁移覆盖
        File f4 = new File(root, "files4");
        f4.mkdirs();
        write(new File(f4, "custom_pet.png"), "old");
        AppearanceStore.setSourceIn(f4, A, Appearance.MODEL);
        ok("★ 已经配置过形象 ⇒ 迁移跳过（不覆盖用户现在的选择）",
                AppearanceStore.migrateLegacyIn(f4, A, Appearance.IMAGE) == 0
                        && Appearance.MODEL.equals(AppearanceStore.sourceIn(f4, A)));

        // ⑩ 恢复默认之后再启动：老文件已经没了 ⇒ 迁移不会把形象搬回来
        File f5 = new File(root, "files5");
        f5.mkdirs();
        write(new File(f5, "custom_pet.png"), "old");
        AppearanceStore.migrateLegacyIn(f5, A, Appearance.IMAGE);
        AppearanceStore.clearIn(f5, A);
        ok("★ 恢复默认形象 → 重启：不会又被老文件搬回来",
                AppearanceStore.migrateLegacyIn(f5, A, Appearance.IMAGE) == 0
                        && Appearance.BUILTIN.equals(AppearanceStore.modeIn(f5, A)));

        System.out.println();
        if (fails > 0) {
            System.out.println(fails + " / " + total + " 项失败");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED (" + total + " 项)");
    }

    // ---------- 小工具 ----------

    static String p(File f) {
        return f == null ? "null" : f.getAbsolutePath().replace('\\', '/');
    }

    static void ok(String name, boolean cond) { ok(name, cond, ""); }

    static void ok(String name, boolean cond, String detail) {
        total++;
        if (!cond) fails++;
        System.out.println("APPEAR|" + (cond ? "OK" : "FAIL") + "|" + name
                + (detail.isEmpty() ? "" : (" :: " + detail)));
    }

    static void write(File f, String s) throws Exception {
        File par = f.getParentFile();
        if (par != null && !par.exists()) par.mkdirs();
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
