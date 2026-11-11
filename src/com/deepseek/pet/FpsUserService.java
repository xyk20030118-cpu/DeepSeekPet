package com.deepseek.pet;

import android.content.Context;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Shizuku UserService 实现 —— 本类的方法**运行在 shell UID 的进程里**。
 *
 * <p>这是 {@code Shizuku.newProcess} 被移除后的官方替代路径：
 * 用 {@code Shizuku.bindUserService()} 让 Shizuku 以 shell 身份拉起本类，
 * 于是类里的 {@code Runtime.exec()} 天然就是 shell 权限，
 * 可以直接跑 {@code dumpsys SurfaceFlinger --latency}。
 *
 * <p>★ 为什么不用 {@code SystemServiceHelper} 直接 transact SurfaceFlinger：
 * 那条路要硬编码隐藏 AIDL 的事务码和 Parcel 格式，随 ROM/版本极易碎。
 * 走 shell 执行 {@code dumpsys} 稳定得多，且输出格式有官方语义。
 *
 * <p>★ 构造函数：不同 Shizuku 版本实例化方式不同（无参 / 带 Context）。
 * 这里两个都提供，运行时用哪个由 Shizuku 反射决定。
 */
public class FpsUserService extends IFpsUserService.Stub {

    public FpsUserService() {
        // Shizuku 用反射实例化，可能走无参构造
    }

    public FpsUserService(Context context) {
        // 部分版本会传 Context
    }

    @Override
    public String exec(String cmd) throws RemoteException {
        return run(cmd, false);
    }

    @Override
    public String execWithErr(String cmd) throws RemoteException {
        return run(cmd, true);
    }

    /**
     * 执行命令并读 stdout。
     *
     * <p>★ 必须用 {@code sh -c "..."} 包裹：直接 exec 字符串数组不会走 shell，
     * 管道/重定向/引号全都失效。
     */
    private String run(String cmd, boolean withErr) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder sb = new StringBuilder(readAll(p.getInputStream()));
            if (withErr) {
                sb.append(readAll(p.getErrorStream()));
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignore) {}
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }
}
