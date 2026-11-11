package com.deepseek.pet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 快捷提问：不进入 App 界面，只在屏幕底部浮出一张「输入条」。
 * 采用「全屏透明窗 + 手动键盘高度监听」，把卡片稳稳抬到软键盘之上，永不被遮挡。
 * 发送后关闭自己并广播给桌宠，由 FloatingService 在桌宠旁用气泡显示回答。
 */
public class QuickAskActivity extends Activity {

    private EditText input;
    private FrameLayout root;
    private LinearLayout card;
    /** 当前主题令牌（跟随玻璃/新拟态 × 浅色/深色） */
    private Tokens.Set T;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ★ 必须在 requestWindowFeature / setContentView 之前生效
        Tokens.applyPlatformTheme(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window w = getWindow();
        // 防止输入法把输入条顶出/盖住：让窗口整体随输入法上移
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        w.setBackgroundDrawableResource(android.R.color.transparent);
        // 全屏透明窗：这样 getWindowVisibleDisplayFrame 能准确报告键盘高度，
        // 我们再手动抬升卡片。ADJUST_RESIZE 让窗口底部随键盘收缩。
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        w.setWindowAnimations(android.R.style.Animation_Toast);
        if (Build.VERSION.SDK_INT >= 21) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        }

        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.BOTTOM;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        w.setAttributes(lp);

        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 点击卡片以外的透明区域即关闭
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishQuiet(); }
        });
        root.addView(buildUi());
        setContentView(root);

        // 全屏窗下准确监听软键盘高度，动态把卡片抬到键盘之上
        final View decor = w.getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                Rect r = new Rect();
                decor.getWindowVisibleDisplayFrame(r);
                int screenH = decor.getRootView().getHeight();
                int keyboardH = screenH - r.bottom;
                if (keyboardH < 0) keyboardH = 0;
                // 阈值过滤掉状态栏/导航栏的小抖动
                if (keyboardH > screenH / 5) {
                    setCardBottomMargin(keyboardH + dp(6));
                } else {
                    setCardBottomMargin(dp(10));
                }
            }
        });

        focusInput();
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private void setCardBottomMargin(int marginPx) {
        if (card == null) return;
        ViewGroup.LayoutParams p = card.getLayoutParams();
        if (!(p instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) p;
        if (clp.bottomMargin == marginPx) return;
        clp.bottomMargin = marginPx;
        card.setLayoutParams(clp);
    }

    private View buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 14);
        T = Tokens.of(this);

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int ins = UiFactory.inset(this);
        card.setPadding(pad + ins, pad + ins, pad + ins, pad + ins);
        // ★ 浮层用 UiKit 的 modal 表面 —— 与 `UiFactory.dialog()` 给弹窗套的那层
        //   同一套约定（轮次㊵ 起弹窗的主题化统一在 UiFactory 里做）。
        //   上一版虽然换了令牌颜色，但表面是扁平
        //   GradientDrawable（圆角 + 描边），所以看着"还是老 UI"。
        //   modal 令牌在四套主题里都有值，一次调用同时跟随引擎与明暗。
        card.setBackground(UiKit.solidFill(this, T, T.modal, Tokens.R_DIALOG));
        card.setClickable(true); // 拦截点击，避免点卡片触发 root 的关闭

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (d * 10);
        clp.setMargins(m, m, m, m);
        clp.gravity = Gravity.BOTTOM;
        card.setLayoutParams(clp);

        TextView tip = new TextView(this);
        tip.setText("💬 问问" + Prefs.petName(this) + " —— 回答会以气泡显示");
        tip.setTextSize(13);
        tip.setTextColor(T.text3);
        tip.setPadding(0, 0, 0, (int) (d * 8));
        card.addView(tip);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        input = new EditText(this);
        input.setHint("想说什么…");
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setHintTextColor(T.text3);
        input.setTextColor(T.text1);
        // 输入框走 UiKit.sunken（凹陷面）——与设置页"输入框是面板上的坑"一致
        input.setBackground(UiKit.sunken(this, T, Tokens.R_CHIP, ins));
        input.setPadding(pad + ins, (int) (d * 10) + ins,
                pad + ins, (int) (d * 10) + ins);
        input.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); return true; }
                return false;
            }
        });
        row.addView(input);

        // 发送键同样复用 UiFactory.button（自带两种引擎的按压反馈）
        Button send = UiFactory.button(this, T, "发送", true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMarginStart((int) (d * 8));
        send.setLayoutParams(blp);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submit(); }
        });
        row.addView(send);

        card.addView(row);
        return card;
    }

    private void focusInput() {
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.requestFocus();
        final Runnable show = new Runnable() {
            @Override public void run() {
                if (input == null) return;
                input.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        };
        input.postDelayed(show, 100);
        input.postDelayed(show, 350);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && input != null) {
            input.postDelayed(new Runnable() {
                @Override public void run() {
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 80);
        }
    }

    private void submit() {
        String q = input.getText().toString().trim();
        if (TextUtils.isEmpty(q)) { finishQuiet(); return; }
        if (!Prefs.hasApiKey(this)) {
            Toast.makeText(this, "请先在 App 里设置 API Key", Toast.LENGTH_LONG).show();
            finishQuiet();
            return;
        }
        Intent ask = new Intent(Prefs.ACTION_ASK);
        ask.putExtra("q", q);
        ask.setPackage(getPackageName());
        sendBroadcast(ask);
        finishQuiet();
    }

    private void finishQuiet() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && input != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) {}
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        finishQuiet();
    }
}
