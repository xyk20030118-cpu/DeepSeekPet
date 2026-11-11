package com.deepseek.pet.dsh.linux;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.io.File;
import java.net.InetAddress;

/**
 * Android 没有 {@code /etc/resolv.conf}：不写这个，rootfs 里 apt / npm 一律报
 * "Temporary failure resolving"。这里从 {@link ConnectivityManager} 取当前网络的 DNS，
 * 写进共享目录，再由 proot 绑成 guest 的 {@code /etc/resolv.conf}。
 */
final class Dns {

    private Dns() { }

    /**
     * @return 写进去的 DNS 个数；{@code -1} 表示没取到、用了兜底值
     */
    static int write(Context c, File out) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network[] nets = cm.getAllNetworks();
                if (nets != null) {
                    for (Network net : nets) {
                        LinkProperties lp = cm.getLinkProperties(net);
                        if (lp == null) continue;
                        for (InetAddress dns : lp.getDnsServers()) {
                            if (dns == null) continue;
                            String ip = dns.getHostAddress();
                            if (ip == null || ip.length() == 0) continue;
                            if (sb.indexOf("nameserver " + ip) >= 0) continue;
                            sb.append("nameserver ").append(ip).append('\n');
                            n++;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
            // 没有网络信息权限时走兜底
        }
        if (n == 0) {
            sb.append("nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
            n = -1;
        }
        try {
            DshFs.writeText(out, sb.toString());
        } catch (Throwable ignore) {
            return 0;
        }
        return n;
    }
}
