package com.deepseek.pet;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 开放平台网页登录。
 *
 * ★ 为什么必须走网页
 *   官网用量接口只认登录态 token（Authorization: Bearer <userToken>），
 *   而这个 token 只有网页登录后才会产生，API Key 换不到。
 *   官方登录接口挂了 AWS WAF + 数美/hCaptcha 风控，自己实现登录不现实。
 *   所以这里用内置 WebView 打开官方登录页：账号密码 / 短信验证码都输在官方页面里，
 *   风控由官方页面自身处理，我们不碰凭据。
 *
 * ★ 拿到了什么
 *   登录成功后官方页面把 userToken 写进 localStorage，这里只读这一个字段，
 *   不读取任何其他内容。token 存应用私有目录，只发往 platform.deepseek.com。
 *
 * ⚠ 坑：官网在**未登录**时就把 userToken 初始化为 null
 *   （前端 create("userToken", null, null) → localStorage 里是 "null"）。
 *   所以不能只看"字段存在"，必须① 长度/格式像 token，② 真正能调通接口，才判定登录成功。
 *
 * <h3>★ 为什么这一页要重做（轮次㊷ · 与轮次㊵/㊶ 同族）</h3>
 *
 * 用户的要求是「所有三级菜单 / 页面都同步 app ui 跟随主题切换」。轮次㊵ 做的是**弹窗**
 * 那层、轮次㊶ 做的是**用量页**，本轮收掉最后两页。本页原来的病：
 * <ul>
 *   <li>根布局写死 {@code Color.WHITE}、顶栏写死 {@code 0xFFF2F7FF}、
 *       状态条写死 {@code 0xFFFBFDFF}、文字写死 {@code 0xFF1B3A6B} / {@code 0xFF5A6B80}
 *       ⇒ 深色模式 / 玻璃皮肤下**顶栏与状态条还是浅色的**；</li>
 *   <li>控件是裸 {@code Button}，与整页的浮雕/玻璃语言不一致。</li>
 * </ul>
 * 现在：根布局走 {@link UiFactory#decoratePage}（玻璃态是 Mesh 光斑）、
 * 顶栏与状态条走 {@link UiFactory#card}、关闭键走 {@link UiFactory#button}、
 * 文字色从 {@link Tokens.Set} 取、窗口自身也上 {@code T.bg}；
 * ★ WebView 四周留出页面边距 ⇒ "官方页面"成为被主题背景框住的一块。
 * ⇒ 本文件里**再出现一个 {@code 0xFF……} 都算回潮**（`build/verify_page_theme.py` 钉着）。
 */
public class PlatformLoginActivity extends Activity {

    private static final String TAG = "DSPlatformLogin";

    /** 真实 token 远长于此；同时排除 "null"/"undefined" 这类占位值 */
    private static final int MIN_TOKEN_LEN = 20;

    /** 已判定无效的值不再重复校验（官方未登录时也会塞 "null"） */
    private final java.util.Set<String> rejected =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** 正在校验的值，防止并发重复提交 */
    private volatile String inFlight = null;

    private WebView web;
    private TextView status;
    private volatile boolean verifying = false;
    private volatile boolean done = false;
    /** ★ 令牌：皮肤（新拟态/玻璃）× 明暗，页面里所有颜色都从它取 */
    private Tokens.Set T;

    /** 与网页交互的桥：只负责把 userToken 递回来 */
    private class JsBridge {
        @JavascriptInterface
        public void onToken(String raw) {
            final String t = unwrap(raw == null ? "" : raw.trim());
            android.util.Log.i(TAG, "bridge.onToken rawLen="
                    + (raw == null ? 0 : raw.length())
                    + " tokenLen=" + t.length()
                    + " head=" + (t.length() > 8 ? t.substring(0, 8) + "..." : t));
            runOnUiThread(new Runnable() {
                @Override public void run() { consider(t); }
            });
        }
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // ★ 用户 2026-10-11：「所有三级菜单都要跟随主题切换」——系统自绘的东西
        //   （对话框确认框 / 输入法选择菜单 / 长按文本菜单）只认 Activity 主题，
        //   不认我们的令牌 ⇒ 必须先换成与明暗一致的系统主题。
        //   必须在 setContentView 之前（setTheme 的硬性要求）。
        Tokens.applyPlatformTheme(this);
        T = Tokens.of(this);
        final float d = getResources().getDisplayMetrics().density;
        // ★ 窗口自身也要上色（深色模式下边缘不露白）—— 与用量页同一套做法
        getWindow().setBackgroundDrawable(new ColorDrawable(T.bg));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // ★ 整页铺主题背景（玻璃态是 Mesh 光斑，新拟态是纯色）—— 原来这里是
        //   `root.setBackgroundColor(Color.WHITE)`，一个写死的白色。
        UiFactory.decoratePage(root, this, T);

        int m = (int) (d * 10);

        // ---------- 顶部条：标题 + 关闭（走卡片，与整页的浮雕语言一致） ----------
        LinearLayout bar = UiFactory.card(this, T);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        // ★ 页头不带 emoji：图标归行首徽章（单色线稿），彩色 emoji 会与它冲突。
        title.setText("登录 DeepSeek 开放平台");
        title.setTextSize(17);
        title.setTextColor(T.text1);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = UiFactory.button(this, T, "关闭", false);
        close.setTextSize(13);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(close);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(m, m, m, 0);
        root.addView(bar, blp);

        // ---------- 状态提示 ----------
        status = new TextView(this);
        status.setText("请在下方页面登录。登录成功后本页会自动关闭 ✓\n"
                + "登录凭据只在官方页面内输入，本应用不读取账号密码。");
        status.setTextSize(13);
        status.setTextColor(T.text2);
        status.setLineSpacing(0, 1.2f);
        LinearLayout stCard = UiFactory.card(this, T);
        stCard.addView(status);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(m, (int) (d * 8), m, 0);
        root.addView(stCard, slp);

        // ---------- WebView ----------
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // ★ 必需：userToken 存在 localStorage
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= 21) {
            // 去掉 "; wv" 标记，降低被风控当成"内嵌浏览器"的概率
            s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new JsBridge(), "DeepSeekPetBridge");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pollToken();
            }
        });

        // ★ WebView 四周留出页面边距：这样"官方页面"也是被主题背景框住的一块，
        //   而不是一直顶到屏幕边缘（同时让页面底色在左/右边缘可见）。
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wlp.setMargins(m, (int) (d * 8), m, m);
        root.addView(web, wlp);
        setContentView(root);
        // ★ 合规：加载官方登录页前必须先取得用户明示同意。
        //   未同意则不打开 WebView、不读取任何 token。
        showAgreementThenLoad();
    }

    /**
     * 《开放平台登录授权说明》确认框。
     * 用户点「同意并继续」才 loadUrl；点「不同意」直接关闭本页。
     */
    private void showAgreementThenLoad() {
        UiFactory.dialog(this)
            .setTitle("登录授权说明")
            .setMessage(
                "在继续之前，请阅读并确认以下事项：\n\n"
              + "1. 本应用为第三方独立开发的非官方工具，与 DeepSeek／深度求索公司"
              + "无任何隶属、赞助或背书关系。DeepSeek 相关名称与商标归其权利人所有。\n\n"
              + "2. 登录将打开 DeepSeek 官方页面（platform.deepseek.com）。"
              + "账号、密码、短信验证码一律只在官方页面内输入，本应用不读取、不存储、不传输这些凭据。\n\n"
              + "3. 登录成功后，官方页面会把一个登录凭证（userToken）写入网页存储。"
              + "本应用只读取这一个字段，用于向 platform.deepseek.com 查询你自己的用量、费用与余额。\n\n"
              + "4. 本应用不会抓取、批量导出或传播你的任何账户数据，不经过任何第三方中间服务器。\n\n"
              + "5. 该凭证只保存在本机应用私有目录。你可以随时在「用量页」点击「退出登录」"
              + "清除它，清除后需重新登录。\n\n"
              + "6. 本功能调用的是官网未公开的接口，未来可能随时失效；"
              + "使用造成的一切后果由使用者自行承担。\n\n"
              + "点击「同意并继续」即表示你已阅读并接受以上条款。")
            .setPositiveButton("同意并继续",
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        loadSignIn();
                    }
                })
            .setNegativeButton("不同意",
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                })
            .setCancelable(false)
            .show();
    }

    /** 真正加载官方登录页 */
    private void loadSignIn() {
        web.loadUrl(PlatformClient.HOST + "/sign_in");
    }


    /**
     * 把 localStorage 里取到的原始值解包成真正的 token。
     *
     * 官网实际存的是 {"value":"<token>", ...} 这种 JSON 包装（字段名以 value 开头），
     * 但不同版本可能换成裸串或别的键名，所以这里多试几种，最后回退成原串。
     * 日志里会打印键名，便于以后前端改结构时快速定位。
     */
    private String unwrap(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        for (int depth = 0; depth < 3 && v.startsWith("{"); depth++) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(v);
                android.util.Log.i(TAG, "unwrap keys=" + keysOf(o));
                String got = null;
                if (o.has("value") && !o.isNull("value")) {
                    Object x = o.get("value");
                    if (x instanceof String) got = (String) x;
                }
                if (got == null && o.has("token") && !o.isNull("token")) {
                    Object x = o.get("token");
                    if (x instanceof String) got = (String) x;
                }
                if (got == null) {
                    // 兜底：任取一个像 token 的长字符串字段
                    java.util.Iterator<String> it = o.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        Object x = o.opt(k);
                        if (x instanceof String && ((String) x).length() >= MIN_TOKEN_LEN) {
                            got = (String) x;
                            android.util.Log.i(TAG, "unwrap fallback key=" + k);
                            break;
                        }
                    }
                }
                if (got == null) return "";      // 包装里确实没有 token
                v = got.trim();
            } catch (Exception e) {
                android.util.Log.w(TAG, "unwrap failed: " + e);
                break;
            }
        }
        // 顺带剥掉外层引号
        while (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    private String keysOf(org.json.JSONObject o) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(it.next());
        }
        return sb.toString();
    }

    /** 读 localStorage.userToken，看起来像真 token 才交给桥 */
    private void pollToken() {
        if (done || verifying || web == null) return;
        // ★ localStorage.userToken 不是裸字符串，而是 JSON 包装：
        //      userToken = {"value":"<真正的 token>", ...}
        //   前端 create("userToken",null,null) 建句柄，落盘时套一层对象，
        //   读取时用 JSON.parse(raw).__version 取版本。
        //   原来直接把整段 JSON 当 token 发出去，请求头变成
        //      Authorization: Bearer {"value":...}
        //   必被服务端拒绝 → 一直「校验失败」，表现就是「登录了但没反应」。
        //   这里把原始值整段交给 Java，由 Java 解包（Java 侧还有一层兜底）。
        final String js =
            "(function(){try{var raw=localStorage.getItem('userToken');" +
            "DeepSeekPetBridge.onToken(raw===null?'':String(raw));return 'sent';}" +
            "catch(e){DeepSeekPetBridge.onToken('');return 'err';}})()";
        web.evaluateJavascript(js, null);
    }

    /** 轮询兜底：登录跳转后 onPageFinished 不一定再触发 */
    private final android.os.Handler poll = new android.os.Handler();
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (done || verifying) return;
            pollToken();
            poll.postDelayed(this, 1500);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (!done) {
            poll.removeCallbacks(pollTask);
            poll.postDelayed(pollTask, 1500);
        }
    }

    @Override protected void onPause() {
        poll.removeCallbacks(pollTask);
        super.onPause();
    }

    /**
     * 拿到候选 token —— 但绝不轻信。
     * 未登录时官网也会往 localStorage 塞占位值，必须真调一次接口验证。
     */
    private void consider(String token) {
        if (done) return;
        if (token == null || token.length() < MIN_TOKEN_LEN) return;
        String low = token.toLowerCase();
        if (low.equals("null") || low.equals("undefined") || low.equals("{}")) return;
        if (rejected.contains(token) || token.equals(inFlight)) return;
        if (verifying) return;

        verifying = true;
        inFlight = token;
        status.setText("检测到登录态，正在校验…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String t = token;
                boolean ok;
                try {
                    // 先用临时 token 试调一次；PlatformClient 从 Prefs 读，故先存后验，
                    // 失败再清掉，避免把无效值留在本地。
                    PlatformClient.saveToken(PlatformLoginActivity.this, t);
                    PlatformClient.fetchSummary(PlatformLoginActivity.this);
                    ok = true;
                } catch (Exception e) {
                    PlatformClient.clearToken(PlatformLoginActivity.this);
                    ok = false;
                }
                final boolean fok = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        verifying = false;
                        inFlight = null;
                        if (fok) {
                            done = true;
                            poll.removeCallbacks(pollTask);
                            status.setText("✓ 登录成功，正在读取用量…");
                            Toast.makeText(PlatformLoginActivity.this,
                                    "✓ 已登录开放平台", Toast.LENGTH_SHORT).show();
                            setResult(RESULT_OK, new Intent().putExtra("token", t));
                            finish();
                        } else {
                            rejected.add(t);
                            status.setText("尚未登录完成，请继续在下方页面登录…\n"
                                    + "（登录凭据只在官方页面内输入）");
                        }
                    }
                });
            }
        }).start();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        poll.removeCallbacks(pollTask);
        if (web != null) {
            try {
                // 先解绑再销毁，避免 "destroy() called while still attached"
                if (web.getParent() instanceof ViewGroup) {
                    ((ViewGroup) web.getParent()).removeView(web);
                }
                web.removeJavascriptInterface("DeepSeekPetBridge");
                web.stopLoading();
                web.loadUrl("about:blank");
                web.destroy();
                CookieManager.getInstance().flush();
            } catch (Exception ignore) {}
            web = null;
        }
        super.onDestroy();
    }
}
