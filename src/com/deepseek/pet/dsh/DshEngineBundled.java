package com.deepseek.pet.dsh;

import android.content.Context;

import java.io.IOException;

/**
 * 随包载荷：Linux 那套东西全在本 APK 的 {@code assets/engine} 里，用户不需要电脑/服务器。
 *
 * <h3>v8.1 起没有"安装第二个 App"这一步了</h3>
 * 以前要把随包的引擎 APK 用系统安装器装上（要"安装未知应用"权限、还要点两次）。
 * 引擎并进主 App 之后，{@link #install(Context)} 直接返回 true（无事可做），
 * 载荷由 {@link com.deepseek.pet.dsh.linux.Engine#installFromAssets} 从 assets 解开。
 */
public final class DshEngineBundled {

    private DshEngineBundled() { }

    /** 这只 APK 里带没带 Linux 载荷。 */
    public static boolean bundled(Context c) {
        try {
            String[] list = c.getAssets().list(DshEngine.ASSET_DIR);
            if (list == null) return false;
            boolean payload = false;
            boolean manifest = false;
            for (String n : list) {
                if (n.equals("manifest.json")) manifest = true;
                else if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) payload = true;
            }
            return payload && manifest;
        } catch (IOException e) {
            return false;
        }
    }

    /** 兼容旧调用：现在已经没有要安装的第二个包了。 */
    public static boolean install(Context c) {
        return bundled(c);
    }
}
