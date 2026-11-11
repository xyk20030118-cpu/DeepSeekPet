package com.deepseek.pet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 历史对话查看页：把 ChatMemory 里记住的全部往来按时间顺序列出，
 * 支持「清空记忆」「继续对话（跳回聊天窗）」。
 *
 * <h3>★ 为什么要跟着 App 主题走（用户要求）</h3>
 * 本页原先是一整套**硬编码颜色**（{@code Color.WHITE}、{@code #5B8DEF}、
 * {@code #F0F0F3}…），而且清单里挂的是系统主题
 * {@code @android:style/Theme.Light.NoTitleBar} —— 于是不管你选
 * 玻璃态/新拟态、浅色/深色，它永远是那个固定的浅蓝样式，
 * 和 App 其它页面完全不搭。
 *
 * <p>现在全部改用 {@link Tokens} 的语义色（背景/卡片/文字/强调），
 * 并在运行时把窗口背景也设成主题色 —— 否则透明窗口下会露出
 * 系统主题的白底，深色模式下尤其刺眼。
 */
public class HistoryActivity extends Activity {

    private LinearLayout msgBox;
    private ScrollView scroll;
    private Tokens.Set T;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ★ 必须在 setContentView 之前：让系统对话框（如「清空记忆」确认框）
        //   也跟着明暗走，否则深色模式下会弹出一个白框。
        Tokens.applyPlatformTheme(this);
        T = Tokens.of(this);
        // ★ 窗口自身也要上色：清单里挂的是系统主题，
        //   不改的话深色模式下边缘会露白。
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(T.bg));
        setContentView(buildUi());
        render();
    }

    private String petName() {
        return Prefs.currentPresetName(this);
    }

    private View buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 12);

        // ★ 整页铺 Mesh 背景 —— 与设置页同一个 {@code decoratePage}。
        //   上一版我只把颜色换成令牌，卡片仍是**扁平圆角矩形**，
        //   所以看起来"还是老 UI"：颜色变了、**表面形态没变**。
        //   当前 UI 的卡片是 UiKit.card（新拟态=浮雕 / 玻璃态=半透明玻璃面），
        //   这里必须走同一套，形态才会一致。
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        UiFactory.decoratePage(page, this, T);

        // 页头：与设置页同样的"大标题"排布
        page.addView(UiFactory.header(this, T, "🕘 对话历史",
                "共 " + ChatMemory.load(this).size() + " 条往来记录"));

        // ---- 消息卡片（UiKit.card：浮雕/玻璃面）----
        LinearLayout card = UiFactory.card(this, T);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.setMargins(pad, 0, pad, 0);
        card.setLayoutParams(clp);

        // ---- 消息滚动区 ----
        scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setPadding(0, 0, 0, 0);
        scroll.addView(msgBox);
        card.addView(scroll);
        page.addView(card);

        // ---- 底部操作栏 ----
        // 用 actionRow 的形态（图标徽章 + 文字）而不是裸 Button，
        // 与设置页里的可点行保持同一套观感。
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setGravity(Gravity.CENTER_VERTICAL);
        ops.setPadding(pad, (int) (d * 8), pad, (int) (d * 12));

        Button cont = UiFactory.button(this, T, "💬 继续对话", true);
        cont.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cont.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(HistoryActivity.this, ChatActivity.class));
            }
        });
        ops.addView(cont);

        Button clear = UiFactory.button(this, T, "🗑 清空记忆", false);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp2.setMarginStart((int) (d * 8));
        clear.setLayoutParams(clp2);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                UiFactory.dialog(HistoryActivity.this)
                        .setTitle("清空对话记忆")
                        .setMessage("确定要删除桌宠记住的所有对话吗？此操作不可恢复。")
                        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dlg, int w) {
                                ChatMemory.clear(HistoryActivity.this);
                                Toast.makeText(HistoryActivity.this, "对话记忆已清空", Toast.LENGTH_SHORT).show();
                                render();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        ops.addView(clear);
        page.addView(ops);

        // 返回：与设置页二级页一致的「返回」胶囊
        Button back = UiFactory.button(this, T, "返回", false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout backRow = new LinearLayout(this);
        backRow.setOrientation(LinearLayout.HORIZONTAL);
        backRow.setPadding(pad, 0, pad, (int) (d * 6));
        backRow.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(backRow, 1);      // 插在页头之后

        return page;
    }

    /** 渲染全部历史记忆 */
    private void render() {
        msgBox.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 10);

        List<String[]> all = ChatMemory.load(this);
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有对话记忆呢～\n和" + petName() + "聊几句，这里就会记录下来。");
            empty.setTextColor(T.text3);       // 次要文字用令牌
            empty.setTextSize(14);
            empty.setPadding(pad, (int) (d * 20), pad, pad);
            empty.setGravity(Gravity.CENTER);
            msgBox.addView(empty);
            return;
        }

        // 顶部信息条
        TextView info = new TextView(this);
        info.setText("共 " + all.size() + " 条 · 最多保留 " + ChatMemory.MAX_ITEMS + " 条");
        info.setTextColor(T.text3);
        info.setTextSize(12);
        info.setPadding(pad, (int) (d * 4), pad, (int) (d * 10));
        msgBox.addView(info);

        for (String[] h : all) {
            if (h == null || h.length < 2) continue;
            addBubble(h[0], h[1]);
        }
    }

    private void addBubble(String role, String text) {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 10);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        // 气泡文字统一用 text1：两种气泡底色都由令牌保证与其对比度达标，
        // 所以没必要按角色换文字色（换了反而可能不达标）。
        tv.setTextColor(T.text1);
        boolean isUser = "user".equals(role);
        // ★ 气泡用**真实表面**，不是扁平圆角：
        //   用户气泡 = UiKit.raised（凸起，本体用 accentSoft 上色）
        //   对方气泡 = UiKit.sunken（凹陷面）
        //   这样在新拟态下是浮雕、在玻璃态下是半透明玻璃面 ——
        //   与设置页的卡片同一套形态。
        //   inset 必须显式传（见 UiKit.raised 的说明：两套引擎的阴影余量不同，
        //   用各自默认值会让同尺寸控件渲染出不同大小）。
        int inset = UiFactory.inset(this);
        if (isUser) {
            tv.setBackground(UiKit.raised(this, T, Tokens.R_CHIP, T.accentSoft, inset));
        } else {
            tv.setBackground(UiKit.sunken(this, T, Tokens.R_CHIP, inset));
        }
        // 文字要让开阴影余量，否则会压在光晕上
        tv.setPadding(pad + inset, pad + inset, pad + inset, pad + inset);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (d * 4), 0, (int) (d * 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (isUser) {
            row.setGravity(Gravity.END);
            lp.gravity = Gravity.END;
            row.addView(tv, lp);
        } else {
            lp.gravity = Gravity.START;
            row.addView(tv, lp);
        }
        msgBox.addView(row);
    }
}
