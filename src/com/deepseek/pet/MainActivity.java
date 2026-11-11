package com.deepseek.pet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.List;

/**
 * 设置主界面：预览、大小/透明度、各种开关、记忆与对话、图片与性格预设。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 2001;
    private static final int REQ_IMPORT_MODEL = 2002;
    private static final int REQ_IMPORT_L2D = 2003;
    private static final int REQ_IMPORT_ASSET = 2004;
    private static final int REQ_IMPORT_SKILL = 2005;
    private static final int REQ_DISTILL_CHAT = 2006;
    private static final int REQ_PICK_MEME = 2007;
    private static final int REQ_PICK_MEME_MULTI = 2008;
    // 原为 2008，与 REQ_PICK_MEME_MULTI 撞号。onActivityResult 从头往下匹配，
    // 2008 先被「批量导入表情」分支吃掉，所以「上传音频克隆音色」这条分支
    // 永远进不去 —— 是一段够不到的死代码。改成不冲突的号。
    private static final int REQ_PICK_CLONE_AUDIO = 2009;
    /** 背景视频选择。★ 必须与上面所有码互不相同 —— 2008 撞号导致过死代码，见其注释。 */
    private static final int REQ_PICK_VIDEO_BG = 2010;
    /** 背景图片选择（第二十一轮）。同样必须与所有码互不相同。 */
    private static final int REQ_PICK_IMAGE_BG = 2011;
    /** 语音大模型文件导入。★ 同样必须与所有码互不相同（2008 撞号的教训）。 */
    private static final int REQ_PICK_ASR_MODEL = 2012;
    private static final int REQ_IMPORT_CHARACTER = 2013;   // v7.0：导入角色包（.zip）
    private static final int REQ_EXPORT_CHARACTER = 2014;   // v7.0：导出当前角色（.zip）
    private static final int REQ_EXPORT_THEME = 2020;       // 轮次53：导出配色方案（.json）
    private static final int REQ_IMPORT_THEME = 2021;       // 轮次53：导入配色方案（.json）
    /** 待办 0j 的 C：在 App 内往当前角色里**添加动作**（相册多选）。★ 同样不能与上面撞号。 */
    private static final int REQ_ADD_ACTIONS = 2015;
    /** 用户追加要求：在**某个场景**里直接导入该场景的动画（相册多选）。★ 同样不能撞号。 */
    private static final int REQ_IMPORT_SCENE_CLIPS = 2016;
    /**
     * 轮次62 第二轮（导入层）：挑一个 {@code .gguf} 本地模型文件。
     *
     * <p>★ 号段一路被撞出来的教训写在上面（2008 撞号 ⇒ 一整条分支变成死代码），
     * 所以这里取 2022（2020/2021 已被配色导入导出占用）。
     */
    private static final int REQ_PICK_GGUF = 2022;

    /**
     * Intent extra：直接导入一个角色包文件（绝对路径）。
     *
     * <p>用途有两个：① 文件管理器里「用本应用打开」一个角色包 zip；
     * ② 自动化验证（`adb shell am start -n …/.MainActivity --es dsp_import_pack /path/x.zip`）
     * —— SAF 选择器是系统 UI，脚本点不到，没有这个入口就只能靠手点。
     */
    public static final String EXTRA_IMPORT_PACK = "dsp_import_pack";
    private String cloneVoiceId = "";
    private String cloneDemoText = "";

    private SeekBar scaleBar;
    private SeekBar alphaBar;
    private TextView scaleLabel;
    private TextView alphaLabel;
    private ImageView preview;
    /** 预览框（诊断用；正常逻辑不依赖它） */
    private android.widget.FrameLayout previewBox;
    private Runnable pendingAction;
    /**
     * 正在进入工作模式（用来抑制 onPause 里的"移除最近任务卡片"）。
     *
     * <p>用户要求：**工作模式要显示后台卡片**（切出去还能从最近任务点回来），
     * 而陪伴模式不要那张卡片。onPause 里移除卡片时会被这个标记挡住一次。
     */
    public static volatile boolean enteringWorkMode = false;

    private int settingsSection = -1;   // -1=分组首页, 0..8=二级页（见 _GRP_NAMES）
    /**
     * 底部导航栏当前选中的板块（0/1/2）。
     *
     * <p>用户要求把设置按功能分成三个板块并用底部导航切换。分法是按
     * <b>用户脑子里"这只桌宠是什么"</b>来的，不是按代码模块：
     * <ul>
     *   <li>0 <b>形象</b> —— 它长什么样：外观、表情素材、语音（声音也是"形象"）</li>
     *   <li>1 <b>陪伴</b> —— 它怎么陪我：对话与人格、记忆</li>
     *   <li>2 <b>运行</b> —— 它怎么活着：服务、权限、自启动、关于</li>
     * </ul>
     * 每个板块下留了空位（板块首页底部有说明），方便日后加功能不改结构。
     */
    private int settingsTab = 0;
    /**
     * 当前底部导航栏实例。
     *
     * <p>横滑切板块时要驱动它的滑块"跟手"，所以必须留一个引用 ——
     * 点导航栏那条路径不需要它（回调直接拿到 index）。
     */
    private UiFactory.NavBar currentNav;
    /** 「大小」滑块实时应用的节流时间戳（见 applyScaleLive） */
    private long lastScaleApply;
    /** 「监测条间距」滑块的节流时间戳（拖动中只发轻量 reposition，不重建视图） */
    private long lastGapApply;
    /**
     * 上一次渲染的板块下标，用于让底部导航的滑块"从旧位置滑过来"。
     *
     * <p>切板块会整页重建（内容确实换了），导航栏也是新的 ——
     * 新滑块会直接长在目标位置，看不出滑动。记住旧格之后，
     * 新导航栏可以先把滑块摆到旧格、再滑到目标格，位移就连续了。
     *
     * <p>{@code -1} 表示还没有渲染过（首次进入不播滑动，直接落位）。
     */
    private int lastNavTab = -1;

    /**
     * 下一次页面重建时，横向翻页动画的方向。
     *
     * <p>{@code true} = 从右侧推入（进入下一级 / 往右切板块）；
     * {@code false} = 从左侧推入（返回上一级 / 往左切板块）。
     *
     * <p>默认 {@code true}：非导航触发的重建（如切换皮肤后刷新当前页）
     * 也走一次推进，比"瞬间替换"自然。
     */
    private boolean slideForward = true;

    /**
     * ★★★ 轮次71：「🧪 本地推理自检」那一轮的**代号**。
     *
     * <p>它就是"取消"的全部实现：{@link #runLocalSelfTest()} 开工前先 `++` 并记下自己的号，
     * 后台线程跑完回到 UI 线程时**先对一下号**——号变了说明用户中途按了取消，
     * 那就**不弹结果**（否则用户明明取消了，几秒后还是会跳出一屏结果，看起来像"没取消掉"）。
     *
     * <p>★ 为什么不直接用一个 boolean：boolean 要处理"取消之后又点了第二次自检"的竞态
     * （第一次的线程回来把第二次的标志清了）—— 单调递增的号天然没有这个问题。
     */
    private int selfTestRun = 0;

    /**
     * 二级页的页标题（下标＝ settingsSection）。
     *
     * <p>提成常量给两处共用：页头大标题，以及 {@code addSection} 里
     * 「分组名与页标题同名就不重复画」的判断。两处各写一份的话，
     * 改了页标题却忘了改分组名，重复标题就会重新出现。
     */
    private static final String[] _GRP_NAMES = {
            "主题与显示", "对话与人格", "表情与素材", "记忆与省 token",
            "运行控制", "关于", "语音", "游戏助手", "选择应用", "本地发言",
            "形象与动作", "工作模式"};   // ★ 11 = 工作模式（v8.0 新增的二级页）
    /**
     * 三个板块的标题与图标。
     *
     * <p>分法说明（用户要求「按照你对桌宠 app 的理解分类，不要参考我的 app」）：
     * <ul>
     *   <li><b>形象</b> —— 这只桌宠<b>看起来</b>是什么样</li>
     *   <li><b>陪伴</b> —— 这只桌宠<b>怎么和你相处</b>（说话、声音、记性）</li>
     *   <li><b>系统</b> —— 这只桌宠<b>怎么在后台活着</b>（权限、保活、版本）</li>
     * </ul>
     * 用「形象 / 陪伴 / 系统」而不是「设置 / 高级 / 其他」这类词，
     * 是因为前者能直接回答用户"我该去哪一栏找"，后者只是把东西堆完剩下的筐。
     */
    private static final String[] TAB_TITLES = {"形象", "陪伴", "系统"};
    private static final String[] TAB_ICONS = {"PALETTE", "CHAT", "GEAR"};

    /**
     * 外部（悬浮窗长按菜单「显示项…」）指定要打开的二级页编号。
     *
     * <p>用户从悬浮窗点进来，心里想的就是"改我刚看到的那条东西"，
     * 不该让他自己再在「陪伴」里翻到游戏助手那一行。
     */
    public static final String EXTRA_SECTION = "dsp_section";

    /**
     * 板块 → 二级页面的归属。
     *
     * <p>{@code settingsSection} 的编号是历史编号（0..7，散落在整个
     * showSettings 里），这里只做"它属于哪一个板块"的映射，
     * 不去重排编号 —— 重排要改动几十处判断，风险远大于收益。
     * 第 8 节（选择应用）是本轮新增的，跟第 7 节同属「陪伴」。
     */
    private static final int[][] TAB_SECTIONS = {
            {0, 10, 2},            // 形象：主题与显示、形象与动作、表情与素材
            {11, 1, 6, 3, 7, 8, 9},  // 陪伴：工作模式、对话与人格、语音、记忆、游戏助手、选择应用、本地发言
            {4, 5},                // 系统：运行控制、关于
    };

    /**
     * 「选择监测应用」二级页的编号。
     *
     * <p>它**不是**一个独立的板块入口，只能从第 7 节那一行「选择应用 ›」进来
     * （用户不切到"仅选定的应用"时，这一页没有意义，摆在板块首页只会添乱）。
     * 返回时靠 {@link #TAB_SECTIONS} 回到「陪伴」—— 用常量而不是散落的字面量 8，
     * 免得以后重排编号时漏掉某一处。
     */
    /**
     * 「工作模式」二级页的编号。
     *
     * <p>★ 用户要求：一级菜单「陪伴」板块里**新增一个工作模式**，把工作模式相关的选项
     * 全部收进去（原来它们散在「对话与人格」页底部）。
     */
    private static final int SECTION_WORK_MODE = 11;

    private static final int SECTION_MONITOR_APPS = 8;

    /**
     * 「纯本地 · 自定义发言」二级页的编号。
     *
     * <p>只能从「对话与人格」里那一行「纯本地台词：逐场景自定义…」进来
     * （用户不玩纯本地时这一页没意义，摆在板块首页只会添乱）。
     */
    private static final int SECTION_LOCAL_LINES = 9;

    /** 某个二级页属于哪个板块 */
    private int tabOfSection(int section) {
        for (int i = 0; i < TAB_SECTIONS.length; i++) {
            for (int s : TAB_SECTIONS[i]) {
                if (s == section) return i;
            }
        }
        return 0;
    }

    /**
     * 切到某个板块的根页。
     *
     * <p>**点导航栏**和**横滑**共用这一条路径 —— 两处的转场方向、动画、
     * 重建方式因此不可能漂移。{@code slideForward} 按目标板块在当前位置的
     * 左/右侧决定推入方向，与底部滑块滑动的方向一致，两个动效不会"对打"。
     */
    private void switchTab(int index) {
        if (index < 0 || index >= TAB_SECTIONS.length) return;
        if (index == settingsTab && settingsSection < 0) return;
        int navCurrent = settingsSection < 0 ? settingsTab : tabOfSection(settingsSection);
        slideForward = index >= navCurrent;
        settingsTab = index;
        settingsSection = -1;
        showSettings();
    }

    /**
     * 全屏（edge-to-edge）：让页面背景铺到状态栏**后面**，去掉顶部那条异色带。
     *
     * <p><b>用户反馈</b>：「去掉最上面的打大片异色区域做全屏」。那条带的来源是
     * 窗口默认不延伸到状态栏后面 —— 系统用主题的 statusBarColor 把状态栏那
     * 一条涂掉，而页面背景（玻璃的 Mesh 光斑／新拟态的纯色）只画在内容区里，
     * 两者颜色对不上，于是屏幕顶部出现一条硬边。滚动时内容又在这条边处被裁，
     * 顶部的按钮看起来"上边没圆角"。
     *
     * <p><b>做法</b>：状态栏设为透明 + 让窗口内容延伸到系统栏后面。
     * 内容不会被状态栏压住 —— {@code showSettings()} 里那个 ScrollView
     * 带着 {@code fitsSystemWindows(true)}，它会吃掉顶部 inset 把自己顶下去；
     * 这里只是把"谁在状态栏后面画画"从系统换成了页面背景。
     *
     * <p>不改导航栏：底部那条系统手势区本来就是深色、和页面过渡自然，
     * 一并透明会让页面自己的导航栏被手势条压住。用户这次指的也只是顶部。
     */
    private void applyEdgeToEdge() {
        try {
            android.view.Window w = getWindow();
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            // 只用**状态栏**这一条：LAYOUT_FULLSCREEN 让内容铺到状态栏后面，
            // 但**不**动导航栏 —— 底部那条系统手势区仍然会给内容让位。
            //
            // 试过 API 30 的 setDecorFitsSystemWindows(false)：它一次把
            // 状态栏和导航栏都放开，结果页面自己的底部导航被手势条切掉了
            // 一截（截图确认）。要那样做就得自己处理 bottom inset，
            // 而这里只想修顶部那条带，没必要引入 inset 监听。
            w.getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // 状态栏变透明后，图标颜色必须跟着页面明暗走：
                    // 浅色页面（玻璃浅色 Mesh 几乎全白）如果还用系统给的
                    // 浅色图标，时间和电量会糊在背景里看不见。
                    | (Tokens.theme(this) != Tokens.THEME_DARK
                       && Build.VERSION.SDK_INT >= 23
                       ? android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0));
        } catch (Throwable ignore) {
            // 个别 ROM 不支持就退回默认行为，不影响功能
        }
    }

    /**
     * 状态栏高度（px），拿不到就按 24dp 兜底。
     *
     * <p>给页头手动让位用 —— 详见 showSettings 里 head padding 的注释。
     * 读系统 dimen（与 FloatingService 里 positionActionBubble 同一路写法），
     * 不走废弃的 getSystemWindowInsetTop。
     */
    private int statusBarHeightPx() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                int h = getResources().getDimensionPixelSize(id);
                if (h > 0) return h;
            }
        } catch (Throwable ignore) {}
        return (int) (getResources().getDisplayMetrics().density * 24);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate() **之前**设主题，否则不生效。
        //
        // 这是修"深色主题下系统控件还是白底"的干净解法：本工程没有自定义 theme
        // 资源，系统自绘的东西（弹窗、SeekBar、菜单、输入法选择菜单）只认 Activity 主题。
        // ★ 轮次㊵ 起：弹窗**另外**还会被 UiFactory.dialog 套上我们的令牌面板
        //   （圆角 + surface + 标题/按钮配色，皮肤与明暗都跟随），所以这里只负责
        //   "系统控件别是白底"这一层；两层是互补的，别删任何一层。
        // ★ 这份实现原本是本类私有、而 Tokens 里另有一份给另外几个窗口共用
        //   （逐字等价）⇒ 轮次㊵ 合并成一份：改一处漏两处的老问题。
        Tokens.applyPlatformTheme(this);
        super.onCreate(savedInstanceState);
        applyEdgeToEdge();   // 状态栏透明：页面背景铺到最顶端（详见方法注释）
        // ★ 注册"跟随系统手势"的预测式返回（仅 API 33+）。
        //   必须尽早注册 —— 放在下面的 early-return 之前，
        //   否则 ACTION_PICK_IMAGE / 无 Key 这两条分支直接 return，
        //   返回手势就没接上（用户从相册回来按返回会直接退出 App）。
        registerPredictiveBack();
        if (savedInstanceState != null) settingsSection = savedInstanceState.getInt("settingsSection", -1);
        // ★ 悬浮窗长按菜单「显示项…」带过来的小节号：**放在恢复之后**，
        //   显式跳转必须压过"上次停在哪一页"。
        // 角色包导入（EXTRA_IMPORT_PACK）：冷启动也要认，所以除了 onNewIntent 这里也接一次
        if (!applyImportPack(getIntent())) applyJumpSection(getIntent());
        ApiClient.init(this);
        ApiClient.setBaseUrl(Prefs.getBaseUrl(this));

        // ★★ 用户 2026-10-11 要求：「把打开软件（没有输入 apiKey 的情况下）弹出的 apiKey
        //   输入窗口取消掉，不强制输入，默认本地模式」。
        //   ⇒ **启动永远不因为"没有 Key"拦人**：
        //     ① 没有 Key ⇒ 自动进入**纯本地模式**（互动发言关 = 只念本地台词），照常进设置页；
        //     ② 想联网/对话时，到「对话与人格 → API Key」里填 —— 那条路仍在，
        //        而且弹窗现在**可取消**（取消只是关掉，不再 `finish()` 退出 App）。
        //   ★ 这也修掉一个**过度门控**：换形象/上传图片（ACTION_PICK_IMAGE）跟 Key 毫无关系，
        //     以前却会被"没有 Key"挡住。
        if (!Prefs.hasApiKey(this)) Prefs.setInteractSpeakEnabled(this, false);

        if (Prefs.ACTION_PICK_IMAGE.equals(getIntent().getAction())) {
            // 换形象不需要 Key（见上）
            ensureOverlayThen(new Runnable() {
                @Override public void run() {
                    pickImage();
                }
            });
            return;
        }

        maybeHintApiKeyOnce();
        ensureOverlayThen(new Runnable() {
            @Override public void run() {
                showSettings();
            }
        });
    }

    /**
     * 没有 API Key 时给一句**不打断**的提示（Toast，不是弹窗）。
     *
     * <p>★ 与"启动不拦人"配套：用户得知道"为什么桌宠不联网说话"以及"去哪填"，
     * 但不能因为缺一个可选配置就把人挡在门外（用户 2026-10-11 的要求）。
     * 每次启动都提示一次 —— 填了 Key 之后自然消失，不需要额外的"已提示过"状态。
     */
    private void maybeHintApiKeyOnce() {
        if (Prefs.hasApiKey(this)) return;
        // ★ 设备侧只能靠日志验"这句提示真的出来了"（Toast 不在 uiautomator dump 里）
        android.util.Log.i("DSPKey", "无 Key：已进本地模式并提示（不再弹强制输入窗）");
        Toast.makeText(this,
                "还没填 API Key：先用本地模式陪你（互动台词走本地）。"
                + "想联网搜索/聊天，到「对话与人格 → API Key」里填即可。",
                Toast.LENGTH_LONG).show();
    }

    /**
     * 处理"直达某个二级页"的 intent（悬浮窗长按菜单「显示项…」）。
     *
     * @return 是否真的跳了
     */
    /** 处理 {@link #EXTRA_IMPORT_PACK}：直接导入指定的角色包文件 */
    private boolean applyImportPack(Intent intent) {
        if (intent == null) return false;
        String path = intent.getStringExtra(EXTRA_IMPORT_PACK);
        if (path == null || path.trim().isEmpty()) return false;
        final File f = new File(path.trim());
        android.util.Log.i("DSPChar", "applyImportPack path=" + path + " isFile=" + f.isFile()
                + " len=" + (f.isFile() ? f.length() : -1));
        if (!f.isFile()) {
            Toast.makeText(this, "找不到角色包文件：" + path, Toast.LENGTH_LONG).show();
            return true;
        }
        Toast.makeText(this, "正在导入角色包…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                CharacterImporter.Result r;
                try {
                    r = CharacterImporter.install(MainActivity.this,
                            new java.io.FileInputStream(f));
                } catch (Throwable t) {
                    r = new CharacterImporter.Result();
                    r.error = "打不开文件：" + t;
                }
                final CharacterImporter.Result rr = r;
                runOnUiThread(new Runnable() {
                    @Override public void run() { afterCharacterImport(rr); }
                });
            }
        }, "pet-char-import").start();
        return true;
    }

    /** 导入完成后的收尾：切到新角色 + 重建桌宠 + 汇报结果 */
    private void afterCharacterImport(CharacterImporter.Result r) {
        android.util.Log.i("DSPChar", "import done ok=" + (r != null && r.ok)
                + " id=" + (r != null ? r.id : "-")
                + " clips=" + (r != null ? r.clips : -1)
                + " missing=" + (r != null ? r.missing.size() : -1)
                + " synth=" + (r != null && r.synthesized)
                + " scenes=" + (r != null ? r.synScenes : -1)
                + " appear=" + (r != null ? r.appearanceFiles : -1)
                + " err=" + (r != null ? r.error : "-"));
        if (r == null || !r.ok) {
            Toast.makeText(this, "导入失败：" + (r == null ? "未知错误" : r.error),
                    Toast.LENGTH_LONG).show();
            return;
        }
        CharacterStore.setCurrentId(this, r.id);
        Prefs.reloadPet(this);
        String msg = "已导入角色「" + r.name + "」：" + r.clips + " 个动作";
        // ★ 待办 0m ①：包里带了形象 ⇒ 如实说（"换了脸"是用户第一眼就能看出来的变化，
        //   不说的话他会以为是 bug）
        if (r.appearance) msg += "，含形象素材（" + r.appearanceFiles + " 个文件）";
        if (r.synthesized) {
            // ★ 待办 0j 的 A：包里没有 anim/pack.json，配置是导入器按文件名合成的。
            //   这句提示要留着 —— 用户得知道自己"少写了一份 JSON 也能用"，
            //   否则下次他还是会去手写 18 个场景的配置。
            msg += "（包里没有 anim/pack.json，已按文件名自动归到 " + r.synScenes + " 个场景）";
        }
        if (!r.missing.isEmpty()) {
            msg += "（" + r.missing.size() + " 个动作缺文件，已跳过：" + r.missing.get(0)
                    + (r.missing.size() > 1 ? " 等" : "") + "）";
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        showSettings();
    }

    private boolean applyJumpSection(Intent intent) {
        if (intent == null) return false;
        int jump = intent.getIntExtra(EXTRA_SECTION, -1);
        if (jump < 0) return false;
        settingsSection = jump;
        settingsTab = tabOfSection(jump);
        return true;
    }

    /**
     * ★ 已经开着本页时再点「显示项…」（SINGLE_TOP）走这里。
     *
     * <p>不实现的话第二次点击完全没反应 —— 用户会以为菜单坏了。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (applyImportPack(intent)) { /* 导入在后台线程跑，完成后自己刷新界面 */ }
        else if (applyJumpSection(intent)) showSettings();
    }

    /** 输入当前服务商的 API Key */
    private void showApiKeyDialog() {
        final ChatProvider.Entry prov = ChatProvider.find(Prefs.getChatProvider(this));
        final EditText input = new EditText(this);
        input.setInputType(129); // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
        input.setHint("sk-xxxxxxxx");
        input.setText(Prefs.getApiKey(this));

        LinearLayout box = new LinearLayout(this);
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        box.setPadding(pad, pad, pad, pad);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        UiFactory.dialog(this)
                .setTitle("输入 " + prov.name + " 的 API Key")
                .setMessage("Key 只保存在本机。**各家分别保存**，切换服务商时会自动"
                        + "使用该家已存的 Key，不必重填。\n\n"
                        + "不填也能用：桌宠会以**本地模式**陪你（互动台词走本地）；"
                        + "联网搜索与完整对话才需要 Key。\n\n"
                        + "当前服务商：" + prov.name + "\n"
                        + "接口地址：" + Prefs.getBaseUrl(this) + "\n"
                        + (prov.hasBalance() ? "余额：可查询"
                           : "余额：该服务商未提供余额接口（对话不受影响）"))
                .setView(box)
                // ★ 用户 2026-10-11：「不强制输入」⇒ 弹窗可取消，取消只是关掉。
                //   原来 setCancelable(false) + 取消=finish()：没 Key 的用户**只有退出**这一条路。
                .setCancelable(true)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        final String key = input.getText().toString().trim();
                        if (key.length() == 0) {
                            Toast.makeText(MainActivity.this,
                                    "Key 不能为空（不填也行，直接用本地模式）",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Prefs.saveApiKey(MainActivity.this, key);
                        ensureOverlayThen(new Runnable() {
                            @Override public void run() {
                                showSettings();
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 自定义 API 基地址：支持 DeepSeek 官方或任意兼容 OpenAI 的网关 */
    private void showBaseUrlDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://api.deepseek.com 或 http://192.168.1.5:7863/v1");
        input.setText(Prefs.getBaseUrl(this));

        LinearLayout box = new LinearLayout(this);
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        box.setPadding(pad, pad, pad, pad);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        UiFactory.dialog(this)
                .setTitle("\uD83D\uDD17 自定义 API 地址")
                .setMessage("填写兼容 OpenAI 的接口基地址（可带 /v1）。\n"
                        + "官方：https://api.deepseek.com\n"
                        + "本机/局域网网关：http://192.168.1.5:7863/v1（明文 http 已允许）\n"
                        + "★ 手机上的 127.0.0.1 指**手机自己**，连不到你电脑上的服务；"
                        + "模拟器里要连宿主机请用 10.0.2.2。\n"
                        + "注意：/user/balance 为 DeepSeek 专有，非官方地址下余额将无法查询。")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        String url = input.getText().toString().trim();
                        if (url.length() == 0) url = "https://api.deepseek.com";
                        final String finalUrl = url;
                        String advice = Endpoint.advice(url);
                        if (advice.length() > 0) {
                            // ★★ 轮次56：地址"一看就连不上"时**先说清楚再存**。
                            //   用户填的 127.0.0.1 在他电脑上是通的（agent 就在用），
                            //   所以只报"连接失败"只会让他更困惑 —— 必须解释"设备上的 127.0.0.1 是谁"。
                            UiFactory.dialog(MainActivity.this)
                                    .setTitle("\u26A0 这个地址可能连不上")
                                    .setMessage(advice)
                                    .setPositiveButton("仍然保存", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d2, int w2) {
                                            saveBaseUrlAndToast(finalUrl);
                                        }
                                    })
                                    .setNegativeButton("改一改", null)
                                    .show();
                            return;
                        }
                        saveBaseUrlAndToast(finalUrl);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 存地址 + 同步给 ApiClient + 提示（保存路径只有这一条，免得两条路走岔） */
    private void saveBaseUrlAndToast(String url) {
        Prefs.saveBaseUrl(this, url);
        ApiClient.setBaseUrl(Prefs.getBaseUrl(this));
        Toast.makeText(this, "已保存：" + ApiClient.getBaseUrl(), Toast.LENGTH_SHORT).show();
        showSettings();
    }

    /**
     * 「🧪 测试连接」（轮次56）。
     *
     * <p>★ 为什么值得单做一个入口：用户报"自定义对话 API 无法正常使用"，而原来的失败路径
     * 把异常**整个吞掉**（只显示"获取模型失败，可能是网络问题"）—— 用户既不知道是
     * 明文被禁、连接被拒、401 还是 404，也无从下手。这里把**真实结果**原样摆出来：
     * 地址 / HTTP 状态码 / 服务端 message / 异常原文。
     */
    private void runConnectionTest() {
        final ProgressDialog pd = UiFactory.progress(this);
        pd.setMessage("正在测试连接…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override public void run() {
                final ApiClient.Probe p =
                        ApiClient.probeModels(Prefs.getApiKey(MainActivity.this));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { pd.dismiss(); } catch (Exception ignore) {}
                        showProbeResult(p);
                    }
                });
            }
        }).start();
    }

    /** 把「测试连接」的结果摆给用户（含"地址本身有问题"时的建议） */
    private void showProbeResult(ApiClient.Probe p) {
        StringBuilder sb = new StringBuilder();
        sb.append("地址：").append(p.url).append("\n");
        if (p.http != null && p.http.ok()) {
            sb.append("结果：HTTP ").append(p.http.code)
              .append("，读到 ").append(p.modelCount).append(" 个模型\n");
            sb.append(p.modelCount > 0
                    ? "\n\u2713 通了 —— 可以点「模型」那一行去选。"
                    : "\n接口通了，但没有模型列表：该地址可能不是 OpenAI 兼容的 /v1。");
        } else {
            sb.append("结果：").append(p.http == null ? "没有结果" : p.http.describe()).append("\n");
            sb.append("\n对话会一直失败，直到这条能通。");
        }
        if (p.advice != null && p.advice.length() > 0) {
            sb.append("\n\n\u26A0 地址本身就有问题：\n").append(p.advice);
        }
        UiFactory.dialog(this)
                .setTitle("\uD83E\uDDEA 测试连接")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    // ==================== 点击音效（轮次57）====================

    /**
     * 「🎵 音效音色」：**选一档就当场试听**。
     * <p>声音这类设置"调完还得退出去戳一下才知道效果"，不试听等于没法调。
     */
    private void pickClickStyle() {
        final String[] names = new String[ClickSound.STYLE_COUNT];
        final int cur = ClickSound.styleIndex(Prefs.clickSoundStyle(this));
        for (int i = 0; i < ClickSound.STYLE_COUNT; i++) {
            names[i] = ClickSound.styleName(i) + (i == cur ? "（当前）" : "");
        }
        UiFactory.dialog(this)
                .setTitle("\uD83C\uDFB5 音效音色（点一下即试听）")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        Prefs.setClickSoundStyle(MainActivity.this, which);
                        ClickPlayer.play(which, Prefs.clickSoundVolume(MainActivity.this));
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 「🔉 音效音量」：三档，选完当场试听 */
    private void pickClickVolume() {
        final float[] vals = { ClickSound.VOL_LOW, ClickSound.VOL_MID, ClickSound.VOL_HIGH };
        final float cur = Prefs.clickSoundVolume(this);
        String[] names = new String[vals.length];
        for (int i = 0; i < vals.length; i++) {
            names[i] = ClickSound.volumeName(vals[i])
                    + "（" + Math.round(vals[i] * 100f) + "%）"
                    + (Math.abs(vals[i] - cur) < 0.01f ? "（当前）" : "");
        }
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDD09 音效音量（点一下即试听）")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        Prefs.setClickSoundVolume(MainActivity.this, vals[which]);
                        ClickPlayer.play(Prefs.clickSoundStyle(MainActivity.this), vals[which]);
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 服务商摘要（用于设置项标题） */
    private String chatProviderSummary() {
        ChatProvider.Entry e = ChatProvider.find(Prefs.getChatProvider(this));
        return e.name;
    }

    /**
     * 选择对话服务商。
     *
     * <p>选定后会**同时切换基地址与默认模型**（若用户还没改过模型）——
     * 否则会出现"选了 Kimi 但地址还是 DeepSeek"这种自相矛盾的状态，
     * 而那正是 400 报错的温床。
     */
    /**
     * 选择对话服务商。
     *
     * <h3>★ 用"选定 + 确认"而不是"点了就生效"</h3>
     * 原实现用 {@code setSingleChoiceItems} 的点击回调**立即写入并关闭**。
     * 问题是这个动作**同时改三样东西**（服务商 / 接口地址 / 默认模型），
     * 而对话框里只显示一个名字 —— 用户点到一半想反悔时，
     * 前一个已经被写进去并生效了。
     *
     * <p>现在：点选只改本地高亮，按「选定」才落盘。
     * 并且确认前就把**将会发生什么**显示出来（地址、模型、Key 状态），
     * 而不是改完再弹 Toast 告诉用户。
     */
    private void showChatProviderDialog() {
        final java.util.List<ChatProvider.Entry> all = ChatProvider.all();
        final String[] labels = new String[all.size()];
        int cur = 0;
        String curId = Prefs.getChatProvider(this);
        for (int i = 0; i < all.size(); i++) {
            ChatProvider.Entry e = all.get(i);
            boolean saved = Prefs.hasApiKeyFor(this, e.id);
            String head = e.name + (e.id.equals(curId) ? "（当前）" : "");
            // 第二行给关键信息：Key 状态 + 余额能力。
            // 用户切来切去时最想知道的就是这两件 —— 直接写在选项上，
            // 不必先选定、也不必靠 Toast 事后告知。
            String sub;
            if (ChatProvider.LOCAL_ID.equals(e.id)) {
                // ★★ 本地模型那一项**不能**跟着显示"还没有 Key" —— 它根本不需要 Key。
                //     第二行要说的是用户真正关心的：模型导进来了没有。
                sub = "纯本机跑，不联网 · " + (ModelStore.current(this) == null
                        ? "还没导入模型" : "本机模型已就绪");
            } else {
                sub = (saved ? "Key 已保存，自动使用" : "还没有 Key");
                if (!e.hasBalance()) sub = sub + " · 无余额接口";
            }
            labels[i] = head + "\n　" + sub;
            if (e.id.equals(curId)) cur = i;
        }
        final int[] chosen = {cur};
        UiFactory.dialog(this)
                .setTitle("\uD83C\uDF10 选择对话服务商（选定后生效）")
                .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        // ★ 只记录选择，不写盘 —— 写盘在「选定」里做。
                        //   刻意什么都不改：让用户能反复比较后再决定。
                        chosen[0] = which;
                    }
                })
                .setPositiveButton("选定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int idx = chosen[0];
                        if (idx >= 0 && idx < all.size()) {
                            applyChatProvider(all.get(idx));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 自定义界面配色 / UI 透明度（玻璃态 · 轮次㊾）====================
    //
    // 用户要求：「自定义主题颜色（玻璃态）可以自定义界面ui颜色 用来搭配不同的背景 增加ui透明度选项」。
    // 方案已逐条拍板：只做玻璃态 / 分组调 / 三项透明度 / 文字自动重推 / 暂不做导入导出。
    // 取值与派生规则全在 ThemeOverride（纯类，有断言钉着）。

    /**
     * 透明度档位（轮次㊾）。
     * ★ 卡片下限 50% / 弹窗下限 75% —— 与 {@link ThemeOverride} 里的引擎下限一一对应
     *   （界面能选到的最低值必须 ≥ 引擎夹紧值，否则"选了 30% 却显示 50%"）
     */
    private static final float[] A_BG = { 1f, 0.9f, 0.8f, 0.7f, 0.5f, 0.35f, 0.2f, 0f };
    private static final float[] A_SURFACE = { 1f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f };
    private static final float[] A_MODAL = { 1f, 0.95f, 0.9f, 0.85f, 0.8f, 0.75f };

    private static String pct(float a) { return Math.round(a * 100f) + "%"; }

    private String customThemeSummary() {
        ThemeOverride.V v = ThemeOverride.load(this);
        if (v.isEmpty()) return "默认";
        StringBuilder sb = new StringBuilder();
        if (v.accent != null) sb.append("主色 ").append(ThemeOverride.hex(v.accent));
        if (v.bg != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("底色 ").append(ThemeOverride.hex(v.bg));
        }
        String tone = (v.textTone == null) ? "auto" : v.textTone;
        if (!"auto".equals(tone)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("light".equals(tone) ? "浅字" : "深字");
        }
        // ★ 轮次55：只改了光斑也要在设置页那一行看得见（否则"自定义了但摘要说默认"）
        if (v.meshStrength != null || v.meshScale != null || "accent".equals(v.meshTint)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("光斑 ").append(meshSummary(v));
        }
        return sb.length() == 0 ? "仅透明度" : sb.toString();
    }

    /**
     * 改完自定义配色之后：重载桌宠 + 重建设置页。
     * ★ 重建时会重新 `uiT()`，而它的缓存 key 里含 `ThemeOverride.revision()` ⇒ 拿到新令牌
     *   （否则页面还在用上一次缓存的那套 Set，表现成"改了没反应"）。
     */
    private void afterThemeCustomChanged() {
        try { Prefs.reloadPet(this); } catch (Throwable ignore) {}
        showSettings();
        // ★★ 轮次52：**改完立刻重开这一页** —— 预览卡是"用当前令牌画出来的真控件"，
        //   重开就等于实时刷新（用户换一个颜色马上能在预览里看到效果）。
        //   ⚠ 前提：所有子弹窗的回调**都必须先 dismiss 自己**（见下面的注释），
        //   否则新开的这一页会被上一个弹窗的自动 dismiss 连带关掉。
        showCustomTheme();
    }

    /**
     * 当前开着的「自定义界面颜色」页。
     *
     * <p>★ 轮次55 修：改完配色会**重开这一页**（"实时预览"），而旧的那一层从来没被 dismiss
     * ⇒ 每改一次就叠一层。设备实测（改一次 Mesh 浓度）：第 1 次点「关闭」页面还在、
     * **第 2 次才回得到设置页** —— 用户视角就是"点了关闭没反应，得点两下"。
     * 这是轮次52"改完重开"引入的（当时只按"文字在不在"判过，叠层时那种判据照样绿）。
     */
    private android.app.Dialog customThemeDlg;

    /** 同上：「✨ Mesh 光斑」那一层。改完档位要重开自定义页 ⇒ 它也必须一起关掉 */
    private android.app.Dialog meshPickerDlg;

    /**
     * 「自定义界面颜色」页（轮次52 改成**自绘弹窗**：顶部一张**实时预览卡** + 下面操作行）。
     *
     * <p>为什么从 `setItems` 换成自绘：预览卡是自定义 View，而 `setItems` 会把 view 顶掉
     * （同一位置只能有一个）。顺带一个好处 —— 自绘的行是**我自己挂的点击**，
     * 不存在"ListView 里的自绘行不可点"那类问题。
     */
    private void showCustomTheme() {
        // ★ 轮次55：先把上一次开着的这一页关掉（否则会一层层叠上去，见 customThemeDlg 的注释）
        if (customThemeDlg != null) {
            try { customThemeDlg.dismiss(); } catch (Throwable ignore) {}
            customThemeDlg = null;
        }
        if (meshPickerDlg != null) {
            try { meshPickerDlg.dismiss(); } catch (Throwable ignore) {}
            meshPickerDlg = null;
        }
        final ThemeOverride.V v = ThemeOverride.load(this);
        final String tone = (v.textTone == null) ? "auto" : v.textTone;
        final Tokens.Set t = uiT();
        final float d = getResources().getDisplayMetrics().density;
        final int pad = (int) (d * 14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(sectionLabelFor(t, "预览（用当前配色画的真实控件，改完立刻变）"));
        root.addView(buildThemePreview(t));
        root.addView(sectionLabelFor(t, "调整"));

        addThemeActionRow(root, "主色调："
                + (v.accent == null ? "默认" : ThemeOverride.hex(v.accent)), new Runnable() {
            @Override public void run() { showColorPicker(true); }
        });
        addThemeActionRow(root, "背景底色："
                + (v.bg == null ? "默认" : ThemeOverride.hex(v.bg)), new Runnable() {
            @Override public void run() { showColorPicker(false); }
        });
        addThemeActionRow(root, "文字颜色："
                + ("auto".equals(tone) ? "自动（按底色重推）"
                   : ("light".equals(tone) ? "强制浅色字" : "强制深色字")), new Runnable() {
            @Override public void run() { pickTextTone(); }
        });
        // ★ 轮次55：Mesh 光斑可调（P2 最后一项）—— 浓度 / 扩散 / 光斑色是否跟随主色。
        //   放在"文字颜色"之后：这一组全是"背景与底色"的观感，改完预览卡立刻变。
        addThemeActionRow(root, "\u2728 Mesh 光斑：" + meshSummary(v), new Runnable() {
            @Override public void run() { showMeshPicker(); }
        });
        addThemeActionRow(root, "\uD83D\uDDBC 从背景图取色…（搭配你现在的背景）", new Runnable() {
            @Override public void run() { pickFromBackgroundImage(); }
        });
        addThemeActionRow(root, "\u2B06 导出这套配色…（.json，可分享给别人）", new Runnable() {
            @Override public void run() { startThemeExport(); }
        });
        addThemeActionRow(root, "\u2B07 导入配色…（文件选择 / 从电脑）", new Runnable() {
            @Override public void run() { showThemeImportPicker(); }
        });
        // ★ 轮次54：**配色体检读数** —— 内置色值的对比度都是手调出来的（Tokens 里写着一串
        //   "原值 3.99:1 → 改后 4.82:1"），用户一旦能自定义，那些保证就不再自动成立
        //   ⇒ 把读数直接摆在行上，点开看逐条。
        addThemeActionRow(root, "\uD83E\uDE7A 配色体检：" + ThemeOverride.auditSummary(t),
                new Runnable() {
            @Override public void run() { showThemeAudit(); }
        });
        addThemeActionRow(root, "\u21BA 主色调恢复默认", new Runnable() {
            @Override public void run() {
                ThemeOverride.V nv = ThemeOverride.load(MainActivity.this);
                nv.accent = null;
                ThemeOverride.save(MainActivity.this, nv);
                afterThemeCustomChanged();
            }
        });
        addThemeActionRow(root, "\u21BA 底色恢复默认", new Runnable() {
            @Override public void run() {
                ThemeOverride.V nv = ThemeOverride.load(MainActivity.this);
                nv.bg = null;
                ThemeOverride.save(MainActivity.this, nv);
                afterThemeCustomChanged();
            }
        });
        addThemeActionRow(root, "\u21BA 这一套全部恢复默认", new Runnable() {
            @Override public void run() {
                ThemeOverride.clear(MainActivity.this);
                afterThemeCustomChanged();
            }
        });

        // ★ 轮次55：内容套一层 ScrollView —— 这一页已经有 10 行，实测在 1080x2400 上
        //   最后两行与「关闭」按钮会被挤出可视区（点了没反应）。套上之后由弹窗自己限高、
        //   内容内部滚动，行数再长也不会"有行点不到"。
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        customThemeDlg = UiFactory.dialog(this)
                .setTitle("\uD83C\uDFA8 自定义界面颜色（玻璃态 · "
                        + (Tokens.theme(this) == Tokens.THEME_DARK ? "深色" : "浅色") + "）")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .show();
    }

    // ==================== Mesh 光斑可调（玻璃态 · 轮次55）====================
    //
    // P2 的最后一项。形态照"UI 透明度"那一套（两级 setItems）：一行进三组档位，
    // 每组再弹一列档位。★ 只有**分组档位**，不给"逐个光斑调位置/颜色"——
    // 那与既有的"分组调、不暴露每个令牌"边界冲突（见 ThemeOverride 的类注释）。

    /** 光斑浓度档位（0 = 关掉光斑；1.0 = 内置） */
    private static final float[] MESH_LEVELS = { 0f, 0.6f, 1f, 1.4f };
    private static final String[] MESH_LEVEL_NAMES = { "关（不要光斑）", "淡", "标准（默认）", "浓" };
    /** 光斑扩散档位（半径倍数；1.0 = 内置） */
    private static final float[] MESH_SCALES = { 0.6f, 1f, 1.4f };
    private static final String[] MESH_SCALE_NAMES = { "收紧", "标准（默认）", "扩散" };

    /** 浓度值 → 档位名（"关/淡/标准/浓"）。**读数一律从值反推**，不另存一份状态 */
    private static String meshLevelName(Float s) {
        float x = (s == null) ? 1f : s;
        if (x <= 0.001f) return "关";
        if (x < 0.85f) return "淡";
        if (x <= 1.15f) return "标准";
        return "浓";
    }

    /** 扩散值 → 档位名（"收紧/标准/扩散"） */
    private static String meshScaleName(Float sc) {
        float x = (sc == null) ? 1f : sc;
        if (x < 0.85f) return "收紧";
        if (x > 1.15f) return "扩散";
        return "标准";
    }

    /** 自定义页那一行的当前值（例：`浓 · 跟随主色`） */
    private static String meshSummary(ThemeOverride.V v) {
        if (v == null) return "标准";
        StringBuilder sb = new StringBuilder(meshLevelName(v.meshStrength));
        if (v.meshScale != null && Math.abs(v.meshScale - 1f) > 0.001f) {
            sb.append(" · ").append(meshScaleName(v.meshScale));
        }
        if ("accent".equals(v.meshTint)) sb.append(" · 跟随主色");
        return sb.toString();
    }

    /** 「✨ Mesh 光斑…」—— 三组档位 + 全部恢复默认 */
    private void showMeshPicker() {
        final ThemeOverride.V v = ThemeOverride.load(this);
        String[] items = {
                "光斑浓度：" + meshLevelName(v.meshStrength) + "（越浓越花，也越吃文字对比度）",
                "扩散范围：" + meshScaleName(v.meshScale) + "（每团光斑的大小）",
                "光斑颜色：" + ("accent".equals(v.meshTint) ? "跟随主色"
                                                            : "内置配色（蓝/绿/紫几团）"),
                "\u21BA 全部恢复默认",
        };
        meshPickerDlg = UiFactory.dialog(this)
                .setTitle("\u2728 Mesh 光斑（玻璃态 · "
                        + (Tokens.theme(this) == Tokens.THEME_DARK ? "深色" : "浅色") + "）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which <= 2) pickMesh(which);
                        else {
                            ThemeOverride.V nv = ThemeOverride.load(MainActivity.this);
                            nv.meshStrength = null; nv.meshScale = null; nv.meshTint = null;
                            ThemeOverride.save(MainActivity.this, nv);
                            afterThemeCustomChanged();
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 光斑的某一组档位（0=浓度 / 1=扩散 / 2=颜色） */
    private void pickMesh(final int kind) {
        final String[] vals = (kind == 2)
                ? new String[] { "builtin", "accent" }
                : null;
        final float[] nums = (kind == 0) ? MESH_LEVELS : (kind == 1 ? MESH_SCALES : null);
        final String[] names = (kind == 2)
                ? new String[] { "内置配色（蓝/绿/紫几团，推荐）", "跟随主色" }
                : (kind == 0 ? MESH_LEVEL_NAMES : MESH_SCALE_NAMES);
        UiFactory.dialog(this)
                .setTitle(kind == 0 ? "光斑浓度" : (kind == 1 ? "扩散范围" : "光斑颜色"))
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        ThemeOverride.V nv = ThemeOverride.load(MainActivity.this);
                        if (kind == 0) nv.meshStrength = nums[which];
                        else if (kind == 1) nv.meshScale = nums[which];
                        else nv.meshTint = vals[which];
                        ThemeOverride.save(MainActivity.this, nv);
                        afterThemeCustomChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 自定义页里的一行操作（自绘 + 自己挂点击 + 按压反馈） */
    private void addThemeActionRow(LinearLayout root, String label, final Runnable onClick) {
        final Tokens.Set t = uiT();
        final float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(t.text1);
        tv.setPadding((int) (d * 4), (int) (d * 12), (int) (d * 4), (int) (d * 12));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { onClick.run(); }
        });
        UiFactory.pressFx(tv);
        root.addView(tv);
    }

    /**
     * **实时预览卡**（轮次52）：用**当前令牌**画一小块真实界面 ——
     * 玻璃卡片 + 三档文字（text1/2/3）+ 主/次按钮 + 一排状态色小条。
     *
     * <p>★ 它是"所见即所得"的关键：用户不用退出这一页去别处对照，
     * 改完颜色这一页会重开（见 {@link #afterThemeCustomChanged()}），预览立刻反映新配色。
     * ★ 卡片用 {@code UiKit.card} ⇒ 会自动跟随引擎（玻璃/新拟态）与深浅，不是我另画一套。
     */
    private View buildThemePreview(Tokens.Set t) {
        final float d = getResources().getDisplayMetrics().density;
        final int pad = (int) (d * 14);

        // ★ 轮次55：预览卡**垫在自己的 Mesh 背景上**（原来是直接贴在弹窗面板上）。
        //   光斑可调之后，"改完立刻变"必须**连光斑一起看得见** —— 否则浓度/扩散/跟随主色
        //   这三项在预览里毫无反应，用户只能退出去看整页。这一层用的就是页面同一套
        //   {@code MeshDrawable}（同一个令牌集），不是另画一个近似效果。
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        int half = pad / 2;
        stage.setPadding(half, half, half, half);
        stage.setBackground(new UiKit.MeshDrawable(t));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(UiKit.card(this, t, Tokens.R_CARD));

        TextView title = new TextView(this);
        title.setText("标题示例 · 玻璃卡片");
        title.setTextSize(16);
        title.setTextColor(t.text1);

        TextView body = new TextView(this);
        body.setText("正文示例：这段说明文字应当清晰可读。");
        body.setTextSize(14);
        body.setTextColor(t.text2);
        body.setPadding(0, (int) (d * 6), 0, 0);

        TextView hint = new TextView(this);
        hint.setText("次级提示 12sp（最容易被调糊的那一档）");
        hint.setTextSize(12);
        hint.setTextColor(t.text3);
        hint.setPadding(0, (int) (d * 4), 0, (int) (d * 10));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView b1 = UiFactory.button(this, t, "主按钮", true);
        TextView b2 = UiFactory.button(this, t, "次按钮", false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        blp.rightMargin = (int) (d * 8);
        b1.setLayoutParams(blp);
        b2.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(b1);
        btns.addView(b2);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, (int) (d * 12), 0, 0);
        int[] colors = { t.accent, t.softAccent, t.success, t.softSuccess,
                         t.warning, t.danger };
        for (int c : colors) {
            View chip = new View(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, (int) (d * 10), 1f);
            clp.rightMargin = (int) (d * 4);
            chip.setLayoutParams(clp);
            chip.setBackground(UiKit.solidFill(this, t, c, Tokens.R_CHIP));
            chips.addView(chip);
        }

        card.addView(title);
        card.addView(body);
        card.addView(hint);
        card.addView(btns);
        card.addView(chips);
        stage.addView(card);
        return stage;
    }

    /** 调色卡上的预设色（6 列 × 4 行） */
    private static final String[] SWATCHES = {
            "#1D5AC5", "#2F7BE0", "#2AA1C8", "#148F8A", "#2E7D4F", "#5AA83C",
            "#7B8A1E", "#C2A21A", "#C2701A", "#D9541E", "#B3261E", "#C2185B",
            "#6C4BC5", "#8E5BD0", "#C25BA8", "#E0709A", "#4A5A72", "#33363B",
            "#111318", "#FFFFFF", "#F3EFEA", "#EEF1F5", "#E8F0FB", "#E9F3EC" };

    /**
     * **可视化调色卡**（轮次㊿）。
     *
     * <p>用户原话：「**自定义颜色加一个调色卡 自己选颜色 而不是文字选项**」——
     * 上一版是 `setItems` 列出一串色值文字（`#1D5AC5` …），看不见颜色、只能念代码。
     * 现在换成：**色卡网格**（点一下即选）+ **色相/饱和度/明度三段滑块**（自己调任意颜色）
     * + **实时预览色块与色值**。手动输入 HEX 降级成"辅助入口"（neutral 键），不再是主路径。
     */
    private void showColorPicker(final boolean isAccent) {
        final Tokens.Set t = uiT();
        final float d = getResources().getDisplayMetrics().density;
        final int pad = (int) (d * 14);

        final ThemeOverride.V v = ThemeOverride.load(this);
        final int start = isAccent ? (v.accent != null ? v.accent : t.accent)
                                   : (v.bg != null ? v.bg : t.bg);
        final float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(start, hsv);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // ① 实时预览色块 + 色值
        final View preview = new View(this);
        root.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (d * 46)));
        final TextView hexTv = new TextView(this);
        hexTv.setTextSize(13);
        hexTv.setTextColor(t.text2);
        hexTv.setPadding(0, (int) (d * 8), 0, (int) (d * 10));
        root.addView(hexTv);

        // ② 三段滑块（色相 / 饱和度 / 明度）—— 自绘，与设置页其它滑块同一套皮肤
        final SeekBar hueBar = UiFactory.seekBar(this, t);
        final SeekBar satBar = UiFactory.seekBar(this, t);
        final SeekBar valBar = UiFactory.seekBar(this, t);
        hueBar.setMax(360);
        satBar.setMax(100);
        valBar.setMax(100);

        final int[] cur = { start };
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                cur[0] = android.graphics.Color.HSVToColor(hsv);
                preview.setBackground(UiKit.solidFill(MainActivity.this, t, cur[0], Tokens.R_CHIP));
                hexTv.setText("当前颜色　" + ThemeOverride.hex(cur[0])
                        + "　（色相 " + Math.round(hsv[0]) + "° · 饱和 "
                        + Math.round(hsv[1] * 100) + "% · 明度 " + Math.round(hsv[2] * 100) + "%）");
            }
        };

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                hsv[0] = hueBar.getProgress();
                hsv[1] = satBar.getProgress() / 100f;
                hsv[2] = Math.max(0.06f, valBar.getProgress() / 100f);
                refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        hueBar.setOnSeekBarChangeListener(l);
        satBar.setOnSeekBarChangeListener(l);
        valBar.setOnSeekBarChangeListener(l);

        // ③ 调色卡（网格；点一下即选中，滑块同步跟过去）
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int cols = 6, size = (int) (d * 36), gap = (int) (d * 6);
        for (int r = 0; r * cols < SWATCHES.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx >= SWATCHES.length) break;
                final Integer col = ThemeOverride.colorOrNull(SWATCHES[idx]);
                if (col == null) continue;
                View sw = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.rightMargin = gap;
                lp.bottomMargin = gap;
                sw.setLayoutParams(lp);
                // ★★ 轮次㊿：色块**必须有无障碍描述** —— 两个理由：
                //   ① 无障碍：纯色块对读屏是"看不见的东西"，得报出它是什么颜色；
                //   ② 设备断言：uiautomator 会**剔除没有文字/描述的纯 View**
                //      ⇒ 不加这句，自动验证连"色卡在不在"都数不出来（实测 0 格）。
                sw.setContentDescription("色卡 " + SWATCHES[idx]);
                // 色卡本身用实心圆角色块（选中的那格加一圈描边）
                sw.setBackground(UiKit.stroked(MainActivity.this, t,
                        UiKit.solidFill(MainActivity.this, t, col, Tokens.R_CHIP),
                        Tokens.R_CHIP, 0, t.glassBorder != 0 ? t.glassBorder : t.edge));
                sw.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) {
                        // ★ 先算进临时数组，再由 seedHsvBars 种进滑块并**从滑块回读** ——
                        //   直接在 hsv 上连调 setProgress 会自己把自己冲掉（见 seedHsvBars 注释）
                        float[] pick = new float[3];
                        android.graphics.Color.colorToHSV(col, pick);
                        seedHsvBars(hueBar, satBar, valBar, pick);
                        hsv[0] = pick[0];
                        hsv[1] = pick[1];
                        hsv[2] = pick[2];
                        refresh.run();
                    }
                });
                row.addView(sw);
            }
            grid.addView(row);
        }

        root.addView(sectionLabelFor(t, "色卡（点一下即选）"));
        root.addView(grid);
        root.addView(sectionLabelFor(t, "自己调（色相 / 饱和度 / 明度）"));
        root.addView(hueBar);
        root.addView(satBar);
        root.addView(valBar);
        root.setPadding(pad, pad, pad, pad);

        // ★★ 初始种子必须走 seedHsvBars：直接在 hsv 上连调 setProgress 会自己冲掉自己
        //   （第一版实测：主色蓝 #1D5AC5 → 调色卡显示"饱和 0% · 明度 6%"、预览近黑）
        seedHsvBars(hueBar, satBar, valBar, hsv);
        refresh.run();

        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle(isAccent ? "选择主色调" : "选择背景底色")
                .setView(root)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface x, int w) {
                        x.dismiss();   // 先关自己（x 就是本弹窗），否则下面重开的页会被连带关掉
                        applyThemeColor(isAccent, ThemeOverride.hex(cur[0]));
                    }
                })
                .setNeutralButton("手动输入色值", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface x, int w) {
                        x.dismiss();
                        askHex(isAccent);      // 辅助入口，不是主路径
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    /**
     * **从背景图取色**（P1 · 轮次51）：给四个候选 —— 主色 / 平均色 / 亮色 / 暗色，
     * 每个候选**用看得见的色块**列出来（不是色值文字），点它再问"用在底色还是主色调"。
     *
     * <p>★ 用户的原话是「自定义界面ui颜色 **用来搭配不同的背景**」—— 这是那句话最直接的实现。
     * <p>★ 视频背景**不支持**：取一帧要解码器 + 时序，成本远高于读一张图；文案里说清楚，
     * 别让用户以为是坏了。
     */
    private void pickFromBackgroundImage() {
        if (!Prefs.imageBgFile(this).isFile()) {
            String extra = "";
            try {
                if (Prefs.videoBgEnabled(this)) extra = "\n\n（你现在用的是**视频**背景 —— 视频暂不支持取色）";
            } catch (Throwable ignore) {}
            UiFactory.dialog(this)
                    .setTitle("\uD83D\uDDBC 从背景图取色")
                    .setMessage("还没设置图片背景。" + extra
                            + "\n\n请先到「界面风格 → 背景」选一张图片，再回来取色。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final int[] pal = ThemeOverride.paletteFromBackgroundImage(this);
        if (pal == null) {
            UiFactory.dialog(this)
                    .setTitle("\uD83D\uDDBC 从背景图取色")
                    .setMessage("这张背景图读不出来（可能格式不支持或文件损坏）。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final Tokens.Set t = uiT();
        final float d = getResources().getDisplayMetrics().density;
        final int pad = (int) (d * 14);
        final String[] names = { "主色", "平均色", "亮色", "暗色" };
        final AlertDialog[] bgHolder = new AlertDialog[1];   // 行回调里要能关掉自己
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < pal.length && i < names.length; i++) {
            final int col = pal[i];
            final String label = names[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int) (d * 4), (int) (d * 8), (int) (d * 4), (int) (d * 8));
            View block = new View(this);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    (int) (d * 38), (int) (d * 38));
            blp.rightMargin = (int) (d * 12);
            block.setLayoutParams(blp);
            block.setBackground(UiKit.stroked(this, t,
                    UiKit.solidFill(this, t, col, Tokens.R_CHIP), Tokens.R_CHIP, 0,
                    t.glassBorder != 0 ? t.glassBorder : t.edge));
            TextView tv = new TextView(this);
            tv.setText("背景图" + label + "\u3000" + ThemeOverride.hex(col));
            tv.setTextSize(15);
            tv.setTextColor(t.text1);
            row.addView(block);
            row.addView(tv);
            // ★ 这里每行是**我自己挂的点击**（不在 ListView 里）⇒ 不存在"自绘行不可点"的问题
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    if (bgHolder[0] != null) bgHolder[0].dismiss();
                    askWhereToApply(col, label);
                }
            });
            UiFactory.pressFx(row);
            root.addView(row);
        }
        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle("\uD83D\uDDBC 从背景图取色（点一下即选）")
                .setView(root)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    /** 取到的颜色用在哪：背景底色 / 主色调 */
    private void askWhereToApply(final int color, String from) {
        final String hx = ThemeOverride.hex(color);
        UiFactory.dialog(this)
                .setTitle("「" + from + " " + hx + "」用在哪？")
                .setItems(new String[] { "背景底色", "主色调" }, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        applyThemeColor(which == 1, hx);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 调色卡里的小标题（比 sectionLabel 轻一点，且不剥 emoji） */
    private TextView sectionLabelFor(Tokens.Set t, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(t.text3);
        tv.setPadding(0, (int) (getResources().getDisplayMetrics().density * 10), 0,
                (int) (getResources().getDisplayMetrics().density * 6));
        return tv;
    }

    /**
     * 把 HSV 种进三条滑块，再**从滑块回读**。
     *
     * <p>★★ 这个"回读"不是啰嗦，是必需的：{@code setProgress} 会触发
     * {@code onProgressChanged}，而那个回调是**从三条滑块读值写回 hsv 数组**的 ——
     * 于是"用同一份 hsv 连种三条滑块"会自己把自己冲掉：第一条 {@code hueBar.setProgress(H)}
     * 一触发，就把 {@code hsv[1]}/{@code hsv[2]} 改成了后两条**还没种**的 0，
     * 接着算出的 {@code satBar.setProgress(hsv[1] * 100)} 就永远是 0。
     *
     * <p>实测症状（第一版）：当前主色是蓝色 {@code #1D5AC5}，调色卡却显示
     * 「色相 218° · 饱和 **0%** · 明度 **6%**」、预览色 {@code #0F0F0F}（近黑）——
     * 色相对（说明确有种子进去），饱和度/明度全丢。
     */
    private void seedHsvBars(SeekBar h, SeekBar s, SeekBar v, float[] hsv) {
        final int hh = Math.round(hsv[0]);
        final int ss = Math.round(hsv[1] * 100);
        final int vv = Math.max(6, Math.round(hsv[2] * 100));
        h.setProgress(hh);
        s.setProgress(ss);
        v.setProgress(vv);
        hsv[0] = h.getProgress();
        hsv[1] = s.getProgress() / 100f;
        hsv[2] = Math.max(0.06f, v.getProgress() / 100f);
    }

    private void askHex(final boolean isAccent) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("#1D5AC5");
        et.setSingleLine(true);
        UiFactory.dialog(this)
                .setTitle(isAccent ? "主色调色值" : "背景底色色值")
                .setMessage("填 #RRGGBB（例如 #1D5AC5）")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        String s = et.getText() == null ? "" : et.getText().toString().trim();
                        if (ThemeOverride.colorOrNull(s) == null) {
                            Toast.makeText(MainActivity.this, "色值格式不对（示例 #1D5AC5）",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        applyThemeColor(isAccent, s);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyThemeColor(boolean isAccent, String hex) {
        Integer c = ThemeOverride.colorOrNull(hex);
        if (c == null) return;
        ThemeOverride.V v = ThemeOverride.load(this);
        if (isAccent) v.accent = c; else v.bg = c;
        ThemeOverride.save(this, v);
        afterThemeCustomChanged();
    }

    private void pickTextTone() {
        final String[] vals = { "auto", "dark", "light" };
        String[] names = { "自动（按底色重推，推荐）", "强制深色字", "强制浅色字" };
        UiFactory.dialog(this)
                .setTitle("文字颜色")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        ThemeOverride.V v = ThemeOverride.load(MainActivity.this);
                        v.textTone = vals[which];
                        ThemeOverride.save(MainActivity.this, v);
                        afterThemeCustomChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAlphaPicker() {
        final Tokens.Set cur = uiT();
        String[] items = {
                "背景底色：" + pct(ThemeOverride.alphaOf(cur.bg)) + "（越透越看得见你的背景图/视频）",
                "卡片面：" + pct(ThemeOverride.alphaOf(cur.surface)) + "（玻璃浓度，下限 50%）",
                "弹窗浮层：" + pct(ThemeOverride.alphaOf(cur.modal)) + "（下限 75%，再低会和页面糊住）",
                "\u21BA 全部恢复默认",
        };
        UiFactory.dialog(this)
                .setTitle("\uD83E\uDEE7 UI 透明度（玻璃态 · "
                        + (Tokens.theme(this) == Tokens.THEME_DARK ? "深色" : "浅色") + "）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which <= 2) pickAlpha(which);
                        else {
                            ThemeOverride.V v = ThemeOverride.load(MainActivity.this);
                            v.alphaBg = null; v.alphaSurface = null; v.alphaModal = null;
                            ThemeOverride.save(MainActivity.this, v);
                            afterThemeCustomChanged();
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void pickAlpha(final int kind) {
        final float[] vals = (kind == 0) ? A_BG : (kind == 1 ? A_SURFACE : A_MODAL);
        String[] names = new String[vals.length];
        for (int i = 0; i < vals.length; i++) names[i] = pct(vals[i]);
        UiFactory.dialog(this)
                .setTitle(kind == 0 ? "背景底色透明度"
                        : (kind == 1 ? "卡片面透明度（玻璃浓度）" : "弹窗浮层透明度"))
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        ThemeOverride.V v = ThemeOverride.load(MainActivity.this);
                        if (kind == 0) v.alphaBg = vals[which];
                        else if (kind == 1) v.alphaSurface = vals[which];
                        else v.alphaModal = vals[which];
                        ThemeOverride.save(MainActivity.this, v);
                        afterThemeCustomChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 毫秒 → "1 秒 / 0.5 秒 / 0 秒"（漫游前摇/后摇这类"读数值"的设置行用） */
    private static String secText(int ms) {
        if (ms <= 0) return "0 秒";
        if (ms % 1000 == 0) return (ms / 1000) + " 秒";
        return String.format(java.util.Locale.US, "%.1f 秒", ms / 1000f);
    }

    /** 「秒数」选择框的回调；{@code -1} = 跟随全局（只在对"单个动作"设置时才给这一项） */
    private interface DelayPick { void onPick(int ms); }

    /**
     * 前后摇的档位（轮次㊼ 加密）。
     * <p>用户要求「前后摇时间给的选项多一点」⇒ 0.2 秒一档到 1 秒，之后 0.2/0.5 秒一档到 4 秒。
     * 下标 0 的 {@code -1} 是"跟随全局"，只在对单个动作设置时才有。
     */
    private static final int[] DELAY_VALS = {
            -1, 0, 200, 400, 600, 800, 1000, 1200, 1500, 1800, 2000, 2500, 3000, 3500, 4000 };
    private static final String[] DELAY_NAMES = {
            "跟随全局", "0 秒（关）", "0.2 秒", "0.4 秒", "0.6 秒", "0.8 秒", "1 秒",
            "1.2 秒", "1.5 秒", "1.8 秒", "2 秒", "2.5 秒", "3 秒", "3.5 秒", "4 秒" };

    /** 弹出「秒数」选择框（全局用：不给"跟随全局"那一项） */
    private void pickDelayMs(String title, int curMs, DelayPick cb) {
        pickDelayMs(title, curMs, false, cb);
    }

    /** 弹出「秒数」选择框；{@code allowGlobal} 时第一项是"跟随全局"（回调拿到 -1） */
    private void pickDelayMs(String title, int curMs, final boolean allowGlobal,
                             final DelayPick cb) {
        int from = allowGlobal ? 0 : 1;
        final int[] vals = java.util.Arrays.copyOfRange(DELAY_VALS, from, DELAY_VALS.length);
        String[] names = java.util.Arrays.copyOfRange(DELAY_NAMES, from, DELAY_NAMES.length);
        int cur = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == curMs) { cur = i; break; }
        }
        UiFactory.dialog(this)
                .setTitle(title)
                .setSingleChoiceItems(names, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        cb.onPick(vals[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 「每个动作的前后摇」总入口：列出**漫游场景**绑定的动作。
     *
     * <p>★ 轮次㊼（用户要求「可以单独设置每个动作的前后摇，用来适配不同角色不同动画的动作」）：
     * 覆盖层按角色隔离（{@link RoamTiming} 写在 {@code filesDir/roam/<角色 id>.json}），
     * 没设过的动作显示"跟随全局"（= 全局前摇/后摇）。
     */
    private void showRoamTimingPicker() {
        final String cid = CharacterStore.currentId(this);
        ActionSystem as = null;
        try { as = ActionSystem.loadForDisplay(this); } catch (Throwable ignore) {}
        java.util.List<String> list = (as == null) ? null : as.clipsOf(SceneRegistry.ROAM);
        final String[] clips = (list == null) ? new String[0] : list.toArray(new String[0]);
        if (clips.length == 0) {
            UiFactory.dialog(this)
                    .setTitle("每个动作的前后摇")
                    .setMessage("当前角色包的「漫游」场景还没绑动作 —— 先去「动作系统」给漫游绑几个，"
                            + "再回来单独调。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final java.util.LinkedHashMap<String, RoamTiming.Timing> m = RoamTiming.load(this, cid);
        final int gLead = Prefs.wanderLeadMs(this), gTail = Prefs.wanderTailMs(this);
        String[] items = new String[clips.length + 1];
        for (int i = 0; i < clips.length; i++) {
            RoamTiming.Timing t = m.get(clips[i]);
            items[i] = clips[i] + "\n\u3000" + (t == null
                    ? ("跟随全局（" + secText(gLead) + " / " + secText(gTail) + "）")
                    : ("前摇 " + secText(t.lead) + " · 后摇 " + secText(t.tail)));
        }
        items[clips.length] = "\u21BA 全部恢复跟随全局";
        UiFactory.dialog(this)
                .setTitle("每个动作的前后摇 · 共 " + clips.length + " 个")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == clips.length) {
                            RoamTiming.clearAll(MainActivity.this, cid);
                            Prefs.reloadPet(MainActivity.this);
                            showSettings();
                            return;
                        }
                        showRoamTimingOne(cid, clips[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 单个动作：先挑前摇、再挑后摇（各自都能选"跟随全局"；两个都跟全局 ⇒ 删掉这条覆盖） */
    private void showRoamTimingOne(final String cid, final String clip) {
        final RoamTiming.Timing cur = RoamTiming.pick(RoamTiming.load(this, cid), clip);
        final int curLead = (cur == null) ? -1 : cur.lead;
        final int curTail = (cur == null) ? -1 : cur.tail;
        pickDelayMs("「" + clip + "」前摇", curLead, true, new DelayPick() {
            @Override public void onPick(final int leadMs) {
                pickDelayMs("「" + clip + "」后摇", curTail, true, new DelayPick() {
                    @Override public void onPick(int tailMs) {
                        if (leadMs < 0 && tailMs < 0) {
                            RoamTiming.clear(MainActivity.this, cid, clip);
                        } else {
                            int l = (leadMs < 0)
                                    ? Prefs.wanderLeadMs(MainActivity.this) : leadMs;
                            int t = (tailMs < 0)
                                    ? Prefs.wanderTailMs(MainActivity.this) : tailMs;
                            RoamTiming.set(MainActivity.this, cid, clip, l, t);
                        }
                        Prefs.reloadPet(MainActivity.this);
                        showSettings();
                    }
                });
            }
        });
    }

    /** 落盘并给出结果反馈（原对话框里"立即生效"的那段逻辑搬到这里） */

    private void applyChatProvider(final ChatProvider.Entry e) {
        // ★★ 轮次64：选「本地模型」是**另一种对话后端**（本机 llama.cpp，不联网、不需要 Key）。
        //    两件事要注意：① 别去改地址/模型（那是"在线那家"的设置，切回去还要用）；
        //    ② 从本地切回在线时把内存里那几百 MB 放掉（否则白占着）。
        final boolean wasLocal = ChatRouter.isLocal(this);
        final boolean toLocal = ChatProvider.LOCAL_ID.equals(e.id);
        Prefs.setChatProvider(this, e.id);
        if (wasLocal && !toLocal) {
            // ★ unload 会**先发停止信号**再去等锁 ⇒ 即使此刻正在生成也不会卡住 UI
            new Thread(new Runnable() { @Override public void run() {
                try { LocalLlm.unload(); } catch (Throwable ignore) {}
                LocalChat.forget();          // 忘掉"内存里载着谁"
            }}, "dspllm-leave-local").start();
        }
        if (toLocal) {
            final String loc = ModelStore.current(this);
            Toast.makeText(this,
                    "已选定 " + e.name + "\n"
                    + (loc == null
                        ? "★ 还没导入模型：点「\uD83E\uDD16 本地模型」→「\uD83D\uDCE5 导入 .gguf 文件…」"
                          + "（建议 0.5B~3B 的量化版，约 400 MB）"
                        : "本机模型：" + localModelSummary()
                          + "\n首次对话会把它载入内存，要等几秒")
                    + "\n本机推理不联网、不需要 API Key\n"
                    + "★ 本机小模型慢一些、也更笨，复杂问题建议切回在线服务商",
                    Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        // ★ 一并切换地址与模型：避免"选了 A 但地址还是 B"的错配
        if (e.baseUrl != null && e.baseUrl.length() > 0) {
            Prefs.saveBaseUrl(this, e.baseUrl);
        }
        if (e.model != null && e.model.length() > 0) {
            Prefs.setChatModel(this, e.model);
        }
        ApiClient.setBaseUrl(Prefs.getBaseUrl(this));
        // ★ Key 不用动：getApiKey() 会自动返回这一家的
        //   （见 Prefs.getApiKey 的按服务商分槽设计）。
        String keyState = Prefs.hasApiKeyFor(this, e.id)
                ? "已自动使用该家已保存的 Key"
                : "该家还没有 Key，请点「修改 API Key」填写";
        String balState = e.hasBalance()
                ? "余额可查询"
                : "余额气泡只显示用量";
        Toast.makeText(this,
                "已选定 " + e.name + "\n"
                + "地址：" + Prefs.getBaseUrl(this) + "\n"
                + "模型：" + Prefs.getChatModel(this) + "\n"
                + keyState + "\n" + balState,
                Toast.LENGTH_LONG).show();
        showSettings();
    }

    // ==================== 搜索服务（轮次㉘：可插拔）====================

    /** 设置页那一行显示的当前服务名 */
    private String searchProviderLabelNow() {
        String id = Prefs.searchProvider(this);
        SearchProvider p = SearchProvider.byId(id, Prefs.customProviders(this));
        if (p == null) return "必应（免费，内置）";
        return p.label;
    }

    /**
     * 选搜索服务。
     *
     * <p>★ 与"选对话服务商"同一套思路：**先选、按「选定」才落盘**；
     * 需要 Key 而没填时，选定后**直接把填 Key 的框弹出来**（不让用户自己去找入口）。
     */
    private void showSearchProviderDialog() {
        final java.util.List<SearchProvider> all =
                SearchProvider.all(Prefs.customProviders(this));
        final String[] labels = new String[all.size()];
        String curId = Prefs.searchProvider(this);
        int cur = 0;
        for (int i = 0; i < all.size(); i++) {
            SearchProvider p = all.get(i);
            String key = p.needsKey ? Prefs.searchKey(this, p.id) : "";
            String sub = p.needsKey
                    ? (key.length() > 0 ? "Key 已保存" : "还没有 Key")
                    : "不需要 Key";
            if (p.note.length() > 0) sub = sub + " · " + p.note;
            labels[i] = p.label + (p.id.equals(curId) ? "（当前）" : "") + "\n　" + sub;
            if (p.id.equals(curId)) cur = i;
        }
        final int[] chosen = {cur};
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDD0E 选择搜索服务（选定后生效）")
                .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        chosen[0] = which;     // 只记选择，不写盘
                    }
                })
                .setPositiveButton("选定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int idx = chosen[0];
                        if (idx < 0 || idx >= all.size()) return;
                        SearchProvider p = all.get(idx);
                        Prefs.setSearchProvider(MainActivity.this, p.id);
                        Toast.makeText(MainActivity.this,
                                "搜索服务已切换为：" + p.label, Toast.LENGTH_SHORT).show();
                        // 需要 Key 却没有 ⇒ 立刻把填写框弹出来（少一步"找不到入口"）
                        if (p.needsKey && Prefs.searchKey(MainActivity.this, p.id).length() == 0) {
                            showSearchKeyDialog(p);
                        } else {
                            showSettings();
                        }
                    }
                })
                .setNeutralButton("自定义服务…", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showCustomProviderDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 填某家搜索服务的 Key（只存本机；不进 APK、不进日志） */
    private void showSearchKeyDialog(final SearchProvider p) {
        final EditText input = new EditText(this);
        input.setInputType(129);              // 文本 | 密码
        input.setHint(p.needsKey ? "填 " + p.label + " 的 Key" : "这家不需要 Key");
        input.setText(Prefs.searchKey(this, p.id));

        LinearLayout box = new LinearLayout(this);
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        box.setPadding(pad, pad, pad, pad);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        UiFactory.dialog(this)
                .setTitle("\uD83D\uDD11 " + p.label + " 的搜索 Key")
                .setMessage("只保存在本机，按服务分别保存，切换时自动使用。\n\n"
                        + "接口地址：" + p.endpoint + "\n"
                        + (p.keyHint.length() > 0 ? ("去哪拿 Key：" + p.keyHint) : "")
                        + (p.note.length() > 0 ? ("\n说明：" + p.note) : ""))
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setSearchKey(MainActivity.this, p.id,
                                input.getText().toString().trim());
                        Toast.makeText(MainActivity.this, "已保存（" + p.label + "）",
                                Toast.LENGTH_SHORT).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        showSettings();
                    }
                })
                .show();
    }

    /**
     * 自定义搜索服务：**贴一段 JSON 就能接任意搜索 API**。
     *
     * <p>字段说明写在对话框里（{@code {q}} 关键词、{@code {key}} 密钥、各字段路径），
     * 输入框里预填一份可用的模板；保存前**先解析校验**，写错了当场说哪儿错（不留坏配置）。
     */
    private void showCustomProviderDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(6);
        input.setHint("贴一段 JSON（见下方说明）");
        String existing = Prefs.customProviders(this);
        java.util.List<SearchProvider> have = SearchProvider.fromJsonArray(existing);
        input.setText(have.isEmpty() ? SearchProvider.exampleJson()
                : (existing.length() > 4000 ? existing.substring(0, 4000) : existing));

        LinearLayout box = new LinearLayout(this);
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        box.setPadding(pad, pad, pad, pad);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        UiFactory.dialog(this)
                .setTitle("\uD83D\uDD0E 自定义搜索服务")
                .setMessage("一次可以贴一个或多个服务（用 [ ] 套起来就是多个）。字段：\n"
                        + "endpoint 地址（{q}=关键词，{key}=密钥）\n"
                        + "method GET 或 POST；headers 请求头；body 请求体模板\n"
                        + "list 结果数组路径；title / url / snippet / content 各字段路径\n"
                        + "content 可省；路径支持 a.b[0].c 与 a[].b（数组摊平）\n"
                        + "body_endpoint / body_body / body_field 可省：第二跳抓整页正文")
                .setView(box)
                .setPositiveButton("保存并选用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        String text = input.getText().toString().trim();
                        if (text.length() == 0) {
                            showSettings();
                            return;
                        }
                        String asArray = text.startsWith("[") ? text : ("[" + text + "]");
                        java.util.List<SearchProvider> list = SearchProvider.fromJsonArray(asArray);
                        if (list.isEmpty()) {
                            Object o = Json.parseOrNull(asArray);
                            String why = (o == null)
                                    ? "JSON 格式不对（检查引号/逗号是否配对）"
                                    : "缺少必要的 endpoint，或 id 为空";
                            Toast.makeText(MainActivity.this,
                                    "没保存：" + why, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Prefs.setCustomProviders(MainActivity.this, asArray);
                        Prefs.setSearchProvider(MainActivity.this, list.get(0).id);
                        Toast.makeText(MainActivity.this,
                                "已保存并切换为：" + list.get(0).label
                                        + (list.size() > 1 ? ("（共 " + list.size() + " 个）") : ""),
                                Toast.LENGTH_LONG).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        showSettings();
                    }
                })
                .show();
    }

    /** 推理强度标签（含 API 返回的 max 等值） */
    private static String effortLabel(String v) {
        if (v == null || v.length() == 0) return "默认（不指定）";
        String k = v.toLowerCase();
        if (k.equals("none")) return "关闭";
        if (k.equals("minimal")) return "极低";
        if (k.equals("low")) return "低";
        if (k.equals("medium")) return "中";
        if (k.equals("high")) return "高";
        if (k.equals("max")) return "最高";
        return v;
    }

    /** 缓存最近一次 /models 结果，供模型与推理强度两个入口共用 */
    private static java.util.List<ApiClient.ModelInfo> sModels = null;

    private ApiClient.ModelInfo findModel(String id) {
        if (sModels == null) return null;
        for (ApiClient.ModelInfo m : sModels) {
            if (m.id != null && m.id.equals(id)) return m;
        }
        return null;
    }

    /** 从 API 动态拉取模型列表；onDone 回调在 UI 线程执行 */
    private void loadModelsAsync(final boolean thenOpenModelDialog,
                                 final boolean thenOpenEffortDialog) {
        final String key = Prefs.getApiKey(this);
        if (key == null || key.trim().length() == 0) {
            Toast.makeText(this, "请先设置 API Key", Toast.LENGTH_SHORT).show();
            showApiKeyDialog();
            return;
        }
        final ProgressDialog pd = UiFactory.progress(this);
        pd.setMessage("正在获取模型列表…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.List<ApiClient.ModelInfo> list = ApiClient.fetchModels(key);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { pd.dismiss(); } catch (Exception ignore) {}
                        if (list == null || list.isEmpty()) {
                            // ★ 轮次56：把**真实原因**一起摆出来（原来只吞掉异常、说"可能是网络问题"）
                            String why = ApiClient.modelsError();
                            UiFactory.dialog(MainActivity.this)
                                    .setTitle("获取模型失败")
                                    .setMessage("未能从接口读取模型列表。\n\n"
                                            + (why.length() > 0 ? "原因：" + why + "\n\n" : "")
                                            + "可手动填写模型名；也可以用上一页的「🧪 测试连接」看完整报错。")
                                    .setPositiveButton("手动填写", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d, int w) {
                                            showCustomModelDialog();
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                            return;
                        }
                        sModels = list;
                        if (thenOpenModelDialog) showModelDialog();
                        if (thenOpenEffortDialog) showEffortDialog();
                    }
                });
            }
        }).start();
    }

    /** 模型选择（来自 /models 动态列表） */
    private void showModelDialog() {
        if (sModels == null || sModels.isEmpty()) {
            loadModelsAsync(true, false);
            return;
        }
        final String cur = Prefs.getChatModel(this);
        final String[] names = new String[sModels.size() + 1];
        final String[] values = new String[sModels.size() + 1];
        int checked = -1;
        for (int i = 0; i < sModels.size(); i++) {
            ApiClient.ModelInfo m = sModels.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(m.id);
            if (m.name != null && m.name.length() > 0 && !m.name.equals(m.id)) {
                sb.append("（").append(m.name).append("）");
            }
            if (m.contextWindow > 0) {
                sb.append("  ·  ").append(m.contextWindow / 1000).append("k 上下文");
            }
            names[i] = sb.toString();
            values[i] = m.id;
            if (m.id.equals(cur)) checked = i;
        }
        names[sModels.size()] = "自定义…";
        values[sModels.size()] = "";
        UiFactory.dialog(this)
                .setTitle("\uD83E\uDD16 选择模型")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        if (values[which].length() == 0) {
                            dlg.dismiss();
                            showCustomModelDialog();
                        } else {
                            Prefs.setChatModel(MainActivity.this, values[which]);
                            reloadService();
                            Toast.makeText(MainActivity.this, "模型已设为 " + values[which], Toast.LENGTH_SHORT).show();
                            dlg.dismiss();
                            showSettings();
                        }
                    }
                })
                .setPositiveButton("刷新", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sModels = null;
                        loadModelsAsync(true, false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 自定义模型名 */
    private void showCustomModelDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setHint("例如 deepseek-flash");
        input.setText(Prefs.getChatModel(this));
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        LinearLayout bx = new LinearLayout(this);
        bx.setPadding(pad, pad, pad, pad);
        bx.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        UiFactory.dialog(this)
                .setTitle("\u270F\uFE0F 自定义模型名")
                .setMessage("填写该 API 支持的模型名。")
                .setView(bx)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        String v = input.getText().toString().trim();
                        if (v.length() == 0) return;
                        Prefs.setChatModel(MainActivity.this, v);
                        reloadService();
                        Toast.makeText(MainActivity.this, "模型已设为 " + v, Toast.LENGTH_SHORT).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 推理强度：选项来自当前模型的 effort.supported_levels */
    private void showEffortDialog() {
        ApiClient.ModelInfo cur = findModel(Prefs.getChatModel(this));
        final java.util.List<String> levels = new java.util.ArrayList<String>();
        if (cur != null && cur.effortLevels != null && !cur.effortLevels.isEmpty()) {
            levels.addAll(cur.effortLevels);
        }
        if (levels.isEmpty()) {
            // 没有元数据：提示并允许先拉取模型列表
            UiFactory.dialog(this)
                    .setTitle("\uD83E\uDDE0 推理强度")
                    .setMessage("尚未获取到该模型的推理强度选项。\n先联网拉取模型列表（需要该接口支持 /models）。")
                    .setPositiveButton("联网获取", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            loadModelsAsync(false, true);
                        }
                    })
                    .setNeutralButton("手动填写", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            showCustomEffortDialog();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        final String[] labels = new String[levels.size() + 2];
        final String[] opts   = new String[levels.size() + 2];
        final String[] thk    = new String[levels.size() + 2];
        // 第 0 项：默认（不指定）
        labels[0] = "默认" + (cur.defaultEffort != null && cur.defaultEffort.length() > 0
                ? "（接口默认 " + effortLabel(cur.defaultEffort) + "）" : "（不指定）");
        opts[0] = ""; thk[0] = "";
        int checked = 0;
        String curE = Prefs.getReasoningEffort(this);
        for (int i = 0; i < levels.size(); i++) {
            String lv = levels.get(i);
            labels[i + 1] = effortLabel(lv) + "（" + lv + "）";
            opts[i + 1] = lv;
            thk[i + 1] = "enabled";
            if (lv.equals(curE)) checked = i + 1;
        }
        labels[levels.size() + 1] = "手动填写…";
        opts[levels.size() + 1] = "__custom__";
        thk[levels.size() + 1] = "enabled";
        UiFactory.dialog(this)
                .setTitle("\uD83E\uDDE0 推理强度（" + Prefs.getChatModel(this) + "）")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        if ("__custom__".equals(opts[which])) {
                            dlg.dismiss();
                            showCustomEffortDialog();
                            return;
                        }
                        Prefs.setReasoningEffort(MainActivity.this, opts[which]);
                        Prefs.setThinkingType(MainActivity.this, thk[which]);
                        reloadService();
                        Toast.makeText(MainActivity.this,
                                "推理强度：" + labels[which].split("（")[0], Toast.LENGTH_SHORT).show();
                        dlg.dismiss();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomEffortDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setHint("例如 low / high / max");
        input.setText(Prefs.getReasoningEffort(this));
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        LinearLayout bx = new LinearLayout(this);
        bx.setPadding(pad, pad, pad, pad);
        bx.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        UiFactory.dialog(this)
                .setTitle("\u270F\uFE0F 自定义推理强度")
                .setMessage("留空表示不发送该参数。")
                .setView(bx)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        String v = input.getText().toString().trim();
                        Prefs.setReasoningEffort(MainActivity.this, v);
                        Prefs.setThinkingType(MainActivity.this, v.length() == 0 ? "" : "enabled");
                        reloadService();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 主动聊天间隔选择（分钟） */
    private void showProactiveIntervalDialog() {
        final int[] opts = new int[]{5, 10, 20, 30, 60, 120};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] + " 分钟";
        }
        UiFactory.dialog(this)
                .setTitle("\u23F1\uFE0F 桌宠多久主动说一次")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        Prefs.setProactiveMinutes(MainActivity.this, opts[which]);
                        Intent i = new Intent(MainActivity.this, FloatingService.class);
                        i.setAction("com.deepseek.pet.RELOAD");
                        startService(i);
                        Toast.makeText(MainActivity.this, "已设为" + opts[which] + " 分钟", Toast.LENGTH_SHORT).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 秒级档位 */
    private static final int[] SEC_OPTS = {30, 60, 120, 300, 600, 1200, 1800, 3600};
    private static String secLabel(int sec) {
        if (sec < 60) return sec + " 秒";
        if (sec % 60 == 0) return (sec / 60) + " 分钟";
        return (sec / 60) + " 分 " + (sec % 60) + " 秒";
    }
    private static String[] secLabels() {
        String[] l = new String[SEC_OPTS.length];
        for (int i = 0; i < SEC_OPTS.length; i++) l[i] = secLabel(SEC_OPTS[i]);
        return l;
    }

    private void reloadService() {
        Intent i = new Intent(MainActivity.this, FloatingService.class);
        i.setAction("com.deepseek.pet.RELOAD");
        startService(i);
    }

    /** 场景搭话：最小间隔（秒）。 */
    private void showMoodCooldownDialog() {
        final String[] labels = secLabels();
        for (int i = 0; i < labels.length; i++) {
            if (SEC_OPTS[i] == 300) labels[i] += "（推荐）";
        }
        UiFactory.dialog(this)
                .setTitle("\u23F1\uFE0F 场景搭话的最小间隔")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        Prefs.setMoodCooldownSec(MainActivity.this, SEC_OPTS[which]);
                        reloadService();
                        Toast.makeText(MainActivity.this, "场景搭话间隔已设为 " + secLabel(SEC_OPTS[which]), Toast.LENGTH_SHORT).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 久留阈值（秒）。 */
    private void showMoodStayDialog() {
        final String[] labels = secLabels();
        for (int i = 0; i < labels.length; i++) {
            if (SEC_OPTS[i] == 1800) labels[i] += "（推荐）";
        }
        UiFactory.dialog(this)
                .setTitle("\u23F3 同一应用停留多久算「待久了」")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        Prefs.setMoodStaySec(MainActivity.this, SEC_OPTS[which]);
                        reloadService();
                        Toast.makeText(MainActivity.this, "久留阈值已设为 " + secLabel(SEC_OPTS[which]), Toast.LENGTH_SHORT).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 互动对话冷却时间。
     *   -1 = 纯本地模式：只念本地台词，绝不调用 API（不花一分钱）
     *    0 = 关闭冷却（每次互动都调 API）
     *   >0 = 冷却期内只蹦跳、不发问
     */
    private void showInteractCdDialog() {
        final long[] opts = new long[]{Prefs.INTERACT_LOCAL_ONLY,
                0, 500, 1000, 2000, 3000, 5000, 10000, 30000};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = cdLabel(opts[i]) + (opts[i] == 2000 ? "（默认）" : "");
        }
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDC46 互动对话冷却")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        Prefs.setInteractCdMs(MainActivity.this, opts[which]);
                        Intent i = new Intent(MainActivity.this, FloatingService.class);
                        i.setAction("com.deepseek.pet.RELOAD");
                        startService(i);
                        Toast.makeText(MainActivity.this,
                                "冷却已设为 " + cdLabel(opts[which]), Toast.LENGTH_SHORT).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String cdLabel(long ms) {
        if (ms == Prefs.INTERACT_LOCAL_ONLY) return "纯本地（互动只念本地台词）";
        if (ms <= 0) return "关闭（每次互动都说话）";
        if (ms < 1000) return ms + " 毫秒";
        long sec = ms / 1000;
        if (sec < 60) return sec + " 秒";
        return (sec / 60) + " 分钟";
    }

    /** 计费时段说明文案（保留：仍有地方以纯文本形式引用） */
    private static String pricingHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("现在是").append(Pricing.label()).append("（").append(Pricing.tag()).append("）")
          .append("，").append(Pricing.priceHint()).append("。\n");
        sb.append(Pricing.nextSwitchHint()).append("\n\n");
        sb.append("高峰时段（单价 ×2）：\n");
        sb.append("  周一至周五 09:00-12:00、14:00-18:00\n");
        sb.append("  不含中国法定节假日\n\n");
        sb.append("空闲时段（单价 ×0.5）：\n");
        sb.append("  其余时间、周末、法定节假日全天\n\n");
        sb.append("余额气泡里的「").append(Pricing.tag()).append("」（与余额之间留了间隔）就是这个意思：\n");
        sb.append("  红色「峰」= 高峰，绿色「谷」= 空闲。\n\n");
        sb.append("命中缓存的输入价格便宜 25~50 倍，\n固定聊同一话题能显著降低花费。");
        return sb.toString();
    }

    /**
     * 计费时段说明 —— 原型 {@code billingDialog} 的结构化版。
     *
     * <p>改造前这番话是一坨 {@code setMessage()} 纯文本，峰/谷、时段、倍数
     * 全都挤在一起，用户很难快速对上号。原型把它做成「表格 + 注释块」：
     * 表格三列（时段/单价/适用时间），当前时段用彩色标签标出来。
     *
     * <p>数据不是抄原型的 —— 全部实时取自 {@link Pricing}，所以「现在是峰
     * 还是谷」「距离切换还有多久」永远是真的。原型的文案只是排版参考。
     */
    private void showBillingDialog() {
        final Tokens.Set T = uiT();
        final float d = getResources().getDisplayMetrics().density;
        final boolean peak = Pricing.isPeak();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (d * 20);
        root.setPadding(p, (int) (d * 16), p, (int) (d * 8));

        // ---- 顶部结论：现在是什么时段、差多少、还有多久切换 ----
        TextView lead = new TextView(this);
        lead.setTextSize(15);
        lead.setTextColor(T.text2);
        lead.setLineSpacing(0, 1.25f);
        lead.setText("现在是 " + Pricing.label() + "，单价是平峰的 "
                + (peak ? "2 倍" : "一半") + "。\n" + Pricing.nextSwitchHint() + "。");
        root.addView(lead);

        // ---- 表格：时段 / 单价 / 适用时间 ----
        root.addView(billingTable(T, peak, d));

        // ---- 注释块：气泡里峰谷字的含义 + 缓存价差 ----
        TextView note = new TextView(this);
        note.setTextSize(13);
        note.setTextColor(T.text3);
        note.setLineSpacing(0, 1.3f);
        note.setPadding((int) (d * 12), (int) (d * 12), (int) (d * 12), (int) (d * 12));
        // 原型这一块是 `background:var(--surface-sunken)`，**不带** deboss
        // （prototype.html 里唯一显式声明 surface-sunken 底的元素）。
        // 所以这里用纯色填充，而不是 UiKit.sunken —— 后者是"凹陷浮雕"，
        // 底色调成 surface 后就和卡片糊在一起了。两者是不同语义：
        // 这里要的是"一块较深的信息底"，不是"一个坑"。
        note.setBackground(UiKit.solidFill(this, T, T.surfaceSunken, Tokens.R_CHIP));
        note.setText("余额气泡里的「峰」与余额之间留了间隔：红色「峰」= 高峰，"
                + "绿色「谷」= 空闲。\n命中缓存的输入价格便宜 25~50 倍，"
                + "固定聊同一话题能显著降低花费。");
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = (int) (d * 12);
        root.addView(note, nlp);

        AlertDialog dlg = UiFactory.dialog(this)
                .setTitle("\u26A1 计费时段说明")
                .setView(root)
                .setPositiveButton("知道了", null)
                .create();
        final AlertDialog ref = dlg;
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface x) {
                Button ok = ref.getButton(AlertDialog.BUTTON_POSITIVE);
                if (ok != null) {
                    ok.setTextColor(T.accentInk);
                    ok.setAllCaps(false);
                }
            }
        });
        dlg.show();
    }

    /**
     * 峰谷价目表。
     *
     * <p>用 {@code LinearLayout} 手工排三列而不是 {@code TableLayout} ——
     * 因为要让「适用时间」那列自由换行，而 TableLayout 的列宽分配在这种
     * 长中文串上会挤压第一列。第一列固定宽度、第三列 weight=1 吸收剩余。
     */
    private View billingTable(Tokens.Set T, boolean peak, float d) {
        LinearLayout tbl = new LinearLayout(this);
        tbl.setOrientation(LinearLayout.VERTICAL);
        tbl.setPadding((int) (d * 12), (int) (d * 8), (int) (d * 12), (int) (d * 8));
        tbl.setBackground(UiKit.card(this, T, Tokens.R_CHIP));

        // 表头
        tbl.addView(tableRow(T, d, "时段", "单价", "适用时间", -1, true));

        String peakTime = "周一至周五 09:00–12:00、14:00–18:00"
                + "（不含法定节假日）";
        String offTime = "其余时间、周末、法定节假日全天";

        // 当前处于哪个时段，就把那一行标成彩色，另一行用次级色。
        // 用 Pricing.colorPeak/colorIdle 按主题取色 —— 直接在深色底上用
        // 浅色主题那对红/绿只有 3.2:1 左右，达不到 AA（实算确认）。
        final boolean dark = Tokens.theme(this) == Tokens.THEME_DARK;
        tbl.addView(tableRow(T, d, "峰", "×2", peakTime,
                peak ? Pricing.colorPeak(dark) : -1, false));
        tbl.addView(tableRow(T, d, "谷", "×0.5", offTime,
                peak ? -1 : Pricing.colorIdle(dark), false));
        return tbl;
    }

    /**
     * 表格一行。
     *
     * <p><b>Java 陷阱（踩过）：</b>判断"有没有标记色"必须用 {@code != NO_TAG}，
     * 不能用 {@code >= 0}。ARGB 颜色常量（如 {@code 0xFFD32F2F}）最高位是 1，
     * 作为 {@code int} 是**负数**，{@code >= 0} 会恒为 false —— 第一版就是这样，
     * 截图确认「峰」字根本没变红。
     */
    private static final int NO_TAG = -1;

    private LinearLayout tableRow(Tokens.Set T, float d, String c1, String c2,
                                  String c3, int tagColor, boolean header) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (d * 6), 0, (int) (d * 6));

        TextView t1 = new TextView(this);
        t1.setText(c1);
        t1.setTextSize(header ? 12 : 13);
        t1.setTextColor(header ? T.text3
                : (tagColor != NO_TAG ? tagColor : T.text2));
        t1.setTypeface(t1.getTypeface(), header
                ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        row.addView(t1, new LinearLayout.LayoutParams((int) (d * 40),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView t2 = new TextView(this);
        t2.setText(c2);
        t2.setTextSize(header ? 12 : 13);
        t2.setTextColor(header ? T.text3 : T.text1);
        row.addView(t2, new LinearLayout.LayoutParams((int) (d * 48),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView t3 = new TextView(this);
        t3.setText(c3);
        t3.setTextSize(header ? 12 : 13);
        t3.setTextColor(header ? T.text3 : T.text2);
        t3.setLineSpacing(0, 1.2f);
        row.addView(t3, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** 碰过桌宠后多久内不主动开口（秒）。0 = 不设门槛。 */
    private void showQuietDialog() {
        final int[] opts = new int[]{0, 30, 60, 120, 300, 600};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = (opts[i] == 0 ? "不设门槛（随时可主动）" : secLabel(opts[i]))
                    + (opts[i] == 300 ? "（推荐）" : "");
        }
        UiFactory.dialog(this)
                .setTitle("\uD83E\uDD10 戳过桌宠后多久内不主动开口")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        Prefs.setQuietSec(MainActivity.this, opts[which]);
                        reloadService();
                        Toast.makeText(MainActivity.this, opts[which] == 0 ? "已设为不设门槛" : ("已设为 " + secLabel(opts[which])), Toast.LENGTH_SHORT).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 若未授予「使用情况访问」权限，跳到系统设置页引导授权 */
    private void ensureUsageAccess() {
        if (MoodWatcher.hasPermission(this)) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "请在列表里找到「DeepSeek桌宠」并允许「使用情况访问」", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try {
                Intent i2 = new Intent(android.provider.Settings.ACTION_SETTINGS);
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i2);
                Toast.makeText(this, "请手动进入：设置 → 应用 → 特殊权限 → 使用情况访问", Toast.LENGTH_LONG).show();
            } catch (Exception ignore) {}
        }
    }

    /** 性格预设管理 */
    private void showPersonaDialog() {
        final List<String[]> presets = Prefs.getPresets(this);
        final int cur = Prefs.getPresetIndex(this);
        final int n = presets.size();
        // ★ 动作项也放进列表里，而不是用按钮 —— 原来有**两个 setNeutralButton**，
        //   后一个（"从聊天记录生成"）把前一个（"编辑当前"）**整个覆盖掉**，
        //   于是"编辑当前预设"这个入口一直点不到（用户 2026-10-07 反馈）。
        //   AlertDialog 只有 3 个按钮位，动作一多就该进列表。
        String[] items = new String[n + 4];
        for (int i = 0; i < n; i++) {
            items[i] = (i == cur ? "\u2705" : "\u3000") + presets.get(i)[0];
        }
        items[n] = "\u270F\uFE0F 编辑当前预设（" + Prefs.currentPresetName(this) + "）\u2026";
        items[n + 1] = "\u2795 新建性格预设\u2026";
        items[n + 2] = "\u2728 从聊天记录生成\u2026";
        items[n + 3] = "\uD83D\uDCE5 导入 skill 文件\u2026";
        UiFactory.dialog(this)
                .setTitle("\uD83C\uDFAD 性格预设（当前：" + Prefs.currentPresetName(this) + "）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        if (which == n) {
                            List<String[]> ps = Prefs.getPresets(MainActivity.this);
                            int idx = Prefs.getPresetIndex(MainActivity.this);
                            if (idx >= 0 && idx < ps.size()) {
                                editPreset(idx, ps.get(idx)[0], ps.get(idx)[1]);
                            }
                        } else if (which == n + 1) {
                            editPreset(n, "新性格", "");
                            Prefs.setPresetIndex(MainActivity.this, n);
                            Toast.makeText(MainActivity.this, "已切换到「新性格」", Toast.LENGTH_SHORT).show();
                            showSettings();
                        } else if (which == n + 2) {
                            pickChatLogFile();
                        } else if (which == n + 3) {
                            pickSkillFile();
                        } else {
                            Prefs.setPresetIndex(MainActivity.this, which);
                            showSettings();
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 新建/编辑一个性格预设 */
    private void editPreset(final int index, String name, String content) {
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        final EditText nameEt = new EditText(this);
        nameEt.setHint("预设名称，如「高冷学霸」");
        nameEt.setText(name == null ? "" : name);
        nameEt.setTextSize(15);
        box.addView(nameEt);

        final EditText contentEt = new EditText(this);
        contentEt.setHint("性格描述（作为 AI 系统提示词）");
        contentEt.setText(content == null ? "" : content);
        contentEt.setTextSize(14);
        contentEt.setMinLines(4);
        contentEt.setMaxLines(10);
        contentEt.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = (int) (d * 8);
        box.addView(contentEt, clp);

        UiFactory.dialog(this)
                .setTitle(index >= Prefs.getPresets(this).size() ? "\u2795 新建性格预设" : "\u270F\uFE0F 编辑性格预设")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        String n = nameEt.getText().toString().trim();
                        String c = contentEt.getText().toString().trim();
                        if (n.length() == 0) n = "未命名";
                        List<String[]> ps = Prefs.getPresets(MainActivity.this);
                        if (index < ps.size()) {
                            ps.set(index, new String[]{n, c});
                        } else {
                            ps.add(new String[]{n, c});
                        }
                        Prefs.savePresets(MainActivity.this, ps);
                        Prefs.setPresetIndex(MainActivity.this, index);
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("\uD83D\uDDD1 删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        List<String[]> ps = Prefs.getPresets(MainActivity.this);
                        if (index < ps.size()) {
                            ps.remove(index);
                        }
                        if (ps.isEmpty()) {
                            ps = new java.util.ArrayList<>();
                            for (String[] p : Prefs.builtinPresets()) ps.add(p);
                        }
                        Prefs.savePresets(MainActivity.this, ps);
                        Prefs.setPresetIndex(MainActivity.this, 0);
                        Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                        showSettings();
                    }
                })
                .show();
    }

    /** 确保悬浮窗权限后再执行动作 */
    private void ensureOverlayThen(final Runnable action) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            pendingAction = action;
            Toast.makeText(this, "若桌宠未显示，请授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignore) {}
        } else {
            action.run();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingAction != null && Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
            Runnable r = pendingAction;
            pendingAction = null;
            r.run();
        }
        // 进设置页就收起桌宠：它是常驻悬浮窗，否则会盖住本页自己的按钮
        // （实测「外观与显示 / 对话与人格 / 记忆与省 TOKEN」三行被压住点不到）。
        // 用 startService 而不是绑定：不改变服务生命周期，只递一个动作。
        hidePetForApp();
        // ★ 用户要求：「工作模式就是工作模式，只能手动切换」。
        //   从桌面/最近任务回来时，如果模式标记还是 work，直接回工作模式页面，
        //   不要停在这一页（那就是"回来变成陪伴模式"的观感）。
        //   退出工作模式时 onDestroy 会把标记写回陪伴，所以手动退出不会进来这里。
        try {
            if (com.deepseek.pet.dsh.DshBridge.workEnabled(this)
                    && com.deepseek.pet.dsh.DshBridge.isWorkMode(this)) {
                enteringWorkMode = true;
                Intent wi = new Intent(this, DshHarnessActivity.class);
                wi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(wi);
            }
        } catch (Throwable ignore) {
        }
        // ★ 性能优化：回到前台时恢复视频背景播放（onPause/onStop 里暂停了）
        setVideoPlaybackPaused(false);
        // 已经回到本页 = 之前那次 startActivityForResult 的"等结果"结束了。
        // 必须在这里清掉：否则第一次跳相册之后它就永远是 true，
        // 之后离开 App 也不会恢复桌宠（onPause 里会一直误以为还在等结果）。
        awaitingResult = false;
        // ★ 游戏助手：onPause 里停掉了采样，回到本页必须重启 ——
        //   授权 Shizuku 时弹的权限框也会走 onPause，不重启就会一直显示"未就绪"。
        if (settingsSection == 7 && Prefs.gameAssistant(this)
                && gameFpsTv != null) {
            startGameFpsSampling(gameFpsTv, gameAppTv);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ★ 性能优化：界面不在前台时停掉视频背景播放。
        //
        // 实测（build/video_cost.py）：视频背景开着时 RenderThread 占 19~23%，
        // 关掉是 0.0% —— 它就是"占用高、发热高"的主因。熄屏后进程仍占 12.5%。
        //
        // ★ 为什么放在 onPause 而不是 onStop：
        //   实测按 HOME 后 **onStop 并不触发**（dumpsys 显示 mLastPausedActivity，
        //   窗口仍在），只到 onPause。放在 onStop 里等于没生效 ——
        //   我第一次就是这么写的，实测后台仍是 20.1%，才发现这一点。
        //   onPause 覆盖面更广：HOME、切到别的 App、熄屏都会走到。
        setVideoPlaybackPaused(true);
        // 只为"我们藏起来的"负责：只有真藏过才恢复，避免把用户主动退出的桌宠拉回来。
        //
        // 注意这里**不能**加 isFinishing() 判断 —— 那是反的。按返回键退出本页正是
        // finish（本类没有 onBackPressed 覆写），若因 finishing 而跳过恢复，
        // 桌宠就永远躲在设置页后面再也不出来了。正在销毁的界面恰恰最需要把桌宠还回去。
        // 同理 afterConfigChange 才跳过：旋转后 Activity 会重建，onResume 会重新收起，
        // 中间恢复一次只会闪一下。
        if (petHiddenByApp && !awaitingResult && !isChangingConfigurations()) {
            restorePetForApp();
        }
        // ★ 桌宠运行时，后台不留 app 任务卡片（见 removeTaskFromRecents）。
        // 放在 onPause 而不是 onStop：本工程实测按 HOME **onStop 不触发**
        // （见上面视频暂停那段注释），放 onStop 等于没生效。
        if (!enteringWorkMode && !com.deepseek.pet.dsh.DshBridge.isWorkMode(this)) {
            removeTaskFromRecents();
        }
        enteringWorkMode = false;
        // 本页不再需要读数：只注销自己 + 释放自己那一份引用。
        // ★ 不能调 GameFpsMonitor.stop() —— 悬浮窗（FloatingService）可能也在用，
        //   那样会把桌宠上的挂牌一起停掉。
        stopGameFpsSampling();
    }

    /**
     * 把本 app 的任务从「最近任务」里移除 —— 用户需求：桌宠运行时后台不显示 app 窗口。
     *
     * <h3>为什么在这里做，而不是在桌宠启动时</h3>
     * 桌宠是在设置页里被启动的，那一刻用户正常停在页面上，
     * 当场把任务移除会把人踢出设置页。等到 {@code onPause}（用户真的离开 App）
     * 再移除，用户完全无感 —— 下次想进设置，点桌面图标会重新拉起本页。
     *
     * <h3>为什么不用 {@code excludeFromRecents} 就够了</h3>
     * 清单属性只在任务**首次创建**时被系统读取；本流程里任务先于桌宠存在，
     * 卡片会留在 recents 里（API 30+ 已由 FloatingService 运行期改属性兜住，
     * 这里覆盖 30 以下的老机器）。
     *
     * <p>{@code finishAndRemoveTask()} 是 API 21+，minSdk 24 可放心用。
     * 它在 onPause 里调用：Activity 已不可见，finish 不会造成视觉跳动。
     */
    private void removeTaskFromRecents() {
        try {
            if (!Prefs.petShouldRun(this)) return;          // 桌宠没在跑，不动任务
            if (awaitingResult) return;                     // 正等选择器结果，别拆任务
            if (isChangingConfigurations()) return;         // 旋转重建中，别拆
            if (isFinishing()) return;                      // 已在退场，无需重复
            finishAndRemoveTask();
        } catch (Throwable t) {
            android.util.Log.w("DSPRecents", "移除任务失败（不影响桌宠）: " + t);
        }
    }

    // ==================== 返回导航 ====================

    /**
     * 返回上一级：二级页 → 所属板块首页 → 第一个板块 → 退出。
     *
     * <h3>★ 为什么必须覆写（用户报的 bug）</h3>
     * 本类原先**没有** {@code onBackPressed} 覆写，所以系统返回手势走默认行为
     * —— 直接 {@code finish()} 退到桌面。而设置页有**两层**层级
     * （板块首页 → 二级页），用户按返回时想的是"回上一页"，
     * 结果整个 App 没了。
     *
     * <p>现在的层级规则（与页面上那个「返回」按钮**完全一致**，见
     * {@code showSettings} 里 back 按钮的实现）：
     * <pre>
     *   二级页（settingsSection ≥ 0）  → 回所属板块首页
     *   板块首页（settingsSection &lt; 0）→ 不是第一个板块则回第一个板块
     *                                   已是第一个板块 → 交给系统（退出）
     * </pre>
     *
     * <p><b>为什么首页还要"回第一个板块"而不是直接退出</b>：
     * 用户可能从「系统」标签页点进来的，此时返回如果直接退出 App，
     * 会显得很突兀 —— 先回到「形象」是更自然的"往上退一层"。
     * 但只在**非**第一个板块时这么做，避免"怎么按都退不出去"。
     */
    @Override
    public void onBackPressed() {
        if (navigateBack()) return;
        // 已经在最外层：交给系统处理（退出 App）
        super.onBackPressed();
    }

    /**
     * 往上一级导航。
     *
     * <p>抽成独立方法是因为它有**三个**调用方，必须行为完全一致：
     * <ol>
     *   <li>{@link #onBackPressed()}（老版本 / 未启用预测式返回时）</li>
     *   <li>API 33+ 的 {@code OnBackInvokedCallback}（见 {@link BackApi33}）</li>
     *   <li>左边缘左滑手势（见 {@link #handleEdgeBackSwipe}）</li>
     * </ol>
     * 三处各写一份的话，改了一处漏改另两处就会出现"有的返回方式能退、
     * 有的直接退出 App"这种最难复现的 bug。
     *
     * @return true = 已在 App 内部处理；false = 已在最外层，应由系统退出
     */
    private boolean navigateBack() {
        // 规则本体在 BackNav（纯 Java，可离线穷举断言）——
        // 三个返回入口共用同一份判断，避免"某种返回方式直接退出 App"。
        BackNav.Action a = BackNav.back(
                settingsSection, settingsTab,
                settingsSection >= 0 ? tabOfSection(settingsSection) : settingsTab,
                TAB_TITLES.length);
        if (a.exit) return false;
        settingsTab = a.tab;
        settingsSection = a.section;
        slideForward = false;      // 退一层：从左侧推入
        showSettings();
        return true;
    }

    /**
     * 左滑返回上一级（边缘到边缘的手势）。
     *
     * <p>用户要求「加入左滑返回上一页的手势」。这里**不**做成"整屏任意左滑"——
     * 那样会和已有的"横向划页面切板块"手势打架（见 root 的
     * {@code onInterceptTouchEvent}，横向滑动是用来切 形象/陪伴/系统 的）。
     *
     * <p>所以按系统惯例限定在**屏幕左边缘**（约 20dp 内）起手：
     * 这是 Android 与 iOS 通用的返回手势区域，用户有肌肉记忆，
     * 而且不会误触发切板块。
     *
     * @return true 表示已消费这次滑动（调用方不要再做别的处理）
     */
    private boolean handleEdgeBackSwipe(android.view.MotionEvent ev) {
        final int edge = (int) (getResources().getDisplayMetrics().density * 20);
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                // 只有在"还能往回退"时才接管边缘滑动；
                // 已经在最外层就让事件正常走（否则最外层会变得划不动）
                backSwipeTracking = canGoBack()
                        && ev.getX() <= edge;
                backSwipeDone = false;
                backSwipeStartX = ev.getX();
                backSwipeStartY = ev.getY();
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (!backSwipeTracking || backSwipeDone) return false;
                {
                    float dx = ev.getX() - backSwipeStartX;
                    float dy = Math.abs(ev.getY() - backSwipeStartY);
                    // 主要是横向位移才认，避免误伤竖滑
                    float need = Math.max(edge * 3f, 96f);
                    if (dx > need && dy < dx * 0.7f) {
                        backSwipeDone = true;
                        backSwipeTracking = false;
                        onBackPressed();
                        return true;
                    }
                }
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                backSwipeTracking = false;
                backSwipeDone = false;
                break;
            default:
                break;
        }
        return false;
    }

    /** 当前是否还能"返回上一级"（在层级里不是最外层） */
    private boolean canGoBack() {
        return BackNav.canGoBack(settingsSection, settingsTab);
    }

    /**
     * API 33+ 的预测式返回（跟随系统手势）注册。
     *
     * <h3>★ 为什么单独一个嵌套类、并且只在 33+ 才加载</h3>
     * {@code OnBackInvokedCallback} 是 API 33 才有的类型。若直接写在
     * {@code onCreate} 里，在 33 以下的机器上**类加载阶段**就会
     * {@code NoClassDefFoundError} —— 不是运行时才报，而是整个类加载失败。
     * 所以必须：
     * <ol>
     *   <li>用独立的嵌套类承载 33+ 的 API 调用（触发类加载隔离）</li>
     *   <li>外面套 {@code Build.VERSION.SDK_INT >= 33} 判断</li>
     * </ol>
     *
     * <h3>★ 为什么不改用 AndroidX 的 OnBackPressedDispatcher</h3>
     * 本工程是**纯 framework Activity**（{@code extends Activity}，无 AndroidX），
     * 引入 AndroidX 要带 aar 依赖 —— 而这个工程是手工 javac + aapt2、
     * 没有依赖管理器（见 STATE.md）。为一个返回手势引入整套 AndroidX 不划算。
     *
     * <p>同时**保留** {@code onBackPressed()} 覆写：两条路径都指向
     * {@link #navigateBack()}，所以无论系统走哪条，行为完全一致。
     * 这样"系统手势跟随"与"老版本兼容"同时成立。
     */
    private static class BackApi33 {
        static void register(final MainActivity a) {
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override public void onBackInvoked() {
                            // 与 onBackPressed 走同一个实现 —— 见 navigateBack 的说明
                            if (!a.navigateBack()) {
                                a.finish();
                            }
                        }
                    });
        }
    }

    /**
     * API 34+ 的**预测式返回动画**（用户要求「加入预见式返回动画」）。
     *
     * <h3>它做什么</h3>
     * 手指从屏幕边缘往里拖时，系统会持续回调
     * {@code onBackProgressed(BackEvent)}，带来 0→1 的手势进度。
     * 我们据此把当前页面**实时平移** —— 于是用户在松手前
     * 就能看到"页面要被划走了"，松手即完成返回、中途取消则弹回。
     *
     * <p><b>纯平移</b>：用户要求「动画采用平移不用翻页了用平移」，
     * 所以不缩放、不淡入，只动 {@code translationX}。
     *
     * <h3>★ 为什么是 34 而不是 33</h3>
     * {@code OnBackAnimationCallback} 这个类 API 33 就有（本工程
     * android.jar 里能编译过），但系统**真正开始调用**进度回调是
     * <b>API 34</b>。33 上注册它只会退化成普通 {@code OnBackInvokedCallback}
     * —— 即"能返回、但没有跟随动画"。所以动画部分判断 34：
     * 在 33 上不注册（走 33 那条纯回调路径），避免"注册了却收不到进度"
     * 这种看不出问题的哑状态。
     *
     * <h3>★ 几何必须与 {@code UiFactory.pageSlide} 一致</h3>
     * 手势松手后，系统会走正常返回 → 新页面用 {@code pageSlide} 平移进来。
     * 如果这里的跟随几何用别的数值，松手瞬间画面会**跳一下**
     * （手势预览的终态 ≠ 动画的起态）。
     * 所以 {@link #MAX_SHIFT} 直接引用 {@link UiFactory#PAGE_SLIDE_FRACTION}，
     * 单一来源、不可能漂移。
     */
    private static class BackApi34 {
        /**
         * 手势拖到 100% 时页面平移多少（屏宽比例）。
         *
         * <p>★ 必须等于 {@link UiFactory#PAGE_SLIDE_FRACTION}：
         * 松手后新页从"离屏恰好 1 个屏宽"处平移进来，而跟随手势的终点
         * 也是 1 个屏宽 —— 两者一致，松手瞬间画面才不会跳。
         * 断言 [5] 会检查这两个值相等。
         */
        static final float MAX_SHIFT = UiFactory.PAGE_SLIDE_FRACTION;

        /**
         * 手势拖到 100% 时是否还缩放。
         *
         * <p>★ 用户要求纯平移，所以是 <b>1.0</b>（不缩放）。
         * 保留这个常量而不是硬编码，是为了让"没有缩放"这件事
         * 在代码里**可读、可断言** —— 否则以后有人想加回缩放，
         * 找不到该改哪里。
         */
        static final float MIN_SCALE = 1f;

        static void register(final MainActivity a) {
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackAnimationCallback() {
                        /** 手势是否已开始（决定要不要把页面归位） */
                        private boolean active = false;

                        @Override public void onBackStarted(
                                android.window.BackEvent e) {
                            active = true;
                            a.beginPredictiveBack();
                        }

                        @Override public void onBackProgressed(
                                android.window.BackEvent e) {
                            if (!active) return;
                            float p = e.getProgress();
                            if (p < 0f) p = 0f;
                            if (p > 1f) p = 1f;
                            a.applyPredictiveBack(p);
                        }

                        @Override public void onBackCancelled() {
                            // 用户中途松手/取消：把页面**弹回原位**，
                            // 而不是硬切 —— 硬切会让人以为"返回已经发生了"。
                            active = false;
                            a.cancelPredictiveBack();
                        }

                        @Override public void onBackInvoked() {
                            active = false;
                            a.finishPredictiveBack();
                            if (!a.navigateBack()) {
                                a.finish();
                            }
                        }
                    });
        }
    }

    /**
     * 注册预测式返回（仅 API 33+ 生效；低版本静默跳过）。
     *
     * <p>34+ 走**带动画**的那条，33 走纯回调那条。
     */
    private void registerPredictiveBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                BackApi34.register(this);
            } else {
                BackApi33.register(this);
            }
        } catch (Throwable t) {
            // 注册失败不该影响 App 启动 —— 退回 onBackPressed 那条路
            android.util.Log.w("DSPBack", "预测式返回注册失败，走 onBackPressed: " + t);
        }
    }

    // ==================== 预测式返回动画 ====================

    /** 正在做跟随手势的视图（null = 没在做） */
    private View backAnimView;
    /** 上次打点的整数百分点（0..10），避免一次手势刷 80+ 行日志 */
    private int lastBackLogPct = -1;

    /**
     * 手势开始：记下要动的视图。
     *
     * <p>动的是**内容根**（{@code contentRoot}）而不是整个 Activity 窗口 ——
     * 窗口位移由系统自己处理（它负责把 Activity 整体挪开、露出下面一层），
     * 我们只需要让自己的内容跟着缩，两层叠加就是标准的预测式返回观感。
     * 若连窗口一起动，会和系统打架、出现"移动了两倍距离"。
     */
    private void beginPredictiveBack() {
        backAnimView = contentRoot;
        if (backAnimView == null) return;
        // 关掉父容器裁剪，否则内容平移时会被切掉边缘
        // （与 pageSlide 里同样的处理）
        View p = backAnimView;
        if (p.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) p.getParent()).setClipChildren(false);
            ((android.view.ViewGroup) p.getParent()).setClipToPadding(false);
        }
        // 手势期间不要有动画残留，否则会跟手指抢
        backAnimView.animate().cancel();
    }

    /**
     * 手势进行中：按进度实时平移。
     *
     * <p>★ 用户要求「动画采用平移不用翻页了用平移」——
     * 所以这里**只动 translationX**：不缩放、不淡入。
     * 缩放（0.94）+ 淡入（alpha）叠加起来就是"翻页/卡片推入"的观感，
     * 去掉后是干净的平移，与 {@link UiFactory#pageSlide} 的转场同源。
     *
     * @param progress 0=未开始拖动，1=已达返回阈值
     */
    private void applyPredictiveBack(float progress) {
        // ★ 打点：这是"预测式返回动画是否真的在跑"的**权威证据**。
        //   像素对比分不清"我的代码 vs 系统自带的窗口动画" ——
        //   实测：把我的动画改成 no-op 后仍有 ~48px 位移（系统的），
        //   所以必须直接证明回调收到了 progress。
        //
        //   但**不能每次都打**：一次手势会回调 80+ 次，
        //   逐次打点会把 logcat 刷满、也会拖慢手势帧率。
        //   只在整数百分点变化时打一次，足够证明链路、又不刷屏。
        int pct = (int) (progress * 10);
        if (pct != lastBackLogPct) {
            lastBackLogPct = pct;
            android.util.Log.i("DSPBack", "progress=" + progress
                    + " view=" + (backAnimView != null));
        }
        if (backAnimView == null) return;
        float screenW = getResources().getDisplayMetrics().widthPixels;
        // 往**左**退（右滑手势 = 返回上一页），所以位移是负的
        backAnimView.setTranslationX(
                -progress * screenW * BackApi34.MAX_SHIFT);
        // 纯平移：把缩放/透明度显式钉在 1，
        // 避免上一次动画（或旧版本遗留）的残留值让画面变形
        backAnimView.setScaleX(BackApi34.MIN_SCALE);
        backAnimView.setScaleY(BackApi34.MIN_SCALE);
        backAnimView.setAlpha(1f);
    }

    /** 手势取消：平移回原位（纯平移，不涉及缩放/透明度） */
    private void cancelPredictiveBack() {
        if (backAnimView == null) return;
        backAnimView.animate()
                .translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
        backAnimView = null;
    }

    /**
     * 手势完成（已确定返回）。
     *
     * <p>这里**不做**收尾动画就直接重建页面：紧接着的
     * {@code navigateBack()} 会调 {@code pageSlide} 播新页入场，
     * 而跟随手势已经把旧页平移到终点位置 —— 两者接起来就是连续的。
     * 若在这里再补一段"旧页继续滑走"，会和 pageSlide 叠成两次动画。
     *
     * <p>但必须**清掉内联的位移**，否则新页会带着旧页的变换进入。
     */
    private void finishPredictiveBack() {
        if (backAnimView == null) return;
        backAnimView.animate().cancel();
        backAnimView.setTranslationX(0f);
        backAnimView.setScaleX(1f);
        backAnimView.setScaleY(1f);
        backAnimView.setAlpha(1f);
        backAnimView = null;
    }

    private boolean backSwipeTracking = false;
    private boolean backSwipeDone = false;
    private float backSwipeStartX = 0f;
    private float backSwipeStartY = 0f;

    /**
     * 暂停/恢复视频背景播放；无视频背景时是安全的空操作。
     *
     * <p>会自动带上"用户是否关闭了自动调节"的判断 ——
     * 关掉自动调节的用户要的是"永远别省"，这里必须尊重。
     */
    private void setVideoPlaybackPaused(boolean paused) {
        // 诊断日志：这个功能的 bug 全在"到底有没有生效"上，
        // 而设备上没法打断点。打一行日志就能直接判断，
        // 不必再从 CPU 数字反推（见 STATE.md 第八轮的方法论教训）。
        android.util.Log.i("DSPPerf", "setVideoPlaybackPaused(" + paused
                + ") view=" + (videoBgView == null ? "null" : "ok"));
        if (videoBgView == null) return;
        boolean auto = true;
        try { auto = Prefs.perfAuto(this); } catch (Throwable ignore) {}
        videoBgView.setPlaybackPaused(paused && auto);
    }

    /** 记录"界面暂时离开是为了等一个 Activity 结果"，避免选择器期间误恢复桌宠 */
    private boolean awaitingResult = false;
    /** 记录桌宠是不是**被本页**藏起来的（是才由本页负责恢复） */
    private boolean petHiddenByApp = false;

    /**
     * 游戏助手 · Shizuku 状态监听器。
     *
     * <p>★ 必须存成字段并在重建时**先移除旧的**：设置页每次 showSettings()
     * 都会新建 TextView 并注册一个新监听器，旧的不摘掉就会越积越多，
     * 而且会往已经脱离视图树的控件上写（表现为读数不再刷新 + Activity 泄漏）。
     */
    private ShizukuBridge.Listener shizukuUi = null;

    /**
     * 游戏助手那两行读数控件（存成字段，供 {@link #onResume} 重启采样时用）。
     *
     * <h3>★ 为什么必须存下来（真机实测的 bug）</h3>
     * 采样原先只在"构建游戏助手页"时启动。但**授权时 Shizuku 会弹权限框**，
     * 那是另一个 Activity ⇒ 我们走 {@code onPause} ⇒ {@code GameFpsMonitor.stop()}。
     * 授权完成后回到本页，**没有任何地方重启采样**，读数就永远冻在
     * 「实时帧率：—（Shizuku 未就绪）」上 —— 而状态行已经写着「已就绪」，
     * 于是界面自相矛盾（用户在真机上截图反馈的正是这个）。
     * 真机实测：绑定本身要 1200ms，所以"构建时启动一次"也不够稳。
     */
    private TextView gameFpsTv = null;
    private TextView gameAppTv = null;

    /**
     * 监测条三格的实时预览行（与悬浮条同源同批数据）。
     *
     * <p>用户选了"上/中/下"三项之后，得能当场看到这三项现在是什么值 ——
     * 否则只能盯着悬浮窗猜自己选对没有。
     */
    private TextView monitorTv = null;

    /**
     * 本页注册到 {@link GameFpsMonitor} 的回调（存成字段才能注销）。
     *
     * <p>★ 改成"监听者 + 引用计数"而不是单个可替换回调：悬浮窗（FloatingService）
     * 和本页**都要用**这份数据，单回调会互相顶掉；而且本页 onPause 时
     * 只该注销自己、不该把服务的采样一起停掉。
     */
    private GameFpsMonitor.Callback gameFpsCb = null;

    /**
     * 本页是否**已经**持有采样引用（acquire / release 必须严格配对）。
     *
     * <h3>★ 为什么必须用标志位（真机 bug 的根因）</h3>
     * 第一版是"条件 acquire + 无条件 release"：
     * {@link #startGameFpsSampling} 只在第 7 节真的渲染时才 acquire，
     * 而 {@code onPause} / {@code onDestroy} 里的 {@link #stopGameFpsSampling}
     * **每次都 release**。于是走这条最普通的路径时：
     * <pre>
     *   打开 App（没进游戏助手页）→ 退出 App
     *   ⇒ 本页 release 掉的是**悬浮窗那一份**引用
     *   ⇒ 引用计数归零 ⇒ 采样线程被停
     *   ⇒ 桌宠挂牌上的数字**永远停在最后一帧**（不再更新，也不消失）
     * </pre>
     * 用户真机反馈的"帧率监测容易失效卡住不动，点开游戏助手的选项才恢复正常，
     * 一退出就又卡住"就是这条路径：进第 7 节 acquire 一次 → 恢复；
     * 退出时 release（onPause + onDestroy 共两次）→ 又归零 → 再冻住。
     *
     * <p>所以：**acquire 过才允许 release，且只允许一次**。
     * 本方法在"构建第 7 节 / Shizuku 就绪回调 / onResume"三条路径上都会被调用，
     * 反过来也不能无条件 acquire —— 计数虚高会让采样永远停不下来（后台白耗电）。
     */
    private boolean gameFpsAcquired = false;

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        awaitingResult = true;
        super.startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 最后一道保险：本页正在销毁时，桌宠**必须**被还回去。
        // onPause 是正常路径，但异常退出 / 系统回收等情况下 onPause 可能没走完，
        // 一旦漏掉，桌宠就会永久消失（比原来"挡住按钮"严重得多）。
        // 重复发一次恢复指令是无害的：服务侧只是把可见性设回 VISIBLE。
        if (petHiddenByApp) {
            restorePetForApp();
        }
        // 丢掉常驻内容壳的引用。它挂在 decor 上，本就会被一起回收，
        // 但 appContentHost 是 Activity 的字段、持有整棵树 —— 在
        // Activity 销毁时主动断开，避免"已销毁的 Activity 仍被自己引用"
        // 这类不易察觉的泄漏（视频视图的原生资源由 onDetachedFromWindow 释放）。
        appContentHost = null;
        contentRoot = null;
        videoBgView = null;
        videoDimView = null;
        // 游戏助手：摘掉自己的监听 + 释放引用，避免已销毁的 Activity 被持有
        stopGameFpsSampling();
        if (shizukuUi != null) {
            ShizukuBridge.removeListener(shizukuUi);
            shizukuUi = null;
        }
    }

    /** 进 App 时收起桌宠（受设置开关控制） */
    private void hidePetForApp() {
        if (!Prefs.hidePetInApp(this)) return;
        petHiddenByApp = true;
        sendPetAction(FloatingService.ACTION_HIDE_TEMP);
    }

    /** 离开 App 时恢复桌宠 */
    private void restorePetForApp() {
        // 开关被关掉的情况：没藏过就没什么要恢复的；藏过了也要还原，
        // 否则用户一关开关，桌宠会一直躲在设置页后面不出来。
        if (!petHiddenByApp) return;
        petHiddenByApp = false;
        sendPetAction(FloatingService.ACTION_SHOW_TEMP);
    }

    private void sendPetAction(String action) {
        try {
            Intent i = new Intent(this, FloatingService.class);
            i.setAction(action);
            startService(i);
        } catch (Exception ignore) {
        }
    }

    /** 主设置界面 */
    /** 主设置界面（按类别分组） */
    private Tokens.Set _uiT;
    private int _uiTKey = Integer.MIN_VALUE;

    /**
     * 当前生效的令牌集合（带缓存）。
     * 缓存 key 用「引擎×主题」——只有这两者变了才需要重建，
     * 否则每次 addButton 都重新 new 一套 Set 会白白产生几十个对象。
     */
    private Tokens.Set uiT() {
        int s = Tokens.skin(this);
        int th = Tokens.theme(this);
        int key = s * 10 + th;
        // ★ 轮次㊾：**自定义配色的修订号也要进缓存 key** —— 否则改完颜色/透明度，
        //   整页还在用上一次缓存的那套 Set（表现成"改了没反应"）。
        try { key = key * 1000000 + ThemeOverride.revision(this); } catch (Throwable ignore) {}
        if (_uiT == null || key != _uiTKey) {
            // ★ 走 Context 版：它会套上自定义覆盖（Context-free 那个重载是纯函数、不套）
            _uiT = Tokens.of(this);
            _uiTKey = key;
        }
        return _uiT;
    }

    /** 令牌被改动后调用（切换引擎/主题），使缓存失效 */
    private void invalidateUiTokens() {
        _uiT = null;
        _uiTKey = Integer.MIN_VALUE;
    }

    /**
     * 重建后要恢复的滚动位置（px），{@code -1} 表示"滚到顶"。
     *
     * <p><b>这是修「点一下按钮就跳回页面顶部」的 bug。</b>
     * 本工程有 55 处 {@code showSettings()} 调用 —— 改一个开关、换一次皮肤、
     * 点一个下拉项都会重建整个页面（这是刻意设计：这些设置互相影响显隐，
     * 就地刷新反而容易漏）。但重建 = 新建一个 ScrollView，
     * 它的滚动位置必然归零。用户在第 6 屏点一下开关，页面"啪"地弹回顶部，
     * 得重新往下滚 —— 这正是反馈的"立马跳转页面顶部"。
     *
     * <p>做法：{@link #showSettings()} 在重建**前**记下当前 scrollY，
     * 重建后把新 ScrollView 滚到同一位置。用 {@code scrollTo}（无动画）
     * 而不是 {@code smoothScrollTo} —— 重建是瞬时的，动画滚动会让内容
     * 先出现在顶部再"飞"下去，比不恢复还糟。
     */
    private int pendingScrollY = -1;
    /** 当前页的 ScrollView，用于在重建前取 scrollY */
    private ScrollView currentScroll;

    /**
     * 上一次真正渲染出来的（板块, 二级页）。
     *
     * <p>用来区分两种 {@code showSettings()}：
     * <ul>
     *   <li><b>导航</b>（切板块 / 进二级页 / 返回）—— 页面真的换了，
     *       该播横向翻页动画、且新页从顶部开始。</li>
     *   <li><b>原地刷新</b>（点开关、改下拉、换皮肤）—— 页面没变，
     *       只是内容要跟着新的设置重算。这时**不该播翻页动画**
     *       （同一个页面横滑进来很吵、也让人以为跳页了），
     *       而且必须**保持滚动位置**。</li>
     * </ul>
     *
     * <p>为什么要自动判断而不是在每个调用点传参：{@code showSettings()}
     * 有 55 处调用，绝大多数是原地刷新。逐个改既容易漏，又会让
     * "以后新增一个开关时该传什么"变成需要思考的问题。
     * 而"页面是否真的换了"这件事，比较 (tab, section) 就能准确得出。
     */
    private int lastRenderedTab = Integer.MIN_VALUE;
    private int lastRenderedSection = Integer.MIN_VALUE;

    private void showSettings() {
        final float d = getResources().getDisplayMetrics().density;
        final int pad = (int) (d * 12);
        // 每次重建都先丢掉上一批读数控件引用：否则 onResume 会往已经
        // 脱离视图树的旧控件上写（表现为"回来以后读数不刷新"）。
        gameFpsTv = null;
        gameAppTv = null;
        monitorTv = null;
        // ★ 引用要跟着"这一页还要不要读数"走：本次重建后第 7 节若不再显示
        //   （切走板块、或用户把游戏助手开关关掉），就当场把本页那份引用还回去。
        //   否则它会一直挂到 onPause 才释放 —— 关掉开关后采样仍在后台每秒跑一条
        //   shell 命令（白耗电），这也是"开关看起来没生效"的来源之一。
        if (settingsSection != 7 || !Prefs.gameAssistant(this)) stopGameFpsSampling();

        final Tokens.Set T = uiT();

        // 本次是不是"真的换了页"？比较目标 (tab, section) 与上次渲染的。
        //
        // 注意要在**任何 setContentView 之前**算出来并记下，
        // 而且这里要同时决定两件事：是否播翻页动画、是否保留滚动位置。
        final boolean navigated = (settingsTab != lastRenderedTab
                || settingsSection != lastRenderedSection);
        lastRenderedTab = settingsTab;
        lastRenderedSection = settingsSection;
        // 原地刷新时强制清掉翻页方向，避免上一次导航遗留的 slideForward
        // 让"点开关"也横滑一下。
        if (!navigated) slideForward = true;

        // 先记下**当前**页面的滚动位置，重建后恢复（否则点一下按钮就弹回顶部）。
        // 累加而不是覆盖：连着调用两次 showSettings（有些分支就是这么写的，
        // 例如 showSettings(); showSettings();）时，第二次若直接用
        // currentScroll.getScrollY()，拿到的已经是新页面（0），
        // 会把第一次记下的位置冲掉。所以有未消费的值就保留它。
        //
        // 只在"原地刷新"时保留位置；导航到新页面时应该从顶部开始看。
        if (!navigated) {
            if (currentScroll != null && pendingScrollY < 0) {
                pendingScrollY = currentScroll.getScrollY();
            }
        } else {
            pendingScrollY = -1;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // 玻璃引擎的 Mesh 光斑要铺在最底层；新拟态下是无光斑的纯色
        UiFactory.decoratePage(scroll, this, T);

        LinearLayout box = new SettingsPage(this, T);
        box.setOrientation(LinearLayout.VERTICAL);
        // 顶部内边距只留 2dp —— 窗口已经为状态栏/刘海让过位（实测内容区顶边
        // 从 y=128 开始），再加 12dp 就成了"上方一大片空白"。
        // 页头自己带 10dp 上内边距，合起来 12dp，视觉上刚好。
        box.setPadding(pad, (int) (d * 2), pad, (int) (d * 24));
        scroll.addView(box);
        // 顶部交给窗口处理，不手动加内边距。
        //
        // 试过手动加 statusBarHeight，结果标题**更低**、留白更大：
        // 这台 Pixel 6（420dpi）的 mDisplayCutout 上边距是 128px（刘海），
        // 而状态栏只有约 66px；窗口本身已经为顶部 inset 让过位，
        // 再用 getSystemWindowInsetTop()（该方法在 API 30 已废弃）加一次
        // 就是**重复让位**，白多出一整条。
        //
        // ★ 关掉窗口自动让位，改由页头手动加状态栏高度（见 head padding）。
        //   两边同时让位就是注释里记过的"重复让位"（标题更低、留白更大）；
        //   只留一边，行为在所有 ROM 上一致。
        scroll.setFitsSystemWindows(false);

        // 页头：标题左对齐、版本号右上角（原型 .topbar）。
        // 改造前标题是居中的，而且没有版本号。
        //
        // 用户反馈「顶部 deepseek 标题文件去掉（deepseek桌宠）桌宠设置太过突兀」：
        // 那条 ActionBar 已在 applyPlatformTheme 里换成 NoActionBar 变体；
        // 这里把标题收敛成**板块名**（首页＝「桌宠设置」），并且右侧版本号
        // 用小字压住视觉重心，让页面从大标题直接进入内容。
        final SettingsPage page = (SettingsPage) box;

        // 页头用**一行两列**：左边大标题（weight=1），右边版本号。
        //
        // 原来是把版本号单独 addToPage，再用 topMargin = -34dp 把它"提"到
        // 与标题同行 —— 负边距是拿一个不参与测量的偏移去伪造对齐，
        // 标题字号（30sp）或行高一变就会错位，而且它会让父容器的
        // 测量高度偏小，把后面的内容整体上提。
        // 改成同一行里的两个子视图后，对齐由布局系统保证。
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);        // 版本号与标题**基线附近**对齐
        // ★ 顶部内边距 = 状态栏高度（报障：二级页大标题撞进状态栏）：
        //   原来靠 scroll.setFitsSystemWindows(true) 吃窗口 inset ——
        //   但 applyEdgeToEdge 把状态栏设透明 + LAYOUT_FULLSCREEN 后，
        //   部分 ROM 上报的 inset 就是 0，标题直接顶到 y=0 撞进状态栏，
        //   且这是 Activity 窗口状态、切页不重建，所以"切回主界面也没用"、
        //   "只有重进软件才好"（onCreate 重走）。
        //   改手动让位后 fits 关掉（见下），两边只让一次，不会重复。
        head.setPadding((int) (d * 4), statusBarHeightPx() + (int) (d * 6),
                (int) (d * 4), (int) (d * 6));

        TextView title = new TextView(this);
        title.setText(settingsSection < 0 ? TAB_TITLES[settingsTab] : _GRP_NAMES[settingsSection]);
        title.setTextSize(30);
        title.setTextColor(T.text1);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setLetterSpacing(-0.01f);
        title.setGravity(Gravity.START);
        head.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 右上角版本号（原型 .topbar .sub）
        TextView ver = new TextView(this);
        ver.setText(versionLabel());
        ver.setTextSize(12);
        ver.setTextColor(T.text3);
        ver.setGravity(Gravity.END);
        ver.setPadding(0, 0, 0, (int) (d * 3));   // 底对齐时略微抬起，视觉更稳
        head.addView(ver, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addToPage(head);

        if (settingsSection >= 0) {
            // 返回：小胶囊按钮靠左，不是占满整行的大按钮（原型 .backrow button）
            //
            // 文案不带 emoji：原型的返回键是「← 返回」纯文字，而 ⬅️ 在这套
            // 单色语言里是一块彩色贴纸（uiautomator 里也能看到 ⬅️ 字样）。
            Button back = UiFactory.button(this, T, "返回", false);
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // 返回 = 回到**所属板块**的首页，并让底部导航跟着选中，
                    // 否则从二级页返回后导航栏会停在别的板块上，看着像 bug。
                    settingsTab = tabOfSection(settingsSection);
                    settingsSection = -1;
                    // 返回是"退一层"，新页从**左侧**推入 —— 与进入时的
                    // 右侧推入方向相反，用户一眼能看出自己在往回走。
                    slideForward = false;
                    showSettings();
                }
            });
            LinearLayout backRow = new LinearLayout(this);
            backRow.setOrientation(LinearLayout.HORIZONTAL);
            backRow.setPadding(0, (int) (d * 2), 0, (int) (d * 8));
            backRow.addView(back, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            page.addToPage(backRow);
        }

        // 首页 hero 卡：圆形头像 + 名字 + 状态（原型 .hero）
        if (settingsSection < 0) {
            page.addToPage(heroCard(T));
        }

        if (settingsSection == 0) {
        // ================= ⓪ 界面风格（引擎 × 主题）=================
        // 放在最前面：这是整套视觉的开关，调完下面所有卡片立即换肤。
        addSection(box, "\uD83C\uDFA8 界面风格");

        LinearLayout _skinRow = new LinearLayout(this);
        _skinRow.setOrientation(LinearLayout.HORIZONTAL);
        _skinRow.setPadding(0, (int) (d * 4), 0, (int) (d * 4));
        Button _bNeu = UiFactory.button(this, T,
                Tokens.skin(this) == Tokens.SKIN_NEUMORPH ? "● 新拟态" : "○ 新拟态", false);
        Button _bGls = UiFactory.button(this, T,
                Tokens.skin(this) == Tokens.SKIN_GLASS ? "● 玻璃态" : "○ 玻璃态", false);
        LinearLayout.LayoutParams _half = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        _half.rightMargin = (int) (d * 6);
        _skinRow.addView(_bNeu, _half);
        _skinRow.addView(_bGls, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        _bNeu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Tokens.setSkin(MainActivity.this, Tokens.SKIN_NEUMORPH);
                invalidateUiTokens(); showSettings();
            }
        });
        _bGls.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Tokens.setSkin(MainActivity.this, Tokens.SKIN_GLASS);
                invalidateUiTokens(); showSettings();
            }
        });
        box.addView(_skinRow);

        LinearLayout _thmRow = new LinearLayout(this);
        _thmRow.setOrientation(LinearLayout.HORIZONTAL);
        _thmRow.setPadding(0, (int) (d * 4), 0, (int) (d * 8));
        String[] _thmNames = {"跟随系统", "浅色", "深色"};
        int[] _thmVals = {-1, Tokens.THEME_LIGHT, Tokens.THEME_DARK};
        for (int _i = 0; _i < _thmNames.length; _i++) {
            final int _v = _thmVals[_i];
            boolean _on = Tokens.themeMode(this) == _v;
            Button _bt = UiFactory.button(this, T,
                    (_on ? "● " : "○ ") + _thmNames[_i], false);
            _bt.setTextSize(14);
            LinearLayout.LayoutParams _lp3 = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (_i < 2) _lp3.rightMargin = (int) (d * 6);
            _bt.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Tokens.setThemeMode(MainActivity.this, _v);
                    invalidateUiTokens();
                    // 主题变了必须 recreate（而不是就地重绘）：系统控件的明暗
                    // 由 Activity 主题决定，只在 onCreate 里 setTheme 才生效。
                    // 引擎不用 recreate —— 它只影响我们自绘的部分。
                    recreate();
                }
            });
            _thmRow.addView(_bt, _lp3);
        }
        box.addView(_thmRow);
        addLabel(box, "玻璃态铺 Mesh 渐变光斑作 App 背景；新拟态下表面与背景同色，形状只靠浮雕阴影表达。");

        // ================= ⓪-1.5 自定义界面配色 + UI 透明度（仅玻璃态 · 轮次㊾）=================
        //
        // ★ 用户要求：「我们现在做自定义主题颜色（玻璃态）可以自定义界面ui颜色 用来搭配不同的背景
        //   增加ui透明度选项」。**只挂在玻璃态**：新拟态表面与背景同色、形状只靠浮雕阴影，
        //   改透明度在那里没有意义（用户已拍板）。
        // ★ 存盘按"引擎×明暗"分两套（glass_light / glass_dark）—— 同一组颜色在深浅底下要的不一样。
        if (Tokens.skin(this) == Tokens.SKIN_GLASS) {
            final Tokens.Set cur = uiT();
            addButton(box, "\uD83C\uDFA8 自定义界面颜色…（" + customThemeSummary() + "）",
                    new View.OnClickListener() {
                @Override public void onClick(View x) { showCustomTheme(); }
            });
            addButton(box, "\uD83E\uDEE7 UI 透明度…（底色 "
                    + pct(ThemeOverride.alphaOf(cur.bg)) + " · 卡片 "
                    + pct(ThemeOverride.alphaOf(cur.surface)) + " · 弹窗 "
                    + pct(ThemeOverride.alphaOf(cur.modal)) + "）",
                    new View.OnClickListener() {
                @Override public void onClick(View x) { showAlphaPicker(); }
            });
            addLabel(box, "调整会立刻生效，浅色/深色**各存一套**。文字颜色按你选的底色**自动重推**，"
                    + "所以调得再透也不至于看不清；随时可以「恢复这一套默认」。");
        }

        // ================= ⓪-1 背景（图片 / 视频，仅玻璃态）=================
        //
        // ★ 第二十二轮：把原来**两个独立小节**（图片背景 / 视频背景）合并成一个。
        //   用户要求「把图片和视频背景的选项合在一起」。
        //
        // 合并前的问题：两个独立开关可以**同时打开**，于是渲染层只能靠一个
        // 含糊的"优先级"开关裁决 —— 用户看到的就是「开了没反应」
        // （图片把视频盖住了）。而且"压暗"在两边各有一个滑块，值却是分开存的，
        // 用户会以为调错了。
        //
        // 合并后：**一个类型选择器（不用 / 图片 / 视频）**，天然三选一。
        // 具体规则：
        //   · 选哪类就只显示哪类的参数（压暗、裁剪、静音…），不再并列两套
        //   · 两个已导入的文件**都保留**，来回切换不必重新导入
        //   · "谁优先"那个开关随之**删掉** —— 类型已经唯一确定，它没有意义了
        //   · 互斥由 {@link Prefs#setBgKind} 一处保证，UI 只可能给出三种状态之一
        //
        // ★ 仍然只在玻璃态出现：新拟态卡片是不透明 surface，背景会被整块盖住、
        //   只在卡片缝隙里露出来，等于没有这个功能。与其给一个"开了没反应"的
        //   开关，不如根本不显示（用户明确要求过「新拟态不加入这个接口」）。
        if (Tokens.skin(this) == Tokens.SKIN_GLASS) {
            addSection(box, "\uD83D\uDDBC 背景");

            final String _bgKind = Prefs.activeBgKind(this);
            final boolean _hasImg = Prefs.imageBgFile(this).exists();
            final boolean _hasVid = Prefs.videoBgFile(this).exists();

            // ---- 类型选择器：不用 / 图片 / 视频 ----
            LinearLayout _bgRow = new LinearLayout(this);
            _bgRow.setOrientation(LinearLayout.HORIZONTAL);
            _bgRow.setPadding(0, (int) (d * 4), 0, (int) (d * 4));
            final String[] _bgKinds = {Prefs.BG_NONE, Prefs.BG_IMAGE, Prefs.BG_VIDEO};
            final String[] _bgNames = {"不用背景", "图片", "视频"};
            for (int _i = 0; _i < _bgKinds.length; _i++) {
                final String _k = _bgKinds[_i];
                boolean _on = _k.equals(_bgKind);
                Button _bt = UiFactory.button(this, T,
                        (_on ? "● " : "○ ") + _bgNames[_i], false);
                _bt.setTextSize(14);
                LinearLayout.LayoutParams _lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (_i < 2) _lp.rightMargin = (int) (d * 6);
                _bt.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // ★ 选"图片/视频"但还没导入 → 直接拉起选择器，
                        //   别只弹一句"请先选择"再让用户自己去找按钮
                        //   （与合并前那两个开关的处理一致）。
                        boolean has = Prefs.BG_IMAGE.equals(_k) ? _hasImg
                                    : Prefs.BG_VIDEO.equals(_k) ? _hasVid
                                    : true;
                        if (!has) {
                            if (Prefs.BG_IMAGE.equals(_k)) {
                                Toast.makeText(MainActivity.this, "先选一张图片",
                                        Toast.LENGTH_SHORT).show();
                                pickImageBg();
                            } else {
                                Toast.makeText(MainActivity.this, "先选一段视频",
                                        Toast.LENGTH_SHORT).show();
                                pickVideoBg();
                            }
                            return;
                        }
                        Prefs.setBgKind(MainActivity.this, _k);
                        showSettings();
                    }
                });
                _bgRow.addView(_bt, _lp);
            }
            box.addView(_bgRow);

            if (Prefs.BG_IMAGE.equals(_bgKind)) {
                // ============ 图片模式的参数 ============
                // ★ 压暗滑块与视频**共用一个 pref 语义**：遮罩是同一层，
                //   但两类背景的默认值不同（图片静态、没有运动干扰，默认更轻）。
                final TextView _dimLabel = new TextView(this);
                _dimLabel.setTextSize(15);
                _dimLabel.setTextColor(T.text2);
                _dimLabel.setPadding(0, (int) (d * 8), 0, (int) (d * 4));
                _dimLabel.setText("压暗：" + Math.round(Prefs.imageBgDim(this) * 100) + "%"
                        + "（图片太亮、字看不清就调大）");
                box.addView(_dimLabel);

                SeekBar _dimBar = UiFactory.seekBar(this, uiT());
                _dimBar.setMax(80);
                _dimBar.setProgress(Math.round(Prefs.imageBgDim(this) / 0.8f * 80f));
                _dimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        float v = p / 80f * 0.8f;
                        _dimLabel.setText("压暗：" + Math.round(v * 100) + "%"
                                + "（图片太亮、字看不清就调大）");
                        if (fromUser) {
                            Prefs.setImageBgDim(MainActivity.this, v);
                            applyVideoDimLive(v);   // 遮罩是共用的一层
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {}
                });
                box.addView(_dimBar);

                addButton(box, "更换背景图片", new View.OnClickListener() {
                    @Override public void onClick(View v) { pickImageBg(); }
                });

                addButton(box, "移除背景图片", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        UiFactory.dialog(MainActivity.this)
                                .setTitle("移除背景图片")
                                .setMessage("删掉已导入的背景图片？之后可以重新选一张。")
                                .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface dlg, int w) {
                                        File f = Prefs.imageBgFile(MainActivity.this);
                                        if (f.exists()) f.delete();
                                        Prefs.clearImageBgCrop(MainActivity.this);
                                        Prefs.setBgKind(MainActivity.this, Prefs.BG_NONE);
                                        showSettings();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });

                addLabel(box, "图片会被裁成和屏幕同比例再铺满，不会变形。"
                        + "太亮导致字看不清就调大上面的压暗。");

            } else if (Prefs.BG_VIDEO.equals(_bgKind)) {
                // ============ 视频模式的参数 ============
                final TextView _dimLabel = new TextView(this);
                _dimLabel.setTextSize(15);
                _dimLabel.setTextColor(T.text2);
                _dimLabel.setPadding(0, (int) (d * 8), 0, (int) (d * 4));
                _dimLabel.setText("压暗：" + Math.round(Prefs.videoBgDim(this) * 100) + "%"
                        + "（视频太亮、字看不清就调大）");
                box.addView(_dimLabel);

                SeekBar _dimBar = UiFactory.seekBar(this, uiT());
                _dimBar.setMax(80);
                _dimBar.setProgress(Math.round(Prefs.videoBgDim(this) / 0.8f * 80f));
                _dimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        float v = p / 80f * 0.8f;
                        _dimLabel.setText("压暗：" + Math.round(v * 100) + "%"
                                + "（视频太亮、字看不清就调大）");
                        if (fromUser) {
                            Prefs.setVideoBgDim(MainActivity.this, v);
                            applyVideoDimLive(v);
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {}
                });
                box.addView(_dimBar);

                addToggle(box, "播放视频声音（会盖住桌宠朗读）", Prefs.videoBgSound(this),
                        new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        Prefs.setVideoBgSound(MainActivity.this, checked);
                        // 只改音量，不必重建整页（重建会让视频从头开始，很跳）。
                        if (videoBgView != null) videoBgView.setSound(checked);
                    }
                });

                // ── 不再提供「画面铺法」选择器 ──
                //
                // 裁剪框**锁成屏幕比例**（见 VideoCropView.lockAspect），
                // 于是 W/cw == H/ch，两种铺法的缩放公式
                //   COVER   = max(W/cw, H/ch)
                //   CONTAIN = min(W/cw, H/ch)
                // **恒等** —— 选哪个都一模一样。留着只会让用户以为"选了没生效"。
                // （等价性有断言：build/vidtest 的 [17]。）
                Prefs.setVideoBgFit(this, Prefs.FIT_COVER);   // 归一成唯一行为

                addButton(box, "\uD83D\uDDBC 裁剪画面", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showVideoCropDialog();
                    }
                });

                addButton(box, "更换背景视频", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        pickVideoBg();
                    }
                });

                addButton(box, "移除背景视频", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.clearVideoBg(MainActivity.this);
                        Prefs.setBgKind(MainActivity.this, Prefs.BG_NONE);
                        showSettings();
                    }
                });

                addLabel(box, "画面按裁剪框原样铺满，不再二次裁切或拉伸。"
                        + "视频循环播放；建议用短片段（几秒到几十秒），"
                        + "太长的每次进设置页都要重新解码。");

            } else {
                // ============ 不用背景 ============
                addLabel(box, "当前用 Mesh 渐变光斑作背景。"
                        + "选「图片」或「视频」可以换成自己的素材 —— "
                        + "玻璃卡片会透出背景画面。");
            }

            // 两类素材都存在时，给一个快速切换入口 —— 合并后这是常见需求
            // （用户可能想「白天用图片、晚上用视频」）。
            if (_hasImg && _hasVid) {
                final boolean _useImg = !Prefs.BG_VIDEO.equals(_bgKind);
                addButton(box, _useImg ? "切换到视频背景" : "切换到图片背景",
                        new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.setBgKind(MainActivity.this,
                                _useImg ? Prefs.BG_VIDEO : Prefs.BG_IMAGE);
                        showSettings();
                    }
                });
            }
        }

        // ================= ① 外观 =================
        }

        // ================= 形象与动作（v7.0 新增二级页）=================
        //
        // 用户要求：把原「外观与显示」里的**外观**、原「表情与素材」里的**桌宠形象**，
        // 加上**动作系统**，集成成同一个二级页；「外观与显示」只留主题与显示相关。
        // 做法与当初拆第 2 节时一样 —— **只在边界插守卫，不搬任何代码**
        // （这几块之间零局部变量共享）。
        if (settingsSection == 10) {
        addSection(box, "\uD83C\uDFA8 外观");

        // 预览
        //
        // 评审报告里那条「183dp 死间距」的根因就在这里：预览固定占 140×187dp，
        // 而内置形象 pet.png 是 268×330 的竖图 —— FIT_CENTER 之后上下留白，
        // 中间一大片空白，把下面真正要操作的滑块推出屏幕。
        //
        // 改法：高度改成 WRAP_CONTENT + adjustViewBounds，让预览按图片实际
        // 比例占位；并在真的拿不到任何图时整个收起（GONE），不留空盒子。
        // 预览框：**固定高度**，宠物图在里面缩放。
        //
        // 用户反馈「放大缩小的时候整个选项框也会被拉长缩短，请固定预览框」。
        // 前一版改的是 ImageView 的 maxHeight —— 那是**参与布局**的尺寸，
        // 图片一变高，卡片跟着变高、下面所有控件都被推着走。
        // 现在外面套一个高度恒定的 FrameLayout，缩放改用 setScaleX/Y：
        // 那是绘制期变换，不触发测量/布局，卡片高度恒定、也不会掉帧。
        final int pvBoxH = (int) (d * 280);   // = 缩放上限 1.30× 时的像素高
        previewBox = new FixedHeightBox(this, pvBoxH);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, pvBoxH);
        plp.topMargin = (int) (d * 4);
        plp.bottomMargin = (int) (d * 4);
        previewBox.setLayoutParams(plp);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(false);
        preview.setScaleType(Prefs.keepRatio(this)
                ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.FIT_XY);
        previewBox.addView(preview, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        box.addView(previewBox);
        // **必须再设一次**：SettingsPage/Group.addRow 在把子视图塞进卡片时，
        // 会显式传一个 `(MATCH_PARENT, WRAP_CONTENT)` 的 LayoutParams，
        // 把上面设好的固定高度**覆盖掉** —— 于是预览框又变成"跟着图片高度走"，
        // 卡片照旧被拉长缩短（实测：拖动时下面那行标签 y 从 1612 跑到 1816）。
        // addView 返回后再设一次，用的一定是这份固定高度。
        previewBox.setLayoutParams(plp);
        applyPreviewScale(Prefs.getScale(this));   // 与滑块当前值对齐
        updatePreview();

        // 大小
        scaleLabel = new TextView(this);
        scaleLabel.setTextSize(16);
        scaleLabel.setTextColor(T.text2);
        scaleLabel.setPadding(0, (int) (d * 8), 0, (int) (d * 4));
        box.addView(scaleLabel);

        scaleBar = UiFactory.seekBar(this, uiT());
        scaleBar.setMax(100);
        int sp = (int) ((Prefs.getScale(this) - Prefs.SCALE_MIN) / (Prefs.SCALE_MAX - Prefs.SCALE_MIN) * 100.0f);
        scaleBar.setProgress(Math.max(0, Math.min(100, sp)));
        scaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float s = Prefs.SCALE_MIN + (p / 100f) * (Prefs.SCALE_MAX - Prefs.SCALE_MIN);
                if (scaleLabel != null) scaleLabel.setText(String.format("大小：%.2f\u00D7", s));
                // 拖动过程中就要看到预览跟着变（不等松手）
                if (fromUser) applyScaleLive(s, false);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                // 松手补一次最终值：节流可能把最后一次丢掉
                int p = sb.getProgress();
                applyScaleLive(Prefs.SCALE_MIN
                        + (p / 100f) * (Prefs.SCALE_MAX - Prefs.SCALE_MIN), true);
            }
        });
        box.addView(scaleBar);
        updateScaleLabel();

        // 透明度
        alphaLabel = new TextView(this);
        alphaLabel.setTextSize(16);
        alphaLabel.setTextColor(T.text2);
        alphaLabel.setPadding(0, pad, 0, (int) (d * 4));
        box.addView(alphaLabel);

        alphaBar = UiFactory.seekBar(this, uiT());
        alphaBar.setMax(80);
        alphaBar.setProgress((int) ((Prefs.getAlpha(this) - 0.2f) / 0.8f * 80.0f));
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float a = 0.2f + (p / 80f) * 0.8f;
                if (alphaLabel != null) alphaLabel.setText(String.format("透明度：%d%%", (int) (a * 100)));
                if (fromUser) {
                    Prefs.saveAlpha(MainActivity.this, a);
                    Prefs.reloadPet(MainActivity.this);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(alphaBar);
        updateAlphaLabel();

        addToggle(box, "图片等比显示（不变形）", Prefs.keepRatio(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setKeepRatio(MainActivity.this, checked);
                if (preview != null) {
                    preview.setScaleType(checked ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.FIT_XY);
                }
                Prefs.reloadPet(MainActivity.this);
            }
        });

        // ================= ② 表情与素材 =================
        //
        // 原型把它从「外观与显示」里拆出来独立成页 —— 表情包、气泡尺寸、
        // 回复连发、桌宠形象导入都属于"素材"，与"外观"是两回事。
        // 复用原先的死枚举 2（`_grpNames` 里叫「主动行为」但从来没有分支）。
        //
        // 拆分是安全的：实测这一整块 7 个子块之间**零局部变量共享**
        // （tools/scan_locals.py 确认无重名），所以只需在边界插守卫，
        // 不用搬动任何代码。
        }
        if (settingsSection == 2) {
        // ———— 气泡里的图片表情 ————
        addSection(box, "\uD83D\uDDBC 气泡图片表情");
        addToggle(box, "\uD83C\uDFF4 在气泡里配上表情包图片", Prefs.memeEnabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setMemeEnabled(MainActivity.this, checked);
                showSettings();      // 依赖它的下级选项要一起刷新显隐
            }
        });

        if (Prefs.memeEnabled(this)) {
            // 这条提示的文案取决于本开关的值，所以必须能被就地刷新
            // （见下面 onCheckedChanged 里的 setText）。
            //
            // ★ 原来这里是 `showSettings()` 整页重建 —— 只为改这段文字。
            // 而整页重建会让**所有**视图重新创建并重新布局，那一次重建
            // 就是用户看到的"闪"。文案是**行内**的事，就地改掉即可。
            //
            // refreshChance 用单元素数组持有：它定义在下面，而这个开关的
            // 回调要调它 —— Java 不允许在初始化器里引用尚未声明的局部变量，
            // 数组是这里最直接的解法（同 §锁定菜单参数 的 lockRowRef）。
            final Runnable[] refreshChance = new Runnable[1];
            final TextView pickHint = new TextView(this);
            pickHint.setTextSize(15);
            pickHint.setTextColor(T.text3);
            pickHint.setPadding(UiFactory.rowSidePad(this), (int) (d * 4),
                    UiFactory.rowSidePad(this), (int) (d * 4));
            pickHint.setLineSpacing(0, 1.15f);

            addToggle(box, "\uD83E\uDD16 让桌宠自己挑图（按语义选，更贴切）", Prefs.memeModelPick(this),
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    Prefs.setMemeModelPick(MainActivity.this, checked);
                    pickHint.setText(checked
                            ? "由模型按回复内容挑最合适的那张（选了就用它）；模型没挑中时，"
                              + "再按下面的概率随机配一张。"
                            : "关闭后完全不把表情清单发给模型（省 token），"
                              + "只在本地按下面的概率随机抽一张。");
                    // 下面「配图概率」的文案里也带模型选图的状态，一并刷新
                    refreshChance[0].run();
                }
            });
            pickHint.setText(Prefs.memeModelPick(this)
                    ? "由模型按回复内容挑最合适的那张（选了就用它）；模型没挑中时，"
                      + "再按下面的概率随机配一张。"
                    : "关闭后完全不把表情清单发给模型（省 token），"
                      + "只在本地按下面的概率随机抽一张。");
            box.addView(pickHint);

            // 配图概率：0=从不配图，100=每条都配
            final TextView chanceLabel = new TextView(this);
            chanceLabel.setTextSize(15);
            refreshChance[0] = new Runnable() {
                @Override public void run() {
                    int c = Prefs.getMemeChance(MainActivity.this);
                    boolean mp = Prefs.memeModelPick(MainActivity.this);
                    String s;
                    if (c <= 0) {
                        // 概率为 0 时，还要看模型选图开没开，否则文案会与实际不符
                        s = mp ? "不随机配图，只在模型挑中时配" : "从不配图";
                    } else if (c >= 100) {
                        s = "每条都配图";
                    } else {
                        s = "约 " + c + "% 的回复会配图";
                    }
                    chanceLabel.setText("\uD83C\uDF9C 配图概率：" + s);
                }
            };
            refreshChance[0].run();
            box.addView(chanceLabel);
            SeekBar chanceBar = UiFactory.seekBar(this, uiT());
            chanceBar.setMax(100);
            chanceBar.setProgress(Prefs.getMemeChance(this));
            chanceBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                    Prefs.setMemeChance(MainActivity.this, pr);
                    refreshChance[0].run();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
            box.addView(chanceBar);

            addToggle(box, "\uD83D\uDCAC 连发短句时也配图（整轮共用一张）",
                    Prefs.memeSplitEnabled(this),
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    Prefs.setMemeSplitEnabled(MainActivity.this, checked);
                }
            });
            addLabel(box, "关闭时：连发只出文字，不会每段都跳出一张图。");
        }

        // ================= 点击音效（轮次57）=================
        // 只挂在**戳桌宠**那一刻（PetView.poke）。音色/音量都能调，改完当场试听 ——
        // 声音这种"调完还得退出去戳一下才知道"的设置，不试听等于没法调。
        addToggle(box, "\uD83D\uDD0A 点击音效（戳桌宠时响一声）",
                Prefs.clickSoundOn(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setClickSoundOn(MainActivity.this, checked);
                if (checked) ClickPlayer.play(Prefs.clickSoundStyle(MainActivity.this),
                        Prefs.clickSoundVolume(MainActivity.this));
            }
        });
        addButton(box, "\uD83C\uDFB5 音效音色：" + ClickSound.styleName(Prefs.clickSoundStyle(this)),
                new View.OnClickListener() {
            @Override public void onClick(View v) { pickClickStyle(); }
        });
        addButton(box, "\uD83D\uDD09 音效音量：" + ClickSound.volumeName(Prefs.clickSoundVolume(this)),
                new View.OnClickListener() {
            @Override public void onClick(View v) { pickClickVolume(); }
        });
        addLabel(box, "音效由 App 现场合成（不占素材、不需要下载），只在你戳桌宠时响；"
                + "关掉就完全不出声。");

        addLabel(box, "\uD83D\uDD0D 表情包大小");
        SeekBar memeScaleBar = UiFactory.seekBar(this, uiT());
        memeScaleBar.setMax(100);
        memeScaleBar.setProgress((int) ((Prefs.getMemeScale(this) - Prefs.MEME_SCALE_MIN)
                / (Prefs.MEME_SCALE_MAX - Prefs.MEME_SCALE_MIN) * 100f));
        final TextView memeScaleLabel = new TextView(this);
        memeScaleLabel.setTextSize(15);
        updateMemeScaleLabel(memeScaleLabel);
        box.addView(memeScaleLabel);
        memeScaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                float v = Prefs.MEME_SCALE_MIN
                        + (Prefs.MEME_SCALE_MAX - Prefs.MEME_SCALE_MIN) * pr / 100f;
                Prefs.setMemeScale(MainActivity.this, v);
                updateMemeScaleLabel(memeScaleLabel);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(memeScaleBar);

        addLabel(box, "\uD83D\uDCE4 表情包管理");

        // 单张上传：顺手把名字改成有意义的（名字就是模型选图的依据之一）
        addButton(box, "\uD83D\uDCE4 上传一张表情（可顺手改名）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_PICK_MEME);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this,
                            "打不开图片选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        // 批量上传：多选，名字一律取自文件名
        addButton(box, "\uD83D\uDCDA 批量导入表情（可多选）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_PICK_MEME_MULTI);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this,
                            "打不开图片选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        // 管理：看清单、改描述、改名、删除
        addButton(box, "\uD83D\uDCC1 查看／管理已有表情", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMemeManager();
            }
        });
        addLabel(box, "名字会同时作为文件名保存；描述决定桌宠什么时候挑中它，"
                + "改完立刻生效。");

        // ———— 余额气泡：★★ 轮次76 用户要的**总开关** ————
        //   「增加气泡显示的开关，可以关闭。不显示余额气泡。」
        //   ★ 与"单击桌宠切换显隐"是两件事：这个关掉之后，单击也不会再把它打开，
        //     而且**余额轮询一起停**（每一拍都是一次真实网络请求）。
        addToggle(box, "\uD83D\uDCB0 显示余额气泡", Prefs.balanceBubbleOn(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setBalanceBubbleOn(MainActivity.this, checked);
                // 立刻通知悬浮窗（隐藏/恢复 + 停/启轮询，不重建整套视图）
                Intent i = new Intent(MainActivity.this, FloatingService.class);
                i.setAction(FloatingService.ACTION_BALANCE_VIS);
                startService(i);
                Toast.makeText(MainActivity.this,
                        checked ? "已显示余额气泡" : "已关闭余额气泡（也不再轮询余额）",
                        Toast.LENGTH_SHORT).show();
            }
        });
        addLabel(box, "关掉后：桌面上不再出现余额气泡，**单击桌宠也不会再切换它**，"
                + "并且**停止余额轮询**（省流量、省电）。");

        // ———— 余额气泡外观 ————
        addLabel(box, "\uD83D\uDCB0 余额气泡大小");
        SeekBar balScaleBar = UiFactory.seekBar(this, uiT());
        balScaleBar.setMax(100);
        balScaleBar.setProgress((int) ((Prefs.getBalanceScale(this) - Prefs.BUBBLE_SCALE_MIN)
                / (Prefs.BUBBLE_SCALE_MAX - Prefs.BUBBLE_SCALE_MIN) * 100f));
        final TextView balScaleLabel = new TextView(this);
        balScaleLabel.setTextSize(15);
        updateBalScaleLabel(balScaleLabel);
        box.addView(balScaleLabel);
        balScaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                float v = Prefs.BUBBLE_SCALE_MIN + (pr / 100f) * (Prefs.BUBBLE_SCALE_MAX - Prefs.BUBBLE_SCALE_MIN);
                Prefs.saveBalanceScale(MainActivity.this, v);
                updateBalScaleLabel(balScaleLabel);
                if (fromUser) Prefs.reloadPet(MainActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(balScaleBar);

        addLabel(box, "\uD83D\uDC46 单击桌宠可显示 / 隐藏余额气泡（需先打开上面的开关），"
                + "显示后一直常驻，不会被自动隐藏。");

        addLabel(box, "\uD83D\uDCAC 对话气泡大小");
        SeekBar bubbleScaleBar = UiFactory.seekBar(this, uiT());
        bubbleScaleBar.setMax(100);
        bubbleScaleBar.setProgress((int) ((Prefs.getBubbleScale(this) - Prefs.BUBBLE_SCALE_MIN)
                / (Prefs.BUBBLE_SCALE_MAX - Prefs.BUBBLE_SCALE_MIN) * 100f));
        final TextView bubbleScaleLabel = new TextView(this);
        bubbleScaleLabel.setTextSize(15);
        updateBubbleScaleLabel(bubbleScaleLabel);
        box.addView(bubbleScaleLabel);
        bubbleScaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                float v = Prefs.BUBBLE_SCALE_MIN + (pr / 100f) * (Prefs.BUBBLE_SCALE_MAX - Prefs.BUBBLE_SCALE_MIN);
                Prefs.saveBubbleScale(MainActivity.this, v);
                updateBubbleScaleLabel(bubbleScaleLabel);
                if (fromUser) Prefs.reloadPet(MainActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(bubbleScaleBar);

        addLabel(box, "\u23F1\uFE0F 对话气泡存在时长");
        SeekBar bubbleDurBar = UiFactory.seekBar(this, uiT());
        bubbleDurBar.setMax(50);   // 0.5x..3.0x, 每档 0.05x
        bubbleDurBar.setProgress((int) ((Prefs.getBubbleDurScale(this) - Prefs.BUBBLE_DUR_MIN)
                / (Prefs.BUBBLE_DUR_MAX - Prefs.BUBBLE_DUR_MIN) * 50f));
        final TextView bubbleDurLabel = new TextView(this);
        bubbleDurLabel.setTextSize(15);
        updateBubbleDurLabel(bubbleDurLabel);
        box.addView(bubbleDurLabel);
        bubbleDurBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                float v = Prefs.BUBBLE_DUR_MIN + (pr / 50f) * (Prefs.BUBBLE_DUR_MAX - Prefs.BUBBLE_DUR_MIN);
                Prefs.saveBubbleDurScale(MainActivity.this, v);
                updateBubbleDurLabel(bubbleDurLabel);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(bubbleDurBar);

        // ———— 回复连发（把一条回复拆成几条短消息，更像真人在发消息）————
        addSection(box, "💬 回复连发");
        addToggle(box, "🔁 开启回复连发（把长回复拆成几条短消息逐条弹出）",
                Prefs.splitReply(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setSplitReply(MainActivity.this, checked);
            }
        });

        addLabel(box, "🔢 一次最多拆几条");
        SeekBar splitMaxBar = UiFactory.seekBar(this, uiT());
        splitMaxBar.setMax(Prefs.SPLIT_MAX_MAX - Prefs.SPLIT_MAX_MIN);
        splitMaxBar.setProgress(Prefs.splitMax(this) - Prefs.SPLIT_MAX_MIN);
        final TextView splitMaxLabel = new TextView(this);
        splitMaxLabel.setTextSize(15);
        updateSplitMaxLabel(splitMaxLabel);
        box.addView(splitMaxLabel);
        splitMaxBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                Prefs.setSplitMax(MainActivity.this, Prefs.SPLIT_MAX_MIN + pr);
                updateSplitMaxLabel(splitMaxLabel);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(splitMaxBar);

        addLabel(box, "⏳ 每条之间的间隔");
        SeekBar splitDelayBar = UiFactory.seekBar(this, uiT());
        splitDelayBar.setMax((Prefs.SPLIT_DELAY_MAX - Prefs.SPLIT_DELAY_MIN) / 100);
        splitDelayBar.setProgress((Prefs.splitDelayMs(this) - Prefs.SPLIT_DELAY_MIN) / 100);
        final TextView splitDelayLabel = new TextView(this);
        splitDelayLabel.setTextSize(15);
        updateSplitDelayLabel(splitDelayLabel);
        box.addView(splitDelayLabel);
        splitDelayBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                Prefs.setSplitDelayMs(MainActivity.this, Prefs.SPLIT_DELAY_MIN + pr * 100);
                updateSplitDelayLabel(splitDelayLabel);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(splitDelayBar);

        // ================= ⑥ 语音 =================
        //
        // 原型明确指出的问题：「语音（服务商 / Key / 音色克隆 / 语速 / 模型）
        // 全部塞在「外观与显示」里，语义不符」→ 整组独立成页。
        }
        if (settingsSection == 6) {
        // ———— 🔊 让桌宠说话（云端语音合成 · 多协议）————
        addSection(box, "\uD83D\uDD0A 让桌宠说话（云端语音合成）");

        addToggle(box, "\uD83D\uDD0A 开启语音朗读（把桌宠说的话念出来）", Prefs.ttsEnabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setTtsEnabled(MainActivity.this, checked);
                Prefs.reloadPet(MainActivity.this);
                // ★ 不调 showSettings()：本页没有任何行依赖这个值，开关自己的
                // 外观已在 UiFactory.switchView 里更新过了。整页重建会让
                // 所有视图重新创建，那一次重建正是用户看到的"闪"。
            }
        });

        addLabel(box, "支持多家服务商：硅基流动 / OpenAI / 智谱 / 阿里云百炼 / 火山引擎 / "
                + "MiniMax，也可填自定义地址。各家协议不同（地址、请求格式、鉴权都不一样），"
                + "已按服务商自动适配 —— 选好服务商再填 Key 即可。");

        String ttsProblem = Prefs.ttsCheck(this);
        if (Prefs.ttsEnabled(this) && ttsProblem != null) {
            addLabel(box, "\u26A0\uFE0F 当前还不能发声：" + ttsProblem);
        }

        addButton(box, "\uD83C\uDF10 服务商：" + ttsProviderSummary(), new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTtsProviderDialog();
            }
        });

        addLabel(box, "接口地址：" + ttsUrlSummary());

        addButton(box, "\uD83D\uDCDF 修改接口地址（留空则用预设）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTtsUrlDialog();
            }
        });

        addButton(box, "\uD83D\uDD11 语音 Key：" + ttsKeySummary(), new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTtsKeyDialog();
            }
        });

        addButton(box, "\uD83C\uDFA7 音色：" + ttsOr(Prefs.ttsVoiceOrDefault(this), "服务端默认")
                        + "  ·  模型：" + ttsOr(Prefs.ttsModelOrDefault(this), "服务端默认")
                        + "  ·  格式：" + Prefs.ttsFormatOrDefault(this),
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTtsVoiceDialog();
            }
        });

        addButton(box, "\uD83C\uDFA4 音色克隆：" + ttsCloneSummary(), new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTtsCloneDialog();
            }
        });

        final TextView ttsSpeedLabel = addLabelTv(box,
                "\uD83D\uDD0A 语速：当前 " + String.format("%.2f", Prefs.ttsSpeed(this)) + "×");
        SeekBar ttsSpeedBar = UiFactory.seekBar(this, uiT());
        ttsSpeedBar.setMax(150);   // 0.50 ~ 2.00
        ttsSpeedBar.setProgress((int) ((Prefs.ttsSpeed(this) - 0.5f) / 1.5f * 150f));
        // 这里原本还有一行 ttsSpeedLabel("  当前：1.00×")，但上面那句已经写了
        // 同样的数值 —— 页面上于是出现「语速：当前 1.00×」紧接着「当前：1.00×」
        // 两行重复文案（截图可见）。现在改为直接刷新上面那一行。
        ttsSpeedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                float v = 0.5f + (pr / 150f) * 1.5f;
                Prefs.setTtsSpeed(MainActivity.this, v);
                if (ttsSpeedLabel != null) {
                    // 不带 emoji：UiFactory.label 在显示层统一剥离 emoji，
                    // 这里若补回去就会让这一行在拖动后突然冒出一个彩色 emoji。
                    ttsSpeedLabel.setText("语速：当前 "
                            + String.format("%.2f", v) + "×");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(ttsSpeedBar);

        addLabel(box, "\uD83D\uDCAC 少于多少字不念：当前 " + Prefs.ttsMinChars(this) + " 字"
                + "（避免「哦」「嗯」之类过于频繁地出声）");
        SeekBar ttsMinBar = UiFactory.seekBar(this, uiT());
        ttsMinBar.setMax(18);   // 1 ~ 19
        ttsMinBar.setProgress(Prefs.ttsMinChars(this) - 1);
        final TextView ttsMinLabel = new TextView(this);
        ttsMinLabel.setTextSize(15);
        ttsMinLabel.setText("  当前：" + Prefs.ttsMinChars(this) + " 字");
        box.addView(ttsMinLabel);
        ttsMinBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                Prefs.setTtsMinChars(MainActivity.this, pr + 1);
                ttsMinLabel.setText("  当前：" + Prefs.ttsMinChars(MainActivity.this) + " 字");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(ttsMinBar);

        addButton(box, "\u25B6\uFE0F 试听一句", new View.OnClickListener() {
            @Override public void onClick(View v) {
                testTts();
            }
        });

        addLabel(box, "\u2139\uFE0F 语音合成使用你配置的服务商与 Key，按服务商规则计费；"
                + "桌宠只在弹出气泡时朗读，不常驻联网、不后台偷跑。失败时只显示文字、不发声。");

        // ———— 语音识别资源（用户要求「把语音输入改为选择性下载资源」）————
        //
        // ★ 与上面「说话」**完全无关**，所以必须另开一个小节、并写清楚。
        //   说话 = 云端 TTS（上面那套）；这里管的是**听**（本地 ASR）。
        //   用户的原话是「不能影响宠物语音说话功能」—— 所以这一节
        //   要明确告诉用户"删了它只是听不了，说话照旧"。
        addSection(box, "\uD83C\uDF99 语音输入资源（可选）");

        final boolean _asrReady = SherpaAsr.isUsable(this);
        final long _asrBytes = SherpaAsr.installedBytes(this);
        addLabel(box, _asrReady
                ? "离线语音输入可用，当前占用 " + (_asrBytes / 1048576) + " MB。"
                : "离线语音输入资源**未安装**（约 229 MB）。安装后可以离线听你说话、"
                  + "不联网不上传；不装也不影响其它功能。");

        if (_asrReady) {
            addButton(box, "\uD83D\uDDD1 删除语音输入资源（释放 " + (_asrBytes / 1048576) + " MB）",
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    UiFactory.dialog(MainActivity.this)
                            .setTitle("删除语音输入资源")
                            .setMessage("将删除离线语音识别模型，释放约 "
                                    + (_asrBytes / 1048576) + " MB 空间。\n\n"
                                    + "★ 只影响「语音输入」（用嘴说话打字）。\n"
                                    + "桌宠**说话**用的是云端语音合成，删除后照常工作。\n"
                                    + "以后想用回来，点下面的「从安装包恢复」即可（不走网络）。")
                            .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dlg, int w) {
                                    SherpaAsr.deleteModel(MainActivity.this);
                                    Toast.makeText(MainActivity.this,
                                            "已释放 " + (_asrBytes / 1048576) + " MB",
                                            Toast.LENGTH_SHORT).show();
                                    showSettings();
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
        } else {
            // ★ 导入入口：大模型不再随包内置，而是作为独立文件随交付 zip 分发。
            //   用户从交付包里解出 sensevoice.int8.onnx，这里用系统文件选择器挑中，
            //   走 SherpaAsr.importModelFile 拷贝 + 校验 + 落盘。
            //   228MB 的拷贝要几十秒，放后台线程 + ProgressDialog，否则 ANR。
            //   恢复框的措辞与删除框刻意区分（「只管…」vs「只影响…」），
            //   否则反向验证 G 项的定位片段会出现两次被跳过。
            addButton(box, "\uD83D\uDCE5 从文件导入语音模型（约 229 MB）",
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    UiFactory.dialog(MainActivity.this)
                            .setTitle("从文件导入语音模型")
                            .setMessage("请先从交付压缩包里解出 sensevoice.int8.onnx"
                                    + "（约 229 MB），然后在这里选中它。\n\n"
                                    + "★ 只管「语音输入」（听你说话转成文字）。\n"
                                    + "桌宠**说话**用的是云端语音合成，不装也能正常说话。")
                            .setPositiveButton("选择文件", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dlg, int w) {
                                    try {
                                        Intent i = new Intent("android.intent.action.GET_CONTENT");
                                        i.setType("*/*");
                                        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                                        startActivityForResult(
                                                Intent.createChooser(i, "选择 sensevoice.int8.onnx"),
                                                REQ_PICK_ASR_MODEL);
                                    } catch (Exception e) {
                                        Toast.makeText(MainActivity.this,
                                                "无法打开文件选择器：" + e.getMessage(),
                                                Toast.LENGTH_SHORT).show();
                                    }
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
        }
        addLabel(box, "说明：语音输入（听）走本地离线模型，与语音朗读（说）是两套独立功能。"
                + "删掉这里的资源，桌宠依然会正常说话。");

        // ———— 展开菜单（三击桌宠弹出的动作菜单）布局微调 ————
        //
        // 归回「外观与显示」：它调的是菜单的内容大小/面板大小/内缩/偏移，
        // 全是外观参数。因为源码里它在「语音」之后，所以这里再开一次
        // section==0 的分支 —— 同一个条件出现两次是合法的，只是为了让
        // 代码物理顺序不用大搬家。代价是读代码时要跳着看，已在此注明。
        }
        // ———— 游戏助手：前台应用实时帧率（需 Shizuku） ————
        //
        // 归「陪伴」板块：用户要的是"桌宠陪着打游戏"。
        // 数据链路见 GameFpsMonitor；Shizuku 只是取数手段，
        // **没装 Shizuku 完全不影响桌宠本身**，这一项只是不可用。
        //
        // ★ 为什么整块用 if 包起来而不是灰掉：未装 Shizuku 时下面那些
        //   状态/读数/按钮全都没有意义，显示出来只会让用户以为"功能坏了"。
        if (settingsSection == 7) {
            addSection(box, "\uD83C\uDFAE 游戏助手");

            addLabel(box, "显示当前前台应用的实时帧率（FPS）。"
                    + "需要安装 Shizuku —— 一个免 root 的提权工具，"
                    + "装好并启动后本功能才能取到数据。");

            addToggle(box, "\uD83C\uDFAE 启用游戏助手", Prefs.gameAssistant(this),
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    Prefs.setGameAssistant(MainActivity.this, checked);
                    // ★ 通知常驻服务：桌宠身上的挂牌要跟着开关立刻出现/消失
                    //   （服务侧 ACTION_RELOAD → rebuildViews → fpsBadgeSync）
                    Prefs.reloadPet(MainActivity.this);
                    // 这一项决定下面整块内容显不显示，必须重建（不是"仅外观变化"）
                    showSettings();
                }
            });

            if (!Prefs.gameAssistant(this)) {
                addLabel(box, "打开上面的开关后，这里会显示 Shizuku 状态与实时帧率。");
            } else {
                ShizukuBridge.hook(this);

                final TextView stateTv = addLabelTv(box, "");
                // 存成字段：onResume 重启采样时要往这两行写（见字段注释里的真机 bug）
                gameFpsTv = addLabelTv(box, "");
                gameAppTv = addLabelTv(box, "");
                final TextView fpsTv = gameFpsTv;
                final TextView appTv = gameAppTv;

                // Shizuku 状态变化时刷新文案（切到 Shizuku 授权后回来要能看到变化）
                if (shizukuUi != null) ShizukuBridge.removeListener(shizukuUi);
                shizukuUi = new ShizukuBridge.Listener() {
                    @Override public void onStateChanged(ShizukuBridge.State s) {
                        renderShizukuState(stateTv, fpsTv);
                        // ★ 就绪后**直接**绑定并启动采样，不再靠 showSettings() 重建。
                        //   第一版是 showSettings()，但那样一旦重建没走到启动分支，
                        //   界面就永远停在"状态已就绪 / 读数说未就绪"的矛盾态
                        //   （真机实测出现过）。直接启动不依赖任何重建时序。
                        if (s == ShizukuBridge.State.READY) {
                            startGameFpsSampling(fpsTv, appTv);
                        }
                    }
                };
                ShizukuBridge.addListener(shizukuUi);

                // 立即判定一次（不能等回调：binder 早就到了的话不会再有事件）
                ShizukuBridge.refresh(this);
                renderShizukuState(stateTv, fpsTv);

                addButton(box, "\uD83D\uDD11 授权 / 修复连接", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onShizukuActionClick();
                    }
                });

                // ———— 监测范围：所有应用 / 仅选定的应用（★ 本轮把两者集成在一起）————
                //
                // 用户 2026-10-08 原话：「新增开关 全局打开或者部分应用打开，
                // 把这俩集成在一起去」。于是把"全局两态"（轮次⑦）与"应用名单"
                // （轮次⑥ 做过、后被撤）**合成一个入口**：一行「监测范围：…」
                // 点开二选，选"仅选定"时下面才出现勾选入口。
                // ⇒ 默认行为与轮次⑦ 完全一致（所有应用），需要精细控制时
                //   也不必去别的页面翻名单。判据本体在 SysMetrics.monitorVisible。
                addSection(box, "\uD83D\uDCCA 监测范围");
                addLabel(box, "CPU、内存、帧率这些数据是系统级的，一直在取；"
                        + "这里选的是监测条在哪些应用里显示。");
                final boolean monOnlySel = Prefs.monitorOnlySelected(this);
                final int monSelCount = Prefs.monitorAppCount(this);
                addButton(box, "\uD83C\uDF9A 监测范围："
                        + (monOnlySel ? "仅选定的 " + monSelCount + " 个应用" : "所有应用"),
                        new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        pickMonitorScope();
                    }
                });
                if (monOnlySel) {
                    addButton(box, "\u2705 选择应用（已选 " + monSelCount + " 个）",
                            new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            settingsSection = SECTION_MONITOR_APPS;
                            slideForward = true;    // 深一层：新页从右侧推入
                            showSettings();
                        }
                    });
                    // ★ 空名单必须**明说**，否则用户看到的是"开关都开着却不显示"，
                    //   只会以为是 bug（轮次⑥ 那次就是栽在这个语义没写出来）。
                    if (monSelCount == 0) {
                        addLabel(box, "名单现在是空的 —— 监测条不会在任何应用里显示。"
                                + "点上面那一行进勾选页挑几个。");
                    }
                }

                addToggle(box, "\uD83D\uDCCA 显示悬浮监测条（关闭 = 全部不显示）",
                        Prefs.monitorBar(this),
                        new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        Prefs.setMonitorBar(MainActivity.this, checked);
                        Prefs.reloadPet(MainActivity.this);   // 悬浮条立刻跟着变
                    }
                });

                // ———— 监测条离桌宠多远（★ 用户要求"可调节"）————
                //
                // 用户原话：「监测条距离桌宠太远，检测条改为就在桌宠旁边跟随桌宠位置
                // 实时移动」+「加一个可调节的选项」。
                // 跟随是代码侧改掉的（applyRootLayout → followMonitorSide，拖动每帧都跟）；
                // 距离则做成这里可调：允许**负值** —— 立绘画布四周有透明留白，
                // 调到 0 看着仍可能有点远，负值可以把它压进那圈留白里真正贴住。
                addLabel(box, "\uD83D\uDCCF 监测条离桌宠多远");
                final SeekBar gapBar = UiFactory.seekBar(this, uiT());
                final int gapSteps = (int) (SysMetrics.MONITOR_GAP_MAX_DP
                        - SysMetrics.MONITOR_GAP_MIN_DP);          // 36 档 = 1dp 一档
                gapBar.setMax(gapSteps);
                final TextView gapLabel = new TextView(this);
                gapLabel.setTextSize(15);
                gapLabel.setTextColor(uiT().text2);
                gapLabel.setText(gapText(Prefs.monitorGapDp(this)));
                gapBar.setProgress((int) (Prefs.monitorGapDp(this)
                        - SysMetrics.MONITOR_GAP_MIN_DP));
                // ★ 滑块必须真的进布局 —— 第一版漏了这句：界面上只有"当前：6 dp"
                //   一行小字、**没有滑块**（设备脚本按 SeekBar 找怎么也找不到；
                //   人眼也只会把它当成一句说明）。其它滑块都写 `box.addView(xxxBar)`。
                box.addView(gapBar);
                box.addView(gapLabel);       // 当前值放在滑块下面读起来更顺
                gapBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                        float dp = SysMetrics.clampMonitorGapDp(
                                SysMetrics.MONITOR_GAP_MIN_DP + pr);
                        Prefs.setMonitorGapDp(MainActivity.this, dp);
                        gapLabel.setText(gapText(dp));
                        // ★ 用 reposition 而不是 reload：拖动中每格都重建整套视图会又卡又闪
                        //   （见 Prefs.repositionPet 的说明）；这里再节流到 ≥90ms 一次。
                        if (!fromUser) return;
                        long now = System.currentTimeMillis();
                        if (now - lastGapApply < 90) return;
                        lastGapApply = now;
                        Prefs.repositionPet(MainActivity.this);
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {
                        // 松手补一次，保证最终值一定生效（节流可能吃掉了最后一格）
                        Prefs.repositionPet(MainActivity.this);
                    }
                });

                // ———— 监测条：上 / 中 / 下 三项自由选 ————
                addSection(box, "\uD83D\uDCCA 监测条显示项");
                addLabel(box, "八项指标里自由选三项。桌宠展开时挂在它左/右侧三行；"
                        + "吸附贴边成竖条时只显示「中」那一项。");
                addSlotRow(box, 0, "上");
                addSlotRow(box, 1, "中");
                addSlotRow(box, 2, "下");
                // 三格当前读数的实时预览（数据与悬浮条同源，便于对照）。
                // ★ 给一句**初始文案**：采样要等 Shizuku 就绪才有数，
                //   留空的话用户（和验证脚本）都看不出这一行存在过。
                monitorTv = addLabelTv(box, "监测条：（等待采样…）");

                // 实时采样：只在 Shizuku 就绪时才启动，否则空转没意义
                if (ShizukuBridge.state() == ShizukuBridge.State.READY) {
                    startGameFpsSampling(fpsTv, appTv);
                } else {
                    // 未就绪：只更新文案，**不 stop 采样** —— 悬浮窗可能仍在用
                    // （它自己会在没数据时显示占位）。
                    fpsTv.setText("实时帧率：—（" + ShizukuBridge.hint(this) + "）");
                    appTv.setText("前台应用：—");
                }

                addLabel(box, "说明：帧率取自系统 SurfaceFlinger 的图层统计"
                        + "（每秒采样两次，按帧计数差值算），是应用真实的渲染帧率，"
                        + "不是屏幕刷新率。画面完全静止时没有新帧，"
                        + "此时显示「静止」而不是 0。");

                // ★ 链路诊断入口：探针 Activity 不在启动器里，没有 adb 就启动不了它。
                //   放在这里，用户点一下就能看到"断在哪一环"并截图反馈。
                addButton(box, "\uD83D\uDD0D 打开链路诊断（排查用）", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            startActivity(new Intent(MainActivity.this, FpsProbeActivity.class));
                        } catch (Throwable t) {
                            Toast.makeText(MainActivity.this, "打不开诊断页：" + t,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }

        // ———— 第 8 节：选择监测应用（搜索 + 勾选）————
        //
        // 只能从第 7 节那行「选择应用 ›」进来。做成**整页**而不是弹窗：
        // 设备上装着的应用动辄上百个，弹窗里既列不下也不好搜。
        // ★ 勾选**不重建页面**（只就地改计数文案）—— 否则每点一个应用滚动位置
        //   就被重置回顶部，勾十几个要滚十几次（本工程在"点开关像重新加载"
        //   那一轮已经吃过这个亏）。
        if (settingsSection == SECTION_MONITOR_APPS) {
            addSection(box, "\uD83C\uDF9A 勾选要监测的应用");
            addLabel(box, "只有勾选过的应用在前台时，悬浮监测条才会显示。"
                    + "一个都不勾 = 哪里都不显示（不会自动变回全部）。");

            final EditText search = new EditText(this);
            search.setSingleLine(true);
            search.setHint("搜索应用名或包名");
            search.setTextSize(14);
            search.setTextColor(uiT().text1);
            search.setHintTextColor(uiT().text3);
            search.setBackground(UiKit.solidFill(this, uiT(), uiT().surfaceSunken, Tokens.R_CHIP));
            final int etPad = UiFactory.dp(this, 10);
            search.setPadding(etPad, etPad, etPad, etPad);

            final LinearLayout listBox = new LinearLayout(this);
            listBox.setOrientation(LinearLayout.VERTICAL);

            final TextView countTv = addLabelTv(box, "");
            final Runnable refresh = new Runnable() {
                @Override public void run() {
                    fillMonitorAppList(listBox, countTv, search.getText().toString());
                }
            };

            addButton(box, "\uD83D\uDDD1 清空已选", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.clearMonitorApps(MainActivity.this);
                    Prefs.reloadPet(MainActivity.this);
                    refresh.run();      // 就地重排列表，不整页重建
                }
            });

            search.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    refresh.run();
                }
            });

            box.addView(search);
            box.addView(listBox);
            refresh.run();
        }

        // ———— 第 9 节：纯本地 · 逐场景自定义发言（用户 2026-10-07 要求）————
        //
        // 场景表在 LocalLines（纯 Java，可离线断言）：点一下 / 被连戳 / 被拎起来 /
        // 双击 / 长按 / 贴边吸附 / 整点报时 / 主动搭话 / 纯本地对话回复。
        // 语义：**留空 = 用内置台词池**；填了就只从用户写的里面挑。
        if (settingsSection == SECTION_LOCAL_LINES) {
            addSection(box, "\uD83D\uDCDD 纯本地台词 · 逐场景");
            // ★ 轮次㊲：场景表改成与动作清单**一一对应**（用户要求「对话台词选项同步动作选项」）
            //   ⇒ 这段说明不再手抄场景名（抄了就一定会过期：这里原来还写着"双击 / 长按"，
            //   而那两个场景当时已被删掉）。文案里也不许出现 markdown 的星号（会原样显示）。
            addLabel(box, "纯本地模式下桌宠互动时会说的话。场景与「动作系统」里的一一对应（共 "
                    + LocalLines.IDS.length + " 个，顺序也一致），一行一句；某个场景留空，"
                    + "就用内置台词（漫游 / 转向 / 双击 / 长按 / 待机 / 轮播 / 节日 / 余额 /"
                    + "工作状态这几个本来不说话，留空就继续不说）。改完立即生效，不用重启。\n"
                    + "★ 打字发消息的入口（双击桌宠的输入条、聊天窗口）不读这里 —— 它们始终走 API。");
            for (final String scene : LocalLines.IDS) {
                addButton(box, "\uD83D\uDCAC " + LocalLines.label(scene) + "："
                        + LocalLines.summary(Prefs.localLines(this, scene)),
                        new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        editLocalLines(scene);
                    }
                });
            }
            addLabel(box, "每场景最多 40 句、每句最多 80 字；重复的句子会自动去重"
                    + "（同一句写两遍会让它出现概率翻倍，看起来像 bug）。");
        }

        if (settingsSection == 0) {
        addSection(box, "\uD83C\uDF9B\uFE0F 展开菜单微调");

        addLabel(box, "\uD83D\uDD0E 菜单内容大小（项/文字）");
        SeekBar menuContentBar = UiFactory.seekBar(this, uiT());
        menuContentBar.setMax(310); // 0.5 .. 16.0
        menuContentBar.setProgress((int) ((Prefs.getMenuContentScale(this) - Prefs.MENU_CONTENT_MIN)
                / (Prefs.MENU_CONTENT_MAX - Prefs.MENU_CONTENT_MIN) * 310f));
        final TextView menuContentLabel = new TextView(this);
        menuContentLabel.setTextSize(15);
        menuContentLabel.setText("当前：" + String.format("%.2f", Prefs.getMenuContentScale(this)) + "×");
        box.addView(menuContentLabel);
        menuContentBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                float v = Prefs.MENU_CONTENT_MIN + (pr / 310f) * (Prefs.MENU_CONTENT_MAX - Prefs.MENU_CONTENT_MIN);
                Prefs.saveMenuContentScale(MainActivity.this, v);
                menuContentLabel.setText("当前：" + String.format("%.2f", Prefs.getMenuContentScale(MainActivity.this)) + "×");
                if (fromUser) Prefs.reloadPet(MainActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(menuContentBar);

        addLabel(box, "\uD83D\uDD0E 背景底板大小（气泡整体）");
        SeekBar menuPanelBar = UiFactory.seekBar(this, uiT());
        menuPanelBar.setMax(397); // 0.03 .. 4.00
        menuPanelBar.setProgress((int) ((Prefs.getMenuPanelScale(this) - Prefs.MENU_PANEL_MIN)
                / (Prefs.MENU_PANEL_MAX - Prefs.MENU_PANEL_MIN) * 397f));
        final TextView menuPanelLabel = new TextView(this);
        menuPanelLabel.setTextSize(15);
        menuPanelLabel.setText("当前：" + String.format("%.3f", Prefs.getMenuPanelScale(this)) + "×");
        box.addView(menuPanelLabel);
        menuPanelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                float v = Prefs.MENU_PANEL_MIN + (pr / 397f) * (Prefs.MENU_PANEL_MAX - Prefs.MENU_PANEL_MIN);
                Prefs.saveMenuPanelScale(MainActivity.this, v);
                menuPanelLabel.setText("当前：" + String.format("%.3f", Prefs.getMenuPanelScale(MainActivity.this)) + "×");
                if (fromUser) Prefs.reloadPet(MainActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(menuPanelBar);

        addLabel(box, "\uD83D\uDCD0 菜单相对底板的内边距");
        SeekBar menuInsetBar = UiFactory.seekBar(this, uiT());
        menuInsetBar.setMax(60); // 0 .. 0.30
        menuInsetBar.setProgress((int) ((Prefs.getMenuInsetRatio(this) - Prefs.MENU_INSET_MIN)
                / (Prefs.MENU_INSET_MAX - Prefs.MENU_INSET_MIN) * 60f));
        final TextView menuInsetLabel = new TextView(this);
        menuInsetLabel.setTextSize(15);
        menuInsetLabel.setText("当前：" + String.format("%.0f", Prefs.getMenuInsetRatio(this) * 100) + "%");
        box.addView(menuInsetLabel);
        menuInsetBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                float v = Prefs.MENU_INSET_MIN + (pr / 60f) * (Prefs.MENU_INSET_MAX - Prefs.MENU_INSET_MIN);
                Prefs.saveMenuInsetRatio(MainActivity.this, v);
                menuInsetLabel.setText("当前：" + String.format("%.0f", Prefs.getMenuInsetRatio(MainActivity.this) * 100) + "%");
                if (fromUser) Prefs.reloadPet(MainActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(menuInsetBar);

        addLabel(box, "\u2B05\uFE0F\u27A1\uFE0F 水平位置偏移（左- 右+）");
        SeekBar menuOffXBar = UiFactory.seekBar(this, uiT());
        menuOffXBar.setMax(800); // -400 .. +400 dp
        menuOffXBar.setProgress(Prefs.getMenuOffsetX(this) + 400);
        final TextView menuOffXLabel = new TextView(this);
        menuOffXLabel.setTextSize(15);
        menuOffXLabel.setText("当前：" + Prefs.getMenuOffsetX(this) + " dp");
        box.addView(menuOffXLabel);
        menuOffXBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                int v = pr - 400;
                Prefs.saveMenuOffsetX(MainActivity.this, v);
                menuOffXLabel.setText("当前：" + Prefs.getMenuOffsetX(MainActivity.this) + " dp");
                if (fromUser) Prefs.reloadPet(MainActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(menuOffXBar);

        addLabel(box, "\u2B06\uFE0F\u2B07\uFE0F 垂直位置偏移（上- 下+）");
        SeekBar menuOffYBar = UiFactory.seekBar(this, uiT());
        menuOffYBar.setMax(800); // -400 .. +400 dp
        menuOffYBar.setProgress(Prefs.getMenuOffsetY(this) + 400);
        final TextView menuOffYLabel = new TextView(this);
        menuOffYLabel.setTextSize(15);
        menuOffYLabel.setText("当前：" + Prefs.getMenuOffsetY(this) + " dp");
        box.addView(menuOffYLabel);
        menuOffYBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                int v = pr - 400;
                Prefs.saveMenuOffsetY(MainActivity.this, v);
                menuOffYLabel.setText("当前：" + Prefs.getMenuOffsetY(MainActivity.this) + " dp");
                if (fromUser) Prefs.reloadPet(MainActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(menuOffYBar);

        // 用一个单元素数组握住行引用：开关回调收到的 CompoundButton 是
// **null**（UiFactory.switchView 传的是 l.onCheckedChanged(null, now)），
// 拿不到行；而直接写 `final View lockRow = addToggle(..., 回调里用 lockRow)`
// 是"初始化器里的自引用"，Java 不允许。数组是这里最直接的解法。
        final View[] lockRowRef = new View[1];
        lockRowRef[0] = addToggle(box, "🔒 锁定当前菜单参数（锁定后手动微调仍可查看）"
                        + (Prefs.menuLocked(this) ? "（已锁定）" : "（未锁定）"),
                Prefs.menuLocked(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setMenuLocked(MainActivity.this, checked);
                Toast.makeText(MainActivity.this, checked ? "已锁定菜单参数" : "已解除锁定", Toast.LENGTH_SHORT).show();
                // ★ 只改这一行的文案，**不整页重建**。
                // 标题里的「（已锁定）/（未锁定）」是这个开关自己的状态后缀，
                // 重建整页会让滚动位置、所有子控件都重来一遍 —— 用户看到的就是
                // 「点一下开关页面又重新加载」。就地改一个 TextView 就够了。
                UiFactory.setRowLabel(lockRowRef[0], "🔒 锁定当前菜单参数（锁定后手动微调仍可查看）"
                        + (checked ? "（已锁定）" : "（未锁定）"));
            }
        });

        addToggle(box, "打开设置时自动收起桌宠（避免挡住本页按钮）",
                Prefs.hidePetInApp(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setHidePetInApp(MainActivity.this, checked);
                // 立刻生效，不用等下次进出：开=现在收起，关=现在恢复
                if (checked) hidePetForApp(); else restorePetForApp();
            }
        });

        // ================= 形象与动作（续）：桌宠形象 =================
        //
        // v7.0 起：桌宠形象（形象来源：上传图片/GIF、Live2D、模型包、恢复内置）
        // 从「表情与素材」搬到「形象与动作」—— 它与外观、动作是同一件事：
        // **这只桌宠长什么样、会做什么**。
        }
        if (settingsSection == 10) {
        // ———— 角色（**唯一入口**，轮次㉛）————
        //
        // ★ 轮次㉛ 把三块入口**全部收进「角色」**（用户要求「删除所有形象导入接口，
        //   改为角色切换的模式」）：设置页上不再有独立的
        //   「形象来源 / 上传图片 / 导入桌宠模型 / 导入 Live2D / 启用 L2D / 恢复默认形象」
        //   与「导入角色包 / 从电脑导入 / 导出当前角色」——
        //   形象、动作、导入、导出、删除全都从这一行进「角色」里做。
        //
        // ★ 也不再有"内置角色"：一个都没导入过时列表是空的（用户要求「不留任何角色，只靠导入」），
        //   这里给一句引导。桌宠本身不会变成空白 —— 素材层对"没有角色"是**内置素材兜底**。
        addSection(box, "\uD83C\uDFAD 角色");
        final PetCharacter curChar = CharacterStore.current(this);
        final int charTotal = CharacterStore.list(this).size();
        addButton(box, "\uD83C\uDFAD " + CharPicker.currentRowLabel(curChar.displayName(), charTotal)
                        + " \u203A",
                new View.OnClickListener() {
            @Override public void onClick(View v) { showCharacterHub(); }
        });
        if (charTotal == 0) {
            addLabel(box, CharPicker.emptyHint());
        } else {
            addLabel(box, "切换 / 新增 / 添加角色包 / 形象 / 导出 / 删除，都在这一行里。"
                    + "每个角色有自己的一整套动作、形象、台词、表情、性格与场景绑定；"
                    + "记忆与 API Key 是全局的。");
        }

        // ———— 动作系统（v7.0）————
        //
        // 分工：**有哪些场景**由程序固定（见 SceneRegistry），
        // **每个场景挂哪些动作**由角色包决定（见 ActionSystem / anim/pack.json）。
        // 所以这里现在只做"看 + 开关"，逐场景自定义要等角色包导入。
        addSection(box, "\uD83C\uDFAC 动作系统");
        // ★ 轮次㉛：角色入口已经并到上面「🎭 角色」那一行（不再重复两处），
        //   这里只管"当前这个角色的动作怎么组织"。一个角色都没有时给引导而不是一堆空列表。
        if (charTotal == 0) {
            addLabel(box, "还没有角色，动作系统先空着 —— 上面点「🎭 角色」新增一个，"
                    + "或在那里「📥 添加角色包」导入一个。");
        } else {
        addToggle(box, "\uD83D\uDD01 待机时随机换动作", Prefs.animPoolEnabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setAnimPoolEnabled(MainActivity.this, checked);
                Prefs.reloadPet(MainActivity.this);
            }
        });
        addLabel(box, "关 = 待机只播那一个默认动作；开 = 从「待机随机池」里抽（默认关）。");
        addButton(box, "\uD83C\uDFAC 场景与已绑动作 ›", new View.OnClickListener() {
            @Override public void onClick(View v) { showSceneOverview(); }
        });
        addButton(box, "\uD83D\uDCDA 动作库（查看 / 预览全部动作）›", new View.OnClickListener() {
            @Override public void onClick(View v) { showClipLibrary(); }
        });
        addButton(box, "\u2795 添加动作（从相册多选）", new View.OnClickListener() {
            @Override public void onClick(View v) { pickActionImages(); }
        });
        addLabel(box, "不用电脑、不用 zip：从相册多选动图或静态图 → 选一个场景就生效。"
                + "图片名带「待机」「点击」「拖拽」这类前缀时会自动归到对应场景；"
                + "不带前缀的先只进动作库，你随时可以绑到任意场景。");
        addButton(box, "\uD83D\uDD04 全部恢复角色包默认", new View.OnClickListener() {
            @Override public void onClick(View v) { promptClearAllBindings(); }
        });
        addLabel(box, "点进一个场景可以：改绑动作（多选）· 调权重 · 预览 · 恢复角色包默认。"
                + "改动按角色保存、存在本机，角色包文件本身不会被改动。");
        }

        // ———— 贴边与挂边（轮次77）————
        //  用户原话：「让我的桌宠项目新增贴边挂边吸附功能」——所以它是**贴边吸附的一种形态**，
        //  不是另开一个功能：同一个 dockCollapse 入口按这里选的形态分流
        //  （FloatingService.dockCollapse → peekEnterFromDock / 压扁竖条老路径）。
        addSection(box, "\uD83E\uDEA9 贴边与挂边");
        addButton(box, (Prefs.edgeDockStyle(this) == Prefs.DOCK_STYLE_PEEK
                        ? "\uD83E\uDEA9 贴边形态：挂边露头"
                        : "\uD83D\uDCCC 贴边形态：压扁竖条") + " \u203A",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] opts = {"挂边露头：只露个头贴在边上", "压扁竖条：收成一条细条（老样子）"};
                UiFactory.dialog(MainActivity.this)
                        .setTitle("贴边形态")
                        .setItems(opts, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                Prefs.setEdgeDockStyle(MainActivity.this,
                                        which == 0 ? Prefs.DOCK_STYLE_PEEK
                                                   : Prefs.DOCK_STYLE_STRIP);
                                Prefs.reloadPet(MainActivity.this);
                                showSettings();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        addLabel(box, "拖到屏幕左/右边缘松手、或长按菜单点「贴边吸附」时，桌宠怎么藏起来：");
        addLabel(box, "· 挂边露头：身体挂到屏幕外，只留头肩贴在边上，会呼吸、隔一会儿再探一下；"
                + "点一下它抬头站直，长按照样出菜单，拖动它就是直接拉回屏内。");
        addLabel(box, "· 压扁竖条：老样子 —— 形象压成细条贴在边上，向内滑动拉出来。");

        // ★ 轮次77 追加（用户要求）：角度与深度让用户自己调
        final int[] leanOpts = {0, 6, 10, 12, 16, 20, 24, 28, 35};
        // ★ 轮次85：三档预设（只露头 / 半身 / 全身）—— 一键切，比分别拧两个滑块快
        addButton(box, "\uD83C\uDF9B 挂边预设：" + Prefs.peekPresetName(Prefs.matchPeekPreset(this))
                + "  \u203A", new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] names = new String[]{"只露头（探出来一点点）", "半身（默认）", "全身（露得多）"};
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("挂边预设")
                        .setItems(names, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dlg, int which) {
                                Prefs.applyPeekPreset(MainActivity.this, which);
                                Prefs.reloadPet(MainActivity.this);
                                showSettings();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        addButton(box, "\uD83D\uDCD0 挂边角度：" + Prefs.peekLeanDeg(this) + "°  \u203A",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] base = new String[leanOpts.length];
                for (int i = 0; i < leanOpts.length; i++) {
                    base[i] = leanOpts[i] + "°" + (leanOpts[i] == 0 ? "（不歪，直着探头）" : "");
                }
                // ★ 轮次77 收尾：第一项永远是"恢复默认" —— 调乱了自己能一键回去
                final String[] items = new String[base.length + 1];
                items[0] = "恢复默认（16°）";
                System.arraycopy(base, 0, items, 1, base.length);
                UiFactory.dialog(MainActivity.this)
                        .setTitle("挂边角度")
                        .setItems(items, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                Prefs.setPeekLeanDeg(MainActivity.this,
                                        which == 0 ? 16 : leanOpts[which - 1]);
                                Prefs.reloadPet(MainActivity.this);
                                showSettings();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        addLabel(box, "挂边时歪多少。0° = 直着探头，越大歪得越明显（建议 12~24°）。");

        final int[] depthOpts = {20, 30, 38, 45, 55, 65, 75};
        addButton(box, "\uD83D\uDCCF 挂出深度：" + (100 - Prefs.peekKeepPercent(this)) + "%  \u203A",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] base = new String[depthOpts.length];
                for (int i = 0; i < depthOpts.length; i++) {
                    base[i] = "挂出 " + depthOpts[i] + "%（留 " + (100 - depthOpts[i]) + "% 在屏内）";
                }
                final String[] items = new String[base.length + 1];
                items[0] = "恢复默认（挂出 38%）";
                System.arraycopy(base, 0, items, 1, base.length);
                UiFactory.dialog(MainActivity.this)
                        .setTitle("挂出深度")
                        .setItems(items, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                Prefs.setPeekKeepPercent(MainActivity.this,
                                        which == 0 ? 62 : 100 - depthOpts[which - 1]);
                                Prefs.reloadPet(MainActivity.this);
                                showSettings();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        addLabel(box, "身体有多大比例挂到屏幕外。越大藏得越深（露得越少）；"
                + "越小越容易看到整个身体。想「只露个头」就往 55%~65% 调。");

        // ———— 漫游（v7.0 恢复；用户要求默认开 + 可关）————
        addSection(box, "\uD83C\uDF0A 漫游");
        addToggle(box, "\uD83D\uDEB6 自动漫游", Prefs.wanderEnabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setWanderEnabled(MainActivity.this, checked);
                Prefs.reloadPet(MainActivity.this);
            }
        });
        addLabel(box, "桌宠会自己在屏幕上来回走。你一碰它就停下；"
                + "走到屏幕边缘、或静止一段时间后自动贴边成竖条。");
        addToggle(box, "\uD83D\uDEA7 漫游前先避开屏幕顶部 1/3", Prefs.wanderAvoidTop(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setWanderAvoidTop(MainActivity.this, checked);
                Prefs.reloadPet(MainActivity.this);
            }
        });
        addLabel(box, "顶部 1/3 是状态栏与小组件密集区，走过去容易挡住信息。"
                + "★ 默认关：漫游就在桌宠当前位置开始，x/y 都不做前置位移。"
                + "打开后，漫游一开始会先把桌宠从顶部 1/3 滑下来再走（也就是它会先动一下）。");
        addToggle(box, "\uD83D\uDCCC 走到屏幕边缘时自动贴边", Prefs.wanderAutoDock(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setWanderAutoDock(MainActivity.this, checked);
                Prefs.reloadPet(MainActivity.this);
            }
        });
        // ★ 轮次㉛：这里原来有「📥 导入角色包 / 💻 从电脑导入 / 📤 导出当前角色」三行 ——
        //   用户要求"删除当前所有的角色导入的接口"，它们**全部收进「🎭 角色」**里了
        //   （见 showCharacterHub）。设置页不再有第二条导入/导出的路。
        addButton(box, "\uD83D\uDDC2 自定义动作菜单 \u203A", new View.OnClickListener() {
            @Override public void onClick(View v) { showMenuCategoriesEditor(); }
        });
        addLabel(box, "给动作菜单自建分类（如「我的最爱」）并往里挑动作；"
                + "自定义分类排在菜单最前面，开关和句子都按你分的来。");

        final String[] speedNames = {"慢", "标准", "快"};
        addButton(box, "\u26A1 漫游速度："
                + speedNames[Math.max(0, Math.min(2, Prefs.wanderSpeed(this)))],
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] opts = {"慢", "标准", "快"};
                UiFactory.dialog(MainActivity.this)
                        .setTitle("漫游速度")
                        .setItems(opts, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                Prefs.setWanderSpeed(MainActivity.this, which);
                                Prefs.reloadPet(MainActivity.this);
                                showSettings();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        // ★ 轮次㊻：漫游的**前摇 / 后摇**（用户原话：「动画播放逻辑不对 —— 在漫游动作里面加一个
        //   漫游开始的延迟还有结束的延迟，用来控制动画前后摇的逻辑」）。
        //   ★ 轮次㊼：档位加密（用户要求"前后摇时间给的选项多一点"）——0.2 秒一档到 1 秒，
        //   之后 0.2/0.5 秒一档一直到 4 秒；0 秒 = 关掉。
        addButton(box, "\u23F1 漫游前摇（全局）：" + secText(Prefs.wanderLeadMs(this))
                + "（动画先播这么久才开始走）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickDelayMs("漫游前摇（全局）", Prefs.wanderLeadMs(MainActivity.this),
                        new DelayPick() {
                    @Override public void onPick(int ms) {
                        Prefs.setWanderLeadMs(MainActivity.this, Math.max(0, ms));
                        Prefs.reloadPet(MainActivity.this);
                        showSettings();
                    }
                });
            }
        });
        addButton(box, "\u23F1 漫游后摇（全局）：" + secText(Prefs.wanderTailMs(this))
                + "（人停住后动画再收尾这么久）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickDelayMs("漫游后摇（全局）", Prefs.wanderTailMs(MainActivity.this),
                        new DelayPick() {
                    @Override public void onPick(int ms) {
                        Prefs.setWanderTailMs(MainActivity.this, Math.max(0, ms));
                        Prefs.reloadPet(MainActivity.this);
                        showSettings();
                    }
                });
            }
        });
        // ★ 轮次㊼：**按动作**单独设（用户要求「可以单独设置每个动作的前后摇，
        //   用来适配不同角色不同动画的动作」）—— 按角色隔离，见 RoamTiming。
        addButton(box, "\uD83C\uDFAC 每个动作的前后摇…（已单独设 "
                + RoamTiming.count(this, CharacterStore.currentId(this)) + " 个）",
                new View.OnClickListener() {
            @Override public void onClick(View v) { showRoamTimingPicker(); }
        });
        addLabel(box, "全局前后摇对所有漫游动作生效；某个动作的「蓄力 / 收尾」明显不一样时，"
                + "在上一行单独给它设一份（按角色保存，换角色各自独立）。");

        // ———— 维护 ————
        //
        // ★ 轮次㉛：这是原来「桌宠形象」块里唯一的**非导入**入口，所以它没跟着那一块被删 ——
        //   v6.x 的旧素材（custom_webp + 两份 custom json）不在新角色包里，
        //   升级上来的用户需要一个能释放空间的地方（不然只能"清除应用数据"，那会连角色一起没）。
        addSection(box, "\uD83E\uDDF9 维护");
        addButton(box, "\uD83E\uDDF9 清理旧版素材（释放空间）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                final int n = Prefs.legacyAssetCount(MainActivity.this);
                UiFactory.dialog(MainActivity.this)
                        .setTitle("清理旧版素材")
                        .setMessage("v6.x 的「导入动画素材」留在本机的文件：" + n + " 个。\n\n"
                                + "新角色包系统不再读取它们（会删掉这些文件）。\n"
                                + "角色、动作、形象都不受影响 —— 只有旧格式的残留会被清掉。")
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                int k = Prefs.clearLegacyAssets(MainActivity.this);
                                Toast.makeText(MainActivity.this,
                                        "已删除 " + k + " 个旧素材文件", Toast.LENGTH_SHORT).show();
                                showSettings();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        }
        // ==================== 工作模式（独立二级页）====================
        if (settingsSection == SECTION_WORK_MODE) {
            addSection(box, "工作模式");
            // ★ 总开关：关了之后顶部的切换入口也进不去（用户要求）。
            box.addView(UiFactory.toggleRow(this, T, "启用工作模式（关掉后进不去）",
                    com.deepseek.pet.dsh.DshBridge.workEnabled(this),
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    com.deepseek.pet.dsh.DshBridge.setWorkEnabled(MainActivity.this, on);
                    Toast.makeText(MainActivity.this,
                            on ? "已启用工作模式" : "已关闭：顶部切换入口也进不去了",
                            Toast.LENGTH_SHORT).show();
                }
            }));
            addButton(box, "🔗 Harness 地址 / 配对（连电脑或自建服务器）›", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, DshHarnessActivity.class));
                }
            });
            addButton(box, "📱 手机文件访问（查看 / 管理权限）",
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    final boolean ok = android.os.Build.VERSION.SDK_INT < 30
                            || android.os.Environment.isExternalStorageManager();
                    final String path = "/storage/emulated/0";
                    UiFactory.dialog(MainActivity.this)
                            .setTitle("手机文件访问")
                            .setMessage((ok ? "已允许所有文件访问" : "尚未授权：现在只能用 App 内工作区")
                                    + "\n\n项目位置由 Harness 选择，客户端不再限定单个文件夹。"
                                    + "授权后可在 Harness 中浏览、创建和切换手机公共目录中的项目。\n\n"
                                    + "手机内部存储：" + path + "\n"
                                    + "在 Harness 里的路径：/sdcard（也是家目录里的 storage/）")
                            .setPositiveButton("管理文件权限", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    try {
                                        startActivity(new Intent(
                                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                android.net.Uri.parse("package:" + getPackageName())));
                                    } catch (Throwable t) {
                                        try {
                                            startActivity(new Intent(
                                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                        } catch (Throwable e2) {
                                            Toast.makeText(MainActivity.this, "打不开授权页", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                }
                            })
                            .setNeutralButton("复制存储路径", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    try {
                                        android.content.ClipboardManager cm =
                                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
                                        Toast.makeText(MainActivity.this, "已复制：" + path, Toast.LENGTH_SHORT).show();
                                    } catch (Throwable ignore) { }
                                }
                            })
                            .setNegativeButton("关闭", null)
                            .show();
                }
            });

            // ★ 用户要求：工作模式要能**请求访问公共目录**。
            addButton(box, "📁 申请「访问所有文件」权限\uff08能读写手机全部目录\uff09",
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
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
                        } catch (Throwable e2) {
                            Toast.makeText(MainActivity.this, "打不开系统授权页：" + e2.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                        }
                    }
                }
            });
            // ★ root 开关（默认关）：打开后宿主以 uid=0 跑，访客里能看到 /data、/system、/storage。
            box.addView(UiFactory.toggleRow(this, T, "🔑 以 root 运行宿主（能访问全部文件夹）",
                    com.deepseek.pet.dsh.DshBridge.rootHostEnabled(this),
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    // 先保存新身份，重启时才能按新身份回收旧进程。
                    com.deepseek.pet.dsh.DshBridge.setRootHostEnabled(MainActivity.this, on);
                    com.deepseek.pet.dsh.DshEngine.restart(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                            on ? "已开启：正在重启宿主，请允许 root 授权；失败会自动退回普通模式"
                               : "已关闭：宿主正在以普通权限重启",
                            Toast.LENGTH_LONG).show();
                }
            }));

            addSection(box, "工作区（手机存储）");
            final boolean extOk = new java.io.File("/storage/emulated/0/DeepSeekHarness").isDirectory();
            addLabel(box, "工作区路径："
                    + (extOk ? "/sdcard/DeepSeekHarness（手机存储）"
                             : "/sdcard/DeepSeekHarness（未授权，暂时用 App 私有目录）") + "\n"
                    + "手机上怎么找：文件管理器 → 内部存储 → DeepSeekHarness。"
                    + "你在工作模式里选的就是这个目录，"
                    + "从文件管理器往里放文件、从工作模式里也能看到。");

            addSection(box, "本机 Linux（已并进这个 App）");
            final TextView engState = new TextView(this);
            engState.setTextSize(12f);
            engState.setTextColor(T.text3);
            int pad8 = (int) (4 * getResources().getDisplayMetrics().density);
            int pad16 = (int) (8 * getResources().getDisplayMetrics().density);
            engState.setPadding(0, pad8, 0, pad16);
            engState.setText(com.deepseek.pet.dsh.DshEngine.installed(this)
                    ? "Linux 环境：已就绪" : "Linux 环境：还没装（进工作模式会自动装）");
            box.addView(engState);
            addButton(box, "▶ 启动 / 重启本机 Linux 宿主", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    com.deepseek.pet.dsh.DshEngine.start(MainActivity.this);
                    engState.setText("已请求启动，进工作模式能看到进度");
                }
            });
            addButton(box, "■ 停止本机 Linux 宿主（省内存）", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    com.deepseek.pet.dsh.DshEngine.stop(MainActivity.this);
                    engState.setText("已停止");
                }
            });
            addLabel(box, "• harness 版本在 工作模式 ⚙ → 高级选项 →「Harness 版本」里切换（填那版的 .tgz 地址）。\n"
                    + "• 工作模式会保留后台卡片，切出去可以从最近任务点回来；陪伴模式不显示卡片。");
        }

        if (settingsSection == 1) {
        // ================= ② 对话与人格 =================
        addSection(box, "\uD83D\uDCAC 对话与人格");

        addToggle(box, "\uD83D\uDCAC 对话功能（双击桌宠打开输入框）", Prefs.chatEnabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setChatEnabled(MainActivity.this, checked);
                Toast.makeText(MainActivity.this,
                        checked ? "已开启：双击桌宠即可聊天" : "已关闭：双击桌宠不再打开输入框",
                        Toast.LENGTH_SHORT).show();
                // 不重建：本页无行依赖此值
            }
        });

        // ★★ 第十三版（用户：「工作模式的设置独立出来 新增陪伴-工作模式」）
        //   这里只留**两行**：一行切模式，一行进独立设置页。
        //   其余（权限 / 外观 / 字号 / 发送行为 / 切换动画 / 工作区 / 模型 / 插件）全在 DshSettingsActivity 里。
        if (com.deepseek.pet.dsh.DshFeature.ENABLED) {
            addSection(box, "工作模式");

            // ★ 用户要求：一级菜单「陪伴」里有工作模式，且**把工作模式的选项全部搬进去**。
            //   这里只留开关 + 一个入口，具体选项都在 SECTION_WORK_MODE 那页。
            final boolean workNow2 = com.deepseek.pet.dsh.DshBridge.isWorkMode(this);
            box.addView(UiFactory.toggleRow(this, T, "🐾 工作模式（开 = Harness 工作台）", workNow2,
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    boolean now = com.deepseek.pet.dsh.DshBridge.isWorkMode(MainActivity.this);
                    if (on == now) return;
                    if (!com.deepseek.pet.dsh.DshBridge.enabled(MainActivity.this)) {
                        com.deepseek.pet.dsh.DshBridge.setEnabled(MainActivity.this, true);
                    }
                    enteringWorkMode = on;
                    DshModeEntry.switchMode(MainActivity.this, T, null);
                }
            }));
            addButton(box, "🛠 工作模式设置 ›（引擎 / 版本 / 地址）", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    settingsSection = SECTION_WORK_MODE;
                    settingsTab = tabOfSection(SECTION_WORK_MODE);
                    slideForward = true;
                    showSettings();
                }
            });
            addLabel(box, "陪伴模式 = 桌宠聊天（原来的样子）；工作模式 = DeepSeek Harness 工作台。\n"
                    + "两边共用同一份会话、权限与桌宠设置；工作模式里桌宠也会在。");

            addButton(box, "🔗 Harness 地址 / 配对 ›", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, DshHarnessActivity.class));
                }
            });
            addLabel(box, "权限 / 外观 / 字号 / 发送行为 / 切换动画 / 工作区 / 模型 / 插件，"
                    + "全在那一页里。");
        }

        addToggle(box, Prefs.webSearchLabel(), Prefs.webSearch(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setWebSearch(MainActivity.this, checked);
                Toast.makeText(MainActivity.this,
                        checked ? "已开启：问最新的事时，桌宠会先联网搜一次再回答"
                                : "已关闭：不再联网检索",
                        Toast.LENGTH_SHORT).show();
                // 不重建：本页无行依赖此值（说明文字是静态的）
            }
        });
        // ★ 说明写清楚三件事（用户 2026-10-07 定的：联网默认关；失败要如实说）：
        //   什么时候会搜 / 会不会额外花钱 / 搜不到会怎样。
        //   ★ 给用户看的文字里**不许出现 markdown 的 `**`**（对话框/说明都是纯文本，星号会原样显示）。
        addLabel(box, "开着时，聊天框和「问一句」里带「要新信息」的问题（最新 / 今天 / 多少钱 / "
                + "是什么 / 谁是…）会先由 App 自己搜一次网，再把资料交给模型回答。"
                + "寒暄闲聊不会搜。\n"
                + "· 免 Key：搜索直接用必应，不需要额外的搜索服务；\n"
                + "· 天气这类问题走中国天气网的结构化数据（直接给温度，不是网页摘要）；\n"
                + "· 问天气时说一句城市（比如「北京天气」）最准 —— 没说会先接着用你上次问的城市，\n"
                + "  再没有就反问你一句是哪个城市；\n"
                + "· 会多花一点流量与 token（资料会拼进这次提问）；\n"
                + "· 搜不到就如实说没查到，不会编造来源。");

        // ★ 轮次㉘：**搜索服务可插拔**（用户要求"任何 API 接进来都能搜"）。
        //   默认还是免费的必应；这里只是换一家、填 Key、或贴自定义模板。
        addButton(box, Prefs.searchProviderLabel() + "："
                + searchProviderLabelNow() + " \u203A", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSearchProviderDialog();
            }
        });
        addLabel(box, "换一家搜索服务：默认是内置必应（免费，摘要为主）。"
                + "换成别的（Kimi / 自建 SearXNG / 你自己的搜索 API）时，按它的接口填 Key 即可，"
                + "路径与地址都写在模板里；它万一失败会自动退回必应，并在资料里说明。\n"
                + "自定义服务：贴一段 JSON 就能接任意搜索 API —— 地址里用 {q} 放关键词、{key} 放密钥，"
                + "再告诉 App 结果在哪个路径（title / url / snippet / content 各填一个路径）。");

        addToggle(box, "\uD83D\uDDE3\uFE0F 主动聊天（桌宠时不时主动找你聊）", Prefs.proactiveChat(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setProactiveChat(MainActivity.this, checked);
                Toast.makeText(MainActivity.this,
                        checked ? "已开启：桌宠会时不时主动找你聊" : "已关闭：桌宠不再主动开口",
                        Toast.LENGTH_SHORT).show();
                Intent i = new Intent(MainActivity.this, FloatingService.class);
                i.setAction("com.deepseek.pet.RELOAD");
                startService(i);
                // 不重建：本页无行依赖此值
            }
        });

        addButton(box, "\u23F1\uFE0F 主动聊天间隔：" + Prefs.getProactiveMinutes(this) + " 分钟",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showProactiveIntervalDialog();
            }
        });

        addToggle(box, "\uD83D\uDC40 场景搭话（识别主人正在用的应用，顺势聊一句）", Prefs.moodWatch(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setMoodWatch(MainActivity.this, checked);
                if (checked) ensureUsageAccess();
                Toast.makeText(MainActivity.this,
                        checked ? "已开启：桌宠会看你正在干嘛，偶尔搭一句" : "已关闭：不再识别你在用什么应用",
                        Toast.LENGTH_SHORT).show();
                Intent i = new Intent(MainActivity.this, FloatingService.class);
                i.setAction("com.deepseek.pet.RELOAD");
                startService(i);
                // 不重建：本页无行依赖此值（"场景搭话间隔"始终显示）
            }
        });

        addButton(box, "\u23F1\uFE0F 场景搭话间隔：" + secLabel(Prefs.getMoodCooldownSec(this)),
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMoodCooldownDialog();
            }
        });

        addToggle(box, "\u23F0 整点报时（每到整点提醒一次现在几点）", Prefs.hourChimeEnabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setHourChimeEnabled(MainActivity.this, checked);
                Toast.makeText(MainActivity.this,
                        checked ? "已开启：每到整点会报一次时" : "已关闭：不再整点报时",
                        Toast.LENGTH_SHORT).show();
                // 不重建：本页无行依赖此值
            }
        });

        addToggle(box, "\uD83C\uDF8F 时节动作（节日/节气/时段专属动画）", Prefs.seasonalAnimEnabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setSeasonalAnimEnabled(MainActivity.this, checked);
                Toast.makeText(MainActivity.this,
                        checked ? "已开启：待机时会挑应景动画播" : "已关闭：不再自动播时节动作",
                        Toast.LENGTH_SHORT).show();
                // 不重建：本页无行依赖此值
            }
        });

        addButton(box, "\u23F3 久留阈值（停留多久算待久了）：" + secLabel(Prefs.getMoodStaySec(this)),
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMoodStayDialog();
            }
        });

        addButton(box, "\uD83E\uDD10 戳过桌宠后多久不主动：" + (Prefs.getQuietSec(this) == 0 ? "不设门槛" : secLabel(Prefs.getQuietSec(this))),
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showQuietDialog();
            }
        });

        addSection(box, "\u23F1\uFE0F 互动冷却与用量提醒");

        // ★ 用户 2026-10-07 要求："做成开关，关闭就是纯本地，打开就是调用api"。
        //   它和下面的「互动对话冷却」写**同一个 pref**（-1 = 纯本地），
        //   开关只负责一键切换，冷却那一行仍可细调；打开时恢复上次的非纯本地值。
        addToggle(box, "\uD83D\uDCAC 互动时桌宠发言（关 = 纯本地台词，开 = 调用 API）",
                Prefs.interactSpeakEnabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setInteractSpeakEnabled(MainActivity.this, checked);
                Prefs.reloadPet(MainActivity.this);
                // 本行下面的「互动对话冷却」文案会跟着变（纯本地/冷却值），要重建
                showSettings();
            }
        });

        addButton(box, "\uD83D\uDC46 互动对话冷却：" + Prefs.interactCdLabel(this),
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showInteractCdDialog();
            }
        });

        // ★ 纯本地 · 各场景自定义发言（用户 2026-10-07 要求）
        addButton(box, "\uD83D\uDCDD 纯本地台词：逐场景自定义\u2026",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                settingsSection = SECTION_LOCAL_LINES;
                slideForward = true;
                showSettings();
            }
        });

        addLabel(box, "冷却期内戳桌宠依然会蹦跳回应，只是不再向 API 发问，避免连点把额度刷掉。"
                + "关掉上面的开关（或选「纯本地」）后：**互动**（点 / 拖 / 双击 / 长按 / 贴边 /"
                + "整点 / 主动搭话）只念本地台词，一次 API 都不会调用。\n"
                + "★ 范围说明：**凡是打字发消息的入口都只走 API** —— 双击桌宠弹出的输入条、"
                + "以及完整对话窗口（聊天页），它们要的是能回答问题的模型，用本地台词顶替没有意义。");

        addLabel(box, "余额气泡：用 DeepSeek 时显示「余额 + 今日花费 + 缓存命中率」；"
                + "切换到其他服务商时**只显示调用次数与 token 用量** —— "
                + "因为别家的定价与缓存字段口径不同，显示出来会不准。\n"
                + "各家的 API Key 分别保存，切换服务商时自动使用该家已存的 Key。");

        addToggle(box, "\uD83D\uDCB0 余额不足时让桌宠提醒", Prefs.balanceNotify(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setBalanceNotify(MainActivity.this, checked);
                reloadService();
                // 不重建：本页无行依赖此值（同上）
            }
        });

        addToggle(box, "\uD83D\uDCCA 用量增长时让桌宠播报（DeepSeek 下含缓存命中率）", Prefs.usageNotify(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setUsageNotify(MainActivity.this, checked);
                reloadService();
                // 不重建：本页无行依赖此值
            }
        });

        addSection(box, "\uD83D\uDCCA\uFE0F 官网用量详情");

        addButton(box, "\uD83D\uDD17 " + (PlatformClient.isLoggedIn(this)
                        ? "查看官网用量（已登录）" : "登录开放平台 · 查看真实用量"),
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, UsageActivity.class));
            }
        });

        addLabel(box, "API Key 只能调对话与余额接口，拿不到「按天 / 按模型」的明细用量。"
                + "登录开放平台后即可读取官网真实统计：今日 / 近 7 天 / 近 30 天的 token、"
                + "缓存命中率、按模型与按 Key 的分布和真实花费，用来核对桌宠的本地估算。\n"
                + "登录在官方页面内完成，本应用不读取账号密码；凭证只存本机、只发往官方域名。");

        addButton(box, "\u26A1 当前计费时段：" + Pricing.tag() + " · " + Pricing.label()
                        + "（" + Pricing.priceHint() + "）",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showBillingDialog();
            }
        });

        addButton(box, "\uD83C\uDFAD 性格预设：" + Prefs.currentPresetName(this), new View.OnClickListener() {
            @Override public void onClick(View v) {
                showPersonaDialog();
            }
        });

        addButton(box, "\uD83C\uDF10 服务商：" + chatProviderSummary(), new View.OnClickListener() {
            @Override public void onClick(View v) {
                showChatProviderDialog();
            }
        });

        // ★★ 轮次64：选了「本地模型」时这把 Key 是**不需要**的 ——
        //    原来固定显示「未保存」，会让用户以为"还差一步没配好"。
        addButton(box, "\uD83D\uDD11 修改 API Key（"
                + (ChatRouter.isLocal(this)
                   ? "本地模型不需要"
                   : (Prefs.hasApiKeyFor(this, Prefs.getChatProvider(this))
                      ? "已保存" : "未保存")) + "）",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showApiKeyDialog();
            }
        });

        addButton(box, "\uD83D\uDD17 API 地址：" + ApiClient.getBaseUrl()
                + (Endpoint.suspicious(ApiClient.getBaseUrl()) ? "  \u26A0 可能是设备自己" : ""),
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showBaseUrlDialog();
            }
        });

        // ★ 轮次56：把"到底通不通"做成一个能点的入口（原来是"获取模型失败"一句话，
        //   连异常原文都看不到 —— 用户报的"自定义 API 用不了"就卡在这一步）。
        addButton(box, "\uD83E\uDDEA 测试连接（试一次 /models，把真实报错显示出来）",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                runConnectionTest();
            }
        });

        // 参数兼容提示：Kimi 这类"锁死参数"的服务商，用户需要知道
        // "我们自动少发了参数"，否则会以为功能被阉割了。
        addLabel(box, ChatProvider.describe(
                ChatProvider.find(Prefs.getChatProvider(this)),
                Prefs.getChatModel(this)));
        // ★★ 轮次64：选了「本地模型」时，这一行必须**当面**说清"模型导没导"——
        //    没导入就对话，是本功能最容易踩的空：用户会以为"选了就能用"。
        if (ChatRouter.isLocal(this)) {
            addLabel(box, ModelStore.current(this) == null
                    ? "· \u26A0 已选本地模型，但还没导入 —— 点下面「\uD83E\uDD16 本地模型」"
                      + "→「\uD83D\uDCE5 导入 .gguf 文件…」选一个 0.5B~3B 的量化模型"
                    : "· \u2705 本地模型已就绪：" + localModelSummary() + "（不联网、不需要 Key）");
        }
        if ("custom".equals(Prefs.getChatProvider(this))) {
            // ★ 轮次56：标签写明"只发标准字段" —— 这个开关以前连 DeepSeek 专有的
            //   thinking / reasoning_effort 也一起发，多数第三方网关遇到未知字段直接 400。
            addToggle(box, "\u2699 自定义服务：强制发送温度/惩罚参数（只发标准字段）",
                    Prefs.chatForceParams(this),
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    Prefs.setChatForceParams(MainActivity.this, checked);
                    Toast.makeText(MainActivity.this,
                            checked ? "已强制发送（若服务商不支持会报 400）"
                                    : "已改为按需发送（更兼容）",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ★★ 轮次64：本地后端下「模型」指的就是本机那个 .gguf ——
        //    点它应该去「🤖 本地模型」那页，而不是去拉在线 /models 列表
        //    （本地必没有 Key，拉列表只会报错）。
        addButton(box, "\uD83E\uDD16 模型：" + (ChatRouter.isLocal(this)
                        ? "本机 · " + localModelSummary() : Prefs.getChatModel(this)),
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ChatRouter.isLocal(MainActivity.this)) showLocalModelPanel();
                else showModelDialog();
            }
        });

        addButton(box, "\uD83E\uDDE0 推理强度：" + effortLabel(Prefs.getReasoningEffort(this)), new View.OnClickListener() {
            @Override public void onClick(View v) {
                showEffortDialog();
            }
        });

        // ================= 🤖 本地模型（轮次62 第二轮 · 导入层）=================
        //
        // ★ 为什么放在「对话与人格」这一页的**最后一组**（而不是新开一个二级页）：
        //   ① 本地模型是**对话后端**的另一种：它跟上面的「服务商 / API Key / 模型 / 推理强度」
        //      回答的是同一个问题 —— "这句话由谁来想"。放在同一页，用户一眼能看出两者是二选一；
        //      真按"下载"或"设置"分类另开一页，反而要在两个页面之间来回跳。
        //   ② 不新开二级页 = 不动 `_GRP_NAMES` / `TAB_SECTIONS` / 页头标题那套编号
        //      （那一套的编号散在 showSettings 里几十处，重排的风险远大于收益 ——
        //      这一点 STATE.md 里早有记录）。
        //   ③ 与既有的「服务商 ›」「搜索服务 ›」同构：一行值 + › 打开操作面板，
        //      用户不用学新交互。
        // ★★★ 轮次75：本地模型入口**下线**（用户原话「先去掉本地模型部署这个接口
        //    保留项目内容后续再加上」）⇒ 这一组不再绘制。
        //    ★ 面板方法 `showLocalModelPanel()` 与它下面的一切都**原样保留**，
        //      把 LocalModelFeature.ENABLED 改成 true 就恢复。
        if (LocalModelFeature.ENABLED) {
        addSection(box, "\uD83E\uDD16 本地模型（手机本机跑，不联网）");

        addButton(box, "\uD83E\uDD16 本地模型：" + localModelSummary() + " \u203A",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showLocalModelPanel();
            }
        });

        addLabel(box, "让桌宠在这一台手机里、不联网把话说出来：自己挑一个小参数 GGUF 模型"
                + "（0.5B~3B 的量化版，常见 0.4~2 GB）导进来，之后就是纯本机推理 —— "
                + "不走 API、不花 token、断网也能用、你说的话不出这台手机。\n"
                + "· 模型不随安装包发（那是几百 MB），得你自己从文件管理器里挑一个 .gguf；\n"
                + "· App 先看文件头是不是 GGUF（前 4 个字节），不是就当场拒绝，"
                + "不会把一个压缩包/视频当成模型收下；\n"
                + "· 文件放在 App 私有目录，卸载 App 会一起清掉，不会留在手机别的地方；\n"
                + "· 小模型比云端模型笨、也慢一些（手机上大概几到十几 tok/s），"
                + "胜在免费、离线、私密。");
        }

        }
        if (settingsSection == 3) {
        // ================= ④ 记忆与省 token =================
        addSection(box, "\uD83E\uDDE0 记忆与省 token");

        addToggle(box, "\uD83E\uDDE0 连续对话记忆（记得之前聊过什么）", ChatMemory.enabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                ChatMemory.setEnabled(MainActivity.this, checked);
                Toast.makeText(MainActivity.this,
                        checked ? "已开启连续对话记忆" : "已关闭连续对话记忆（之前的记录保留）",
                        Toast.LENGTH_SHORT).show();
            }
        });

        addButton(box, "\uD83D\uDCCF 上下文上限：" + ChatMemory.ctxLimitLabel(this), new View.OnClickListener() {
            @Override public void onClick(View v) {
                final int[] opts = ChatMemory.CTX_OPTIONS;
                final String[] labels = new String[opts.length];
                int cur = ChatMemory.ctxLimit(MainActivity.this);
                int curIdx = 0;
                for (int i = 0; i < opts.length; i++) {
                    labels[i] = (opts[i] <= 0 ? "不带历史（最省 token）"
                            : opts[i] + " 条 · " + ChatMemory.ctxCostHint(opts[i]))
                            + (opts[i] == cur ? "（当前）" : "");
                    if (opts[i] == cur) curIdx = i;
                }
                UiFactory.dialog(MainActivity.this)
                        .setTitle("上下文上限（越大记得越多、越费 token）")
                        .setSingleChoiceItems(labels, curIdx, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dlg, int which) {
                                ChatMemory.setCtxLimit(MainActivity.this, opts[which]);
                                dlg.dismiss();
                                showSettings();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        addToggle(box, "\uD83D\uDCCC 主动记忆点（提炼长期事实，比全量历史更省 token）", ChatMemory.factsEnabled(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                ChatMemory.setFactsEnabled(MainActivity.this, checked);
            }
        });

        addButton(box, "\u270F\uFE0F 编辑记忆点（" + ChatMemory.factsCount(this) + " 条）",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                final EditText et = new EditText(MainActivity.this);
                et.setMinLines(4);
                et.setGravity(Gravity.TOP | Gravity.START);
                et.setText(ChatMemory.facts(MainActivity.this));
                et.setHint("每行一条，例如：\n用户叫小明\n喜欢安静的环境");
                UiFactory.dialog(MainActivity.this)
                        .setTitle("编辑记忆点")
                        .setView(et)
                        .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dlg, int w) {
                                ChatMemory.setFacts(MainActivity.this, et.getText().toString());
                                Toast.makeText(MainActivity.this, "记忆点已保存", Toast.LENGTH_SHORT).show();
                                showSettings();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        addButton(box, "\uD83D\uDD04 立即整理记忆点", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Prefs.hasApiKey(MainActivity.this) || ChatMemory.size(MainActivity.this) == 0) {
                    Toast.makeText(MainActivity.this, "需先设置 Key 并有对话记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(MainActivity.this, "正在整理记忆点…", Toast.LENGTH_SHORT).show();
                MemoryFacts.updateNowAsync(MainActivity.this,
                        Prefs.getApiKey(MainActivity.this),
                        Prefs.getChatModel(MainActivity.this),
                        Prefs.personaForPet(MainActivity.this));
            }
        });

        addButton(box, "\uD83D\uDD50 查看对话历史", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, HistoryActivity.class));
            }
        });

        addButton(box, "\uD83D\uDDD1 清空对话记忆（当前 " + ChatMemory.size(this) + " 条）",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                showDangerDialog("清空对话记忆",
                        "确定要删除桌宠记住的所有对话吗？此操作不可恢复。",
                        "清空", new Runnable() {
                    @Override public void run() {
                        ChatMemory.clear(MainActivity.this);
                        Toast.makeText(MainActivity.this, "对话记忆已清空", Toast.LENGTH_SHORT).show();
                        recreate();
                    }
                });
            }
        });

        }
        if (settingsSection == 4) {
                // ================= ⑤ 运行控制 =================
        addSection(box, "\u2699\uFE0F 运行控制");

        addButton(box, "\u25B6\uFE0F 启动 / 重启桌宠", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, FloatingService.class);
                i.setAction(FloatingService.ACTION_RESTART);
                startService(i);
                Toast.makeText(MainActivity.this, "桌宠已启动 / 重启", Toast.LENGTH_SHORT).show();
            }
        });

        // 启动 / 退出时的问候语总开关
        addToggle(box, "👋 启动 / 退出问候语（伸懒腰、道别、接上次话题）",
                Prefs.greetingOn(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setGreetingOn(MainActivity.this, checked);
            }
        });

        // ================= 性能优化 =================
        //
        // 用户反馈"占用高、发热高"。根因是桌宠作为**常驻悬浮窗**
        // 以屏幕刷新率持续重绘（模型包/Live2D 模式），全天累计就明显发热。
        // 这里给三档基础帧率 + 一个自动调节开关。
        addSection(box, "\u26A1 性能优化");

        final String[] perfNames = {"高性能", "均衡（推荐）", "省电"};
        final String[] perfDesc = {
                "不限帧率，动作最顺滑（模型包 / Live2D 生效）",
                "动作流畅与省电兼顾（推荐）",
                "约 15 帧，最省电（模型包 / Live2D 生效）",
        };
        final TextView perfLabel = new TextView(this);
        perfLabel.setTextSize(15);
        perfLabel.setTextColor(T.text2);
        perfLabel.setPadding(0, (int) (d * 8), 0, (int) (d * 4));
        final Runnable refreshPerf = new Runnable() {
            @Override public void run() {
                int m = Prefs.perfMode(MainActivity.this);
                perfLabel.setText("当前：" + perfNames[m] + "（" + perfDesc[m] + "）");
            }
        };
        refreshPerf.run();
        box.addView(perfLabel);

        // 三档：模式与文案**成对声明**，避免"下标 ≠ 常量值"那类错
        // （视频背景的铺法选择器就踩过一次，见 STATE.md）
        final int[] perfModes = {Prefs.PERF_HIGH, Prefs.PERF_BALANCED, Prefs.PERF_SAVER};
        LinearLayout perfRow = new LinearLayout(this);
        perfRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < perfModes.length; i++) {
            final int mode = perfModes[i];
            Button pb = UiFactory.button(this, uiT(), perfNames[i], false);
            pb.setTextSize(12);
            pb.setMinHeight((int) (d * 38));
            pb.setMinimumHeight((int) (d * 38));
            pb.setPadding((int) (d * 4), (int) (d * 6), (int) (d * 4), (int) (d * 6));
            pb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.setPerfMode(MainActivity.this, mode);
                    refreshPerf.run();
                    reloadService();            // 让正在跑的桌宠立刻按新档位生效
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = (i < perfModes.length - 1) ? (int) (d * 6) : 0;
            perfRow.addView(pb, lp);
        }
        box.addView(perfRow);

        addToggle(box, "\uD83C\uDF19 熄屏 / 遮挡时自动停止动画（省电）",
                Prefs.perfAuto(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.setPerfAuto(MainActivity.this, checked);
                refreshPerf.run();
                reloadService();
            }
        });
        addLabel(box, "「熄屏 / 遮挡时自动停止动画」是省电效果最明显的一项："
                + "实测熄屏后桌宠占用的 CPU 从约 10% 降到 0.2%（降 98%），"
                + "从桌面切走时也能降约三成。"
                + "桌宠的耗电主要来自视频背景与动画渲染，看不见时停掉它们不影响任何观感。");

        addButton(box, "\uD83D\uDD11 权限与后台保活引导", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(MainActivity.this, PermissionActivity.class);
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this,
                            "打不开引导页：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        // 状态摘要：让用户一眼看到关键项有没有开
        {
            boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(MainActivity.this);
            boolean battOk = AutoStartHelper.isIgnoringBatteryOptimizations(MainActivity.this);
            int vend = AutoStartHelper.availableEntries(MainActivity.this).size();

            // 状态列表：每项一行「单色图标 + 文字」，而不是一整块多行字符串。
            //
            // 原来是一段裸 TextView，里面用 ✅❌ℹ️⚠️ 当行首标记 ——
            // 三个问题叠在一起（子页面截图确认）：
            //   1. 裸 TextView 直接 addView 到页面，**没有卡片承载**，
            //      视觉上突然从卡片变成一段飘着的文字；
            //   2. 彩色 emoji 与整套单色图标语言冲突（✅❌ 在 Android 上
            //      就是绿框红叉的彩色贴纸）；
            //   3. 没有左右内边距，长句会顶到屏幕边。
            // 现在走 addSection + UiFactory 的图标行，与其它设置项同构。
            addSection(box, "后台保活状态");
            addStatusRow(box, overlayOk ? "CHECK" : "WARN",
                    overlayOk ? "悬浮窗已授权" : "悬浮窗未授权（桌宠显示不出来）",
                    overlayOk);
            addStatusRow(box, battOk ? "CHECK" : "WARN",
                    battOk ? "已忽略电池优化" : "未忽略电池优化（会被系统省电清掉）",
                    battOk);
            addStatusRow(box, vend > 0 ? "INFO" : "WARN",
                    vend > 0
                        ? "检测到 " + vend + " 个可直达的自启动页面，点上方按钮去开"
                        : "本机未探测到自启动页，需按引导手动设置",
                    vend > 0);
        }

        addButton(box, "\u23F9\uFE0F 退出桌宠", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, FloatingService.class);
                i.setAction(FloatingService.ACTION_STOP);
                startService(i);
                Toast.makeText(MainActivity.this, "桌宠已退出（后台进程仍在）", Toast.LENGTH_SHORT).show();
            }
        });

        // 手势说明 —— 原型 behavior 屏的 `gest` 控件。
        //
        // 改造前这是一坨 13sp、#888888 的纯文本（tip.setTextColor(-7829368)），
        // 评审报告实测它叠在页面底色上只有 **1.07:1** —— 而这是新手**唯一**
        // 的手势说明书，等于完全读不出来。而且 5 条互不相关的内容（GIF 上传、
        // 手势、退出语义、省 token、保活指引）挤在一个块里。
        //
        // 现在拆成「手势表」卡片：每行 = 手势名 + 说明，用令牌色，可达 AA。
        addSection(box, "\uD83D\uDC46 手势说明");
        LinearLayout gestCard = UiFactory.card(this, T);
        // 手势名 -> 说明。单击/双击/三击/长按/拖动 —— 全部来自实际实现，
        // 不是抄原型的文案（改代码时这里要一起改）。
        gestCard.addView(UiFactory.gestureRow(this, T, "单击", "切换余额气泡显示 / 隐藏（可在设置里关掉余额气泡）"));
        gestCard.addView(UiFactory.gestureRow(this, T, "双击", "打开输入框，直接和桌宠聊天"));
        gestCard.addView(UiFactory.gestureRow(this, T, "三击", "打开动作菜单（换装 / 说话 / 设置等）"));
        gestCard.addView(UiFactory.gestureRow(this, T, "长按", "退出 / 重启桌宠"));
        gestCard.addView(UiFactory.gestureRow(this, T, "拖动", "把桌宠拖到屏幕任意位置"));
        box.addView(gestCard);

        addSection(box, "\uD83D\uDCA1 提示");
        addLabel(box, "• 上传 GIF / 动态 WebP 即可让桌宠「动起来」。");
        addLabel(box, "• 「退出桌宠」只是收起、不杀后台；想召回点上方「启动 / 重启桌宠」。");
        addLabel(box, "• 想更省 token：把「上下文上限」调小，或开启「主动记忆点」。");
        addLabel(box, "• 桌宠老是消失？点上方「权限与后台保活引导」，把自启动与电池优化一次开好。");

        }

        if (settingsSection == 5) {
        // ================= \u2465 \u5173\u4E8E =================
        addSection(box, "\u2139\uFE0F 关于");

        // 版本信息：运行时读取，无需硬编码
        String verName = "?";
        int verCode = 0;
        try {
            android.content.pm.PackageInfo pi =
                getPackageManager().getPackageInfo(getPackageName(), 0);
            verName = pi.versionName;
            verCode = pi.versionCode;
        } catch (Exception ignore) {}

        // 版本类型：由清单 meta-data dsp.variant 提供（开发版 / 正式版）
        String variant = "";
        try {
            android.content.pm.ApplicationInfo ai =
                getPackageManager().getApplicationInfo(getPackageName(),
                    android.content.pm.PackageManager.GET_META_DATA);
            if (ai != null && ai.metaData != null) {
                String v = ai.metaData.getString("dsp.variant");
                if (v != null) variant = v;
            }
        } catch (Exception ignore) {}

        addLabel(box, "\uD83D\uDCC4 版本：v" + verName + "（" + verCode + "）");
        if (variant.length() > 0)
            addLabel(box, "\uD83C\uDFF7\uFE0F 版本类型：" + variant);
        addLabel(box, "\uD83D\uDC64 作者：曦城那年");

        // 赞赏码：只有打包了 assets/reward_qr.png 的版本才会显示
        // （开发版不含该图，自动跳过，不报错）
        try {
            java.io.InputStream is = getAssets().open("reward_qr.png");
            Bitmap bmpQr = BitmapFactory.decodeStream(is);
            is.close();
            if (bmpQr != null) {
                addSection(box, "\u2615 赞赏支持");
                addLabel(box, "如果这个小桌宠帮到了你，可以请我喝杯咖啡～");
                ImageView ivQr = new ImageView(this);
                ivQr.setImageBitmap(bmpQr);
                ivQr.setAdjustViewBounds(true);
                final float dd = getResources().getDisplayMetrics().density;
                int qw = (int) (dd * 220);
                LinearLayout.LayoutParams lpQr = new LinearLayout.LayoutParams(
                        qw, LinearLayout.LayoutParams.WRAP_CONTENT);
                lpQr.gravity = Gravity.CENTER_HORIZONTAL;
                lpQr.topMargin = (int) (dd * 10);
                lpQr.bottomMargin = (int) (dd * 4);
                ivQr.setLayoutParams(lpQr);
                box.addView(ivQr);
                // ★ 合规：赞赏是纯自愿的个人赠与，与 DeepSeek／深度求索公司无关。
                //   明确「个人赞赏 ≠ 官方捐赠」，且不与任何功能解锁挂钩。
                addLabel(box, "本区块为开发者个人自愿赠与，"
                        + "与 DeepSeek／深度求索公司无任何关系，"
                        + "并非官方捐赠渠道；是否赞赏完全自愿，"
                        + "不影响本应用的任何功能与使用。");
                TextView tvThanks = new TextView(this);
                tvThanks.setText("\u201C谢谢老板\u201D");
                // 原为硬编码 0xFFB8860B（暗金）：在深色底上偏暗、也不随主题走。
                // 改用令牌的强调色，两套引擎/主题下都一致。
                tvThanks.setTextColor(uiT().accentInk);
                tvThanks.setGravity(Gravity.CENTER_HORIZONTAL);
                tvThanks.setPadding(0, 0, 0, (int) (dd * 10));
                box.addView(tvThanks, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        } catch (Exception ignore) {}

        addLabel(box, "本应用不含内购与广告，赞助与否不影响任何功能。\n"
                + "API Key 与开放平台登录凭证只存本机，只发往 DeepSeek 官方域名。");

        addLabel(box, "\uD83D\uDEE0\uFE0F 对话数据均直接请求 DeepSeek API，"
                + "不经过任何中间服务器。");

        addLabel(box, "⚠️ 本应用为第三方独立开发的非官方工具，与 DeepSeek／深度求索公司"
                + "无任何隶属、赞助或背书关系。DeepSeek 及其相关名称、商标、界面素材"
                + "归其权利人所有，本应用仅作功能调用与桌面陪伴用途。");

        addButton(box, "📄 第三方许可与开源协议声明",
                new View.OnClickListener() {
            @Override public void onClick(View v) { showThirdPartyNotices(); }
        });

        addButton(box, "🔓 清除开放平台登录凭证",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!PlatformClient.isLoggedIn(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "当前未登录开放平台", Toast.LENGTH_SHORT).show();
                    return;
                }
                UiFactory.dialog(MainActivity.this)
                    .setTitle("清除登录凭证")
                    .setMessage("将删除本机保存的开放平台登录凭证，"
                            + "并从官方页面退出登录。清除后需要重新登录才能查看用量。\n\n确定要清除吗？")
                    .setPositiveButton("清除",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                PlatformClient.clearToken(MainActivity.this);
                                Toast.makeText(MainActivity.this, "已清除登录凭证", Toast.LENGTH_SHORT).show();
                            }
                        })
                    .setNegativeButton("取消", null)
                    .show();
            }
        });

        }   // end settingsSection==5
        // 板块首页：只显示**当前板块**下的入口
        //
        // 用户反馈「整体布局太过繁杂，功能分开做底部导航栏，氛围三个板块，
        // 请自行布局，可以留有空余以便日后添加新功能」。
        //
        // 改造前是一屏 7 个入口平铺（外观/对话/语音/素材/记忆/运行/关于），
        // 加上 hero 卡和分组标题，一屏塞满、没有主次。现在按板块切开：
        // 每个板块只列自己的 2~3 个入口，下方留白给以后加功能。
        //
        // 入口文案不再带 emoji 前缀 —— 行首徽章已经用 VectorIcon 画了单色图标，
        // 文案里再带一个彩色 emoji 就是"一块贴纸"，和整套单色语言冲突
        // （iconBadge/actionRow 本来就只取行首 emoji 当图标名，这里直接不写，
        //   改用显式图标名，语义更准）。
        if (settingsSection < 0) {
            String[][] items;
            switch (settingsTab) {
                case 0:
                    items = new String[][]{
                            {"PALETTE", "主题与显示", "皮肤 · 主题 · 背景 · 菜单微调", "0"},
                            {"ROBOT", "形象与动作", "外观大小/透明度 · 形象来源 · 动作系统", "10"},
                            {"IMAGE", "表情与素材", "表情包 · 气泡尺寸 · 回复连发", "2"},
                    };
                    break;
                case 1:
                    items = new String[][]{
                            {"GEAR", "工作模式", "Harness 工作台 · 引擎 · 版本 · 地址", "11"},
                {"CHAT", "对话与人格", "API Key · 模型 · 推理强度 · 主动聊天", "1"},
                            {"MIC", "语音", "语音朗读 · 服务商 · 音色克隆 · 语速", "6"},
                            {"BRAIN", "记忆与省 token", "记忆点 · 上下文上限 · 查看历史 · 清空", "3"},
                            {"GAMEPAD", "游戏助手", "前台游戏实时帧率（需 Shizuku）", "7"},
                    };
                    break;
                default:
                    items = new String[][]{
                            {"SHIELD", "运行控制", "自启动 · 保活 · 权限 · 重启 · 退出", "4"},
                            {"INFO", "关于", "版本 · 许可声明 · 赞赏支持", "5"},
                    };
                    break;
            }
            // **不开分组标题**：页头已经是大号的板块名（"形象/陪伴/系统"），
            // 卡片上方再写一遍同样的分组名就是重复（截图确认）。
            // 原型里 home 页也只有一个 topbar 标题。
            addSectionWithoutLabel(box);
            for (String[] it : items) {
                addHubButton(box, it[0], it[1], it[2], Integer.parseInt(it[3]));
            }
            // 留白区：用户明确要求"可以留有空余以便日后添加新功能"。
            // 这里放一行很轻的提示，既解释了留白，也标出扩展点。
            box.addView(UiFactory.hint(this, T,
                    "更多功能会陆续加到这个板块里。"));
        }

        // ---- 根布局：上滚动区 + 下固定导航栏 ----
        //
        // 导航栏必须**固定在底部、不随内容滚动**，所以不能直接
        // setContentView(scroll)，要套一层竖向 LinearLayout，
        // 让 scroll 占据剩下的所有高度（weight=1）。
        // 根布局。用匿名子类是为了**横滑切板块**：
        // 竖向滚动归 ScrollView，横向拖动必须在这一层截获 —— 否则
        // ScrollView 会一直吃事件，手指横划只会触发它的边沿发光。
        //
        // 截获时机：横向位移超过 touchSlop 且明显大于竖向位移（×1.6）。
        // 用比值而不是固定阈值：斜着划时不该抢走竖向滚动，
        // 那会让长列表"划不动"。
        LinearLayout root = new LinearLayout(this) {
            private float downX, downY;
            private boolean horiz;
            private boolean downInNav;
            private boolean downOnSlider;
            private int slop = -1;
            private final int[] locSelf = new int[2];
            private final int[] locNav = new int[2];

            /**
             * 命中测试：这个坐标下面是不是一个 SeekBar（或其子视图）。
             *
             * <p><b>为什么必须测这个：</b>划页面的手势是"横向位移够大就抢事件"，
             * 而**滑块本身也是横向拖动的**。不排除它，拖「大小」会被判成划页面：
             * 滑块纹丝不动、反而去切板块 —— 实机验证时正是如此
             * （拖完标签还停在 0.72×，预览当然也不变）。
             * 所以按下的位置一旦落在 SeekBar 上，就完全不截获，把横向拖动
             * 留给平台滑块。
             */
            private boolean hitsSeekBar(android.view.ViewGroup g, float x, float y) {
                for (int i = g.getChildCount() - 1; i >= 0; i--) {
                    android.view.View ch = g.getChildAt(i);
                    if (ch.getVisibility() != android.view.View.VISIBLE) continue;
                    float cx = x - ch.getLeft() + g.getScrollX();
                    float cy = y - ch.getTop() + g.getScrollY();
                    if (cx < 0 || cy < 0 || cx >= ch.getWidth() || cy >= ch.getHeight()) {
                        continue;
                    }
                    if (ch instanceof android.widget.SeekBar) return true;
                    if (ch instanceof android.view.ViewGroup
                            && hitsSeekBar((android.view.ViewGroup) ch, cx, cy)) {
                        return true;
                    }
                }
                return false;
            }

            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
                if (slop < 0) {
                    slop = android.view.ViewConfiguration.get(getContext())
                            .getScaledTouchSlop();
                }
                // ★ 左边缘「左滑返回」优先于「横滑切板块」。
                //   两者的手势都是横向，必须有个先后 —— 边缘起手的算返回，
                //   其余算切板块。顺序反了会导致边缘滑动把板块也切走。
                if (handleEdgeBackSwipe(ev)) return true;
                if (backSwipeTracking || backSwipeDone) {
                    // 正在做边缘返回手势：把后续事件都吃掉，
                    // 不让 ScrollView / NavBar 同时响应。
                    if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP
                            || ev.getActionMasked()
                               == android.view.MotionEvent.ACTION_CANCEL) {
                        backSwipeTracking = false;
                        backSwipeDone = false;
                    }
                    return true;
                }
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = ev.getX(); downY = ev.getY(); horiz = false;
                        // **按下点落在导航栏上就不截获**，交给 NavBar 自己处理
                        // （拖导航栏＝滑块跟手＋滑到哪切到哪）。
                        // 少了这个判断，最外层会先把事件抢走 —— NavBar 的
                        // onInterceptTouchEvent 根本收不到 MOVE，
                        // "拖滑块切板块"于是完全失效（实机验证：拖完仍停在原板块）。
                        downInNav = false;
                        downOnSlider = hitsSeekBar(this, ev.getX(), ev.getY());
                        if (currentNav != null && currentNav.getWidth() > 0) {
                            getLocationInWindow(locSelf);
                            currentNav.getLocationInWindow(locNav);
                            float nx = ev.getX() + locSelf[0] - locNav[0];
                            float ny = ev.getY() + locSelf[1] - locNav[1];
                            downInNav = nx >= 0 && ny >= 0
                                    && nx < currentNav.getWidth()
                                    && ny < currentNav.getHeight();
                        }
                        break;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        if (downInNav || downOnSlider) break;   // 归导航栏 / 归滑块
                        float dx = ev.getX() - downX, dy = ev.getY() - downY;
                        if (!horiz && Math.abs(dx) > slop
                                && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                            horiz = true;
                            // **划页面时滑块全程不动**（用户要求
                            // 「滑动页面的时候 滑块不要动」）——只把竖向滚动
                            // 从 ScrollView 手里抢过来，松手才在
                            // ACTION_UP 里判定切哪一页。
                            // 想让滑块跟手，请直接拖底部导航栏
                            // （UiFactory.NavBar 自己的 onTouchEvent）。
                            return true;
                        }
                        break;
                    }
                    default:
                        horiz = false;
                        break;
                }
                return false;
            }

            @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
                if (!horiz) return super.onTouchEvent(ev);
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_MOVE:
                        // 划页面：滑块不动，只吃掉事件（详见截获处的注释）
                        return true;
                    case android.view.MotionEvent.ACTION_UP: {
                        float dx = ev.getX() - downX;
                        horiz = false;
                        if (currentNav != null) {
                            int target = currentNav.dragRelease(dx);
                            if (target >= 0) switchTab(target);
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_CANCEL:
                        horiz = false;
                        return true;
                    default:
                        return true;
                }
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        // 背景：视频背景优先，没有就铺 Mesh 渐变光斑（详见 installVideoBackground）。
        //
        // ★ 视频**不能**直接作为 root 的子 View：root 是**竖向 LinearLayout**，
        // 任何子 View 都会占掉一段高度、把内容挤下去。视频必须与 root **重叠**，
        // 所以外面再套一层 FrameLayout 当"外壳"，视频和 root 都 match_parent
        // 叠在里面（见下方 shell）。
        // 视频背景**只在玻璃态生效**（见设置页 ⓪-2 的说明）。
        //
        // ★ 这里必须重复判一次引擎，不能只看 Prefs.videoBgEnabled：
        // 用户可能"玻璃态下开了视频背景 → 又切回新拟态"。此时偏好仍是开着的，
        // 但新拟态卡片是不透明的，视频只会在卡片缝隙里乱闪。
        // 所以渲染层也以引擎为准，切回新拟态就自然回到 Mesh。
        //
        // ★ 第二十一轮：图片背景与视频背景**共用这一条通道**。
        //   谁生效由 Prefs.activeBgKind() 统一裁决（图片优先），
        //   这里不自己写判断 —— 两处各写一遍必然漂移。
        final String bgKind = Tokens.skin(this) == Tokens.SKIN_GLASS
                ? Prefs.activeBgKind(this) : null;
        final boolean videoBg = "video".equals(bgKind);
        final boolean imageBg = "image".equals(bgKind);
        if (bgKind != null) {
            // root 自身的背景必须让开，否则 Mesh 会盖在背景上、背景根本看不见。
            // 玻璃卡片的"磨砂"感此时来自背景本身，这正是选的「用背景替换 Mesh」。
            root.setBackground(null);
        } else {
            UiFactory.decoratePage(root, this, T);   // Mesh 背景铺满含导航栏在内的整屏
        }
        scroll.setBackground(null);              // 背景已由 root 提供
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (Build.VERSION.SDK_INT >= 21) {
            // 底部导航栏不能压到系统手势区（否则会被系统条盖住）
            root.setPadding(0, 0, 0, 0);
            root.setFitsSystemWindows(false);
            scroll.setClipToPadding(false);
        }

        // 底部导航栏本体：外面再包一层，给它留出与内容的分隔留白
        LinearLayout navWrap = new LinearLayout(this);
        navWrap.setOrientation(LinearLayout.VERTICAL);
        int np = (int) (d * 10);
        navWrap.setPadding(np, 0, np, (int) (d * 8));
        UiFactory.NavItem[] navItems = new UiFactory.NavItem[TAB_TITLES.length];
        for (int i = 0; i < TAB_TITLES.length; i++) {
            navItems[i] = new UiFactory.NavItem(TAB_ICONS[i], TAB_TITLES[i]);
        }
        int navCurrent = settingsSection < 0 ? settingsTab : tabOfSection(settingsSection);
        navWrap.addView(UiFactory.bottomNav(this, T, navItems,
                navCurrent, lastNavTab,
                new UiFactory.OnNavPick() {
                    @Override public void onPick(int index) {
                        // 切板块 = 回到该板块的首页（settingsSection=-1），
                        // 这样"点导航栏"和"点返回"的心智模型一致：
                        // 导航栏永远带你到板块根部。
                        //
                        // 滑块动画由 UiFactory.NavBar 自己在 select() 里做，
                        // 这里负责整页重建（内容确实换了，必须重建）。
                        switchTab(index);
                    }
                }), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // 抓住导航栏实例：横滑切板块时要用它驱动滑块跟手
        if (navWrap.getChildCount() > 0
                && navWrap.getChildAt(0) instanceof UiFactory.NavBar) {
            currentNav = (UiFactory.NavBar) navWrap.getChildAt(0);
        }
        root.addView(navWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- 装配到常驻内容壳 ----
        //
        // ★★ 关键：这里**不再每次调用 setContentView**（除首次）。
        //
        // 用户反馈「切换页面的时候会闪、背景不加载」。根因就是每重建一次都
        // setContentView —— 而 Window.setContentView() 会 removeAllViews()
        // 再挂新的，于是**整棵树**（含视频视图）被 detach 再 attach：
        // TextureView 一 detach 就销毁 SurfaceTexture，播放器必须重新
        // prepareAsync，出第一帧之前画面是黑的。这就是那一"闪"。
        // 长视频解码慢，看起来就更像"背景根本没加载"。
        //
        // 正确做法是**只换页内容、不换外壳**：首次挂一个常驻的 FrameLayout
        // （appContentHost），之后每次重建只替换它最上面那层页面。
        // 视频与遮罩原地不动，永远不 detach。
        if (appContentHost == null) {
            appContentHost = new android.widget.FrameLayout(this);
            setContentView(appContentHost);
        }
        ensureVideoLayers(bgKind);
        if (contentRoot != null && contentRoot.getParent() == appContentHost) {
            appContentHost.removeView(contentRoot);
        }
        appContentHost.addView(root, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        contentRoot = root;

        currentScroll = scroll;
        // 重建后**恢复滚动位置**，且必须做到"用户看不见中间过程"。
        //
        // 用户反馈：「点按最底部开关不会跳回顶部，但会闪一下顶部画面，
        // 然后回到当前页面」。原因是我上一版把 scrollTo 放在 post() 里 ——
        // post 要等**下一帧**，而这一帧**已经画出来了**：屏幕上先出现
        // 内容顶部，下一帧才滚下去。所以"不跳了"但"闪一下"。
        //
        // ★ 修法（第三版，前两版都不对，别再走回头路）：
        //
        // 第一版 `post()`：慢一帧，先露出顶部再滚 —— 闪。
        // 第二版 `alpha=0` + 淡入：**这一版本身就是"闪屏"的来源**。
        //   用户反馈「大部分按钮开关打开关闭都会闪屏」，就是它：
        //   每次重建都把**整页内容透明掉再淡入**，屏幕上表现为
        //   内容整块消失→浮现，越靠近页面顶部越明显。
        //   而且它无条件发生在**任何**调 showSettings() 的开关上
        //   （全工程 56 处），所以"很多开关都闪"。
        //
        // 现在：**只把滚动位置设好，不碰透明度**。
        // `OnPreDrawListener` 在**每次绘制前**触发，那时内容已完成测量、
        // `scrollTo` 能立即生效，而且**这一帧还没画到屏幕上** ——
        // 所以根本不存在"中间那一帧"，也就不需要靠隐藏来遮丑。
        //
        // 为什么可以不做任何可见性处理：新树是这一帧才挂上去的，
        // 它的第一次绘制就发生在 OnPreDraw 之后，此时 scrollY 已经是目标值。
        // 用户看到的第一眼就是正确位置，没有"顶部闪一下"可言。
        if (pendingScrollY > 0) {
            final int target = pendingScrollY;
            pendingScrollY = -1;
            scroll.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                        @Override public boolean onPreDraw() {
                            scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                            scroll.scrollTo(0, target);
                            return true;   // 继续本次绘制（此时位置已正确）
                        }
                    });
        } else {
            pendingScrollY = -1;
        }
        // 记下本次的格子，供下次重建时当作滑块动画的起点
        lastNavTab = navCurrent;
        // 页面入场：微信式**横向翻页**（新页从侧方推入 + 淡入 + 轻微缩放），
        // 替代原来"整屏瞬间替换"的生硬切换。
        //
        // ⚠ 动画只作用在 **scroll（内容区）**，不作用在 root 上。
        //
        // 用户明确要求「翻页的时候底部导航栏不翻页请独立出来」。
        // 原来传 root，而 root = 内容区 + 导航栏，于是整页（含底部三个板块）
        // 一起横移 —— 观感上像"连同外壳一起被推走"，而导航栏是**常驻外壳**，
        // 它应该像微信的 tabBar 一样固定在原地，只有页面内容在动。
        // 传 scroll 之后：内容横移、导航栏静止，导航栏自己的滑块动画
        // （NavBar.moveIndicator）照常独立进行 —— 两个动效各司其职。
        //
        // 页面入场动画：**只在真的换页时**播。
        //
        // 原地刷新（点开关、改下拉、换皮肤）时页面**没变**，不该有任何动画。
        //
        // ★ 这里以前调 `pageFade(scroll)`，那是"闪屏"的第二个来源：
        // 它把内容从 `alpha=0.55` 淡回 1，等于每次都让整页**先变淡再恢复**。
        // 用户反馈「大部分按钮开关打开关闭都会闪屏」正是这个 + 上面滚动
        // 恢复的 alpha=0。两处叠加，点一下开关整页要暗两次。
        //
        // 原地刷新的内容是**同一批视图、同样的设置重算**，外观必然一致，
        // 没有需要"掩盖重排"的东西 —— 所以直接不播动画，
        // 一次绘制到位，这才是真正的"不闪"。
        //
        // 注意别用"极短动画"来折中：哪怕 60ms 的 alpha 变化，
        // 在连点开关时也会被眼睛捕捉成"屏幕在闪"，而它换不来任何收益。
        if (navigated) {
            UiFactory.pageSlide(scroll, slideForward);
        }
        // else：不播任何动画（原地刷新的正确行为）
        slideForward = true;   // 复位，避免影响下一次非导航路径的重建

        try {
            startService(new Intent(this, FloatingService.class));
        } catch (Exception ignore) {}
    }

    /**
     * 按需挂载/摘除**视频背景层**（视频 + 压暗遮罩），挂在常驻内容壳
     * {@link #appContentHost} 的底部。
     *
     * <p>★ 这两层一旦挂上就**不再重建**：它们永远待在 appContentHost 里
     * 最下面两个位置，页面内容每次替换都加在它们**上面**。这正是
     * 「切页面/点开关不黑闪」的关键 —— 视频视图从不 detach，
     * SurfaceTexture 与 MediaPlayer 一直活着。
     *
     * <p>叠放顺序由 addView 顺序决定（同一 ViewGroup 内后加的画在上面）：
     * 视频 → 遮罩 → 页面内容。遮罩必须在内容**下面**：它压的是视频，
     * 压在内容上会把文字一起压暗。
     *
     * @param kind 当前应该显示哪种背景：{@code "image"} / {@code "video"} /
     *             {@code null}（都不显示，走 Mesh）。
     *             已含"仅玻璃态"的判断 —— 由调用方用
     *             {@link Prefs#activeBgKind} 算好。
     */
    private void ensureVideoLayers(String kind) {
        final int MATCH = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
        final boolean wantVideo = "video".equals(kind);
        final boolean wantImage = "image".equals(kind);
        final boolean wantAny = wantVideo || wantImage;

        // ★ 两种背景**互斥**：同时存在会让图片盖住视频（或反之），
        //   且白占一份内存。切种类时先把另一种摘掉。
        if (!wantVideo && videoBgView != null) {
            // 摘掉会让视频视图 detach，其 MediaPlayer 在
            // onDetachedFromWindow 里正常释放 —— 这是预期行为。
            appContentHost.removeView(videoBgView);
            videoBgView = null;
        }
        if (!wantImage && imageBgView != null) {
            appContentHost.removeView(imageBgView);
            imageBgView = null;
        }

        if (wantVideo && videoBgView == null) {
            VideoBackgroundView video = new VideoBackgroundView(this);
            // index 0 = 最底下
            appContentHost.addView(video, 0,
                    new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));
            videoBgView = video;
        }

        if (wantImage && imageBgView == null) {
            ImageBackgroundView img = new ImageBackgroundView(this);
            appContentHost.addView(img, 0,
                    new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));
            imageBgView = img;
        }

        // 压暗遮罩：两种背景共用同一层（只是取值不同），
        // 所以它的增删只看 wantAny，不看具体是哪种。
        if (wantAny && videoDimView == null) {
            // 为什么颜色固定是黑而不是按主题取：背景内容明暗未知，浅色主题下
            // 用白色遮罩会让亮画面更亮、文字更读不清 —— 黑色遮罩在任何主题下
            // 都是「把画面压下去」，方向恒定。
            View dim = new View(this);
            dim.setClickable(false);
            dim.setFocusable(false);
            appContentHost.addView(dim, 1,
                    new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));
            videoDimView = dim;
        } else if (!wantAny && videoDimView != null) {
            appContentHost.removeView(videoDimView);
            videoDimView = null;
        }

        // 每次重建都同步一次：用户可能刚「更换背景」或拖过压暗滑块。
        // setSource 对**同一个文件**是空操作（不重新解码），所以这里安全。
        if (videoBgView != null) {
            videoBgView.setSource(Prefs.videoBgFile(this), Prefs.videoBgSound(this));
            // 填充方式与裁剪框也每次同步：用户可能在裁剪界面改过它们。
            // setFit 是纯几何变换（不重解码），所以每次重建调都无所谓。
            videoBgView.setFit(Prefs.videoBgFit(this), Prefs.videoBgCrop(this));
            videoBgView.setVisibility(View.VISIBLE);
        }
        if (imageBgView != null) {
            imageBgView.setSource(Prefs.imageBgFile(this));
            imageBgView.setCrop(Prefs.imageBgCrop(this), Prefs.FIT_COVER);
            imageBgView.setVisibility(View.VISIBLE);
        }
        if (videoDimView != null) {
            // 压暗强度按当前生效的背景种类取 —— 图片与视频的默认值不同
            float dim = wantImage ? Prefs.imageBgDim(this) : Prefs.videoBgDim(this);
            videoDimView.setBackgroundColor(videoDimColor(dim));
        }
    }

    /** 压暗强度 0~0.8 → 0x00000000 ~ 0xCC000000（最高 80% 不透明） */
    private static int videoDimColor(float dim) {
        int a = Math.round(Math.max(0f, Math.min(0.8f, dim)) * 255f);
        return (a << 24);   // 纯黑 + alpha；RGB 全 0
    }

    /** 当前页面上那块视频背景视图（没有则为 null），供音量/压暗的"就地更新"用 */
    private VideoBackgroundView videoBgView;
    /** 当前页面上那块图片背景视图（没有则为 null） */
    private ImageBackgroundView imageBgView;
    /** 当前页面上那层压暗遮罩，配合滑块实时调透明度 */
    private View videoDimView;
    /**
     * 常驻内容壳：整页重建时**只换它的上层页面**，不再动
     * {@code setContentView}（见 {@link #ensureVideoLayers} 的说明）。
     */
    private android.widget.FrameLayout appContentHost;
    /** 当前挂在 {@link #appContentHost} 最上层的那棵页面树 */
    private View contentRoot;

    /**
     * 拖动压暗滑块时**就地**改遮罩透明度，不重建页面。
     *
     * <p>重建会让视频从头开始解码播放，滑块每拖一格就闪一下、并且从头播，
     * 完全没法调。所以这里只改那一层的 alpha（纯绘制属性，不触发重新测量）。
     */
    private void applyVideoDimLive(float dim) {
        View v = videoDimView;
        if (v == null) return;
        v.setBackgroundColor(videoDimColor(dim));
    }

    /** 拉起系统文件选择器挑一段视频 */
    private void pickVideoBg() {
        try {
            Intent i = new Intent("android.intent.action.GET_CONTENT");
            i.setType("video/*");
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选择背景视频"), REQ_PICK_VIDEO_BG);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 拉起系统文件选择器挑一张图片（第二十一轮新增） */
    private void pickImageBg() {
        try {
            Intent i = new Intent("android.intent.action.GET_CONTENT");
            i.setType("image/*");
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选择背景图片"), REQ_PICK_IMAGE_BG);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 把选中的图片拷进 filesDir。
     *
     * <p><b>为什么必须拷贝：</b>相册返回的 {@code content://} 是一次性授权，
     * 进程重启或 Uri 失效后就读不到了 —— 和视频背景、自定义桌宠图同一个道理。
     *
     * <p><b>为什么不转码：</b>图片解码在 {@link ImageBackgroundView} 里
     * 已经做了按屏幕尺寸的降采样（不会 OOM），所以这里原样存下来即可 ——
     * 存原始文件的好处是用户以后换大屏/横屏还能重新取样，
     * 而转码成固定尺寸就永久损失了清晰度。
     * 代价是占空间，但图片通常远小于视频。
     *
     * @return true 表示拷贝成功
     */
    private boolean saveImageBg(Uri uri) {
        java.io.InputStream is = null;
        java.io.OutputStream os = null;
        try {
            is = getContentResolver().openInputStream(uri);
            if (is == null) return false;
            File dst = Prefs.imageBgFile(this);
            // 先写临时文件再改名：中途失败不会留下半截文件让下次去解码
            File tmp = new File(dst.getAbsolutePath() + ".tmp");
            os = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            os = null;
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Throwable t) {
            // 空间不足等：不能让设置页崩掉
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignore) {}
            try { if (os != null) os.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * 把选中的视频拷进 filesDir。
     *
     * <p><b>为什么必须拷贝：</b>相册返回的 {@code content://} 是一次性授权，
     * 进程重启或 Uri 失效后就读不到了 —— 和自定义桌宠图是同一个道理
     * （见 {@code saveCustomPet}）。
     *
     * <p>与图片不同的是这里**不做转码/预缩放**：视频转码要么引 ffmpeg、
     * 要么用平台 {@code MediaCodec} 手写一遍封装，两者都远超本功能的范围。
     * 代价是超大视频会占空间、每次解码也慢，所以 UI 上提示用户选短片段。
     *
     * @return true 表示拷贝成功
     */
    private boolean saveVideoBg(Uri uri) {
        java.io.InputStream is = null;
        java.io.OutputStream os = null;
        try {
            is = getContentResolver().openInputStream(uri);
            if (is == null) return false;
            File dst = Prefs.videoBgFile(this);
            // 先写临时文件再改名：中途失败（空间不足/用户取消）不会留下
            // 一个半截的 app_bg_video.mp4 让下次启动去解码一个坏文件。
            File tmp = new File(dst.getAbsolutePath() + ".tmp");
            os = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            os = null;
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Throwable t) {
            // 空间不足等：不能让设置页崩掉
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignore) {}
            try { if (os != null) os.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * 弹出「预览 + 裁剪」界面（选完/更换视频后，以及设置页的"重新裁剪"入口）。
     *
     * <h3>为什么要有这一步</h3>
     * 用户反馈「视频导入之后无法正常显示，被裁切了」。原先选完就立刻
     * center-crop 应用，横屏视频当竖屏背景时左右被切掉一大截，而用户在
     * 导入那一刻**看不到**自己失去了哪部分画面。现在多一道确认：
     * 先看到完整画面 → 自己框出想要的区域 → 再应用。
     *
     * <h3>★ 默认：按屏幕比例裁一条居中的竖条 + 铺满</h3>
     * 用户明确要求「视频背景按照屏幕大小纵向裁切，不要横屏，截取视频中的
     * 一段就好，不用完整平铺」。所以默认取景框**已经是**一条与屏幕同比例的
     * 竖条（由 {@link VideoFitMath#defaultCrop} 算，居中、尽可能大），
     * 默认铺法是 {@code FIT_COVER}（铺满）——
     * 于是**直接点「应用」**就得到一个填满屏幕、不变形、无黑边的背景。
     *
     * <p>用户还想换一段画面时，拖动/缩放取景框即可；铺法可在下面切换
     * （只有「铺满」和「完整显示」两种，**都不会变形**）。
     */
    private void showVideoCropDialog() {
        final File f = Prefs.videoBgFile(this);
        if (!f.exists()) {
            Toast.makeText(this, "还没有背景视频", Toast.LENGTH_SHORT).show();
            return;
        }
        final Tokens.Set T = uiT();
        final float d = getResources().getDisplayMetrics().density;

        // ── 取景框 ──
        final VideoCropView crop = new VideoCropView(this);
        // 只解码首帧位图即可 —— 尺寸信息直接从位图取（见 setFrame 的说明）。
        // 不再单独读 METADATA_KEY_VIDEO_WIDTH/HEIGHT：那些是**原始分辨率**，
        // 而变换数学必须用缩小后的位图尺寸（两套尺寸并存正是上一版
        // "画面比预期小、取景框底部露出黑边"的根源）。
        // 旋转也交给 decodeFrame 处理，那里是显式转的，不依赖平台行为。
        int maxPx = (int) (d * 480);
        // decodeFrame 内部会按旋转标记摆正 + 等比缩小，
        // 返回的位图就是"可以按原样铺/裁"的那张（尺寸由它自己决定）。
        final Bitmap frame = VideoCropView.decodeFrame(f.getAbsolutePath(), maxPx, maxPx);
        // ★ 顺序要紧：先给屏幕比例，再 setCropRect。
        // 反过来的话，setCropRect(null)（没裁过）会去算默认竖条，
        // 而那时 screenAspect 还是 0 —— 算出来就是整段视频（不该出现黑边的那条路）。
        //
        // ★ 比例取**内容壳**（appContentHost）的，不是物理屏幕的。
        // 视频最终铺在内容壳上（它比屏幕矮一条底部导航栏），
        // 用物理屏幕比例会让"框内内容 == 屏幕内容"这条等式差一点点，
        // 裁框锁的比例就与实际铺法不匹配。
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float aspect;
        if (appContentHost != null && appContentHost.getHeight() > 0
                && appContentHost.getWidth() > 0) {
            aspect = (float) appContentHost.getHeight() / appContentHost.getWidth();
        } else {
            aspect = (float) dm.heightPixels / dm.widthPixels;
        }
// ★ 传位图本身，不传视频的原始分辨率 ——
        // decodeFrame 会把首帧等比缩小，而变换数学必须用**位图尺寸**
        // （用原始分辨率会让画面摆错大小、取景框露出黑边，实测踩过）。
        crop.setFrame(frame);
        crop.setScreenAspect(aspect);
        crop.setCropRect(Prefs.videoBgCrop(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (d * 14);
        root.setPadding(pad, pad, pad, pad);

        // 取景框高度：屏幕的 40%。取景框 + 说明 + 三个单选项 + 按钮，
        // 加起来在小屏上可能超过弹窗上限，所以外面再套一层 ScrollView
        // （弹窗高度由系统限制，不套的话底部按钮会被挤出去点不到）。
        int cropH = (int) (getResources().getDisplayMetrics().heightPixels * 0.40f);
        root.addView(crop, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cropH));

        TextView tip = new TextView(this);
        // ★ 文案必须与真实行为一致：框锁屏幕比例 ⇒ 框内内容 1:1 铺满，
        // 没有"二次裁切"、没有黑边、没有变形。不再提"铺法选项"（已撤掉）。
        tip.setText("框内就是你最终看到的画面（比例已锁定）。\n"
                + "拖动移动取景框，双指缩放改变取景大小 —— 框里的内容会原样铺满屏幕。");
        tip.setTextSize(13);
        tip.setTextColor(T.text3);
        tip.setPadding(0, (int) (d * 10), 0, 0);
        tip.setLineSpacing(0, 1.15f);
        root.addView(tip);

        // 唯一的铺法：COVER。写死即可 —— 框已锁比例，两种铺法等价
        // （见 build/vidtest [17]），留着选择器只会误导。
        Prefs.setVideoBgFit(this, Prefs.FIT_COVER);
        final int[] fitRef = new int[]{Prefs.FIT_COVER};

        // 再套一层 ScrollView：取景框 + 说明在小屏上可能超过弹窗高度上限，
        // 不套的话底部按钮会被裁掉、点不到。
        ScrollView cropScroll = new ScrollView(this);
        cropScroll.setFillViewport(true);
        cropScroll.addView(root);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        cropScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));

        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle("\uD83C\uDFAC 预览与裁剪")
                .setView(cropScroll)
                .setPositiveButton("应用", null)   // 真正的处理在 onShow 里挂（要先校验）
                .setNegativeButton("取消", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface x) {
                Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                if (ok != null) {
                    ok.setTextColor(T.accentInk);
                    ok.setAllCaps(false);
                    ok.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            float[] r = crop.getCropRect();
                            // 永远是"裁过"的（默认就是一条按屏幕比例的竖条），
                            // 所以直接存。
                            //
                            // ★ 不要再用"接近 0,0,1,1 就当没裁"那条判据 ——
                            // 那是上一版（默认=整段视频）的遗留逻辑。
                            // 现在默认本来就是竖条，若照旧判断，用户
                            // **手动把框拉大到整段视频**时会被当成"没裁过"而丢弃，
                            // 读回来又是竖条，表现为"我的调整没保存"。
                            Prefs.setVideoBgCrop(MainActivity.this,
                                    r[0], r[1], r[2], r[3]);
                            Prefs.setVideoBgFit(MainActivity.this, fitRef[0]);
                            // ★ 用 setBgKind 而不是 setVideoBgEnabled：
                            //   合并成类型选择器后，启用视频必须**同时**把图片关掉，
                            //   否则会退回"两个都开着"的脏状态（那正是合并前
                            //   「开了没反应」的根因）。
                            Prefs.setBgKind(MainActivity.this, Prefs.BG_VIDEO);
                            if (frame != null && !frame.isRecycled()) frame.recycle();
                            dlg.dismiss();
                            Toast.makeText(MainActivity.this, "背景视频已应用",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                Button cancel = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (cancel != null) {
                    cancel.setTextColor(T.text2);
                    cancel.setAllCaps(false);
                }
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface x) {
                // 位图不小（最长边 480dp×密度），用完必须回收
                if (frame != null && !frame.isRecycled()) frame.recycle();
                // 无论确定还是取消都回到设置页：否则会停在"视频已换但没应用"
                // 的中间态，用户不知道到底生效没有。重建一次最省事。
                showSettings();
            }
        });
        dlg.show();
    }

    /**
     * 展示第三方许可与开源协议声明（读 assets/第三方许可声明.md）。
     * MIT 等许可要求：版权声明与许可文本必须随软件一同分发，
     * 所以声明既在工程根目录，也打进 assets 并在应用内提供入口。
     */
    private void showThirdPartyNotices() {
        String txt;
        try {
            java.io.InputStream is = getAssets().open("第三方许可声明.md");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            txt = new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            txt = "未能读取内置声明文件。\n\n"
                + "本应用包含或改编了以下第三方作品，均为宽松开源许可：\n"
                + "· dsh-pet（MIT，Copyright 2026 PC2005-cloud）—— 动画动作素材\n"
                + "· sherpa-onnx（Apache-2.0）、ONNX Runtime（MIT）\n"
                + "· SenseVoice（Apache-2.0）、Silero VAD（MIT）\n"
                + "· Kotlin Standard Library（Apache-2.0）";
        }
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(12);
        tv.setTextColor(uiT().text2);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding((int) (getResources().getDisplayMetrics().density * 16),
                      (int) (getResources().getDisplayMetrics().density * 16),
                      (int) (getResources().getDisplayMetrics().density * 16),
                      (int) (getResources().getDisplayMetrics().density * 16));
        sv.addView(tv);
        UiFactory.dialog(this)
            .setTitle("第三方许可与开源协议")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show();
    }

    /**
     * 开一个新分组。
     *
     * <p>{@code SettingsPage} 会把之后所有 {@code addView(View)} 自动塞进这张
     * 新卡片，所以 60+ 处调用点一行都不用改。这里只需要告诉容器"开新组"。
     */
    /**
     * 开一张新的分组卡片，但**不加**分组标题。
     *
     * <p>用于板块首页：页头已经是大号板块名，卡片上方再写一遍就是重复。
     * {@code beginGroup(null)} 的实现本来就不画标签（只建卡片），
     * 所以这里直接转发，语义上比传空字符串清楚。
     */
    private void addSectionWithoutLabel(LinearLayout parent) {
        if (parent instanceof SettingsPage) {
            ((SettingsPage) parent).beginGroup(null);
        }
    }

    private void addSection(LinearLayout parent, String text) {
        // 原型的分组标题是纯文字小字（.sec），不带 emoji —— 图标归行首徽章。
        // 但这里不能剥掉 emoji 就完事：像「界面风格」这种标题本身有信息量，
        // 而「🎨 外观」的 emoji 已经在每行的徽章里出现，重复。
        if (text != null) {
            String[] b = new String[]{ text };
            if (UiFactory.takeLeadingEmoji(b) != null && b[0].length() > 0) text = b[0];
        }
        // **与页标题同名就不画**。
        //
        // 二级页的页头已经是大号标题（如「运行控制」），卡片上方再写一遍
        // 一模一样的小字标签就是重复 —— 截图里能看到「运行控制」出现两次。
        // 比在 17 个调用点逐个删更稳：以后新增二级页若沿用「页标题 = 主分组名」
        // 的惯例，这里自动生效，不会又冒出一个重复标题。
        String pageTitle = settingsSection < 0 ? null : _GRP_NAMES[settingsSection];
        if (pageTitle != null && text != null && text.equals(pageTitle)) {
            if (parent instanceof SettingsPage) {
                ((SettingsPage) parent).beginGroup(null);
            }
            return;
        }
        if (parent instanceof SettingsPage) {
            ((SettingsPage) parent).beginGroup(text);
        } else {
            // 非设置页的旧调用路径（不该发生）：退化成一行标题
            final float d = getResources().getDisplayMetrics().density;
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(12);
            tv.setTextColor(uiT().text3);
            tv.setPadding(0, (int) (d * 14), 0, (int) (d * 6));
            parent.addView(tv);
        }
    }

    /** 页头的版本号（原型右上角那个 v5.37） */
    private String versionLabel() {
        try {
            return "v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 首页角色卡：圆形头像 + 名字 + 绿色状态点（原型 {@code .hero}）。
     *
     * <p>原型的首页第一张卡就是它 —— 这是「这是我的桌宠」的归属感来源，
     * 第一版漏掉了。
     */
    private View heroCard(Tokens.Set T) {
        final float d = getResources().getDisplayMetrics().density;

        LinearLayout card = UiFactory.card(this, T);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 圆形头像：复用预览那套取图逻辑（自定义图 → 内置待机动画）。
        //
        // 注意本工程**没有** assets/pet.png —— 内置桌宠是 assets/webp/ 下 106 个
        // animated WebP 之一，具体哪个由 pet_anim.json 的 "idle" 指定。
        // （updatePreview 里那个 assets/pet.png 的回退其实一直取不到图，
        // 只是被 catch(Exception) 静默吞掉了。)
        android.widget.ImageView av = new android.widget.ImageView(this);
        av.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        try {
            // ★ App 头像优先（PetAvatar）。注意：点它**只改 App 头像**，
            //   不动桌宠形象 —— 这是两条独立链路（用户 2026-10-2x 定的口径）。
            android.graphics.Bitmap bm = PetAvatar.load(this);
            if (bm == null && Prefs.useCustomImage(this)) {
                bm = android.graphics.BitmapFactory.decodeFile(
                        Prefs.customImageFile(this).getAbsolutePath());
            }
            if (bm == null) bm = loadIdleAvatar();
            // 圆形裁剪：原型头像是圆的，直接放 Bitmap 会是方块
            if (bm != null) av.setImageDrawable(UiKit.circleBitmap(this, bm, 56));
        } catch (Throwable ignored) { }
        av.setContentDescription("点一下更换 App 头像");
        av.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { PetAvatar.pick(MainActivity.this); }
        });
        int size = (int) (d * 56);
        row.addView(av, new LinearLayout.LayoutParams(size, size));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding((int) (d * 14), 0, 0, 0);

        // ★ 轮次81：名字可自定义（点它改名）。原先是硬编码 "小深"。
        // ★ 名字 + "✎" **收在一个 WRAP_CONTENT 的横排里**：
        //   否则这一行会占满整个宽度，右边那块空白也变成"点一下改名"，
        //   跟"整张卡点进工作模式"争点击 —— 用户报告过"容易误触改名"。
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(Prefs.petName(this));
        name.setTextSize(17);
        name.setTextColor(T.text1);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setPadding(0, (int) (d * 2), 0, (int) (d * 2));
        name.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptPetName(); }
        });
        nameRow.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // "✎" 小字：让"可以改名"看得出来（又不像按钮）；点它也能改
        TextView pen = new TextView(this);
        pen.setText("\u270E");
        pen.setTextSize(12);
        pen.setTextColor(T.text3);
        pen.setPadding((int) (d * 6), (int) (d * 2), 0, 0);
        pen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptPetName(); }
        });
        nameRow.addView(pen, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        txt.addView(nameRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView st = new TextView(this);
        st.setText("\u25CF 正在桌面上陪着你");
        st.setTextSize(12.5f);
        st.setTextColor(T.success);
        st.setPadding(0, (int) (d * 3), 0, 0);
        txt.addView(st);

        row.addView(txt, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (d * Tokens.CARD_GAP);
        card.setLayoutParams(lp);

        // ★★ 整张卡就是「陪伴 ⇄ 工作模式」的入口（不另画按钮，用户要求）。
        //   子控件各自覆盖点击：头像=换 App 头像；名字=改名；状态行不可点。
        if (com.deepseek.pet.dsh.DshFeature.ENABLED) {
            card.setClickable(true);
            card.setFocusable(true);
            card.setContentDescription("点一下进入工作模式");
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // 不死锁：运行时开关被关了也能点进去（自动开回来）
                    if (!com.deepseek.pet.dsh.DshBridge.enabled(MainActivity.this)) {
                        com.deepseek.pet.dsh.DshBridge.setEnabled(MainActivity.this, true);
                    }
                    DshModeEntry.switchMode(MainActivity.this, T, null);
                }
            });
        }
        return card;
    }

    /**
     * 取内置桌宠的待机图，给首页 hero 卡的圆头像用。
     *
     * <p>本工程没有 pet.png：内置形象是 {@code assets/webp/} 下的 animated WebP，
     * 具体用哪个由 {@code assets/pet_anim.json} 的 {@code "idle"} 字段指定
     * （脚本 tools/find_idle.py 确认当前值是「待机呼吸休闲.webp」）。
     *
     * <p>这里**硬编码读 json 而不是写死文件名** —— 文件名是中文，写死在 Java 里
     * 一旦换素材就会静默失效；读 json 至少和桌宠本体用的是同一个来源。
     *
     * @return 首帧 Bitmap；任何一步失败都返回 null（调用方会显示空圆）
     */
    private android.graphics.Bitmap loadIdleAvatar() {
        try {
            String fn = "待机呼吸休闲";   // pet_anim.json 的 idle[0]
            java.io.InputStream js = getAssets().open("pet_anim.json");
            byte[] buf = new byte[js.available()];
            int off = 0;
            while (off < buf.length) {
                int r = js.read(buf, off, buf.length - off);
                if (r <= 0) break;
                off += r;
            }
            js.close();
            org.json.JSONObject o = new org.json.JSONObject(new String(buf, "UTF-8"));
            org.json.JSONArray idle = o.optJSONArray("idle");
            if (idle != null && idle.length() > 0) fn = idle.getString(0);
            if (fn.endsWith(".webp") || fn.endsWith(".png")) fn = fn.substring(0, fn.lastIndexOf('.'));
            java.io.InputStream is = getAssets().open("webp/" + fn + ".webp");
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(is);
            is.close();
            return bm;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 危险操作二次确认对话框。
     *
     * <p><b>为什么单独做一个：</b>评审报告里的 P0 之一 —— 「清空对话记忆」弹窗
     * 的「清空」和「取消」两个按钮**都是普通蓝色**，视觉权重完全一样。
     * 用户手一快就点到不可恢复的删除上。原型 {@code dangerDialog} 的处理是：
     * 不可逆动作的按钮用红色**填充**（不是红字），确认键放**左**侧，
     * 取消放右侧且是中性色 —— 靠位置和填色双重区分，不靠读文字。
     *
     * <p>{@code AlertDialog} 的按钮样式受主题控制，没法直接指定背景色，
     * 所以这里在 {@code show()} 之后拿到按钮再把红色填上去。
     */
    private void showDangerDialog(String title, String message,
                                  String confirmText, final Runnable onConfirm) {
        final Tokens.Set T = uiT();
        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle(title)
                .setMessage(message)
                // 关键：危险键放 **negative** 位（Android 会把它排在左边），
                // 中性"取消"放 positive 位（右边）。原型就是这么排的 ——
                // 拇指从左往右扫，先碰到的是"取消"。
                .setNegativeButton(confirmText, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (onConfirm != null) onConfirm.run();
                    }
                })
                .setPositiveButton("取消", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                // 危险键：实心红 + 白字，和右侧中性的"取消"拉开距离
                Button danger = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (danger != null) {
                    danger.setBackground(UiKit.solidFill(MainActivity.this, T, T.dangerFill,
                            Tokens.R_BUTTON));
                    danger.setTextColor(T.onDanger);
                    danger.setAllCaps(false);
                    danger.setMinHeight(UiFactory.dp(MainActivity.this, 44));
                    danger.setPadding(UiFactory.dp(MainActivity.this, 14),
                            UiFactory.dp(MainActivity.this, 10),
                            UiFactory.dp(MainActivity.this, 14),
                            UiFactory.dp(MainActivity.this, 10));
                }
                Button cancel = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                if (cancel != null) {
                    cancel.setTextColor(T.text2);   // 中性，不抢注意力
                    cancel.setAllCaps(false);
                }
            }
        });
        dlg.show();
    }

    /** 开关行 */
    // ==================== 语音合成（让桌宠说话）设置项 ====================
    //
    // 多协议版：协议细节在 TtsCodec / TtsProvider，这里只做「选服务商 + 填参数 + 试听」。

    /** 服务商选择 */
    private void showTtsProviderDialog() {
        final java.util.List<TtsProvider.Entry> list = TtsProvider.all();
        final String[] names = new String[list.size()];
        final String cur = Prefs.ttsProvider(this);
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            TtsProvider.Entry e = list.get(i);
            names[i] = (e.verified ? "\u2705 " : "\u25CB ") + e.name
                    + "（" + TtsProvider.kindName(e.kind) + "）";
            if (e.id.equals(cur)) checked = i;
        }

        UiFactory.dialog(this)
                .setTitle("\uD83C\uDF10 选择语音服务商")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        TtsProvider.Entry e = list.get(which);
                        Prefs.setTtsProvider(MainActivity.this, e.id);
                        dlg.dismiss();
                        reloadService();
                        showSettings();
                        Toast.makeText(MainActivity.this,
                                "已切换到「" + e.name + "」，地址/模型/音色已按预设填好。\n"
                                + "若有专用 Key，请在「语音 Key」里填一次。",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 当前服务商的一行摘要 */
    private String ttsProviderSummary() {
        TtsProvider.Entry e = TtsProvider.findOrDef(Prefs.ttsProvider(this));
        return e.name + "（" + TtsProvider.kindName(e.kind) + "）";
    }

    /** 语音接口地址摘要：现在只可能来自「用户填写」或「服务商预设」两个来源 */
    private String ttsUrlSummary() {
        String custom = Prefs.ttsCloudUrl(this);
        if (custom.length() > 0) return "自定义：" + custom;
        String eff = Prefs.ttsEffectiveUrl(this);
        return eff.length() == 0 ? "未配置" : "预设：" + eff;
    }

    /** 语音 Key 摘要：专用 / 复用对话 Key / 未配置 */
    private String ttsKeySummary() {
        String custom = Prefs.ttsCloudKey(this);
        if (custom.length() > 0) return "已单独设置";
        return Prefs.getApiKey(this).length() > 0 ? "复用对话 Key" : "未配置";
    }

    private static String ttsOr(String v, String fallback) {
        return (v == null || v.trim().length() == 0) ? fallback : v.trim();
    }

    /** 接口地址：填完整端点，或用「原样直发」模式避免任何自动拼接 */
    private void showTtsUrlDialog() {
        TtsProvider.Entry e = TtsProvider.findOrDef(Prefs.ttsProvider(this));
        final EditText input = new EditText(this);
        input.setHint(e.url.length() > 0 ? e.url : "https://你的服务/完整/路径");
        input.setText(Prefs.ttsCloudUrl(this));

        LinearLayout box = new LinearLayout(this);
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        box.setPadding(pad, pad, pad, pad);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        String msg = "当前服务商：" + e.name + "（" + TtsProvider.kindName(e.kind) + "）\n\n"
                + (e.hint.length() > 0 ? "提示：" + e.hint + "\n\n" : "")
                + ("openai".equals(e.kind)
                        ? "填「基址」会自动补 /audio/speech，填完整端点则原样使用。\n"
                          + "若不想被自动补路径，请改用「自定义（原样直发）」。"
                        : "此协议的地址不会被改写，请照服务商文档填完整地址。")
                + "\n\n留空则使用该服务商的预设地址。";

        UiFactory.dialog(this)
                .setTitle("\uD83D\uDCDF 语音合成接口地址")
                .setMessage(msg)
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setTtsCloudUrl(MainActivity.this, input.getText().toString());
                        reloadService();
                        showSettings();
                    }
                })
                .setNeutralButton("恢复预设", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setTtsCloudUrl(MainActivity.this, "");
                        reloadService();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 语音合成专用 Key：留空=复用对话 Key */
    private void showTtsKeyDialog() {
        TtsProvider.Entry e = TtsProvider.findOrDef(Prefs.ttsProvider(this));
        final EditText input = new EditText(this);
        input.setInputType(129);   // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
        input.setHint("留空=复用对话 API Key");
        input.setText(Prefs.ttsCloudKey(this));

        LinearLayout box = new LinearLayout(this);
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        box.setPadding(pad, pad, pad, pad);
        if (e.needGroupId) {
            EditText gid = new EditText(this);
            gid.setHint("GroupId");
            gid.setText(Prefs.ttsGroupId(this));
            box.addView(gid, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            final EditText gidRef = gid;
            box.addView(input, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            UiFactory.dialog(this)
                    .setTitle("\uD83D\uDD11 语音 Key / GroupId")
                    .setMessage("当前服务商：" + e.name + "\n\n"
                            + "MiniMax 需要 <b>GroupId</b>（在控制台的账户信息里）。\n"
                            + "它会自动拼到接口地址末尾，不用你手填 URL。\n\n"
                            + "Key 留空则复用「对话用的 API Key」。")
                    .setView(box)
                    .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dlg, int w) {
                            Prefs.setTtsGroupId(MainActivity.this, gidRef.getText().toString());
                            Prefs.setTtsCloudKey(MainActivity.this, input.getText().toString());
                            reloadService();
                            showSettings();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        if (e.needAppId) {
            EditText app = new EditText(this);
            app.setHint("AppId（纯数字，在语音技术控制台）");
            app.setText(Prefs.ttsAppId(this));
            box.addView(app, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            final EditText appRef = app;
            box.addView(input, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            UiFactory.dialog(this)
                    .setTitle("\uD83D\uDD11 语音 Key / AppId")
                    .setMessage("当前服务商：" + e.name + "\n\n"
                            + "需要两项：\n"
                            + "  · <b>AppId</b>：纯数字，来自「语音技术 → 开通管理 / API Key」页\n"
                            + "  · <b>Access Token</b>：同一页面给的令牌\n\n"
                            + "⚠ 不要填 <b>ark- 开头</b>的方舟（大模型）Key —— "
                            + "那是另一套服务，语音合成不认它，会报 "
                            + "「load grant: requested grant not found」。\n\n"
                            + "另外要先在控制台<b>开通「语音合成大模型」</b>，否则填对了也报同样的错。\n"
                            + "鉴权头是 Bearer;<token>（带分号），已自动处理。")
                    .setView(box)
                    .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dlg, int w) {
                            Prefs.setTtsAppId(MainActivity.this, appRef.getText().toString());
                            Prefs.setTtsCloudKey(MainActivity.this, input.getText().toString());
                            reloadService();
                            showSettings();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        UiFactory.dialog(this)
                .setTitle("\uD83D\uDD11 语音合成专用 Key")
                .setMessage("当前服务商：" + e.name + "\n\n"
                        + "留空则复用「对话用的 API Key」。\n"
                        + "若语音与对话用的是不同账号，在这里单独填一个。")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setTtsCloudKey(MainActivity.this, input.getText().toString());
                        reloadService();
                        showSettings();
                    }
                })
                .setNeutralButton("清空（改为复用）", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setTtsCloudKey(MainActivity.this, "");
                        reloadService();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 模型 / 音色 / 返回格式 */
    private void showTtsVoiceDialog() {
        TtsProvider.Entry e = TtsProvider.findOrDef(Prefs.ttsProvider(this));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        box.setPadding(pad, pad, pad, pad);

        final EditText mIn = new EditText(this);
        mIn.setHint("模型，默认：" + e.model);
        mIn.setText(Prefs.ttsModel(this));
        box.addView(mIn);

        final EditText vIn = new EditText(this);
        vIn.setHint("音色，默认：" + e.voice);
        vIn.setText(Prefs.ttsVoice(this));
        box.addView(vIn);

        final String[] fmts;
        if (TtsProvider.K_OPENAI.equals(e.kind)) {
            fmts = new String[]{"mp3", "wav", "pcm", "opus", "flac"};
        } else if (TtsProvider.K_VOLC.equals(e.kind)) {
            fmts = new String[]{"mp3", "wav", "pcm", "ogg_opus"};
        } else {
            fmts = new String[]{"mp3", "wav", "pcm"};
        }
        final TextView fmtLabel = new TextView(this);
        fmtLabel.setTextSize(15);
        fmtLabel.setText("返回格式：当前 " + Prefs.ttsFormatOrDefault(this)
                + "（点这里切换，接口报「不支持的格式」时改这个）");
        fmtLabel.setPadding(0, (int) (d * 12), 0, 0);
        fmtLabel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cur = 0;
                String c = Prefs.ttsFormatOrDefault(MainActivity.this);
                for (int i = 0; i < fmts.length; i++) if (fmts[i].equals(c)) cur = i;
                UiFactory.dialog(MainActivity.this)
                        .setTitle("返回音频格式")
                        .setSingleChoiceItems(fmts, cur, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dlg, int w) {
                                Prefs.setTtsFormat(MainActivity.this, fmts[w]);
                                dlg.dismiss();
                                reloadService();
                                showSettings();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        box.addView(fmtLabel);

        UiFactory.dialog(this)
                .setTitle("\uD83C\uDFA7 模型与音色")
                .setMessage("当前服务商：" + e.name + "\n\n"
                        + (e.hint.length() > 0 ? e.hint + "\n\n" : "")
                        + "留空即使用预设默认值。")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setTtsModel(MainActivity.this, mIn.getText().toString());
                        Prefs.setTtsVoice(MainActivity.this, vIn.getText().toString());
                        reloadService();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * MiniMax 音色克隆：官方分三步（上传音频 → 复刻 → 试听），
     * 与 OpenAI 那套「references 动态音色」完全不是一个机制，所以单独走这条。
     */
    private void showMinimaxCloneDialog() {
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (d * 16);
        box.setPadding(pad, pad, pad, pad);

        final EditText idIn = new EditText(this);
        idIn.setHint("自定义音色 ID（8~256 位，首字符为字母）");
        idIn.setText("DspVoice" + (System.currentTimeMillis() % 100000));
        box.addView(idIn);

        final EditText txIn = new EditText(this);
        txIn.setHint("试听文本");
        txIn.setText("你好呀，我是你的桌宠，这是我说话的声音。");
        box.addView(txIn);

        UiFactory.dialog(this)
                .setTitle("\uD83C\uDFA4 MiniMax 音色克隆")
                .setMessage("按官方三步走：上传参考音频 → 复刻音色 → 试听。\n\n"
                        + "· 参考音频 10 秒 ~ 5 分钟，mp3 / m4a / wav，不超过 20MB\n"
                        + "· 需先在 MiniMax 网页端完成个人认证，否则会报「无复刻权限」\n"
                        + "· 复刻出的音色是临时的：7 天内用它合成过语音才会永久保留\n"
                        + "· 复刻成功后会自动把音色 ID 填好，之后直接用即可")
                .setView(box)
                .setPositiveButton("选择音频并复刻", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        cloneVoiceId = idIn.getText().toString().trim();
                        cloneDemoText = txIn.getText().toString().trim();
                        try {
                            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                            i.setType("audio/*");
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            startActivityForResult(i, REQ_PICK_CLONE_AUDIO);
                        } catch (Throwable t) {
                            Toast.makeText(MainActivity.this,
                                    "打不开音频选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 跑 MiniMax 复刻（后台线程 + 进度框），成功后自动填好音色 ID */
    private void runMinimaxClone(final Uri uri) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在上传并复刻音色…\n（可能要几十秒）");
        pd.setCancelable(false);
        pd.show();

        new Thread(new Runnable() {
            @Override public void run() {
                final TtsEngine eng = new TtsEngine(getApplicationContext());
                final byte[] audio = eng.cloneVoice(uri, cloneVoiceId, cloneDemoText);
                final String err = TtsEngine.lastError();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { pd.dismiss(); } catch (Throwable ignore) {}
                        if (audio == null || audio.length == 0) {
                            UiFactory.dialog(MainActivity.this)
                                    .setTitle("复刻失败")
                                    .setMessage(err == null || err.length() == 0
                                            ? "没有拿到音频，请检查 Key 与 GroupId。" : err)
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }
                        Prefs.setTtsVoice(MainActivity.this, cloneVoiceId);
                        if (Prefs.ttsModel(MainActivity.this).length() == 0) {
                            Prefs.setTtsModel(MainActivity.this, "speech-2.8-hd");
                        }
                        Prefs.setTtsEnabled(MainActivity.this, true);
                        reloadService();
                        playClonedDemo(audio);
                        UiFactory.dialog(MainActivity.this)
                                .setTitle("复刻成功")
                                .setMessage("音色 ID「" + cloneVoiceId + "」已自动填好并启用语音。\n\n"
                                        + "刚才那段就是复刻出来的声音，听听像不像。\n\n"
                                        + "注意：这个音色是临时的，7 天内用它合成过语音才会永久保留。")
                                .setPositiveButton("好", null)
                                .show();
                        showSettings();
                    }
                });
            }
        }).start();
    }

    /** 播放一段试听音频（写到缓存再用 MediaPlayer 播） */
    private void playClonedDemo(byte[] audio) {
        try {
            java.io.File f = new java.io.File(getCacheDir(), "clone_demo.mp3");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(audio);
            fo.close();
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            mp.start();
        } catch (Throwable t) {
            Toast.makeText(this, "试听播放失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 音色克隆摘要 */
    private String ttsCloneSummary() {
        if (!TtsProvider.supportsClone(Prefs.ttsKind(this))) {
            return "当前协议不支持（仅 OpenAI 兼容）";
        }
        String a = Prefs.ttsCloneRefAudio(this);
        if (a.length() == 0) return "未启用（可选）";
        if (!Prefs.ttsCloneReady(this)) return "\u26A0\uFE0F 缺少参考文本，未生效";
        return "已启用：" + a;
    }

    private void showTtsCloneDialog() {
        TtsProvider.Entry e = TtsProvider.findOrDef(Prefs.ttsProvider(this));
        if (TtsProvider.K_MINIMAX.equals(e.kind)) {
            showMinimaxCloneDialog();
            return;
        }
        if (!TtsProvider.supportsClone(e.kind)) {
            UiFactory.dialog(this)
                    .setTitle("\uD83C\uDFA4 音色克隆")
                    .setMessage("音色克隆用的是 OpenAI 兼容协议里的动态音色机制"
                            + "（voice 传空 + references 参考音频），\n\n"
                            + "当前选的是「" + e.name + "」（" + TtsProvider.kindName(e.kind)
                            + "），它的协议格式不同，不支持这种方式。\n\n"
                            + "想用克隆请切到「硅基流动」等服务商。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 16);
        box.setPadding(pad, pad, pad, pad);

        final EditText aIn = new EditText(this);
        aIn.setHint("参考音频地址（须公网可访问的音频直链）");
        aIn.setText(Prefs.ttsCloneRefAudio(this));
        box.addView(aIn);

        final EditText tIn = new EditText(this);
        tIn.setHint("参考音频里说的原话（必填，用于对齐音素）");
        tIn.setText(Prefs.ttsCloneRefText(this));
        box.addView(tIn);

        UiFactory.dialog(this)
                .setTitle("\uD83C\uDFA4 音色克隆")
                .setMessage("填上参考音频地址 + 它说了什么，桌宠就会用那个音色说话。\n\n"
                        + "· 音频需是公网可访问直链（5~20 秒、干净人声效果最好）\n"
                        + "· 参考文本必须与音频内容逐字一致，否则音质会明显发飘\n"
                        + "· 两项都填了才生效；清空即回到普通音色\n"
                        + "· 硅基流动需先在网页端完成实名认证才可用此功能")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setTtsCloneRefAudio(MainActivity.this, aIn.getText().toString());
                        Prefs.setTtsCloneRefText(MainActivity.this, tIn.getText().toString());
                        reloadService();
                        showSettings();
                    }
                })
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setTtsCloneRefAudio(MainActivity.this, "");
                        Prefs.setTtsCloneRefText(MainActivity.this, "");
                        reloadService();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 试听：临时起一个引擎念一句，把失败原因直接告诉用户 */
    private void testTts() {
        String problem = Prefs.ttsCheck(this);
        if (problem != null) {
            Toast.makeText(this, "还不能试听：" + problem, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在合成…", Toast.LENGTH_SHORT).show();
        final TtsEngine e = new TtsEngine(this);
        // 试听要绕过「朗读总开关」：临时打开一下，放完再恢复
        final boolean wasOn = Prefs.ttsEnabled(this);
        Prefs.setTtsEnabled(this, true);
        e.resetErrorNotice();
        final boolean started = e.speak("你好，我是你的桌宠，现在可以说话了。");
        Prefs.setTtsEnabled(this, wasOn);

        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                String err = TtsEngine.lastError();
                if (err != null && err.length() > 0) {
                    UiFactory.dialog(MainActivity.this)
                            .setTitle("\u26A0\uFE0F 语音合成失败")
                            .setMessage("当前服务商：" + ttsProviderSummary() + "\n\n"
                                    + "原因：\n" + err + "\n\n"
                                    + "按这个顺序排查：\n"
                                    + "1. 服务商是否选对（不同家协议不同，选错必然失败）\n"
                                    + "2. 模型名 / 音色名是否被该服务商接受\n"
                                    + "3. Key 是否有权调用语音接口\n"
                                    + "4. 返回格式是否需要换成 wav / pcm\n\n"
                                    + "改完再点「试听一句」。")
                            .setPositiveButton("知道了", null)
                            .show();
                } else if (!started) {
                    Toast.makeText(MainActivity.this,
                            "没能发出语音请求（文字太短或配置为空）", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this,
                            "已发出请求，若听到声音即配置正确", Toast.LENGTH_LONG).show();
                }
                e.release();
            }
        }, 8000);
    }

    private View addToggle(LinearLayout parent, String text, boolean checked,
                           android.widget.CompoundButton.OnCheckedChangeListener l) {
        // 走 UiFactory：整行 48dp 可点、点文字也能切换。
        // 改造前是裸 CheckBox，触点只有 50.9×27.4dp，低于可达性下限。
        View row = UiFactory.toggleRow(this, uiT(), text, checked, l);
        parent.addView(row);
        // 返回行本身：标题里带状态后缀的开关可以就地改文案，不必整页重建
        // （见 UiFactory.setRowLabel 的说明）。
        return row;
    }

    /**
     * 状态行：行首单色图标 + 文字，右侧可选一个"通过/未通过"的短标。
     *
     * <p>用于"后台保活状态"这类**只有信息、不能点**的条目。
     * 不做成 {@link #addHubButton} 是因为那个带右箭头（暗示可点），
     * 这里点了没反应会让人困惑。
     *
     * @param iconName  图标名（{@code CHECK} / {@code WARN} / {@code INFO} …）
     * @param positive  为真时图标用成功色，否则用警示色
     */
    private void addStatusRow(LinearLayout parent, String iconName,
                              String text, boolean positive) {
        final Tokens.Set T = uiT();
        LinearLayout row = UiFactory.row(this, T);
        // 徽章颜色按状态给：通过=成功色，未通过=警示色。
        // 这比彩色 emoji（✅ 绿框 / ❌ 红叉）克制，且随主题变化。
        row.addView(UiFactory.iconBadgeByName(this, T, iconName, null,
                positive ? T.success : T.danger));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(positive ? T.text1 : T.danger);
        tv.setLineSpacing(0, 1.15f);
        row.addView(tv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

    /** 首页分组入口按钮 */
    /** 首页分组入口按钮（单行，保留给内部调用） */
    private void addHubButton(LinearLayout parent, String text, final int section) {
        addHubButton(parent, text, null, section);
    }

    /**
     * 首页分组入口 —— 原型 {@code kind:'nav'} 的两行行式。
     *
     * <p>原型每个入口是「标题 + 一行副标题」，副标题说明这个分组里有什么
     * （如「皮肤 · 主题 · 大小 · 透明度」）。这比只有标题有用得多：
     * 用户不必逐个点进去才知道功能在哪。
     *
     * <p>改造前是裸 {@code Button}，每个各带一圈浮雕光晕，五个排下来像
     * 五个漂浮的气泡；而且只有标题。现在做成行式，靠左对齐、副标题用次级色。
     */
    /**
     * 首页各入口用哪个图标。
     *
     * <p>取自原型 {@code SCREENS} 里 home 的 nav 项，按 {@code to} 对应的页面：
     * appearance→palette、behavior→eye、model→chat、voice→mic、
     * assets→palette、memory→brain、runtime→shield、about→info。
     *
     * <p>意图是**明确指定**而不是从 emoji 猜 —— emoji 只是历史的文案前缀，
     * 它和"这一页该配什么图标"没有必然关系（比如「记忆与省 token」用 🧠，
     * 而「运行控制」用的 ⚙ 被首页和设置标题共用）。
     */
    private String iconForSection(int section, String glyph) {
        switch (section) {
            case 0: return "palette";   // 外观与显示
            case 1: return "chat";      // 对话与人格
            case 2: return "image";     // 表情与素材
            case 3: return "brain";     // 记忆与省 token
            case 4: return "shield";    // 运行控制
            case 5: return "info";      // 关于
            case 6: return "mic";       // 语音
            case 7: return "gamepad";   // 游戏助手（用户 2026-10-07 要求用手柄图标）
            default: return VectorIcon.fromEmoji(glyph);
        }
    }

    // ==================== 游戏助手 · Shizuku ====================

    /**
     * 绑定取数服务并开始采样。
     *
     * <p>抽成方法是为了让"首次进入页面"和"授权成功回调"走**同一条路** ——
     * 第一版只在页面构建时启动，授权成功靠 {@code showSettings()} 重建来间接触发，
     * 结果真机上出现了「Shizuku 状态：已就绪」和「实时帧率：—（Shizuku 未就绪）」
     * 同时显示的矛盾界面（重建没走到启动分支）。直接调用不依赖任何重建时序。
     */
    private void startGameFpsSampling(final TextView fpsTv, final TextView appTv) {
        ShizukuBridge.bind(this);
        // 先摘掉上一次的监听（页面重建时会重新走这里），再注册新的
        if (gameFpsCb != null) {
            GameFpsMonitor.removeListener(gameFpsCb);
            gameFpsCb = null;
        }
        gameFpsCb = new GameFpsMonitor.Callback() {
            @Override public void onSample(String pkg, String appName, float fps,
                                           float refreshHz, String layer,
                                           SysMetrics.Snapshot m) {
                if (appTv != null) {
                    appTv.setText("前台应用：" + (appName != null ? appName : pkg));
                }
                renderMonitorPreview(m, pkg);
                if (fpsTv == null) return;
                if (fps < 0) {
                    // 静止画面没有新帧 —— 这不是故障，是"没在出帧"
                    fpsTv.setText("实时帧率：静止（画面未刷新）");
                } else {
                    fpsTv.setText(String.format(java.util.Locale.US,
                            "实时帧率：%.1f FPS　（屏幕 %.0f Hz）", fps, refreshHz));
                }
            }

            @Override public void onUnavailable(String reason) {
                if (fpsTv != null) fpsTv.setText("实时帧率：—（" + reason + "）");
                if (appTv != null) appTv.setText("前台应用：—");
            }
        };
        // acquire 与 release 配对：本页只要可见就持有，离开时释放。
        // 悬浮窗那边也各自持有，计数归零才真正停采样。
        // ★ 必须用标志位防重复（本方法有三条调用路径：构建第 7 节 /
        //   Shizuku 就绪回调 / onResume），否则计数虚高、退出时还不回去。
        if (!gameFpsAcquired) {
            gameFpsAcquired = true;
            GameFpsMonitor.acquire(this);
        }
        GameFpsMonitor.addListener(gameFpsCb);
    }

    /** 本页不再需要读数（onPause / onDestroy）：只注销自己，不碰别人的采样。 */
    private void stopGameFpsSampling() {
        if (gameFpsCb != null) {
            GameFpsMonitor.removeListener(gameFpsCb);
            gameFpsCb = null;
        }
        // ★ 没 acquire 过就**绝不** release：多还一份会把悬浮窗的引用抵消掉，
        //   采样被停、挂牌数字冻住（真机 bug 的根因，见 gameFpsAcquired 的注释）。
        //   本方法会在 onPause 与 onDestroy 各被调用一次，标志位保证只还一份。
        if (gameFpsAcquired) {
            gameFpsAcquired = false;
            GameFpsMonitor.release();
        }
    }

    /**
     * 三格预览行：把当前选的三项 + 实时值写出来（与悬浮条同源同批数据）。
     */
    private void renderMonitorPreview(SysMetrics.Snapshot m, String pkg) {
        if (monitorTv == null || m == null) return;
        StringBuilder sb = new StringBuilder("监测条：");
        String[] ids = new String[]{
                Prefs.monitorSlot(this, 0), Prefs.monitorSlot(this, 1), Prefs.monitorSlot(this, 2)};
        boolean any = false;
        for (String id : ids) {
            if (SysMetrics.NONE.equals(id)) continue;
            if (any) sb.append("　");
            sb.append(SysMetrics.fullText(id, m));
            any = true;
        }
        if (!any) sb.append("（三格都设成了「不显示」）");
        // ★ 监测范围有两档（用户 2026-10-08 要求把"全局"与"仅选定应用"集成在一起），
        //   所以这里必须**把当前档位写出来** —— 否则用户看到"开着却不显示"时
        //   根本没有线索（空名单那一档尤其容易误会成 bug）。
        boolean on = false, onlySel = false;
        int selCount = 0;
        try {
            on = Prefs.gameAssistant(this) && Prefs.monitorBar(this);
            onlySel = Prefs.monitorOnlySelected(this);
            selCount = Prefs.monitorAppCount(this);
        } catch (Throwable ignore) {}
        if (!on) {
            sb.append("\n悬浮条：已关闭（上面那个开关）");
        } else if (!onlySel) {
            sb.append("\n悬浮条：已打开　范围：所有应用（任何应用前台都显示）");
        } else if (selCount == 0) {
            sb.append("\n悬浮条：范围是「仅选定的应用」，但一个都没选 ⇒ 哪里都不显示");
        } else {
            sb.append("\n悬浮条：已打开　范围：仅选定的 ").append(selCount).append(" 个应用");
        }
        sb.append("\n吸附贴边时只显示「中」那一项：")
          .append(SysMetrics.NONE.equals(ids[1]) ? "（中格没设，会退回普通竖条）"
                                                 : SysMetrics.label(ids[1]));
        monitorTv.setText(sb.toString());
    }

    /** 一行"第 X 格显示什么"的入口（点了弹单选列表）。 */
    private void addSlotRow(LinearLayout box, final int slot, final String name) {
        String cur = Prefs.monitorSlot(this, slot);
        addButton(box, name + " 格显示：" + SysMetrics.label(cur), new View.OnClickListener() {
            @Override public void onClick(View v) { pickMonitorSlot(slot, name); }
        });
    }

    /** 三格之一的单选弹窗：八项指标 + 「不显示」。 */
    private void pickMonitorSlot(final int slot, String name) {
        final String[] ids = new String[SysMetrics.IDS.length + 1];
        final String[] names = new String[ids.length];
        ids[0] = SysMetrics.NONE;
        names[0] = "不显示";
        for (int i = 0; i < SysMetrics.IDS.length; i++) {
            ids[i + 1] = SysMetrics.IDS[i];
            names[i + 1] = SysMetrics.label(ids[i + 1]);
        }
        String cur = Prefs.monitorSlot(this, slot);
        int checked = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(cur)) checked = i;
        UiFactory.dialog(this)
                .setTitle(name + " 格显示什么")
                .setSingleChoiceItems(names, checked,
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        Prefs.setMonitorSlot(MainActivity.this, slot, ids[which]);
                        Prefs.reloadPet(MainActivity.this);   // 悬浮条立刻跟着变
                        d.dismiss();
                        showSettings();                        // 本页文案跟着变
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 「监测范围」二选：所有应用（全局）/ 仅在选定的应用里。
     *
     * <p>★ 为什么做成一行二选而不是两个开关：用户 2026-10-08 明确要求
     * "把这俩集成在一起"。两档是**互斥**的（要么全局、要么按名单），
     * 摆成两个开关就会出现"两个都开/都关到底算什么"的第三种状态 ——
     * 那正是上一轮"看不出来为什么它不显示"的来源。
     */
    private void pickMonitorScope() {
        final String[] names = {"所有应用（全局打开）", "仅在选定的应用里显示"};
        int checked = Prefs.monitorOnlySelected(this) ? 1 : 0;
        UiFactory.dialog(this)
                .setTitle("监测范围")
                .setSingleChoiceItems(names, checked,
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Prefs.setMonitorOnlySelected(MainActivity.this, which == 1);
                        Prefs.reloadPet(MainActivity.this);   // 悬浮条立刻跟着变
                        d.dismiss();
                        // 切档会改变本页要显示哪些行（勾选入口只在"仅选定"时出现），
                        // 属于结构性变化，必须重建 —— 与"点开关刷新一行文案"不同。
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 编辑某个场景的本地台词：多行文本框 + 保存 / 恢复内置。
     *
     * <p>★ 只用**一个** neutral 按钮：AlertDialog 同一位置只能有一个按钮，
     * 两个 `setNeutralButton` 会互相覆盖（性格预设那边就因为这个，
     * "编辑当前"入口一直点不到 —— 用户 2026-10-07 报的就是它）。
     */
    private void editLocalLines(final String scene) {
        final EditText et = new EditText(this);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setMinLines(5);
        et.setHint("一行一句，例如：\n早呀～\n今天也要加油哦");
        et.setText(Prefs.localLines(this, scene));
        UiFactory.dialog(this)
                .setTitle("编辑「" + LocalLines.label(scene) + "」台词")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setLocalLines(MainActivity.this, scene, et.getText().toString());
                        Prefs.reloadPet(MainActivity.this);   // 悬浮层立刻可用新台词
                        showSettings();
                    }
                })
                .setNeutralButton("恢复内置", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) {
                        Prefs.setLocalLines(MainActivity.this, scene, "");
                        Prefs.reloadPet(MainActivity.this);
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 间距滑块的当前值文案（负值必须说清是"压进桌宠那边"，否则用户看不懂）。 */
    private String gapText(float dp) {
        String v = (Math.abs(dp - Math.round(dp)) < 0.01f)
                ? String.valueOf(Math.round(dp))
                : String.format(java.util.Locale.US, "%.1f", dp);
        if (dp < 0) return "当前：" + v + " dp（往桌宠那边压进去一点）";
        if (dp == 0) return "当前：0 dp（紧贴桌宠）";
        return "当前：" + v + " dp";
    }

    /**
     * 一次最多列出多少个应用（剩下的靠搜索缩小范围）。
     */
    private static final int MONITOR_PICK_MAX_ROWS = 60;

    /**
     * 填充"选择监测应用"的列表（搜索过滤 + 已选排前）。
     *
     * <p>只重填列表容器，**不动整页** —— 这样勾选和打字都不会重置滚动位置。
     * 代价是勾选后**不重新排序**（刚勾的那个不会立刻跳到最上面）：
     * 排序会让手指底下那一行突然换人，比"顺序暂时不变"更容易点错。
     */
    private void fillMonitorAppList(LinearLayout listBox, TextView countTv, String query) {
        if (listBox == null) return;
        final Tokens.Set T = uiT();
        final java.util.Set<String> sel = Prefs.monitorApps(this);
        listBox.removeAllViews();
        if (countTv != null) {
            // ★ 空名单必须写清楚后果：这是"开着却不显示"最常见的解释。
            countTv.setText(sel.isEmpty()
                    ? "已选 0 个（空名单 ⇒ 监测条不显示）"
                    : "已选 " + sel.size() + " 个应用");
        }

        final java.util.List<android.content.pm.ResolveInfo> hits =
                new java.util.ArrayList<android.content.pm.ResolveInfo>();
        for (android.content.pm.ResolveInfo ri : launcherApps()) {
            String pkg = ri.activityInfo.packageName;
            if (!SysMetrics.appMatches(query, labelOf(ri), pkg)) continue;
            hits.add(ri);
        }
        java.util.Collections.sort(hits, new java.util.Comparator<android.content.pm.ResolveInfo>() {
            @Override public int compare(android.content.pm.ResolveInfo a,
                                         android.content.pm.ResolveInfo b) {
                boolean sa = sel.contains(a.activityInfo.packageName);
                boolean sb = sel.contains(b.activityInfo.packageName);
                if (sa != sb) return sa ? -1 : 1;      // 已选的排前面
                return labelOf(a).compareToIgnoreCase(labelOf(b));
            }
        });

        final int n = Math.min(hits.size(), MONITOR_PICK_MAX_ROWS);
        for (int i = 0; i < n; i++) {
            final android.content.pm.ResolveInfo ri = hits.get(i);
            final String pkg = ri.activityInfo.packageName;
            if (i > 0) listBox.addView(UiFactory.divider(this, T));
            addToggle(listBox, labelOf(ri), sel.contains(pkg),
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    Prefs.setMonitorApp(MainActivity.this, pkg, checked);
                    // 通知常驻服务：正在前台的那个应用当场生效，不用等下一轮巡检
                    Prefs.reloadPet(MainActivity.this);
                    if (countTv != null) {
                        int c = Prefs.monitorAppCount(MainActivity.this);
                        countTv.setText(c == 0
                                ? "已选 0 个（空名单 ⇒ 监测条不显示）"
                                : "已选 " + c + " 个应用");
                    }
                }
            });
        }

        if (hits.isEmpty()) {
            listBox.addView(UiFactory.label(this, T, "没有匹配的应用。换个关键词试试。"));
        } else if (hits.size() > n) {
            listBox.addView(UiFactory.label(this, T,
                    "还有 " + (hits.size() - n) + " 个没列出来，用上面的搜索框缩小范围。"));
        }
    }

    /**
     * 有启动图标的应用（一个包只取一个）。
     *
     * <p>用 {@code CATEGORY_LAUNCHER} 而不是"所有已安装应用"：
     * 用户想勾的是"我会打开来玩/用的那个应用"，而系统组件、后台服务
     * 混进来只会让列表长到没法翻。自己的包也不算（监测自己没意义）。
     */
    private java.util.List<android.content.pm.ResolveInfo> launcherApps() {
        java.util.List<android.content.pm.ResolveInfo> out =
                new java.util.ArrayList<android.content.pm.ResolveInfo>();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> all = pm.queryIntentActivities(main, 0);
            if (all == null) return out;
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (android.content.pm.ResolveInfo ri : all) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || getPackageName().equals(pkg)) continue;
                if (!seen.add(pkg)) continue;     // 同一个包有多个入口时只留一个
                out.add(ri);
            }
        } catch (Throwable ignore) {
            // 查不到就返回空列表（页面会显示"没有匹配的应用"），绝不崩
        }
        return out;
    }

    /** 应用显示名；取不到就退回包名（**不能**返回 null，否则列表里会出现空行）。 */
    private String labelOf(android.content.pm.ResolveInfo ri) {
        try {
            CharSequence cs = ri.loadLabel(getPackageManager());
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignore) {}
        return ri.activityInfo != null && ri.activityInfo.packageName != null
                ? ri.activityInfo.packageName : "（未知应用）";
    }

    /** 把 Shizuku 当前状态渲染到设置页那两行上。 */
    private void renderShizukuState(TextView stateTv, TextView fpsTv) {
        if (stateTv == null) return;
        String label;
        switch (ShizukuBridge.state()) {
            case NOT_INSTALLED: label = "未安装";           break;
            case NOT_RUNNING:   label = "已安装，服务未运行"; break;
            case NOT_GRANTED:   label = "已安装，待授权";     break;
            default:            label = "已就绪";           break;
        }
        // ★ 取数服务（UserService）是**另一件事**：Shizuku 就绪 ≠ 取数服务已连上。
        //   真机上两者不同步时界面会自相矛盾，所以这里显式把连接状态也写出来。
        String bind = "";
        switch (ShizukuBridge.bindState()) {
            case BINDING: bind = "　取数服务：连接中…"; break;
            case BOUND:   bind = "　取数服务：已连接"; break;
            case FAILED:  bind = "　取数服务：连接失败（"
                    + (ShizukuBridge.bindError().isEmpty() ? "未知" : ShizukuBridge.bindError())
                    + "）"; break;
            default:      bind = ""; break;
        }
        stateTv.setText("Shizuku 状态：" + label + bind + "\n" + ShizukuBridge.hint(this));
    }

    /**
     * 点「授权 / 修复连接」时的分派。
     *
     * <p>按状态给不同动作 —— 用一个按钮覆盖四种情况，而不是四个按钮：
     * 用户此刻只可能处于其中一种状态，摆四个按钮有三个是无效的。
     */
    private void onShizukuActionClick() {
        ShizukuBridge.State s = ShizukuBridge.refresh(this);
        switch (s) {
            case NOT_INSTALLED:
                UiFactory.dialog(this)
                        .setTitle("需要安装 Shizuku")
                        .setMessage("游戏助手靠 Shizuku 读取系统帧数据（免 root）。\n\n"
                                + "请先安装 Shizuku 并启动它的服务，然后回到这里授权。\n\n"
                                + "★ 不装也完全不影响桌宠本身，只是这一项用不了。")
                        .setPositiveButton("知道了", null)
                        .show();
                return;

            case NOT_RUNNING:
                // 直接把 Shizuku 拉起来，让用户在里面点"启动"
                try {
                    Intent i = getPackageManager()
                            .getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PKG);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        return;
                    }
                } catch (Throwable ignore) {}
                Toast.makeText(this, "请手动打开 Shizuku 并启动服务",
                        Toast.LENGTH_LONG).show();
                return;

            case NOT_GRANTED:
                // 结果由 Shizuku 自己的回调送达（不是 onRequestPermissionsResult）
                ShizukuBridge.request();
                return;

            default:
                ShizukuBridge.bind(this);
                showSettings();   // 重建一次，让采样按新状态启动
                return;
        }
    }

    private void addHubButton(LinearLayout parent, String title, String sub,
                              final int section) {
        addHubButton(parent, null, title, sub, section);
    }

    /**
     * 板块首页入口（显式指定图标名）。
     *
     * <p>比 {@link #iconForSection} 更明确：文案里不带 emoji 时，
     * 靠 section 编号推图标仍然可行，但如果以后同一板块出现两个入口、
     * 或想把入口挪到别的板块，编号推断就会给出错误图标。
     * 显式传名字可以让文案与图标解耦。
     *
     * @param iconName 图标名（{@code VectorIcon} 的语义名，如 "PALETTE"）；
     *                 传 null 则按 section 推断
     */
    private void addHubButton(LinearLayout parent, String iconName, String title,
                              String sub, final int section) {
        final Tokens.Set T = uiT();

        // 原型 kind:'nav' 的行：徽章 + （标题 + 副标题）+ 右箭头。
        // 注意它是**卡片内的一行**，靠 addSection 开的那张卡承载；
        // 第一版每行各自一张浮动卡 + 浮雕背景，五个排下来就是"气泡墙"。
        String[] box = new String[]{ title };
        String glyph = UiFactory.takeLeadingEmoji(box);
        if (iconName == null || iconName.length() == 0) {
            iconName = iconForSection(section, glyph);
        }

        LinearLayout row = UiFactory.row(this, T);
        row.setMinimumHeight(UiFactory.dp(this, 68));
        row.addView(UiFactory.iconBadgeByName(this, T, iconName, glyph));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(box[0]);
        tv.setTextSize(17);
        tv.setTextColor(T.text1);
        txt.addView(tv);

        if (sub != null && sub.length() > 0) {
            TextView sv = new TextView(this);
            sv.setText(sub);
            sv.setTextSize(12.5f);
            sv.setTextColor(T.text2);
            sv.setPadding(0, UiFactory.dp(this, 2), 0, 0);
            txt.addView(sv);
        }
        row.addView(txt, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = new TextView(this);
        chev.setText("\u203A");
        chev.setTextSize(20);
        chev.setTextColor(T.text3);
        row.addView(chev);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                settingsSection = section;
                // 进二级页 = "深一层"，新页从**右侧**推入（微信进入下一级的方向）
                slideForward = true;
                showSettings();
            }
        });
        row.setClickable(true);
        row.setFocusable(true);
        // 常态扁平，按下才凹陷（原型 button.row:active）
        row.setBackground(UiFactory.rowPressable(this, T, Tokens.R_CARD));
        row.setTag(section);
        parent.addView(row);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("settingsSection", settingsSection);
    }
    private View addButton(LinearLayout parent, String text, View.OnClickListener l) {
        // 走 actionRow（卡片内的一行），不是浮雕胶囊 —— 见 UiFactory.actionRow 注释
        // ★ 返回这一行：调用方要能**就地改它的文案**（UiFactory.setRowLabel），
        //   而不是为了刷新一行字去整页重建。
        View v = UiFactory.actionRow(this, uiT(), text, l);
        parent.addView(v);
        return v;
    }

    /**
     * 改名对话框（轮次81：主页点名字触发）。
     *
     * <p>★ 行为口径：保存后**立刻**刷新首页并通知桌宠重读（气泡/聊天/人设都要跟着变），
     *   留空或点「恢复默认」都回到 {@link Prefs#DEFAULT_PET_NAME}。
     */
    private void promptPetName() {
        final float d = getResources().getDisplayMetrics().density;
        final android.widget.EditText et = new android.widget.EditText(this);
        // ★ 输入框**留空**、把当前名放进提示（"留空＝保持原名"）——
        //   实测：预填 + 全选在对话框里并不稳（打 "Ayu" 会变成 "Ayu小深"，光标落在开头），
        //   而留空是确定的：你打什么就是什么，不打就是不改。
        et.setText("");
        et.setHint("当前：" + Prefs.petName(this) + "（留空＝不改）");
        et.setSingleLine(true);
        int pad = (int) (d * 12);
        et.setPadding(pad, pad, pad, pad);

        UiFactory.dialog(this)
                .setTitle("给桌宠起个名字")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        String v = et.getText().toString().trim();
                        if (v.length() == 0) return;          // 留空 = 不改名（要恢复默认请点中间那个）
                        Prefs.setPetName(MainActivity.this, v);
                        Prefs.reloadPet(MainActivity.this);   // 桌宠/气泡/聊天重读
                        recreate();                           // 首页那行跟着变
                    }
                })
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        Prefs.setPetName(MainActivity.this, Prefs.DEFAULT_PET_NAME);
                        Prefs.reloadPet(MainActivity.this);
                        recreate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 小标题（滑块等的说明行） */
    private void addLabel(LinearLayout parent, String text) {
        parent.addView(UiFactory.label(this, uiT(), text));
    }

    /**
     * 小标题，并把 TextView 返回给调用方。
     *
     * <p>滑块那种"标签里带当前值"的行需要能在拖动时刷新自己 ——
     * 语音的语速原本额外加了一行重复的「当前：1.00×」来做这件事，
     * 结果页面上出现两行同样的值。返回 TextView 后，调用方直接改这一行即可，
     * 重复行就不需要了。
     */
    private TextView addLabelTv(LinearLayout parent, String text) {
        TextView tv = UiFactory.label(this, uiT(), text);
        parent.addView(tv);
        return tv;
    }

    private void updateBalScaleLabel(TextView tv) {
        if (tv != null) tv.setText(String.format("  当前：%.2f\u00D7", Prefs.getBalanceScale(this)));
    }

    private void updateBubbleScaleLabel(TextView tv) {
        if (tv != null) tv.setText(String.format("  当前：%.2f\u00D7", Prefs.getBubbleScale(this)));
    }

    private void updateMemeScaleLabel(TextView tv) {
        if (tv != null) tv.setText(String.format("  当前：%.2f\u00D7", Prefs.getMemeScale(this)));
    }

    private void updateSplitMaxLabel(TextView tv) {
        if (tv != null) tv.setText("  当前：" + Prefs.splitMax(this) + " 条");
    }

    private void updateSplitDelayLabel(TextView tv) {
        if (tv != null) tv.setText("  当前：" + Prefs.splitDelayMs(this) + " ms（含随机抖动）");
    }

    private void updateBubbleDurLabel(TextView tv) {
        if (tv == null) return;
        float v = Prefs.getBubbleDurScale(this);
        int sec = (int) Math.round(8 * v);
        tv.setText(String.format("  当前：%.2f\u00D7（约 %d 秒/次）", v, sec));
    }

    /**
     * 高度**恒定**的容器（预览框用）。
     *
     * <p>为什么不直接在调用点 {@code setLayoutParams}：{@code SettingsPage}
     * 把子视图塞进卡片时会显式传一个 {@code (MATCH_PARENT, WRAP_CONTENT)}，
     * **覆盖掉**调用点设好的高度 —— 实测"设了也没用"：拖动大小滑块时，
     * 下方标签的 y 仍从 1612 被推到 1816（而同页上方的控件稳定在 569，
     * 说明这不是滚动漂移，是真的重新布局）。
     *
     * <p>这里在 {@code onMeasure} 里**直接把自己的高度定死**，不去依赖
     * LayoutParams 里写的是什么。谁覆盖 LP 都不影响，卡片高度恒定。
     */
    private static final class FixedHeightBox extends android.widget.FrameLayout {
        private final int fixedH;

        FixedHeightBox(Context c, int h) {
            super(c);
            this.fixedH = h;
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            super.onMeasure(wSpec,
                    MeasureSpec.makeMeasureSpec(fixedH, MeasureSpec.EXACTLY));
            setMeasuredDimension(getMeasuredWidth(), fixedH);
        }
    }

    private void updateScaleLabel() {
        if (scaleLabel != null) {
            scaleLabel.setText(String.format("大小：%.2f\u00D7", Prefs.getScale(this)));
        }
        applyPreviewScale(Prefs.getScale(this));
    }

    /**
     * 「大小」滑块的实时应用（节流）。
     *
     * <p><b>为什么要节流：</b>滑块每个像素都会回调一次，而这里做的是
     * "重排预览 + 存 pref + 让桌面上那只真宠物重载"—— 三件都不便宜。
     * 一像素一次会把主线程压到画不完一帧，实测**触发过 ANR**
     * （trace：main 卡在 {@code HardwareRenderer.nSyncAndDrawFrame}，
     * 输入分发超时 5s）。节流到约 12 次/秒既看不出卡顿，
     * 又不至于把绘制拖垮；松手时再用 {@code force} 补一次最终值。
     */
    private void applyScaleLive(float s, boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - lastScaleApply < 80) return;
        lastScaleApply = now;
        applyPreviewScale(s);
        Prefs.saveScale(this, s);
        Prefs.reloadPet(this);
    }

    /**
     * 让**预览图**跟着「大小」滑块变（在固定框内缩放）。
     *
     * <p><b>用户反馈</b>：「预览的调节大小 无变化」→ 现在跟随；
     * 紧接着「放大缩小的时候整个选项框也会被拉长缩短，请固定预览框」
     * → 所以这里**只改缩放、不改尺寸**：{@code setScaleX/Y} 是绘制期变换，
     * 不参与测量，卡片高度恒定，预览框就是 280dp 那一个。
     *
     * <p>映射：{@code SCALE_MIN..SCALE_MAX}(0.3~1.3) 线性映到
     * 90dp~280dp，再除以框高得到缩放系数 0.32~1.00。
     * 取这个区间是为了让**默认值 0.72× 落在约 170dp**，与改造前
     * 写死的 180dp 基本一致 —— 修好"能动"的同时不改变默认观感。
     */
    private void applyPreviewScale(float scale) {
        if (preview == null) return;
        float norm = (scale - Prefs.SCALE_MIN) / (Prefs.SCALE_MAX - Prefs.SCALE_MIN);
        if (norm < 0f) norm = 0f;
        if (norm > 1f) norm = 1f;
        float f = (90f + 190f * norm) / 280f;
        preview.setScaleX(f);
        preview.setScaleY(f);
    }

    private void updateAlphaLabel() {
        if (alphaLabel != null) {
            alphaLabel.setText(String.format("透明度：%d%%", (int) (Prefs.getAlpha(this) * 100.0f)));
        }
    }

    private void updatePreview() {
        if (preview == null) return;
        // 先假设没有可显示的内容：任何一条成功路径都会把它设回 VISIBLE。
        // 这样"完全拿不到图"时预览会收起，而不是留一个 183dp 的空盒子
        // （评审报告里的死间距问题）。
        preview.setVisibility(View.GONE);
        if (Prefs.useCustomL2D(this)) {
            try {
                File dir = Prefs.customL2DDir(this);
                File[] pngs = dir.listFiles(new java.io.FilenameFilter() {
                    @Override public boolean accept(File d, String n) { return n.toLowerCase().endsWith(".png"); }
                });
                if (pngs != null && pngs.length > 0) {
                    Bitmap bm = BitmapFactory.decodeFile(pngs[0].getAbsolutePath());
                    if (bm != null) {
                        preview.setImageBitmap(bm);
                        preview.setVisibility(View.VISIBLE);
                        return;
                    }
                }
            } catch (Exception ignore) {}
        }
        if (Prefs.useLive2D(this)) {
            try {
                InputStream is = getAssets().open("live2d/preview.png");
                Bitmap bm = BitmapFactory.decodeStream(is);
                is.close();
                if (bm != null) {
                    preview.setImageBitmap(bm);
                    preview.setVisibility(View.VISIBLE);
                    return;
                }
            } catch (Exception ignore) {}
        }
        try {
            if (Prefs.isCustomAnim(this) && Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.Source src =
                        android.graphics.ImageDecoder.createSource(Prefs.customAnimFile(this));
                Drawable dr = android.graphics.ImageDecoder.decodeDrawable(src);
                preview.setImageDrawable(dr);
                if (dr instanceof AnimatedImageDrawable) {
                    AnimatedImageDrawable aid = (AnimatedImageDrawable) dr;
                    aid.setRepeatCount(-1);
                    aid.start();
                }
                preview.setVisibility(View.VISIBLE);
                return;
            }
        } catch (Exception ignore) {}
        try {
            if (Prefs.useCustomImage(this)) {
                Bitmap bm = BitmapFactory.decodeFile(Prefs.customImageFile(this).getAbsolutePath());
                if (bm != null) {
                    preview.setImageBitmap(bm);
                    preview.setVisibility(View.VISIBLE);
                    return;
                }
            }
            // 内置形象的兜底：不是 assets/pet.png（本工程没这个文件，
            // 之前的代码在这里静默失败），而是待机动画的首帧。
            Bitmap idle = loadIdleAvatar();
            if (idle != null) {
                preview.setImageBitmap(idle);
                preview.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignore) {}
    }

    // ==================== P5：形象来源（按角色）====================
    //
    // ★ 轮次㉛：设置页上那一行「🎨 形象来源：X ›」连同它的就地刷新
    //   （appearanceRowRef / refreshAppearanceRow）一起删掉了 ——
    //   形象入口收进「🎭 角色 → 🎨 形象」，那里的清单**每次打开都重新读**，
    //   不需要再"刷一行文案"这套东西。

    /**
     * 形象清单（**当前角色**的）—— 轮次㉛ 起这是**唯一**的形象入口（从「🎭 角色」进来）。
     *
     * <p>清单 = 5 种来源 + 4 个动作（上传图片 / 导入序列帧包 / 导入 Live2D / 恢复默认形象）。
     * 以前那 4 个动作是设置页上独立的四行（还有一行 Live2D 开关），
     * 用户要求「删除所有形象导入接口，改为角色切换的模式」⇒ 全部收进这里。
     *
     * <p>★ 素材没导入的来源也列出来（标「（未导入）」）：用户得知道有这条路，
     * 以及为什么现在点不了 —— 点它只会提示"先去导入"，**不会**悄悄改设置。
     */
    private void showAppearancePicker() {
        final PetCharacter ch = CharacterStore.current(this);
        if (ch.dir == null) {
            Toast.makeText(this, "先新增或导入一个角色，再给它设置形象", Toast.LENGTH_LONG).show();
            return;
        }
        final String cur = AppearanceStore.modeFor(this);
        final String[] srcs = Appearance.ALL;
        final java.util.List<String> rows = new java.util.ArrayList<String>();
        for (String s : srcs) {
            rows.add(Appearance.rowLabel(s, s.equals(cur), appearanceAvailable(s)));
        }
        rows.add("\uD83D\uDCF7 上传图片 / GIF 动图…");
        rows.add("\uD83E\uDDF1 导入序列帧模型包（.zip）…");
        rows.add("\uD83E\uDDE9 导入 Live2D 模型（.model3.json / .moc3 / 分层PNG）…");
        rows.add("\uD83D\uDD01 恢复默认形象（清掉这个角色的形象）");
        UiFactory.dialog(this)
                .setTitle("\uD83C\uDFA8 形象（角色：" + ch.displayName() + "）")
                .setItems(rows.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which < srcs.length) { pickAppearance(srcs[which]); return; }
                        int act = which - srcs.length;
                        if (act == 0) pickImage();
                        else if (act == 1) showImportModelDialog();
                        else if (act == 2) showImportL2DDialog();
                        else confirmResetAppearance();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 这种来源的素材在不在（内置永远在） */
    private boolean appearanceAvailable(String src) {
        if (Appearance.BUILTIN.equals(src)) return true;
        if (Appearance.IMAGE.equals(src)) return AppearanceStore.hasImage(this);
        if (Appearance.ANIM.equals(src))  return AppearanceStore.hasAnim(this);
        if (Appearance.MODEL.equals(src)) return AppearanceStore.hasModel(this);
        if (Appearance.L2D.equals(src))   return AppearanceStore.hasL2d(this);
        return false;
    }

    /** 选中一种来源：素材没导入就只提示（**不动**当前设置），否则写盘 + 让悬浮窗重载 */
    private void pickAppearance(String src) {
        if (!appearanceAvailable(src)) {
            Toast.makeText(this, Appearance.missingHint(src), Toast.LENGTH_LONG).show();
            return;
        }
        AppearanceStore.setSourceFor(this, src);
        android.util.Log.i("DSPAppear", "形象来源 -> " + src
                + " char=" + CharacterStore.currentId(this));
        Prefs.reloadPet(this);
        updatePreview();
        Toast.makeText(this, "形象已切换：" + Appearance.label(src), Toast.LENGTH_SHORT).show();
    }

    /**
     * 「恢复默认形象」= 只把**当前角色**清回内置角色包。
     * <p>★ 与"恢复默认角色"是两件事：这里不切角色，也不碰别的角色 ——
     * 所以必须先说清楚"只影响这个角色"，再动手。
     */
    private void confirmResetAppearance() {
        final PetCharacter ch = CharacterStore.current(this);
        UiFactory.dialog(this)
                .setTitle("恢复默认形象")
                .setMessage(Appearance.resetConfirmMessage(ch.displayName(),
                        AppearanceStore.modeFor(this)))
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        AppearanceStore.clearFor(MainActivity.this, ch.id);
                        android.util.Log.i("DSPAppear", "恢复默认形象 char=" + ch.id);
                        Prefs.reloadPet(MainActivity.this);
                        updatePreview();
                        Toast.makeText(MainActivity.this, "已恢复内置形象（只影响这个角色）",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 弹窗：讲解并开始导入模型 */
    private void showImportModelDialog() {
        UiFactory.dialog(this)
                .setTitle("导入桌宠模型")
                .setMessage("支持三种：\n\n"
                        + "1) 序列帧压缩包 .zip —— 可让桌宠按动作动起来。\n"
                        + "   包内按动作放帧图（png/webp/jpg），推荐结构：\n"
                        + "     idle_0.png idle_1.png …\n"
                        + "     tap_0.png  …（被戳）\n"
                        + "     drag_0.png …（拖拽）\n"
                        + "     drop_0.png …（落地）\n"
                        + "     talk_0.png …（说话）\n"
                        + "   也可用子目录：idle/0.png tap/0.png …\n\n"
                        + "2) GIF / 动态 WebP —— 单文件循环动画\n"
                        + "3) 普通图片 —— 静态形象\n\n"
                        + "选好后立即生效。")
                .setPositiveButton("选择文件", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        pickModelFile();
                    }
                })
                .setNeutralButton("查看模型规范", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showModelSpecDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 模型规范说明 */
    private void showModelSpecDialog() {
        UiFactory.dialog(this)
                .setTitle("模型包规范")
                .setMessage("动作名（同义词均可）：\n"
                        + "  idle / stand / 待机 / 默认\n"
                        + "  tap / touch / poke / 戳 / 点击\n"
                        + "  drag / carry / 拖拽 / 拖动\n"
                        + "  drop / land / 落地 / 放下\n"
                        + "  talk / speak / 说话\n"
                        + "  happy / 开心    sad / 难过\n\n"
                        + "帧文件名可用「动作_序号」前缀，也可放入同名子目录。\n"
                        + "只提供 idle 时，其余状态都会播放 idle。\n"
                        + "帧建议尺寸一致、透明背景 PNG。")
                .setPositiveButton("好", null)
                .show();
    }

    /** Live2D 导入说明弹窗 */
    private void showImportL2DDialog() {
        UiFactory.dialog(this)
                .setTitle("导入 Live2D 模型")
                .setMessage("请选择 Live2D 模型包（zip）或单个文件：\n\n"
                        + "✅ 支持：\n"
                        + "  • 标准导出包：*.model3.json + 贴图目录（+ 可选 moc3/physics/motions）\n"
                        + "  • 直接的分层图片包：meta.json + 若干 PNG\n"
                        + "  • 纯 PNG 分层（按目录顺序叠放）\n\n"
                        + "⚠️ 说明：\n"
                        + "  • .moc3 是 Live2D 封闭格式，需要官方 Cubism 原生运行时。\n"
                        + "    本 App 未集成，因此“仅含 .moc3”的模型可识别但不能驱动；\n"
                        + "    若包内同时含分层贴图，会用贴图分层做近似驱动（转头/眨眼/呼吸）。\n\n"
                        + "用法：把整个 Live2D 模型文件夹压成 zip 后选择。")
                .setPositiveButton("选择文件", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { pickL2DFile(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选择 Live2D 文件（任何类型，按内容识别） */
    private void pickL2DFile() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed",
                    "application/octet-stream", "application/json", "image/*"});
            startActivityForResult(Intent.createChooser(i, "选择 Live2D 模型文件"), REQ_IMPORT_L2D);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 处理 Live2D 导入：解压 → 解析 → 生成 meta.json → 启用 */
    private void importL2DPackage(Uri uri) {
        InputStream is = null;
        java.io.OutputStream os = null;
        File tmp = new File(getFilesDir(), "import_l2d.zip");
        try {
            is = getContentResolver().openInputStream(uri);
            if (is == null) { Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show(); return; }
            os = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush(); os.close(); os = null; is.close(); is = null;

            File dir = Prefs.customL2DDir(this);
            PetModel.deleteRecursive(dir);
            if (!dir.mkdirs()) { Toast.makeText(this, "无法创建目录", Toast.LENGTH_SHORT).show(); return; }
            if (!PetModel.unzip(tmp, dir)) {
                Toast.makeText(this, "解压失败，请确认是有效的 zip", Toast.LENGTH_SHORT).show();
                return;
            }

            // 若 zip 内只有一层同名文件夹，把它的内容上移到 dir 根，
            // 保证 meta.json（及相对贴图路径）与包根一致。
            File[] kids = dir.listFiles();
            if (kids != null && kids.length == 1 && kids[0].isDirectory()) {
                File inner = kids[0];
                File[] innerKids = inner.listFiles();
                if (innerKids != null) {
                    for (File k : innerKids) {
                        File moved = new File(dir, k.getName());
                        if (!moved.exists()) k.renameTo(moved);
                    }
                }
                inner.delete();
            }

            Live2DPackage pkg = Live2DPackage.load(dir);
            if (!Live2DPackage.STATUS_OK.equals(pkg.status())) {
                // 兜底：万一 model3.json 在更深层，再扫一遍
                File m3 = findModel3(dir);
                if (m3 != null) {
                    pkg = Live2DPackage.load(m3.getParentFile());
                }
            }
            if (!Live2DPackage.STATUS_OK.equals(pkg.status())) {
                Toast.makeText(this, "导入失败：" + pkg.message(), Toast.LENGTH_LONG).show();
                return;
            }

            Prefs.setUseLive2D(this, false);
            Prefs.setUseCustomImage(this, false);
            Prefs.setCustomAnim(this, false);
            Prefs.setUseCustomModel(this, false);
            Prefs.setUseCustomL2D(this, true);
            Prefs.reloadPet(this);
            updatePreview();
            Toast.makeText(this, "Live2D 已导入并应用！\n" + pkg.message()
                    + "\n分层数：" + pkg.layerCount(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignore) {}
            try { if (os != null) os.close(); } catch (Exception ignore) {}
            try { tmp.delete(); } catch (Exception ignore) {}
        }
    }

    /** 递归查找 *.model3.json */
    private File findModel3(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory()) {
                File r = findModel3(f);
                if (r != null) return r;
            } else if (f.getName().toLowerCase().endsWith(".model3.json")) {
                return f;
            }
        }
        return null;
    }

    private void copyFile(File src, File dst) throws Exception {
        InputStream in = new java.io.FileInputStream(src);
        java.io.OutputStream out = new java.io.FileOutputStream(dst);
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close(); out.close();
    }

    /** 打开文件选择器（zip / 动图 / 图片） */
    private void pickModelFile() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed",
                    "application/octet-stream", "image/*"});
            startActivityForResult(Intent.createChooser(i, "选择桌宠模型文件"), REQ_IMPORT_MODEL);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 处理导入：zip → 解压为序列帧模型；gif/webp → 动图；图片 → 静态 */
    private void handleModelImport(Uri uri) {
        String type = null;
        try { type = getContentResolver().getType(uri); } catch (Exception ignore) {}
        String lower = (type == null ? "" : type.toLowerCase());

        boolean isZip = lower.contains("zip");
        if (!isZip) {
            // 用文件名兜底判断
            String name = queryName(uri);
            if (name != null && name.toLowerCase().endsWith(".zip")) isZip = true;
        }

        if (isZip) {
            importZipModel(uri);
            return;
        }

        // 动图 / 图片：复用现有逻辑
        int r = saveCustomPet(uri);
        if (r != 0) {
            Prefs.setUseCustomModel(this, false);
            Prefs.reloadPet(this);
            Toast.makeText(this, r == 2 ? "动图已应用！" : "图片已应用！",
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "读取失败（不是有效的图片/动图）", Toast.LENGTH_SHORT).show();
        }
    }

    /** 导入 zip 模型包：解压到 custom_pet_model/ 并启用 */
    private void importZipModel(Uri uri) {
        InputStream is = null;
        java.io.OutputStream os = null;
        File tmp = new File(getFilesDir(), "import_tmp.zip");
        try {
            is = getContentResolver().openInputStream(uri);
            if (is == null) { Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show(); return; }
            os = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush(); os.close(); os = null; is.close(); is = null;

            File dir = Prefs.customModelDir(this);
            PetModel.deleteRecursive(dir);
            if (!dir.mkdirs()) {
                Toast.makeText(this, "无法创建模型目录", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!PetModel.unzip(tmp, dir)) {
                Toast.makeText(this, "解压失败，请确认是有效的 zip", Toast.LENGTH_SHORT).show();
                return;
            }
            // 校验模型有效
            PetModel m = PetModel.loadDir(dir);
            if (m == null || !m.isValid()) {
                PetModel.deleteRecursive(dir);
                Toast.makeText(this, "模型包内未找到有效帧图（需 png/webp/jpg）", Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.setUseCustomImage(this, false);
            Prefs.setCustomAnim(this, false);
            Prefs.setUseCustomModel(this, true);
            Prefs.reloadPet(this);
            Toast.makeText(this, "模型已导入并应用！动作数：" + m.actions().size(),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignore) {}
            try { if (os != null) os.close(); } catch (Exception ignore) {}
            try { tmp.delete(); } catch (Exception ignore) {}
        }
    }

    /** 通过 ContentResolver 查询文件名 */
    private String queryName(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignore) {
        } finally {
            try { if (c != null) c.close(); } catch (Exception ignore) {}
        }
        return null;
    }

    // ================= ex-skill 导入 =================

    /** 选择一个 ex-skill 产物文件（persona.md / memories.md 或含它们的 .zip） */
    // ==================== 从聊天记录蒸馏人格 ====================

    /** 选一个聊天记录导出文件（txt / md / zip 都行） */
    private void pickChatLogFile() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/plain", "text/markdown", "application/zip",
                    "application/x-zip-compressed", "application/octet-stream"});
            startActivityForResult(Intent.createChooser(i,
                    "选择聊天记录（每行形如「老王: 在吗」）"), REQ_DISTILL_CHAT);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 读文件 → 问目标人物 → 后台解析 */
    private void handleDistillSource(final Uri uri) {
        final ProgressDialog pd = UiFactory.progress(this);
        pd.setMessage("正在读取并解析聊天记录\u2026");
        pd.setCancelable(false);
        pd.show();

        new Thread(new Runnable() {
            @Override public void run() {
                String raw = null;
                String err = null;
                try {
                    raw = PersonaDistiller.readUri(MainActivity.this, uri);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fRaw = raw;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { pd.dismiss(); } catch (Exception ignore) {}
                        if (fErr != null || fRaw == null || fRaw.trim().length() == 0) {
                            UiFactory.dialog(MainActivity.this)
                                    .setTitle("读取失败")
                                    .setMessage(fErr != null ? fErr : "文件内容为空。")
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }
                        askTargetThenDistill(fRaw);
                    }
                });
            }
        }).start();
    }

    /** 让用户填目标人物名字（留空 = 取发消息最多的人） */
    private void askTargetThenDistill(final String raw) {
        final EditText et = new EditText(this);
        et.setHint("例如：老王（留空则自动取发言最多的人）");
        UiFactory.dialog(this)
                .setTitle("\u2728 生成人格")
                .setMessage("记录已读取 " + raw.length() + " 字。\n\n要提取谁的人格？")
                .setView(et)
                .setPositiveButton("开始生成", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        runDistill(raw, et.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 两段式：
     *   1) 本地解析 + 统计（不联网，聊天记录不上传）
     *   2) 只把抽样片段交给 LLM 写成 5 层人格文档
     */
    private void runDistill(final String raw, final String target) {
        final String apiKey = Prefs.getApiKey(this);
        if (apiKey == null || apiKey.trim().length() == 0) {
            UiFactory.dialog(this)
                    .setTitle("还没有 API Key")
                    .setMessage("生成人格需要调用一次模型。\n请先在设置里填好 API Key。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        final ProgressDialog pd = UiFactory.progress(this);
        pd.setMessage("正在解析聊天记录\u2026");
        pd.setCancelable(false);
        pd.show();

        new Thread(new Runnable() {
            @Override public void run() {
                final PersonaDistiller.Dig d = PersonaDistiller.parse(raw, target);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!d.ok()) {
                            try { pd.dismiss(); } catch (Exception ignore) {}
                            UiFactory.dialog(MainActivity.this)
                                    .setTitle("解析失败")
                                    .setMessage(d.error)
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }
                        pd.setMessage("正在生成人格文档\u2026\n（这一步要模型写完整篇，可能需要 1-3 分钟）");
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String[] out = new String[1];
                                final String[] ferr = new String[1];
                                int oldTimeout = ApiClient.getReadTimeout();
                                try {
                                    ApiClient.setReadTimeout(300000);
                                    ApiClient.ChatResult r = ApiClient.chatFull(
                                            Prefs.getApiKey(MainActivity.this),
                                            Prefs.getChatModel(MainActivity.this),
                                            PersonaDistiller.buildSystemPrompt(),
                                            null, null, null,
                                            PersonaDistiller.buildUserPrompt(d),
                                            0.6, false, 16000);
                                    if (r == null || !r.ok
                                            || r.reply == null || r.reply.trim().length() == 0) {
                                        ferr[0] = (r == null) ? "无响应"
                                                : (r.error != null && r.error.length() > 0
                                                        ? r.error : "模型返回空内容");
                                    } else {
                                        out[0] = PersonaDistiller.stripFence(r.reply);
                                    }
                                } catch (Throwable t) {
                                    ferr[0] = t.getClass().getSimpleName() + ": " + t.getMessage();
                                } finally {
                                    ApiClient.setReadTimeout(oldTimeout);
                                }
                                final String fOut = out[0];
                                final String fFerr = ferr[0];
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        try { pd.dismiss(); } catch (Exception ignore) {}
                                        if (fOut == null || fOut.trim().length() == 0) {
                                            UiFactory.dialog(MainActivity.this)
                                                    .setTitle("生成失败")
                                                    .setMessage(fFerr == null ? "模型没有返回内容。" : fFerr)
                                                    .setPositiveButton("知道了", null)
                                                    .show();
                                            return;
                                        }
                                        confirmDistill(d, fOut);
                                    }
                                });
                            }
                        }).start();
                    }
                });
            }
        }).start();
    }

    /** 展示生成结果 + 本地合规体检，确认后存文件 & 建预设 */
    private void confirmDistill(final PersonaDistiller.Dig d, final String persona) {
        final List<String> warns = PersonaDistiller.check(persona);
        String name = PersonaDistiller.guessName(persona);

        StringBuilder sb = new StringBuilder();
        sb.append("目标人物：").append(d.target).append("\n");
        sb.append("消息条数：").append(d.total).append("\n");
        sb.append("识别到的其他说话人：")
          .append(d.others.length == 0 ? "（无）" : joinNames(d.others)).append("\n");
        sb.append("人格文档：").append(persona.length()).append(" 字\n");
        sb.append("预设名称：").append(name).append("\n");
        if (persona.length() > PersonaDistiller.MAX_RESULT_CHARS) {
            sb.append("\n⚠ 超出 ").append(PersonaDistiller.MAX_RESULT_CHARS)
              .append(" 字上限，可能无法被导入器接受。\n");
        }
        if (!warns.isEmpty()) {
            sb.append("\n⚠ 体检提醒（").append(warns.size()).append(" 项）：\n");
            for (int i = 0; i < warns.size() && i < 5; i++) {
                sb.append("· ").append(warns.get(i)).append("\n");
            }
        }
        sb.append("\n将把文档存到下载目录，并新建一个性格预设立即启用。");

        UiFactory.dialog(this)
                .setTitle("\u2728 生成完成")
                .setMessage(sb.toString())
                .setPositiveButton("保存并启用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        saveDistilled(d, persona, warns);
                    }
                })
                .setNeutralButton("先看看正文", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        showPreview(persona, null);
                    }
                })
                .setNegativeButton("放弃", null)
                .show();
    }

    private void saveDistilled(PersonaDistiller.Dig d, String persona,
                               List<String> warns) {
        String name = PersonaDistiller.guessName(persona);
        String safeWho = d.target.replaceAll("[^\\u4e00-\\u9fffA-Za-z0-9_-]", "");
        if (safeWho.length() == 0) safeWho = "persona";

        // 存到下载目录，文件名对 SkillImporter 友好（能被 zip/单文件识别）
        File dir = new File("/sdcard/Download/桌宠人格");
        String path = null;
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, safeWho + "_persona.md");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(persona.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            path = f.getAbsolutePath();
        } catch (Exception e) {
            path = null;
        }

        List<String[]> ps = Prefs.getPresets(this);
        ps.add(new String[]{name, persona});
        Prefs.savePresets(this, ps);
        Prefs.setPresetIndex(this, ps.size() - 1);

        String msg = "已启用预设「" + name + "」\n"
                + (path != null ? "文档已存到：\n" + path : "（文档保存失败，但预设已建立）")
                + (warns.isEmpty() ? "" : "\n\n注意体检提醒里的信息，建议自行核对后再长期使用。");
        UiFactory.dialog(this)
                .setTitle("已保存")
                .setMessage(msg)
                .setPositiveButton("看看正文", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        showPreview(persona, null);
                    }
                })
                .setNegativeButton("好", null)
                .show();
    }

    /** 只读预览一篇文章（供多处复用） */
    private void showPreview(String text, String title) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setPadding(40, 30, 40, 30);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        UiFactory.dialog(this)
                .setTitle(title == null ? "人格文档预览" : title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static String joinNames(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length && i < 12; i++) {
            if (i > 0) sb.append("、");
            sb.append(arr[i]);
        }
        if (arr.length > 12) sb.append(" 等 ").append(arr.length).append(" 人");
        return sb.toString();
    }

    private void pickSkillFile() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/markdown", "text/plain", "application/zip",
                    "application/x-zip-compressed", "application/octet-stream"});
            startActivityForResult(Intent.createChooser(i,
                    "选择 ex-skill 的 persona.md / memories.md 或 .zip"), REQ_IMPORT_SKILL);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 后台解析，避免大文件卡住 UI */
    private void handleSkillImport(final Uri uri) {
        final ProgressDialog pd = UiFactory.progress(this);
        pd.setMessage("正在解析 skill 文件\u2026");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override public void run() {
                final SkillImporter.Result r = SkillImporter.fromUri(MainActivity.this, uri);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { pd.dismiss(); } catch (Exception ignore) {}
                        if (!r.ok()) {
                            UiFactory.dialog(MainActivity.this)
                                    .setTitle("导入失败")
                                    .setMessage(r.error)
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }
                        confirmSkillImport(r);
                    }
                });
            }
        }).start();
    }

    /** 导入前展示解析结果，确认后新建预设并切换 */
    private void confirmSkillImport(final SkillImporter.Result r) {
        final String content = r.toPresetContent();
        String msg = "名称：" + r.name + "\n"
                + "人格文本：" + r.persona.length() + " 字\n"
                + "共同记忆：" + r.memories.length() + " 字\n"
                + "合计：" + content.length() + " 字\n\n"
                + "将新建一个性格预设并立即启用（原预设不受影响）。";
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDCE5 导入 ex-skill")
                .setMessage(msg)
                .setPositiveButton("导入并启用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        List<String[]> ps = Prefs.getPresets(MainActivity.this);
                        ps.add(new String[]{r.name, content});
                        Prefs.savePresets(MainActivity.this, ps);
                        Prefs.setPresetIndex(MainActivity.this, ps.size() - 1);
                        Toast.makeText(MainActivity.this,
                                "已导入\u300C" + r.name + "\u300D并切换", Toast.LENGTH_SHORT).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 动作系统总览（P1 起**可编辑**）：列出全部场景 → 点进去改绑 / 调权重 / 预览 / 恢复角色包默认。
     *
     * <p>● = 已绑定（触发时会播），○ = 未绑定（触发时什么都不播，只剩台词/反馈）；
     * 括号里的"已自定义"＝这个场景走的是**用户覆盖层**（{@link SceneBindings}），不是角色包原样。
     *
     * <p>★ 场景内部的三个动作（编辑 / 预览 / 权重 / 恢复）走 {@code setItems} 分流，
     * **不能**用三个按钮：{@code AlertDialog} 同一位置只能有一个按钮，
     * 两个 {@code setNeutralButton} 会互相覆盖 —— 性格预设那一轮就这么废掉过一个入口。
     *
     * <p>★ 装配用的是 {@link ActionSystem#loadForDisplay}，它**已经应用了覆盖层**，
     * 所以这里看到的就是运行期真正会播的那一套（改完立刻能看见）。
     */
    private void showSceneOverview() {
        final ActionSystem as = loadSceneEditor();
        final String cid = CharacterStore.currentId(this);
        final java.util.List<String> ids = new java.util.ArrayList<String>();
        final java.util.List<String> items = new java.util.ArrayList<String>();
        for (String id : SceneRegistry.ids()) { ids.add(id); items.add(sceneRowText(as, cid, id)); }
        for (String id : SceneRegistry.eventIds()) { ids.add(id); items.add(sceneRowText(as, cid, id) + "（事件）"); }
        UiFactory.dialog(this)
                .setTitle("\uD83C\uDFAC 场景与已绑动作（已自定义 " + as.overrideCount() + " 个）")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        editScene(as, ids.get(which));
                    }
                })
                .setNeutralButton("\uD83D\uDCDA 动作库", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { showClipLibrary(as); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 设置页用的动作系统装配（按**当前角色**加载并已应用用户覆盖层；预览也复用它） */
    private ActionSystem loadSceneEditor() {
        ActionSystem as = null;
        try { as = ActionSystem.loadForDisplay(this); } catch (Throwable ignore) {}
        return as;
    }

    /** 总览里的一行：绑定个数 + 是否为用户覆盖 */
    private String sceneRowText(ActionSystem as, String cid, String scene) {
        if (as == null) return SceneRegistry.label(scene) + "：--";
        int n = as.clipsOf(scene).size();
        return (n > 0 ? "\u25CF " : "\u25CB ") + SceneRegistry.label(scene) + "：" + n + " 个"
                + (as.isOverridden(scene) ? "（已自定义）" : "");
    }

    /** 单个场景的操作面：导入动画 / 编辑绑定 / 预览 / 权重 / 恢复角色包默认 */
    private void editScene(final ActionSystem as, final String scene) {
        final int n = (as != null) ? as.clipsOf(scene).size() : 0;
        final boolean over = (as != null) && as.isOverridden(scene);
        final String[] acts = {
                "\uD83D\uDCE5 导入动画到此场景",
                "\u270F\uFE0F 编辑绑定（多选）",
                "\u25B6\uFE0F 预览动作",
                "\uD83D\uDCCF 设置权重",
                "\uD83D\uDD04 恢复角色包默认",
        };
        // ★★ 平台铁律：AlertDialog 一旦 setMessage(...)，setItems(...) 会被**静默吞掉**
        //   （AlertController.setupContent 只在 mMessage == null 时才安装 ListView）。
        //   现象极具误导性：对话框照常弹、只剩一行说明文本，**列表与所有入口一起消失**，
        //   而源码里 setItems 明明写着 —— P1 定版就是这样在模拟器上"看起来有、点不开"的
        //   （见 STATE 轮次㉑）。所以"触发时机"并进 title，绝不与 setItems 共存。
        //   全工程判据见 verify_scene_bindings.py 的"链级 lint"（扫所有 Builder 链）。
        UiFactory.dialog(this)
                .setTitle("\uD83C\uDFAC " + SceneRegistry.label(scene)
                        + " · " + SceneRegistry.hint(scene)
                        + "（已绑 " + n + " 个" + (over ? "，已自定义" : "") + "）")
                .setItems(acts, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (as == null) return;
                        if (which == 0) pickSceneImport(scene);
                        else if (which == 1) editSceneBindings(as, scene);
                        else if (which == 2) previewScene(as, scene);
                        else if (which == 3) editSceneWeights(as, scene);
                        else restoreSceneDefault(as, scene);
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { showSceneOverview(); }
                })
                .show();
    }

    /**
     * 多选改绑某个场景的动作（动作库 = 本角色全部可播动作）。
     *
     * <p>★ 轮次㉚：原来的 {@code setMultiChoiceItems} 在一百多个动作上只能滚，
     * 换成 {@link ClipListPane}（搜索 / 排序 / **只看已选**）。排序里
     * 「已绑定优先」在这个页面正好等于"这个场景现在绑了谁" —— 改绑时最常用。
     */
    private void editSceneBindings(final ActionSystem as, final String scene) {
        final String cid = CharacterStore.currentId(this);
        final java.util.List<String> all = as.allClipIds();
        if (all.isEmpty()) {
            Toast.makeText(this, "当前角色没有动作可绑（先在「形象与动作」里导入角色包）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final java.util.List<String> bound = as.clipsOf(scene);
        final int[] counts = new int[all.size()];
        final java.util.List<Integer> ws = new java.util.ArrayList<Integer>();
        for (int i = 0; i < all.size(); i++) {
            String id = all.get(i);
            // 这个页面里"已绑定"＝**已经绑到本场景**（不是"绑到几个场景"）
            counts[i] = bound.contains(id) ? 1 : 0;
            ws.add(Integer.valueOf(as.weightOf(scene, id)));
        }
        final ClipListPane pane = new ClipListPane(this, uiT(), all, counts, true, null);
        pane.setWeights(ws);
        pane.setPicked(bound);
        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle("\u270F\uFE0F " + SceneRegistry.label(scene) + "：选择动作")
                .setView(pane.view())
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        // ★ 保留已设过的权重：改绑不该把用户调好的 ×N 一起抹掉
                        java.util.List<SceneBindings.Bind> binds = new java.util.ArrayList<SceneBindings.Bind>();
                        java.util.List<String> pick = pane.pickedIds();
                        for (int i = 0; i < pick.size(); i++) {
                            String id = pick.get(i);
                            int old = as.weightOf(scene, id);
                            binds.add(new SceneBindings.Bind(id, old < 1 ? 1 : old));
                        }
                        SceneBindings.setBinds(MainActivity.this, cid, scene, binds);
                        syncOverrides(as);
                        Prefs.reloadPet(MainActivity.this);
                        Toast.makeText(MainActivity.this,
                                "已保存：" + SceneRegistry.label(scene) + "（" + binds.size() + " 个动作）",
                                Toast.LENGTH_SHORT).show();
                        editScene(as, scene);
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { editScene(as, scene); }
                })
                .create();
        dlg.show();
    }

    /** 权重列表：先选动作，再选档位。权重只在**随机抽取**的场景里起作用（见 Group.pick） */
    private void editSceneWeights(final ActionSystem as, final String scene) {
        java.util.List<String> bound = as.clipsOf(scene);
        if (bound.isEmpty()) {
            Toast.makeText(this, "这个场景还没绑动作（先「编辑绑定」）", Toast.LENGTH_SHORT).show();
            return;
        }
        // ★ 没有覆盖时先把角色包当前的绑定**落成一份覆盖**，
        //   否则 SceneBindings.setWeight 找不到可改的清单（会静默什么都不做 —— 典型假功能）
        ensureOverride(as, scene);
        bound = as.clipsOf(scene);
        final java.util.List<String> ids = bound;
        final String[] items = new String[bound.size()];
        for (int i = 0; i < bound.size(); i++) {
            items[i] = bound.get(i) + "  \u00D7" + as.weightOf(scene, bound.get(i));
        }
        UiFactory.dialog(this)
                // ★ 同上：这里原来是 setTitle + setMessage + setItems —— message 会把列表吞掉。
                //   说明文案并进 title。
                .setTitle("\uD83D\uDCCF 权重（" + SceneRegistry.label(scene)
                        + "）：数值越大越常被抽到")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        pickWeight(as, scene, ids.get(which));
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { editScene(as, scene); }
                })
                .show();
    }

    private void pickWeight(final ActionSystem as, final String scene, final String clipId) {
        final int[] vals = {1, 2, 3, 5, 10};
        final String[] labels = {"1（普通）", "2", "3（更常出现）", "5", "10（最常出现）"};
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDCCF " + clipId + " 的权重")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        SceneBindings.setWeight(MainActivity.this, CharacterStore.currentId(MainActivity.this),
                                scene, clipId, vals[which]);
                        syncOverrides(as);
                        Prefs.reloadPet(MainActivity.this);
                        editSceneWeights(as, scene);
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { editSceneWeights(as, scene); }
                })
                .show();
    }

    /** 把当前绑定落成覆盖层（不动任何绑定内容，只为"权重可改"先建一份清单） */
    private void ensureOverride(ActionSystem as, String scene) {
        String cid = CharacterStore.currentId(this);
        if (SceneBindings.isOverridden(this, cid, scene)) return;
        java.util.List<SceneBindings.Bind> binds = new java.util.ArrayList<SceneBindings.Bind>();
        for (PetAnimPack.Clip c : as.bindsOf(scene)) {
            binds.add(new SceneBindings.Bind(c.id, Math.max(1, c.weight)));
        }
        SceneBindings.setBinds(this, cid, scene, binds);
        syncOverrides(as);
    }

    /** 恢复角色包默认 = 删掉这个场景的覆盖（不是写一份"当前默认值"的快照） */
    private void restoreSceneDefault(final ActionSystem as, final String scene) {
        final String cid = CharacterStore.currentId(this);
        if (!SceneBindings.isOverridden(this, cid, scene)) {
            Toast.makeText(this, "这个场景本来就是角色包默认，没有可恢复的改动", Toast.LENGTH_SHORT).show();
            return;
        }
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDD04 恢复角色包默认")
                .setMessage("「" + SceneRegistry.label(scene)
                        + "」将恢复成角色包里写的那一套绑定（你自己改的这条会被清掉）。")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        SceneBindings.clear(MainActivity.this, cid, scene);
                        syncOverrides(as);
                        Prefs.reloadPet(MainActivity.this);
                        Toast.makeText(MainActivity.this,
                                "已恢复：" + SceneRegistry.label(scene), Toast.LENGTH_SHORT).show();
                        editScene(as, scene);
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { editScene(as, scene); }
                })
                .show();
    }

    /** 「全部恢复角色包默认」 */
    private void promptClearAllBindings() {
        final String cid = CharacterStore.currentId(this);
        final int n = SceneBindings.overrideCount(this, cid);
        if (n <= 0) {
            Toast.makeText(this, "还没有自定义过任何场景", Toast.LENGTH_SHORT).show();
            return;
        }
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDD04 全部恢复角色包默认")
                .setMessage("会清掉 " + n + " 个场景的自定义绑定，全部恢复成角色包里写的那一套。\n"
                        + "角色包文件本身不会被改动。")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        SceneBindings.clearAll(MainActivity.this, cid);
                        Prefs.reloadPet(MainActivity.this);
                        Toast.makeText(MainActivity.this, "已全部恢复角色包默认", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 角色中心（P3 起，轮次㉛ 收成一个入口）====================

    /**
     * 「🎭 角色：… ›」→ <b>角色中心</b>（轮次㉛：全工程唯一的角色入口）。
     *
     * <p>用户要求「删除所有形象导入接口 + 删除所有角色导入接口，改为角色切换的模式」
     * ⇒ 原来散在设置页上的「切换角色 / 删除角色 / 导入角色包 / 从电脑导入 / 导出当前角色 /
     * 形象来源 / 上传图片 / 导入模型 / 导入 Live2D / 恢复默认形象」**十行**，
     * 现在只有这一行，进来是这张清单：
     * <ul>
     *   <li>每个角色一行 —— 点一下切过去（● 当前 / ○ 其他）；</li>
     *   <li>➕ 新增角色…（输入名字，立刻落盘并切过去，之后给它加动作/设形象）；</li>
     *   <li>📥 添加角色包（.zip）…（角色包 = 形象 + 动作 + 台词 + 表情 + 性格）；</li>
     *   <li>💻 从电脑导入（USB 拖进本应用文件夹后点这里）；</li>
     *   <li>🎨 形象（当前角色）：X ›（原来那一整套形象导入都在里面）；</li>
     *   <li>📤 导出当前角色（.zip）；</li>
     *   <li>🗑 删除角色…。</li>
     * </ul>
     *
     * <p>★ 一个角色都没有时只列前三项（新增 / 导入 / 从电脑导入）——
     * 没有角色就谈不上"形象 / 导出 / 删除"，列出来只会点了报错。
     * <p>★ 用 {@code setItems} 分流而不是四个按钮：{@code AlertDialog} 同一位置只能有一个按钮
     * （两个 neutral 会互相覆盖，点了没反应 —— 性格预设那轮废掉过一个入口）。
     */
    private void showCharacterHub() {
        final java.util.List<PetCharacter> list = CharacterStore.list(this);
        final String cur = CharacterStore.currentId(this);
        final java.util.List<String> acts = new java.util.ArrayList<String>();
        for (PetCharacter c : list) acts.add(CharPicker.rowLabel(c.displayName(), cur.equals(c.id)));
        final int nChars = acts.size();
        acts.add("\u2795 新增角色…");
        acts.add("\uD83D\uDCE5 添加角色包（.zip）…");
        acts.add("\uD83D\uDCBB 从电脑导入（USB 拖进本应用文件夹后点这里）");
        final boolean has = !list.isEmpty();
        if (has) {
            acts.add("\uD83C\uDFA8 形象（当前角色）："
                    + Appearance.shortLabel(AppearanceStore.modeFor(this)) + " \u203A");
            acts.add("\uD83D\uDCE4 导出当前角色（.zip）");
            acts.add("\uD83D\uDDD1 删除角色…");
        }
        UiFactory.dialog(this)
                .setTitle("\uD83C\uDFAD 角色（共 " + nChars + " 个）")
                .setItems(acts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which < nChars) { switchCharacter(list.get(which).id); return; }
                        int act = which - nChars;
                        if (act == 0) promptNewCharacter();
                        else if (act == 1) pickCharacterPack();
                        else if (act == 2) importFromComputer();
                        else if (act == 3) showAppearancePicker();
                        else if (act == 4) exportCurrentCharacter();
                        else promptDeleteCharacter();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 「➕ 新增角色…」：输入名字 → 立刻建目录并切过去。
     *
     * <p>用户明确选了"**不用单独保存**"：目录一建出来就是真的，
     * 后面「➕ 添加动作」「🎨 形象」都直接写进这个角色。
     * <p>新角色一开始没有动作/形象 ⇒ 素材层"角色包优先、内置兜底"，
     * 所以它先显示内置形象与待机动作，用户加的东西一层层盖上去。
     */
    private void promptNewCharacter() {
        final EditText et = new EditText(this);
        et.setHint("角色名字，如：我的猫");
        et.setSingleLine(true);
        UiFactory.dialog(this)
                .setTitle("\u2795 新增角色")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = (et.getText() == null) ? "" : et.getText().toString().trim();
                        java.util.List<String> names = new java.util.ArrayList<String>();
                        for (PetCharacter c : CharacterStore.list(MainActivity.this)) {
                            names.add(c.displayName());
                        }
                        String err = CharPicker.nameError(names, name);
                        if (err != null) {
                            Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        PetCharacter ch = CharacterStore.create(MainActivity.this, name);
                        if (ch == null) {
                            Toast.makeText(MainActivity.this, "创建失败（存储不可写？）",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        Prefs.reloadPet(MainActivity.this);
                        Toast.makeText(MainActivity.this, CharPicker.createdHint(ch.displayName()),
                                Toast.LENGTH_LONG).show();
                        showSettings();
                        showCharacterHub();     // 建完停在角色中心，方便接着设形象 / 加动作
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 切到某个角色：写 pref → 让悬浮窗整套重载 → 刷新设置页。
     *
     * <p>★ 为什么这里**要**整页重建（别的地方都在躲重建）：动作系统这一整段（当前角色、
     * 场景与已绑动作、动作库、权重提示）**全都跟着角色变**，就地改文案反而不一致。
     * 重建本身已经不闪了（见 STATE 第三节第 9 条 Bug E）。
     *
     * <p>★ 运行期真的会换：动作/台词/表情/性格/场景绑定**各自**都按
     * {@code CharacterStore.currentId()} 取文件（这是 P0～㉑ 一路留下的约定），
     * 所以这里只需要"改 id + 重载"。
     */
    private void switchCharacter(final String id) {
        final String cur = CharacterStore.currentId(this);
        if (!CharPicker.switchNeeded(cur, id)) {
            Toast.makeText(this, "已经是当前角色了", Toast.LENGTH_SHORT).show();
            return;
        }
        CharacterStore.setCurrentId(this, id);
        PetCharacter c = CharacterStore.current(this);
        android.util.Log.i("DSPChar", "切换角色 -> " + id + " (" + c.displayName() + ")");
        Prefs.reloadPet(this);
        Toast.makeText(this, "已切换到「" + c.displayName() + "」", Toast.LENGTH_LONG).show();
        showSettings();
    }

    /** 「🗑 删除角色…」：列出全部角色（轮次㉛ 起没有"内置不能删"这回事）→ 确认 → 连它的绑定/素材一起清 */
    private void promptDeleteCharacter() {
        final java.util.List<PetCharacter> list = CharacterStore.list(this);
        final java.util.List<PetCharacter> del = new java.util.ArrayList<PetCharacter>();
        final java.util.List<String> labels = new java.util.ArrayList<String>();
        for (PetCharacter c : list) {
            if (!CharPicker.deletable(c.id)) continue;
            del.add(c);
            labels.add(CharPicker.deleteRowLabel(c.displayName()));
        }
        if (del.isEmpty()) {
            Toast.makeText(this, "还没有角色可删（先新增或导入一个）", Toast.LENGTH_SHORT).show();
            return;
        }
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDDD1 删除哪个角色？")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        confirmDeleteCharacter(del.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteCharacter(final PetCharacter ch) {
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDDD1 删除「" + ch.displayName() + "」")
                .setMessage(CharPicker.deleteConfirmMessage(ch.displayName()))
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        // ★ 删的正好是当前角色时会**落到剩下的第一个角色**（轮次㉛ 起；
                        //   一个都不剩就回"没有角色"，桌宠退回内置素材兜底）——
                        //   见 CharPicker.currentAfterDelete。所以删完必须重载一次，
                        //   否则桌宠还停在已删角色的素材上。
                        boolean wasCurrent = ch.id.equals(CharacterStore.currentId(MainActivity.this));
                        CharacterStore.delete(MainActivity.this, ch.id);
                        if (wasCurrent) Prefs.reloadPet(MainActivity.this);
                        Toast.makeText(MainActivity.this,
                                "已删除「" + ch.displayName() + "」"
                                        + (wasCurrent ? "，已切到剩下的角色" : ""),
                                Toast.LENGTH_LONG).show();
                        showSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 场景里选一个动作预览；只有一个就直接预览 */
    private void previewScene(final ActionSystem as, final String scene) {
        final java.util.List<String> bound = as.clipsOf(scene);
        if (bound.isEmpty()) {
            Toast.makeText(this, "这个场景还没绑动作，没有可预览的", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bound.size() == 1) { previewClip(as, scene, bound.get(0)); return; }
        UiFactory.dialog(this)
                .setTitle("\u25B6\uFE0F 预览哪个动作（" + SceneRegistry.label(scene) + "）")
                .setItems(bound.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        previewClip(as, scene, bound.get(which));
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { editScene(as, scene); }
                })
                .show();
    }

    /**
     * 在对话框里试播一个动作。
     *
     * <p>★ 直接用素材层的 {@link PetAnimPack#getDrawable(String)}（返回 AnimatedImageDrawable），
     * **不绕后台服务** —— 那些素材本来就在本进程里，为了"看一眼"去发一条广播让悬浮窗播，
     * 既慢又要处理"服务不在"的分支。
     *
     * <p>动图**必须显式 start()**：ImageView 只是 setImageDrawable 的话，
     * 在部分 ROM 上第一帧之后就停住（设置页原有的形象预览也是这么处理的）。
     */
    private void previewClip(final ActionSystem as, final String scene, final String clipId) {
        final PetAnimPack pk = (as != null) ? as.pack() : null;
        Drawable dr = null;
        try { if (pk != null) dr = pk.getDrawable(clipId); } catch (Throwable ignore) {}
        final String where = (scene == null) ? "动作库" : SceneRegistry.label(scene);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, 0);
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (240 * getResources().getDisplayMetrics().density)));
        if (dr != null) {
            iv.setImageDrawable(dr);
            if (dr instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable aid = (AnimatedImageDrawable) dr;
                aid.setRepeatCount(-1);
                try { aid.start(); } catch (Throwable ignore) {}
            }
        }
        col.addView(iv);
        // ★ 兜底值不能给 0：解析不出来时会显示「约 0 ms」（模拟器实测就撞到了这个 ——
        //   真因是 PetAnimPack.parseWebpDuration 的 fourcc 字节序写反，已修）。
        //   这里同时用秒来显示，10 秒的待机动画写 "约 10050 ms" 很难读。
        int ms = 0;
        try { if (pk != null) ms = pk.clipDurationMs(clipId, 3500); } catch (Throwable ignore) {}
        TextView info = new TextView(this);
        info.setText(dr == null
                ? "找不到这个动作的素材（角色包里没有 " + clipId + " 对应的文件）"
                : (where + " / " + clipId
                   + (dr instanceof AnimatedImageDrawable
                      ? ("　单轮约 " + (ms / 1000.0) + " 秒　动图循环")
                      : "　静帧（单帧动作，播一次的场景按固定时长收尾）")));
        info.setPadding(0, pad / 2, 0, 0);
        col.addView(info);
        AlertDialog.Builder b = UiFactory.dialog(this)
                .setTitle("\u25B6\uFE0F 预览：" + clipId)
                .setView(col);
        if (scene != null) {
            b.setPositiveButton("返回", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { editScene(as, scene); }
            });
        }
        b.setNegativeButton("关闭", null).show();
    }

    /**
     * 动作库：本角色全部可播动作，标出"已绑到几个场景"；点一个即可预览。
     * <p>这是"我到底有哪些动作可用"的入口 —— 场景页只回答"这个场景绑了谁"。
     *
     * <p>★ 轮次㉚：一百多个动作**不能再靠滚**（待办 0m ③）⇒ 换成
     * {@link ClipListPane}（搜索 + 排序 + 计数）。原来的 {@code setItems} 列表
     * 装的是平台自己的 ListView，加不了搜索框，所以这里改走 {@code setView}。
     */
    private void showClipLibrary() { showClipLibrary(loadSceneEditor()); }

    private void showClipLibrary(final ActionSystem as) {
        final java.util.List<String> all = (as != null) ? as.allClipIds()
                : new java.util.ArrayList<String>();
        if (all.isEmpty()) {
            Toast.makeText(this, "当前角色没有动作（先在「形象与动作」里导入角色包）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final int[] counts = new int[all.size()];
        for (int i = 0; i < all.size(); i++) counts[i] = boundSceneCount(as, all.get(i));
        // ★ 单选对话框**不会自己关**（setItems 才会）：点一行要手动 dismiss，
        //   否则预览对话框关掉以后又露出下面这个动作库，看起来像"点了没反应"。
        final AlertDialog[] ref = new AlertDialog[1];
        ClipListPane pane = new ClipListPane(this, uiT(), all, counts, false,
                new ClipListPane.Listener() {
                    @Override public void onPick(String id) {
                        if (ref[0] != null) ref[0].dismiss();
                        previewClip(as, null, id);
                    }
                    @Override public void onToggle(String id, boolean checked) {}
                });
        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle("\uD83D\uDCDA 动作库（共 " + all.size() + " 个动作）")
                .setView(pane.view())
                .setNeutralButton("\u2795 添加动作", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { pickActionImages(); }
                })
                .setNegativeButton("关闭", null)
                .create();
        ref[0] = dlg;
        dlg.show();
    }

    private static int boundSceneCount(ActionSystem as, String clipId) {
        if (as == null || clipId == null) return 0;
        int n = 0;
        for (String s : SceneRegistry.allIds()) if (as.clipsOf(s).contains(clipId)) n++;
        return n;
    }

    /** 把刚落盘的覆盖层重新装到这份装配上（否则返回上一级看到的还是改之前的状态） */
    private void syncOverrides(ActionSystem as) {
        if (as == null) return;
        try { as.applyOverrides(SceneBindings.loadRoot(this, CharacterStore.currentId(this))); }
        catch (Throwable ignore) {}
    }

    /**
     * 「自定义动作菜单」：列分类 + 新建 / 进入编辑 / 删除 / **调整顺序**。
     *
     * <p>数据存 {@code filesDir/menus/<角色 id>.json}（见 {@link ActionMenuConfig}），
     * 按角色隔离，内置角色也能用。
     *
     * <p>★ 轮次㉚：「调整分类顺序」不是装饰 —— 三击桌宠弹出的菜单里，
     * **自定义分类排在最前、顺序就是文件里的顺序**（见 {@code FloatingService.classifyAnims}）。
     */
    private void showMenuCategoriesEditor() {
        final String cid = CharacterStore.currentId(this);
        final java.util.LinkedHashMap<String, java.util.List<String>> cats =
                ActionMenuConfig.categories(this, cid);
        final java.util.List<String> labels = new java.util.ArrayList<String>(cats.keySet());
        final java.util.List<String> items = new java.util.ArrayList<String>(labels);
        items.add("\uFF0B 新建分类");
        items.add("\u2261 调整分类顺序（拖动）");
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDDC2 自定义动作菜单（" + cats.size() + " 个分类）")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == items.size() - 2) { promptNewMenuCategory(cid); return; }
                        if (which == items.size() - 1) { promptReorderCategories(cid, labels); return; }
                        editMenuCategory(cid, labels.get(which));
                    }
                })
                .setNeutralButton("删除分类", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { promptDeleteMenuCategory(cid, labels); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 「≡ 调整分类顺序」：拖完落盘（顺序 = 三击菜单里的分类顺序） */
    private void promptReorderCategories(final String cid, final java.util.List<String> labels) {
        if (labels == null || labels.size() < 2) {
            Toast.makeText(this, "至少要两个分类才谈得上排序", Toast.LENGTH_SHORT).show();
            return;
        }
        showDragSortDialog("\uD83D\uDDC2 调整分类顺序", labels, new OnOrderSaved() {
            @Override public void save(java.util.List<String> order) {
                ActionMenuConfig.reorderCategories(MainActivity.this, cid, order);
                Toast.makeText(MainActivity.this, "已保存分类顺序", Toast.LENGTH_SHORT).show();
                showMenuCategoriesEditor();
            }
        });
    }

    /** 「≡ 拖动排序」对话框的保存回调 */
    private interface OnOrderSaved {
        void save(java.util.List<String> order);
    }

    /**
     * 通用「拖动排序」对话框（轮次㉚）：{@link DragSortPane} + 保存 / 取消。
     *
     * <p>★ 只用在本工程里**顺序真的有用**的地方（自定义菜单的分类与分类内动作）；
     * 场景绑定顺序不参与播放抽取（按权重随机），所以那里不给拖拽 —— 详见 {@link DragSortPane}。
     */
    private void showDragSortDialog(String title, java.util.List<String> items,
                                    final OnOrderSaved onSave) {
        final DragSortPane pane = new DragSortPane(this, uiT(), items);
        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle(title)
                .setView(pane.view())
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (onSave != null) onSave.save(pane.items());
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private void promptNewMenuCategory(final String cid) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("分类名，如：我的最爱");
        UiFactory.dialog(this)
                .setTitle("新建动作分类")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = et.getText() == null ? "" : et.getText().toString().trim();
                        if (name.isEmpty()) return;
                        if (!ActionMenuConfig.addCategory(MainActivity.this, cid, name)) {
                            Toast.makeText(MainActivity.this,
                                    "创建失败（重名，或已达 "
                                            + ActionMenuConfig.MAX_CATEGORIES + " 个上限）",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        editMenuCategory(cid, name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptDeleteMenuCategory(final String cid, final java.util.List<String> labels) {
        if (labels.isEmpty()) {
            Toast.makeText(this, "还没有自定义分类", Toast.LENGTH_SHORT).show();
            return;
        }
        UiFactory.dialog(this)
                .setTitle("删除哪个分类？")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        ActionMenuConfig.removeCategory(MainActivity.this, cid, labels.get(which));
                        Toast.makeText(MainActivity.this, "已删除分类", Toast.LENGTH_SHORT).show();
                        showMenuCategoriesEditor();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 往某个自定义分类里挑动作（多选，取自当前角色的动作库）。
     *
     * <p>★ 轮次㉚：换成 {@link ClipListPane}（搜索 / 排序），并多一个
     * 「≡ 拖动排序」——自定义分类里的动作顺序**就是三击菜单里的显示顺序**，
     * 所以这个顺序值得让用户拖（场景绑定顺序则不值得，见 {@link DragSortPane}）。
     */
    private void editMenuCategory(final String cid, final String label) {
        ActionSystem as = null;
        try { as = ActionSystem.loadForDisplay(this); } catch (Throwable ignore) {}
        final java.util.List<String> all = (as != null) ? as.allClipIds() : new java.util.ArrayList<String>();
        if (all.isEmpty()) {
            Toast.makeText(this, "当前角色没有动作可绑（先在「形象与动作」里导入角色包）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final int[] counts = new int[all.size()];
        for (int i = 0; i < all.size(); i++) counts[i] = boundSceneCount(as, all.get(i));
        java.util.List<String> cur = ActionMenuConfig.categories(this, cid).get(label);
        final ClipListPane pane = new ClipListPane(this, uiT(), all, counts, true, null);
        pane.setPicked(cur);
        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle("\uD83D\uDDC2 " + label)
                .setView(pane.view())
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        java.util.List<String> pick = pane.pickedIds();
                        ActionMenuConfig.setClips(MainActivity.this, cid, label, pick);
                        Toast.makeText(MainActivity.this,
                                "已保存：" + label + "（" + pick.size() + " 个动作）", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("\u2261 拖动排序", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        // ★ 按**当前勾选**进拖拽（这个对话框一关就没了，所以先把选中的带过去）
                        java.util.List<String> pick = pane.pickedIds();
                        if (pick.isEmpty()) {
                            Toast.makeText(MainActivity.this, "先勾几个动作再排序", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showDragSortDialog("\uD83D\uDDC2 " + label + "：调整菜单顺序", pick,
                                new OnOrderSaved() {
                                    @Override public void save(java.util.List<String> order) {
                                        ActionMenuConfig.setClips(MainActivity.this, cid, label, order);
                                        Toast.makeText(MainActivity.this,
                                                "已保存：" + label + "（" + order.size() + " 个动作）",
                                                Toast.LENGTH_SHORT).show();
                                        showMenuCategoriesEditor();
                                    }
                                });
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private static int countChecked(boolean[] a) {
        int n = 0;
        for (boolean b : a) if (b) n++;
        return n;
    }

    /**
     * 导出当前角色为 zip（SAF：让用户自己选存哪、也能直接分享）。
     *
     * <p>★ 轮次㉑ 起语义变了：导出的是**按当前绑定打包**的自洽包
     * （配置 = 有效绑定（角色包＋你的改动），clips 只含被引用的动作，台词/表情/性格一起带上）。
     * <ul>
     *   <li><b>内置角色也能导出</b>了 —— 按当前绑定导出不再是"只有 1 个动作的空包"
     *       （你往里加的动作也在里面）。</li>
     *   <li>确实"一点都没改过"时**提示**一次（不是拦死）：这样导出的包会带上
     *       内置基底那一整套（可能有 300 MB 量级）。</li>
     * </ul>
     */
    private void exportCurrentCharacter() {
        final PetCharacter ch = CharacterStore.current(this);
        if (ch == null) {
            Toast.makeText(this, "读不到当前角色", Toast.LENGTH_LONG).show();
            return;
        }
        boolean pristine = false;
        int n = 0;
        try {
            n = SceneBindings.overrideCount(this, ch.id) + SceneBindings.userClipIds(this, ch.id).size();
            pristine = (n == 0);
        } catch (Throwable ignore) {}
        if (!pristine) { startExport(ch); return; }
        // ★ 提示而非拦截（原来的"内置角色不能导出"已经取消：用户的追加动作让它变得有意义）
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDCE4 导出当前角色")
                .setMessage("这个角色还没有任何你自己的改动。\n"
                        + "导出的包会按**当前绑定**打包，也就是内置基底那一整套动作"
                        + "（可能 300 MB 量级），导入回来与原样一致。\n\n要继续吗？")
                .setPositiveButton("继续导出", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { startExport(ch); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 配色方案的导出 / 导入（P1 · 轮次53）====================
    //
    // 形态照工程既有的角色包那条：导出走 SAF 的 ACTION_CREATE_DOCUMENT，
    // 导入有两条路 —— 系统文件选择器，或"从电脑导入"（扫 Android/data/<包>/files 里的 .json）。
    // ★ 后一条不是凑数：adb push / MTP 放进**本应用的外部私有目录**的文件，应用自己读得到
    //   （角色包那段注释里记着原因：分区存储按调用方判定归属），
    //   而且它让"导入"这条链路**能被设备脚本自动验证**（文件选择器没法自动化）。

    /**
     * 「🩺 配色体检」：逐条列出当前配色下每一处文字/主色的真实对比度。
     * ★ 轮次55 起量的是"**卡片叠光斑极值**"（最暗/最亮那团光斑底下各算一遍、取更差的）
     * —— 光斑能调浓之后，只量底色会给出**假绿**的读数。
     */
    private void showThemeAudit() {
        final Tokens.Set t = uiT();
        UiFactory.dialog(this)
                .setTitle("\uD83E\uDE7A 配色体检（当前这一套）")
                .setMessage("口径：文字落在“卡片叠光斑极值”上（最坏的那一处）\n\n"
                        + ThemeOverride.auditReport(t))
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 「⬆ 导出这套配色…」：SAF 选保存位置，真正的写在 onActivityResult 里 */
    private void startThemeExport() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, ThemeOverride.suggestedFileName(this));
            startActivityForResult(i, REQ_EXPORT_THEME);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开保存对话框：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 「⬇ 导入配色…」：先选走哪条路 */
    private void showThemeImportPicker() {
        UiFactory.dialog(this)
                .setTitle("导入配色方案")
                .setItems(new String[] { "从文件选择（.json）", "从电脑导入（扫 Android/data/.../files）" },
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();          // 先关自己再开下一个（老坑）
                        if (which == 0) startThemeImportFile();
                        else importThemeFromComputer();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startThemeImportFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "application/json", "text/plain", "application/octet-stream" });
            startActivityForResult(i, REQ_IMPORT_THEME);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件选择器：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 「从电脑导入」：扫本应用外部私有目录里的 .json（与角色包同款做法） */
    private void importThemeFromComputer() {
        java.io.File ext = getExternalFilesDir(null);
        final java.util.List<String> jsons = new java.util.ArrayList<String>();
        if (ext != null && ext.isDirectory()) {
            java.io.File[] fs = ext.listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs);
                for (java.io.File f : fs) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".json")) {
                        jsons.add(f.getAbsolutePath());
                    }
                }
            }
        }
        if (jsons.isEmpty()) {
            Toast.makeText(this, "没找到配色文件。请把 .json 放进\n"
                    + "内部存储/Android/data/" + getPackageName() + "/files/", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[jsons.size()];
        for (int i = 0; i < jsons.size(); i++) {
            names[i] = new java.io.File(jsons.get(i)).getName();
        }
        UiFactory.dialog(this)
                .setTitle("选择要导入的配色文件")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        String text = readTextFile(jsons.get(which));
                        confirmImportedTheme(text, "来自 " + new java.io.File(jsons.get(which)).getName());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String readTextFile(String path) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
        }
    }

    /**
     * 导入的**最后一道闸**：先校验（不是配色方案就直说），再把"里面是什么"摆给用户看，
     * 确认后才落盘。★ 不静默套用 —— 导入的是别人的文件，用户有权先看清再决定。
     */
    private void confirmImportedTheme(String text, String source) {
        final ThemeOverride.V v = ThemeOverride.fromJsonText(text);
        if (v == null) {
            UiFactory.dialog(this)
                    .setTitle("导入配色")
                    .setMessage("这个文件不是配色方案（应当是本应用导出的 .json）。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        UiFactory.dialog(this)
                .setTitle("导入这份配色？")
                .setMessage(source + "\n\n" + ThemeOverride.describe(v)
                        + "\n\n只会覆盖它写了的部分，其余仍用你当前的内置配色。")
                .setPositiveButton("应用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        ThemeOverride.save(MainActivity.this, v);
                        afterThemeCustomChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 打开 SAF 保存对话框（真正的导出在 onActivityResult 里跑后台线程） */
    private void startExport(PetCharacter ch) {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, ActionMenuConfig.sanitize(ch.id) + ".zip");
            startActivityForResult(i, REQ_EXPORT_CHARACTER);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开保存对话框：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 「从电脑导入」：USB 连接后，电脑可以直接把 zip 拖进
     * {@code 内部存储/Android/data/com.deepseek.pet/files/}（MTP 允许，且**不需要任何权限**）；
     * 这里扫描该目录里的 *.zip 让用户挑一个导入。
     *
     * <p>★ 为什么不直接读用户在电脑上放的那个路径：分区存储（Android 11+）下，
     * {@code adb push} / MTP 以 shell 身份写入的文件，**应用自己反而读不到**
     * （实测 isFile=false）—— 但放进本应用的 {@code Android/data/<包>/files/} 后
     * 由**本应用**发起读取是允许的（FUSE 按调用方判定归属）。
     */
    private void importFromComputer() {
        java.io.File ext = getExternalFilesDir(null);
        java.util.List<String> zips = new java.util.ArrayList<String>();
        if (ext != null && ext.isDirectory()) {
            java.io.File[] fs = ext.listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs);
                for (java.io.File f : fs) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".zip")) zips.add(f.getAbsolutePath());
                }
            }
        }
        if (zips.isEmpty()) {
            Toast.makeText(this, "没找到角色包。请先用 USB 把 .zip 拖进\n"
                    + "内部存储/Android/data/com.deepseek.pet/files/",
                    Toast.LENGTH_LONG).show();
            return;
        }
        UiFactory.dialog(this)
                .setTitle("选择要导入的角色包")
                .setItems(zips.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final String path = zips.get(which);
                        Toast.makeText(MainActivity.this, "正在导入…", Toast.LENGTH_SHORT).show();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                CharacterImporter.Result r;
                                try {
                                    r = CharacterImporter.install(MainActivity.this,
                                            new java.io.FileInputStream(path));
                                } catch (Throwable t) {
                                    r = new CharacterImporter.Result();
                                    r.error = "读不到文件：" + t;
                                }
                                final CharacterImporter.Result rr = r;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { afterCharacterImport(rr); }
                                });
                            }
                        }, "pet-char-import").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选一个角色包 zip（SAF） */
    private void pickCharacterPack() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed", "application/octet-stream"});
            startActivityForResult(i, REQ_IMPORT_CHARACTER);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件选择器：" + t, Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 待办 0j 的 C：App 内「添加动作」====================

    /**
     * 「➕ 添加动作」第一步：相册**多选**（动图或静态图都行）。
     *
     * <p>这条路存在的意义：用户想在桌宠上用自己的动作时，改造前只有"打 zip 从电脑导入"
     * 一条路（他自己说的「角色动作包太麻烦了」）。现在手机相册里选几张图就能用。
     * 不用 zip、不用重导整包、不用重启。
     */
    private void pickActionImages() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            // ★ 显式列出 MIME：部分 ROM 的相册选图器只按 MIME 过滤，
            //   不给的话会把 webp 动图灰掉（本工程的动作素材主要就是 webp）。
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "image/webp", "image/gif", "image/png", "image/jpeg"});
            startActivityForResult(i, REQ_ADD_ACTIONS);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开图片选择器：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 「添加动作」第二步：选目标场景。
     * <p>第一项是「只加入动作库（先不绑定）」—— 用户常常只是想先把素材放进来，
     * 之后再在场景页里挑，硬要他当场选一个场景反而是负担。
     */
    private void handleAddActions(Intent data) {
        final List<Uri> uris = new java.util.ArrayList<Uri>();
        final List<String> names = new java.util.ArrayList<String>();
        try {
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) { uris.add(u); names.add(queryDisplayName(u)); }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
                names.add(queryDisplayName(data.getData()));
            }
        } catch (Throwable ignore) {}
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有选中图片", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> scenes = new java.util.ArrayList<String>();
        scenes.add(null);                       // null = 只进动作库
        for (String id : SceneRegistry.ids()) scenes.add(id);
        for (String id : SceneRegistry.eventIds()) scenes.add(id);
        final String[] items = new String[scenes.size()];
        items[0] = "只加入动作库（先不绑定）";
        for (int i = 1; i < scenes.size(); i++) {
            String id = scenes.get(i);
            items[i] = SceneRegistry.label(id) + (SceneRegistry.isEvent(id) ? "（事件）" : "");
        }
        UiFactory.dialog(this)
                .setTitle("\u2795 把 " + uris.size() + " 个动作加到哪？")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        saveActions(uris, names, scenes.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 「添加动作」第三步：复制文件进当前角色的 {@code anim/clips/} +（可选）绑到场景。
     *
     * <p>★ 两条必须一起做的接线，缺一条就是"选了图没反应"：
     * <ol>
     *   <li>文件要落进 {@code anim/clips/} —— 素材层按目录扫描认识它（{@link PetAnimPack}）；</li>
     *   <li>绑定走 P1 的用户覆盖层 {@link SceneBindings} —— **不重导整包**、不动角色包文件，
     *       而且"恢复角色包默认"随时可逆。</li>
     * </ol>
     */
    private void saveActions(final List<Uri> uris, final List<String> names, final String scene) {
        final PetCharacter ch = CharacterStore.current(this);
        if (ch == null) {
            Toast.makeText(this, "读不到当前角色，添加失败", Toast.LENGTH_LONG).show();
            return;
        }
        final ProgressDialog pd = UiFactory.progress(this);
        pd.setMessage("正在添加 " + uris.size() + " 个动作…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() { @Override public void run() {
            final List<String> added = new java.util.ArrayList<String>();
            int fail = 0;
            int renamed = 0;
            java.util.List<String> taken = new java.util.ArrayList<String>();
            try { taken = loadSceneEditor().allClipIds(); } catch (Throwable ignore) {}
            for (int i = 0; i < uris.size(); i++) {
                String mime = null;
                try { mime = getContentResolver().getType(uris.get(i)); } catch (Throwable ignore) {}
                String id = CharacterStore.addClipFromUri(MainActivity.this, ch, uris.get(i),
                        names.get(i), mime, taken);
                if (id == null) { fail++; continue; }
                if (!id.equals(ClipNaming.safeClipId(names.get(i)))) renamed++;
                added.add(id);
                taken.add(id);
            }
            if (scene != null && !added.isEmpty()) bindActionsToScene(scene, added);
            final int fok = added.size(), ffail = fail, fren = renamed;
            runOnUiThread(new Runnable() { @Override public void run() {
                try { pd.dismiss(); } catch (Throwable ignore) {}
                String msg = "已添加 " + fok + " 个动作"
                        + (ffail > 0 ? "，跳过 " + ffail + " 个（读不到文件）" : "")
                        + (fren > 0 ? "，" + fren + " 个重名已改存为「名字_2」" : "");
                msg += (scene == null)
                        ? "\n已进动作库（还没绑定到场景，可随时绑）"
                        : ("\n已绑定到「" + SceneRegistry.label(scene) + "」");
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                Prefs.reloadPet(MainActivity.this);
                showSettings();
            }});
        }}, "pet-add-actions").start();
    }

    /**
     * 把刚添加的动作**追加**绑到某个场景。
     *
     * <p>★ 覆盖层的语义是"整个场景替换"（见 {@link ActionSystem#applyOverrides}），
     * 所以必须先把该场景**当前生效**的绑定（角色包默认 **或** 用户已改的那份）读出来，
     * 再把新动作并进去；直接 {@code setClips(只放新动作)} 会把角色包原有的绑定**整场抹掉**。
     * 追加逻辑本身抽在 {@link SceneBindings#appendBinds}（纯函数、可离线断言）。
     */
    private void bindActionsToScene(String scene, List<String> added) {
        String cid = CharacterStore.currentId(this);
        ActionSystem as = loadSceneEditor();
        List<SceneBindings.Bind> cur = new java.util.ArrayList<SceneBindings.Bind>();
        if (as != null) {
            for (PetAnimPack.Clip c : as.bindsOf(scene)) {
                cur.add(new SceneBindings.Bind(c.id, Math.max(1, c.weight)));
            }
        }
        List<SceneBindings.Bind> binds = SceneBindings.appendBinds(cur, added);
        SceneBindings.setBinds(this, cid, scene, binds);
        android.util.Log.i("DSPBind", "添加动作已绑场景=" + scene + " 新增=" + added.size()
                + " 合计=" + binds.size());
    }

    // ==================== 场景内「📥 导入动画到此场景」（用户追加要求）====================

    /** 「📥 导入动画到此场景」的目标场景（onActivityResult 只剩 Intent，用它记住选了谁） */
    private String pendingSceneImport;

    /**
     * 从某个场景里直接导入动画：SAF 多选 → 复制到**用户素材目录** → 追加绑到**这个场景**。
     *
     * <p>与「➕ 添加动作」的区别：那个是"先收素材、再挑场景"，这个是"**我在看这个场景，
     * 直接给它加动画**" —— 少一步、也不会加错场景。
     */
    private void pickSceneImport(final String scene) {
        pendingSceneImport = scene;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "image/webp", "image/gif", "image/png", "image/jpeg"});
            startActivityForResult(i, REQ_IMPORT_SCENE_CLIPS);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开图片选择器：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 场景内导入的落盘 + 绑定。（请求码回来时只剩 Intent，目标场景存在字段里。）
     * <p>★ 结束必须 {@code Prefs.reloadPet} 并回到该场景的操作面 —— 否则用户看到的是
     * "已绑 N 个"没变，以为没成功。
     */
    private void handleSceneImport(Intent data, final String scene) {
        final List<Uri> uris = new java.util.ArrayList<Uri>();
        final List<String> names = new java.util.ArrayList<String>();
        try {
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) { uris.add(u); names.add(queryDisplayName(u)); }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
                names.add(queryDisplayName(data.getData()));
            }
        } catch (Throwable ignore) {}
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有选中图片", Toast.LENGTH_SHORT).show();
            return;
        }
        final PetCharacter ch = CharacterStore.current(this);
        if (ch == null) {
            Toast.makeText(this, "读不到当前角色，导入失败", Toast.LENGTH_LONG).show();
            return;
        }
        final ProgressDialog pd = UiFactory.progress(this);
        pd.setMessage("正在导入 " + uris.size() + " 个动画…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() { @Override public void run() {
            final List<String> added = new java.util.ArrayList<String>();
            int fail = 0, renamed = 0;
            java.util.List<String> taken = new java.util.ArrayList<String>();
            try { taken = loadSceneEditor().allClipIds(); } catch (Throwable ignore) {}
            for (int i = 0; i < uris.size(); i++) {
                String mime = null;
                try { mime = getContentResolver().getType(uris.get(i)); } catch (Throwable ignore) {}
                String id = CharacterStore.addClipFromUri(MainActivity.this, ch, uris.get(i),
                        names.get(i), mime, taken);
                if (id == null) { fail++; continue; }
                if (!id.equals(ClipNaming.safeClipId(names.get(i)))) renamed++;
                added.add(id);
                taken.add(id);
            }
            if (!added.isEmpty()) bindActionsToScene(scene, added);
            final int fok = added.size(), ffail = fail, fren = renamed;
            runOnUiThread(new Runnable() { @Override public void run() {
                try { pd.dismiss(); } catch (Throwable ignore) {}
                // ★ 如实汇报：成功几个、跳过几个（为什么）、几个重名改了名。绝不"看起来成功了"。
                String msg = "「" + SceneRegistry.label(scene) + "」已导入 " + fok + " 个动作";
                if (ffail > 0) msg += "；跳过 " + ffail + " 个（读不到文件）";
                if (fren > 0) msg += "；" + fren + " 个重名已改存为「名字_2」";
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                Prefs.reloadPet(MainActivity.this);
                editScene(loadSceneEditor(), scene);
            }});
        }}, "pet-scene-import").start();
    }

    private void pickImage() {
        try {
            Intent i = new Intent("android.intent.action.GET_CONTENT");
            i.setType("image/*");
            i.addCategory("android.intent.category.OPENABLE");
            startActivityForResult(Intent.createChooser(i, "选择桌宠图片"), REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开相册：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // ★ 头像 / 切换视频 的结果回调（两条独立链路，各自处理各自的请求码）
        if (requestCode == PetAvatar.REQ_PICK) {
            PetAvatar.handleResult(this, requestCode, resultCode, data, new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, "头像已更新", Toast.LENGTH_SHORT).show();
                    showSettings();
                }
            });
            return;
        }
        if (requestCode == DshModeEntry.REQ_VIDEO) {
            DshModeEntry.handleResult(this, requestCode, resultCode, data, null);
            return;
        }
        if (requestCode == REQ_IMPORT_MODEL && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            handleModelImport(data.getData());
            return;
        }
        if (requestCode == REQ_IMPORT_L2D && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importL2DPackage(data.getData());
            return;
        }
        if (requestCode == REQ_IMPORT_CHARACTER && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            final Intent d = data;
            Toast.makeText(this, "正在导入角色包…", Toast.LENGTH_SHORT).show();
            new Thread(new Runnable() {
                @Override public void run() {
                    CharacterImporter.Result r;
                    try {
                        r = CharacterImporter.install(MainActivity.this,
                                getContentResolver().openInputStream(d.getData()));
                    } catch (Throwable t) {
                        r = new CharacterImporter.Result();
                        r.error = "读不到所选文件：" + t;
                    }
                    final CharacterImporter.Result rr = r;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { afterCharacterImport(rr); }
                    });
                }
            }, "pet-char-import").start();
            return;
        }
        if (requestCode == REQ_EXPORT_CHARACTER && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            final PetCharacter ch = CharacterStore.current(this);
            final Intent d = data;
            new Thread(new Runnable() {
                @Override public void run() {
                    CharacterExporter.Result r;
                    try {
                        r = CharacterExporter.export(MainActivity.this, ch,
                                getContentResolver().openOutputStream(d.getData()));
                    } catch (Throwable t) {
                        r = new CharacterExporter.Result();
                        r.error = "写不出去：" + t;
                    }
                    final CharacterExporter.Result rr = r;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            android.util.Log.i("DSPChar", "export done ok=" + rr.ok
                                    + " files=" + rr.files + " bytes=" + rr.bytes
                                    + " clips=" + rr.clips + " scenes=" + rr.scenes
                                    + " appear=" + rr.appearanceFiles
                                    + " missing=" + rr.missing.size()
                                    + " err=" + rr.error);
                            String msg = "已导出角色包：" + rr.files + " 个文件，"
                                    + (rr.bytes / 1048576) + " MB（" + rr.clips + " 个动作 / "
                                    + rr.scenes + " 个场景，按当前绑定打包）";
                            // ★ 待办 0m ①：形象有没有跟着走，必须让用户看得见 ——
                            //   否则"发给别人对方没脸"这件事要等对方反馈才知道。
                            msg += rr.hasAppearance()
                                    ? "\n已带上形象素材（" + rr.appearanceFiles + " 个文件），对方导入后看到的是同一张脸"
                                    : "\n这个角色没有自定义形象（对方会用内置形象）";
                            if (!rr.missing.isEmpty()) {
                                // ★ 找不到素材的动作要**如实说**，不能看着像"全都导出了"
                                msg += "\n有 " + rr.missing.size() + " 个动作找不到素材，已跳过："
                                        + rr.missing.get(0)
                                        + (rr.missing.size() > 1 ? " 等" : "");
                            }
                            Toast.makeText(MainActivity.this, rr.ok ? msg : ("导出失败：" + rr.error),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }, "pet-char-export").start();
            return;
        }
        if (requestCode == REQ_EXPORT_THEME && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try {
                java.io.OutputStream os = getContentResolver().openOutputStream(data.getData());
                os.write(ThemeOverride.exportText(this).getBytes("UTF-8"));
                os.flush();
                os.close();
                Toast.makeText(this, "已导出配色方案", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "导出失败：" + t, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQ_IMPORT_THEME && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            String text = null;
            try {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                text = new String(bo.toByteArray(), "UTF-8");
            } catch (Throwable t) {
                text = null;
            }
            confirmImportedTheme(text, "来自所选文件");
            return;
        }
        if (requestCode == REQ_ADD_ACTIONS && resultCode == RESULT_OK && data != null) {
            handleAddActions(data);
            return;
        }
        if (requestCode == REQ_IMPORT_SCENE_CLIPS && resultCode == RESULT_OK && data != null) {
            handleSceneImport(data, pendingSceneImport);
            return;
        }
        // ★ 轮次62 第二轮：挑了一个 .gguf ⇒ 净化名字、同名就确认、然后后台流式拷
        if (requestCode == REQ_PICK_GGUF && resultCode == RESULT_OK && data != null) {
            handleGgufImport(data.getData());
            return;
        }
        if (requestCode == REQ_IMPORT_SKILL && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            handleSkillImport(data.getData());
            return;
        }
        if (requestCode == REQ_DISTILL_CHAT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            handleDistillSource(data.getData());
            return;
        }
        if (requestCode == REQ_PICK_MEME && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            handleMemeUpload(data.getData());
            return;
        }
        if (requestCode == REQ_PICK_MEME_MULTI && resultCode == RESULT_OK && data != null) {
            handleMemeMultiUpload(data);
            return;
        }
        if (requestCode == REQ_PICK_CLONE_AUDIO && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            runMinimaxClone(data.getData());
            return;
        }
        if (requestCode == REQ_PICK_VIDEO_BG && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            if (saveVideoBg(data.getData())) {
                // ★ 拷完先**不**直接应用，而是弹裁剪预览 —— 用户可以框出想要的画面。
                //
                // 为什么不直接应用：原先的实现强制 center-crop，横屏视频当竖屏
                // 背景时左右被切掉一大截，而用户在导入那一刻看不到自己失去了
                // 哪部分画面（用户反馈「无法正常显示，被裁切了」）。
                // 多这一步，用户先看到完整画面、再自己决定框哪块，不会再有意外裁切。
                Prefs.clearVideoBgCrop(this);   // 新片不带旧片的裁剪框
                showVideoCropDialog();
            } else {
                Toast.makeText(this, "视频读取失败（可能空间不足或文件不可读）",
                        Toast.LENGTH_LONG).show();
                showSettings();
            }
            return;
        }
        if (requestCode == REQ_PICK_IMAGE_BG && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            // ★ 图片**不需要**裁剪确认这一步（与视频不同）：
            //   视频裁剪是因为横屏视频当竖屏背景会被切掉一大截，
            //   而图片的默认兜底（按屏幕比例居中取一块）通常就是用户想要的，
            //   且事后可以在设置页随时重裁。少一步打扰。
            if (saveImageBg(data.getData())) {
                Prefs.clearImageBgCrop(this);        // 新图不带旧图的裁剪框
                // ★ 用 setBgKind：启用图片的同时把视频关掉 —— 三选一语义。
                //   （合并前这里调 setImageBgEnabled + setImageBgPriority，
                //     两个 pref 各写一次，容易不一致。）
                Prefs.setBgKind(this, Prefs.BG_IMAGE);
                Toast.makeText(this, "背景图片已应用", Toast.LENGTH_SHORT).show();
                showSettings();
            } else {
                Toast.makeText(this, "图片读取失败（可能空间不足或格式不支持）",
                        Toast.LENGTH_LONG).show();
                showSettings();
            }
            return;
        }
        if (requestCode == REQ_PICK_ASR_MODEL && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            // ★ 语音大模型文件导入：后台线程拷贝 + 校验，UI 只管进度与结果。
            //   失败原因由 SherpaAsr.importModelFile 直接给人话（文件太小/IO 失败），
            //   这里原样展示，不再二次包装 —— 包装一次就多一处文案要同步断言。
            final Uri uri = data.getData();
            final ProgressDialog pd = UiFactory.progress(this);
            pd.setMessage("正在导入语音模型（约 229 MB，请稍候）…");
            pd.setCancelable(false);
            pd.show();
            new Thread(new Runnable() {
                @Override public void run() {
                    java.io.InputStream is = null;
                    String err;
                    try {
                        is = getContentResolver().openInputStream(uri);
                        if (is == null) {
                            err = "打不开选中的文件";
                        } else {
                            err = SherpaAsr.importModelFile(MainActivity.this, is);
                        }
                    } catch (Exception e) {
                        err = "导入语音模型失败：" + e.getMessage();
                    } finally {
                        try { if (is != null) is.close(); } catch (Exception ignore) {}
                    }
                    final String msg = err;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try { pd.dismiss(); } catch (Exception ignore) {}
                            if (msg == null) {
                                Toast.makeText(MainActivity.this,
                                        "语音模型已导入，离线语音输入可用",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                UiFactory.dialog(MainActivity.this)
                                        .setTitle("导入失败")
                                        .setMessage(msg)
                                        .setPositiveButton("知道了", null)
                                        .show();
                            }
                            showSettings();
                        }
                    });
                }
            }).start();
            return;
        }
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int r = saveCustomPet(uri);
            if (r != 0) {
                Prefs.reloadPet(this);
                Toast.makeText(this, r == 2 ? "动图已应用！桌宠开始播放动画" : "图片已应用！",
                        Toast.LENGTH_SHORT).show();
                if (preview == null) {
                    showSettings();
                } else {
                    updatePreview();
                    // ★ 轮次㉛：原来这里要"刷形象来源那一行"，那行已随形象入口收进「角色」。
                }
            } else {
                Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
            }
            return;
        }
    }

    /**
     * 单张上传表情。默认直接用<b>文件名</b>当表情名，同时允许在这里顺手改成
     * 更好记的名字 —— 名字会同时成为图片文件名，改完仍是一一对应。
     */
    private void handleMemeUpload(final Uri uri) {
        final String shown = queryDisplayName(uri);
        final String defaultName = shown;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("表情名（默认用文件名）");
        nameInput.setText(rebaseName(shown));
        nameInput.setSingleLine(true);
        box.addView(nameInput);

        final EditText descInput = new EditText(this);
        descInput.setHint(Memes.descHint());
        descInput.setSingleLine(true);
        box.addView(descInput);

        final android.widget.ImageView preview = new android.widget.ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight((int) (getResources().getDisplayMetrics().density * 160));
        preview.setPadding(0, (int) (getResources().getDisplayMetrics().density * 8), 0, 0);
        box.addView(preview);
        loadPreview(uri, preview);

        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle("添加气泡表情")
                .setMessage("原文件名：" + shown + "\n\n"
                        + "「表情名」就是以后保存的文件名，也是桌宠认它的依据；\n"
                        + "「什么时候用」决定它会不会在合适场合被挑中。")
                .setView(box)
                .setPositiveButton("添加", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String nm = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                        if (nm.length() == 0) nm = defaultName;
                        String desc = descInput.getText() == null ? "" : descInput.getText().toString().trim();
                        if (desc.length() == 0) {
                            Toast.makeText(MainActivity.this,
                                    "建议填写「什么时候用」，否则桌宠很难挑中它",
                                    Toast.LENGTH_LONG).show();
                        }
                        String name = Memes.addCustom(MainActivity.this, uri, nm, desc);
                        if (name == null) {
                            Toast.makeText(MainActivity.this, "读取图片失败", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "已添加：" + name, Toast.LENGTH_SHORT).show();
                            showSettings();
                        }
                    }
                });
            }
        });
        dlg.show();
    }

    /** 去掉扩展名，作为名字输入框的预填（真正的清洗在 Memes.cleanName 里） */
    private String rebaseName(String fileName) {
        if (fileName == null) return "自定义表情";
        String n = fileName.trim();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.trim();
    }

    /** 把 content Uri 的图加载到预览控件（失败静默，不阻塞上传） */
    private void loadPreview(final Uri uri, final android.widget.ImageView iv) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                final Bitmap bm = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
                if (bm == null) return;
                runOnUiThread(new Runnable() { @Override public void run() {
                    iv.setImageBitmap(bm);
                }});
            } catch (Throwable ignore) {}
        }}).start();
    }

    /**
     * 批量导入：一次多选，表情名一律取自文件名（重名自动加序号）。
     * 描述先留空，随后可在「查看／管理已有表情」里补。
     */
    private void handleMemeMultiUpload(Intent data) {
        final List<Uri> uris = new java.util.ArrayList<Uri>();
        final List<String> names = new java.util.ArrayList<String>();
        try {
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) { uris.add(u); names.add(queryDisplayName(u)); }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
                names.add(queryDisplayName(data.getData()));
            }
        } catch (Throwable ignore) {}
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有选中图片", Toast.LENGTH_SHORT).show();
            return;
        }
        final ProgressDialog pd = UiFactory.progress(this);
        pd.setMessage("正在导入 " + uris.size() + " 张表情…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() { @Override public void run() {
            int ok = 0, fail = 0;
            for (int i = 0; i < uris.size(); i++) {
                String nm = Memes.addCustomUri(MainActivity.this, uris.get(i), names.get(i), null);
                if (nm == null) fail++; else ok++;
            }
            final int fok = ok, ffail = fail;
            runOnUiThread(new Runnable() { @Override public void run() {
                try { pd.dismiss(); } catch (Throwable ignore) {}
                Toast.makeText(MainActivity.this,
                        "导入完成：成功 " + fok + " 张" + (ffail > 0 ? "，失败 " + ffail + " 张" : "")
                                + "\n可去「查看／管理已有表情」补上使用场景描述。",
                        Toast.LENGTH_LONG).show();
                showSettings();
            }});
        }}).start();
    }

    // ==================================================================
    // 🤖 本地 GGUF 模型 · 导入层（轮次62 第二轮）+ 载入衔接（轮次64 第三轮）
    // ==================================================================
    //
    // 这一层做五件事：**导入 / 删除 / 切换 / 看进度 / 看载入状态**。
    // 真正的推理在 LocalLlm（第一轮），"谁来用它"在 ChatRouter/LocalChat（第三轮）。
    // ★★ 导入完**刻意不加载**（第二轮定的）：加载是几百 MB 的 mmap，
    //    放在"第一次真正要用它"的时候（LocalChat.ensureLoaded）才对 ——
    //    否则用户导 3 个模型就白载 3 次。
    //
    // ★★ 上一轮交代下来的调用纪律（这里照做）：
    //   ① LocalLlm.load / generate 都是**同步阻塞**的 ⇒ 只能在后台线程调；
    //   ② 流式回调里绝不要再调 load / unload / generate（前两个要同一把锁 = 自锁）；
    //   ③ 拷 0.4~2 GB = 几百 MB 的顺序 IO ⇒ 后台线程 + 流式 + 可取消
    //      （这三件事都在 ModelImporter 里，这里只负责把进度画出来）。

    /** 设置页那一行的值：未导入 / 名字 · 398.5 MB（共 N 个） */
    private String localModelSummary() {
        String cur = ModelStore.current(this);
        if (cur == null) return ModelFile.summary(null, 0, 0);
        File f = ModelStore.file(this, cur);
        long sz = (f != null && f.isFile()) ? f.length() : 0;
        return ModelFile.summary(cur, sz, ModelStore.list(this).size());
    }

    /**
     * ★★★ 轮次67：这一行的内容 = "模型放不放得进内存"。
     *
     * <p>为什么值得单独占一行人话：真机实测 decode 稳定在 **约 6500 ms/token** 且
     * **与上下文长度无关**，换算下来单 token 只有 ~0.4 GFLOP/s（prefill 段是 ~40 GFLOP/s）
     * ⇒ 瓶颈是"每个字都要把 1 GB 权重重新捞一遍"（1.06 GB / 6.5 s ≈ 163 MB/s，
     * 正好是"权重没常驻内存"的量级）。用户看到的只是"慢得离谱"，
     * 而这一行是唯一能告诉他"为什么慢、该换什么"的地方。
     *
     * <p>三个数（模型 / 总内存 / 当前可用）都来自系统 API；判据在
     * {@link ModelFile#memoryAdvice} 这个**纯函数**里（可被离线断言真跑）。
     *
     * @return 一行建议；没模型 / 放得下 ⇒ {@code null}（调用方据此不显示）
     */
    private String localMemAdvice() {
        final String cur = ModelStore.current(this);
        if (cur == null) return null;
        final File f = ModelStore.file(this, cur);
        final long sz = (f != null && f.isFile()) ? f.length() : 0;
        long total = 0, avail = 0;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi =
                        new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                total = mi.totalMem;
                avail = mi.availMem;
            }
        } catch (Throwable ignore) {
            // 拿不到就按"不知道"处理（memoryAdvice 对 <=0 的入参不拿来当判据）
        }
        return ModelFile.memoryAdvice(sz, total, avail);
    }

    /**
     * 「🤖 本地模型：… ›」的操作面板。
     *
     * <p>与「🌐 服务商 ›」「搜索服务 ›」同构：**一行值 + › → 一个面板**，
     * 用户不用为这一个功能学新的交互层级。
     *
     * <p>★ 面板里先报"引擎到底能不能用" —— 这个包只有 arm64 的原生库，
     * 在不支持的设备上导入几百 MB 是白导，必须让用户在**动手之前**看见。
     */
    private void showLocalModelPanel() {
        final List<File> fs = ModelStore.list(this);
        final String cur = ModelStore.current(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 12);
        box.setPadding(pad, pad, pad, pad);

        addLabel(box, "\uD83D\uDCC1 " + ModelStore.describe(this));
        addLabel(box, LocalLlm.isAvailable()
                ? "· 推理引擎：llama.cpp 已就绪"
                    + (LocalLlm.version() == null ? "" : "（" + LocalLlm.version() + "）")
                    + "，导入后即可纯本机出话。"
                : "· 推理引擎：这个包里的原生库没装上（本功能只支持 arm64 手机）"
                    + " —— 在这台设备上导进来也用不了，先别导。");
        addLabel(box, cur == null
                ? "· 当前模型：还没导入。挑一个小参数 GGUF（0.5B~3B 的量化版）即可。"
                : "· 当前模型：" + cur);
        // ★★ 轮次64：把"内存里到底载没载"也说出来 ——
        //    加载是几百 MB 的 mmap，"第一次对话要等几秒"必须提前知道。
        //    ★★★ 轮次69：这一行改由 LocalModelMenu 出（**唯一出处**）——
        //    卸载之后它必须**看得出"当前没有载入模型"**（用户点名要的验收点）。
        addLabel(box, LocalModelMenu.memoryRow(LocalLlm.isLoaded(), cur));
        // ★★ 轮次64：把"这个模型到底有没有被用上"当面说清 ——
        //    用户导完模型但对话后端还停在线服务商，是很常见的一步之遥。
        addLabel(box, ChatRouter.isLocal(this)
                ? "· \u2705 对话后端：就是本机这个模型（不联网、不需要 Key）"
                : "· 对话后端：目前还是在线服务商。想用本机的，去上一层「\uD83C\uDF10 服务商」"
                  + "选「\uD83E\uDD16 本地模型（手机本机跑，不联网）」。");

        // ★★★ 轮次74：三档（自动 / 开 / 关），**默认自动**。
        //    用户原话：「运行时自动检测硬件 → 动态计算 -(n-gpu-layers) → 自动降级兜底
        //    不用用户手动调参数。」⇒ 这一行说"现在是什么档位、这么用会发生什么"，
        //    下面那颗按钮才是"改成什么"。
        final String gpuMode = Prefs.localGpuMode(this);
        addLabel(box, GpuMode.describe(gpuMode));
        // ★★ 自动档：把**上一次真正算出来的计划**也显示出来（"这台机器被判定成什么"）。
        //    没有计划（还没载入过）就不显示 —— 不塞一句"暂无"，那是噪声。
        if (GpuMode.isAuto(gpuMode)) {
            final GpuPlan.Plan plan = LocalHardware.lastPlan();
            if (plan != null) {
                addLabel(box, "· " + GpuPlan.summary(LocalHardware.lastFacts(), plan));
            }
            // ★★ 上一次真的自动降级过 ⇒ 把原因也摆出来（不是静默：用户要能看见"它替我做了什么"）。
            final String fb = LocalHardware.lastFallback();
            if (fb != null) {
                final String shortWhy = fb.length() > 120 ? fb.substring(0, 120) + "…" : fb;
                addLabel(box, "· ⚠ 上一次 GPU 失败，已**自动**改用纯 CPU（不需要你操作）：" + shortWhy);
            }
        }

        // ★★★ 轮次68 建立 / 轮次69 改成两态：开着 GPU 失败过 ⇒ 把原因**留着**（不是弹一次就没），
        //    并给一颗**真能一键切过去**的按钮 —— 这就是"开"必须留的退路。
        //    ★ 文案里的原因就是原生侧交回来的那一条（logcat DSPLlm 里是同一份）。
        //    ★★ 轮次69 的硬要求：这一段只**追加**告警，绝不 `return`、也绝不改变下面
        //       那几颗按钮的构造 —— GPU 出问题不许把卸载/更换/删除/导入/切回在线堵死。
        final String gpuFail = GpuFailNotice.last();
        if (gpuFail != null) {
            addLabel(box, GpuFailNotice.panelLine(gpuFail));
            if (GpuMode.isOn(gpuMode)) {
                box.addView(UiFactory.actionRow(this, uiT(), GpuFailNotice.btnOff(),
                        new View.OnClickListener() {
                    @Override public void onClick(View v) { applyLocalGpuMode(GpuMode.OFF); }
                }));
            }
        }

        // ★★ 轮次67：把"模型放不放得进内存"当面说清（decode 慢的真实原因）。
        //    放得下 / 还没导入 ⇒ localMemAdvice() 返回 null，这一行**不出现**
        //    （不是显示一句"没问题"—— 那是噪声）。
        String memAdvice = localMemAdvice();
        if (memAdvice != null) addLabel(box, memAdvice);

        // ① 导入（ACTION_OPEN_DOCUMENT 挑 .gguf）
        box.addView(UiFactory.actionRow(this, uiT(), "\uD83D\uDCE5 导入 .gguf 文件…",
                new View.OnClickListener() {
            @Override public void onClick(View v) { startGgufPick(); }
        }));

        // ② 切换（≥2 个才有意义；只有一个时点它不如直接告诉他"只有一个"）
        box.addView(UiFactory.actionRow(this, uiT(),
                "\uD83D\uDD04 切换当前模型（已导入 " + fs.size() + " 个）…",
                new View.OnClickListener() {
            @Override public void onClick(View v) { showModelPicker(); }
        }));

        // ③ 删除（★ 说清删的是什么、多大 —— 见 confirmDeleteModel）
        box.addView(UiFactory.actionRow(this, uiT(),
                cur == null ? "\uD83D\uDDD1 删除本地模型（当前没有可删的）…"
                        : "\uD83D\uDDD1 删除本地模型（" + cur + "）…",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cur == null) {
                    Toast.makeText(MainActivity.this, "还没有导入任何本地模型",
                            Toast.LENGTH_SHORT).show();
                } else {
                    confirmDeleteModel(cur);
                }
            }
        }));

        // ④ ★★★ 轮次69 新增：「⏏ 卸载当前模型」—— 用户明确点名要的那颗按钮。
        //    与「删除」的区别（面板里必须一眼看得出，否则用户会怕误删文件）：
        //      卸载 = 只把内存里那个模型放掉、把 1 GB 量级的内存交还，**文件一个字节都不动**；
        //      删除 = 把 filesDir/models/ 里那个 .gguf **删掉**。
        //    ★★ 这一颗**永远可点**（LocalModelMenu.canUnload() 恒为 true）：
        //       载着东西就卸它；什么都没载就如实说一句"本来就没载入" —— 绝不因为
        //       "上次加载失败 / 没载入"把这条最便宜的退路藏起来。
        box.addView(UiFactory.actionRow(this, uiT(),
                LocalModelMenu.unloadRow(LocalLlm.isLoaded(), cur),
                new View.OnClickListener() {
            @Override public void onClick(View v) { unloadLocalModelNow(); }
        }));

        // ⑤ ★★★ 轮次69：GPU 加速的**两态**选择（开 / 关，默认「开」）。
        //    轮次68 这里是三态（关/自动/强制），而"自动"那条**静默回落 CPU** 正是用户
        //    两次都想消灭的坏法；用户这一轮要的就是"开 GPU"这四个字 ⇒ 收回两态。
        //    ★ 与上面那行状态、上面那条一键退路配套；★ 退路**不止**这一条
        //      （卸载/更换/删除/切回在线都不受 GPU 影响 —— 见 LocalModelMenu）。
        box.addView(UiFactory.actionRow(this, uiT(), Prefs.localGpuLabel(this) + " \u203A",
                new View.OnClickListener() {
            @Override public void onClick(View v) { showGpuModeDialog(); }
        }));

        // ⑥ ★★★ 轮次71 新增：「🧪 本地推理自检」—— 用户这一轮最需要的那颗按钮。
        //    ★★ 为什么它比别的都重要：轮次66~70 每一轮都卡在"这台手机的 GPU 到底行不行"
        //     这一句话上 —— 开发机 adb 是空的，而**真机是用户自己的日常机**，
        //     他不会连电脑、更不会挂 logcat。这颗按钮把答案变成**一屏能复制的文本**。
        //    ★ 后台线程跑 + 进度提示 + 可取消（见 runLocalSelfTest / cancelLocalSelfTest），
        //      **绝不许让 UI 卡住**（载入几秒 + 生成几秒，放主线程就是 ANR）。
        box.addView(UiFactory.actionRow(this, uiT(), LocalSelfTest.BUTTON,
                new View.OnClickListener() {
            @Override public void onClick(View v) { runLocalSelfTest(); }
        }));
        addLabel(box, LocalSelfTest.HINT);

        // ⑦ ★★★ 轮次71 新增：「🎛 本地推理调优（进阶 · A/B 用）」——
        //    把"只能在 PC 上做的 A/B"全部搬进设置页（用户不带电脑也能自己试出答案）。
        //    ★ 8 个旋钮全部有默认值，**什么都不动 = 与上一版行为完全一样**。
        addLabel(box, LocalTune.describe(Prefs.localTune(this)));
        box.addView(UiFactory.actionRow(this, uiT(), Prefs.localTuneLabel(this) + " \u203A",
                new View.OnClickListener() {
            @Override public void onClick(View v) { showTunePanel(); }
        }));

        // ★★★ 轮次72（真机实测抓到的真 bug）：这个面板**必须能滚**。
        //   `AlertDialog` 的自定义面板（`android:id/customPanel` 那个 FrameLayout）
        //   **不会**自己变成可滚动容器 ⇒ 内容比对话框高时，尾部整段被裁掉。
        //   实测机型 25091RP04C（1904×3040 / 440dpi）：面板里
        //   「🔄 切换 / 🗑 删除 / ⏏ 卸载 / ⚡ GPU 加速 / 🧪 自检 / 🎛 调优」全都被裁到
        //   对话框之外 —— a11y 树里连 `🎛 本地推理调优` 这个**文字节点都不存在**
        //   （不是"在小屏幕外"，是根本没渲染出来）⇒ 用户**点不到**，
        //   而它正是轮次71 交给用户做 A/B 的唯一入口。
        //   ★ 写法与 showTunePanel 完全一致（ScrollView + 高度上限）：
        //     一处改了另一处要连着改，否则两个面板的行为会漂。
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.72f)));
        UiFactory.dialog(this)
                .setTitle("\uD83E\uDD16 本地模型（纯本机跑，不联网）")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .show();
    }

    /**
     * ★★★ 轮次69：「⏏ 卸载当前模型」—— 真的把内存里那个模型卸掉、把内存交还。
     *
     * <p>为什么必须放**后台线程**：{@code LocalLlm.unload()} 会先发停止信号再等锁，
     * 但万一此刻正在生成，主线程等它就是"点一下卡半秒"（轮次65 定的纪律）。
     *
     * <p>★ 卸载**不动文件**：所以这里绝不碰 {@code ModelStore} ——
     * 卸载之后"当前模型"还是它，下一次对话会重新载入（那正是用户想要的"先放开内存"）。
     */
    private void unloadLocalModelNow() {
        final boolean wasLoaded = LocalLlm.isLoaded();
        final String name = ModelStore.current(this);
        Toast.makeText(this, "正在卸载…（内存里的模型放掉，文件保留）", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { @Override public void run() {
            LocalChat.unloadNow();
            runOnUiThread(new Runnable() { @Override public void run() {
                Toast.makeText(MainActivity.this, LocalModelMenu.unloadResult(wasLoaded, name),
                        Toast.LENGTH_LONG).show();
                // ★ 立刻重画这一屏：用户要能在界面上看到"当前没有载入模型"
                showLocalModelPanel();
            }});
        }}, "dspllm-unload").start();
    }

    /**
     * GPU 档位的**两态单选**（轮次69）。
     *
     * <p>与「🌐 服务商」「🔄 切换模型」同构（{@code setSingleChoiceItems} + 「选定」），
     * 区别只在选项是**两档而不是一堆对象** —— 所以直接写成一张固定的表。
     *
     * <p>★ 用"选定 + 确认"而不是"点了就生效"：这一改会**放掉内存里的模型**
     * （GPU 层数只在载入时给一次），点错一次就要重载几百 MB，值得一次确认。
     */
    private void showGpuModeDialog() {
        final String[] modes  = { GpuMode.AUTO, GpuMode.ON, GpuMode.OFF };
        final String[] labels = GpuMode.choices();
        final String cur = Prefs.localGpuMode(this);
        int checked = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(cur)) checked = i;
        final int[] chosen = { checked };
        UiFactory.dialog(this)
                .setTitle(GpuMode.dialogTitle())
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        chosen[0] = which;      // ★ 只记录，不写盘（与「服务商」那一处同一套）
                    }
                })
                .setPositiveButton("选定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final int i = chosen[0];
                        if (i >= 0 && i < modes.length) applyLocalGpuMode(modes[i]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 真的切过去（写设置 + 放掉内存里的模型 + 清掉失败提示 + Toast + 刷新这一屏）。 */
    private void applyLocalGpuMode(String mode) {
        final String m = GpuMode.normalize(mode);
        if (m == null) return;
        GpuModeUi.applyMode(this, m);
        showLocalModelPanel();
    }

    // ==================================================================
    // ★★★ 轮次71：「🎛 本地推理调优」+「🧪 本地推理自检」
    //
    //   用户这几轮的原话一直是同一件事：**「速度慢的离谱 用gpu加速」**。
    //   而"哪个旋钮能治好它"**只能在用户自己的手机上试** —— 开发机 adb 从头到尾是空的。
    //   ⇒ 这一块把"只能在 PC 上做的 A/B"（轮次67 那个 setprop + force-stop）
    //     全部变成设置页里可见、可点、会持久化、会写进日志的选项，
    //     并配一颗"跑一句、出一屏可直接复制的读数"的自检按钮。
    // ==================================================================

    /** 「🎛 本地推理调优」面板：一行一个旋钮（点进去单选）。 */
    private void showTunePanel() {
        final String cur = Prefs.localTune(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 12);
        box.setPadding(pad, pad, pad, pad);

        addLabel(box, LocalTune.describe(cur));
        addLabel(box, "· 每一项的**依据**都写在它自己的选项里："
                + "有上游依据的（llama.cpp / ggml 里真的存在的参数）与"
                + "**我们的推测**（可能是解药、但没在真机上验过）分得很清楚。");

        // ★ 8 行是照着 LocalTune.KEYS 循环出来的（加一个旋钮不用改这里）。
        for (int i = 0; i < LocalTune.knobCount(); i++) {
            final int idx = i;
            box.addView(UiFactory.actionRow(this, uiT(),
                    LocalTune.title(i) + "：" + LocalTune.valueLabel(i, cur) + " \u203A",
                    new View.OnClickListener() {
                @Override public void onClick(View v) { showTuneKnobDialog(idx); }
            }));
        }

        // 一键回到出厂默认（用户试乱了要有退路 —— 本工程的老规矩）
        box.addView(UiFactory.actionRow(this, uiT(), "\u21A9 全部恢复默认（试乱了点这个）",
                new View.OnClickListener() {
            @Override public void onClick(View v) { resetAllTune(); }
        }));

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        UiFactory.dialog(this)
                .setTitle("\uD83C\uDF9B 本地推理调优（进阶 · A/B 用）")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .show();
    }

    /**
     * 单个旋钮的单选（与「⚡ GPU 加速」「🌐 服务商」同构：setSingleChoiceItems + 选定）。
     *
     * <p>★ 用"选定 + 确认"而不是"点了就生效"：改一个旋钮可能要把内存里的模型放掉
     * （载入级那四个），点错一次就要重载几百 MB，值得一次确认。
     */
    private void showTuneKnobDialog(final int i) {
        final String[] vals = LocalTune.values(i);
        final String[] labels = LocalTune.choiceLabels(i);
        if (vals.length == 0 || labels.length != vals.length) return;
        final String cur = Prefs.localTune(this);
        final String now = LocalTune.value(cur, LocalTune.key(i));
        int checked = 0;
        for (int k = 0; k < vals.length; k++) if (vals[k].equals(now)) checked = k;
        final int[] chosen = { checked };
        UiFactory.dialog(this)
                .setTitle(LocalTune.knobDialogTitle(i))
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        chosen[0] = which;      // ★ 只记录，不写盘（与「服务商」那一处同一套）
                    }
                })
                .setPositiveButton("选定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final int k = chosen[0];
                        if (k >= 0 && k < vals.length) applyTuneKnob(i, vals[k]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 真的改一个旋钮：写设置 ⇒（载入级的）放掉内存里的模型 ⇒ Toast 说清**什么时候生效**。
     *
     * <p>★★★ 两类旋钮的生效时机**完全不同**，Toast 必须分开说（见 {@link LocalTune#effect(int)}）：
     * <ul>
     *   <li>**载入级**（flash / ubatch / mlock / loadmode）：放掉模型，下一次载入就生效；</li>
     *   <li>**后端级**（adreno / mmvq / fusion / async）：这几个是 ggml **造 Vulkan 设备时**
     *       读走的，而 Vulkan 设备在一个进程里只造一次 ⇒ 要**完全退出 App 再打开**；
     *       ★ 这里**故意不**放掉内存里的模型 —— 放了也没用，只会让用户白等一次载入。</li>
     * </ul>
     */
    private void applyTuneKnob(final int i, final String val) {
        final String key = LocalTune.key(i);
        if (key == null || val == null) return;
        Prefs.setLocalTuneKnob(this, key, val);
        final String toast = LocalTune.changedToast(i, val);
        if (LocalTune.needsRestart(i)) {
            Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
            showTunePanel();
            return;
        }
        Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
        new Thread(new Runnable() { @Override public void run() {
            // ★ 后台线程：unload 会先发停止信号再等锁，主线程等它就是"点一下卡几秒"。
            try { LocalLlm.unload(); } catch (Throwable ignore) {}
            LocalChat.forget();
        }}, "dspllm-tune-unload").start();
        showTunePanel();
    }

    /** 八个旋钮一键回默认（试乱了要有退路）。 */
    private void resetAllTune() {
        Prefs.setLocalTune(this, LocalTune.defaults());
        Toast.makeText(this, "已把八个旋钮全部恢复默认（= 与上一版完全一样的行为）。"
                + "内存里的模型已放掉，下一次载入按默认参数走。", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() { @Override public void run() {
            try { LocalLlm.unload(); } catch (Throwable ignore) {}
            LocalChat.forget();
        }}, "dspllm-tune-reset").start();
        showTunePanel();
    }

    /**
     * ★★★ 轮次71：「🧪 本地推理自检」—— 后台线程跑，带进度提示、可取消，**绝不许卡 UI**。
     *
     * <p>★ 进度框必须走 {@code UiFactory.progress(this)}（**不是** `new ProgressDialog(this)`）——
     * 本工程的一条硬判据：所有对话框都要经过设计层，直接 new 出来的在深色/玻璃态下是原生白底
     * （`verify_dialog_theme.py` 当场红）。★ 本轮第一次就是写成了 `new ProgressDialog(this)`，
     * 全量套件在 **54/73** 抓出来的 —— 这就是那条判据存在的理由。
     */
    private void runLocalSelfTest() {
        final int myRun = ++selfTestRun;          // ★ 取消就是把这个号 +1（结果作废）
        final ProgressDialog pd = UiFactory.progress(this);
        pd.setMessage(LocalSelfTest.PROGRESS);
        pd.setCancelable(false);
        pd.setButton(DialogInterface.BUTTON_NEGATIVE, "取消",
                new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { cancelLocalSelfTest(); }
        });
        pd.show();
        new Thread(new Runnable() { @Override public void run() {
            final LocalChat.Check c = LocalChat.selfTest(MainActivity.this);
            runOnUiThread(new Runnable() { @Override public void run() {
                dismissQuietly(pd);
                if (myRun != selfTestRun) {
                    // ★ 用户中途取消了 ⇒ 不弹结果（但要把账本清掉，别让下一次对话拿一个假状态）
                    LocalChat.forget();
                    Toast.makeText(MainActivity.this, "自检已取消（内存里的模型已放掉）",
                            Toast.LENGTH_SHORT).show();
                    showLocalModelPanel();
                    return;
                }
                showSelfTestResult(c);
            }});
        }}, "dspllm-selftest").start();
    }

    /** 取消自检：把正在跑的那次作废，并在**后台线程**把引擎叫停（它会等生成收手）。 */
    private void cancelLocalSelfTest() {
        selfTestRun++;
        Toast.makeText(this, "正在取消…（会放掉内存里的模型）", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { @Override public void run() {
            // ★★ 必须后台线程：LocalLlm.unload() 会先发停止信号**再等锁**，
            //    而此刻正有一次生成在跑（这台机器上一个字 ~6.5 秒）⇒ 主线程等它就是 ANR。
            try { LocalLlm.unload(); } catch (Throwable ignore) {}
            LocalChat.forget();
        }}, "dspllm-selftest-cancel").start();
    }

    /** 关掉进度框（Activity 已经结束时不抛）。 */
    private void dismissQuietly(ProgressDialog pd) {
        try {
            if (pd != null && pd.isShowing()) pd.dismiss();
        } catch (Throwable ignore) {}
    }

    /**
     * 把自检结果**一屏显示**出来（可滚动、可长按选中），并给一颗**一键复制**。
     *
     * <p>★★ 为什么复制这件事值一颗按钮：这份报告是**用户要发回给我们的唯一物证** ——
     * 他没有 adb、不会挂 logcat，能做的只有"复制 / 粘贴"。
     * ★ 同时把它整段打进 logcat（`DSPLlm`）：他万一挂了日志，那边也能拿到同一份。
     */
    private void showSelfTestResult(final LocalChat.Check c) {
        final String report = (c == null || c.report == null) ? "（没有报告）" : c.report;
        final TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11f);
        int pad = (int) (getResources().getDisplayMetrics().density * 12);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        UiFactory.dialog(this)
                .setTitle(LocalSelfTest.DIALOG_TITLE + "\uff1a" + (c == null ? "" : c.title))
                .setView(sc)
                .setPositiveButton("\uD83D\uDCCB 复制结果",
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copySelfTest(report); }
                })
                .setNegativeButton("关闭", null)
                .show();
        android.util.Log.i("DSPLlm", "自检报告：\n" + report);
        showLocalModelPanel();
    }

    /** 一键复制（★ 复制失败也要给出手动复制的办法 —— 不许"点了没反应"）。 */
    private void copySelfTest(String report) {
        final String text = report == null ? "" : report;
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) throw new IllegalStateException("没有剪贴板服务");
            cm.setPrimaryClip(android.content.ClipData.newPlainText("本地推理自检", text));
            Toast.makeText(this, "已复制 " + text.length() + " 字 —— 直接粘贴发回来就行",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败（" + t + "）：在结果框里长按可以手动全选复制",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 打开系统文件选择器挑 {@code .gguf}。
     *
     * <p>★ 用 {@link Intent#ACTION_OPEN_DOCUMENT}（不是 GET_CONTENT）：它给的是**长期可读**的
     * 文档 Uri，而且系统会按 MIME 过滤。
     * ★★ MIME 用 {@code *}{@code /*}：手机上的 {@code .gguf} **大多被报成
     * {@code application/octet-stream}**（有些文件管理器干脆报 {@code null}），
     * 只按 {@code application/octet-stream} 过滤会让用户"看不到自己那个文件"。
     * ⇒ 这里**不靠 MIME 把关**，靠导进来之后**看文件头**（见 ModelImporter）。
     */
    private void startGgufPick() {
        if (!LocalLlm.isAvailable()) {
            // ★ 不静默失败，也不硬拦：先说清"这台设备导了也用不了"，再由用户决定
            Toast.makeText(this, "提示：这台设备上的本地推理库没装上（只支持 arm64 手机），"
                    + "导入后可能用不了。", Toast.LENGTH_LONG).show();
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/octet-stream", "application/gguf", "*/*"});
        try {
            startActivityForResult(Intent.createChooser(i, "选择 GGUF 模型文件"), REQ_PICK_GGUF);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件选择器：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 选完文件之后的入口：净化名字 → （同名就确认覆盖）→ 开拷。
     *
     * <p>★ 名字可能问不到（云盘上的文件、某些文件管理器不实现 DISPLAY_NAME）⇒
     * 退化成"本地模型.gguf"，**不能因为没名字就放弃导入**。
     * ★ 大小可能问不到 ⇒ 传 -1：进度条变成"已拷 X"，而且**不做拷完对账**
     * （宁可不报"不完整"，也不能拿一个假的大小去误判）。
     */
    private void handleGgufImport(Uri uri) {
        if (uri == null) {
            Toast.makeText(this, "没有选到文件", Toast.LENGTH_SHORT).show();
            return;
        }
        String raw = ModelImporter.displayName(this, uri);
        long size = ModelImporter.sizeOf(this, uri);
        final String name = ModelFile.sanitizeName(raw == null ? "本地模型.gguf" : raw);
        if (name == null) {
            Toast.makeText(this, "这个文件名没法用（净化后是空的）⇒ 已拒绝导入。",
                    Toast.LENGTH_LONG).show();
            return;
        }
        android.util.Log.i("DSPModel", "导入入口 uri=" + uri + " 名字=" + name
                + " 大小=" + size);
        final File exists = new File(ModelStore.dir(this), name);
        if (exists.isFile()) {
            // ★ 同名覆盖要先问：默默覆盖会毁掉用户上一次导入的模型
            UiFactory.dialog(this)
                    .setTitle("\u26A0 已有同名模型")
                    .setMessage("App 里已经有一个「" + name + "」（" + ModelFile.humanSize(exists.length())
                            + "）。\n\n继续导入会覆盖它；导入失败或被取消则保留原来那个"
                            + "（新文件先写成 .part，成功才改名）。")
                    .setPositiveButton("覆盖导入", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            runModelImport(uri, name, size);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        runModelImport(uri, name, size);
    }

    /**
     * 真正开拷：模态横向进度框 + 后台线程 + 可取消。
     *
     * <p>★ 顺序很要紧：**先 show 再 start**。反过来的话，小文件（几 MB）可能在
     * {@code start()} 返回之前就跑完了 ⇒ {@code onDone} 先 {@code dismiss()} 了一个
     * 还没 show 的框，紧接着又 {@code show()} 出来一个永远关不掉的"最后一个 100%"。
     */
    private void runModelImport(Uri uri, String name, long size) {
        final ProgressDialog pd = UiFactory.progress(this);
        pd.setTitle("\uD83E\uDD16 导入本地模型");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setProgress(0);
        pd.setIndeterminate(size <= 0);           // 问不到大小 ⇒ 不确定进度
        pd.setMessage(name + (size > 0 ? "\n" + ModelFile.humanSize(size) : ""));
        pd.setCancelable(true);
        pd.setCanceledOnTouchOutside(false);      // 拷一半误触外面就取消，太容易踩
        final ModelImporter.Job[] holder = new ModelImporter.Job[1];
        pd.setButton(DialogInterface.BUTTON_NEGATIVE, "取消",
                new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (holder[0] != null) holder[0].cancel();   // 先发停止信号
                d.dismiss();
            }
        });
        // 返回键 / 点外面（这里关了外面）也当作取消：否则会给用户一个"停不下来"的框
        pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface d) {
                if (holder[0] != null) holder[0].cancel();
            }
        });
        pd.show();

        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        holder[0] = ModelImporter.start(this, uri, name, size, new ModelImporter.Listener() {
            @Override public void onProgress(final int pct, final long copied, final long total) {
                if (finished.get()) return;
                // ★ 回调在后台线程 ⇒ 动控件必须回 UI 线程
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (finished.get()) return;
                    try {
                        if (pct < 0) {
                            pd.setMessage(name + "\n已拷 " + ModelFile.humanSize(copied) + "…");
                        } else {
                            pd.setProgress(pct);
                            pd.setMessage(name + "\n" + ModelFile.humanSize(copied)
                                    + " / " + ModelFile.humanSize(total) + "（" + pct + "%）");
                        }
                    } catch (Throwable ignore) {}
                }});
            }

            @Override public void onDone(final boolean ok, final String nm,
                                         final File f, final String msg) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    finished.set(true);
                    try { if (pd.isShowing()) pd.dismiss(); } catch (Throwable ignore) {}
                    if (ok && nm != null) {
                        // ★ 导进来的就设成当前模型：用户刚挑的，意图很明确；
                        //   而且只有一个模型时"当前"必须是它
                        ModelStore.setCurrent(MainActivity.this, nm);
                        // ★★ 轮次64：导进来的（很可能是新文件）必须把内存里那个放掉。
                        //    尤其是**同名覆盖**：路径没变、而盘上那份已经是新内容，
                        //    只按路径判断会让引擎一直用内存里的旧模型
                        //    （症状是"重新导了却没生效"，最难查的那一类）。
                        new Thread(new Runnable() { @Override public void run() {
                            try { LocalLlm.unload(); } catch (Throwable ignore) {}
                            LocalChat.forget();
                        }}, "dspllm-import").start();
                    }
                    Toast.makeText(MainActivity.this, msg,
                            ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                    android.util.Log.i("DSPModel", "导入结果 ok=" + ok + " :: " + msg);
                    showSettings();
                }});
            }
        });
    }

    /** 删除确认：**说清删的是什么、多大**，以及删掉之后会怎样。 */
    private void confirmDeleteModel(final String name) {
        if (name == null) return;
        File f = ModelStore.file(this, name);
        final long sz = (f != null && f.isFile()) ? f.length() : 0;
        final boolean isCurrent = ModelFile.sameName(ModelStore.current(this), name);
        StringBuilder m = new StringBuilder();
        m.append("删除这个本地模型？\n\n");
        m.append(name).append("\n").append(ModelFile.humanSize(sz)).append("\n\n");
        if (isCurrent) {
            m.append("★ 它正是当前使用的模型，删完需要重新导入才能用本地模型。\n\n");
        }
        m.append("只删本机这一份；你原来那个 .gguf 文件不动。");
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDDD1 删除本地模型")
                .setMessage(m.toString())
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        deleteModelNow(name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 真删：先卸载（如果正被引擎载着），再删文件。
     *
     * <p>★ 卸载放**后台线程**：{@code LocalLlm.unload()} 会先发停止信号再等锁，
     * 万一此刻正在生成，主线程等它一下就是"点删除卡半秒"。
     * 第三轮接线之后这条会真的被用到。
     */
    private void deleteModelNow(final String name) {
        final boolean wasCurrent = ModelFile.sameName(ModelStore.current(this), name);
        new Thread(new Runnable() { @Override public void run() {
            if (wasCurrent) {
                try { LocalLlm.unload(); } catch (Throwable ignore) {}
                LocalChat.forget();      // ★ 忘掉"内存里载着谁"（轮次64）
            }
            final boolean ok = ModelStore.delete(MainActivity.this, name);
            runOnUiThread(new Runnable() { @Override public void run() {
                Toast.makeText(MainActivity.this,
                        ok ? "已删除 " + name : "删不掉（文件可能被占用），请稍后再试",
                        ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                showSettings();
            }});
        }}, "dspllm-delete").start();
    }

    /** 切换当前模型：单选列表，当前那个打勾（★ "当前用哪个"必须看得见）。 */
    private void showModelPicker() {
        final String[] names = ModelStore.names(this);
        if (names.length == 0) {
            Toast.makeText(this, "还没有导入任何本地模型，先「导入 .gguf 文件…」",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (names.length == 1) {
            Toast.makeText(this, "只导入了 1 个模型（" + names[0] + "），再导入一个才能切换。",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String cur = ModelStore.current(this);
        String[] labels = new String[names.length];
        int checked = -1;
        for (int i = 0; i < names.length; i++) {
            File f = new File(ModelStore.dir(this), names[i]);
            labels[i] = names[i] + "  ·  " + ModelFile.humanSize(f.length());
            if (ModelFile.sameName(names[i], cur)) checked = i;
        }
        UiFactory.dialog(this)
                .setTitle("\uD83D\uDD04 切换当前模型")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        dlg.dismiss();
                        switchLocalModel(names[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 换一个当前模型：先记选择（快），再在后台把已载入的旧模型卸掉。
     *
     * <p>★★ 轮次69：这条链是"换不了模型"那个报障的**界面侧答案** —— 它本身没有问题
     * （记选择 → 卸旧 → 忘账本 → 下一次对话载新的），坏的是原生侧那个
     * "以前失败过就再也不许试"的粘性标记（已在 {@code jni/dspllm_jni.cpp} 里删掉）。
     * ★ 这里补一行**旧→新**的日志：真机上"到底换没换"就靠它对上（与
     * `DSPLocalChat 本地模型已载入并常驻：<新文件名>` 连起来读）。
     */
    private void switchLocalModel(final String name) {
        final String from = ModelStore.current(this);
        if (ModelFile.sameName(from, name)) {
            Toast.makeText(this, "已经是当前模型了", Toast.LENGTH_SHORT).show();
            return;
        }
        ModelStore.setCurrent(this, name);
        android.util.Log.i("DSPModel", LocalModelMenu.switchLog(from, name));
        // ★ 换了文件就得把旧模型从内存里放掉：否则下次生成用的还是旧的那个
        //   （unload 是阻塞的，但会先发停止信号 ⇒ 放后台线程更稳）
        new Thread(new Runnable() { @Override public void run() {
            try { LocalLlm.unload(); } catch (Throwable ignore) {}
            LocalChat.forget();          // ★ 忘掉"内存里载着谁"（轮次64）
        }}, "dspllm-switch").start();
        Toast.makeText(this, LocalModelMenu.switchToast(name), Toast.LENGTH_SHORT).show();
        showSettings();
    }

    /** 表情管理：列出全部表情，可改描述、改名、删除 */
    private void showMemeManager() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        box.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final AlertDialog dlg = UiFactory.dialog(this)
                .setTitle("表情包（共 " + Memes.names(this).size() + " 张）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .create();
        dlg.show();
        fillMemeList(box, dlg);
    }

    /** 渲染表情列表；每次增删改后重调，实现「就地刷新」 */
    private void fillMemeList(final LinearLayout box, final AlertDialog dlg) {
        box.removeAllViews();
        final float d = getResources().getDisplayMetrics().density;
        List<Memes.Item> items = Memes.allItems(this);

        TextView tip = new TextView(this);
        tip.setTextSize(13);
        tip.setPadding(0, 0, 0, (int) (d * 8));
        tip.setText("内置表情为只读；用户上传的可以改描述、改名、删除。"
                + "描述越具体，桌宠越容易挑对图。");
        box.addView(tip);

        for (final Memes.Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (d * 6), 0, (int) (d * 6));
            row.setGravity(Gravity.CENTER_VERTICAL);

            android.widget.ImageView iv = new android.widget.ImageView(this);
            int side = (int) (d * 56);
            iv.setLayoutParams(new LinearLayout.LayoutParams(side, side));
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            try {
                // ★★ 轮次80：缩略图也走 openDrawable（上限 224px）—— GIF / 动图 WebP
                //   在列表里就会动起来；解不出来再退回原来的静态解码。
                android.graphics.drawable.Drawable thumb = Memes.openDrawable(this, it.name, 224);
                if (thumb != null) {
                    iv.setImageDrawable(thumb);
                } else {
                    InputStream is = Memes.openImage(this, it.name);
                    if (is != null) {
                        Bitmap bm = BitmapFactory.decodeStream(is);
                        is.close();
                        if (bm != null) iv.setImageBitmap(bm);
                    }
                }
            } catch (Throwable ignore) {}
            row.addView(iv);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding((int) (d * 10), 0, 0, 0);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvName = new TextView(this);
            tvName.setTextSize(15);
            tvName.setText(it.name + (it.custom ? "" : "（内置）"));
            col.addView(tvName);

            TextView tvDesc = new TextView(this);
            tvDesc.setTextSize(12);
            String dsc = it.desc == null || it.desc.trim().length() == 0 ? "（未填写使用场景）" : it.desc;
            tvDesc.setText("用法：" + dsc
                    + (it.width > 0 ? "   ·   " + it.width + "×" + it.height : ""));
            col.addView(tvDesc);
            row.addView(col);

            if (it.custom) {
                Button edit = new Button(this);
                edit.setText("编辑");
                edit.setTextSize(12);
                edit.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { editMemeDialog(it, box, dlg); }
                });
                row.addView(edit);
            }
            box.addView(row);

            View line = new View(this);
            line.setBackgroundColor(UiFactory.dividerColor(uiT()));
            box.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) Math.max(1, d)));
        }
    }

    /** 单个表情的编辑框：改描述 / 改名 / 删除 */
    private void editMemeDialog(final Memes.Item it, final LinearLayout listBox,
                                final AlertDialog parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView nl = new TextView(this);
        nl.setText("表情名（= 文件名）");
        nl.setTextSize(13);
        box.addView(nl);
        final EditText nameInput = new EditText(this);
        nameInput.setText(it.name);
        nameInput.setSingleLine(true);
        box.addView(nameInput);

        TextView dl = new TextView(this);
        dl.setText("什么时候用（描述）");
        dl.setTextSize(13);
        dl.setPadding(0, (int) (getResources().getDisplayMetrics().density * 8), 0, 0);
        box.addView(dl);
        final EditText descInput = new EditText(this);
        descInput.setText(it.desc == null ? "" : it.desc);
        descInput.setHint(Memes.descHint());
        descInput.setSingleLine(true);
        box.addView(descInput);

        UiFactory.dialog(this)
                .setTitle("编辑：" + it.name)
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String nn = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                        String nd = descInput.getText() == null ? "" : descInput.getText().toString();
                        boolean changed = false;
                        if (nn.length() > 0 && !nn.equals(it.name)) {
                            String r = Memes.rename(MainActivity.this, it.name, nn);
                            if (r == null) {
                                Toast.makeText(MainActivity.this,
                                        "改名失败：名字已存在或不可用", Toast.LENGTH_LONG).show();
                            } else {
                                changed = true;
                            }
                        }
                        String actual = (changed && nn.length() > 0) ? Memes.cleanName(nn) : it.name;
                        if (Memes.setDesc(MainActivity.this, actual, nd)) changed = true;
                        Toast.makeText(MainActivity.this,
                                changed ? "已保存" : "没有变化", Toast.LENGTH_SHORT).show();
                        fillMemeList(listBox, parent);
                    }
                })
                .setNeutralButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showDangerDialog("删除表情",
                                "确定删除「" + it.name + "」？图片文件也会一起删除。",
                                "删除", new Runnable() {
                            @Override public void run() {
                                if (Memes.removeCustom(MainActivity.this, it.name)) {
                                    Toast.makeText(MainActivity.this, "已删除",
                                            Toast.LENGTH_SHORT).show();
                                    showMemeManager();
                                } else {
                                    Toast.makeText(MainActivity.this, "删除失败",
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 取 content Uri 的显示名 */
    private String queryDisplayName(Uri uri) {
        String name = null;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) { try { c.close(); } catch (Throwable ignore) {} }
        }
        return name == null ? "自定义表情" : name;
    }

    /** 导入 AI 音色模型 *.onnx */

    /** 导入音色配置 config.json */

    /** 保存用户选择的图片；返回 2=动图，1=静态图，0=失败 */
    private int saveCustomPet(Uri uri) {
        InputStream is = null;
        java.io.OutputStream os = null;
        try {
            String type = getContentResolver().getType(uri);
            boolean anim = type != null
                    && (type.contains("gif") || type.contains("webp") || type.contains("animated"));
            is = getContentResolver().openInputStream(uri);
            if (is == null) return 0;

            File dst = anim ? Prefs.customAnimFile(this) : Prefs.customImageFile(this);
            os = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();

            if (anim) {
                Prefs.setCustomAnim(this, true);
                Prefs.setUseCustomImage(this, false);
            } else {
                Prefs.setCustomAnim(this, false);
                Prefs.setUseCustomImage(this, true);
            }
            return anim ? 2 : 1;
        } catch (Exception e) {
            return 0;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignore) {}
            try { if (os != null) os.close(); } catch (Exception ignore) {}
        }
    }
}
