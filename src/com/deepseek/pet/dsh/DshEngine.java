package com.deepseek.pet.dsh;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.deepseek.pet.dsh.linux.Engine;
import com.deepseek.pet.dsh.linux.EngineProot;
import com.deepseek.pet.dsh.linux.EngineService;

/**
 * 本机 Linux（proot + Ubuntu + 官方 Node）的进程内门面。
 *
 * <h3>v8.1 起：不再有"第二个包"</h3>
 * 以前 Linux 那层是独立的 {@code com.deepseek.pet.linux}（targetSdk 28），主 App 通过
 * {@code 127.0.0.1:43190} 的 HTTP 控制接口去问它。用户要装两个 App、划掉后台就断、还要
 * 单独授权 —— 全是这一层跨包带来的。
 *
 * <p>现在把引擎源码并进主 App、主 App 整体用 targetSdk 28（proot 只在 ≤28 的包里能 execve
 * 自己目录里的程序，这是硬约束，第二十版文档里有实测对照），于是：
 * <ul>
 *   <li>只有一个包、一个后台服务；</li>
 *   <li>载荷直接读自己的 assets（{@code asset://engine}），不用服务器也不用跨进程提供；</li>
 *   <li>状态直接从 {@link Engine} 读，没有 HTTP 控制接口。</li>
 * </ul>
 */
public final class DshEngine {

    /** 载荷在自己 assets 里的哪个目录。 */
    public static final String ASSET_DIR = "engine";

    /** 交给 {@link Engine#download} 的"包内载荷"标记。 */
    public static final String ASSET_SOURCE = "asset://" + ASSET_DIR;

    private DshEngine() { }

    /** 引擎组件（现在就是本 App 的服务）。 */
    static ComponentName serviceComponent(Context c) {
        return new ComponentName(c.getPackageName(), EngineService.class.getName());
    }

    /** 本机 Linux 是否已经装好（rootfs 里的 bash + node 都在）。 */
    public static boolean installed(Context c) {
        try {
            return EngineProot.available(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 起宿主（幂等：已经在跑就只是把状态补一下）。 */
    public static boolean start(Context c) {
        try {
            Intent i = new Intent();
            i.setComponent(serviceComponent(c));
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 起宿主；{@code source} 现在只可能是 {@link #ASSET_SOURCE}（包内载荷）。
     * 保留这个签名是为了让界面代码不用改。
     */
    public static boolean start(Context c, String source) {
        return start(c);
    }

    /** 切换 root / 普通身份时使用：回收旧进程后按新设置重新启动。 */
    public static boolean restart(Context c) {
        stop(c);
        return start(c);
    }

    /** 停宿主。 */
    public static boolean stop(Context c) {
        try {
            Engine.get(c).stop();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 服务在跑吗（进程内直接问单例）。 */
    public static boolean serviceAlive(Context c) {
        try {
            return Engine.get(c).running();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前状态（进程内读取，毫秒级）。 */
    public static Status status(int timeoutMs) {
        Status s = new Status();
        try {
            // 这里没有 Context，交给调用方传入的那个单例（见 DshEngine.status(Context)）
            return s;
        } catch (Throwable t) {
            return s;
        }
    }

    /** 当前状态（进程内读取）。 */
    public static Status status(Context c) {
        Status s = new Status();
        try {
            Engine e = Engine.get(c);
            s.state = e.stateName();
            s.detail = e.detailText();
            s.url = e.urlText();
            s.token = e.tokenText();
            s.log = e.logLines();
        } catch (Throwable t) {
            s.detail = "读不到引擎状态：" + t.getClass().getSimpleName();
        }
        return s;
    }

    /** 引擎状态（字段名与旧版一致，界面代码不用改）。 */
    public static final class Status {
        public String state = "";
        public String url = "";
        public String token = "";
        public String detail = "";
        public java.util.List<String> log = new java.util.ArrayList<String>();

        public boolean running() {
            return "running".equals(state) && url != null && url.length() > 0;
        }

        public String summary() {
            if ("running".equals(state)) return "运行中：宿主已启动";
            if ("installing".equals(state)) return "安装中";
            if ("starting".equals(state)) return "启动宿主中";
            if ("failed".equals(state)) return "失败：" + (detail.length() > 0 ? detail : "未知原因");
            if ("idle".equals(state)) return detail.length() > 0 ? detail : "未运行";
            return "未运行";
        }
    }
}
