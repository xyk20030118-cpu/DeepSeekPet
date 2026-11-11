package com.deepseek.pet;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 权限与后台保活引导页。
 *
 * 设计原则：
 *   1. 每条权限都写明「为什么需要」和「不给会怎样」，不写空洞的"请授权"；
 *   2. 能程序化申请的直接弹系统框（通知、悬浮窗、电池优化）；
 *   3. 厂商私有的自启动页做可达性探测，只展示本机真实存在的入口，
 *      点击直达；打不开时降到文字指引，绝不留死路；
 *   4. 打开前会标注状态，用户回来能看到自己刚有没有改成功。
 *
 * <h3>★ 为什么这一页要重做（轮次㊷ · 与轮次㊵/㊶ 同族）</h3>
 *
 * 用户的要求是「所有三级菜单 / 页面都同步 app ui 跟随主题切换」。
 * 轮次㊵ 做的是**弹窗**那层、轮次㊶ 做的是**用量页**，本轮收掉最后两页。本页原来的病：
 * <ul>
 *   <li>整页底色写死 {@code 0xFFF7F7FA}（{@code sv.setBackgroundColor(…)}）⇒
 *       深色模式 / 玻璃皮肤下**整页还是浅色**；</li>
 *   <li><b>10 处</b>文字色写死（{@code 0xFF222222} / {@code 0xFF3F51B5} / {@code 0xFF444444} /
 *       {@code 0xFF555555} / {@code 0xFF2E7D32} / {@code 0xFFC62828} …），
 *       另有 {@code 0x11000000} 当"代码块底"、{@code 0x223F51B5} 当分组分隔线；</li>
 *   <li>控件是裸 {@code Button} / {@code CheckBox} —— 与设置页的浮雕/玻璃语言不一致，
 *       而且裸 CheckBox 的触点只有约 50×27dp（低于可达性下限，设置页早已换成
 *       {@link UiFactory#toggleRow}）。</li>
 * </ul>
 *
 * 现在：页容器改用 {@link SettingsPage}（与设置页**同一套**"分组小标题 + 分组卡片"结构）、
 * 文字走**语义角色**（见 {@link #colorOf}）、状态行走 {@link UiFactory#iconBadgeByName}
 * 的**单色图标**（不再用 ✅❌ 彩色 emoji）、开关走 {@link UiFactory#toggleRow}、
 * 入口行走 {@link UiFactory#actionRow}、"手动路径"那块走 {@link UiKit#sunken} 凹槽。
 * ⇒ 本文件里**再出现一个 {@code 0xFF……} 都算回潮**（`build/verify_page_theme.py` 钉着）。
 */
public class PermissionActivity extends Activity {

    private SettingsPage page;
    /** ★ 令牌：皮肤（新拟态/玻璃）× 明暗，页面里所有颜色都从它取 */
    private Tokens.Set T;
    private float d;

    // ★ 文字的**语义角色** —— 取代原来散落在各处的 0xFF…… 硬编码色。
    //   用角色而不是直接传颜色，是为了"换主题时不用逐个调用点改"
    //   （原来 10 处硬编码色，漏一处就是深色下一块看不清的字）。
    private static final int ROLE_TITLE = 0;    // 强调
    private static final int ROLE_BODY = 1;     // 正文
    private static final int ROLE_DIM = 2;      // 次级说明
    private static final int ROLE_DANGER = 3;   // 错误 / 未授权

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ★ 必须在 setContentView 之前：让系统自绘的东西（对话框确认框 /
        //   输入法选择菜单 / 长按文本菜单）也跟着明暗走。
        Tokens.applyPlatformTheme(this);
        T = Tokens.of(this);
        d = getResources().getDisplayMetrics().density;
        // ★ 窗口自身也要上色：清单里挂的是系统主题，不改的话深色模式下边缘会露白
        //   （与 UsageActivity / HistoryActivity / ChatActivity 同一套做法）。
        getWindow().setBackgroundDrawable(new ColorDrawable(T.bg));
        setTitle("权限与后台保活");
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户从系统设置页回来，刷新各项状态
        build();
    }

    /** 语义角色 → 令牌色 */
    private int colorOf(int role) {
        if (role == ROLE_TITLE) return T.text1;
        if (role == ROLE_BODY) return T.text2;
        if (role == ROLE_DANGER) return T.danger;
        return T.text3;
    }

    // ==================== 页面骨架 ====================

    private void build() {
        ScrollView sv = new ScrollView(this);
        page = new SettingsPage(this, T);
        // ★ 整页铺主题背景（玻璃态是 Mesh 光斑，新拟态是纯色）—— 原来这里是
        //   `sv.setBackgroundColor(0xFFF7F7FA)`，一个写死的浅色。
        UiFactory.decoratePage(page, this, T);
        sv.addView(page);
        setContentView(sv);

        // ★ 页头不带 emoji：设计语言里图标归**行首徽章**（单色线稿），
        //   页头放彩色 emoji 会与它冲突（与 MainActivity 的二级页页头一致）。
        page.addToPage(UiFactory.header(this, T, "权限与后台保活",
                "每一项都写明「为什么需要」和「不给会怎样」"));

        // ---------- 顶部说明 ----------
        page.beginGroup("为什么桌宠会「消失」");
        page.addView(text("Android 为了省电，会在你锁屏一段时间后限制后台应用。"
                + "桌宠不是被卸载了，而是被系统「冻」住了。", ROLE_BODY));
        page.addView(text("下面每一项都是让桌宠能稳定待着的必要条件。"
                + "建议一次全部打开，之后基本不会再掉。", ROLE_BODY));

        // ★ 标题点明"反馈问题时请附上"：本页底部那句就是让用户报机型与系统版本，
        //   把这一块标出来，用户不必猜"我该报什么"。
        page.beginGroup("本机环境（反馈问题时请附上）");
        page.addView(text("品牌：" + AutoStartHelper.vendorName(), ROLE_BODY));
        page.addView(text("系统版本：Android " + Build.VERSION.RELEASE
                + "（API " + Build.VERSION.SDK_INT + "）", ROLE_BODY));

        // ---------- ① 悬浮窗 ----------
        page.beginGroup("① 显示在其他应用上层（悬浮窗）");
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        addState(overlay, "没有它桌宠根本显示不出来");
        if (!overlay) {
            addAction("🖼️ 立即授予悬浮窗权限", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                        i.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                        Toast.makeText(PermissionActivity.this,
                                "请打开「允许显示在其他应用上层」", Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        toast("请手动进入：设置 → 应用 → 特殊权限 → 显示在其他应用上层");
                    }
                }
            });
        }

        // ---------- ② 通知 ----------
        page.beginGroup("② 通知权限");
        boolean noti = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                   == android.content.pm.PackageManager.PERMISSION_GRANTED;
        addState(noti, "桌宠常驻通知+语音输入需要它；不给则锁屏后容易被清");
        if (!noti && Build.VERSION.SDK_INT >= 33) {
            addAction("🔔 申请通知权限", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(
                                new String[]{"android.permission.POST_NOTIFICATIONS"}, 3001);
                    }
                }
            });
        }

        // ---------- ③ 电池优化豁免 ★ 真正向系统索要 ----------
        page.beginGroup("③ 忽略电池优化 ★关键");
        boolean ignoring = AutoStartHelper.isIgnoringBatteryOptimizations(this);
        addState(ignoring, ignoring
                ? "已豁免，系统不会因省电掐掉桌宠"
                : "未豁免 —— 这是桌宠被系统杀掉的最主要原因");
        if (!ignoring) {
            addAction("🛡️ 向系统申请「忽略电池优化」", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (AutoStartHelper.requestIgnoreBatteryOptimizations(PermissionActivity.this)) {
                        Toast.makeText(PermissionActivity.this,
                                "请在系统弹窗里点「允许」", Toast.LENGTH_LONG).show();
                    } else {
                        AutoStartHelper.openBatteryOptimizationList(PermissionActivity.this);
                        toast("请找到「DeepSeek桌宠」并设为「不优化 / 允许」");
                    }
                }
            });
            addAction("⚙️ 打开电池优化列表（手动找）", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    AutoStartHelper.openBatteryOptimizationList(PermissionActivity.this);
                }
            });
        }

        // ---------- ④ 厂商自启动 ★ 用户反馈的核心诉求 ----------
        page.beginGroup("④ 自启动 / 后台运行 ★关键");
        page.addView(text("Android 没有公开接口能直接打开厂商的自启动开关，"
                + "各品牌都藏在自家设置里。下面按钮已自动探测过，"
                + "能用的才会显示 —— 点一下直达对应页面。", ROLE_BODY));

        List<AutoStartHelper.Entry> list = AutoStartHelper.availableEntries(this);
        if (list.isEmpty()) {
            page.addView(text("未在本机探测到可直达的自启动页面。"
                    + "可能是该品牌未开放、或系统版本变了。请按下面的路径手动设置：",
                    ROLE_DANGER));
            addCode(AutoStartHelper.manualHint());
            addAction("⚙️ 打开本应用详情页", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    AutoStartHelper.openAppDetails(PermissionActivity.this);
                }
            });
        } else {
            for (int i = 0; i < list.size(); i++) {
                final AutoStartHelper.Entry e = list.get(i);
                addAction("🔗 " + e.label, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (!AutoStartHelper.open(PermissionActivity.this, e)) {
                            toast("打不开，请按下方文字路径手动设置");
                        }
                    }
                });
                addCode("若打不开：" + e.fallbackNote);
            }
            addAction("⚙️ 打开本应用详情页（兜底）", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    AutoStartHelper.openAppDetails(PermissionActivity.this);
                }
            });
        }

        page.addView(text("在自启动页面里，把「DeepSeek桌宠」的开关打开即可。"
                + "有的系统还需要同时打开「关联启动」「后台运行」。", ROLE_BODY));

        // ---------- ④.5 看门狗（真正"强"的部分） ----------
        page.beginGroup("④½ 后台自愈看门狗 ★最强保障");
        page.addView(text("自启动开关只保证「开机那一次」。系统在运行中仍可能因内存/省电"
                + "把桌宠回收掉 —— 这就是「明明开了自启，过一阵还是没了」的原因。", ROLE_BODY));
        page.addView(text("看门狗会定时巡检，发现桌宠不在了就立刻把它拉回来。"
                + "它不依赖厂商设置，是唯一由本应用自己掌控的保活手段。", ROLE_BODY));

        boolean kaOn = Prefs.keepAliveEnabled(this);
        page.addView(UiFactory.toggleRow(this, T, "开启后台自愈看门狗", kaOn,
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setKeepAliveEnabled(PermissionActivity.this, checked);
                if (checked) {
                    KeepAlive.scheduleWatchdog(PermissionActivity.this,
                            Prefs.keepAliveIntervalMs(PermissionActivity.this));
                    toast("看门狗已开启，每 " + Prefs.keepAliveMinutes(PermissionActivity.this)
                            + " 分钟巡检一次");
                } else {
                    KeepAlive.cancelWatchdog(PermissionActivity.this);
                    toast("看门狗已关闭");
                }
                build();
            }
        }));
        addAction("⏱️ 巡检间隔：" + Prefs.keepAliveMinutes(this) + " 分钟（点击修改）",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] opts = {"5 分钟", "10 分钟", "15 分钟", "30 分钟", "60 分钟"};
                final int[] vals = {5, 10, 15, 30, 60};
                UiFactory.dialog(PermissionActivity.this)
                        .setTitle("看门狗巡检间隔")
                        .setItems(opts, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dlg, int which) {
                                Prefs.setKeepAliveMinutes(PermissionActivity.this, vals[which]);
                                KeepAlive.scheduleWatchdog(PermissionActivity.this,
                                        Prefs.keepAliveIntervalMs(PermissionActivity.this));
                                toast("已设为 " + opts[which]);
                                build();
                            }
                        })
                        .show();
            }
        });
        page.addView(text("间隔越短恢复越快、越耗电。15 分钟是均衡值；"
                + "桌宠对你很重要可以调到 5 分钟。", ROLE_DIM));

        // ---------- 一键加强 ----------
        page.beginGroup("一键加强（推荐先点这个）");
        page.addView(text("按顺序把能程序化申请的都申请一遍："
                + "① 悬浮窗 ② 通知 ③ 电池优化豁免 ④ 打开自启动页。", ROLE_BODY));
        Button boost = UiFactory.button(this, T, "一键依次申请全部后台权限", true);
        boost.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                runOneTapBoost();
            }
        });
        page.addView(boost);

        page.beginGroup("当前后台保活状态");
        page.addView(text(KeepAlive.statusLine(PermissionActivity.this), ROLE_BODY));

        // ---------- ⑤ 使用情况访问（场景搭话用） ----------
        page.beginGroup("⑤ 使用情况访问（可选）");
        page.addView(text("只有开启「场景搭话」才需要。会给桌宠读到你正在用哪个应用，"
                + "好让它在合适的时候搭一句话。不开启则桌宠一段时间后可能被系统清理。",
                ROLE_BODY));
        addAction("👀 打开使用情况访问设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    toast("请在列表里找到「DeepSeek桌宠」并允许");
                } catch (Throwable t) {
                    toast("请手动进入：设置 → 应用 → 特殊权限 → 使用情况访问");
                }
            }
        });

        // ---------- ⑥ 锁定后台任务（最强保障） ----------
        page.beginGroup("⑥ 一键上锁（最强保障）");
        page.addView(text("在「最近任务」界面把桌宠卡片下拉 / 点小锁图标锁住，"
                + "多数国产系统就不会清理它了。这是最省事的办法。", ROLE_BODY));
        addAction("👆 打开最近任务（请下拉卡片上锁）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME));
                    Toast.makeText(PermissionActivity.this,
                            "请点「最近任务」，找到桌宠卡片下拉上锁",
                            Toast.LENGTH_LONG).show();
                } catch (Throwable ignore) {}
            }
        });

        // ---------- 底部 ----------
        page.beginGroup("提示");
        page.addView(text("全部设置完重启一次桌宠即可长期稳定。\n"
                + "若仍有问题，可在应用内「关于」页反馈机型与系统版本。", ROLE_DIM));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        build();
    }

    // ================= UI 小工具（全部走令牌，见类注释）=================

    /** 正文段落（走语义角色；左右内边距与卡片内其它行对齐） */
    private TextView text(String s, int role) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(14);
        tv.setTextColor(colorOf(role));
        tv.setLineSpacing(0, 1.25f);
        int side = UiFactory.rowSidePad(this);
        tv.setPadding(side, (int) (d * 6), side, (int) (d * 6));
        return tv;
    }

    /**
     * 状态行：行首**单色图标**（通过=成功色 / 未通过=警示色）+ 文字。
     *
     * <p>原来是一行带 ✅ / ⚠️ 彩色 emoji 的裸文字 —— 彩色 emoji 与整套单色图标语言
     * 冲突，而且颜色不受控（深色下绿框红叉很刺眼）。与设置页的
     * {@code MainActivity.addStatusRow} 保持同一手法。
     */
    private void addState(boolean ok, String note) {
        LinearLayout row = UiFactory.row(this, T);
        row.addView(UiFactory.iconBadgeByName(this, T, ok ? "CHECK" : "WARN", null,
                ok ? T.success : T.danger));
        TextView tv = new TextView(this);
        tv.setText((ok ? "已授权" : "未授权") + "　" + note);
        tv.setTextSize(14);
        tv.setTextColor(ok ? T.text1 : T.danger);
        tv.setLineSpacing(0, 1.15f);
        row.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(row);
    }

    /** 可点入口行（卡片内的一行，右侧带 chevron） */
    private void addAction(String text, View.OnClickListener l) {
        page.addView(UiFactory.actionRow(this, T, text, l));
    }

    /** "手动路径"这类要原样照抄的文字：凹槽底 + 次级色 */
    private void addCode(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setTextColor(T.text3);
        tv.setLineSpacing(0, 1.2f);
        // ★ 原来是 `setBackgroundColor(0x11000000)` 一个写死的半透明黑
        //   ⇒ 深色下"凹"不下去（黑叠黑），改成令牌的凹陷面。
        tv.setBackground(UiKit.sunken(this, T, Tokens.R_INPUT));
        int side = UiFactory.rowSidePad(this);
        tv.setPadding(side, (int) (d * 10), side, (int) (d * 10));
        page.addView(tv);
    }

    /**
     * 一键依次申请。
     * 说明：系统权限弹窗必须一个个来（弹窗会抢占前台），
     * 所以这里做成「点了就跳到第一个缺口」，用户回来后自动继续下一个。
     */
    private void runOneTapBoost() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3001);
            toast("① 请先允许通知，回来后再点一次会继续下一项");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
                toast("② 请打开悬浮窗权限，回来后再点一次继续");
                return;
            } catch (Throwable ignore) {}
        }
        if (!AutoStartHelper.isIgnoringBatteryOptimizations(this)) {
            if (AutoStartHelper.requestIgnoreBatteryOptimizations(PermissionActivity.this)) {
                toast("③ 请在系统弹窗里点「允许」，回来后再点一次继续");
                return;
            }
        }
        java.util.List<AutoStartHelper.Entry> list = AutoStartHelper.availableEntries(this);
        if (!list.isEmpty()) {
            if (AutoStartHelper.open(this, list.get(0))) {
                toast("④ 请打开桌宠的自启动开关，回来后就全部完成了");
                return;
            }
        }
        toast("全部能自动申请的项都已完成。若仍会掉，请手动设置自启动页。");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
