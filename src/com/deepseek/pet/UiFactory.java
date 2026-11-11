package com.deepseek.pet;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 视图工厂 —— 原型视觉落到真实控件上的唯一入口。
 *
 * <p>改造前 {@code MainActivity} 直接 {@code new Button(this)}、{@code new CheckBox(this)}，
 * 全靠系统默认样式，于是评审报告里那些问题全都成立：开关触点只有 50.9×27.4dp
 * （低于 48dp 可达性下限）、按钮没有统一圆角、卡片无层级。
 *
 * <p>这里把「造控件」和「怎么长」分开：布局代码只管说"这是一个分组卡片"、
 * "这是一个主按钮"，视觉全部由令牌驱动。换引擎/主题不需要动布局。
 */
final class UiFactory {

    private UiFactory() {}

    // ==================== 尺寸 ====================

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ==================== 页面骨架 ====================

    /** 页面背景：玻璃引擎铺 Mesh 光斑，新拟态引擎是纯底色 */
    static void decoratePage(ViewGroup root, Context c, Tokens.Set t) {
        root.setBackground(new UiKit.MeshDrawable(t));
    }

    /**
     * 分组卡片：圆角 + 浮雕/玻璃底 + 内边距。
     *
     * <p><b>为什么 padding 要加上 inset：</b>浮雕阴影需要画在形状之外，
     * 而 Drawable 的边界就是 View 的边界 —— 阴影画出去会被裁掉。
     * 所以 {@link UiKit.EmbossDrawable} 把形状从边界内缩，留出偏移+模糊的空间。
     * 文字必须跟着让开同样的量，否则会压在阴影光晕上。
     *
     * <p>注意这里**不能**用 margin 代替：margin 缩小的是 View 本身，
     * Drawable 边界跟着缩小，阴影照样没地方画（试过，无效）。
     */
    static LinearLayout card(Context c, Tokens.Set t) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiKit.card(c, t, Tokens.R_CARD));
        int inset = inset(c);
        int p = dp(c, Tokens.PAD_CARD);
        box.setPadding(p + inset, dp(c, 8) + inset, p + inset, dp(c, 12) + inset);
        return box;
    }

    /**
     * 分组容器卡：**只留浮雕余量，不留内容内边距**。
     *
     * <p>原型里 {@code .card} 自己不设 padding，padding 由每个 {@code .row}
     * 各自带上（{@code padding:13px var(--pad-card)}）。这样行与行之间的
     * 分隔线才能通到卡片边缘内侧、而文字又整齐对齐。
     * 如果在这里加 PAD_CARD，行会缩进两次。
     */
    static LinearLayout bareCard(Context c, Tokens.Set t) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiKit.card(c, t, Tokens.R_CARD));
        int inset = inset(c);
        // 上下的 inset 让阴影有地方画；左右也用 inset 保持圆角不被裁
        box.setPadding(inset, inset, inset, inset);
        return box;
    }

    /**
     * 按语义名建徽章（调用方明确知道该用哪个图标时）。
     * {@code fallbackGlyph} 在 {@code name} 为空时当 emoji 用。
     */
    static View iconBadgeByName(Context c, Tokens.Set t, String name, String fallbackGlyph) {
        if (name == null) name = iconNameFor(fallbackGlyph);
        // 用令牌强调色；显式 4 参版本（iconBadgeByName 的第 5 个参数）留给需要
        // 成功/警示色的状态行。
        return iconBadgeByName(c, t, name, fallbackGlyph, 0);
    }

    /**
     * emoji → 图标名。
     *
     * <p>全部委托给 {@link VectorIcon#fromEmoji}（由
     * {@code tools/build_vectoricon.py} 从原型生成，覆盖 76 个 emoji）。
     * 这里曾经另有一张手写补充表 —— 两张表容易漂移，而且生成表已经全覆盖，
     * 所以删掉了。
     *
     * <p>返回 null 表示"查不到"：调用方**不画图标、只留凹槽底片**，
     * 绝不退回彩色 emoji（原型的行首只有单色线稿）。
     */
    private static String iconNameFor(String glyph) {
        return VectorIcon.fromEmoji(glyph);
    }

    /**
     * 行首图标底片：36×36、圆角 12、凹陷面、强调色图标（原型 {@code .ico}）。
     *
     * <p>原型这里是**单色线稿**图标，颜色跟随令牌。第一版我直接放了 emoji，
     * 于是颜色不受控 —— 深色新拟态下一屏几十个彩色小方块，这是"和原型不一样"
     * 最直接的来源。
     *
     * <p>现在：emoji 能在 {@link VectorIcon#fromEmoji} 或 {@link #EXTRA_EMOJI}
     * 里查到对应图标就画矢量线稿（形状抄自原型，逐字节一致）；
     * 查不到则**不画图标、只留凹槽底片**，绝不退回彩色 emoji。
     */
    static View iconBadge(Context c, Tokens.Set t, String glyph) {
        return iconBadgeByName(c, t, iconNameFor(glyph), glyph);
    }

    /**
     * 行首图标底片（指定图标名与颜色）。
     *
     * <p>{@code color} 为 0 时用令牌的强调色。状态行需要它 ——
     * 「已授权 / 未授权」用同一种强调色就分不出好坏，
     * 但也不该退回彩色 emoji（✅ 绿框 ❌ 红叉在深色下很刺眼）。
     * 用令牌里的 success / danger 着色，既分得清又随主题走。
     */
    static View iconBadgeByName(Context c, Tokens.Set t, String name, String glyph,
                                int color) {
        int size = dp(c, 36);
        android.widget.FrameLayout box = new android.widget.FrameLayout(c);
        box.setBackground(UiKit.sunken(c, t, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = dp(c, 12);
        box.setLayoutParams(lp);

        if (name != null) {
            View ic = VectorIcon.view(c, name, color != 0 ? color : t.accent, 19);
            android.widget.FrameLayout.LayoutParams ip =
                    new android.widget.FrameLayout.LayoutParams(dp(c, 19), dp(c, 19));
            ip.gravity = Gravity.CENTER;
            box.addView(ic, ip);
        }
        return box;
    }

    /**
     * 从文案里剥掉开头那个 emoji 并返回它；没有就返回 null。
     *
     * <p>本工程所有设置项文案都以 emoji 开头（如 {@code "🔊 开启语音朗读"}）。
     * 原型是「徽章放图标 + 标题不带图标」，两边都留会图标重复。这里自动把
     * 首 emoji 挪进徽章，所以 60+ 处调用点的文案**一行都不用改**。
     *
     * <p>emoji 可能是代理对、带变体选择符（⚙️ = U+2699 U+FE0F）或 ZWJ 组合，
     * 所以按码点扫描。只吃**开头连续**的 emoji/空格，正文里的 emoji 不动
     * —— 那些是内容的一部分（"上传 GIF / 动态 WebP" 之类不能被破坏）。
     */
    static String takeLeadingEmoji(String[] inOut) {
        String s = inOut[0];
        if (s == null || s.length() == 0) return null;
        int i = 0;
        int n = s.length();
        while (i < n) {
            int cp = s.codePointAt(i);
            // 必须复用 isEmoji —— 这里原来自己写了一份"是不是 emoji"的判断，
            // 两份不一致：
            //   takeLeadingEmoji 缺 0x23F0(⏰) 和 0x1F910(🤐)，
            //   isEmoji 有它们。
            // 后果是「⏰ 整点报时…」和「🤐 戳过桌宠后多久不主动…」这两行
            // **既没摘出行首徽章、也没把 emoji 剥掉**：徽章位置空着，
            // 文字里还留着一个彩色 emoji（真机 dump 里文字左边界 89px，
            // 而正常带徽章的行是 216px）。
            // 统一成一份判断后，两份行为不可能再漂移。
            if (!isEmoji(cp)) break;
            i += Character.charCount(cp);
        }
        if (i == 0) return null;
        String glyph = s.substring(0, i);
        String rest = s.substring(i);
        while (rest.startsWith(" ")) rest = rest.substring(1);
        inOut[0] = rest;
        return glyph;
    }

    /**
     * 一行（卡片内的条目）。行自带左右内边距与最小高度，符合原型 {@code .row}。
     */
    static LinearLayout row(Context c, Tokens.Set t) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        // 行高从 56dp 加到 60dp、上下内边距 10→13dp：
        // 用户反馈「选项之间过于紧凑」，配合去掉分隔线后需要更多留白，
        // 让每一行自己成为一个可点的整块。
        r.setMinimumHeight(dp(c, 60));
        int p = dp(c, Tokens.PAD_CARD);
        r.setPadding(p, dp(c, 13), p, dp(c, 13));
        return r;
    }

    /**
     * 浮雕/玻璃阴影所需的边距（px）。
     *
     * <p>用 {@link UiKit#panelInset} 而不是新拟态那一个值：玻璃的外阴影模糊
     * 是新拟态的两倍，形状本体的边缘比它更靠内 8px。布局按新拟态留白时，
     * 玻璃下面板边缘会压到内容上 —— 这正是用户看到的
     * 「玻璃态的时候字体已经超出范围」。
     */
    static int inset(Context c) {
        return UiKit.panelInset(c);
    }

    /**
     * 卡片内**直接子元素**需要的左右内边距（px）。
     *
     * <p>返回值就是 {@code PAD_CARD}，**不能加 inset** —— 卡片的
     * {@code bareCard} 已经用 inset 做了自己的 padding，子元素的 padding
     * 是从**卡片内容盒**起算的，那个盒子已经在可见面板边缘上了。
     * 早先这里加了一次 inset，等于把浮雕余量算了两遍：新拟态下文字会被
     * 顶到距面板边 25dp，比 {@link #row} 的 16dp 多出 9dp，两套缩进对不齐。
     *
     * <p>所以判据是：**任何直接放进卡片的元素，自带 PAD_CARD 左右内边距**，
     * 这与 {@code row()} 完全一致，也正是原型
     * {@code .row{...var(--pad-card)}} / {@code .slider{padding:13px var(--pad-card)}}
     * 的做法 —— 卡片的可见边缘到文字恒为 16dp，与引擎无关。
     */
    static int rowSidePad(Context c) {
        return dp(c, Tokens.PAD_CARD);
    }

    /**
     * 行与行之间的留白（dp）。
     *
     * <p>取代原来的发丝分隔线。用户反馈「选项之间过于紧凑，请优化布局」——
     * 光去掉线还不够，行距也要给足，否则 7 行仍然贴成一片。
     */
    static final int ROW_GAP_DP = 6;

    // ==================== 按压与过渡动效 ====================

    /** 按压缩放比例（skill: HyperOS 短按 0.95~0.97） */
    private static final float PRESS_SCALE = 0.96f;
    /** 按下/回弹时长（skill: 150ms） */
    private static final int PRESS_DOWN_MS = 90;
    private static final int PRESS_UP_MS = 150;

    /**
     * 给任意可点 View 加统一的按压反馈：轻微缩小 + 回弹。
     *
     * <p>用户反馈「按钮功能页面切换生硬，需要加过渡动画」。这套 UI 是新拟态，
     * 没有涟漪（skill 明确写了 HyperOS press = scale 0.95–0.97 / 150ms /
     * <b>no ripple</b>）—— 涟漪是 Material 的语言，画在浮雕上会显得脏。
     * 所以反馈只能靠"按下去缩一点、松开弹回来"，这也符合实体按键的直觉：
     * 你按下去，它就陷一点。
     *
     * <p>为什么用 {@code onTouch} 而不是 {@code StateListDrawable}：
     * 缩放是 View 属性，不是 Drawable 的事，StateList 只能换背景。
     *
     * <p>注意必须返回 false —— 返回 true 会吃掉点击事件，
     * 让 {@code OnClickListener} 不再触发（这样"加了动画但点不动"）。
     */
    static void pressFx(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().cancel();
                        view.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                                .setDuration(PRESS_DOWN_MS)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.animate().cancel();
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(PRESS_UP_MS)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                                .start();
                        break;
                    default:
                        break;
                }
                return false;   // 不吃事件，OnClickListener 照常
            }
        });
    }

    /**
     * 原地刷新的过渡：**只淡入、不位移**。
     *
     * <p>用于"页面没换、只是内容跟着新设置重算"的重建（点开关、改下拉、
     * 换皮肤）。这时不该播 {@link #pageSlide} —— 同一个页面横滑进来
     * 会让人以为跳页了；但也不能硬切，因为列表可能整个重排，
     * 硬切会闪。淡入是最轻的过渡，刚好掩盖重排、又不产生"我到了别处"的暗示。
     *
     * <p>时长也比翻页短（160ms vs 260ms）：原地刷新是高频操作
     * （用户可能连点几个开关），过渡太慢会拖手感。
     */
    static void pageFade(View v) {
        if (v == null) return;
        v.setAlpha(0.55f);      // 不从 0 开始：高频操作下全淡入会显得闪
        v.animate()
                .alpha(1f)
                .setDuration(160)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
                .start();
    }

    /**
     * 页面入场动画：整块内容轻轻上浮 + 淡入。
     *
     * <p>用户反馈「页面切换生硬」。原来的做法是点一下、整屏瞬间替换，
     * 没有任何过渡，眼睛来不及跟上"我到了哪里"。
     *
     * <p>参数刻意做小（位移 12dp、时长 240ms）：这是**层级切换**的提示，
     * 不是表演。位移给大了会像转场特效，反而拖慢操作节奏。
     */
    static void pageEnter(View v) {
        if (v == null) return;
        final float d = v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(12f * d);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
    }

    /**
     * 页面切换的位移量（屏宽比例）。
     *
     * <h3>★ 用户要求：用平移，不要翻页</h3>
     * 原实现是"微信式整页横向翻页"—— 位移 1/3 屏宽，<b>叠加 0.94→1.0 缩放
     * 和 0→1 淡入</b>。那个缩放 + 淡入正是"翻页/卡片推入"的观感来源，
     * 用户明确要求改成**纯平移**（只横向移动，不缩不放不淡入）。
     *
     * <p>取<b>整屏宽</b>而不是原来的 1/3：去掉淡入后若只位移 1/3，
     * 新页会"凭空出现在离屏 1/3 处"再滑进来 —— 仍有翻页的突兀感。
     * 整屏宽才是标准的"从屏幕外平移进来"。
     *
     * <p>这个常量同时被 {@code MainActivity} 的预测式返回动画引用
     * （跟随手势的终点必须等于转场的起点，否则松手瞬间画面会跳）。
     */
    static final float PAGE_SLIDE_FRACTION = 1f;

    /**
     * 整页**平移**转场：新页从一侧平移进来。
     *
     * <p>方向约定：
     * <ul>
     *   <li>{@code forward = true}：进入二级页 —— 从<b>右侧</b>平移进来</li>
     *   <li>{@code forward = false}：返回上级 —— 从<b>左侧</b>平移进来</li>
     * </ul>
     * 底部导航切板块时用 {@code forward}（同级之间也走一次平移，
     * 与点击进二级页同一套手感）。
     *
     * <p>整块内容一起动（含底部导航栏），所以导航栏不会"跳"。
     *
     * <p><b>为什么不做"旧页滑出 + 新页滑入"两页同时动：</b>
     * 那需要同时持有两个页面的视图树（这里每屏都是现场重建的
     * {@code LinearLayout}），改造成本远超收益。
     */
    static void pageSlide(View v, boolean forward) {
        if (v == null) return;
        float screenW = v.getResources().getDisplayMetrics().widthPixels;
        // 从屏幕外侧平移进来；符号决定来自哪一侧
        float from = (forward ? 1f : -1f) * screenW * PAGE_SLIDE_FRACTION;
        // ★ 纯平移：只动 translationX，**不缩放、不淡入**。
        //   原来的 0.94→1 缩放 + 0→1 淡入就是"翻页/卡片推入"的观感来源，
        //   用户要求「动画采用平移不用翻页了用平移」，所以去掉那两者。
        //   同时把 scale/alpha 显式复位 —— 旧代码留下的值不复位的话，
        //   新页会一直是缩小/半透明的。
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
        v.setTranslationX(from);
        // ★ 打点：证明"确实是纯平移"（在**赋值之后**打，记录的就是实际生效值）。
        //   为什么用日志而不是截图：`input tap` + `screencap` 的往返延迟
        //   远超 260ms 的动画时长，截图根本抓不到动画中间帧
        //   （实测抓到的两帧面积比 0.991、位移 1.0px —— 完全静止，
        //   那种"通过"是空转的）。
        //   日志给出**确定**的事实：位移多少、缩放/透明度是否恒为 1。
        android.util.Log.i("DSPAnim", "pageSlide forward=" + forward
                + " from=" + from
                + " scale=" + v.getScaleX() + "/" + v.getScaleY()
                + " alpha=" + v.getAlpha() + " screenW=" + screenW);
        v.animate()
                .translationX(0f)
                .setDuration(260)
                // Decelerate 让"起步快、收尾慢"；线性插值会显得生硬。
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                .start();
        // 关闭子视图裁剪，否则页面在横向位移时会被父容器切掉两侧
        // （表现为"滑到一半突然出现一条竖边"）。
        if (v.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) v.getParent()).setClipChildren(false);
            ((android.view.ViewGroup) v.getParent()).setClipToPadding(false);
        }
    }

    /** 页头：大标题 + 副标题 */
    static LinearLayout header(Context c, Tokens.Set t, String title, String sub) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 20);
        box.setPadding(p, dp(c, 16), p, dp(c, 8));

        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextSize(26);
        tv.setTextColor(t.text1);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        box.addView(tv);

        if (sub != null && sub.length() > 0) {
            TextView sv = new TextView(c);
            sv.setText(sub);
            sv.setTextSize(14);
            sv.setTextColor(t.text2);
            sv.setPadding(0, dp(c, 4), 0, 0);
            box.addView(sv);
        }
        return box;
    }

    /**
     * 分组小标题。原型的 {@code .sec} 是 <b>11.5px / 600 / 字距 .06em / text-3</b>，
     * 在卡片**外面**、左对齐、不显眼。
     *
     * <p>第一版我做成了 17sp 加粗强调色 —— 那是"卡片标题"的视觉重量，
     * 于是分组标题比组内的行还抢眼，整页看起来全是标题。
     * 原型的做法相反：标题退到背景里，让行成为主体。
     */
    static TextView sectionLabel(Context c, Tokens.Set t, String text) {
        TextView tv = new TextView(c);
        tv.setText(stripEmoji(text));
        tv.setTextSize(12);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(t.text3);
        tv.setLetterSpacing(0.06f);
        tv.setPadding(dp(c, 4), dp(c, 14), 0, dp(c, 6));
        return tv;
    }

    /**
     * 一个「分组块」：小标题（卡外）+ 一张卡片。
     *
     * <p><b>这是与原型对齐的关键结构。</b>原型里一个分组 = 一张卡片，
     * 卡片内部的多个 {@code .row} 之间用内阴影细线分隔。第一版我做成了
     * 「每行各自一张浮动卡片」，一屏 8 行就是 8 个各带光晕的胶囊 ——
     * 用户反馈"像一堆气泡"，和原型完全不像。
     *
     * <p>skill 里也明确要求：{@code Section 不要做卡片套卡片，组内条目用轻分隔}。
     *
     * <p>行通过 {@link #addRow} 加入，自动插入分隔线；分隔线不画在最后一行之后。
     */
    static final class Group {
        final LinearLayout card;
        private final Context ctx;
        private final Tokens.Set t;
        private boolean first = true;

        Group(Context c, Tokens.Set tokens) {
            this.ctx = c;
            this.t = tokens;
            this.card = bareCard(c, tokens);
        }

        /** 加一行。
         *
         * <p><b>不再自动插分隔线。</b>用户明确要求「每个选项之间都有这种横线
         * 把它去掉」。原来的实现是首行之外每行上方画一条 hairline，
         * 一屏 7 行就是 7 条线 —— 在浮雕语言里这些线既不是阴影也不是边框，
         * 显得很脏（截图圈出来的就是它们）。
         *
         * <p>行与行的区分改由**行自身的凹陷按压态 + 行间距**承担：
         * 卡片本身与页面同色，行与行之间靠留白呼吸，这也更接近原型
         * 「卡片是一整块材质」的意图。
         */
        void addRow(View v) {
            // 裸 TextView（"大小：0.93×"那类滑块值标签）直接放进卡片时
            // **没有任何左右内边距** —— {@link #row} 自带 PAD_CARD，
            // 而这些标签是调用点各自 {@code new TextView} + {@code setPadding(0,…)}
            // 造的。结果文字贴在卡片可见边缘上；玻璃态下面板还有一道描边，
            // 看起来就是"字超出了框"（用户截图红框圈的就是它）。
            //
            // 在这里补，而不是去改 17 处调用点：本类存在的意义就是
            // "在容器层面拦截"（见 SettingsPage 的注释），逐个补既容易漏，
            // 以后新增标签还会再犯。只处理**左右都为 0** 的 TextView ——
            // 已经自带边距的（row、label、hint）不动，否则会缩进两次。
            if (v instanceof TextView
                    && v.getPaddingLeft() == 0 && v.getPaddingRight() == 0) {
                int side = rowSidePad(ctx);
                v.setPadding(side, v.getPaddingTop(), side, v.getPaddingBottom());
            }
            if (!first) {
                // 行间距（不是分隔线）：让相邻两行不贴在一起
                View gap = new View(ctx);
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, ROW_GAP_DP));
                card.addView(gap, glp);
            }
            first = false;
            card.addView(v, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    /** 开一个分组卡片（不负责往页面里加，由调用方决定放置方式） */
    static Group group(Context c, Tokens.Set t, LinearLayout page, String title) {
        if (title != null && title.length() > 0) {
            page.addView(sectionLabel(c, t, title));
        }
        return new Group(c, t);
    }

    // ==================== 底部导航栏 ====================

    /**
     * 一个底部导航项的定义。
     *
     * <p>用静态数组而不是 enum，是为了让调用方（MainActivity）能把
     * 图标名和文案就近放在一起写，改文案不用改两处。
     */
    static final class NavItem {
        final String icon;
        final String label;
        NavItem(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    /**
     * 底部导航栏。
     *
     * <p>用户要求「整体布局太过繁杂，功能分开做底部导航栏，氛围三个板块」。
     * 这里用原型 {@code .seg} 的做法：<b>外层是一条凹陷的胶囊槽，
     * 选中项凸起</b> —— 这既是新拟态最标准的"分段控件"写法，
     * 也让"当前在哪一栏"靠浮雕方向就能读出来，不需要颜色块。
     *
     * <p>为什么不做成平台 BottomNavigationView：工程是纯 framework、
     * 零 androidx，没有 Material 依赖；而且平台的底栏样式（实心色条 + 图标着色）
     * 和新拟态"同色材质、只靠阴影分形"的规则直接冲突。
     *
     * <p>选中项用强调色图标 + 强调色文字，未选中用 {@code text2} ——
     * 只靠阴影区分在深色下不够清楚，补一层颜色对比更稳妥
     * （对比度已按 4.5:1 校验）。
     *
     * @param current 当前选中的下标
     * @param onPick  点击回调，参数是被点的下标
     */
    static View bottomNav(final Context c, final Tokens.Set t, NavItem[] items,
                          int current, final OnNavPick onPick) {
        return new NavBar(c, t, items, current, onPick);
    }

    /**
     * 带"从哪一格滑过来"的导航栏。
     *
     * <p>用于**整页重建**的场景：切换板块时页面内容确实要重建，
     * 连导航栏一起换新，新滑块自然就出现在目标位置 —— 看不出滑动。
     * 传入 {@code fromIndex} 可以让新滑块先摆在旧位置，
     * 首次布局后再滑到目标格，视觉上就是连续滑动。
     *
     * @param fromIndex 起始格（一般传上一次的选中格）；&lt;0 或等于 current 时不播动画
     */
    static View bottomNav(Context c, Tokens.Set t, NavItem[] items, int current,
                          int fromIndex, final OnNavPick onPick) {
        return new NavBar(c, t, items, current, fromIndex, onPick);
    }

    /**
     * 底部导航栏。
     *
     * <p><b>为什么是自定义 ViewGroup：</b>用户要求「底部导航栏的切换加入滑块效果」。
     * 用 {@code LinearLayout} 逐格换背景做不到"滑块"—— 那只是选中格突然出现
     * 一块凸起，没有连续位移。这里在格子**底下**放一个独立的凸起指示器
     * （{@code indicator}），切换时用 {@code translationX} 把它平滑滑到目标格，
     * 视觉上就是"一块浮雕滑块在凹槽里滑过去"。
     *
     * <p>指示器必须在格子<b>下面</b>（先 addView），否则会盖住图标文字。
     * FrameLayout 天然按添加顺序叠放，所以顺序就是：指示器 → 格子行。
     */
    static final class NavBar extends android.widget.FrameLayout {
        private final View indicator;
        private final LinearLayout row;
        private final int n;
        private final Context c;
        /** 令牌要在 select() 里重新着色图标/文字，所以必须存成字段 */
        private final Tokens.Set t;
        private int current;
        /** 已测量到的单格宽度；0 表示还没 layout 过 */
        private int cellW;
        private int padPx;
        private boolean laidOut;
        /**
         * 入场滑动：首次 onLayout 时滑块先摆在这一格，然后滑到 {@link #current}。
         *
         * <p>为什么需要它：切板块会整页重建（内容真的换了），导航栏也是新的，
         * 新滑块自然就"长"在目标位置 —— 用户看不到滑动，等于没加动画。
         * 记下上一次的格子，先摆过去再滑过来，才有连续的位移感。
         * -1 表示不需要入场滑动。
         */
        private int pendingFrom;
        /** 点击／拖动换板块都要用它，所以必须存下来（原来只传给了格子） */
        private final OnNavPick pick;
        /** 拖动导航栏：按下点与"是否已从格子手里抢过事件" */
        private float navDownX;
        private boolean navStolen;
        private int navSlop = -1;

        NavBar(Context c, Tokens.Set t, NavItem[] items, int current, OnNavPick onPick) {
            this(c, t, items, current, -1, onPick);
        }

        /**
         * @param fromIndex 首次布局时滑块从哪一格开始滑（&lt;0 表示不做入场滑动）
         */
        NavBar(Context c, Tokens.Set t, NavItem[] items, int current,
               int fromIndex, OnNavPick onPick) {
            super(c);
            this.c = c;
            this.t = t;
            this.n = items.length;
            this.current = current;
            this.pendingFrom = (fromIndex >= 0 && fromIndex != current) ? fromIndex : -1;
            this.pick = onPick;
            this.padPx = dp(c, 6);

            // ⓪ 外层凹槽 —— **画在 NavBar 自己的背景上**，不画在 row 上。
            //
            // 这是一个真实的层级 bug：凹槽原来挂在 row 上，而 row 是在
            // indicator **之后** addView 的 —— FrameLayout 按加入顺序绘制，
            // 于是凹槽盖住了滑块。后果分引擎体现：
            //   * 新拟态：凹槽是不透明的 `surface`，滑块被**完全盖住**，
            //     选中项只剩文字变蓝，"滑块"根本看不见；
            //   * 玻璃：凹槽是 .70 白的半透明面，滑块被压暗一层，
            //     看着又扁又淡 —— 用户说的"滑块上下宽度太窄"就是它。
            // 这里只设背景、**不设 padding**：原注释担心的是"给 this 加
            // padding 会把 indicator 和 row 一起挤窄"，而背景不参与内边距
            // 计算，row 自己的 padPx 仍是唯一的水平留白来源。
            setBackground(UiKit.sunken(c, t, Tokens.R_PILL));

            // ① 滑块指示器（凸起胶囊），处于凹槽之上、格子之下
            indicator = new View(c);
            indicator.setBackground(UiKit.card(c, t, Tokens.R_PILL));
            // 无障碍描述是有**实际作用**的：读屏用户需要知道"当前在哪一栏"，
            // 而这层滑块本身没有文字，不讲就是纯装饰。
            // 附带好处：它给了 uiautomator 一个可测量的锚点，能直接读出
            // 滑块的实时 bounds（含 translationX），验证"是否真的在滑动"
            // 而不必在截图里猜像素 —— 用强调色和阴影各猜错一次之后才改用这个。
            indicator.setContentDescription(items[current].label + "（当前板块）");
            addView(indicator);

            // ② 格子行，铺在指示器之上
            row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(padPx, padPx, padPx, padPx);
            for (int i = 0; i < n; i++) {
                row.addView(makeCell(c, t, items[i], i, i == current, onPick),
                        new LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            // row 必须 WRAP_CONTENT：它的高度是"内容的自然高度"。
            //
            // 之前用 MATCH_PARENT，配合 this（FrameLayout）没有自身高度约束，
            // 结果 row 一路撑满整屏 —— 导航栏把上面的滚动区挤成 0 高，
            // 整个设置页只剩三个 tab 文字（截图确认的严重布局事故）。
            addView(row, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));

            // 外层凹槽已经移到 NavBar 自身的背景（见构造函数开头）——
            // 挂在 row 上会被"后加入者先绘制"的规则盖住滑块。
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            // 自身高度 = row 的高度（不理会父级给的 MATCH_PARENT）。
            //
            // 这是修上面那个"导航栏撑满整屏"的关键：父级 root 里这一项是
            // WRAP_CONTENT，但如果这里调 super.onMeasure 并把 hSpec 是
            // AT_MOST 的情况交给 FrameLayout，FrameLayout 会按**最大可用高度**
            // 去量，于是又变高。所以显式量 row，再把自己定成 row 的高度。
            measureChild(row, wSpec,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int h = row.getMeasuredHeight();
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, h);
        }

        private View makeCell(final Context c, final Tokens.Set t, final NavItem it,
                              final int idx, final boolean on, final OnNavPick onPick) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(c, 4), dp(c, 7), dp(c, 4), dp(c, 7));
            // 格子本身**不画背景** —— 凸起由底层滑块提供，
            // 这样切换时看到的是一块连续的滑块，而不是两格各闪一下。
            cell.setBackground(null);

            final View ic = VectorIcon.view(c, it.icon, on ? t.accent : t.text2, 20);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ilp.bottomMargin = dp(c, 3);
            cell.addView(ic, ilp);

            final TextView lb = new TextView(c);
            lb.setText(it.label);
            lb.setTextSize(11.5f);
            lb.setGravity(Gravity.CENTER);
            lb.setTextColor(on ? t.accent : t.text2);
            if (on) lb.setTypeface(lb.getTypeface(), android.graphics.Typeface.BOLD);
            cell.addView(lb);

            // 图标与文字的颜色/字重也要跟着滑块一起过渡，
            // 否则滑块滑过去了、颜色却是瞬间跳变，看着很割裂。
            cell.setTag(new Object[]{ic, lb, it});
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // 同格重复点击不再触发重建（避免无谓的整页刷新闪烁）
                    if (idx == NavBar.this.current) return;
                    select(idx, true);
                    if (onPick != null) onPick.onPick(idx);
                }
            });
            if (on) setTag(new Object[]{ic, lb, it});
            return cell;
        }

        /** 高亮第 idx 格；{@code animate} 为真时滑块平移过去 */
        void select(int idx, boolean animate) {
            if (idx < 0 || idx >= n) return;
            current = idx;
            // 逐格刷新图标/文字的颜色与字重
            for (int i = 0; i < n; i++) {
                View cell = row.getChildAt(i);
                Object tag = cell.getTag();
                if (!(tag instanceof Object[])) continue;
                Object[] parts = (Object[]) tag;
                View ic = (View) parts[0];
                TextView lb = (TextView) parts[1];
                boolean on = (i == idx);
                // VectorIcon.IconView 的颜色是 final，只能换 View；
                // 这里用重建代替着色（图标很小，代价可接受）
                if (ic instanceof android.view.ViewGroup
                        || ic.getClass().getSimpleName().equals("IconView")) {
                    LinearLayout parent = (LinearLayout) cell;
                    View nv = VectorIcon.view(c, ((NavItem) parts[2]).icon,
                            on ? t.accent : t.text2, 20);
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) ic.getLayoutParams();
                    int at = parent.indexOfChild(ic);
                    parent.removeViewAt(at);
                    parent.addView(nv, at, lp);
                    parts[0] = nv;
                }
                lb.setTextColor(on ? t.accent : t.text2);
                lb.setTypeface(lb.getTypeface(),
                        on ? android.graphics.Typeface.BOLD
                           : android.graphics.Typeface.NORMAL);
            }
            moveIndicator(animate);
        }

        @Override protected void onLayout(boolean changed, int l, int tt, int r, int b) {
            super.onLayout(changed, l, tt, r, b);
            int w = getWidth();
            int avail = w - padPx * 2;
            if (avail <= 0 || n == 0) return;
            int nw = avail / n;
            boolean first = !laidOut || nw != cellW;
            cellW = nw;
            laidOut = true;
            moveIndicator(!first);
        }

        /**
         * 入场滑动是否正在进行。
         *
         * <p>用它挡住后续的布局回调。为什么不用"预计结束时间"：动画时长会被
         * 系统的 {@code animator_duration_scale} 放大（调试时可调到 10 倍），
         * Java 侧算出来的时间戳对不上真实时长 —— 实测闸门 320ms、
         * 实际动画 2600ms，于是闸门一过就被 {@code setTranslationX} 瞬移覆盖，
         * 中间帧一个都采不到。改成由动画自己的结束回调来清除标志，与时长无关。
         */
        private boolean introRunning;
        /** 入场动画的本次目标，避免重复启动 */
        private int introTarget = -1;

        private void moveIndicator(boolean animate) {
            if (cellW <= 0) return;
            // **竖向铺满整个凹槽，不是凹槽减去内边距。**
            //
            // 用户反馈「底部导航栏滑块上下宽度太窄」。原来这里用
            // `getHeight() - padPx*2`（跟着 row 的 6dp 内边距一起缩），
            // 再叠加指示器自身的内缩余量（新拟态 6dp / 玻璃 9dp），
            // 可见胶囊只剩凹槽高度的 **六成**，上下各空出一大块。
            //
            // 原型的分段控件是「凹槽 padding:6px，选中段铺满内区」，
            // 也就是说竖向只该让出**一次** 6px。这里把 height 放到整条
            // 凹槽的完整高度：Drawable 自己的内缩正好充当那一次让白，
            // 可见胶囊的高度＝凹槽高 - 2×内缩（新拟态约 6dp、玻璃约 9dp），
            // 也就是上下各留一条窄边。fillHeight 即为此。
            int h = getHeight();
            if (h <= 0) h = row.getMeasuredHeight();
            // 指示器与格子等宽等高，只在横向上平移
            indicator.layout(padPx, 0, padPx + cellW, h);

            if (pendingFrom >= 0 && cellW > 0) {
                // 入场滑动：先落位到旧格，下一帧再滑到目标格。
                // post() 保证"先摆好、再动画"，否则动画起点会被同帧的
                // setTranslationX 覆盖掉，看起来还是瞬移。
                final float from = pendingFrom * (float) cellW;
                final float to = current * (float) cellW;
                pendingFrom = -1;
                if (introRunning && introTarget == current) return;
                introRunning = true;
                introTarget = current;
                indicator.animate().cancel();
                indicator.setTranslationX(from);
                post(new Runnable() {
                    @Override public void run() {
                        indicator.animate().cancel();
                        indicator.animate()
                                .translationX(to)
                                .setDuration(260)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                                .withEndAction(new Runnable() {
                                    @Override public void run() {
                                        introRunning = false;
                                        introTarget = -1;
                                    }
                                })
                                .start();
                    }
                });
                return;
            }

            // 入场滑动进行中：忽略这次布局回调，别去改 translationX
            if (introRunning) return;

            float target = current * (float) cellW;
            indicator.animate().cancel();
            if (animate) {
                indicator.animate()
                        .translationX(target)
                        .setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                        .start();
            } else {
                indicator.setTranslationX(target);
            }
        }

        // 供外部查询当前选中项
        int selectedIndex() { return current; }

        /**
         * 拖动过程中让滑块**跟手**（用户要求「手动跟随滑动效果」）。
         *
         * <p>{@code dxPx} 是相对本次拖动起点的累计水平位移。滑块位置
         * ＝ 当前格的基准位置 + 位移，并夹在首格与末格之间 ——
         * 越过边界时滑块就停在边缘，不会拖出导航栏。
         */
        void dragFollow(float dxPx) {
            if (cellW <= 0) return;
            float base = current * (float) cellW;
            float max = (n - 1) * (float) cellW;
            float t = base + dxPx;
            if (t < 0f) t = 0f;
            if (t > max) t = max;
            indicator.animate().cancel();
            indicator.setTranslationX(t);
        }

        /**
         * 松手结算：位移过半就换板块。
         *
         * <p>返回目标下标；返回 -1 表示没换（滑块回弹到原位）。
         * 只在这里改 {@code current} 并把滑块动画过去，**不**负责重建页面 ——
         * 换页由调用方走与点击导航栏同一条路径（{@code switchTab}），
         * 这样"点"和"滑"的心智模型与转场动画完全一致。
         *
         * <p><b>只给"划页面"用</b>：划页面时滑块全程不动（用户要求
         * 「滑动页面的时候 滑块不要动」），松手才按这个判定切一页。
         * 直接拖导航栏走 {@link #onTouchEvent} 里的"跟手＋吸附"逻辑。
         */
        int dragRelease(float dxPx) {
            if (cellW <= 0) return -1;
            float half = cellW / 2f;
            int target = current;
            if (dxPx <= -half && current < n - 1) target = current + 1;
            else if (dxPx >= half && current > 0) target = current - 1;
            if (target == current) return -1;
            select(target, true);
            return target;
        }

        /** 拖动后落点最近的格子（"滑到哪切换到哪"） */
        private int nearestCell(float dxPx) {
            if (cellW <= 0) return current;
            float pos = current * (float) cellW + dxPx;
            int idx = Math.round(pos / cellW);
            if (idx < 0) idx = 0;
            if (idx > n - 1) idx = n - 1;
            return idx;
        }

        /**
         * 直接拖导航栏：滑块跟手，松手吸附到最近的格子并切过去。
         *
         * <p>用户要求「底部导航栏滑块做滑到哪切换到哪」。这条路径与
         * "划页面"是分开的：只有手指按在导航栏上拖动，滑块才跟手；
         * 划页面时滑块必须静止。
         *
         * <p>截获时机：横向位移超过 touchSlop 才从格子手里抢事件 ——
         * 没超过就是一个普通点击，让格子自己处理。
         */
        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            if (navSlop < 0) {
                navSlop = android.view.ViewConfiguration.get(getContext())
                        .getScaledTouchSlop();
            }
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    navDownX = ev.getX();
                    navStolen = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!navStolen && Math.abs(ev.getX() - navDownX) > navSlop) {
                        navStolen = true;
                        return true;
                    }
                    break;
                default:
                    navStolen = false;
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
            if (!navStolen) return super.onTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_MOVE:
                    dragFollow(ev.getX() - navDownX);
                    return true;
                case android.view.MotionEvent.ACTION_UP: {
                    float dx = ev.getX() - navDownX;
                    navStolen = false;
                    dragFollow(dx);
                    int target = nearestCell(dx);
                    if (target == current) {
                        moveIndicator(true);      // 又拖回原格：吸附回去
                        return true;
                    }
                    select(target, true);
                    if (pick != null) pick.onPick(target);
                    return true;
                }
                case android.view.MotionEvent.ACTION_CANCEL:
                    navStolen = false;
                    moveIndicator(true);
                    return true;
                default:
                    return true;
            }
        }
    }

    /** 底部导航的点击回调 */
    interface OnNavPick {
        void onPick(int index);
    }

    // ==================== 控件 ====================

    /**
     * 胶囊按钮（返回键、分段控件用）。
     *
     * <p><b>注意：设置页里的动作项不要用这个</b>，用 {@link #actionRow}。
     * 一屏十几个浮雕胶囊就是"气泡墙"（第一版的错误）。
     */
    static Button button(Context c, Tokens.Set t, String text, boolean primary) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        int ins = inset(c);
        b.setPadding(dp(c, 15) + ins, dp(c, 10) + ins, dp(c, 15) + ins, dp(c, 10) + ins);
        b.setTextColor(primary ? t.accent : t.text1);
        b.setBackground(pressable(c, t, Tokens.R_PILL, primary));
        // 按压缩放：与行、格一致。Button 也走同一套反馈，
        // 否则同一个页面里"行会缩、按钮不会缩"，手感不统一。
        pressFx(b);
        return b;
    }

    /**
     * 动作行：图标徽章 + 文字 + 右箭头（原型的 {@code button.row}）。
     *
     * <p>常态扁平、只在按下时凹陷 —— 它是卡片**里面的一行**，
     * 不是独立的浮动按钮。返回 {@code LinearLayout} 而不是 {@code Button}，
     * 因为需要放徽章子视图，Button 装不了。
     */
    static LinearLayout actionRow(Context c, Tokens.Set t, String text,
                                  final View.OnClickListener l) {
        String[] box = new String[]{ text };
        String glyph = takeLeadingEmoji(box);
        // 首 emoji 进徽章，**剩下的 emoji 一并剥掉**。
        //
        // 有些文案的 emoji 不在开头（"整点报时…" 里带 ⏰、"久留阈值…" 里带 ⏳），
        // 只剥首字符会漏下它们，页面上就会冒出彩色 emoji（截图确认）。
        String label = stripEmoji(box[0]);

        LinearLayout row = row(c, t);
        if (glyph != null) row.addView(iconBadge(c, t, glyph));

        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(t.text1);
        // 长标题（如「启动 / 退出问候语（伸懒腰、道别、接上次话题）」）会换行，
        // 行距给一点避免两行贴死；右侧 12dp 把它与箭头分开。
        tv.setLineSpacing(0, 1.18f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        alp.rightMargin = dp(c, 12);
        row.addView(tv, alp);
        // ★ 与 toggleRow 一样把标题存进 TAG_LABEL：调用方要能**就地改这一行**。
        //   P5 的「形象来源：X（当前角色）」就是靠它刷新的 —— 上传完图片若只
        //   updatePreview() 而不刷这一行，用户会看到"传了图，那一行还写着内置"
        //   （设备实测抓到），而整页重建又会变成"点一下像重新加载"。
        row.setTag(TAG_LABEL, tv);

        // 右侧 chevron（原型 .row .chev）
        TextView chev = new TextView(c);
        chev.setText("\u203A");
        chev.setTextSize(20);
        chev.setTextColor(t.text3);
        row.addView(chev);

        row.setBackground(rowPressable(c, t, Tokens.R_CARD));
        if (l != null) {
            row.setOnClickListener(l);
            row.setClickable(true);
            row.setFocusable(true);
            pressFx(row);   // 按压时整行轻微下沉
        }
        return row;
    }

    /** 分组入口（首页那种大行）：更高、更显眼 */
    static Button hubButton(Context c, Tokens.Set t, String text) {
        Button b = button(c, t, text, false);
        b.setTextSize(17);
        b.setMinHeight(dp(c, 64));
        b.setMinimumHeight(dp(c, 64));
        return b;
    }

    /**
     * 去掉字符串里的 emoji 与变体选择符。
     *
     * <p>原型的正文/说明/标题**全部是纯文字**，一个 emoji 都没有 ——
     * 图标只出现在行首的 36×36 徽章里。留下 emoji 会让页面重新变成
     * "彩色贴纸墙"，这是"看起来和原型不一样"的最后一块来源。
     *
     * <p>而且有些 emoji 在本机渲染成豆腐块（{@code 🔍 表情包大小} 显示为一个空框），
     * 所以剥掉比留着更安全。
     *
     * <p>为什么在**显示层**剥，而不是改 60+ 处字符串字面量：
     * 改字面量既容易漏、又会让日志和 Toast 里的文案跟着变；
     * 在 {@link #label}/{@link #hint}/{@link #sectionLabel} 出口统一过滤，
     * 覆盖面是 100%，调用点一行都不用动。
     */
    static String stripEmoji(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            if (!isEmoji(cp)) b.appendCodePoint(cp);
            i += n;
        }
        // 去掉剥完残留的前后空白（"🔍 表情包大小" -> "表情包大小"）
        return b.toString().trim();
    }

    private static boolean isEmoji(int cp) {
        // 只收真正的 emoji 区块，**不要**把箭头/技术符号整片包进来。
        //
        // 第一版把 U+2190–U+21FF（箭头）和 U+2300–U+23FF 整段当成 emoji，
        // 结果「设置 → 应用 → 权限」这类**导航指引**里的 → 会被删掉，
        // 文案直接变成「设置  应用  权限」（tools/check_strip_emoji.py 抓到）。
        // 箭头在这些提示里是有意义的内容，不是装饰。
        //
        // ★(U+2605) 和 ✓(U+2713) 同理保留：它们在权限页表示"关键项"和"已完成"，
        // 是符号语言的一部分，不是彩色贴纸。
        if (cp == 0x2605 || cp == 0x2606) return false;   // ★ ☆
        if (cp == 0x2713 || cp == 0x2714) return false;   // ✓ ✔
        if (cp == 0x2192 || cp == 0x2190) return false;   // → ←
        if (cp == 0x2300 || cp == 0x2325) return false;   // ⌀ ⌥
        if (cp == 0x25B8) return false;                   // ▸ 小三角（用量页分组前缀）
        // ℹ️ / ⏹️ 是**行首 emoji**（「ℹ️ 关于」「⏹️ 退出桌宠」），必须剥离并进徽章。
        //
        // 这两个原来只写在 takeLeadingEmoji 自己的清单里，统一到本方法时漏掉了，
        // 结果它们既没进徽章也没被剥掉，直接在界面上渲染成彩色方块
        // （子页面截图里的橙色方块就是 ⏹️）。
        // tools/cross_check_emoji.py 从 fromEmoji 反查才抓出来 —— 用
        // isEmoji 自己写检查是查不到的，因为检查器与被检代码共享同一个错误假设。
        if (cp == 0x2139) return true;                    // ℹ
        if (cp == 0x23F9) return true;                    // ⏹
        return (cp >= 0x1F000 && cp <= 0x1FAFF)   // 各类图形符号（emoji 主体）
            || (cp >= 0x1F1E6 && cp <= 0x1F1FF)   // 区域指示符
            || (cp >= 0x2600 && cp <= 0x26FF)     // 杂项符号（☀☂⚠⏰ 等）
            || (cp >= 0x2700 && cp <= 0x27BF)     // 装饰符（✂➡ 等）
            || (cp >= 0x2B00 && cp <= 0x2BFF)     // 杂项箭头（⭐⬆ 等）
            || cp == 0x23F3 || cp == 0x23F1 || cp == 0x23F0   // ⏳ ⏱ ⏰
            || cp == 0x25B6 || cp == 0x25C0       // ▶ ◀（试听 / 启动），进徽章
            || cp == 0xFE0F || cp == 0x200D;      // 变体选择符 / 零宽连接
    }

    /**
     * 标签行（滑块等的说明 / 副标题）。
     *
     * <p>左右内边距必须给足：原来只给了 6dp，而卡片内容区的左右边界是
     * {@code PAD_CARD(16dp) + inset}。直接用 {@code box.addView(label)}
     * 加进去时文字会紧贴卡片边、长句看起来"溢出到框外"（截图确认）。
     * 这里补到 {@code PAD_CARD + 6}，与 {@link #row} 的文字左边界对齐 ——
     * 卡片内所有文字都从同一条竖线开始，长句自动换行也不会越界。
     */
    static TextView label(Context c, Tokens.Set t, String text) {
        TextView tv = new TextView(c);
        tv.setText(stripEmoji(text));
        tv.setTextSize(15);
        tv.setTextColor(t.text2);
        int side = rowSidePad(c);
        tv.setPadding(side, dp(c, 12), side, dp(c, 4));
        // 长句必须能换行；不设 maxLines，让 TextView 自己撑高
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    /** 最次级说明（小字、更淡）——内边距与 {@link #label} 对齐 */
    static TextView hint(Context c, Tokens.Set t, String text) {
        TextView tv = new TextView(c);
        tv.setText(stripEmoji(text));
        tv.setTextSize(13);
        tv.setTextColor(t.text3);
        int side = rowSidePad(c);
        tv.setPadding(side, dp(c, 4), side, dp(c, 4));
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    /**
     * 开关行。
     *
     * <p>用 {@link Switch} 并强制整行最小 48dp 高、整行可点 ——
     * 改造前用 CheckBox，触点只有 50.9×27.4dp，是评审报告里明确的问题。
     * 现在点文字也能切换（整行 OnClickListener 转发），实测可达性达标。
     */
    static View toggleRow(Context c, Tokens.Set t, String text, boolean checked,
                          final CompoundButton.OnCheckedChangeListener l) {
        // 把文案开头的 emoji 挪进行首徽章（原型是「徽章 + 纯文字标题」），
        // 其余位置的 emoji 一并剥掉（见 actionRow 的说明）。
        String[] box = new String[]{ text };
        String glyph = takeLeadingEmoji(box);
        String label = stripEmoji(box[0]);

        LinearLayout row = row(c, t);
        if (glyph != null) row.addView(iconBadge(c, t, glyph));

        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(t.text1);
        // 标题与开关之间要留出**明确的间距**。
        //
        // 用户反馈「字体太近」：原来文字区是 weight=1 直接顶到开关左边，
        // 长标题（如「启动 / 退出问候语（伸懒腰、道别、接上次话题）」）
        // 换行后末行会紧贴开关，看着像挤在一起。
        // 这里给文字区右侧留 14dp —— 它同时是"文字区"和"控件区"的分界，
        // 无论标题多长都保证有一段空白。
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = dp(c, 14);
        tv.setLineSpacing(0, 1.18f);
        row.addView(tv, tlp);

        // 自绘开关（原型 .sw）：凹陷轨道 + 凸起滑块
        row.addView(switchView(c, t, checked, l));

        final View target = row;
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 整行可点：把点击转发给行内的开关，否则用户点文字没反应。
                //
                // ★ 必须**递归**找那个开关。开关外面还包了一层 FrameLayout
                // （{@link #switchView} 末尾的 wrap，用来避免被 LinearLayout
                // 拉伸成整行宽），而 OnClickListener 挂在内层的 box 上。
                // 这里原来只扫一层直接子 View，扫到的是外层 wrap ——
                // wrap 上没有监听器，performClick() 什么都不做，
                // 于是"点文字也能切换"**一直是坏的**。
                // 实测：点行内文字和点行首徽章都不切换，只有点开关本体才行。
                View sw = firstClickableChild(row);
                if (sw != null) sw.performClick();
            }
        });
        row.setClickable(true);
        pressFx(row);

        // 把标题 TextView 挂到行上，供调用方**就地改文案**。
        //
        // 为什么需要：有些开关的标题里带了状态后缀（如
        // 「🔒 锁定当前菜单参数…（未锁定）」）。原先的做法是切换后
        // showSettings() 整页重建 —— 而整页重建会重建滚动位置、
        // 重建所有子控件，视觉上就是用户说的「点一下开关页面又重新加载」。
        // 状态后缀是**行内**的事，只改这一个 TextView 就够了。
        row.setTag(TAG_LABEL, tv);
        return row;
    }

    /**
     * 深度优先找出第一个**可点的子 View**（不含 {@code v} 自己）。
     *
     * <p>给"整行可点、把点击转发给行内控件"用（见 {@link #toggleRow}）。
     * 必须递归：目标控件可能被包在若干层装饰性容器里。
     * 倒序遍历是为了先碰到右侧的控件区（开关在行尾）。
     */
    private static View firstClickableChild(android.view.ViewGroup v) {
        for (int i = v.getChildCount() - 1; i >= 0; i--) {
            View ch = v.getChildAt(i);
            if (ch.getVisibility() != View.VISIBLE) continue;
            if (ch.isClickable()) return ch;
            if (ch instanceof android.view.ViewGroup) {
                View r = firstClickableChild((android.view.ViewGroup) ch);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** {@code getTag} 用的键：行的标题 TextView（见 {@link #toggleRow}） */
    static final int TAG_LABEL = 0x7f0f0001;

    /**
     * 就地改一行的标题文案（不重建页面）。
     *
     * <p>与 {@link #toggleRow} / {@link #actionRow} 配套：两者都把标题 TextView
     * 存进了 {@link #TAG_LABEL}。找不到就静默返回 —— 调用方不该为了刷新一行文案
     * 而被迫整页重建（那正是"点开关像重新加载"的来源）。
     */
    static void setRowLabel(View row, String text) {
        if (row == null || text == null) return;
        Object o = row.getTag(TAG_LABEL);
        if (!(o instanceof TextView)) return;
        // 必须按 toggleRow 建标题时**同样的方式**处理文案：
        // 行首 emoji 已经被摘到徽章里了，正文里不能再出现（否则
        // 「🔒 锁定…」会变成徽章一颗锁 + 文字里又一颗锁）。
        String[] box = new String[]{ text };
        takeLeadingEmoji(box);
        ((TextView) o).setText(stripEmoji(box[0]));
    }

    /**
     * 可点击**行**的背景（首页导航行、子页里的箭头行）。
     *
     * <p>和 {@link #button} 的区别：行本身没有按钮那样明显的立体感，
     * 常态就是一个平面，按下时才凹陷 —— 因为一屏里有 8 行，
     * 每行都做成浮雕按钮会变成"气泡墙"（第一版就是这样，截图确认）。
     *
     * <p><b>常态必须是"真的什么都没有"。</b>这里原来给常态挂了
     * {@code UiKit.card}，注释却写着"常态就是一个平面" —— 实现和注释相反。
     * 新拟态下 card 与背景同色、只多一圈浮雕，勉强算"平"；但**玻璃态下
     * card 会画出半透明面板 + 1px glassBorder 描边**，于是同一张卡片里
     * "带箭头的行"长成了描边圆角块、"带开关的行"还是扁平的 ——
     * 用户截图里同一列表两种观感，说的就是它（"r 角不一致"）。
     *
     * <p>改成透明的常态后，所有行都只剩内容与留白，行与行的一致性是结构性的，
     * 与引擎无关；可点性由按下凹陷 + {@code pressFx} 的缩放承担。
     */
    static Drawable rowPressable(Context c, Tokens.Set t, int radiusDp) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed},
                UiKit.sunken(c, t, radiusDp));
        sld.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        return sld;
    }

    /**
     * 手势说明的一行：手势名（固定宽度、强调色）+ 说明。
     *
     * <p>替代原来那坨 {@code setTextColor(0xFF888888)} 的长文本 ——
     * 实测那个颜色叠在页面底色上只有 1.07:1，完全读不出来。这里用令牌色，
     * 并把每个手势拆成独立一行，扫一眼就能找到想看的那条。
     */
    static View gestureRow(Context c, Tokens.Set t, String name, String desc) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));

        TextView n = new TextView(c);
        n.setText(name);
        n.setTextSize(15);
        n.setTextColor(t.accentInk);   // 小字，用加深变体过 AA
        n.setTypeface(n.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(n, new LinearLayout.LayoutParams(
                dp(c, 56), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView d = new TextView(c);
        d.setText(desc);
        d.setTextSize(14);
        d.setTextColor(t.text2);
        row.addView(d, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * 发丝分隔线的颜色。
     *
     * <p>给需要直接 {@code setBackgroundColor} 的地方用（如自绘的行内分隔），
     * 避免再写死 {@code 0x223F51B5} 这类常量 —— 那种常量不随主题变化，
     * 深色下几乎看不见。
     */
    static int dividerColor(Tokens.Set t) {
        return t.hairline;
    }

    /** 分区标题 + 分隔线的组合（原型里的 sec 行） */
    static View divider(Context c, Tokens.Set t) {
        View v = new View(c);
        v.setBackgroundColor(t.hairline);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        lp.topMargin = dp(c, 8);
        lp.bottomMargin = dp(c, 8);
        v.setLayoutParams(lp);
        return v;
    }

    // ==================== 背景构造 ====================

    /**
     * 可按压态的背景。
     *
     * <p>原型的设计决定：**不用 ripple**，改用 scale 0.95 + 150ms 缓动
     * （{@code hyperosClickable}）。这里用「按下时换一张略凹陷/更亮的底」
     * 来近似那个反馈 —— 纯 framework 下做真 ripple 需要 API 21+ 的
     * {@code RippleDrawable}，但那会带来系统默认的水波纹观感，与原型的
     * 克制风格冲突。
     */
    private static Drawable pressable(Context c, Tokens.Set t, int radiusDp, boolean primary) {
        StateListDrawable sld = new StateListDrawable();

        // **用户明确要求的偏离**：原型不给按钮描边（只有 .card/.modal/.pill 有
        // `border:1px solid var(--glass-edge)`），按钮靠 box-shadow 表达。
        // 但用户看到玻璃态浅色的分段按钮后要求"请加入描边效果"，
        // 所以这里按用户要求给按钮加一圈 1dp 描边。
        //
        // 描边色：玻璃用令牌的 glassBorder（原型 .card 用的就是它）；
        // 新拟态没有 border 概念、glassBorder 为 0，用 surface 与 edge 按
        // 0.45 混出的浅边 —— 直接上 hairline(α20) 在实机上几乎看不见，
        // 用纯 edge 又会变成一圈很硬的灰线，破坏"同色只靠阴影"的语言。
        final int stroke = Tokens.skin(c) == Tokens.SKIN_GLASS && t.glassBorder != 0
                ? t.glassBorder : UiKit.mix(t.surface, t.edge, 0.45f);
        final int ins = inset(c);

        Drawable normal;
        Drawable pressed;
        if (primary) {
            // 实心渐变按钮（solid）本身画满 View 边界，内缩为 0，
            // 描边直接由 GradientDrawable 的 setStroke 承担。
            normal = solid(c, t, radiusDp, blend(t.surface, t.accent, 0.18f,
                    Tokens.skin(c) == Tokens.SKIN_GLASS), stroke);
            pressed = solid(c, t, radiusDp, blend(t.surface, t.accent, 0.30f,
                    Tokens.skin(c) == Tokens.SKIN_GLASS), stroke);
        } else {
            // 常态＝浮雕/玻璃凸起（形状内缩 ins），按下＝同内缩的凹陷。
            // 按下态**必须**用同一个 ins：原来按下换成内缩 0 的 sunken，
            // 形状比常态大一圈，按下去按钮会"胀开"。
            normal = UiKit.stroked(c, t, UiKit.card(c, t, radiusDp), radiusDp, ins, stroke);
            pressed = UiKit.stroked(c, t, UiKit.sunken(c, t, radiusDp, ins),
                    radiusDp, ins, stroke);
        }

        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /**
     * 自绘开关（原型 {@code .sw}，来自 skill 的 {@code NeumorphicSwitch}：
     * 52×32 凹陷轨道 + 26dp 凸起滑块）。
     *
     * <p>为什么不用系统 {@code Switch}：它是 Material 的实心胶囊 + 白圆点，
     * 和浮雕语言不搭，而且轨道色不受令牌控制。自绘后轨道凹陷、滑块凸起，
     * 选中时滑块染强调色 —— 与原型一致。
     *
     * <p>返回 {@code FrameLayout}：整块 52×32 可点。注意这比 Material 建议的
     * 48dp 触点在**宽度**上够（52dp），高度 32dp 不足 —— 所以外层行
     * 仍是整行可点（{@link #toggleRow} 已把整行设成 OnClickListener），
     * 实际触点等于整行高度（56dp），可达性达标。
     */
    /**
     * 开关的**句柄**：一个不进入视图树的 {@link CompoundButton}，只作为
     * {@code onCheckedChanged} 的第一个参数（"self"）交给监听器。
     *
     * <p><b>为什么需要它</b>：有些开关在被打开时要先校验条件，条件不满足就把
     * 自己**弹回去**（如「启用视频背景」但还没选过视频 → 弹回并拉起选择器）。
     * 监听器要弹回自己，就得拿到"自己"。原来的做法是回调里传 {@code null}，
     * 调用点写 {@code b.setChecked(false)} —— 直接 NPE 崩溃：
     * {@code CompoundButton.setChecked on a null object reference}
     * （实测抓到的崩溃路径：点「启用视频背景」且未选视频，进程必崩）。
     *
     * <p>自绘开关的可见部分是 {@code box} 里的两个 View，本身不是
     * {@code CompoundButton}，所以用这个轻量句柄把"状态读写"桥接过去：
     * {@link #isChecked()} / {@link #setChecked(boolean)} 直接操作 box 的
     * tag 与两个子 View，并且**走和点击路径同一套动画**，
     * 这样"弹回去"看起来和用户自己点一下完全一样。
     */
    static final class SwitchHandle extends CompoundButton {
        private android.widget.FrameLayout box;
        private Context ctx;
        private Tokens.Set tk;

        SwitchHandle(Context c) {
            super(c);
        }

        void attach(android.widget.FrameLayout b, Tokens.Set tokens) {
            this.box = b;
            this.ctx = b.getContext();
            this.tk = tokens;
        }

        @Override public boolean isChecked() {
            return box != null && Boolean.TRUE.equals(box.getTag());
        }

        @Override public void setChecked(boolean checked) {
            // 状态没变就什么都不做：避免"弹回"时重复播一遍动画。
            if (box == null || isChecked() == checked) return;
            box.setTag(Boolean.valueOf(checked));
            final View th = box.getChildAt(1);
            th.animate().cancel();
            th.animate()
                    .translationX(dp(ctx, checked ? 20 : 0))
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                    .start();
            animateSwitchSkin(ctx, tk, box, th, checked);
        }
    }

    /**
     * 把开关**弹回**到指定状态（不重建页面）。
     *
     * <p>给"条件不满足就别打开"的监听器用：{@code revertSwitch(b, false)}。
     * 对 {@code b} 为 null 或非 {@link SwitchHandle} 的情况静默返回 ——
     * 这样监听器不必自己判空（历史崩溃就是漏了这一步）。
     */
    static void revertSwitch(CompoundButton b, boolean to) {
        if (b != null) b.setChecked(to);
    }

    /**
     * 自绘开关（原型 {@code .sw}）。
     *
     * <p>用 {@link Switch} 并强制整行最小 48dp 高、整行可点 ——
     * 改造前用 CheckBox，触点只有 50.9×27.4dp，是评审报告里明确的问题。
     * 现在点文字也能切换（整行 OnClickListener 转发），实测可达性达标。
     */
    static View switchView(final Context c, final Tokens.Set t, boolean checked,
                           final CompoundButton.OnCheckedChangeListener l) {
        final android.widget.FrameLayout box = new android.widget.FrameLayout(c);
        // 保留 clipChildren=false：滑块的外阴影现在**恰好**落在 View 内
        // （0.5× 阴影 → 余量 3dp＝原型里滑块与轨道之间那 3px 的空隙），
        // 但开关在按下/滑动的过渡帧里仍可能有半像素的溢出，裁掉会留下硬边。
        box.setClipChildren(false);

        final View track = new View(c);
        track.setBackground(trackDrawable(c, t, checked));
        box.addView(track, new android.widget.FrameLayout.LayoutParams(
                dp(c, 52), dp(c, 32)));

        final View thumb = new View(c);
        thumb.setBackground(thumbDrawable(c, t, checked));
        // View 比可见滑块每边大 `ins`：浮雕的外阴影要有地方画，
        // 否则会被 View 边界切成硬边。可见形体仍是 26dp（原型 26px）。
        final int ins = thumbInset(c);
        android.widget.FrameLayout.LayoutParams tp = new android.widget.FrameLayout.LayoutParams(
                dp(c, 26) + ins * 2, dp(c, 26) + ins * 2);
        // 滑块位置**只用 translationX 表达**，leftMargin 恒定（左端起点）。
        //
        // 这是一个真实的 bug，用户连报两轮："开关本身是打开的，再点击会跳出去"
        // 和截图里滑块有大半颗跑到轨道外面。原因是**两种定位方式被叠加**了：
        //   leftMargin = checked ? 23 : 3   ← 按初始状态给到右端
        //   translationX = now ? 20 : 0     ← 点击时又叠加 20dp
        // 于是"开 → 关"时实际偏移 = 23 + 20 = 43dp，而行程只有 20dp，
        // 滑块被推出轨道右侧 23dp（正好是截图里那半颗）。
        // 而且 leftMargin 在点击时**从不更新**，所以再点回来时
        // margin 还是 23、translationX 归 0，位置又对不上，看起来就是"跳"。
        //
        // 现在统一：margin 固定为左端起点，位移全部由 translationX 负责。
        // 这样"当前状态"只有一个来源，两种定位不可能再互相打架。
        // 可见形体的左上角落在 (3dp,3dp)（原型 `top:3px; left:3px`）。
        // View 自己把 `ins` 内缩掉了，所以 margin 要相应减去它 ——
        // 这样可见滑块的位置和尺寸都与加浮雕之前**逐像素一致**。
        tp.leftMargin = dp(c, 3) - ins;
        tp.topMargin = dp(c, 3) - ins;
        thumb.setTranslationX(dp(c, checked ? 20 : 0));
        box.addView(thumb, tp);

        box.setTag(Boolean.valueOf(checked));
        box.setClickable(true);
        box.setFocusable(true);
        // 交给监听器的"self"句柄（见 SwitchHandle 的说明）
        final SwitchHandle owner = new SwitchHandle(c);
        owner.attach(box, t);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !((Boolean) box.getTag());
                box.setTag(Boolean.valueOf(now));
                final View th = box.getChildAt(1);
                // 滑块用 translationX 平滑滑动，而不是直接改 leftMargin。
                //
                // 原来改 leftMargin 后立刻 setLayoutParams —— 那是**瞬间跳过去**的，
                // 用户反馈"不符合实际效果"就包括这一点：真实开关的滑块是滑过去的。
                // translationX 是绘制层属性，可以带动画且不触发 requestLayout，
                // 滑起来更顺。
                float target = dp(c, now ? 20 : 0);   // 23-3 = 20dp 行程
                th.animate().cancel();
                th.animate()
                        .translationX(target)
                        .setDuration(180)
                        // 略微回弹：真实开关到位时有一点过冲再回落，
                        // 线性滑到位会显得"机械"。
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                        .start();
                // 轨道与滑块的颜色**用动画过渡**，不要瞬切。
                //
                // 原来这两行是直接 setBackground —— 位置在滑、颜色却瞬间跳变，
                // 用户看到的"开关打开关闭的样子依旧不一样/没有动画"就是这个：
                // 新拟态下轨道和滑块的材质变化比位置更显眼，瞬时切换尤其刺眼。
                // 用 ValueAnimator 在两种材质间插值：把过渡过程画成
                // 「轨道填充量 + 滑块颜色」随进度变化，与位置动画同一个 180ms。
                animateSwitchSkin(c, t, box, th, now);
                // ★ 监听器必须知道"是哪个开关在回调"，否则它没法把状态**退回**去。
                //
                // 历史坑：这里原来传 `null`，而两个调用点写了 `b.setChecked(false)`
                // （"条件不满足就把开关弹回去"）—— 直接 NPE 崩溃，
                // 报错是 `CompoundButton.setChecked on a null object reference`，
                // 触发路径是「点『启用视频背景』但还没选视频」。
                // 传 owner 而不是 box：owner 是 CompoundButton 的子类，
                // 既能 revertSwitch() 也能 cast 成 View 用。
                if (l != null) l.onCheckedChanged(owner, now);
            }
        });

        // 单独包一层，避免被 LinearLayout 拉伸成整行宽
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(c);
        wrap.setClipChildren(false);   // 同上：别切掉滑块的浮雕阴影
        wrap.addView(box, new android.widget.FrameLayout.LayoutParams(
                dp(c, 52), dp(c, 32)));
        return wrap;
    }

    /**
     * 开关的轨道。
     *
     * <p><b>反馈的问题</b>：原来的"开"是 {@code accentSoft} 实心浅蓝底 +
     * 蓝色实心圆滑块，看上去就是一坨蓝 —— 关闭与开启的区别只剩滑块位置，
     * 而滑块本身又是纯色方块，读不出"开关"这个动作。
     *
     * <p><b>反馈仍不满意（第二轮）</b>：把开=0.20 强调色填充后，用户说
     * "新拟态的时候尤为明显还是不一样"。原因是 0.20 太淡，在浅色新拟态下
     * 与卡片底色几乎同色（截图里两个开关都像"关"）。
     *
     * <p><b>第三轮（像素实测后）</b>：把填充提到 0.62 后量了真实颜色 ——
     * 轨道 RGB(102,140,206)、滑块 RGB(31,96,208)。填充确实画出来了，
     * 但**两者明度太接近**，糊成一个蓝胶囊，所以"看起来还是不对"。
     * 这一版的修法是拉开材质差而不是继续加饱和度：
     * <ul>
     *   <li>轨道 = 强调色 <b>淡化</b>（0.30，偏白的浅蓝底）</li>
     *   <li>滑块 = <b>纯</b>强调色实心 + 更强浮雕</li>
     * </ul>
     * 于是"开"= 浅蓝槽里嵌一颗纯强调色的凸起块，形状与明度都分得开；
     * "关"= 槽里嵌一颗与轨道同底色（{@code surface}）的凸起块。
     * 两个状态在缩略图尺寸下也能分辨。
     */
    private static Drawable trackDrawable(Context c, Tokens.Set t, boolean on) {
        // 半径从 R_CHIP(14dp) 改成 R_PILL：原型的 `.sw i` 是
        // `border-radius: var(--r-chip)`，在 32dp 高的轨道上留出 2dp 的
        // 直边 —— 滑块改成**正圆**之后，轨道那点直边和圆滑块就不是同一种
        // 形状语言了（用户："按钮的边框 r 角度不一致，跟圆形按钮保持一致"）。
        // 取 R_PILL 后轨道也是完整胶囊，圆滑块＋胶囊轨道＝开关的通用形态。
        return on ? UiKit.sunkenTinted(c, t, Tokens.R_PILL, t.accent, 0.42f)
                  : UiKit.sunken(c, t, Tokens.R_PILL);
    }

    /**
     * 开关的滑块：浮雕圆点。
     *
     * <p>「开」用强调色实心，「关」用与卡片同色的凸起 ——
     * 关的时候滑块是**背景的一部分**（凸起），开的时候才"点亮"。
     *
     * <p><b>两态必须同构（这是修「按钮开启和关闭的大小不一样」）。</b>
     * 原来写的是 {@code on ? UiKit.solidFill(...) : UiKit.card(...)}，
     * 两者是**不同种类**的 Drawable，可见尺寸不同：
     * <ul>
     *   <li>{@code solidFill} = GradientDrawable，画满整个 bounds → 26dp</li>
     *   <li>{@code card} = EmbossDrawable，内部内缩 off+blur ≈ 4dp
     *       → 可见 26 - 4×2 = 18dp</li>
     * </ul>
     * 于是"开"比"关"大 8dp，在 26dp 的控件上非常明显。更迷惑的是
     * **两套引擎表现不一样**：玻璃的内缩区域是透明的，露出背景，
     * 所以"看起来还是大的"，于是用户看到"换成玻璃态就正常了" ——
     * 那不是玻璃对了，而是玻璃把内缩暴露得不明显。
     *
     * <p><b>现在的做法</b>：两态都走 {@link UiKit#raised}（同一条渲染路径、
     * 同一个 inset），可见尺寸按构造就相同，形状差异只由颜色承担。
     * 同时补齐了原型要求、此前一直缺的三点：
     * <ul>
     *   <li>形状是**圆角方块**（{@code border-radius:10px}），不是正圆
     *       —— 旧实现填的是 {@code radius=13}，在 26dp 上正好是圆；</li>
     *   <li>关态本体用 {@code surface}（与轨道同色），旧实现用
     *       {@code surfaceSunken}，比轨道更暗，读起来像"洞"而不是"珠"；</li>
     *   <li>**没有描边**：原型 {@code border:0}，凸起靠 {@code box-shadow}
     *       表达。旧实现那圈 {@code t.edge} 灰描边其实是"没有浮雕"的补偿，
     *       现在有真浮雕了就不需要它。</li>
     * </ul>
     */
    private static Drawable thumbDrawable(Context c, Tokens.Set t, boolean on) {
        int ins = thumbInset(c);
        // 半径 13 = 26dp 的一半 → **正圆**。
        //
        // 原型写的是 `border-radius:10px`（26px 的圆角方块），但用户明确要求
        // "开关按钮请做成圆形"，所以这里按用户要求取圆 —— 与滑块珠子
        // （border-radius:16px @32px，本身就是圆）也统一了形状语言。
        return UiKit.raised(c, t, 13, on ? t.accent : 0, ins,
                UiKit.shadowScaleFor(c, ins));
    }

    /**
     * 开关滑块给外阴影留的余量（px）。
     *
     * <p><b>从 6dp 改成 3dp 是这一轮的关键。</b>原型的滑块是
     * {@code top:3px; left:3px; width:26px; height:26px} 放在 52×32 的轨道里
     * —— 上下左右**各只有 3px 空隙**。这个空隙就是阴影能用的全部地方。
     *
     * <p>原先这里沿用页面的 {@code embossInset}（6dp）：滑块 View 被撑到
     * 26+12=38dp，可见形体虽仍是 26dp，但那 12dp 的余量让阴影摊开到轨道
     * 圆角之外，在玻璃态深色下尤其明显（用户截图红框：滑块右下那圈灰晕）。
     *
     * <p>改成 3dp 后，View＝26+6=32dp＝轨道高度，可见形体与阴影**恰好**
     * 落在轨道内，和原型的几何完全一致。
     */
    private static int thumbInset(Context c) {
        return dp(c, 3);
    }

    /**
     * 开关换挡时的材质过渡动画。
     *
     * <p>为什么不能像原来那样直接 {@code setBackground}：位置在滑、材质却瞬切，
     * 视觉上就是"抖一下" —— 用户连着两轮反馈"开关打开关闭还是不一样"，
     * 新拟态下轨道填充与滑块颜色的变化比位置位移更显眼，所以瞬切尤其明显。
     *
     * <p>做法：按进度 {@code f} 逐帧重建两个 Drawable，
     * 让"轨道填充比例"和"滑块是否点亮"连续变化。用 4 帧一档重建
     * （{@code steps = 6}）而不是每帧都建 —— 每帧 new 一个 Drawable
     * 会持续产生垃圾，而这个动画只有 180ms。
     */
    private static void animateSwitchSkin(final Context c, final Tokens.Set t,
                                          final android.widget.FrameLayout box,
                                          final View thumb, final boolean on) {
        final android.view.View track = ((android.view.ViewGroup) box).getChildAt(0);
        final int steps = 6;
        android.animation.ValueAnimator va =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(180);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        final int[] last = new int[]{ -1 };
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float f = a.getAnimatedFraction();
                int step = Math.round(f * steps);
                if (step == last[0]) return;      // 同一档不重复重建
                last[0] = step;
                float p = step / (float) steps;
                // 轨道填充：关→开时 0→0.62；开→关时反向
                float amount = on ? (0.42f * p) : (0.42f * (1f - p));
                track.setBackground(amount <= 0.001f
                        ? UiKit.sunken(c, t, Tokens.R_PILL)
                        : UiKit.sunkenTinted(c, t, Tokens.R_PILL, t.accent, amount));
                // 滑块：进度过半即视为"点亮"，避免中间态出现半蓝不透的糊色
                thumb.setBackground(thumbDrawable(c, t, p >= 0.5f ? on : !on));
            }
        });
        va.start();
    }

    /**
     * 换肤后的滑块（原型 {@code .slider}：14dp 凹陷轨道 + 32px 凸起圆点）。
     *
     * <p>为什么不能用默认 SeekBar：平台 SeekBar 是 2dp 细线 + 小灰圆点，
     * 在这套浮雕语言里像"没画完"。原型是**厚轨道内嵌 + 凸起滑块**，
     * 滑块本身带双向阴影，和按钮同一种材质。
     *
     * <p>做法：轨道用 {@link UiKit#sunken} 自绘（凹陷感），
     * 滑块用 {@link UiKit#solidFill} 画实心圆 + 浮雕由 thumbOffset 布局体现。
     * 逐处替换 {@code new SeekBar(this)} 容易漏，所以集中在这里。
     */
    static SeekBar seekBar(Context c, Tokens.Set t) {
        return new SkinSeekBar(c, t);
    }

    /**
     * 给已存在的 SeekBar 换肤。
     *
     * <p>轨道由 {@link SkinSeekBar#onDraw} 自己画，所以这里只能换滑块 ——
     * 直接 new 出来的普通 SeekBar 拿不到自绘轨道。设置页现在全部走
     * {@link #seekBar}，这个方法只为兼容其它调用点保留。
     */
    static void skinSeekBar(SeekBar sb, Context c, Tokens.Set t) {
        if (sb == null) return;
        sb.setThumb(new ThumbDrawable(c, t));
    }

    /**
     * 自绘轨道的 SeekBar（原型 {@code .slider}：14dp 凹陷槽 + 32dp 凸起圆点）。
     *
     * <p><b>为什么不用 LayerDrawable + R.id.progress 让平台裁剪：</b>
     * 试过，实机整条槽全是强调色（像素采样 x=60…960 全是 {@code 1F5FD0}），
     * 也就是说进度层**没有被 level 裁剪**，滑到 0.55× 右边依然满格。
     * 既然平台语义在这里不可靠，就干脆不依赖它：轨道和已选段全部自己画，
     * 进度直接读 {@link #getProgress()}/{@link #getMax()}，所见即所得。
     *
     * <p>顺序很关键：先画槽和已选段，再 {@code super.onDraw} 让平台画滑块，
     * 滑块才会压在轨道上面。若反过来，轨道会盖住滑块。
     */
    private static final class SkinSeekBar extends SeekBar {
        private final Context c;
        private final Tokens.Set t;
        private final Drawable sunk;

        SkinSeekBar(Context c, Tokens.Set t) {
            super(c);
            this.c = c;
            this.t = t;
            this.sunk = UiKit.sunken(c, t, 7);
            // 平台轨道不画：否则会和我自绘的槽叠在一起（两条轨道）。
            setProgressDrawable(new EmptyDrawable());
            setThumb(new ThumbDrawable(c, t));
            // 关键：把 ThumbDrawable 的**位图内缩**交给平台当 thumbOffset。
            //
            // 这里原来写的是 0，注释说"让平台的定位公式和 drawTrack 的槽范围一致"
            // —— 那个推理只对上了 **Drawable 的边界**，没算上边界内那圈透明余量
            // （每边 7.5dp，给浮雕阴影留的）。后果是可见珠子停在内缩处：
            // 实测 100% 时珠子右缘 x=1002 / 轨道右端 x=1022，差了 20px，
            // 也就是"拖到最大还没到头"。
            //
            // 补上 inset 后，可见珠子的外缘在 0% 贴轨道左端、100% 贴右端，
            // 与原型 HTML range 的行为一致（那边 thumb 也不含阴影余量）。
            setThumbOffset(ThumbDrawable.insetPx(c));
            setSplitTrack(false);
            // 44→56：滑块本体换成 40dp 后（给阴影留位置），
            // 原来的高度会把上下阴影裁掉，必须同步加高。
            setMinimumHeight(dp(c, 56));
            // 左右留出 PAD_CARD + 形变余量：裸 SeekBar 直接放在卡片里，
            // 原先只有 4dp 左右内边距 —— 轨道右端几乎顶到卡片可见边缘
            // （玻璃态下有描边，看起来就是"控件长出了卡片"）。
            int side = rowSidePad(c);
            setPadding(side, dp(c, 12), side, dp(c, 12));
        }

        @Override protected synchronized void onDraw(Canvas cv) {
            drawTrack(cv);
            super.onDraw(cv);   // 画滑块
        }

        private void drawTrack(Canvas cv) {
            final int w = getWidth();
            if (w <= 0) return;
            final int pl = getPaddingLeft(), pr = getPaddingRight();

            // 槽：原型 `track { height:14px; border-radius:7px;
            //                  background:var(--surface); box-shadow:var(--deboss) }`
            //
            // 注意原型**没有已选进度段** —— 整条槽就是 surface 同色 + 凹陷阴影，
            // 值完全靠滑块位置表达。第一版照着"常规滑块"画了一条蓝色已选段，
            // 结果轨道左半截是蓝的（像素采样确认 x=60..280 全为 1F5FD0），
            // 和原型对不上。这里去掉。
            final int th14 = dp(c, 14);
            final int top = (getHeight() - th14) / 2;
            sunk.setBounds(pl, top, w - pr, top + th14);
            sunk.draw(cv);
        }
    }

    /** 什么都不画的 Drawable：用来屏蔽平台的默认轨道 */
    private static final class EmptyDrawable extends Drawable {
        @Override public void draw(Canvas cv) {}
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSPARENT; }
        @Override public int getIntrinsicHeight() { return 0; }
        @Override public int getIntrinsicWidth() { return -1; }
    }

    /**
     * 滑块的圆形 thumb。
     *
     * <p><b>反馈的问题</b>：「进度条拖动按钮优化一下，一定要符合主题」。
     * 原来是一个纯色蓝圆 + 一圈 1.5dp 浅描边 —— 在两套引擎里都不成立：
     * 新拟态的核心是"形状靠双色浮雕阴影表达"，一个平面色块既没有光源方向、
     * 也没有起伏，看上去就像平台默认控件贴上来。
     *
     * <p><b>改法</b>：画成一颗**凸起的珠子** ——
     * <ol>
     *   <li>先用 {@code shadowDark} 画暗侧投影（右下偏移 + 模糊）</li>
     *   <li>再用 {@code shadowLight} 画亮侧高光（左上偏移 + 模糊）</li>
     *   <li>最后叠上强调色本体</li>
     * </ol>
     * 这与 {@link UiKit.EmbossDrawable} 的凸起逻辑一致，所以滑块和按钮、
     * 卡片是同一种材质；玻璃引擎下额外叠一层顶部内高光，
     * 与 {@code GlassDrawable} 的观感对齐。
     */
    private static final class ThumbDrawable extends Drawable {
        private final Context c;
        private final Tokens.Set t;
        /** 复用位图缓存：拖动时每帧都会重画，不能每帧 new Bitmap + BlurMaskFilter */
        private final UiKit.ShadowCache cache = new UiKit.ShadowCache();

        ThumbDrawable(Context c, Tokens.Set t) {
            this.c = c;
            this.t = t;
        }

        @Override public void draw(Canvas cv) {
            final android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            final int w = Math.max(1, b.width());
            final int h = Math.max(1, b.height());

            android.graphics.Bitmap bmp = cache.get(w, h, 0,
                    (t.accent * 31 + t.shadowDark * 7 + t.shadowLight * 13
                     + (Tokens.skin(c) == Tokens.SKIN_GLASS ? 5 : 0)),
                    new UiKit.Painter() {
                @Override public android.graphics.Bitmap paint(int width, int height, int r) {
                    return renderThumb(width, height);
                }
            });
            if (bmp != null) cv.drawBitmap(bmp, b.left, b.top, null);
        }

        private android.graphics.Bitmap renderThumb(int w, int h) {
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);

            final float d = c.getResources().getDisplayMetrics().density;
            // 阴影参数必须**明显**大于之前那版：
            //
            // 第一版用 off=1.5dp / blur=2.5dp，实机像素采样显示整行亮度跨度只有
            // 31（222~253），也就是说浮雕基本看不见 —— 用户反馈"拖动按钮不符合
            // 主题"正是这个原因。而且当时阴影内缩后仍在 32dp 的框里画，
            // 模糊被 bounds 裁掉，圆点被切成"上平下圆"（放大截图确认）。
            //
            // 现在：偏移 2.5dp、模糊 5dp、形状半径再内缩一个 blur，
            // 并在 intrinsic size 上留足空间（见下方 40dp），阴影才画得完整。
            final float off = 2.5f * d;
            final float blur = 5f * d;
            float inset = off + blur;
            float r = Math.min(w, h) / 2f - inset;
            if (r <= 0) {
                r = Math.min(w, h) / 2f - 1f;
                inset = 0;
            }
            float cx = w / 2f, cy = h / 2f;
            final boolean glass = Tokens.skin(c) == Tokens.SKIN_GLASS;

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setMaskFilter(new android.graphics.BlurMaskFilter(
                    blur, android.graphics.BlurMaskFilter.Blur.NORMAL));

            if (glass) {
                // 玻璃引擎**只画一道向下的暗影**，不画亮的侧光。
                //
                // 原型里两套引擎的 `--emboss` 不是一回事：
                //   新拟态 `3px 3px 6px dark, -3px -3px 6px light`（双向双色）
                //   玻璃   `0 2px 6px outer`（单向、单色）
                // 原实现两套都用双向双色，于是玻璃浅色下珠子左上方被糊上
                // 一团 75% 的白晕（shadowLight＝rgba(255,255,255,.75)）。
                // 珠子是蓝的、轨道是白的，那团白晕正好卡在珠子边缘，
                // 轮廓被啃掉一块 —— 用户说的"会变形不是圆"就是它。
                final float gOff = 2.5f * d;
                p.setColor(t.glassOuter != 0 ? t.glassOuter : t.shadowDark);
                cv.drawCircle(cx, cy + gOff, r, p);
            } else {
                // ① 暗侧：右下（偏移 + 模糊，形成"圆点浮在轨道上"的投影）
                p.setColor(t.shadowDark);
                cv.drawCircle(cx + off, cy + off, r, p);
                // ② 亮侧：左上
                p.setColor(t.shadowLight);
                cv.drawCircle(cx - off, cy - off, r, p);
            }

            // ③ 本体（强调色球）
            Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
            body.setColor(t.accent);
            cv.drawCircle(cx, cy, r, body);

            // ③b 球面感：右下压暗、左上提亮，避免"一坨纯色"。
            //     用径向渐变而不是纯色填充，滑块才有体积。
            Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            sheen.setShader(new android.graphics.RadialGradient(
                    cx - r * 0.35f, cy - r * 0.35f, r * 1.25f,
                    new int[]{withAlpha(0xFFFFFFFF, 0.30f),
                              withAlpha(0xFFFFFFFF, 0f)},
                    new float[]{0f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r, sheen);

            // ④ 玻璃引擎补一道顶部内高光，和玻璃卡片的"顶部反光"一致。
            //
            // 用 glassInner（原型 --glass-inner 就是给这个用的）而不是
            // shadowLight：后者在玻璃浅色下是 75% 的白，细弧会亮得像一道缺口。
            if (glass) {
                Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
                hl.setStyle(Paint.Style.STROKE);
                hl.setStrokeWidth(Math.max(1f, 1f * d));
                hl.setColor(t.glassInner != 0 ? t.glassInner : t.shadowLight);
                cv.drawArc(new android.graphics.RectF(cx - r + 1, cy - r + 1,
                                cx + r - 1, cy + r - 1),
                        200f, 140f, false, hl);
            }
            return out;
        }

        private static int withAlpha(int color, float a) {
            return (Math.round(255 * Math.max(0f, Math.min(1f, a))) << 24)
                    | (color & 0x00FFFFFF);
        }

        // 位图每边的内缩量（px）＝ 给外阴影留的余量。
        //
        // 这个值必须同时交给平台当 `thumbOffset`，否则滑块的**行程**会少一截：
        // 平台的定位公式让"Drawable 的边界"对齐轨道两端，而可见珠子比
        // Drawable 小 2×inset —— 实测 100% 时珠子右缘停在 x=1002，
        // 轨道右端在 x=1022，差 20px（7.6dp），看上去就是"滑块没到头"。
        // 原型（HTML range）里珠子的外缘正好贴着轨道两端，所以要把内缩补掉。
        static int insetPx(Context c) {
            return Math.round(7.5f * c.getResources().getDisplayMetrics().density);
        }

        // 46dp 而不是 40dp：可见直径要贴近原型的 32px。
        //
        // 可见直径 = intrinsic - 2*(off + blur)。之前 intrinsic=40、
        // off=2.5、blur=5 → 只有 25dp，比原型的 32px 小了 7dp，
        // 所以滑块看着"不够圆润、不够像珠子"（用户说"符合主题就行"，
        // 而原型的珠子就是 32px）。改 intrinsic=46 后可见 31dp ≈ 原型。
        @Override public int getIntrinsicWidth() { return dp(c, 46); }
        @Override public int getIntrinsicHeight() { return dp(c, 46); }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * 把强调色按比例混进表面色。
     * 玻璃引擎下表面是半透明的，混色要在**不透明**基底上做，
     * 否则叠出来会透出下面的 Mesh 而显得脏。
     */
    private static int blend(int surface, int accent, float amount, boolean frosted) {
        int base = frosted ? 0xFF141926 : surface;
        int r = (int) (Color.red(base) * (1 - amount) + Color.red(accent) * amount);
        int g = (int) (Color.green(base) * (1 - amount) + Color.green(accent) * amount);
        int b = (int) (Color.blue(base) * (1 - amount) + Color.blue(accent) * amount);
        if (frosted) return Color.argb((int) (0.92f * 255), r, g, b);
        return Color.argb(Color.alpha(surface), r, g, b);
    }

    // ==================== 三级菜单（对话框）：皮肤与明暗都跟随 App UI ====================
    //
    // ★★ 用户 2026-10-11 要求：「更改所有的三级菜单让所有的三级菜单都同步 app ui 跟随主题切换」。
    //
    // 背景（为什么以前不同步）：本工程是纯 framework、零 AndroidX，**页面**的颜色全部由 Java
    // 从 {@link Tokens.Set} 取 ⇒ 换皮肤/换明暗自动生效。但**系统自绘的对话框**用的是
    // **Activity 主题**，不是我们的令牌 —— 清单里挂的是 Theme.Light.*，于是深色模式下
    // 页面是深色、一按「清空记忆」却弹出一个白色确认框（Tokens.applyPlatformTheme 的注释
    // 记着这件事）。以前只靠 setTheme 换了明暗变体 ⇒ **明暗跟上了，皮肤（新拟态/玻璃态）没跟上**，
    // 而且换过主题后已经打开的对话框不会变。
    //
    // 现在的做法：**所有对话框都从 {@link #dialog} 造**，在 `create()` 里把系统窗口换成
    // 我们的令牌面板（圆角 R_DIALOG + surface + glassBorder）并把标题/正文/按钮染色。
    // 调用点写法与平台**完全一致**（`UiFactory.dialog(this).setTitle(…).show()`），
    // 所以 94 处迁移是机械替换，且每次打开都重新取令牌 ⇒ **换主题后新开的对话框立刻是新皮肤**。

    /**
     * 三级菜单（对话框）的**唯一入口**。
     *
     * <p>用法与 {@code UiFactory.dialog(c)} 完全一致，区别只在创建/显示时套上令牌。
     * ★ 别在业务代码里再写 {@code UiFactory.dialog(…)} —— 那会绕过主题化
     * （`build/verify_dialog_theme.py` 会报红）。
     */
    static AlertDialog.Builder dialog(Context c) {
        return new ThemedBuilder(c);
    }

    /** 进度框（同样是系统自绘）—— 走同一个主题化入口。 */
    static ProgressDialog progress(Context c) {
        // ★ 这里**故意**用全限定名构造：本工程的迁移脚本（build/_r40_migrate_dialogs.py）
        //   会把 `new ProgressDialog(` 一律换成 `UiFactory.progress(` —— 写成短名的话
        //   它会把这一行也换掉，于是 progress() 自己调自己（无限递归）。
        //   全限定写法既躲开脚本，也一眼看得出"这里就是唯一真正 new 的地方"。
        android.app.ProgressDialog pd = new android.app.ProgressDialog(c);
        themeDialog(pd, c);
        return pd;
    }

    /** 把 Builder 的 {@code create()} 也接管（有 8 处调用点直接用 {@code .create()}）。 */
    private static final class ThemedBuilder extends AlertDialog.Builder {
        private final Context ctx;
        /** ★ 轮次㊸：记下列表内容，好在 create() 之后把系统那种"贴边纯文字行"换成我们的行 */
        private CharSequence[] items;
        private boolean choice;

        ThemedBuilder(Context c) {
            super(c);
            ctx = c;
        }

        @Override public AlertDialog.Builder setItems(CharSequence[] a, DialogInterface.OnClickListener l) {
            items = a; choice = false;
            return super.setItems(a, l);
        }

        @Override public AlertDialog.Builder setItems(int itemsId, DialogInterface.OnClickListener l) {
            try { items = ctx.getResources().getTextArray(itemsId); } catch (Throwable ignore) {}
            choice = false;
            return super.setItems(itemsId, l);
        }

        @Override public AlertDialog.Builder setSingleChoiceItems(CharSequence[] a, int checked,
                                                                  DialogInterface.OnClickListener l) {
            items = a; choice = true;
            return super.setSingleChoiceItems(a, checked, l);
        }

        @Override public AlertDialog.Builder setSingleChoiceItems(int itemsId, int checked,
                                                                  DialogInterface.OnClickListener l) {
            try { items = ctx.getResources().getTextArray(itemsId); } catch (Throwable ignore) {}
            choice = true;
            return super.setSingleChoiceItems(itemsId, checked, l);
        }

        @Override public AlertDialog.Builder setMultiChoiceItems(CharSequence[] a, boolean[] checked,
                                                                 DialogInterface.OnMultiChoiceClickListener l) {
            items = a; choice = true;
            return super.setMultiChoiceItems(a, checked, l);
        }

        @Override public AlertDialog.Builder setMultiChoiceItems(int itemsId, boolean[] checked,
                                                                 DialogInterface.OnMultiChoiceClickListener l) {
            try { items = ctx.getResources().getTextArray(itemsId); } catch (Throwable ignore) {}
            choice = true;
            return super.setMultiChoiceItems(itemsId, checked, l);
        }

        @Override public AlertDialog create() {
            AlertDialog d = super.create();
            themeDialog(d, ctx, items, choice);
            return d;
        }
    }

    /**
     * 给系统对话框套上我们的令牌（圆角面板 + 标题/正文/按钮配色）。
     *
     * <p>★ 用 {@code getIdentifier} 找 {@code android:id/alertTitle}：它**不在**公开的
     * {@code android.R.id} 里（button1/2/3 与 message 在），写死会编译不过。
     * <p>★ 全程 try/catch：对话框主题化失败只该"看起来旧"，绝不能让弹窗弹不出来。
     */
    static void themeDialog(Dialog d, Context c) {
        themeDialog(d, c, null, false);
    }

    /**
     * 给系统对话框套上我们的令牌（圆角面板 + 标题/正文/按钮配色 + **行与按钮的形状**）。
     *
     * <h3>★ 轮次㊸：从"只同步颜色"升级成"同步 UI"</h3>
     *
     * 用户原话：「**三级菜单并没有同步 ui，只同步了主题颜色**」—— 说得准确。
     * 轮次㊵ 只做了面板底色与三处文字色（{@link #tint}），而对话框的**件**还是系统的：
     * 扁平蓝字按钮条、贴边的纯文字列表行、系统字号的标题与正文。
     * 于是"新拟态/玻璃"这套语言在弹窗里只剩一个圆角底板，和页面明显不是一套东西。
     *
     * <p>现在在同一个入口里把三件事也换掉（**不动 100 个调用点**）：
     * <ol>
     *   <li><b>按钮条</b> → {@link #styleDialogButton}：胶囊 + 我们的 pressable（浮雕/玻璃面），
     *       与 {@link #button} 完全同形；</li>
     *   <li><b>列表行</b> → {@link DialogRowAdapter}：行首单色图标徽章 + 15sp 文字 +
     *       单选/多选右侧的选中标记，行高与卡片行一致（只换 adapter，
     *       **item 点击监听仍由 AlertController 那套管着** ⇒ 行为零变化）；</li>
     *   <li><b>标题/正文</b> → 设计层的字号与行距。</li>
     * </ol>
     * ★ 为什么走"改系统弹窗"而不是自己写一个 Dialog：调用点有 **100 处**，
     * 其中 4 处还在 {@code setOnShowListener} 里用 {@code getButton(...)} 改按钮
     * （改色 / 换 onClick）。自己写 Dialog 会把这些全打断；改内容不动类型 ⇒ 一处都不用改。
     *
     * <p>★ 用 {@code getIdentifier} 找 {@code android:id/alertTitle}：它**不在**公开的
     * {@code android.R.id} 里（button1/2/3 与 message 在），写死会编译不过。
     * <p>★ 全程 try/catch：对话框主题化失败只该"看起来旧"，绝不能让弹窗弹不出来。
     */
    static void themeDialog(Dialog d, Context c, CharSequence[] items, boolean choice) {
        if (d == null || c == null) return;
        try {
            Tokens.Set t = Tokens.of(c);
            Window w = d.getWindow();
            if (w != null) {
                // 外面留一圈内边距：内容不会顶到圆角上（系统原来的 9-patch 就带内边距，
                // 我们换成自己的 drawable 时要自己补回来）
                // ★ 用令牌的 **modal**（弹窗/浮层专用表面）而不是 surface：
                //   新拟态下 modal == bg（形状靠浮雕阴影表达），玻璃下 modal 是近不透明的
                //   白/深面板 —— 这正是设计系统里给"浮在页面之上的那一层"准备的颜色
                //   （ChatActivity / QuickAskActivity 的浮层也用 modal）。
                w.setBackgroundDrawable(new InsetDrawable(
                        solid(c, t, Tokens.R_DIALOG, t.modal, t.glassBorder),
                        dp(c, 14), dp(c, 18), dp(c, 14), dp(c, 18)));
            }
            // ---- ① 标题 / 正文：设计层的字号与行距 ----
            View titleV = d.findViewById(androidId(c, "alertTitle"));
            if (titleV == null) titleV = d.findViewById(android.R.id.title);
            tint(titleV, t.text1, true);
            if (titleV instanceof TextView) {
                TextView tv = (TextView) titleV;
                tv.setTextSize(19);
                tv.setLineSpacing(0, 1.15f);
            }
            View msgV = d.findViewById(android.R.id.message);
            tint(msgV, t.text2, false);
            if (msgV instanceof TextView) {
                TextView tv = (TextView) msgV;
                tv.setTextSize(15);
                tv.setLineSpacing(0, 1.25f);
            }
            // ★ 轮次㊹：系统标题下面那条**分隔线**不跟随令牌（Material 下是系统色的一根线），
            //   直接藏掉 —— 用户要的是"同步主题"，留一条系统色的线就不算同步。
            try {
                View div = d.findViewById(androidId(c, "titleDivider"));
                if (div != null) div.setVisibility(View.GONE);
                View divTop = d.findViewById(androidId(c, "titleDividerTop"));
                if (divTop != null) divTop.setVisibility(View.GONE);
            } catch (Throwable ignore) {}
            // ---- ② 按钮条：换成我们的胶囊（正键用主色） ----
            styleDialogButton(d.findViewById(android.R.id.button1), c, t, true);
            styleDialogButton(d.findViewById(android.R.id.button2), c, t, false);
            styleDialogButton(d.findViewById(android.R.id.button3), c, t, false);
            // ★★ 但 `create()` 这一刻按钮**还没建出来**（实测 Android 36：上面三行拿到的全是 null，
            //   日志里 `btn1=null`）——AlertController 把它们留到 show() 阶段才塞进按钮条。
            //   所以再挂一次"首帧之前"的回调补齐（用 OnPreDraw 而不是 post(延迟)：
            //   它在**第一帧画出来之前**跑 ⇒ 用户看不到系统样式闪一下）。
            //   ★ 顺序上调用点的 setOnShowListener 更早（show() 内就触发），
            //   所以危险键那种"有意改成实心红"的背景在这里必须**跳过**，见 restyleIfSystemDefault。
            final Dialog fd = d;
            try {
                if (w != null && w.getDecorView() != null) {
                    final View decor = w.getDecorView();
                    decor.getViewTreeObserver().addOnPreDrawListener(
                            new android.view.ViewTreeObserver.OnPreDrawListener() {
                        @Override public boolean onPreDraw() {
                            try {
                                Context cc = fd.getContext();
                                Tokens.Set tt = Tokens.of(cc);
                                restyleIfSystemDefault(fd.findViewById(android.R.id.button1), cc, tt, true);
                                restyleIfSystemDefault(fd.findViewById(android.R.id.button2), cc, tt, false);
                                restyleIfSystemDefault(fd.findViewById(android.R.id.button3), cc, tt, false);
                                // ★★ 行也在这里补 —— create() 时那个 ListView 不是最终显示的那个
                                //   （见 styleDialogRows 的说明）
                                String ad = styleDialogRows(fd, cc, tt, items, choice);
                                // ★ 设备侧的证据：create() 那一刻按钮还不存在（btn1=null）、
                                //   行的 ListView 也不是最终那个 ⇒ "真的换上了"只能在这里报。
                                android.util.Log.i("DSDialog", "首帧补齐 btn1="
                                        + bgClassOf(fd, android.R.id.button1)
                                        + " btn1MinH=" + minHeightOf(fd, android.R.id.button1)
                                        + " btn2=" + bgClassOf(fd, android.R.id.button2)
                                        + " rows=" + (items == null ? 0 : items.length)
                                        + " adapter=" + ad);
                            } catch (Throwable ignore) {}
                            try {
                                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                            } catch (Throwable ignore) {}
                            return true;
                        }
                    });
                }
            } catch (Throwable ignore) {}
            // ---- ③ 列表行：换成"徽章 + 文字 + 选中标记"的行 ----
            //   ★★ 但**必须先试一次再在首帧之前补一次**：Android 36 实测，
            //   `create()` 这一刻拿到的 ListView **不是最终显示的那个**
            //   （真机 dump 出来的行仍是系统的 `CheckedTextView`，文字左边界 65px），
            //   和 `btn1=null` 是同一个根因 —— AlertController 的内容到 show() 阶段才装进去。
            //   所以真正生效的是下面 OnPreDraw 里那一次；这里先试一次是为了
            //   在"内容已经装好"的平台上少一次重排。
            String adapterName = styleDialogRows(d, c, t, items, choice);
            // ★ 设备侧只能靠日志验"这一层真的套上了令牌"：对话框不在 uiautomator dump 里，
            //   而且"跟随主题切换"这件事只能靠**两次读数的差**证明（见 verify_dialog_theme_ui.py）。
            //   ★★ 还要报**窗口背景 drawable 的类名**：这是"面板真被换掉了"的**直接证据**
            //   （新拟态下 surface == bg 是设计如此 ⇒ 截图取色分不出弹窗与页面，
            //    而 DecorView 的背景是不是我们的 InsetDrawable 是查得出来的硬状态）。
            String bg = "null";
            try {
                if (w != null && w.getDecorView() != null
                        && w.getDecorView().getBackground() != null) {
                    bg = w.getDecorView().getBackground().getClass().getSimpleName();
                }
            } catch (Throwable ignore2) {}
            // ★ 设备侧的证据：`badge=all` = 每一行都有行首徽章（轮次㊹ 起不再依赖文案有没有 emoji）；
            //   `twoLine=` = 有几条选项是"标题 + 副标题"两行式。
            int twoLine = 0;
            if (items != null) {
                for (CharSequence it : items) {
                    if (it != null && it.toString().indexOf('\n') >= 0) twoLine++;
                }
            }
            android.util.Log.i("DSDialog", "三级菜单套用令牌 skin=" + Tokens.skin(c)
                    + " theme=" + Tokens.theme(c)
                    + " surface=#" + Integer.toHexString(t.surface)
                    + " accent=#" + Integer.toHexString(t.accent)
                    + " bg=" + bg
                    + " rows=" + (items == null ? 0 : items.length)
                    + " twoLine=" + twoLine
                    + " badge=all"
                    + " choice=" + choice
                    + " adapter=" + adapterName
                    + " btn1=" + bgClassOf(d, android.R.id.button1)
                    + " btn1MinH=" + minHeightOf(d, android.R.id.button1));
        } catch (Throwable ignore) {
            // 主题化失败不影响功能（最多看起来是系统默认弹窗）
        }
    }

    /**
     * 把对话框里的系统列表行换成我们自己的行（{@link DialogRowAdapter}）。
     *
     * <p>★ 为什么要单独抽出来、还要在**两个时机**各调一次：Android 36 实测，
     * {@code create()} 那一刻 {@code getListView()} 拿到的**不是最终显示的那个**
     * —— 真机 dump 出来的行仍是系统的 {@code CheckedTextView}（文字左边界 65px，
     * 有徽章时应该被推到 ~170px）。这跟 {@code btn1=null} 是**同一个根因**：
     * AlertController 的内容要到 show() 阶段才装进去。所以真正生效的是
     * {@code OnPreDraw} 里那一次（见 themeDialog）。
     *
     * @return 适配器的类名（设备侧只认这个字段；没装上就是 "none"）
     */
    private static String styleDialogRows(Dialog d, Context c, Tokens.Set t,
                                          CharSequence[] items, boolean choice) {
        if (items == null || items.length == 0 || !(d instanceof AlertDialog)) return "none";
        android.widget.ListView lv = null;
        try { lv = ((AlertDialog) d).getListView(); } catch (Throwable ignore) {}
        if (lv == null) return "none";
        try {
            lv.setDivider(null);
            lv.setDividerHeight(0);
            // ★ 系统的列表 selector 是一层系统色的高亮，会盖在我们的行上
            //   ⇒ 换成透明，按压反馈交给行自己的 rowPressable。
            lv.setSelector(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
        } catch (Throwable ignore) {}
        lv.setAdapter(new DialogRowAdapter(c, t, items, choice, lv));
        android.widget.ListAdapter ad = lv.getAdapter();
        return ad == null ? "null" : ad.getClass().getSimpleName();
    }

    /**
     * 把系统对话框按钮条里的那个 {@code Button} 换成我们的胶囊（与 {@link #button} 同形）。
     * <p>★ 只是**改外观**，不碰 onClick —— 4 处调用点在 {@code setOnShowListener} 里
     * 用 {@code getButton(...)} 改色/换 onClick，都必须继续有效。
     */
    private static void styleDialogButton(View v, Context c, Tokens.Set t, boolean primary) {
        if (!(v instanceof Button)) return;
        Button b = (Button) v;
        try {
            b.setAllCaps(false);
            b.setTextSize(15);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(dp(c, 44));
            b.setMinimumHeight(dp(c, 44));
            int ins = inset(c);
            b.setPadding(dp(c, 15) + ins, dp(c, 10) + ins, dp(c, 15) + ins, dp(c, 10) + ins);
            b.setTextColor(primary ? t.accent : t.text1);
            b.setBackground(pressable(c, t, Tokens.R_PILL, primary));
        } catch (Throwable ignore) {}
    }

    /**
     * 补一次按钮样式 —— 但**只给"还是系统默认背景"的按钮**。
     *
     * <p>★ 为什么要有守卫：4 处调用点在 {@code setOnShowListener} 里把危险键改成
     * **实心红**（{@code UiKit.solidFill} = {@code GradientDrawable}），那是有意为之，
     * 不能被这里的胶囊覆盖掉。而 {@code onShow} 比首帧更早触发，
     * 所以走到这里时"被改过的"按钮背景已经是 GradientDrawable ⇒ 跳过。
     */
    private static void restyleIfSystemDefault(View v, Context c, Tokens.Set t, boolean primary) {
        if (!(v instanceof Button)) return;
        try {
            Drawable bg = v.getBackground();
            if (bg != null && bg.getClass().getSimpleName().contains("Gradient")) return;
        } catch (Throwable ignore) {}
        styleDialogButton(v, c, t, primary);
    }

    /** 某个按钮当前的背景 drawable 类名（设备侧判"形状真被换掉了"用；没有就 "null"）。 */
    private static String bgClassOf(Dialog d, int id) {
        try {
            View v = d.findViewById(id);
            if (v == null || v.getBackground() == null) return "null";
            return v.getBackground().getClass().getSimpleName();
        } catch (Throwable ignore) {
            return "?";
        }
    }

    /**
     * 某个按钮的最小高度（px）。
     * ★ 用 {@code getMinimumHeight()} 而不是 {@code getHeight()}：日志打在 {@code create()} 里，
     * 那一刻**还没布局**，{@code getHeight()} 恒为 0（量不到东西）。最小高度是
     * {@link #styleDialogButton} 当场设的，能直接证明"我们的样式套上了"。
     */
    private static int minHeightOf(Dialog d, int id) {
        try {
            View v = d.findViewById(id);
            return v == null ? -1 : v.getMinimumHeight();
        } catch (Throwable ignore) {
            return -2;
        }
    }

    /**
     * 对话框列表行的适配器：把系统那种"贴边、无图标、行高不定"的文字行，
     * 换成设计层的一行 —— **行首单色图标徽章 + 15sp 文字 + 右侧选中标记**。
     *
     * <p>文案开头的 emoji 与设置页同规矩：挪进行首徽章（查不到图标就只留凹槽底片），
     * 正文里其余 emoji 一并剥掉 —— 这样弹窗和页面是同一套视觉语言。
     *
     * <p>★ 只换 adapter，**不碰 ListView 的 OnItemClickListener**：
     * 单选/多选的状态仍由 {@code AlertController} 通过 ListView 的 choiceMode 维护，
     * 这里只负责把它画出来（{@code isItemChecked}）。
     */
    /**
     * 弹窗行**没有 emoji** 时，按关键词给一个图标名（查不到返回 null ⇒ 只留凹槽底片）。
     *
     * <p>★ 轮次㊹：为什么需要它 —— 用户点名「选择对话服务商」那个弹窗"还是最普通的样子"。
     * 它的选项是"名字 + \n + Key 状态"，开头没有 emoji，于是行首只剩一块**空底片**，
     * 看着仍然像纯文字列表。给一张**小表**兜底之后，每一行都有语义图标
     * （"Key 已保存"→KEY、"无余额接口"→CHART），弹窗才和页面是同一套"行首有图标"的语言。
     *
     * <p>★ 表要**短**：宁可留空底片，也不要猜错图标（猜错比没有更糟）。
     * 关键词命中不了就返回 null，回退成底片 —— 这是本工程既有的回退手法。
     */
    private static String dialogRowIcon(String s) {
        if (s == null || s.length() == 0) return null;
        if (s.contains("Key") || s.contains("密钥")) return "KEY";
        if (s.contains("余额") || s.contains("花费") || s.contains("用量")) return "CHART";
        if (s.contains("导入") || s.contains("上传")) return "UPLOAD";
        if (s.contains("编辑") || s.contains("重命名") || s.contains("名字")) return "TEXT";
        if (s.contains("预览") || s.contains("看一眼")) return "EYE";
        if (s.contains("权重") || s.contains("排序")) return "CHART";
        if (s.contains("删除") || s.contains("清空") || s.contains("移除")) return "WARN";
        if (s.contains("角色") || s.contains("形象") || s.contains("图片")) return "IMAGE";
        if (s.contains("场景") || s.contains("动画") || s.contains("动作")) return "FILM";
        if (s.contains("链接") || s.contains("地址") || s.contains("服务商")) return "LINK";
        return null;
    }

    private static final class DialogRowAdapter extends android.widget.BaseAdapter {
        private final Context ctx;
        private final Tokens.Set t;
        private final CharSequence[] items;
        private final boolean choice;
        private final android.widget.ListView lv;

        DialogRowAdapter(Context c, Tokens.Set tokens, CharSequence[] a, boolean ch,
                         android.widget.ListView list) {
            ctx = c; t = tokens; items = a; choice = ch; lv = list;
        }

        @Override public int getCount() { return items.length; }
        @Override public Object getItem(int i) { return items[i]; }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, android.view.ViewGroup parent) {
            LinearLayout row = row(ctx, t);
            row.setMinimumHeight(dp(ctx, 56));
            // ★ 行本身也给按压反馈（与设置页那些行同一套；系统 ListView 的 selector
            //   在 themeDialog 里已被换成透明，否则会盖出系统色的一层高亮）
            try {
                row.setBackground(rowPressable(ctx, t, Tokens.R_CARD));
                pressFx(row);
            } catch (Throwable ignore) {}

            String[] box = new String[]{ items[pos] == null ? "" : items[pos].toString() };
            String glyph = takeLeadingEmoji(box);
            String label = stripEmoji(box[0]);
            // ★★ 轮次㊹：**每一行都给行首徽章**（以前只在文案以 emoji 开头时才画）。
            //   用户点名「选择对话服务商」那个弹窗"还是最普通的样子" —— 它的选项是
            //   "名字 + \n + Key 状态"，**开头没有 emoji** ⇒ 一行徽章都没有，
            //   看着就是纯文字（改动只剩行高和按钮，太隐形）。
            //   查不到图标时只留凹槽底片 —— 这是本工程既有的回退手法（见 iconBadgeByName）。
            //   ★★ 轮次㊹ 再进一步：没有 emoji 的行按**关键词**给图标（见 dialogRowIcon），
            //   否则"选择对话服务商"那种纯文字选项只会得到一块空底片，还是像纯文字列表。
            String iconName = iconNameFor(glyph);
            if (iconName == null) iconName = dialogRowIcon(label);
            row.addView(iconBadgeByName(ctx, t, iconName, glyph));

            // 文案里带 \n 的（"名字 + \n + 说明"）拆成**两行**：主行 text1 / 副行 text3。
            //   原型与设置页的入口行都是"标题 + 次级副标题"，弹窗里也该是同一套。
            String head = label;
            String sub = null;
            int nl = label.indexOf('\n');
            if (nl >= 0) {
                head = label.substring(0, nl);
                sub = label.substring(nl + 1).replace("\u3000", "").trim();
            }
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView tv = new TextView(ctx);
            tv.setText(head);
            tv.setTextSize(15);
            tv.setTextColor(t.text1);
            tv.setLineSpacing(0, 1.15f);
            col.addView(tv);
            if (sub != null && sub.length() > 0) {
                TextView sv = new TextView(ctx);
                sv.setText(sub);
                sv.setTextSize(12);
                sv.setTextColor(t.text3);
                sv.setPadding(0, dp(ctx, 2), 0, 0);
                col.addView(sv);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(ctx, 12);
            row.addView(col, lp);

            if (choice) {
                boolean checked = false;
                try { checked = lv != null && lv.isItemChecked(pos); } catch (Throwable ignore) {}
                row.addView(iconBadgeByName(ctx, t, checked ? "CHECK" : null, null,
                        checked ? t.accent : 0));
            }
            return row;
        }
    }

    /** 按名字取 framework 的 id（{@code alertTitle} 这类没进 android.R 的只能这么找）。 */
    private static int androidId(Context c, String name) {
        try {
            return c.getResources().getIdentifier(name, "id", "android");
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static void tint(View v, int color, boolean bold) {
        if (!(v instanceof TextView)) return;
        TextView tv = (TextView) v;
        tv.setTextColor(color);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
    }

    private static Drawable solid(Context c, Tokens.Set t, int radiusDp,
                                  int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, radiusDp));
        d.setColor(fill);
        // 用调用方给的描边色。这里原来写死了 t.glassBorder（且忽略了已有的
        // stroke 参数）：新拟态下 glassBorder 为 0，实心按钮就完全没有描边。
        d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }
}
