package com.deepseek.pet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.deepseek.pet.dsh.DshBridge;
import com.deepseek.pet.dsh.DshFeature;
import com.deepseek.pet.dsh.DshHarnessInstaller;
import com.deepseek.pet.dsh.DshFs;
import com.deepseek.pet.dsh.DshEngine;
import com.deepseek.pet.dsh.DshEngineBundled;
import com.deepseek.pet.dsh.DshHostState;
import com.deepseek.pet.dsh.DshNodeProcess;

import java.io.File;

/**
 * <b>工作模式 = DeepSeek Harness 本体</b>（不再自造界面）。
 *
 * <h3>这一页是什么</h3>
 * 上游 {@code deepseek-ai/deepseek-harness} 是一个 Node 宿主 + 浏览器前端：
 * 宿主跑起来会打印一行 {@code dsh web: http://127.0.0.1:43129/?token=…}，
 * 真正的界面就是宿主自己发出来的那份网页（{@code apps/web}）。
 *
 * <p>所以这里的做法是**把上游页面装进 WebView**：
 * <ol>
 *   <li>不重画任何业务界面 —— 会话、工具卡、审批、自动化、插件面板全是上游自己的；</li>
 *   <li>地址与 token 可在页内设置（手机上填电脑的 LAN 地址；用 adb 联调时用 {@code 127.0.0.1:43129} + {@code adb reverse}）；</li>
 *   <li>上游的"手机连电脑"配对页（{@code /reconnect}）原样工作：在电脑上点允许即可。</li>
 * </ol>
 *
 * <p>★ 与旧版的区别：旧版是**照规格重写的 Java 工作台**（已按用户要求删除）；
 * 这一版没有任何"我发明的界面"，只有一层 WebView 外壳。
 */
public class DshHarnessActivity extends Activity {

    private Tokens.Set T;
    private WebView web;
    private ProgressBar bar;
    private TextView status;
    private FrameLayout settingsLayer;
    private EditText urlInput;
    private TextView harnessVerStatus;
    private FrameLayout storageLayer;
    private static final int REQ_PICK_FILE = 7103;
    private ValueCallback<Uri[]> filePathCallback;
    private FrameLayout quickMenu;
    private FrameLayout confirmLayer;
    private Button oneTapBtn;
    private TextView oneTapStatus;
    private LinearLayout advancedBox;
    private TextView installStatus;
    private Button installBtn;
    private DshHarnessInstaller installer;
    private DshNodeProcess node;
    private final StringBuilder nodeLog = new StringBuilder();
    /** 当前 WebView 真正加载的地址（本机宿主 vs 用户配的远端，只有一个是真的）。 */
    private String currentUrl = "";
    /** 等引擎时的取消标记（置位后不再等下去，引擎本身继续在后台跑）。 */
    private volatile boolean engineWaitCancelled = false;

    /** 完整 Linux（引擎包）那一层的状态行。 */
    private TextView prootStatus;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Tokens.applyPlatformTheme(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        T = Tokens.of(this);
        DshBridge.init(this);
        DshBridge.setWorkMode(this, true);
        // 记下“真的创建了”的时刻：长按菜单那边要用它判断
        // “直接 startActivity 有没有真的生效”（被系统拦掉时不会报异常）。
        DshBridge.markWorkCreated();
        setContentView(buildUi());
        applyFullscreen();
        // 工作模式里把浮动桌宠收起来（它会在正文上漫游、挡字）；离开时按全局设置还回去
        if (DshBridge.prefs(this).getBoolean("dsh_hide_pet_in_work", false)) {
            sendPetAction(FloatingService.ACTION_HIDE_TEMP);
        }
        refreshInstallStatus();
        // 双轨，按优先级排：
        //   ① 用户显式配过地址 ⇒ 尊重用户的（连电脑那台）
        //   ② 装了「DSH Linux 引擎」⇒ 完整 Linux（真 bash/apt/git，上游一行不改）
        //   ③ 这只包自带 Node + 包树 ⇒ 原生直跑（离线可用，但没有 bash）
        //   ④ 都没有 ⇒ 老老实实说缺什么
        if (prepared() && DshEngine.installed(this)) {
            // ★ 配置过一次就永久生效：不再弹安装器、不再问任何东西，直接连已经装好的那个。
            //   （用户报障：切出去再回来又被要求"安装"一遍。）
            startEngineAndWait();
        } else if (DshEngineBundled.bundled(this) && !DshEngine.installed(this)) {
            // ★ 全离线版：**排在"连电脑"之前**。用户没有电脑、也没有服务器，
            //   所以只要随包带了 Linux 组件、而本机还没装，就先装它。
            //   （以前这里排在后面，只要曾经存过一个地址就永远走不到安装 —— 用户看到的就是
            //   "一直安装不了、也打不开"。）
            oneTapPrepare();
        } else if (hasConfiguredUrl()) {
            load(harnessUrl());
        } else if (DshEngine.installed(this)) {
            startEngineAndWait();
        } else if (canRunLocalHost()) {
            startLocalHost();
        } else {
            showStatus("还没装包树：点右上 ⚙ →「本地安装」；或装「DSH Linux 引擎」；"
                    + "也可以在这里填电脑的 Harness 地址");
            load(harnessUrl());
        }
    }

    /** 用户是否显式配过地址（空 = 没配过 ⇒ 走"手机自己跑宿主"）。 */
    private boolean hasConfiguredUrl() {
        String u = DshBridge.prefs(this).getString(DshFeature.KEY_HARNESS_URL, "");
        return u != null && u.trim().length() > 0;
    }

    /** 这只包能不能自己跑宿主：带 Node 运行时，且包树/DSH_HOME 已就位。 */
    private boolean canRunLocalHost() {
        try {
            return DshNodeProcess.available(this) && installer().isInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        // 从系统设置授完「访问所有文件」回来：自动继续
        if (storageLayer != null && storageLayer.getVisibility() == View.VISIBLE && storageOk()) {
            storageLayer.setVisibility(View.GONE);
            oneTapPrepare();
        }
        // 用户从系统安装器回来：装好了就自动接着跑，不用再点任何东西
    }

    /** 选文件回调：把用户选的 URI 交回给 WebView（附件上传靠它）。 */
    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_PICK_FILE) {
            Uri[] out = null;
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    out = new Uri[] {data.getData()};
                }
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(out);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(code, result, data);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        DshBridge.setWorkMode(this, false);
        if (Prefs.hidePetInApp(this)) sendPetAction(FloatingService.ACTION_HIDE_TEMP);
        else sendPetAction(FloatingService.ACTION_SHOW_TEMP);
        if (web != null) {
            try {
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignore) { }
            web = null;
        }
    }

    private void sendPetAction(String action) {
        try {
            Intent i = new Intent(this, FloatingService.class);
            i.setAction(action);
            startService(i);
        } catch (Throwable ignore) { }
    }

    // ==================== 地址与 token ====================

    /** 默认值：adb 联调用 127.0.0.1（配合 {@code adb reverse tcp:43129 tcp:43129}）。 */
    static String defaultUrl() {
        return "http://127.0.0.1:43129/";
    }

    private String harnessUrl() {
        String u = DshBridge.prefs(this).getString(DshFeature.KEY_HARNESS_URL, "");
        if (u == null || u.trim().length() == 0) u = defaultUrl();
        String tok = DshBridge.prefs(this).getString(DshFeature.KEY_HARNESS_TOKEN, "");
        if (tok != null && tok.trim().length() > 0 && u.indexOf("token=") < 0) {
            u = u + (u.indexOf('?') >= 0 ? "&" : "?") + "token=" + tok.trim();
        }
        return u;
    }

    // ==================== 界面（全屏：只有网页 + 一颗悬浮 ⚙ + 一条细进度） ====================

    /**
     * 全屏的工作模式外壳。
     *
     * <p>★ v8.0 起**去掉了原来那条顶栏**（⟳ / 标题 / 地址 / ⚙ / ✕）：它占掉一整行高度，
     * 而这一行里真正有用的只有 ⚙ —— 刷新可以用网页自己的刷新/下拉，退出用返回键。
     * 现在网页铺满整屏（连系统栏都收起来），只在右上角留一颗半透明悬浮 ⚙，
     * 底部留一条细到几乎看不见的状态条（显示当前地址 / 出错原因）。
     */
    private View buildUi() {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(T.bg);

        // ---- 细进度条（贴顶 3dp，加载完自动消失）----
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        shell.addView(bar, blp);

        // ---- 网页本体：铺满 ----
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setBackgroundColor(T.bg);
        web.setWebChromeClient(new WebChromeClient() {
            /**
             * ★ 用户报障：工作模式里“无法添加附件”。
             *
             * <p>原因：WebView 默认**不**处理 `&lt;input type=file&gt;`，不实现 onShowFileChooser
             * 就是“点了没反应”。这里拿系统选文件器（SAF）拉起来：
             * 不需要存储权限（用户选中哪个就授哪个），也不需要访客里能看到文件。
             */
            @Override public boolean onShowFileChooser(WebView v,
                                                       ValueCallback<Uri[]> callback,
                                                       FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    boolean multi = params != null
                            && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    if (params != null && params.getAcceptTypes() != null
                            && params.getAcceptTypes().length > 0
                            && params.getAcceptTypes()[0] != null
                            && params.getAcceptTypes()[0].length() > 0) {
                        i.putExtra(Intent.EXTRA_MIME_TYPES, params.getAcceptTypes());
                    }
                    if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, REQ_PICK_FILE);
                    return true;
                } catch (Throwable t) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override public void onProgressChanged(WebView v, int p) {
                bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                bar.setProgress(p);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // 只在这个 host 里跳；其余交给系统浏览器（别把 WebView 变成万能浏览器）
                if (url != null && url.startsWith("http") && !sameHost(url)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                        return true;
                    } catch (Throwable ignore) { }
                }
                return false;
            }

            @Override public void onPageFinished(WebView v, String url) {
                showStatus("");   // ★ 用户要求：左下角地址太挡，加载完就隐藏
            }

            @Override public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                showStatus("连不上：" + desc);
            }
        });
        shell.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 右上角悬浮 ⚙（半透明，不挡内容）----
        Button gear = smallBtn("⚙");
        // 颜色一律走设计令牌（页面主题断言：活代码里不许出现 0xAARRGGBB 字面量），
        // 半透明靠 setAlpha，不靠手写 alpha 通道。
        gear.setAlpha(0.55f);
        gear.setBackground(roundBg(T.surface, 999));
        // ★ 用户要求：右上角这颗按钮要**能拖动**（不同人习惯的落点不一样）。
        //   轻点 = 打开菜单；拖动 = 挪位置，松手记坐标，下次进来还在那儿。
        final int gearSize = dp(46);
        final FrameLayout.LayoutParams glp =
                new FrameLayout.LayoutParams(gearSize, gearSize, Gravity.TOP | Gravity.START);
        glp.leftMargin = dp(14);
        glp.topMargin = dp(14);
        shell.addView(gear, glp);
        shell.post(new Runnable() {
            @Override public void run() {
                int sx = DshBridge.prefs(DshHarnessActivity.this).getInt("dsh_gear_x", -1);
                int sy = DshBridge.prefs(DshHarnessActivity.this).getInt("dsh_gear_y", -1);
                if (sx < 0 || sy < 0) {
                    sx = Math.max(0, shell.getWidth() - gearSize - dp(14));
                    sy = dp(14);
                }
                glp.leftMargin = Math.min(sx, Math.max(0, shell.getWidth() - gearSize));
                glp.topMargin = Math.min(sy, Math.max(0, shell.getHeight() - gearSize));
                gear.setLayoutParams(glp);
            }
        });
        final float[] downAt = new float[2];
        final int[] startAt = new int[2];
        final boolean[] dragged = new boolean[1];
        final int slop = (int) (8 * getResources().getDisplayMetrics().density);
        gear.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downAt[0] = e.getRawX();
                        downAt[1] = e.getRawY();
                        startAt[0] = lp.leftMargin;
                        startAt[1] = lp.topMargin;
                        dragged[0] = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float mx = e.getRawX() - downAt[0];
                        float my = e.getRawY() - downAt[1];
                        if (!dragged[0] && (Math.abs(mx) > slop || Math.abs(my) > slop)) {
                            dragged[0] = true;
                        }
                        if (dragged[0]) {
                            int nx = (int) (startAt[0] + mx);
                            int ny = (int) (startAt[1] + my);
                            int maxX = Math.max(0, shell.getWidth() - v.getWidth());
                            int maxY = Math.max(0, shell.getHeight() - v.getHeight());
                            lp.leftMargin = Math.max(0, Math.min(nx, maxX));
                            lp.topMargin = Math.max(0, Math.min(ny, maxY));
                            v.setLayoutParams(lp);
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragged[0]) {
                            DshBridge.prefs(DshHarnessActivity.this).edit()
                                    .putInt("dsh_gear_x", lp.leftMargin)
                                    .putInt("dsh_gear_y", lp.topMargin).apply();
                        } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            showQuickMenu(true);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        // ---- 右上角只留一颗：点开是「设置 / 退出工作模式」菜单（用户要求合并）----


        // ---- 底部状态条：细、半透明，只写"现在连的是谁/怎么失败了" ----
        status = new TextView(this);
        status.setTextSize(10f);
        status.setTextColor(T.text1);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(dp(10), dp(5), dp(10), dp(5));
        status.setBackground(roundBg(T.surface, 999));
        status.setAlpha(0.85f);
        showStatus("");
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        slp.setMargins(dp(14), 0, dp(14), dp(14));
        shell.addView(status, slp);

        storageLayer = buildStorageLayer();
        shell.addView(storageLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        storageLayer.setVisibility(View.GONE);

        quickMenu = buildQuickMenu();
        shell.addView(quickMenu, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        quickMenu.setVisibility(View.GONE);

        settingsLayer = buildSettingsLayer();
        shell.addView(settingsLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        settingsLayer.setVisibility(View.GONE);
        return shell;
    }

    /**
     * 把系统栏也收起来，网页真正铺满整屏。
     *
     * <p>点一下屏幕边缘会临时浮出系统栏（immersive sticky），所以不影响下拉通知/返回手势。
     * 键盘（IME）不受影响：它不是 systemBars。
     */
    /**
     * 全屏 + 铺到刘海/挖孔区，并让窗口底色跟着主题走。
     *
     * <p>★ 修"顶部一条黑边"：那一条是**挖孔/状态栏区域没被我们窗口覆盖**（窗口底色还是主题的
     * 黑色）+ 没声明 layoutInDisplayCutoutMode。现在：窗口底色 = 设计令牌的 bg、
     * 允许画进挖孔区，再加上系统栏一起收起来。
     */
    private void applyFullscreen() {
        try {
            Window w = getWindow();
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(T.bg));
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
                        ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
        } catch (Throwable ignore) {
        }
        applyImmersive();
    }
    private void applyImmersive() {
        try {
            Window w = getWindow();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable ignore) {
            // 收不起来也不是致命问题：网页照样铺满，只是上面多一条系统栏
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 系统栏被别的应用/下拉通知叫出来之后，回到本页要重新收起来
        if (hasFocus) applyImmersive();
    }
    /**
     * 底部状态条：**只有真有事要说时才错显示**（进度 / 出错）。
     *
     * <p>★ 用户反馈：下左角那个地址太挡了 —— 现在它只在“正在装/出错”时出现，
     * 页面加载完就自动隐藏。
     */
    private void showStatus(String text) {
        if (status == null) return;
        boolean empty = text == null || text.trim().length() == 0;
        status.setText(empty ? "" : text);
        status.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** 一个圆角纯色背景（程序里画，不引 drawable 资源）。 */
    private android.graphics.drawable.Drawable roundBg(int argb, int radiusPx) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(argb);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** 设置层：粘贴宿主地址 + token（不弹对话框，项目里 DSH 部分本来就不许弹 AlertDialog）。 */
    private FrameLayout buildSettingsLayer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(T.scrim);
        layer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSettings(false); }
        });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(T.surface);
        box.setPadding(dp(16), dp(20), dp(16), dp(16));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        box.setLayoutParams(lp);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* 吃掉点击 */ }
        });

        // ==================== 一键区（普通用户只用这一颗按钮） ====================
        // ★ 为什么要重做：以前面板里摊着「宿主地址 / 下载并配置包树 / Node 自检 /
        //   本地启动宿主 / 取消等待 / 停止引擎…」一堆按钮和术语，普通用户根本不知道该点哪个，
        //   还会去点"下载并配置包树"然后吃一个 ConnectException（因为他们没有服务器）。
        //   现在：一个按钮做完"装组件 → 解载荷 → 起宿主 → 打开页面"，其余全收进「高级选项」。
        TextView heroTitle = new TextView(this);
        heroTitle.setText("一键准备并启动");
        heroTitle.setTextSize(16f);
        heroTitle.setTypeface(Typeface.DEFAULT_BOLD);
        heroTitle.setTextColor(T.text1);
        heroTitle.setPadding(0, 0, 0, dp(6));
        box.addView(heroTitle);

        oneTapBtn = smallBtn("⚡ 一键准备并启动（不用设置任何东西）");
        oneTapBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { oneTapPrepare(); }
        });
        LinearLayout.LayoutParams otp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.addView(oneTapBtn, otp);

        oneTapStatus = new TextView(this);
        oneTapStatus.setTextSize(12f);
        oneTapStatus.setTextColor(T.text3);
        oneTapStatus.setPadding(0, dp(6), 0, dp(2));
        oneTapStatus.setText("点上面那颗按钮就行；第一次会问你一次「安装」，然后全自动。");
        box.addView(oneTapStatus);

        // ---- Harness 版本：显示当前版本 + 下载/切换别的版本 ----
        //   用户要求：「可以选择下载不同的 deepseek harness 版本」。
        harnessVerStatus = new TextView(this);
        harnessVerStatus.setTextSize(12f);
        harnessVerStatus.setTextColor(T.text3);
        harnessVerStatus.setPadding(0, dp(6), 0, dp(4));
        box.addView(harnessVerStatus);

        final EditText verUrl = new EditText(this);
        verUrl.setHint("harness 包树 .tgz 地址（http/https 都可以）");
        verUrl.setTextSize(13f);
        verUrl.setTextColor(T.text1);
        verUrl.setSingleLine(true);
        verUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        verUrl.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.addView(verUrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button verGet = smallBtn("下载并切换到这个版本");
        verGet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String u = verUrl.getText().toString().trim();
                if (u.length() == 0) {
                    harnessVerStatus.setText("先填一个 .tgz 地址");
                    return;
                }
                harnessVerStatus.setText("正在下载并替换 /opt/harness…（rootfs 与 Node 不动，只换包树）");
                new Thread(new Runnable() {
                    @Override public void run() {
                        final boolean ok = com.deepseek.pet.dsh.linux.Engine.get(DshHarnessActivity.this)
                                .switchHarness(u, u);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                harnessVerStatus.setText(ok
                                        ? "已切换：" + u + "（宿主已重启）"
                                        : "切换失败，见 ⚙ → 高级选项 →「打开引擎 App」的日志");
                                refreshHarnessVersion();
                            }
                        });
                    }
                }, "harness-switch").start();
            }
        });
        LinearLayout.LayoutParams vglp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vglp.setMargins(0, dp(6), 0, 0);
        box.addView(verGet, vglp);

        refreshHarnessVersion();

        Button advToggle = smallBtn("高级选项 ▾");
        advToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (advancedBox == null) return;
                boolean show = advancedBox.getVisibility() != View.VISIBLE;
                advancedBox.setVisibility(show ? View.VISIBLE : View.GONE);
                ((Button) v).setText(show ? "高级选项 ▴" : "高级选项 ▾");
            }
        });
        LinearLayout.LayoutParams atp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        atp.setMargins(0, dp(10), 0, 0);
        box.addView(advToggle, atp);

        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        advancedBox = advanced;
        box.addView(advanced);

        TextView t1 = new TextView(this);
        t1.setText("Harness 宿主地址");
        t1.setTextSize(16f);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(T.text1);
        advanced.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText("填电脑上 `dsh web` 打印的那一行（含 token）。\n"
                + "手机：http://<电脑局域网IP>:43129/?token=…\n"
                + "adb 联调：先 `adb reverse tcp:43129 tcp:43129`，这里填 http://127.0.0.1:43129/");
        t2.setTextSize(12f);
        t2.setTextColor(T.text3);
        t2.setPadding(0, dp(6), 0, dp(8));
        advanced.addView(t2);

        urlInput = new EditText(this);
        urlInput.setHint("http://192.168.1.10:43129/?token=…");
        urlInput.setTextSize(14f);
        urlInput.setTextColor(T.text1);
        urlInput.setSingleLine(false);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        urlInput.setText(harnessUrl());
        urlInput.setBackground(roundRect(T.surfaceSunken));
        urlInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        advanced.addView(urlInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 本地安装 / 配置（把上游包树下载到这台手机上） ----
        TextView t3 = new TextView(this);
        t3.setText("本地安装（不依赖电脑）");
        t3.setTextSize(14f);
        t3.setTypeface(Typeface.DEFAULT_BOLD);
        t3.setTextColor(T.text1);
        t3.setPadding(0, dp(14), 0, dp(4));
        advanced.addView(t3);

        installStatus = new TextView(this);
        installStatus.setTextSize(12f);
        installStatus.setTextColor(T.text3);
        installStatus.setPadding(0, 0, 0, dp(6));
        advanced.addView(installStatus);

        installBtn = smallBtn("下载并配置 Harness 包树");
        installBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startInstall(); }
        });
        advanced.addView(installBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ★ 全离线版里这个按钮是**纯误导**：它去连地址栏那个地址（用户没有服务器 ⇒
        //   ConnectException: Failed to connect to /127.0.0.1:43129）。
        //   随包已经带了 Ubuntu + Node + 包树，这里只留一句说明，按钮收起来。
        if (DshEngineBundled.bundled(this)) {
            t3.setText("本机 Linux（随包自带，不用下载）");
            installBtn.setVisibility(View.GONE);
        }

        LinearLayout nodeRow = new LinearLayout(this);
        nodeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button check = smallBtn("Node 自检");
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runNodeSelfTest(); }
        });
        nodeRow.addView(check, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button local = smallBtn("本地启动宿主");
        local.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startLocalHost(); }
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.setMargins(dp(8), 0, 0, 0);
        nodeRow.addView(local, nlp);
        LinearLayout.LayoutParams nrp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nrp.setMargins(0, dp(8), 0, 0);
        advanced.addView(nodeRow, nrp);

        Button stopHost = smallBtn("停止本机宿主");
        stopHost.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopLocalHost(); }
        });
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shp.setMargins(0, dp(8), 0, 0);
        advanced.addView(stopHost, shp);

        Button cancelWait = smallBtn("取消等待（引擎继续在后台装）");
        cancelWait.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                engineWaitCancelled = true;
                showStatus("已取消等待");
            }
        });
        LinearLayout.LayoutParams cwp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cwp.setMargins(0, dp(8), 0, 0);
        advanced.addView(cancelWait, cwp);


        Button stopEngine = smallBtn("停止 Linux 引擎（省内存）");
        stopEngine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean ok = DshEngine.stop(DshHarnessActivity.this);
                showStatus(ok ? "已请求停止引擎" : "停不了引擎");
                refreshEngineStatus();
            }
        });
        LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sep.setMargins(0, dp(8), 0, 0);
        advanced.addView(stopEngine, sep);

        TextView t4 = new TextView(this);
        t4.setText("★ Node 运行时随 APK 走（Android 10+ 不允许从 App 私有目录执行二进制），"
                + "包树与配置在这里下载。");
        t4.setTextSize(11f);
        t4.setTextColor(T.text3);
        t4.setPadding(0, dp(4), 0, 0);
        advanced.addView(t4);

        // ---- 完整 Linux：由独立的「DSH Linux 引擎」包提供（本包 targetSdk 34 跑不了 proot） ----
        TextView t5 = new TextView(this);
        t5.setText("完整 Linux 模式（独立引擎包）");
        t5.setTextSize(14f);
        t5.setTypeface(Typeface.DEFAULT_BOLD);
        t5.setTextColor(T.text1);
        t5.setPadding(0, dp(14), 0, dp(4));
        advanced.addView(t5);

        prootStatus = new TextView(this);
        prootStatus.setTextSize(12f);
        prootStatus.setTextColor(T.text3);
        prootStatus.setText("正在查询引擎…");
        prootStatus.setPadding(0, 0, 0, dp(6));
        advanced.addView(prootStatus);

        LinearLayout engineRow = new LinearLayout(this);
        engineRow.setOrientation(LinearLayout.HORIZONTAL);
        Button engineStart = smallBtn("启动 / 重启引擎");
        engineStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startEngineAndWait(); }
        });
        engineRow.addView(engineStart, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button engineOpen = smallBtn("打开引擎 App");
        engineOpen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    // Linux 引擎已经并进本 App：直接打开本包的引擎页
                    Intent i = new Intent();
                    i.setClassName(DshHarnessActivity.this,
                            "com.deepseek.pet.dsh.linux.EngineActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t) {
                    installStatus.setText("打不开引擎：" + t.getClass().getSimpleName());
                }
            }
        });
        LinearLayout.LayoutParams erp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        erp.setMargins(dp(8), 0, 0, 0);
        engineRow.addView(engineOpen, erp);
        LinearLayout.LayoutParams erlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        erlp.setMargins(0, dp(8), 0, 0);
        advanced.addView(engineRow, erlp);

        TextView t6 = new TextView(this);
        t6.setText("★ 为什么是另一个包：Android 10+ 只允许 targetSdk≤28 的包执行自己目录里的程序，"
                + "而 proot 必须这样。本包是 34，所以把 Linux 那层隔到「DSH Linux 引擎」里，"
                + "通过 127.0.0.1:43190 问它要地址。没装或起不来就自动回落到上面那条原生直跑。");
        t6.setTextSize(11f);
        t6.setTextColor(T.text3);
        t6.setPadding(0, dp(4), 0, 0);
        advanced.addView(t6);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = smallBtn("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSettings(false); }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // ★ v8.0：顶栏的 ↻ 随顶栏一起去掉了，重载放到这里（重载的仍是"真正在用的那个地址"）。
        Button reload = smallBtn("↻ 重新载入");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleSettings(false);
                load(activeUrl());
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(8), 0, 0);
        advanced.addView(reload, rlp);

        Button save = smallBtn("保存并打开");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveAndLoad(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.setMargins(dp(8), 0, 0, 0);
        actions.addView(save, slp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(0, dp(10), 0, 0);
        advanced.addView(actions, alp);

        // ★ v8.0：顶栏去掉之后没有 ✕ 了，退出工作模式放到这里（返回键同样可以退出）。
        Button quit = smallBtn("退出工作模式");
        quit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.setMargins(0, dp(8), 0, 0);
        advanced.addView(quit, qlp);

        layer.addView(box);
        return layer;
    }

    private void toggleSettings(boolean open) {
        settingsLayer.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            if (urlInput != null) urlInput.setText(harnessUrl());
            refreshInstallStatus();
            refreshEngineStatus();
        }
    }

    private void saveAndLoad() {
        String raw = urlInput == null ? "" : urlInput.getText().toString().trim();
        if (raw.length() == 0) raw = defaultUrl();
        String tok = "";
        int i = raw.indexOf("token=");
        if (i >= 0) {
            tok = raw.substring(i + 6);
            int amp = tok.indexOf('&');
            if (amp >= 0) tok = tok.substring(0, amp);
            raw = raw.substring(0, i).replaceAll("[?&]$", "");
        }
        DshBridge.prefs(this).edit()
                .putString(DshFeature.KEY_HARNESS_URL, raw)
                .putString(DshFeature.KEY_HARNESS_TOKEN, tok)
                .apply();
        // 用户改用远端 ⇒ 本机宿主没必要再占着内存（也把端口让出来）
        toggleSettings(false);
        load(harnessUrl());
    }

    // ==================== 本地安装 / 配置 ====================

    private DshHarnessInstaller installer() {
        if (installer == null) installer = new DshHarnessInstaller(getFilesDir());
        return installer;
    }

    private void refreshInstallStatus() {
        if (installStatus == null) return;
        DshHarnessInstaller ins = installer();
        String v = ins.installedVersion();
        if (DshEngineBundled.bundled(this)) {
            // 全离线版：原生直跑那条"包树"是可选的，不该拿它当默认提示
            installStatus.setText(v.length() > 0
                    ? ("已安装包树：v" + v + (ins.isInstalled() ? "" : "（文件不全）"))
                    : "这只包已自带 Linux 组件与全部载荷 —— 不需要下载、不需要服务器。"
                      + "点下面的「安装随包的本机 Linux 组件」即可。");
            return;
        }
        installStatus.setText(v.length() > 0
                ? ("已安装包树：v" + v + (ins.isInstalled() ? "" : "（文件不全）"))
                : "还没安装包树（当前用电脑上的宿主）");
    }

    /** 后台下载 → 校验 → 解压 → 写配置（主线程只更新文字）。 */
    private void startInstall() {
        final String base = urlInput == null ? DshHarnessInstaller.DEFAULT_BASE
                : urlInput.getText().toString().trim();
        final DshHarnessInstaller ins = installer();
        installBtn.setEnabled(false);
        installStatus.setText("正在连接下载源…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err = "";
                String ver = "";
                try {
                    DshHarnessInstaller.Manifest mf = ins.fetchManifest(base);
                    ver = ins.install(base, mf, new DshHarnessInstaller.Progress() {
                        @Override public void onProgress(final long done, final long total) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    installStatus.setText("下载中 " + (done / 1024) + " KB"
                                            + (total > 0 ? (" / " + (total / 1024) + " KB") : ""));
                                }
                            });
                        }

                        @Override public void onStage(final String stage) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() { installStatus.setText(stage); }
                            });
                        }

                        @Override public void onError(final String message) { }
                    });
                    ins.writeSettings(Prefs.getChatProvider(DshHarnessActivity.this),
                            "", Prefs.getChatModel(DshHarnessActivity.this));
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage());
                }
                final String fErr = err;
                final String fVer = ver;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        installBtn.setEnabled(true);
                        refreshInstallStatus();
                        if (fErr.length() > 0) {
                            installStatus.setText("安装失败：" + fErr);
                            return;
                        }
                        installStatus.setText("已安装 v" + fVer
                                + "（Node 运行时随 APK，Android 版 provider 已随启动覆盖进 profile）");
                    }
                });
            }
        }, "harness-install").start();
    }

    // ==================== 本机跑 Node（不依赖电脑） ====================

    private DshNodeProcess node() {
        if (node == null) node = new DshNodeProcess();
        return node;
    }

    private DshNodeProcess.Sink nodeSink() {
        return new DshNodeProcess.Sink() {
            @Override public void onLine(final String line) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        nodeLog.append(line).append('\n');
                        while (nodeLog.length() > 600) nodeLog.delete(0, 200);
                        installStatus.setText(nodeLog.toString().trim());
                    }
                });
            }

            @Override public void onUrl(final String url) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        installStatus.setText("宿主已启动：" + shortUrl(url));
                        // ★ 本机地址写本机那一格，绝不污染"用户配的远端地址"那格
                        DshBridge.prefs(DshHarnessActivity.this).edit()
                                .putString(DshFeature.KEY_HARNESS_LOCAL, url)
                                .apply();
                        toggleSettings(false);
                        load(url);
                    }
                });
            }

            @Override public void onExit(final int code) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        installStatus.setText(nodeLog.toString().trim()
                                + "\n（进程退出 code=" + code + "）");
                    }
                });
            }
        };
    }

    private void runNodeSelfTest() {
        if (!DshNodeProcess.available(this)) {
            installStatus.setText("lib/ 里没有可执行的 node（这只包没带 Node 运行时）");
            return;
        }
        installStatus.setText("正在跑 node -v …");
        node().selfTest(this, nodeSink());
    }

    // ==================== 完整 Linux：交给独立的引擎包 ====================

    /**
     * 拉起「DSH Linux 引擎」并等它报告地址。
     *
     * <p>抓不到就**如实回落**：先试原生直跑（这只包自带 Node + 包树时），否则把缺什么写到状态行。
     * 三条判据都不靠"猜"：控制接口说 running 且给了 url 才算成功。
     */
    private void startEngineAndWait() {
        showStatus("正在启动 Linux 引擎…");
        engineWaitCancelled = false;
        // ★ 等引擎可能是几分钟（首次要下载+解包 811 MB）：用不确定进度条明确告诉用户"在动"，
        //   并允许取消等待（引擎仍在后台继续，取消的只是这次等待）。
        bar.setIndeterminate(true);
        bar.setVisibility(View.VISIBLE);
        // ★ 默认只跑一条轨道：选引擎就把原生宿主停掉（少一份常驻进程/内存）。
        stopLocalHostQuiet();
        new Thread(new Runnable() {
            @Override public void run() {
                // ★ 随包版：载荷就在本 APK 的 assets 里，引擎通过 ContentProvider 直接读（不联网）
                final String src = DshEngineBundled.bundled(DshHarnessActivity.this)
                        ? DshEngine.ASSET_SOURCE : null;
                final boolean launched = DshEngine.start(DshHarnessActivity.this, src);
                if (!launched) {
                    fallBack("引擎拉不起来");
                    return;
                }
                String last = "";
                String pushed = "";
                boolean retriedAfterFail = false;
                long started = System.currentTimeMillis();
                long deadline = started + 150000L;   // 首次要解载荷，给足 2.5 分钟
                while (System.currentTimeMillis() < deadline) {
                    if (engineWaitCancelled) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                bar.setVisibility(View.GONE);
                                bar.setIndeterminate(false);
                                showStatus("已取消等待（引擎仍在后台继续装/起）");
                            }
                        });
                        return;
                    }
                    DshEngine.Status st = DshEngine.status(DshHarnessActivity.this);
                    if (st.running()) {
                        final String url = st.url;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                bar.setVisibility(View.GONE);
                                bar.setIndeterminate(false);
                                prootStatus.setText("完整 Linux：运行中");
                                load(url);
                            }
                        });
                        return;
                    }
                    if (st.state.length() > 0) {
                        last = st.summary();
                        // ★ 把引擎报的 detail 翻到界面上：首次装载荷时它是
                        //   "解 Ubuntu 根：1500 条目 / 66 MB"，下载时是百分比。
                        //   不显示的话用户只能对着"正在启动…"发呆好几分钟。
                        final String shown = st.detail.length() > 0 ? st.detail : st.summary();
                        if (!shown.equals(pushed)) {
                            pushed = shown;
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    showStatus("Linux 引擎：" + shown);
                                }
                            });
                        }
                    }
                    if ("failed".equals(st.state)) {
                        // ★ 首次装载荷之后立刻起宿主，偶发"先报 failed、十几秒后才 running"
                        //   （解包刚完、端口/进程还没就位的竞态）。**不能把第一次 failed 当终局** ——
                        //   实测就是这样误判，页面直接空了。这里再踢一次、继续等满 deadline。
                        last = st.summary();
                        if (!retriedAfterFail) {
                            retriedAfterFail = true;
                            try { Thread.sleep(12000); } catch (InterruptedException e) { return; }
                            DshEngine.start(DshHarnessActivity.this, src);
                        }
                    }

                    try { Thread.sleep(1200); } catch (InterruptedException e) { return; }
                }
                fallBack(last.length() > 0 ? last : "引擎没报告地址");
            }
        }, "engine-wait").start();
    }

    /** 回落到原生直跑（这只包自带 Node + 包树时），否则只说事实。 */
    private void fallBack(final String why) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                bar.setVisibility(View.GONE);
                bar.setIndeterminate(false);
                if (canRunLocalHost()) {
                    showStatus("完整 Linux 没起来（" + why + "），改用原生直跑");
                    startLocalHost();
                } else {
                    showStatus("完整 Linux 没起来（" + why + "），这只包也没有可用的包树");
                }
                if (prootStatus != null) prootStatus.setText("完整 Linux：不可用（" + why + "）");
            }
        });
    }

    /** 刷新引擎状态行（后台读，别卡 UI）。 */
    private void refreshEngineStatus() {
        if (prootStatus == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean installed = DshEngine.installed(DshHarnessActivity.this);
                final DshEngine.Status st = installed ? DshEngine.status(DshHarnessActivity.this) : new DshEngine.Status();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (prootStatus == null) return;
                        if (!installed) {
                            prootStatus.setText("引擎：没装（「DSH Linux 引擎」是独立包，装了才有完整 Linux）");
                        } else if (st.state.length() == 0) {
                            prootStatus.setText("引擎：已安装，未运行（点下面的按钮启动）");
                        } else {
                            prootStatus.setText("引擎：" + st.summary());
                        }
                    }
                });
            }
        }, "engine-status").start();
    }

    /**
     * 起本机宿主。宿主住在 {@link DshHostService}（前台服务）里，界面只是"连上去"。
     *
     * <p>★ 为什么不再在这里直接起 node：以前宿主跟 Activity 同生共死 —— 退出工作模式 /
     * 切图 / 旋转重建都会把它杀掉。现在进出界面只是重新拿同一个地址，宿主继续跑。
     */
    private void startLocalHost() {
        final DshHarnessInstaller ins = installer();
        if (!ins.isInstalled()) {
            installStatus.setText("先装包树（点上面的下载按钮）");
            return;
        }
        if (!DshNodeProcess.available(this)) {
            installStatus.setText("这只包里没有 Node 运行时 —— 需要带 libnode-bin.so 的包");
            return;
        }
        installStatus.setText("正在启动本机宿主（前台服务）…");
        // ★ 默认只跑一条轨道：原生直跑起来前先把引擎停掉（引擎那套常驻要 300 MB 上下）。
        //   两条确实能并存（端口 3081/3080 不同），但除非你在调试，没有同时跑的必要。
        DshEngine.stop(this);
        try {
            Intent svc = new Intent(this, com.deepseek.pet.dsh.DshHostService.class);
            svc.setAction(com.deepseek.pet.dsh.DshHostService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable t) {
            installStatus.setText("起不了宿主服务：" + t.getClass().getSimpleName());
            return;
        }
        // 等它打印地址（宿主起来了但没地址不算成功）
        new Thread(new Runnable() {
            @Override public void run() {
                long deadline = System.currentTimeMillis() + 90000L;
                while (System.currentTimeMillis() < deadline) {
                    final String u = DshHostState.url();
                    if (u.length() > 0) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                installStatus.setText("本机宿主：" + shortUrl(u));
                                prootStatus.setText("完整 Linux：未装引擎（当前用原生直跑）");
                                load(u);
                            }
                        });
                        return;
                    }
                    String err = DshHostState.lastError();
                    if (err.length() > 0) {
                        final String e = err;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                installStatus.setText("宿主失败：" + e);
                            }
                        });
                        return;
                    }
                    try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        installStatus.setText("宿主 90 秒没打印地址（见 dsh-node.log）");
                    }
                });
            }
        }, "host-wait").start();
    }

    /** 停掉本机宿主服务。 */
    private void stopLocalHost() {
        try {
            Intent svc = new Intent(this, com.deepseek.pet.dsh.DshHostService.class);
            svc.setAction(com.deepseek.pet.dsh.DshHostService.ACTION_STOP);
            startService(svc);
            installStatus.setText("已请求停止本机宿主");
        } catch (Throwable t) {
            installStatus.setText("停不了：" + t.getClass().getSimpleName());
        }
    }
    /** 安静地停本机宿主（切换轨道时用，不改状态行）。 */
    private void stopLocalHostQuiet() {
        try {
            Intent svc = new Intent(this, com.deepseek.pet.dsh.DshHostService.class);
            svc.setAction(com.deepseek.pet.dsh.DshHostService.ACTION_STOP);
            startService(svc);
        } catch (Throwable ignore) {
        }
    }

    /**
     * **一键**：装组件 → 解载荷 → 起宿主 → 打开页面，全程自动，用户只需要点一次"安装"。
     *
     * <p>这就是普通用户该走的唯一路径：不看地址、不选模式、不管包树。
     * 出错时把原因写到 {@link #oneTapStatus}，并把「高级选项」留给他们自救。
     */
    private void oneTapPrepare() {
        if (!ensureStorage()) return;                 // 先把「访问所有文件」要到手
        if (oneTapBtn != null) oneTapBtn.setEnabled(false);
        oneTap("开始…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final boolean bundled = DshEngineBundled.bundled(DshHarnessActivity.this);

                    // ① 组件：本机 Linux 现在就在这个 App 里，没有"安装第二个 App"这一步了

                    // ② 已经跑着就直接用
                    DshEngine.Status st = DshEngine.status(DshHarnessActivity.this);
                    if (st.running() && st.url.length() > 0) {
                        oneTap("③ 已就绪，打开页面…");
                        openUrl(st.url);
                        return;
                    }

                    // ③ 起宿主（首次会自己解 Ubuntu/Node/包树，进度来自引擎的 /status）
                    oneTap("② 启动 Linux 宿主" + (bundled ? "（首次要解约 800 MB，几分钟）" : "")
                            + "…");
                    DshEngine.start(DshHarnessActivity.this,
                            bundled ? DshEngine.ASSET_SOURCE : null);
                    long started = System.currentTimeMillis();
                    long limit = started + 20 * 60000L;
                    String last = "";
                    boolean kicked = false;
                    while (System.currentTimeMillis() < limit) {
                        st = DshEngine.status(DshHarnessActivity.this);
                        if (st.running() && st.url.length() > 0) {
                            oneTap("③ 打开页面…");
                            markPrepared();
                            openUrl(st.url);
                            return;
                        }
                        if (st.detail.length() > 0 && !st.detail.equals(last)) {
                            last = st.detail;
                            oneTap("② " + last);
                        }
                        if ("failed".equals(st.state) && !kicked) {
                            // 首次解包后起宿主的竞态：再踢一次（和 startEngineAndWait 同一个道理）
                            kicked = true;
                            Thread.sleep(8000);
                            DshEngine.start(DshHarnessActivity.this,
                                    bundled ? DshEngine.ASSET_SOURCE : null);
                            continue;
                        }
                        Thread.sleep(1500);
                    }
                    oneTap("超时了：点下面「打开引擎 App」看日志，或再点一次一键");
                } catch (Throwable t) {
                    oneTap("出错了：" + t.getClass().getSimpleName());
                } finally {
                    if (oneTapBtn != null) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { oneTapBtn.setEnabled(true); }
                        });
                    }
                }
            }
        }, "one-tap").start();
    }

    /** 刷新"当前 harness 版本"那行字。 */
    private void refreshHarnessVersion() {
        if (harnessVerStatus == null) return;
        String label;
        try {
            label = com.deepseek.pet.dsh.linux.Engine.get(this).harnessLabel();
        } catch (Throwable t) {
            label = "内置 0.1.7-rc.2";
        }
        harnessVerStatus.setText("Harness 版本：" + label + "\n"
                + "（想换版本就把那版的 .tgz 地址填在下面；rootfs 与 Node 不动，只换包树）");
    }

    /**
     * 退出工作模式：标回陪伴模式 + 把这一页（含最近任务卡片）收掉。
     *
     * <p>用户要求：工作模式右上角要有个退出开关，点一下就回陪伴模式（桌宠浮窗）。
     * 这里用 {@code finishAndRemoveTask()}，所以后台里那张卡也会跟着消失 —— 陪伴模式不留卡片。
     */
    private void exitWorkMode() {
        try {
            DshBridge.setWorkMode(this, false);
            // ★ 这个静态标志不清的话，回到陪伴模式后 onPause 会一直跳过
            //   “移除最近任务卡片”（它是为“刚进工作模式”准备的），
            //   结果就是陪伴模式也留了一张卡片。
            MainActivity.enteringWorkMode = false;
        } catch (Throwable ignore) {
        }
        try {
            finishAndRemoveTask();
        } catch (Throwable t) {
            finish();
        }
    }

    // ==================== 「访问所有文件」权限 ====================

    /** 有没有到位的存储权限（Android 11+ 看 MANAGE_EXTERNAL_STORAGE，以下看运行时权限）。 */
    private boolean storageOk() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                return android.os.Environment.isExternalStorageManager();
            }
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;      // 判断不了就别挡路
        }
    }

    /**
     * 进工作模式前要一次「访问所有文件」权限。
     *
     * <p>用户报障（vivo）：应用设置里**没有文件权限入口**，于是工作模式打不开。
     * 这里主动把系统那一页推给用户（Android 11+ 只能跳设置页授权，没法用 requestPermissions）。
     *
     * @return 是否已经可用
     */
    private boolean ensureStorage() {
        if (storageOk()) {
            if (storageLayer != null) storageLayer.setVisibility(View.GONE);
            return true;
        }
        if (storageLayer != null) {
            storageLayer.setVisibility(View.VISIBLE);
            try {
                Intent i;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName()));
                } else {
                    i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()));
                }
                startActivity(i);
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable ignore) {
                }
            }
        }
        return false;
    }

    /** 权限层：说明 + 「去授权」+「已授权，继续」。 */
    private FrameLayout buildStorageLayer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(T.scrim);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBg(T.surface, dp(16)));
        box.setPadding(dp(20), dp(22), dp(20), dp(16));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(340), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        box.setLayoutParams(lp);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* 吃掉点击 */ }
        });

        TextView title = new TextView(this);
        title.setText("需要「访问所有文件」权限");
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(T.text1);
        box.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(12f);
        body.setTextColor(T.text2);
        body.setPadding(0, dp(8), 0, dp(14));
        body.setText("工作模式要读写你选择的文件夹（工作区、附件），没有这个权限打不开。\n\n"
                + "点「去授权」→ 在系统页面里打开「允许访问所有文件」→ 返回即可。\n"
                + "部分机型（vivo 等）在「设置 → 应用」里看不到这项，所以必须从这里进。");
        box.addView(body);

        Button go = smallBtn("去授权");
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ensureStorage(); }
        });
        box.addView(go, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button ok = smallBtn("已授权，继续");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (storageOk()) {
                    storageLayer.setVisibility(View.GONE);
                    oneTapPrepare();
                } else {
                    ensureStorage();
                }
            }
        });
        LinearLayout.LayoutParams okp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okp.setMargins(0, dp(8), 0, 0);
        box.addView(ok, okp);

        layer.addView(box);
        return layer;
    }

    // ==================== 右上角菜单（设置 / 退出，退出要二次确认）====================

    private void showQuickMenu(boolean open) {
        if (quickMenu == null) return;
        quickMenu.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    /**
     * 右上角那颗 ⚙ 点开的菜单。
     *
     * <p>用户要求：把「设置」和「退出工作模式」**合成一个入口**，且退出要**弹窗确认**
     * （以前是两颗独立的悬浮按钮，容易误触直接退出）。
     */
    private FrameLayout buildQuickMenu() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        layer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (confirmLayer != null && confirmLayer.getVisibility() == View.VISIBLE) return;
                showQuickMenu(false);
            }
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg(T.surface, dp(16)));
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(68), dp(14), 0);
        card.setLayoutParams(lp);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* 吃掉点击 */ }
        });

        Button set = smallBtn("⚙  设置");
        set.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showQuickMenu(false);
                toggleSettings(true);
            }
        });
        card.addView(set, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button quit = smallBtn("⏻  退出工作模式");
        quit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showQuickMenu(false);
                showExitConfirm(true);
            }
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.setMargins(0, dp(6), 0, 0);
        card.addView(quit, qlp);

        layer.addView(card);

        confirmLayer = buildExitConfirm();
        layer.addView(confirmLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        confirmLayer.setVisibility(View.GONE);
        return layer;
    }

    private void showExitConfirm(boolean open) {
        if (confirmLayer == null) return;
        if (open) showQuickMenu(true);
        confirmLayer.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    /** 退出确认弹窗（自己画一层，DSH 这层约定不弹系统 AlertDialog）。 */
    private FrameLayout buildExitConfirm() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(T.scrim);
        layer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showExitConfirm(false); }
        });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBg(T.surface, dp(16)));
        box.setPadding(dp(20), dp(20), dp(20), dp(16));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        box.setLayoutParams(lp);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* 吃掉点击 */ }
        });

        TextView title = new TextView(this);
        title.setText("退出工作模式？");
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(T.text1);
        box.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(12f);
        body.setTextColor(T.text2);
        body.setPadding(0, dp(8), 0, dp(14));
        body.setText("退出后回到陪伴模式（桌宠浮窗），后台卡片会一起收掉。"
                + "本机 Linux 宿主继续在后台跑，下次进来秒开。");
        box.addView(body);

        Button cancel = smallBtn("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showExitConfirm(false); }
        });
        box.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button ok = smallBtn("确定退出");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exitWorkMode(); }
        });
        LinearLayout.LayoutParams okp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okp.setMargins(0, dp(8), 0, 0);
        box.addView(ok, okp);

        layer.addView(box);
        return layer;
    }

    /** 本机 Linux 是否已经"准备过一次"（成功起来过）。 */
    private boolean prepared() {
        try {
            return DshBridge.prefs(this).getBoolean(DshFeature.KEY_DSH_PREPARED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记下"已经准备过了"，以后不再走安装/一键流程。 */
    private void markPrepared() {
        try {
            DshBridge.prefs(this).edit().putBoolean(DshFeature.KEY_DSH_PREPARED, true).apply();
        } catch (Throwable ignore) {
        }
    }

    @Override protected void onStop() {
        super.onStop();
        // ★ 工作模式**不在后台留页面**：切出去就结束这一页，回到陪伴模式（桌宠浮窗）。
        //   宿主跑在前台服务里，回来只是重新连上同一个地址，不用重新装、也不用重新等。
        //   两个例外：正在等系统安装器（结束会把一键流程打断）、以及横竖屏切换。
        // 还在"准备阶段"（没配置完 / 等安装 / 等授权）时别结束：跳系统设置、安装器回来还要接着跑
        if (!prepared() || isChangingConfigurations()) return;
        // ★ 用户要求（两次）：切到桌面时**卡片要留着**、回来还应该是工作模式。
        //   所以离开时什么都不做 —— 任务留着，卡片在，回到 app 直接还是这一页。
        //   只有用户主动按返回/「退出工作模式」才 finish（那时 onDestroy 会把模式标回陪伴）。
    }
    /** 等用户确认安装的那一小段状态（用户从系统安装器回来时接着跑）。 */

    /** 一键流程的状态输出（主线程）。 */
    private void oneTap(final String text) {
        if (oneTapStatus == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() { oneTapStatus.setText(text); }
        });
    }

    /** 关掉设置层并载入地址（主线程）。 */
    private void openUrl(final String u) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                toggleSettings(false);
                load(u);
            }
        });
    }
    private static String tokenOf(String url) {
        int i = url.indexOf("token=");
        if (i < 0) return "";
        String t = url.substring(i + 6);
        int amp = t.indexOf('&');
        return amp > 0 ? t.substring(0, amp) : t;
    }

    private void load(String url) {
        if (url == null) url = "";
        currentUrl = url;
        if (web == null) return;
        showStatus("正在连接 " + shortUrl(url) + " …");
        try {
            web.loadUrl(url);
        } catch (Throwable t) {
            showStatus("加载失败：" + t.getClass().getSimpleName());
        }
    }

    /** 真正在用的地址：本机宿主优先，其次是用户配的（默认值兜底）。 */
    private String activeUrl() {
        return currentUrl.length() > 0 ? currentUrl : harnessUrl();
    }

    private boolean sameHost(String url) {
        try {
            java.net.URL a = new java.net.URL(activeUrl());
            java.net.URL b = new java.net.URL(url);
            return a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (settingsLayer != null && settingsLayer.getVisibility() == View.VISIBLE) {
                toggleSettings(false);
                return true;
            }
            if (web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, e);
    }

    // ==================== 小工具（不引 AndroidX） ====================

    private Button smallBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14f);
        b.setAllCaps(false);
        b.setTextColor(T.text1);
        b.setBackground(roundRect(T.surfaceSunken));
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private GradientDrawable roundRect(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
