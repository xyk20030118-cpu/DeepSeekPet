package com.deepseek.pet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌宠对话窗口：一个半透明悬浮 Activity，标题栏 + 消息列表 + 输入框。
 * 接入 DeepSeek /chat/completions，性格由 Prefs.getPersona 提供。
 */
public class ChatActivity extends Activity {

    /** 最多保留的上下文轮数（双向消息数），避免请求过大 */
    private static final int MAX_HISTORY = 16;

    private LinearLayout msgBox;
    private ScrollView scroll;
    private EditText input;
    private Button sendBtn;

    private final List<String[]> history = new ArrayList<>(); // [role, text]
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean sending = false;
    private float density = 3f;
    /** 当前主题令牌（跟随玻璃/新拟态 × 浅色/深色） */
    private Tokens.Set T;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        ApiClient.init(this);
        ApiClient.setBaseUrl(Prefs.getBaseUrl(this));
        // ★ setTheme 必须在 requestWindowFeature / setContentView **之前**，
        //   否则不生效。见 Tokens.applyPlatformTheme 的说明。
        Tokens.applyPlatformTheme(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        T = Tokens.of(this);

        // 悬浮小窗样式
        Window w = getWindow();
        // ★ 窗口本身保持透明（这是个悬浮小窗，圆角外的区域要透出桌面），
        //   但**卡片**会铺上主题色 —— 所以深色模式下不会再有白底闪烁。
        w.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.94);
        lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.62);
        lp.dimAmount = 0.35f;
        w.setAttributes(lp);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        setContentView(buildUi());

        // 载入连续对话记忆：只回显最近 N 条（上限可调）
        int showLimit = Math.max(MAX_HISTORY, ChatMemory.ctxLimit(this));
        List<String[]> mem = ChatMemory.recent(this, showLimit);
        if (mem != null && !mem.isEmpty()) {
            for (String[] h : mem) {
                if (h == null || h.length < 2) continue;
                history.add(new String[]{h[0], h[1]});
                say("user".equals(h[0]) ? "user" : "assistant", h[1], false);
            }
            say("system", "（已恢复上次的 " + history.size() + " 条对话记忆）", false);
        } else {
            say("system", "你好呀，我是" + petName() + "，点我聊天就可以啦～", false);
        }
    }

    private String petName() {
        // ★ 轮次81：跟着「主页自定义名字」走（原来写死小深）
        return Prefs.petName(this);
    }

    /** 用代码构建整个界面 */
    private View buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 12);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        // ★ 浮层用 UiKit 的 modal 表面 —— 与 `UiFactory.dialog()` 给弹窗套的那层
        //   同一套约定（那边也是 solidFill(T.modal, R_DIALOG)，轮次㊵ 起由设计层统一做）。
        //   上一版我用裸 round(T.surface)：颜色对了，但**表面形态**是扁平矩形，
        //   所以看起来"还是老 UI"。
        //   modal 令牌在四套主题里都有值（新拟态=bg、玻璃=半透明白/深蓝），
        //   所以这一个调用就同时跟随引擎与明暗。
        card.setBackground(UiKit.solidFill(this, T, T.modal, Tokens.R_DIALOG));

        // ---- 标题栏 ----
        // ★ 不再用"彩色横条"：当前 UI 的标题是**页头大标题 + 返回胶囊**。
        //   彩色横条是旧形态，留着它整页看着就不像这套 UI。
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, (int) (d * 2));

        TextView title = new TextView(this);
        title.setText("💬 和" + petName() + "聊天");
        title.setTextColor(T.text1);
        title.setTextSize(19);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(T.text2);
        close.setTextSize(20);
        close.setPadding(pad, 0, pad, 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(close);
        card.addView(bar);

        // ---- 消息滚动区 ----
        scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setPadding(pad, pad, pad, pad);
        scroll.addView(msgBox);
        card.addView(scroll);

        // ---- 输入区 ----
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setPadding(pad, (int) (d * 4), pad, pad);

        input = new EditText(this);
        input.setHint("说点什么…");
        // 输入框用 UiKit.sunken（凹陷面）—— 与设置页里"输入框是面板上的坑"
        // 这套形态一致；上一版用扁平描边矩形，是旧形态。
        input.setHintTextColor(T.text3);
        input.setTextSize(15);
        input.setTextColor(T.text1);
        input.setMaxLines(4);
        int ins = UiFactory.inset(this);
        input.setBackground(UiKit.sunken(this, T, Tokens.R_CHIP, ins));
        input.setPadding((int) (d * 16) + ins, (int) (d * 12) + ins,
                (int) (d * 16) + ins, (int) (d * 12) + ins);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(ilp);
        inputBar.addView(input);

        // 发送键走 UiFactory.button：它自带两种引擎的按压反馈与描边，
        // 手写渐变按钮在玻璃态下会显得"不是这套 UI 的东西"。
        sendBtn = UiFactory.button(this, T, "发送", true);
        sendBtn.setTextSize(15);
        sendBtn.setPadding((int)(d*22), 0, (int)(d*22), 0);
        sendBtn.setMinWidth(0);
        sendBtn.setMinimumWidth(0);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onSend(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (int) (d * 46));
        blp.setMarginStart((int) (d * 8));
        sendBtn.setLayoutParams(blp);
        inputBar.addView(sendBtn);

        card.addView(inputBar);
        return card;
    }

    private int px(float dp) { return (int) (dp * density + 0.5f); }
    /** 添加一条气泡消息 */
    private void say(final String role, final String text, final boolean isErr) {
        final boolean isUser = "user".equals(role);
        final boolean isSys = "system".equals(role);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        float r = px(16);
        int ins = UiFactory.inset(this);
        // ★ 气泡用**真实表面**（UiKit），不是扁平圆角矩形。
        //   上一版虽然换了令牌颜色，但表面仍是 GradientDrawable 扁平块，
        //   所以用户看着"还是老 UI" —— 颜色变了、形态没变。
        //   现在：用户气泡=凸起（本体强调色）、对方气泡=凹陷面、
        //   系统提示=凹陷面浅字、错误=凹陷面 + 危险色文字。
        //   新拟态下是浮雕、玻璃态下是半透明玻璃面，与设置页同一套。
        if (isErr) {
            tv.setTextColor(T.danger);
            tv.setBackground(UiKit.sunken(this, T, Tokens.R_CHIP, ins));
        } else if (isUser) {
            // 凸起本体用强调色 → 文字必须用 accentInk（令牌保证对比度）
            tv.setTextColor(T.accentInk);
            tv.setBackground(UiKit.raised(this, T, Tokens.R_CHIP, T.accent, ins));
        } else if (isSys) {
            tv.setTextColor(T.text3);
            tv.setTextSize(13);
            tv.setBackground(UiKit.sunken(this, T, Tokens.R_CHIP, ins));
        } else {
            tv.setTextColor(T.text1);
            tv.setBackground(UiKit.raised(this, T, Tokens.R_CHIP, T.surface, ins));
        }
        // 文字让开阴影余量，否则会压在光晕上
        tv.setPadding(px(15) + ins, px(11) + ins, px(15) + ins, px(11) + ins);
        tv.setTextIsSelectable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = isUser ? Gravity.END : Gravity.START;
        lp.topMargin = 12;
        lp.leftMargin = 8;
        lp.rightMargin = 8;
        tv.setLayoutParams(lp);
        // 限制气泡最大宽度
        tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.72f));

        msgBox.addView(tv);
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void onSend() {
        if (sending) return;
        final String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        // ★★ 这里是**完整对话窗口（聊天框）**，它**始终走 AI** ——
        //    用户 2026-10-07 明确：「是互动对话纯本地，不是聊天框发送的消息纯本地；
        //    聊天框发送消息默认走 api 且只能走 api」。
        //    所以这里**不**看 interactLocalOnly：纯本地**台词**只影响"互动对话"
        //    （戳/拖的反应 + 点桌宠弹出的问一句）。
        // ★★ 轮次64 补充：那句讲的是「纯本地**台词**」这个开关。用户在「服务商」里
        //    **显式选**「🤖 本地模型（手机本机跑，不联网）」是另一回事 ——
        //    那是在回答"这句话由谁来想"。所以这里统一用 ChatRouter：
        //    在线要有 Key，本地模型不要（本地后端下整场对话**一个 HTTP 都不发**）。
        if (!ChatRouter.ready(this)) {
            // ★★★ 轮次75：本地模型入口下线期间，**不许再引导用户去选一个看不到的选项**。
            Toast.makeText(this, "请先在 App 里设置 API Key"
                            + (LocalModelFeature.ENABLED
                                    ? "，或到「服务商」里选「\uD83E\uDD16 本地模型」" : ""),
                    Toast.LENGTH_LONG).show();
            return;
        }
        // ★ 本地后端首次要载入几百 MB ⇒ 先把等待反馈显出来（不需要载入时是 null）
        final String loadHint = ChatRouter.loadHintIfNeeded(this);

        input.setText("");
        say("user", text, false);
        // 记录用户消息（持久化连续对话记忆）
        history.add(new String[]{"user", text});
        trimHistory();
        ChatMemory.save(this, history);

        sending = true;
        sendBtn.setEnabled(false);
        sendBtn.setText("…");

        final String apiKey = Prefs.getApiKey(this);
        final String model = Prefs.getChatModel(this);
        final String persona = Prefs.personaForPet(this);
        final List<String[]> snap = new ArrayList<>(history);
        // 最后一条是本次用户输入，chat() 会单独加，故从历史中剔除
        if (!snap.isEmpty() && "user".equals(snap.get(snap.size() - 1)[0])) {
            snap.remove(snap.size() - 1);
        }

        final TextView typing = addTyping();
        final String factsPrompt = ChatMemory.factsPrompt(this);
        new Thread(new Runnable() {
            @Override public void run() {
                if (loadHint != null) {
                    ui.post(new Runnable() {
                        @Override public void run() { typing.setText(loadHint); }
                    });
                }
                // ★ 联网检索（待办 0d 路线 C）：开关开着且是"要新信息的问句"时先搜一次。
                //   sendText 只发给模型；界面上的用户消息与历史都用**原话**（text）。
                //   ★★ 轮次64：本地后端**绝不联网检索**（判据统一走 ChatRouter.webAllowed）。
                final boolean webOn = ChatRouter.webAllowed(ChatActivity.this)
                        && Prefs.webSearch(ChatActivity.this);
                if (webOn) {
                    ui.post(new Runnable() {
                        @Override public void run() { typing.setText("🌐 联网检索中…"); }
                    });
                }
                final String sendText = webOn ? WebClient.augment(ChatActivity.this, text) : text;
                final WebClient.Last web = webOn ? WebClient.lastRun() : null;
                // ★★ 本地后端**流式出话**：逐段把已经生成的部分放进"正在思考…"那个气泡
                //    （生成结束后再交给 say() 显示最终那句）。在线链是 stream:false ⇒ 传 null。
                final LocalLlm.TokenSink sink = ChatRouter.isLocal(ChatActivity.this)
                        ? LocalChat.throttledSink(new LocalChat.PieceSink() {
                            @Override public void onPiece(final String soFar) {
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        // ★ 回调里**只做 append**（这里只是把攒到的文本画出来）
                                        typing.setText(soFar);
                                        scroll.post(new Runnable() {
                                            @Override public void run() {
                                                scroll.fullScroll(View.FOCUS_DOWN);
                                            }
                                        });
                                    }
                                });
                            }
                        })
                        : null;
                final ApiClient.ChatResult res =
                        ChatRouter.chat(ChatActivity.this, apiKey, model, persona,
                                factsPrompt, snap, sendText, sink);
                ui.post(new Runnable() {
                    @Override public void run() {
                        msgBox.removeView(typing);
                        sending = false;
                        sendBtn.setEnabled(true);
                        sendBtn.setText("发送");
                        // 用户开了联网就该知道"这次没联上"，别让它悄悄退化
                        if (web != null && web.ran && !web.ok) {
                            Toast.makeText(ChatActivity.this, "🌐 这次没联上网：" + web.reason,
                                    Toast.LENGTH_LONG).show();
                        }
                        if (res != null && res.ok) {
                            say("assistant", res.reply, false);
                            history.add(new String[]{"assistant", res.reply});
                            trimHistory();
                            ChatMemory.save(ChatActivity.this, history);
                            // 异步整理记忆点（节流）★ 本地后端跳过：它走的是在线链，
                            // 本地必没有 Key ⇒ 只会白起一个必然失败的 HTTP 请求。
                            if (!ChatRouter.isLocal(ChatActivity.this)) {
                                MemoryFacts.maybeUpdateAsync(ChatActivity.this, apiKey, model, persona,
                                        ChatMemory.recent(ChatActivity.this, ChatMemory.ctxLimit(ChatActivity.this)));
                            }
                        } else {
                            say("assistant", "（出错了：" + (res == null ? "没有拿到回复" : res.error) + "）", true);
                            // ★★★ 轮次68 建立 / 轮次69 两态：**开着 GPU** 失败 ⇒
                            //   给一颗一键改成「关（纯 CPU）」的按钮
                            //   （气泡里已经有完整原因；这里是"可操作"的那一半）。
                            GpuModeUi.showFallbackPromptIfNeeded(ChatActivity.this,
                                    UiFactory.dialog(ChatActivity.this));
                        }
                    }
                });
            }
        }, "pet-chat").start();
    }

    private TextView addTyping() {
        TextView tv = new TextView(this);
        tv.setText("正在思考…");
        tv.setTextSize(14);
        tv.setTextColor(T.text3);
        // 同样走真实表面（凹陷面），与其它气泡一致
        int ins = UiFactory.inset(this);
        tv.setBackground(UiKit.sunken(this, T, Tokens.R_CHIP, ins));
        tv.setPadding(24 + ins, 16 + ins, 24 + ins, 16 + ins);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        lp.leftMargin = 8;
        tv.setLayoutParams(lp);
        msgBox.addView(tv);
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
        return tv;
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY) history.remove(0);
    }
}
