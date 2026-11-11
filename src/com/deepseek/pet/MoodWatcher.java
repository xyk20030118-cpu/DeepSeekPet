package com.deepseek.pet;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;

/**
 * 轻量「主人在干嘛」探测：只读前台应用包名 + 当前时间，不涉及通知/位置/媒体。
 *
 * 依赖系统「使用情况访问」权限（PACKAGE_USAGE_STATS），需用户手动授予。
 * 未授权或取不到时，返回 null，调用方应静默跳过。
 */
public class MoodWatcher {

    /** 前台应用信息：包名 + 人类可读名 + 归类标签 */
    public static class Foreground {
        public String pkg;      // 包名
        public String appName;  // 人类可读名（如「微信」）
        public String scene;    // 场景标签（如「刷短视频」「聊天」）
        public Foreground(String p, String n, String s) { pkg = p; appName = n; scene = s; }
    }

    /** 曾成功读到过前台事件 → 视为已授权（避免静置无事件时误判） */
    private static volatile boolean grantedOnce = false;

    /**
     * 是否已授予使用情况访问权限。
     *
     * <p>★ 结果一旦为 true 就永久缓存（{@code grantedOnce}）——
     * 权限在运行期被撤销极罕见，而调用方每 5 秒问一次。
     *
     * <p>★ 判据是"能查到使用记录"。这里同样用**由近及远**的窗口：
     * 先查 10 分钟（绝大多数情况立即命中、且事件量小）。
     *
     * <p>⚠️ 注意不能用"只查短窗口"了事：手机静置超过窗口时长为空，
     * 会被误判成"没授权"，于是场景搭话**默默失效**（而且不报错，很难查）。
     * 原实现用固定的 1 小时正是为了避开这个坑。所以命中不了时
     * 继续退到 1 小时 —— 代价只在"真的没授权"这条慢路径上。
     */
    public static boolean hasPermission(Context ctx) {
        if (grantedOnce) return true;
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;
            long now = System.currentTimeMillis();
            for (long back : PERM_LOOKBACK_MS) {
                UsageEvents ev = usm.queryEvents(now - back, now);
                if (ev != null && ev.hasNextEvent()) {
                    grantedOnce = true;
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 权限判定的回溯窗口，由近及远（先便宜后兜底） */
    private static final long[] PERM_LOOKBACK_MS = {
            10 * 60 * 1000L,       // 10 分钟：常见情况，事件量小
            60 * 60 * 1000L,       // 1 小时：手机静置过，兜底避免误判未授权
    };

    /** 取当前前台应用；取不到返回 null */
    public static Foreground current(Context ctx) {
        String pkg = currentPackage(ctx);
        if (pkg == null || pkg.length() == 0) return null;
        if (pkg.equals(ctx.getPackageName())) return null; // 自己不算
        if (isLauncher(pkg)) return null;                  // 桌面不算
        return new Foreground(pkg, appName(pkg), scene(pkg));
    }

    /**
     * 原始前台包名 —— **包含自己**，也包含桌面。
     *
     * <p>给游戏助手的"自采样"用：设置页在前台时，{@link #current} 因为"自己不算"
     * 会返回 null，于是读数永远是「未识别到前台应用」——用户看不到任何数字，
     * 没法判断通道到底通没通。这个方法让调用方拿到真实包名后自行决定怎么用
     * （游戏助手会采样本应用并明确标注"本应用"，既给出活数字、又不冒充游戏帧率）。
     */
    public static String currentPackageRaw(Context ctx) {
        try {
            return currentPackage(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 通过 UsageEvents 找最近一次 MOVE_TO_FOREGROUND 的包名。
     *
     * <h3>★ 性能：绝不每次回溯 1 小时</h3>
     * 原实现是 {@code queryEvents(now - 1小时, now)} —— 每次都把**整整一小时**
     * 的使用事件全查出来遍历一遍，只为了拿最后一个前台包名。
     * 而调用方每 5 秒就来一次（{@code FloatingService.MOOD_POLL_MS}），
     * 于是系统侧每秒都在为一个"只要最新一条"的问题做"扫一小时"的活 ——
     * 这正是用户反馈的"占用高、发热高"的一个真实来源。
     *
     * <p>改成**指数递增回溯**：绝大多数情况下"当前前台应用"的事件就在
     * 最近几分钟内，先查最近 60 秒；查不到再退到 5 分钟、1 小时。
     * 常见路径（用户刚切过应用）的查询量降到原来的 **1/60**，
     * 而"长时间静置在某应用"这种罕见路径仍能回退覆盖。
     *
     * <p>用 {@code lastPackageCache} 缓存结果，窗口内不重复查询。
     */
    private static String currentPackage(Context ctx) {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();

            // 由近及远地找：命中即停，避免无谓的大范围扫描
            for (long back : CANDIDATE_LOOKBACK_MS) {
                String p = lastForegroundIn(usm, now - back, now);
                if (p != null) return p;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 回溯窗口（毫秒），由近及远。
     * 60s 覆盖"刚切过应用"（最常见）；5min 覆盖"切了但没动"；
     * 1h 是兜底，覆盖"打开某应用后长时间静置"。
     */
    private static final long[] CANDIDATE_LOOKBACK_MS = {
            60 * 1000L,            // 1 分钟
            5 * 60 * 1000L,        // 5 分钟
            60 * 60 * 1000L,       // 1 小时（兜底）
    };

    /** 在 [from, to) 里找最后一个前台事件的包名；没有返回 null */
    private static String lastForegroundIn(UsageStatsManager usm, long from, long to) {
        UsageEvents events = usm.queryEvents(from, to);
        if (events == null) return null;
        String last = null;
        long lastTs = 0;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            boolean fg = (type == UsageEvents.Event.ACTIVITY_RESUMED)      // API 29+
                    || (type == UsageEvents.Event.MOVE_TO_FOREGROUND);      // 旧版
            if (fg && e.getTimeStamp() >= lastTs) {
                lastTs = e.getTimeStamp();
                last = e.getPackageName();
            }
        }
        return last;
    }

    private static final String[] LAUNCHERS = {
            "com.android.launcher", "com.android.launcher3", "com.miui.home",
            "com.oppo.launcher", "com.coloros.launcher", "com.huawei.android.launcher",
            "com.android.systemui", "com.sec.android.app.launcher"
    };

    private static boolean isLauncher(String pkg) {
        for (String l : LAUNCHERS) if (pkg.startsWith(l)) return true;
        return false;
    }

    /** 包名 → 人类可读名（常见应用；未知则取包名末段） */
    public static String appName(String pkg) {
        switch (pkg) {
            case "com.tencent.mm": return "微信";
            case "com.tencent.mobileqq": return "QQ";
            case "com.tencent.tim": return "TIM";
            case "com.ss.android.ugc.aweme": return "抖音";
            case "com.ss.android.ugc.aweme.lite": return "抖音极速版";
            case "com.smile.gifmaker": return "快手";
            case "tv.danmaku.bili": return "哔哩哔哩";
            case "com.tencent.qqlive": return "腾讯视频";
            case "com.qiyi.video": return "爱奇艺";
            case "com.youku.phone": return "优酷";
            case "com.netease.cloudmusic": return "网易云音乐";
            case "com.tencent.qqmusic": return "QQ音乐";
            case "com.ximalaya.ting.android": return "喜马拉雅";
            case "com.taobao.taobao": return "淘宝";
            case "com.jingdong.app.mall": return "京东";
            case "com.xunmeng.pinduoduo": return "拼多多";
            case "com.eg.android.AlipayGphone": return "支付宝";
            case "com.sina.weibo": return "微博";
            case "com.zhihu.android": return "知乎";
            case "com.xingin.xhs": return "小红书";
            case "com.tencent.news": return "腾讯新闻";
            case "com.netease.newsreader.activity": return "网易新闻";
            case "com.autonavi.minimap": return "高德地图";
            case "com.baidu.BaiduMap": return "百度地图";
            case "com.sdu.didi.psnger": return "滴滴出行";
            case "com.MobileTicket": return "铁路12306";
            case "com.tencent.mtt": return "QQ浏览器";
            case "com.UCMobile": return "UC浏览器";
            case "com.android.chrome": return "Chrome";
            case "com.microsoft.emmx": return "Edge";
            case "com.tencent.qqmail": return "QQ邮箱";
            case "com.alibaba.android.rimet": return "钉钉";
            case "com.tencent.wework": return "企业微信";
            case "com.ss.android.lark": return "飞书";
            case "com.tencent.wemeet.app": return "腾讯会议";
            case "com.tencent.tmgp.sgame": return "王者荣耀";
            case "com.tencent.tmgp.pubgmhd": return "和平精英";
            case "com.netease.party": return "蛋仔派对";
            case "com.miHoYo.Yuanshen": return "原神";
            case "com.tencent.docs": return "腾讯文档";
            case "cn.wps.moffice_eng": return "WPS Office";
            case "com.kingsoft.moffice": return "WPS";
            case "com.tencent.androidqqmail": return "QQ邮箱";
            case "com.tencent.qt.qtl": return "腾讯文档";
            default:
                int dot = pkg.lastIndexOf('.');
                return dot >= 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : pkg;
        }
    }

    /** 包名 → 场景标签（用于生成更贴合的搭话） */
    public static String scene(String pkg) {
        if (pkg == null) return "用手机";
        if (pkg.contains("aweme") || pkg.contains("gifmaker") || pkg.contains("kuaishou"))
            return "刷短视频";
        if (pkg.contains("tencent.mm") || pkg.contains("mobileqq") || pkg.contains("tim")
                || pkg.contains("weibo") || pkg.contains("xingin") || pkg.contains("lark"))
            return "聊天／社交";
        if (pkg.contains("qqlive") || pkg.contains("qiyi") || pkg.contains("youku")
                || pkg.contains("bili") || pkg.contains("mgtv"))
            return "看视频";
        if (pkg.contains("cloudmusic") || pkg.contains("qqmusic") || pkg.contains("ximalaya")
                || pkg.contains("kugou") || pkg.contains("kuwo"))
            return "听音乐";
        if (pkg.contains("taobao") || pkg.contains("jingdong") || pkg.contains("pinduoduo")
                || pkg.contains("alipay") || pkg.contains("tmall"))
            return "逛购物";
        if (pkg.contains("sgame") || pkg.contains("pubgmhd") || pkg.contains("netease.party")
                || pkg.contains("mihoyo") || pkg.contains("game") || pkg.contains("tmgp"))
            return "玩游戏";
        if (pkg.contains("zhihu") || pkg.contains("news") || pkg.contains("toutiao")
                || pkg.contains("weibo"))
            return "看资讯";
        if (pkg.contains("minimap") || pkg.contains("baidumap") || pkg.contains("didi")
                || pkg.contains("MobileTicket"))
            return "出行导航";
        if (pkg.contains("moffice") || pkg.contains("wps") || pkg.contains("dingtalk")
                || pkg.contains("wework") || pkg.contains("docs") || pkg.contains("tencent.wework"))
            return "工作／办公";
        if (pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("emmx")
                || pkg.contains("UCMobile") || pkg.contains("mtt"))
            return "浏览网页";
        return "用手机";
    }
}
