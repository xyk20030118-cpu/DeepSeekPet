package com.deepseek.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;

/**
 * 单色矢量图标 —— 直接消费原型里的 SVG path 数据。
 *
 * <p><b>为什么必须换掉 emoji：</b>原型的每一行左侧是一个 36×36 凹陷底片 +
 * <b>单色强调色线稿图标</b>。这是整套视觉统一感的来源 —— 线稿图标只有一个
 * 颜色，会跟着令牌走，深浅主题、两种引擎下都协调。emoji 是彩色位图，
 * 颜色不受控，在深色新拟态下尤其突兀（一屏几十个彩色小方块），
 * 是"看起来和原型不一样"最直接的来源。
 *
 * <p><b>图标数据是生成的，不是手抄的：</b>{@code tools/make_icons.py} 把原型
 * {@code const I = {palette:'<path d="…"/><circle …/>', …}} 里每个图标**所有**
 * 子元素（path / circle / rect / line / polyline）合成一条路径再写成 Java 常量。
 *
 * <p>这一点很关键：43 个图标里有 32 个是多元素组成的，只取第一个
 * {@code <path>} 会让它们缺一半 —— 实测语音显示成一个"0"、记忆显示成"()"、
 * 表情与素材只剩一个空方框。所以千万别手改这个块，改原型后重新生成。
 *
 * <p>命令解析见 {@link #parseIconPath}：原型用到 {@code M L H V C S A Z}，
 * 其中 {@code A} 的两个 flag 是**单字符**（SVG 允许和数字连写），
 * 这是最容易写错的一处。
 */
final class VectorIcon {

    private VectorIcon() {}

    // ===== BEGIN GENERATED ICON PATHS (tools/build_vectoricon.py) =====
    // 由原型 I{} 生成，含每枚图标的**全部**子元素。勿手改；
    // 改原型后跑 tools/build_vectoricon.py 重新生成。

    static final String ALERT =
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z"
            + "M12 7.5v5.5"
            + "M11 16.4a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z";

    static final String BELL =
            "M18 15.5V11a6 6 0 10-12 0v4.5L4.5 18h15z"
            + "M10 21h4";

    static final String BOOK =
            "M4 4.5h6a3 3 0 013 3v12a2.4 2.4 0 00-2.4-2.4H4z"
            + "M20 4.5h-6a3 3 0 00-3 3v12a2.4 2.4 0 012.4-2.4H20z";

    static final String BOX =
            "M20.5 8.2v7.6a1.6 1.6 0 01-.9 1.4l-6.8 3.6a1.6 1.6 0 01-1.6 0l-6.8-3.6a1.6 1.6 0 01-.9-1.4V8.2"
            + "M3.7 7.6l7.6-4a1.6 1.6 0 011.4 0l7.6 4"
            + "M12 21.2V12";

    static final String BRAIN =
            "M9.5 3.5A3 3 0 006.6 6a2.8 2.8 0 00-1.9 4.6A3 3 0 005 15a3 3 0 003 3 2.6 2.6 0 004-1.6V5.6a2.2 2.2 0 00-2.5-2.1z"
            + "M14.5 3.5A3 3 0 0117.4 6a2.8 2.8 0 011.9 4.6A3 3 0 0119 15a3 3 0 01-3 3 2.6 2.6 0 01-4-1.6V5.6a2.2 2.2 0 012.5-2.1z";

    static final String CHART =
            "M4 20V4"
            + "M4 20h16"
            + "M8.5 12H9.5a1 1 0 0 1 1 1V16a1 1 0 0 1 -1 1H8.5a1 1 0 0 1 -1 -1V13a1 1 0 0 1 1 -1z"
            + "M13.5 8.5H14.5a1 1 0 0 1 1 1V16a1 1 0 0 1 -1 1H13.5a1 1 0 0 1 -1 -1V9.5a1 1 0 0 1 1 -1z"
            + "M18.5 14H19.5a1 1 0 0 1 1 1V16a1 1 0 0 1 -1 1H18.5a1 1 0 0 1 -1 -1V15a1 1 0 0 1 1 -1z";

    static final String CHAT =
            "M21 11.5a8.4 8.4 0 01-9 8.4 9.6 9.6 0 01-3.4-.6L3.5 21l1.4-4.2A8.2 8.2 0 013 11.5C3 7 7 3.5 12 3.5s9 3.5 9 8z";

    static final String CHECK =
            "M4.5 12.5l4.5 4.5 10.5-10.5";

    static final String CHEV =
            "M9 5l7 7-7 7";

    static final String CLOCK =
            "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z"
            + "M12 7.5V12l3 1.8";

    static final String DOT =
            "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z";

    static final String DROP =
            "M12 3.5s6 6.2 6 10.2a6 6 0 11-12 0C6 9.7 12 3.5 12 3.5z";

    static final String EYE =
            "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"
            + "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z";

    static final String FILM =
            "M5.5 5H18.5a2.5 2.5 0 0 1 2.5 2.5V16.5a2.5 2.5 0 0 1 -2.5 2.5H5.5a2.5 2.5 0 0 1 -2.5 -2.5V7.5a2.5 2.5 0 0 1 2.5 -2.5z"
            + "M8 5v14M16 5v14M3 12h18";

    static final String FINGER =
            "M9 11V5.6a1.8 1.8 0 013.6 0V11"
            + "M12.6 11V9.4a1.7 1.7 0 013.4 0V11"
            + "M16 11.4a1.7 1.7 0 013.4 0v3.2A6.4 6.4 0 0113 21h-1a6 6 0 01-6-6v-2.4a1.7 1.7 0 013.4 0";

    static final String GEAR =
            "M8.8 12a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z"
            + "M19.4 14.5a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 01-2.8 2.8l-.1-.1a1.7 1.7 0 00-2.9 1.2 2 2 0 01-4 0 1.7 1.7 0 00-2.9-1.2l-.1.1a2 2 0 01-2.8-2.8l.1-.1a1.7 1.7 0 00-1.2-2.9 2 2 0 010-4 1.7 1.7 0 001.2-2.9l-.1-.1a2 2 0 012.8-2.8l.1.1a1.7 1.7 0 002.9-1.2 2 2 0 014 0 1.7 1.7 0 002.9 1.2l.1-.1a2 2 0 012.8 2.8l-.1.1a1.7 1.7 0 001.2 2.9 2 2 0 010 4 1.7 1.7 0 00-1.6 1z";

    static final String GIFT =
            "M5.5 9H18.5a2 2 0 0 1 2 2V18.5a2 2 0 0 1 -2 2H5.5a2 2 0 0 1 -2 -2V11a2 2 0 0 1 2 -2z"
            + "M3.5 13.5h17M12 9v11.5"
            + "M12 9S10.5 4 7.8 4a2.4 2.4 0 000 5zM12 9s1.5-5 4.2-5a2.4 2.4 0 010 5z";

    static final String GLOBE =
            "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z"
            + "M3.5 12h17"
            + "M12 3.5a13 13 0 010 17 13 13 0 010-17z";

    static final String HIST =
            "M3.5 9.5A8.5 8.5 0 1112 20.5 8.4 8.4 0 015.5 17.6"
            + "M3 4.5v5h5"
            + "M12 8v4.3l3 1.7";

    static final String IMAGE =
            "M5.5 4H18.5a2.5 2.5 0 0 1 2.5 2.5V17.5a2.5 2.5 0 0 1 -2.5 2.5H5.5a2.5 2.5 0 0 1 -2.5 -2.5V6.5a2.5 2.5 0 0 1 2.5 -2.5z"
            + "M7 9.4a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z"
            + "M3.5 17l4.6-4.3 3.4 3.2 3-2.8 6 5.4";

    static final String INFO =
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z"
            + "M12 11v5"
            + "M10.9 7.8a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z";

    static final String KEY =
            "M4.5 15.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z"
            + "M10.6 13L20 3.6M17.4 6.2l1.8 1.8M15 8.6l1.8 1.8";

    static final String LAYERS =
            "M12 3.5l8.5 4.6L12 12.7 3.5 8.1z"
            + "M3.5 12.5L12 17l8.5-4.5M3.5 16.6L12 21.2l8.5-4.6";

    static final String LINK =
            "M10.5 13.5a4 4 0 005.7 0l2.6-2.6a4 4 0 10-5.7-5.7l-1 1"
            + "M13.5 10.5a4 4 0 00-5.7 0l-2.6 2.6a4 4 0 105.7 5.7l1-1";

    static final String LOCK =
            "M7 10.5H17a2.5 2.5 0 0 1 2.5 2.5V18a2.5 2.5 0 0 1 -2.5 2.5H7a2.5 2.5 0 0 1 -2.5 -2.5V13a2.5 2.5 0 0 1 2.5 -2.5z"
            + "M8 10.5V7.8a4 4 0 018 0v2.7";

    static final String LOGOUT =
            "M15 8V6a2 2 0 00-2-2H6.5a2 2 0 00-2 2v12a2 2 0 002 2H13a2 2 0 002-2v-2"
            + "M9.5 12H21M18 9l3 3-3 3";

    static final String MIC =
            "M12 3H12a3 3 0 0 1 3 3V11a3 3 0 0 1 -3 3H12a3 3 0 0 1 -3 -3V6a3 3 0 0 1 3 -3z"
            + "M5.5 11.5a6.5 6.5 0 0013 0M12 18v3";

    static final String MODEL =
            "M12 3l8 4.5v9L12 21l-8-4.5v-9z"
            + "M4 7.5l8 4.5 8-4.5M12 12v9";

    static final String PALETTE =
            "M12 3a9 9 0 100 18h1.5a2.5 2.5 0 002.5-2.5c0-.7-.3-1.3-.8-1.7h1.3A3.5 3.5 0 0020 13.3C20 7.6 16.4 3 12 3z"
            + "M6.8 10a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z"
            + "M10.8 8a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z"
            + "M14.3 10.5a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z";

    static final String PIN =
            "M12 21s6-5.3 6-10a6 6 0 10-12 0c0 4.7 6 10 6 10z"
            + "M9.8 11a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z";

    static final String PLAY =
            "M7 4.8l12 7.2-12 7.2z";

    /**
     * 手柄（游戏助手用）。
     *
     * <p>★ 用户 2026-10-07 要求：「给游戏模式的图标换成游戏手柄」。
     * 原型只有 43 枚图标、没有手柄，所以这一枚是**手写**的（同样的 24×24 视口、
     * 1.7 描边、圆头圆角），并且已经在 {@code prototype.html} 的 {@code I{}} 里
     * 同步登记 —— 否则下次用 {@code tools/build_vectoricon.py} 重新生成时它会丢。
     */
    static final String GAMEPAD =
            "M8.2 9h7.6c2.4 0 4.4 1.8 4.6 4.2l.3 3.4c.1 1.6-1.8 2.4-2.9 1.3"
            + "l-1.6-1.7c-.4-.4-.9-.6-1.5-.6h-4.6c-.6 0-1.1.2-1.5.6l-1.6 1.7"
            + "c-1.1 1.1-3 .3-2.9-1.3l.3-3.4C4 10.8 5.9 9 8.2 9z"
            + " M9 12.2v2.4 M7.8 13.4h2.4 M15.6 12.4h.01 M17.6 14.4h.01";

    // ==================== 轮次78 追加的自绘图标（10 枚）====================
    //
    // 起因：审计发现设置页有 7 行的行首 emoji 没有任何图标映射（图标槽空着），
    // 另有 3 行（贴边形态 / 挂边角度 / 挂出深度）只是落到通用 SLIDERS/PIN。
    // 用户要求「为所有没有增加图标的选项自定义图标」⇒ 这 10 枚按同一规格手写：
    // 24×24 视口、1.7 描边、圆头圆角，并同步登记进 prototype.html 的 I{}。
    // =========================================================================

    /** 屏幕边缘 + 探出来的头（贴边形态：挂边露头） */
    static final String PEEK_STYLE =
            "M6 3v18"
            + " M6 8.4a3.6 3.6 0 0 1 0 7.2"
            + " M9.4 16.4l1.8 1.6";

    /** 边缘 + 斜线 + 角度弧（挂边角度） */
    static final String PEEK_ANGLE =
            "M5 20V4"
            + " M5 16h9"
            + " M5 16L14 7"
            + " M9.6 16a5 5 0 0 0 -1.9 -4.4";

    /** 边缘 + 方框 + 向内箭头（挂出深度） */
    static final String PEEK_DEPTH =
            "M4 3v18"
            + " M9 6h10v12H9z"
            + " M17 12H8"
            + " M10.5 9.5L8 12l2.5 2.5";

    /** 圆 + 直径 + 斜纹（UI 透明度） */
    static final String OPACITY =
            "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z"
            + " M12 3.5v17"
            + " M8.4 9.2l7.2 7.2";

    /** 八分音符（音效音色） */
    static final String MUSIC =
            "M16.2 16V5.4"
            + " M16.2 5.4c1.8.5 2.6 1.5 2.6 3"
            + " M11.8 16a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z";

    /** 喇叭 + 声波（音效音量） */
    static final String SPEAKER =
            "M4 9.5h3.2L12 6v12l-4.8-3.5H4z"
            + " M15 9.2a4 4 0 0 1 0 5.6"
            + " M17.6 7.2a7 7 0 0 1 0 9.6";

    /** 走路小人（自动漫游） */
    static final String WALK =
            "M14.6 4.4a1.9 1.9 0 1 0 0 3.8a1.9 1.9 0 0 0 0-3.8z"
            + " M14.6 8.2l-1.6 4.6"
            + " M13 12.8l-2.6 4.2"
            + " M13 12.8l3.4 3.2"
            + " M13.4 9.8l-3 2.2";

    /** 路障横杠 + 斜纹（漫游避开顶部） */
    static final String BARRIER =
            "M5 8v10"
            + " M19 8v10"
            + " M3.6 10.4h16.8v3.2H3.6z"
            + " M7 10.4l-2 3.2"
            + " M12 10.4l-2 3.2"
            + " M17 10.4l-2 3.2";

    /** 圆点 + 三横线（自定义动作菜单） */
    static final String LIST =
            "M7.5 6.6a1.1 1.1 0 1 0 0 2.2a1.1 1.1 0 0 0 0-2.2z"
            + " M7.5 10.9a1.1 1.1 0 1 0 0 2.2a1.1 1.1 0 0 0 0-2.2z"
            + " M7.5 15.2a1.1 1.1 0 1 0 0 2.2a1.1 1.1 0 0 0 0-2.2z"
            + " M11 7.7h9"
            + " M11 12h9"
            + " M11 16.3h9";

    /** 锥形瓶（测试连接） */
    static final String FLASK =
            "M9 3.5h6"
            + " M10.4 3.5v5.3l-4.8 9.6c-.6 1.2.3 2.6 1.6 2.6h9.6c1.3 0 2.2-1.4 1.6-2.6l-4.8-9.6V3.5"
            + " M8 14.6h8";

    static final String REFRESH =
            "M20 11.5A8 8 0 106.3 17M20 5.5v6h-6";

    static final String ROBOT =
            "M7 7.5H17a3 3 0 0 1 3 3V16.5a3 3 0 0 1 -3 3H7a3 3 0 0 1 -3 -3V10.5a3 3 0 0 1 3 -3z"
            + "M8.1 13a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0z"
            + "M13.1 13a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0z"
            + "M12 4v3.5M9 17h6";

    static final String SHIELD =
            "M12 3l7.5 3v5.5c0 4.6-3.1 8.2-7.5 9.5-4.4-1.3-7.5-4.9-7.5-9.5V6z";

    static final String SLIDERS =
            "M5 20V13M5 9V4M12 20v-4M12 12V4M19 20v-9M19 7V4"
            + "M3 11a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z"
            + "M10 14a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z"
            + "M17 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z";

    static final String SPARKLE =
            "M12 3l1.9 5.4L19.5 10l-5.6 1.6L12 17l-1.9-5.4L4.5 10l5.6-1.6z"
            + "M18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z";

    static final String TAIL =
            "M20 11.5a8 8 0 01-8 8H5l1.6-3.4A8 8 0 1120 11.5z";

    static final String TEXT =
            "M5 6.5h14M12 6.5v12";

    static final String TRASH =
            "M4.5 7h15M9.5 7V5.2A1.2 1.2 0 0110.7 4h2.6a1.2 1.2 0 011.2 1.2V7M6.5 7l1 12.1A1.5 1.5 0 009 20.5h6a1.5 1.5 0 001.5-1.4L17.5 7";

    static final String UNDO =
            "M4 10.5h9.5a5.5 5.5 0 010 11H9"
            + "M8 6l-4 4.5L8 15";

    static final String UPLOAD =
            "M12 16V4.5M7.5 9L12 4.5 16.5 9"
            + "M4.5 15v3.5A2 2 0 006.5 20.5h11a2 2 0 002-2V15";

    static final String USER =
            "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z"
            + "M4.5 20.5a7.5 7.5 0 0115 0";

    static final String WARN =
            "M12 4l9 16H3z"
            + "M12 10v4"
            + "M11 17a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z";

    // ===== END GENERATED ICON PATHS =====

    /**
     * SVG path 解析器，覆盖原型 {@code I{}} 里出现的全部命令。
     *
     * <p><b>为什么不用 {@code android.util.PathParser}：</b>它在编译期的
     * android.jar 里是 {@code @hide} 的（运行时存在，但 {@code -bootclasspath}
     * 下 javac 看不到符号），直接调编译失败。
     * {@code androidx.core.graphics.PathParser} 有公开版本，但本工程
     * **零 androidx 依赖**（纯 framework），引入会破坏这个前提。
     *
     * <p>支持 {@code M L H V C S A Z}（大小写皆可）。原型里用到 S（平滑三次曲线），
     * 少了它 eye / gift / hist 等图标会缺一块。
     *
     * <p><b>关键坑一：A 命令的 flag 是单字符。</b>SVG 允许把 flag 和前后的数字
     * 连写，例如 {@code "a9 9 0 100 18"} 实际是
     * {@code rx=9 ry=9 rot=0 large-arc=1 sweep=0 x=0 y=18} —— 那个 {@code 100}
     * 不是一百，而是「1」「0」「0」三个 token。若按普通数字扫描就会解析错，
     * 把圆弧画成完全不同的形状（tools/check_paths.py 抓到过这个问题）。
     *
     * <p><b>关键坑二：命令可以紧挨着命令</b>（{@code "M5 5h14M12 5v14"}），
     * 必须逐字符扫描、遇到字母就切换命令，不能先分词再逐个取。
     *
     * <p>所以这里写成**逐字符状态机**，而不是先分词再处理。
     */
    static Path parseIconPath(String d) {
        Path p = new Path();
        if (d == null || d.length() == 0) return p;

        int i = 0;
        final int n = d.length();
        char cmd = 0;
        float cx = 0, cy = 0;      // 当前点
        float sx = 0, sy = 0;      // 子路径起点（Z 用）
        float lx = 0, ly = 0;      // 上一个控制点（S 用）
        boolean prevCubic = false;

        while (i < n) {
            char ch = d.charAt(i);
            if (ch == ',' || Character.isWhitespace(ch)) { i++; continue; }
            if (Character.isLetter(ch)) {
                cmd = ch;
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.close();
                    cx = sx; cy = sy;
                    prevCubic = false;
                }
                continue;
            }
            final boolean rel = Character.isLowerCase(cmd);
            final char up = Character.toUpperCase(cmd);
            try {
                switch (up) {
                    case 'M': {
                        float x = num(d, i); i = next(d, i);
                        float y = num(d, i); i = next(d, i);
                        if (rel) { x += cx; y += cy; }
                        p.moveTo(x, y); cx = x; cy = y; sx = x; sy = y;
                        cmd = rel ? 'l' : 'L';   // 后续隐式 lineto
                        prevCubic = false;
                        break;
                    }
                    case 'L': {
                        float x = num(d, i); i = next(d, i);
                        float y = num(d, i); i = next(d, i);
                        if (rel) { x += cx; y += cy; }
                        p.lineTo(x, y); cx = x; cy = y;
                        prevCubic = false;
                        break;
                    }
                    case 'H': {
                        float x = num(d, i); i = next(d, i);
                        if (rel) x += cx;
                        p.lineTo(x, cy); cx = x;
                        prevCubic = false;
                        break;
                    }
                    case 'V': {
                        float y = num(d, i); i = next(d, i);
                        if (rel) y += cy;
                        p.lineTo(cx, y); cy = y;
                        prevCubic = false;
                        break;
                    }
                    case 'C': {
                        float x1 = num(d, i); i = next(d, i);
                        float y1 = num(d, i); i = next(d, i);
                        float x2 = num(d, i); i = next(d, i);
                        float y2 = num(d, i); i = next(d, i);
                        float x = num(d, i);  i = next(d, i);
                        float y = num(d, i);  i = next(d, i);
                        if (rel) {
                            x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy;
                        }
                        p.cubicTo(x1, y1, x2, y2, x, y);
                        lx = x2; ly = y2; cx = x; cy = y;
                        prevCubic = true;
                        break;
                    }
                    case 'S': {
                        // 平滑三次：第一控制点是上一点的镜像
                        float x2 = num(d, i); i = next(d, i);
                        float y2 = num(d, i); i = next(d, i);
                        float x = num(d, i);  i = next(d, i);
                        float y = num(d, i);  i = next(d, i);
                        if (rel) { x2 += cx; y2 += cy; x += cx; y += cy; }
                        float x1 = prevCubic ? 2 * cx - lx : cx;
                        float y1 = prevCubic ? 2 * cy - ly : cy;
                        p.cubicTo(x1, y1, x2, y2, x, y);
                        lx = x2; ly = y2; cx = x; cy = y;
                        prevCubic = true;
                        break;
                    }
                    case 'A': {
                        // rx ry rot large-arc sweep x y —— 后三个 flag 是单字符
                        i = skipSep(d, i);
                        float rx = num(d, i); i = next(d, i);
                        float ry = num(d, i); i = next(d, i);
                        num(d, i); i = next(d, i);      // rot（原型里全是 0）
                        i = skipSep(d, i);
                        boolean large = d.charAt(i) == '1'; i++;
                        i = skipSep(d, i);
                        boolean sweep = d.charAt(i) == '1'; i++;
                        i = skipSep(d, i);
                        float x = num(d, i); i = next(d, i);
                        float y = num(d, i); i = next(d, i);
                        if (rel) { x += cx; y += cy; }
                        arcTo(p, cx, cy, rx, ry, large, sweep, x, y);
                        cx = x; cy = y;
                        prevCubic = false;
                        break;
                    }
                    default:
                        i = next(d, i);   // 不认识的命令：跳过这个数字
                        prevCubic = false;
                }
            } catch (RuntimeException e) {
                break;   // 数据异常就结束，残缺好过崩溃
            }
        }
        return p;
    }

    /** 跳过分隔符（逗号 / 空白） */
    private static int skipSep(String d, int i) {
        final int n = d.length();
        while (i < n && (d.charAt(i) == ',' || Character.isWhitespace(d.charAt(i)))) i++;
        return i;
    }

    /** 读一个数字（允许数字前有分隔符） */
    private static float num(String d, int i) {
        return Float.parseFloat(d.substring(i, next(d, i)));
    }

    /**
     * 从 i 开始扫描一个数字，返回它结束后的下标。
     *
     * <p><b>必须在一个数字里只允许一个小数点</b>，否则 SVG 压缩写法
     * （省略分隔符）会被读错。实例：GEAR 路径里的 {@code l.1.1}
     * 是"line by (0.1, 0.1)"两个数字，但按"数字可含多个点"去扫会读成
     * 一个 {@code ".1.1"}，{@code Float.parseFloat} 抛
     * {@code NumberFormatException}，被外层 {@code catch} 静默吞掉 ——
     * 结果是**从那里往后的整条子路径全部丢失**。
     * GEAR 的齿圈正好在这之后，于是齿轮渲染成"一个圆加一个点"
     * （底部导航"系统"页的图标，真机截图确认）。
     *
     * <p>同理，"00.3" 这种"两个 0 紧贴 .3"的旗标写法也必须靠
     * 单点规则才能正确切成 {@code 0} / {@code 0} / {@code .3}。
     */
    private static int next(String d, int i) {
        final int n = d.length();
        i = skipSep(d, i);
        int st = i;
        if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean sawDot = false;
        while (i < n) {
            char c = d.charAt(i);
            if (c == '.') {
                if (sawDot) break;      // 第二个小数点：这是下一个数字
                sawDot = true;
                i++;
                continue;
            }
            if (Character.isDigit(c)) { i++; continue; }
            // 紧贴的符号也是下一个数字（如 "0-6.4"）
            if (c == '-' || c == '+') break;
            if ((c == 'e' || c == 'E') && i + 1 < n) {
                char nx = d.charAt(i + 1);
                if (Character.isDigit(nx)
                        || ((nx == '-' || nx == '+') && i + 2 < n
                            && Character.isDigit(d.charAt(i + 2)))) {
                    i += 2;
                    continue;
                }
            }
            break;
        }
        if (i == st) return st + 1;   // 空数字：前进一格防死循环
        return i;
    }

    /**
     * 椭圆弧转贝塞尔（SVG {@code A} 命令）。
     * 原型里只用于圆角与圆点，分段误差肉眼不可见。
     */
    private static void arcTo(Path p, float x0, float y0, float rx, float ry,
                              boolean large, boolean sweep, float x, float y) {
        if (rx == 0 || ry == 0) { p.lineTo(x, y); return; }
        if (x0 == x && y0 == y) return;
        rx = Math.abs(rx); ry = Math.abs(ry);
        double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
        double lam = (dx2 * dx2) / ((double) rx * rx) + (dy2 * dy2) / ((double) ry * ry);
        if (lam > 1) { double s = Math.sqrt(lam); rx *= s; ry *= s; }
        double sign = (large != sweep) ? 1.0 : -1.0;
        double numr = (double) rx * rx * ry * ry - (double) rx * rx * dy2 * dy2
                    - (double) ry * ry * dx2 * dx2;
        double den = (double) rx * rx * dy2 * dy2 + (double) ry * ry * dx2 * dx2;
        double co = den == 0 ? 0 : sign * Math.sqrt(Math.max(0, numr / den));
        double cxp = co * rx * dy2 / ry, cyp = -co * ry * dx2 / rx;
        double ccx = cxp + (x0 + x) / 2.0, ccy = cyp + (y0 + y) / 2.0;

        double t1 = Math.atan2((dy2 - cyp) / ry, (dx2 - cxp) / rx);
        double t2 = Math.atan2((-dy2 - cyp) / ry, (-dx2 - cxp) / rx);
        double dt = t2 - t1;
        if (!sweep && dt > 0) dt -= 2 * Math.PI;
        else if (sweep && dt < 0) dt += 2 * Math.PI;

        int seg = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2));
        if (seg <= 0) seg = 1;
        double dseg = dt / seg;
        double t = t1;
        double kk = 4.0 / 3.0 * Math.tan(dseg / 4.0);
        for (int s = 0; s < seg; s++) {
            double tn = t + dseg;
            float c1x = (float) (ccx + rx * (Math.cos(t) - kk * Math.sin(t)));
            float c1y = (float) (ccy + ry * (Math.sin(t) + kk * Math.cos(t)));
            float c2x = (float) (ccx + rx * (Math.cos(tn) + kk * Math.sin(tn)));
            float c2y = (float) (ccy + ry * (Math.sin(tn) - kk * Math.cos(tn)));
            float ex = (float) (ccx + rx * Math.cos(tn));
            float ey = (float) (ccy + ry * Math.sin(tn));
            p.cubicTo(c1x, c1y, c2x, c2y, ex, ey);
            t = tn;
        }
    }


    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    /**
     * 语义名 → 生成的常量。名字与原型 {@code I{}} 的键一致。
     * 未收录的名字回退到 CHAT（宁可给个通用图标，也不要空白）。
     */
    static String pathFor(String name) {
        if (name == null) return CHAT;
        String n = name.toUpperCase(java.util.Locale.US);
        if (n.equals("BELL")) return BELL;
        if (n.equals("BOOK")) return BOOK;
        if (n.equals("BOX")) return BOX;
        if (n.equals("BRAIN")) return BRAIN;
        if (n.equals("CHART")) return CHART;
        if (n.equals("CHAT")) return CHAT;
        if (n.equals("CHECK")) return CHECK;
        if (n.equals("CHEV")) return CHEV;
        if (n.equals("CLOCK")) return CLOCK;
        if (n.equals("DOT")) return DOT;
        if (n.equals("DROP")) return DROP;
        if (n.equals("EYE")) return EYE;
        if (n.equals("FILM")) return FILM;
        if (n.equals("FINGER")) return FINGER;
        if (n.equals("GAMEPAD")) return GAMEPAD;   // 手柄（用户 2026-10-07 要求；见 GAMEPAD 注释）
        if (n.equals("PEEK_STYLE")) return PEEK_STYLE;
        if (n.equals("PEEK_ANGLE")) return PEEK_ANGLE;
        if (n.equals("PEEK_DEPTH")) return PEEK_DEPTH;
        if (n.equals("OPACITY")) return OPACITY;
        if (n.equals("MUSIC")) return MUSIC;
        if (n.equals("SPEAKER")) return SPEAKER;
        if (n.equals("WALK")) return WALK;
        if (n.equals("BARRIER")) return BARRIER;
        if (n.equals("LIST")) return LIST;
        if (n.equals("FLASK")) return FLASK;
        if (n.equals("GEAR")) return GEAR;
        if (n.equals("GIFT")) return GIFT;
        if (n.equals("GLOBE")) return GLOBE;
        if (n.equals("HIST")) return HIST;
        if (n.equals("IMAGE")) return IMAGE;
        if (n.equals("INFO")) return INFO;
        if (n.equals("KEY")) return KEY;
        if (n.equals("LAYERS")) return LAYERS;
        if (n.equals("LINK")) return LINK;
        if (n.equals("LOCK")) return LOCK;
        if (n.equals("LOGOUT")) return LOGOUT;
        if (n.equals("MIC")) return MIC;
        if (n.equals("MODEL")) return MODEL;
        if (n.equals("PALETTE")) return PALETTE;
        if (n.equals("PIN")) return PIN;
        if (n.equals("PLAY")) return PLAY;
        if (n.equals("REFRESH")) return REFRESH;
        if (n.equals("ROBOT")) return ROBOT;
        if (n.equals("SHIELD")) return SHIELD;
        if (n.equals("SLIDERS")) return SLIDERS;
        if (n.equals("SPARKLE")) return SPARKLE;
        if (n.equals("TAIL")) return TAIL;
        if (n.equals("TEXT")) return TEXT;
        if (n.equals("TRASH")) return TRASH;
        if (n.equals("UNDO")) return UNDO;
        if (n.equals("UPLOAD")) return UPLOAD;
        if (n.equals("USER")) return USER;
        if (n.equals("WARN")) return WARN;
        return CHAT;
    }

    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程的设置文案全部以 emoji 开头（“🎨 外观”、“🔊 语音”…），
     * 有了这张表，{@link com.deepseek.pet.UiFactory#takeLeadingEmoji}
     * 剥下来的 emoji 就能换成对应线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null，调用方退回显示 emoji 本身
     * （宁可显示彩色 emoji，也不要空徽章）。
     */
    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程设置文案以 emoji 开头（“🎨 外观”…），有了这张表，
     * {@link com.deepseek.pet.UiFactory#takeLeadingEmoji} 剥下来的
     * emoji 就能换成线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null —— 调用方**不画图标只留底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程设置文案以 emoji 开头（“🎨 外观”…），有了这张表，
     * {@link com.deepseek.pet.UiFactory#takeLeadingEmoji} 剥下来的
     * emoji 就能换成线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null —— 调用方**不画图标只留底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程设置文案以 emoji 开头（“🎨 外观”…），有了这张表，
     * {@link com.deepseek.pet.UiFactory#takeLeadingEmoji} 剥下来的
     * emoji 就能换成线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null —— 调用方**不画图标只留底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程设置文案以 emoji 开头（“🎨 外观”…），有了这张表，
     * {@link com.deepseek.pet.UiFactory#takeLeadingEmoji} 剥下来的
     * emoji 就能换成线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null —— 调用方**不画图标只留底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程设置文案以 emoji 开头（“🎨 外观”…），有了这张表，
     * {@link com.deepseek.pet.UiFactory#takeLeadingEmoji} 剥下来的
     * emoji 就能换成线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null —— 调用方**不画图标只留底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程设置文案以 emoji 开头（“🎨 外观”…），有了这张表，
     * {@link com.deepseek.pet.UiFactory#takeLeadingEmoji} 剥下来的
     * emoji 就能换成线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null —— 调用方**不画图标只留底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程设置文案以 emoji 开头（“🎨 外观”…），有了这张表，
     * {@link com.deepseek.pet.UiFactory#takeLeadingEmoji} 剥下来的
     * emoji 就能换成线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null —— 调用方**不画图标只留底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程设置文案以 emoji 开头（“🎨 外观”…），有了这张表，
     * {@link com.deepseek.pet.UiFactory#takeLeadingEmoji} 剥下来的
     * emoji 就能换成线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null —— 调用方**不画图标只留底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    /**
     * emoji → 生成的常量名。
     *
     * <p>本工程设置文案以 emoji 开头（“🎨 外观”…），有了这张表，
     * {@link com.deepseek.pet.UiFactory#takeLeadingEmoji} 剥下来的
     * emoji 就能换成线稿图标，60+ 处调用点一行都不用改。
     *
     * <p>表里没有的 emoji 返回 null —— 调用方**不画图标只留底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    static String fromEmoji(String emoji) {
        if (emoji == null) return null;
        String e = emoji.replace("\uFE0F", "").replace("\u200D", "").trim();
        if (e.equals("\uD83E\uDEA9")) return "PEEK_STYLE";   // 🪝 贴边形态
        if (e.equals("\uD83E\uDEE7")) return "OPACITY";   // 🫧 UI 透明度（原来没有图标）
        if (e.equals("\uD83C\uDFB5")) return "MUSIC";   // 🎵 音效音色（原来没有图标）
        if (e.equals("\uD83D\uDD09")) return "SPEAKER";   // 🔉 音效音量（原来没有图标）
        if (e.equals("\uD83D\uDEB6")) return "WALK";   // 🚶 自动漫游（原来没有图标）
        if (e.equals("\uD83D\uDEA7")) return "BARRIER";   // 🚧 避开顶部 1/3（原来没有图标）
        if (e.equals("\uD83D\uDDC2")) return "LIST";   // 🗂 自定义动作菜单（原来没有图标）
        if (e.equals("\uD83E\uDDEA")) return "FLASK";   // 🧪 测试连接（原来没有图标）
        if (e.equals("\uD83C\uDFA8")) return "PALETTE";
        if (e.equals("\uD83D\uDCAC")) return "CHAT";
        if (e.equals("\uD83E\uDDE0")) return "BRAIN";
        if (e.equals("\u2699")) return "GEAR";
        if (e.equals("\u2139")) return "INFO";
        if (e.equals("\uD83D\uDDBC")) return "IMAGE";
        if (e.equals("\uD83C\uDFA4")) return "MIC";
        if (e.equals("\uD83D\uDD0A")) return "MIC";
        if (e.equals("\uD83D\uDEE1")) return "SHIELD";
        if (e.equals("\u23F1")) return "CLOCK";
        if (e.equals("\uD83D\uDD14")) return "BELL";
        if (e.equals("\uD83D\uDC40")) return "EYE";
        if (e.equals("\uD83D\uDD0D")) return "EYE";
        if (e.equals("\uD83D\uDCE4")) return "UPLOAD";
        if (e.equals("\uD83D\uDD17")) return "LINK";
        if (e.equals("\u26A0")) return "WARN";
        if (e.equals("\uD83D\uDCCA")) return "CHART";
        if (e.equals("\uD83D\uDCB0")) return "CHART";
        if (e.equals("\uD83D\uDCB3")) return "CHART";
        if (e.equals("\uD83D\uDCDD")) return "TEXT";
        if (e.equals("\uD83D\uDC3E")) return "IMAGE";
        if (e.equals("\uD83E\uDDE9")) return "MODEL";
        if (e.equals("\uD83D\uDCE6")) return "BOX";
        if (e.equals("\uD83D\uDCF7")) return "IMAGE";
        if (e.equals("\uD83C\uDFAC")) return "FILM";
        if (e.equals("\uD83D\uDD11")) return "KEY";
        if (e.equals("\uD83D\uDC46")) return "FINGER";
        if (e.equals("\uD83D\uDCA1")) return "INFO";
        if (e.equals("\uD83D\uDE80")) return "PLAY";
        if (e.equals("\uD83D\uDD04")) return "REFRESH";
        if (e.equals("\u2615")) return "GIFT";
        if (e.equals("\uD83C\uDF81")) return "GIFT";
        if (e.equals("\uD83D\uDD52")) return "CLOCK";
        if (e.equals("\uD83D\uDDD1")) return "TRASH";
        if (e.equals("\uD83D\uDCC1")) return "BOX";
        if (e.equals("\uD83C\uDFF4")) return "IMAGE";
        if (e.equals("\uD83E\uDD16")) return "ROBOT";
        if (e.equals("\uD83D\uDD12")) return "LOCK";
        if (e.equals("\uD83C\uDF10")) return "GLOBE";
        if (e.equals("\uD83D\uDCE5")) return "UPLOAD";
        if (e.equals("\uD83D\uDCBE")) return "LAYERS";
        if (e.equals("\uD83D\uDD27")) return "SLIDERS";
        if (e.equals("\uD83D\uDCCB")) return "TEXT";
        if (e.equals("\uD83D\uDC64")) return "USER";
        if (e.equals("\u2B05")) return "CHEV";
        if (e.equals("\u2705")) return "CHECK";
        if (e.equals("\u274C")) return "WARN";
        if (e.equals("\uD83D\uDD34")) return "DOT";
        if (e.equals("\u23F0")) return "CLOCK";
        if (e.equals("\uD83D\uDCCC")) return "PIN";
        if (e.equals("\uD83D\uDCD6")) return "BOOK";
        if (e.equals("\uD83D\uDCA7")) return "DROP";
        if (e.equals("\u2728")) return "SPARKLE";
        if (e.equals("\uD83D\uDD01")) return "REFRESH";
        if (e.equals("\uD83D\uDD22")) return "TEXT";
        if (e.equals("\u23F3")) return "CLOCK";
        if (e.equals("\uD83D\uDC4B")) return "USER";
        if (e.equals("\uD83D\uDCC4")) return "TEXT";
        if (e.equals("\uD83D\uDD13")) return "LOCK";
        if (e.equals("\uD83E\uDDF1")) return "BOX";
        if (e.equals("\uD83D\uDD0E")) return "EYE";
        if (e.equals("\uD83D\uDD0B")) return "DOT";
        if (e.equals("\u27A1")) return "CHEV";
        if (e.equals("\u2B06")) return "UPLOAD";
        if (e.equals("\u2714")) return "CHECK";
        if (e.equals("\u2713")) return "CHECK";
        if (e.equals("\u25B8")) return "CHEV";
        if (e.equals("\uD83C\uDFA7")) return "MIC";
        if (e.equals("\uD83C\uDF9A")) return "SLIDERS";
        if (e.equals("\uD83D\uDCDF")) return "LINK";
        if (e.equals("\uD83C\uDFAD")) return "USER";
        if (e.equals("\uD83C\uDF9C")) return "IMAGE";
        if (e.equals("\uD83C\uDF8F")) return "SPARKLE";
        if (e.equals("\uD83C\uDFF7")) return "TEXT";
        if (e.equals("\u25B6")) return "PLAY";
        if (e.equals("\u25C0")) return "CHEV";
        if (e.equals("\u270F")) return "TEXT";
        if (e.equals("\u26A1")) return "CHART";
        if (e.equals("\uD83D\uDCCF")) return "PEEK_DEPTH";   // 📏 挂出深度
        if (e.equals("\u2795")) return "CHECK";
        if (e.equals("\u2796")) return "SLIDERS";
        if (e.equals("\u2715")) return "WARN";
        if (e.equals("\u274C")) return "WARN";
        if (e.equals("\uD83D\uDDE3")) return "MIC";
        if (e.equals("\uD83D\uDD50")) return "CLOCK";
        if (e.equals("\uD83D\uDD58")) return "CLOCK";
        if (e.equals("\uD83E\uDDF9")) return "TRASH";
        if (e.equals("\uD83D\uDCAD")) return "CHAT";
        if (e.equals("\uD83E\uDD10")) return "LOCK";
        if (e.equals("\uD83D\uDD07")) return "WARN";
        if (e.equals("\uD83D\uDE34")) return "LOGOUT";
        if (e.equals("\uD83D\uDD7F")) return "DROP";
        if (e.equals("\uD83D\uDE48")) return "EYE";
        if (e.equals("\uD83D\uDC41")) return "EYE";
        if (e.equals("\uD83D\uDCDA")) return "BOOK";
        if (e.equals("\uD83C\uDF9B")) return "SLIDERS";
        if (e.equals("\uD83D\uDCD0")) return "PEEK_ANGLE";   // 📐 挂边角度
        if (e.equals("\u23F9")) return "LOGOUT";
        if (e.equals("\uD83D\uDEE0")) return "GEAR";
        // ---- 轮次⑨ 补齐（audit_emoji.py 扫出来的三个空底片）----
        // 原型只有 43 枚图标，没有画过麦克风/手柄/月亮，按**语义最近**映射，
        // 不新增图标集合（与上面"补齐"那批同一原则）。
        if (e.equals("\uD83C\uDF99")) return "MIC";      // 🎙 语音输入资源 -> 麦克风
        if (e.equals("\uD83C\uDFAE")) return "GAMEPAD";  // 🎮 游戏助手 -> 手柄（用户 2026-10-07 要求）
        if (e.equals("\uD83C\uDF19")) return "LOGOUT";   // 🌙 熄屏省电 -> 休眠/停止
        return null;
    }

    // ==================== 绘制 ====================

    /** 把 SVG path（原型视口 24×24）缩放绘制到画布。 */
    private static void paintIcon(Canvas cv, String svg, float sizePx, int color) {
        Path p = parseIconPath(svg);
        if (p.isEmpty()) return;
        float scale = sizePx / 24f;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        p.transform(m);

        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(color);
        // 原型是线稿（stroke="currentColor" fill="none"），描边宽度约 1.7/24
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(1.7f * scale);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeJoin(Paint.Join.ROUND);
        cv.drawPath(p, pt);
    }

    /** 生成一个只画该图标的 View。{@code sizeDp} 是绘制边长（原型 .ico svg = 19）。 */
    static View view(Context c, String name, int color, float sizeDp) {
        return new IconView(c, pathFor(name), color, UiKit.dp(c, sizeDp));
    }

    /** 只画矢量图标的 View */
    private static final class IconView extends View {
        private final String path;
        private final int color;
        private final int px;

        IconView(Context c, String path, int color, int px) {
            super(c);
            this.path = path;
            this.color = color;
            this.px = px;
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(px, px);
        }

        @Override protected void onDraw(Canvas cv) {
            paintIcon(cv, path, px, color);
        }
    }
}
