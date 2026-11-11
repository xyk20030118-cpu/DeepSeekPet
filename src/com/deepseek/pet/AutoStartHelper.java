package com.deepseek.pet;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台保活 / 自启动引导助手。
 *
 * ── 先说清一件事，避免误导用户 ──────────────────────────────
 * Android 从来没有公开 API 能「直接打开」或「强开」厂商的自启动开关：
 *   • 厂商的自启白名单是各 ROM 私有实现，只有各自 Settings 里的私有页面能改；
 *   • 那些页面没有文档化、不同机型/版本路径都不一样，还可能被系统改掉。
 * 所以本类做两件真正有价值的事：
 *   ① 用「电池优化豁免」（标准 API，可编程、可查询、有系统弹窗）真正向系统索要
 *      后台运行许可 —— 这是唯一能被程序确认生效的部分；
 *   ② 把厂商私有的自启动/后台管理页面列成清单，逐个探测可达性，
 *      用户点一下就直达对的那一页，不用在设置里瞎翻。
 *
 * 探测顺序讲究：先试本机品牌自己的入口，再试通用的，最后退回「应用详情页」。
 */
public final class AutoStartHelper {

    private AutoStartHelper() {}

    /** 一家 ROM 的一条候选入口 */
    public static final class Entry {
        public final String vendor;      // 品牌名，如 "vivo / iQOO"
        public final String label;       // 给用户看的按钮名
        public final String pkg;         // 目标包
        public final String cls;         // 目标类
        public final String fallbackNote; // 打不开时的文字指引

        Entry(String vendor, String label, String pkg, String cls, String note) {
            this.vendor = vendor;
            this.label = label;
            this.pkg = pkg;
            this.cls = cls;
            this.fallbackNote = note;
        }
    }

    // ================= 品牌识别 =================

    private static String brand() {
        String b = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        return (b + "|" + m);
    }

    public static String vendorName() {
        String s = brand();
        if (s.contains("vivo") || s.contains("iqoo")) return "vivo / iQOO";
        if (s.contains("oppo") || s.contains("oneplus") || s.contains("realme")
                || s.contains("oplus")) return "OPPO / 一加 / realme";
        if (s.contains("xiaomi") || s.contains("redmi") || s.contains("poco"))
            return "小米 / Redmi";
        if (s.contains("huawei") || s.contains("honor")) return "华为 / 荣耀";
        if (s.contains("meizu")) return "魅族";
        if (s.contains("samsung")) return "三星";
        if (s.contains("asus")) return "华硕";
        if (s.contains("lenovo") || s.contains("motorola")) return "联想 / moto";
        if (s.contains("zte") || s.contains("nubia")) return "中兴 / 努比亚";
        if (s.contains("sony")) return "索尼";
        if (s.contains("google")) return "Google";
        return Build.MANUFACTURER == null ? "本机" : Build.MANUFACTURER;
    }

    /** 本机品牌对应的自启动入口清单；不含通用项 */
    public static List<Entry> vendorEntries() {
        String s = brand();
        List<Entry> out = new ArrayList<Entry>();

        if (s.contains("vivo") || s.contains("iqoo")) {
            // i管家（新系统）与旧版管家两组入口都放上，探测时取能用的那个
            out.add(new Entry("vivo / iQOO", "自启动管理（i管家）",
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    "i管家 → 应用管理 → 权限管理 → 自启动"));
            out.add(new Entry("vivo / iQOO", "自启动管理（备用入口）",
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity",
                    "i管家 → 权限管理 → 自启动"));
            out.add(new Entry("vivo / iQOO", "后台高耗电（允许后台运行）",
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                    "i管家 → 电池 → 后台高耗电 → 允许"));
            out.add(new Entry("vivo / iQOO", "电池管理",
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                    "i管家 → 电池管理"));
            out.add(new Entry("vivo / iQOO", "应用详情",
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity",
                    "设置 → 应用 → 找到「DeepSeek桌宠」→ 权限"));
        } else if (s.contains("oppo") || s.contains("oneplus") || s.contains("realme")
                || s.contains("oplus")) {
            out.add(new Entry("OPPO / 一加 / realme", "自启动管理",
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "设置 → 应用管理 → 自启动"));
            out.add(new Entry("OPPO / 一加 / realme", "自启动管理（备用）",
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                    "设置 → 应用管理 → 自启动"));
            out.add(new Entry("OPPO / 一加 / realme", "允许后台运行",
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                    "设置 → 电池 → 更多 → 耗电管理"));
            out.add(new Entry("OPPO / 一加 / realme", "耗电保护",
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                    "设置 → 电池 → 耗电保护"));
        } else if (s.contains("xiaomi") || s.contains("redmi") || s.contains("poco")) {
            out.add(new Entry("小米 / Redmi", "自启动管理",
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    "设置 → 应用设置 → 授权管理 → 自启动管理"));
            out.add(new Entry("小米 / Redmi", "省电策略（无限制）",
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                    "设置 → 应用设置 → 应用管理 → 省电策略 → 无限制"));
            out.add(new Entry("小米 / Redmi", "后台联网/权限管理",
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    "设置 → 应用设置 → 授权管理"));
        } else if (s.contains("huawei") || s.contains("honor")) {
            out.add(new Entry("华为 / 荣耀", "启动管理",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "设置 → 应用 → 应用启动管理 → 手动管理"));
            out.add(new Entry("华为 / 荣耀", "启动管理（备用）",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    "设置 → 应用 → 应用启动管理"));
            out.add(new Entry("华为 / 荣耀", "受保护应用",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                    "设置 → 应用启动管理"));
        } else if (s.contains("meizu")) {
            out.add(new Entry("魅族", "后台管理",
                    "com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC",
                    "设置 → 应用管理 → 权限 → 后台管理"));
        } else if (s.contains("samsung")) {
            out.add(new Entry("三星", "电池 → 后台使用限制",
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                    "设置 → 电池 → 后台使用限制"));
            out.add(new Entry("三星", "设备维护",
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity",
                    "设置 → 设备维护 → 电池"));
        } else if (s.contains("asus")) {
            out.add(new Entry("华硕", "自启动管理",
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.powersaver.PowerSaverSettings",
                    "设置 → 电源管理 → 自启动"));
        } else if (s.contains("lenovo") || s.contains("motorola")) {
            out.add(new Entry("联想 / moto", "自启动管理",
                    "com.lenovo.security",
                    "com.lenovo.security.purebackground.PureBackgroundActivity",
                    "设置 → 应用 → 权限 → 自启动"));
        } else if (s.contains("zte") || s.contains("nubia")) {
            out.add(new Entry("中兴 / 努比亚", "自启动管理",
                    "cn.nubia.security2",
                    "cn.nubia.security2.activity.StartupAppControlActivity",
                    "设置 → 应用 → 自启动"));
            out.add(new Entry("中兴 / 努比亚", "自启动管理（备用）",
                    "com.zte.heartyservice",
                    "com.zte.heartyservice.setting.SettingActivity",
                    "设置 → 应用 → 自启动"));
        } else if (s.contains("sony")) {
            out.add(new Entry("索尼", "省电 → 电池优化",
                    "com.sonymobile.smartcharger",
                    "com.sonymobile.smartcharger.MainActivity",
                    "设置 → 电池 → 电池优化 / STAMINA 模式"));
        }
        // 原生 Android / 未识别品牌：无可跳的厂商页，交给电池优化 + 应用详情
        return out;
    }

    // ================= 可达性探测 =================

    /** 目标 Activity 是否真实存在且可启动 */
    public static boolean canOpen(Context ctx, String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PackageManager pm = ctx.getPackageManager();
            List<ResolveInfo> rs = pm.queryIntentActivities(i, 0);
            return rs != null && !rs.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 挑出本机确实能打开的入口（品牌项 + 通用兜底） */
    public static List<Entry> availableEntries(Context ctx) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : vendorEntries()) {
            if (canOpen(ctx, e.pkg, e.cls)) out.add(e);
        }
        return out;
    }

    /** 直接跳到这条入口；失败返回 false（调用方再给文字指引） */
    public static boolean open(Context ctx, Entry e) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(e.pkg, e.cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // 尽量把页面定位到「本应用」那一行：不同 ROM 认的键不一样，
            // 全都塞上，认得的就用，不认得的会被忽略，不会出错。
            i.putExtra("package_name", ctx.getPackageName());
            i.putExtra("pkg_name", ctx.getPackageName());
            i.putExtra("packagename", ctx.getPackageName());
            i.putExtra("app_package_name", ctx.getPackageName());
            i.putExtra(Intent.EXTRA_PACKAGE_NAME, ctx.getPackageName());
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================= 电池优化豁免（标准 API，真正可编程） =================

    public static boolean isIgnoringBatteryOptimizations(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            return pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            return true;   // 查询失败就当已豁免，避免反复骚扰用户
        }
    }

    /**
     * 向系统索要电池优化豁免。
     * 这是唯一「主动向系统要后台权限」的正规途径：会弹系统自己的确认框。
     */
    public static boolean requestIgnoreBatteryOptimizations(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 23) return false;
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 退而求其次：打开电池优化总列表，用户自己找 */
    public static boolean openBatteryOptimizationList(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================= 本应用详情页（永远可用的兜底） =================

    public static boolean openAppDetails(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            try {
                Intent i2 = new Intent(Settings.ACTION_SETTINGS);
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i2);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    /** 该品牌是否需要在设置里手动加白（几乎都需要；用于文案） */
    public static String manualHint() {
        String s = brand();
        if (s.contains("vivo") || s.contains("iqoo"))
            return "i管家 → 应用管理 → 权限管理 → 自启动 → 允许「DeepSeek桌宠」\n"
                 + "再到 i管家 → 电池 → 后台高耗电 → 允许\n"
                 + "部分系统里入口叫「后台启动」或藏在「应用管理 → 权限」里，可用上方按钮直达。";
        if (s.contains("oppo") || s.contains("oneplus") || s.contains("realme"))
            return "设置 → 应用管理 → 自启动管理 → 允许「DeepSeek桌宠」\n"
                 + "设置 → 电池 → 应用耗电管理 → 允许后台运行";
        if (s.contains("xiaomi") || s.contains("redmi"))
            return "设置 → 应用设置 → 授权管理 → 自启动管理 → 允许\n"
                 + "设置 → 应用设置 → 应用管理 → 省电策略 → 无限制";
        if (s.contains("huawei") || s.contains("honor"))
            return "设置 → 应用 → 应用启动管理 → 关掉「自动管理」→ 三个开关全开";
        return "设置 → 应用 → 找到「DeepSeek桌宠」→ 允许自启动 / 后台运行 / 无限制省电";
    }
}
