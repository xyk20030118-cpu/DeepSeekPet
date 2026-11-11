package com.deepseek.pet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 开放平台用量面板。
 *
 * 数据来自 platform.deepseek.com 官网接口（需先网页登录）：
 *   今日/近 7 天/近 30 天的 token 用量、缓存命中率、按模型与按 Key 的分布、费用。
 * 与官网「用量统计」页口径一致，用于核对桌宠本地估算是否准确。
 *
 * <h3>★ 为什么这一页要重做（轮次㊶ · 用户要求"三级菜单跟随主题切换"的同族缺口）</h3>
 * 本页是**最后一个没接设计层**的页面（`UiFactory`/`UiKit`/`Tokens` 一处都没用）：
 * <ul>
 *   <li>整页底色写死 {@code 0xFFF5F8FC}、卡片用 {@code R.drawable.bubble_chat}
 *       （**静态 XML**：白→浅蓝渐变 + 淡蓝描边）⇒ 深色模式 / 玻璃皮肤下**整页还是白的**；</li>
 *   <li>所有文字色写死（{@code 0xFF1B3A6B} / {@code 0xFF44566C} / …）⇒ 同样不跟随主题。</li>
 * </ul>
 * 现在全部改走令牌：底色走 {@link UiFactory#decoratePage}（与设置页同一套 Mesh），
 * 卡片走 {@link UiFactory#card}（新拟态=浮雕面 / 玻璃态=玻璃面），按钮走
 * {@link UiFactory#button}，文字色走**语义角色**（见 {@link #ROLE_TITLE} 等）
 * —— 本文件里**再出现一个 {@code 0xFF……} 都算回潮**（`build/verify_ui_theme.py` 钉着）。
 */
public class UsageActivity extends Activity {

    private LinearLayout box;
    private TextView head;
    /** buildPanel() 留在 box 里的头部控件数量，去掉它们就是卡片区 */
    private int headerCount = 3;
    private float d;
    private int days = 0;            // 0=今天, 7=近7天, 30=近30天
    private boolean loading = false;
    /** ★ 令牌：皮肤（新拟态/玻璃）× 明暗，页面里所有颜色都从它取 */
    private Tokens.Set T;

    // ★ 文字的**语义角色** —— 取代原来散落在各处的 0xFF…… 硬编码色。
    //   用角色而不是直接传颜色，是为了"换主题时不用逐个调用点改"
    //   （原来 24 处硬编码色，漏一处就是深色下一块看不清的字）。
    private static final int ROLE_TITLE = 0;    // 卡片小标题 / 强调数值
    private static final int ROLE_BODY = 1;     // 正文
    private static final int ROLE_DIM = 2;      // 次级说明
    private static final int ROLE_DANGER = 3;   // 错误

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // ★ 必须在 setContentView 之前：让系统对话框也跟着明暗走
        Tokens.applyPlatformTheme(this);
        T = Tokens.of(this);
        d = getResources().getDisplayMetrics().density;
        // ★ 窗口自身也要上色：清单里挂的是系统主题，不改的话深色模式下边缘会露白
        //   （与 HistoryActivity / ChatActivity 同一套做法）。
        getWindow().setBackgroundDrawable(new ColorDrawable(T.bg));

        ScrollView scroll = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // ★ 整页铺主题背景（玻璃态是 Mesh 光斑，新拟态是纯色）—— 原来这里是
        //   `scroll.setBackgroundColor(0xFFF5F8FC)`，一个写死的浅色。
        UiFactory.decoratePage(box, this, T);
        scroll.addView(box);
        setContentView(scroll);

        if (!PlatformClient.isLoggedIn(this)) {
            buildLoginPrompt();
        } else {
            buildPanel();
        }
    }

    /** 语义角色 → 令牌色 */
    private int colorOf(int role) {
        if (role == ROLE_TITLE) return T.text1;
        if (role == ROLE_BODY) return T.text2;
        if (role == ROLE_DANGER) return T.danger;
        return T.text3;
    }

    private int sidePad() {
        return (int) (d * 14);
    }

    // ==================== 未登录 ====================

    private void buildLoginPrompt() {
        box.removeAllViews();

        box.addView(UiFactory.header(this, T, "🔗 连接开放平台",
                "登录一次就能读到官网的真实用量"));

        LinearLayout info = UiFactory.card(this, T);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(sidePad(), 0, sidePad(), (int) (d * 12));
        info.setLayoutParams(ilp);
        line(info,
            "登录后可以读到官网的真实用量：\n\n"
          + "• 今日 / 近 7 天 / 近 30 天的 token 用量\n"
          + "• 缓存命中率、请求次数\n"
          + "• 按模型、按 API Key 的分布\n"
          + "• 官网账目里的真实花费\n\n"
          + "—— 为什么必须登录 ——\n"
          + "API Key 只能调 api.deepseek.com（对话、余额），"
          + "拿不到按天的明细用量。官网的用量接口只认网页登录态，"
          + "所以需要打开官方登录页登录一次。\n\n"
          + "登录全程在官方页面内完成，本应用不读取你的账号密码；"
          + "登录凭证只保存在本机应用私有目录，只发往 platform.deepseek.com。\n\n"
          + "⚠️ 本应用为第三方独立开发的非官方工具，与 DeepSeek／深度求索公司"
          + "无隶属、赞助或背书关系。点击登录将先弹出《登录授权说明》，需你明示同意。",
            ROLE_BODY, 13);
        box.addView(info);

        Button login = UiFactory.button(this, T,
                "🌐 去登录（打开官方页面 · 需同意授权说明）", true);
        login.setTextSize(15);
        login.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivityForResult(
                        new Intent(UsageActivity.this, PlatformLoginActivity.class), 1001);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(sidePad(), 0, sidePad(), (int) (d * 8));
        box.addView(login, lp);

        Button close = UiFactory.button(this, T, "返回", false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(sidePad(), 0, sidePad(), (int) (d * 12));
        box.addView(close, clp);
    }

    // ==================== 已登录 ====================

    private void buildPanel() {
        box.removeAllViews();

        box.addView(UiFactory.header(this, T, "📊 官网用量",
                "数据来自 platform.deepseek.com（与官网口径一致）"));

        // 刷新按钮
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(sidePad(), 0, sidePad(), (int) (d * 6));
        bar.setLayoutParams(blp);
        Button refresh = UiFactory.button(this, T, "刷新", false);
        refresh.setTextSize(13);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(bar);

        // 区间切换（当前档用主按钮色，其余是普通按钮）
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        int[] vals = {0, 7, 30};
        String[] names = {"今日", "近 7 天", "近 30 天"};
        for (int i = 0; i < vals.length; i++) {
            final int v = vals[i];
            Button b = UiFactory.button(this, T, names[i], days == v);
            b.setTextSize(13);
            b.setEnabled(days != v);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { days = v; buildPanel(); load(); }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bp.setMargins((int) (d * 3), 0, (int) (d * 3), 0);
            tabs.addView(b, bp);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(sidePad() - (int) (d * 3), (int) (d * 6),
                sidePad() - (int) (d * 3), 0);
        box.addView(tabs, tlp);

        head = UiFactory.hint(this, T, "");
        head.setPadding(sidePad(), (int) (d * 8), sidePad(), (int) (d * 4));
        box.addView(head);

        // 头部控件到此为止，后面都是可重新加载的卡片
        headerCount = box.getChildCount();

        load();
    }

    private LinearLayout card(String title) {
        LinearLayout c = UiFactory.card(this, T);
        // ★ 原来这里 `setBackgroundResource(R.drawable.bubble_chat)`：
        //   那是**静态 XML**（写死白→浅蓝渐变），深色/玻璃皮肤下永远是白卡片。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(sidePad(), 0, sidePad(), (int) (d * 12));
        c.setLayoutParams(lp);
        TextView t = UiFactory.sectionLabel(this, T, title);
        c.addView(t);
        return c;
    }

    private TextView line(LinearLayout parent, String text, int role, float size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(colorOf(role));
        t.setLineSpacing(0, 1.2f);
        parent.addView(t);
        return t;
    }

    private void load() {
        if (loading) return;
        loading = true;
        head.setText("正在读取官网用量…");
        // 清掉上次的卡片，只留头部控件。
        // ★ 这里必须用 buildPanel() 实际留下的控件数，原来写死成 4，
        //   结果是上一轮的「总览」卡片永远不会被移除，
        //   新一轮又把总览加在它后面 → 界面上出现两份「总览」。
        while (box.getChildCount() > headerCount) {
            box.removeViewAt(box.getChildCount() - 1);
        }

        final int back = days;
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                PlatformClient.Usage usage = null;
                PlatformClient.Summary sum = null;
                Map<String, String> keys = new LinkedHashMap<String, String>();
                try {
                    usage = PlatformClient.fetchUsage(UsageActivity.this, back);
                    keys = PlatformClient.fetchApiKeys(UsageActivity.this);
                    if (back == 30) sum = PlatformClient.fetchSummary(UsageActivity.this);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fe = err;
                final PlatformClient.Usage fu = usage;
                final PlatformClient.Summary fs = sum;
                final Map<String, String> fk = keys;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        if (fe != null || fu == null) renderError(fe);
                        else render(fu, fs, fk);
                    }
                });
            }
        }).start();
    }

    private void renderError(String msg) {
        head.setText("");
        LinearLayout c = card("⚠️ 读取失败");
        line(c, String.valueOf(msg), ROLE_DANGER, 13);
        if (msg != null && (msg.contains("token") || msg.contains("Token")
                || msg.contains("Authorization") || msg.contains("40003"))) {
            line(c, "\n登录可能已过期，请重新登录。", ROLE_BODY, 13);
            Button b = UiFactory.button(this, T, "重新登录", true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    PlatformClient.clearToken(UsageActivity.this);
                    startActivityForResult(
                            new Intent(UsageActivity.this, PlatformLoginActivity.class), 1001);
                }
            });
            c.addView(b);
        }
        box.addView(c);
    }

    private void render(PlatformClient.Usage u, PlatformClient.Summary sum,
                        Map<String, String> keys) {
        long in = u.totalIn(), out = u.totalOut(), hit = u.totalHit(), miss = u.totalMiss();
        long total = in + out;
        double cost = u.totalCost();
        head.setText("分桶：" + PlatformClient.bucketName(u.bucketMode)
                + "　·　登录于 " + PlatformClient.loginTimeText(this));

        if (total <= 0 && u.totalReq() <= 0) {
            LinearLayout c = card("这段时间还没有用量");
            line(c, "官网没有返回任何调用记录。\n如果刚刚才聊过天，稍等一会儿刷新试试。",
                    ROLE_BODY, 13);
            box.addView(c);
            return;
        }

        // ---- 总览 ----
        LinearLayout c1 = card("总览");
        float rate = in > 0 ? (float) hit / (float) in : -1f;
        line(c1, "总 token　" + UsageStats.k(total), ROLE_TITLE, 16);
        line(c1, "输入 " + UsageStats.k(in) + "　·　输出 " + UsageStats.k(out), ROLE_BODY, 13);
        line(c1, "请求 " + u.totalReq() + " 次", ROLE_BODY, 13);
        if (rate >= 0) {
            line(c1, "缓存命中率　" + String.format(Locale.US, "%.1f%%", rate * 100f),
                    ROLE_TITLE, 14);
            line(c1, "命中 " + UsageStats.k(hit) + "　·　未命中 " + UsageStats.k(miss),
                    ROLE_DIM, 12);
        }
        if (cost > 0) {
            line(c1, "官网花费　" + PlatformClient.money(cost, "CNY"), ROLE_TITLE, 15);
        }
        box.addView(c1);

        // ---- 按模型 ----
        LinkedHashMap<String, long[]> byModel = new LinkedHashMap<String, long[]>();
        for (PlatformClient.Series s : u.amount) {
            String m = s.model == null || s.model.length() == 0 ? "未知模型" : s.model;
            long[] v = byModel.get(m);
            if (v == null) { v = new long[4]; byModel.put(m, v); }
            v[0] += s.sumHit(); v[1] += s.sumMiss(); v[2] += s.sumOut(); v[3] += s.sumReq();
        }
        if (!byModel.isEmpty()) {
            LinearLayout c2 = card("按模型");
            for (Map.Entry<String, long[]> e : byModel.entrySet()) {
                long[] v = e.getValue();
                long tot = v[0] + v[1] + v[2];
                line(c2, "▸ " + e.getKey(), ROLE_TITLE, 14);
                line(c2, "　" + UsageStats.k(tot) + " tok　·　" + v[3] + " 次", ROLE_BODY, 12);
                long mi = v[0] + v[1];
                if (mi > 0) {
                    line(c2, "　命中 " + String.format(Locale.US, "%.1f%%",
                            v[0] * 100.0 / mi), ROLE_DIM, 12);
                }
            }
            box.addView(c2);
        }

        // ---- 按 Key ----
        LinkedHashMap<String, long[]> byKey = new LinkedHashMap<String, long[]>();
        for (PlatformClient.Series s : u.amount) {
            String id = s.trackingId == null || s.trackingId.length() == 0
                    ? "—" : s.trackingId;
            long[] v = byKey.get(id);
            if (v == null) { v = new long[4]; byKey.put(id, v); }
            v[0] += s.sumHit(); v[1] += s.sumMiss(); v[2] += s.sumOut(); v[3] += s.sumReq();
        }
        if (byKey.size() > 1 || (byKey.size() == 1 && !byKey.containsKey("—"))) {
            LinearLayout c3 = card("按 API Key");
            for (Map.Entry<String, long[]> e : byKey.entrySet()) {
                long[] v = e.getValue();
                String name = keys.get(e.getKey());
                if (name == null || name.length() == 0) {
                    name = e.getKey().length() > 8
                            ? e.getKey().substring(0, 8) + "…" : e.getKey();
                }
                line(c3, "▸ " + name, ROLE_TITLE, 13);
                line(c3, "　" + UsageStats.k(v[0] + v[1] + v[2]) + " tok　·　" + v[3] + " 次",
                        ROLE_BODY, 12);
            }
            box.addView(c3);
        }

        // ---- 账户（近30天时才拉，省一次请求）----
        if (sum != null) {
            LinearLayout c4 = card("账户");
            line(c4, "余额　" + PlatformClient.money(sum.balance, sum.currency),
                    ROLE_TITLE, 16);
            if (sum.bonus > 0) {
                line(c4, "赠送余额　" + PlatformClient.money(sum.bonus, sum.currency),
                        ROLE_BODY, 13);
            }
            line(c4, "本月用量　" + UsageStats.k(sum.monthlyUsage) + " tok", ROLE_BODY, 13);
            line(c4, "累计用量　" + UsageStats.k(sum.totalUsage) + " tok", ROLE_BODY, 13);
            if (sum.totalCost > 0) {
                line(c4, "累计花费　" + PlatformClient.money(sum.totalCost, sum.currency),
                        ROLE_BODY, 13);
            }
            box.addView(c4);
        }

        // ---- 底部操作 ----
        Button logout = UiFactory.button(this, T, "退出登录（清除本机凭证）", false);
        logout.setTextSize(13);
        logout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlatformClient.clearToken(UsageActivity.this);
                Toast.makeText(UsageActivity.this, "已清除登录凭证", Toast.LENGTH_SHORT).show();
                buildLoginPrompt();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(sidePad(), (int) (d * 4), sidePad(), (int) (d * 8));
        box.addView(logout, lp);

        Button back = UiFactory.button(this, T, "返回", false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(sidePad(), 0, sidePad(), (int) (d * 12));
        box.addView(back, blp);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1001) {
            if (PlatformClient.isLoggedIn(this)) buildPanel();
            else buildLoginPrompt();
        }
    }
}
