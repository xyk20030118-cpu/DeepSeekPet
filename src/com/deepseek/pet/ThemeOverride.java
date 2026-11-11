package com.deepseek.pet;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * **玻璃态**的「自定义界面配色 + UI 透明度」覆盖层（轮次㊾ · P0）。
 *
 * <h3>用户要求</h3>
 * 「我们现在做自定义主题颜色（玻璃态）可以自定义界面ui颜色 用来搭配不同的背景
 * 增加ui透明度选项」。
 *
 * <h3>设计边界（都写过方案、用户逐条拍板）</h3>
 * <ul>
 *   <li><b>只做玻璃态</b>：背景图/视频本来就只在玻璃态出现，透明度也只有它有意义；</li>
 *   <li><b>分组调，不暴露 25 个令牌</b>：用户只调「主色调 / 背景底色 / 玻璃浓度 / 文字深浅」，
 *       其余由这里推导；</li>
 *   <li><b>三项透明度</b>：底色 / 卡片 / 弹窗，各带下限；</li>
 *   <li><b>文字色自动重推</b>：调多透都不会看不清；</li>
 *   <li>配色方案导入/导出**不做**（下一轮）。</li>
 * </ul>
 *
 * <h3>轮次55 追加：Mesh 光斑可调（P2 最后一项）</h3>
 * 三组档位 —— **浓度**（关/淡/标准/浓）、**扩散范围**（收紧/标准/扩散）、
 * **光斑颜色**（内置 / 跟随主色）。同样是"分组调、不暴露每个光斑"。
 * <p>★ 一并把**体检的口径**从"卡片叠底色"升级成"卡片叠**光斑极值**"
 * （见 {@link #meshExtremes}）—— 光斑能调浓之后，只量底色就会**假绿**
 * （读数达标、实际看不清）。这就是"体检是光斑可调的安全网"的落点。
 *
 * <h3>★ 为什么"文字色自动重推"是切换两套**已调好的**调色板，而不是现算颜色</h3>
 * 内置那两套玻璃配色的每个值背后都有一串实测注释（浅色 surface α 必须 ≥.70，
 * 否则 text2/text3 塌成同一个灰；深色若用白 tint，白字会掉到 2.4:1 ……）。
 * 所以这里**不发明新颜色**：按"合成底色的亮度"在 `glassLight` 与 `glassDark` 两套
 * 已经过对比度验证的文字/小字强调色之间切换 —— 用户把底色改深，字就自动换成深色系那套。
 *
 * <h3>★ 为什么透明度只能在令牌层改</h3>
 * {@code MeshDrawable} / {@code GlassDrawable} 的 {@code setAlpha(int)} 是**空实现**
 * （见 UiKit），所以 {@code View.setAlpha} 对它们无效 —— 透明度必须写进令牌的 alpha 通道。
 * 而 {@link Tokens#of(Context)} 是全工程唯一入口，覆盖就挂在那里（调用点一处都不用改）。
 *
 * <h3>存哪</h3>
 * {@code filesDir/theme/glass_light.json} 与 {@code glass_dark.json}（**明暗各一套**：
 * 同一组颜色在深浅两套底下要的不一样）。
 *
 * <pre>{"version":1,"accent":"#1D5AC5","bg":"#F3EFEA",
 *  "alpha":{"bg":1.0,"surface":0.7,"modal":0.94},"textTone":"auto",
 *  "mesh":{"strength":1.0,"scale":1.0,"tint":"builtin"}}</pre>
 */
public final class ThemeOverride {

    private static final String DIR = "theme";
    private static final String N_ALPHA = "alpha";
    private static final String N_MESH = "mesh";

    /**
     * 卡片面透明度下限。
     * <p>★ 不是随手定的：{@code Tokens.glassLight} 的注释里记着实测结论 ——
     * 「surface 的 alpha 必须是 .70：低于此值 text-2 与 text-3 会塌成几乎相同的灰，
     * 层级感消失」。留一点余量取 .50 作为硬下限（再低就必须靠重推文字色救了）。
     */
    public static final float MIN_SURFACE = 0.50f;
    /** 弹窗面下限：浮层要压得住底下内容，低于这个值会和页面糊在一起 */
    public static final float MIN_MODAL = 0.75f;

    /**
     * Mesh 光斑浓度上限（1.0 = 内置）。
     * <p>★ 允许超过 1 是有意的：内置那几团的 α 只有 ~.40，用户想"更花一点"就得往上走；
     * 但也不能无限 —— 再高就只剩一团糊，且文字对比度会被吃穿（体检会把读数摆出来）。
     */
    public static final float MAX_MESH = 1.5f;
    /** 光斑扩散（半径倍数）的下限 / 上限。1.0 = 内置 */
    public static final float MIN_MESH_SCALE = 0.6f;
    public static final float MAX_MESH_SCALE = 1.4f;

    /** 正文 AA 的目标对比度（带余量：光斑峰值上还要够） */
    private static final double WANT_TEXT = 4.5 * 1.22;
    /** 小字强调色（accentInk）的目标 */
    private static final double WANT_INK = 4.5 * 1.22;

    /** 底色亮度高于它 ⇒ 用深色字；否则用浅色字 */
    private static final double DARK_TEXT_MAX_LUM = 0.45;

    private ThemeOverride() {}

    /** 上次打过日志的「槽位:修订号」——`apply` 是热路径，同样的值只报一次 */
    private static String lastLogged = null;

    // ==================== 值对象 ====================

    /** 用户设的四个语义值；**null = 这一项没自定义**（保持内置） */
    public static final class V {
        public Integer accent;
        public Integer bg;
        public Float alphaBg;
        public Float alphaSurface;
        public Float alphaModal;
        public String textTone;      // "auto" / "dark"（深字）/ "light"（浅字）
        public Float meshStrength;   // 光斑浓度 0~1.5（0 = 关掉光斑）
        public Float meshScale;      // 光斑扩散 0.6~1.4（半径倍数）
        public String meshTint;      // "builtin"（内置）/ "accent"（跟随主色）

        public boolean isEmpty() {
            return accent == null && bg == null && alphaBg == null && alphaSurface == null
                    && alphaModal == null && (textTone == null || "auto".equals(textTone))
                    && meshStrength == null && meshScale == null
                    && (meshTint == null || "builtin".equals(meshTint));
        }
    }

    // ==================== 纯函数（零 Android 状态 ⇒ 可断言）====================

    /** WCAG 相对亮度（0~1） */
    public static double luminance(int color) {
        double r = srgb(Color.red(color) / 255.0);
        double g = srgb(Color.green(color) / 255.0);
        double b = srgb(Color.blue(color) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double srgb(double c) {
        return (c <= 0.03928) ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** 对比度（1~21）。**半透明的先按"叠在对方上"合成再算** —— 否则 alpha 被忽略、读数偏乐观 */
    public static double contrast(int a, int b) {
        int ob = withAlpha(b, 1f);
        int oa = withAlpha(a, 1f);
        if (Color.alpha(b) < 255) ob = composite(b, oa);
        if (Color.alpha(a) < 255) oa = composite(a, ob);
        double la = luminance(oa), lb = luminance(ob);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /**
     * 把 {@code fg}（可带 alpha）叠在不透明的 {@code bg} 上，返回不透明结果。
     *
     * <p>★ 为什么必须有它：玻璃态的卡片是**半透明**的，文字实际落在"卡片叠底色"上，
     * 而不是落在卡片色本身。用户把卡片透明度拉低时，真正的对比度是**合成之后**才成立的
     * —— 拿卡片色直接算会偏乐观，而"体检"的全部意义就是量准。
     */
    public static int composite(int fg, int bg) {
        int a = Color.alpha(fg);
        if (a >= 255) return withAlpha(fg, 1f);
        if (a <= 0) return withAlpha(bg, 1f);
        float f = a / 255f;
        int r = (int) (Color.red(fg) * f + Color.red(bg) * (1 - f));
        int g = (int) (Color.green(fg) * f + Color.green(bg) * (1 - f));
        int b = (int) (Color.blue(fg) * f + Color.blue(bg) * (1 - f));
        return Color.argb(255, clamp255(r), clamp255(g), clamp255(b));
    }

    /** 按比例混色（f=0 取 a，f=1 取 b） */
    public static int mix(int a, int b, float f) {
        if (f <= 0f) return a;
        if (f >= 1f) return b;
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f);
        return Color.argb(255, clamp255(r), clamp255(g), clamp255(bl));
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** 只换 alpha，**保留 RGB**（玻璃的 tint 颜色是实测定下来的，不许被用户改） */
    public static int withAlpha(int color, float a) {
        float f = a < 0f ? 0f : (a > 1f ? 1f : a);
        return (color & 0x00FFFFFF) | (((int) (f * 255f + 0.5f)) << 24);
    }

    public static float alphaOf(int color) { return Color.alpha(color) / 255f; }

    /** 这个底色该配深字还是浅字 */
    public static boolean preferDarkText(int bg) {
        return luminance(bg) > DARK_TEXT_MAX_LUM;
    }

    /**
     * 把一个颜色往"更暗 / 更亮"推，直到与底色达到目标对比度（最多 12 步）。
     * <p>用于 accentInk（小字强调色）—— accent 本身**不动**（大按钮上它是主色，改了就不是用户选的那个色了）。
     *
     * <p>★ 两个方向都试、取更好的那个：底色很艳（例如亮红 #C81E1E）时，"往暗推"到纯黑也只有
     * 3.66:1，而"往亮推"能到 4.37:1 —— 只按"底色亮暗"选一个方向会白丢一档对比度
     * （实测：自定义底色 #C81E1E + 默认蓝主色时，单方向 4.37、双方向取优也是它，
     *  但换别的底色组合时差别更明显）。
     */
    public static int ensureContrast(int color, int bg, double want) {
        int best = color;
        double bestC = contrast(color, bg);
        for (int dir = 0; dir < 2; dir++) {
            int target = (dir == 0) ? 0xFF000000 : 0xFFFFFFFF;
            int c = color;
            for (int i = 0; i < 12 && contrast(c, bg) < want; i++) {
                c = mix(c, target, 0.14f);
                double k = contrast(c, bg);
                if (k > bestC) { bestC = k; best = c; }
            }
        }
        return best;
    }

    // ==================== 解析 / 序列化 ====================

    public static V parse(JSONObject o) {
        V v = new V();
        if (o == null) return v;
        v.accent = colorOrNull(o.optString("accent", null));
        v.bg = colorOrNull(o.optString("bg", null));
        JSONObject a = o.optJSONObject(N_ALPHA);
        if (a != null) {
            if (a.has("bg")) v.alphaBg = (float) a.optDouble("bg", 1.0);
            if (a.has("surface")) v.alphaSurface = (float) a.optDouble("surface", 0.7);
            if (a.has("modal")) v.alphaModal = (float) a.optDouble("modal", 0.94);
        }
        String tone = o.optString("textTone", "auto");
        v.textTone = ("dark".equals(tone) || "light".equals(tone)) ? tone : "auto";
        JSONObject m = o.optJSONObject(N_MESH);
        if (m != null) {
            if (m.has("strength")) v.meshStrength = (float) m.optDouble("strength", 1.0);
            if (m.has("scale")) v.meshScale = (float) m.optDouble("scale", 1.0);
            v.meshTint = "accent".equals(m.optString("tint", "builtin")) ? "accent" : "builtin";
        }
        return v;
    }

    /** {@code #RRGGBB} / {@code #AARRGGBB} → 色值；坏串返回 null（**绝不因为一行脏数据让界面变色**） */
    public static Integer colorOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() == 0) return null;
        if (t.charAt(0) != '#') t = "#" + t;
        try {
            return Color.parseColor(t.length() == 7 || t.length() == 9 ? t : t);
        } catch (Throwable ignore) {
            return null;
        }
    }

    public static String hex(int color) {
        return String.format(java.util.Locale.US, "#%06X", color & 0x00FFFFFF);
    }

    public static JSONObject toJson(V v) {
        JSONObject o = new JSONObject();
        try {
            o.put("version", 1);
            if (v.accent != null) o.put("accent", hex(v.accent));
            if (v.bg != null) o.put("bg", hex(v.bg));
            JSONObject a = new JSONObject();
            if (v.alphaBg != null) a.put("bg", round2(v.alphaBg));
            if (v.alphaSurface != null) a.put("surface", round2(clampA(v.alphaSurface, MIN_SURFACE, 1f)));
            if (v.alphaModal != null) a.put("modal", round2(clampA(v.alphaModal, MIN_MODAL, 1f)));
            if (a.length() > 0) o.put(N_ALPHA, a);
            o.put("textTone", v.textTone == null ? "auto" : v.textTone);
            JSONObject m = new JSONObject();
            if (v.meshStrength != null) m.put("strength", round2(clampA(v.meshStrength, 0f, MAX_MESH)));
            if (v.meshScale != null) m.put("scale", round2(clampA(v.meshScale, MIN_MESH_SCALE, MAX_MESH_SCALE)));
            if ("accent".equals(v.meshTint)) m.put("tint", "accent");
            if (m.length() > 0) o.put(N_MESH, m);
        } catch (Throwable ignore) {}
        return o;
    }

    private static double round2(float f) { return Math.round(f * 100.0) / 100.0; }

    private static float clampA(float f, float lo, float hi) {
        return f < lo ? lo : (f > hi ? hi : f);
    }

    // ==================== 落盘（明暗各一套） ====================

    /** 槽位名：当前引擎×明暗。非玻璃态返回 null（本功能只服务玻璃态） */
    public static String slot(Context c) {
        if (Tokens.skin(c) != Tokens.SKIN_GLASS) return null;
        return (Tokens.theme(c) == Tokens.THEME_DARK) ? "glass_dark" : "glass_light";
    }

    public static File fileFor(Context c) {
        String s = slot(c);
        if (s == null) return null;
        return fileIn(c.getFilesDir(), s);
    }

    /** {@link #fileFor} 的 Context-free 版本（离线断言用） */
    static File fileIn(File filesDir, String slot) {
        File d = new File(filesDir, DIR);
        if (!d.exists()) d.mkdirs();
        return new File(d, slot + ".json");
    }

    public static V load(Context c) {
        File f = fileFor(c);
        if (f == null || !f.isFile()) return new V();
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String s = new String(bo.toByteArray(), "UTF-8").trim();
            if (s.length() == 0) return new V();
            return parse(new JSONObject(s));
        } catch (Throwable t) {
            return new V();       // 读坏了一律当"没自定义"，绝不让界面花掉
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
        }
    }

    public static boolean save(Context c, V v) {
        File f = fileFor(c);
        if (f == null) return false;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(toJson(v).toString().getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) {}
        }
    }

    /** 恢复**当前这一套**（明或暗）的默认 */
    public static boolean clear(Context c) {
        File f = fileFor(c);
        return f == null || !f.exists() || f.delete();
    }

    public static boolean isCustom(Context c) {
        File f = fileFor(c);
        return f != null && f.isFile();
    }

    /**
     * 覆盖层的**修订号**（写盘时间取模）。
     * <p>★ {@code MainActivity.uiT()} 按"引擎×明暗"缓存令牌集 —— 改完颜色若不进缓存 key，
     * 整页还会用旧的那套 Set（表现成"改了没反应"）。
     */
    public static int revision(Context c) {
        try {
            File f = fileFor(c);
            return (f == null || !f.exists()) ? 0 : (int) (f.lastModified() % 1000000);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    // ==================== 注入点：套用到令牌集 ====================

    /**
     * 把用户的覆盖套到内置令牌集上。**未自定义时逐字段零变化**（这是最重要的一条承诺）。
     *
     * @return 是否真的套用了（设备断言/调试用）
     */
    public static boolean apply(Tokens.Set t, Context c) {
        try {
            if (t == null || c == null) return false;
            if (Tokens.skin(c) != Tokens.SKIN_GLASS) return false;   // 只做玻璃态
            V v = load(c);
            if (v == null || v.isEmpty()) return false;
            boolean dark = Tokens.theme(c) == Tokens.THEME_DARK;
            Tokens.Set base = Tokens.of(Tokens.SKIN_GLASS, dark ? Tokens.THEME_DARK
                                                                : Tokens.THEME_LIGHT);

            // ① 底色（可带透明度）
            if (v.bg != null) t.bg = v.bg;
            if (v.alphaBg != null) t.bg = withAlpha(t.bg, clampA(v.alphaBg, 0f, 1f));

            // ② 玻璃浓度：只改 alpha，**tint 的 RGB 保持内置**（白 tint / 深 tint 是实测定下来的）
            float baseSurf = alphaOf(base.surface);
            float aSurf = (v.alphaSurface == null) ? baseSurf
                    : clampA(v.alphaSurface, MIN_SURFACE, 1f);
            float k = (baseSurf <= 0f) ? 1f : (aSurf / baseSurf);
            t.surface = withAlpha(base.surface, aSurf);
            t.surfaceSunken = withAlpha(base.surfaceSunken, clampA(alphaOf(base.surfaceSunken) * k, 0f, 1f));
            t.glassInner = withAlpha(base.glassInner, clampA(alphaOf(base.glassInner) * k, 0f, 1f));
            t.glassOuter = withAlpha(base.glassOuter, clampA(alphaOf(base.glassOuter) * k, 0f, 1f));
            t.glassBorder = withAlpha(base.glassBorder, clampA(alphaOf(base.glassBorder) * k, 0f, 1f));

            // ③ 弹窗/浮层
            float baseModal = alphaOf(base.modal);
            float aModal = (v.alphaModal == null) ? baseModal
                    : clampA(v.alphaModal, MIN_MODAL, 1f);
            t.modal = withAlpha(base.modal, aModal);

            // ④ 文字：自动重推 = 在**两套已调好的**配色的文字色之间切换（不发明新颜色）
            boolean darkText;
            if ("dark".equals(v.textTone)) darkText = true;
            else if ("light".equals(v.textTone)) darkText = false;
            else darkText = preferDarkText(t.bg);
            Tokens.Set tone = Tokens.of(Tokens.SKIN_GLASS,
                    darkText ? Tokens.THEME_LIGHT : Tokens.THEME_DARK);
            t.text1 = tone.text1;
            t.text2 = tone.text2;
            t.text3 = tone.text3;

            // ⑤ 主色调：accent 用用户选的；**只有小字用的 accentInk 做对比度兜底**
            int accent = (v.accent != null) ? v.accent : t.accent;
            t.accent = accent;
            t.accentInk = ensureContrast(accent, opaqueUnder(t), WANT_INK);
            t.accentSoft = withAlpha(accent, alphaOf(base.accentSoft));

            // ⑥ 描边/发丝线跟着底色明暗走（否则深底配深描边 = 看不见）
            t.hairline = darkText ? base.hairline : Tokens.of(Tokens.SKIN_GLASS,
                    Tokens.THEME_DARK).hairline;
            t.edge = darkText ? base.edge : Tokens.of(Tokens.SKIN_GLASS,
                    Tokens.THEME_DARK).edge;

            // ⑦ Mesh 光斑：浓度 / 扩散 / 光斑色跟随主色（轮次55）。
            //    ★ 三项都没设时 meshOf **原样返回内置数组**（零变化承诺的 Mesh 那一半）；
            //    ★ 放在 accent 之后：跟随主色要用最终生效的那个 accent。
            t.mesh = meshOf(base.mesh, v, accent);

            // ★ 设备侧唯一证据：报出**实际生效**的值（含"文字自动重推成了深/浅"与最终透明度）。
            //   只在"槽位或修订号变了"时打一次 —— apply 是每个 Tokens.of 都会走的**热路径**。
            try {
                String key = slot(c) + ":" + revision(c);
                if (!key.equals(lastLogged)) {
                    lastLogged = key;
                    android.util.Log.i("DSTheme", "自定义配色生效 slot=" + slot(c)
                            + " bg=#" + Integer.toHexString(t.bg)
                            + " surface=#" + Integer.toHexString(t.surface)
                            + " modal=#" + Integer.toHexString(t.modal)
                            + " accent=#" + Integer.toHexString(t.accent)
                            + " text1=#" + Integer.toHexString(t.text1)
                            + " 文字=" + (darkText ? "深" : "浅")
                            + " mesh=" + meshTag(v)
                            + " accentInk对比=" + String.format(java.util.Locale.US, "%.2f",
                                    contrast(t.accentInk, opaqueUnder(t))));
                }
            } catch (Throwable ignore2) {}
            return true;
        } catch (Throwable ignore) {
            return false;         // 覆盖失败只该"看起来是默认配色"，绝不能让界面画不出来
        }
    }

    /** 文字的"实际落点"底色：底色本身是实色时就是它（透明底色的情况按不透明近似） */
    private static int opaqueUnder(Tokens.Set t) {
        return withAlpha(t.bg, 1f);
    }

    // ==================== P1：从背景图取色（轮次51）====================

    /**
     * 从背景图里挑候选配色 —— 返回 {@code [主色, 平均色, 亮色, 暗色]}（都不透明）。
     *
     * <h3>为什么给"候选"而不是"一个答案"</h3>
     * 用户的诉求是「自定义界面ui颜色 **用来搭配不同的背景**」—— 那就不该替他拍板。
     * 四种取法各有用途：**主色**贴近画面主体、**平均色**最稳（不会被某个角落带跑）、
     * **亮色/暗色**成对给出，方便"底色取暗、主色调用亮"这类搭配。
     *
     * <p>★ 纯函数（只吃像素数组）⇒ 离线断言可以喂**已知图**直接验，不必上设备。
     */
    public static int[] paletteFrom(int[] px) {
        int[] out = { 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080 };
        if (px == null || px.length == 0) return out;

        final int BUCKETS = 4096;                       // 4bit/通道粗量化
        int[] cnt = new int[BUCKETS];
        int[] sumR = new int[BUCKETS], sumG = new int[BUCKETS], sumB = new int[BUCKETS];
        int[] lumCnt = new int[64];
        long[] lumR = new long[64], lumG = new long[64], lumB = new long[64];
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int c : px) {
            if (Color.alpha(c) < 8) continue;           // 全透明像素不参与
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            sr += r; sg += g; sb += b; n++;
            int k = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
            cnt[k]++; sumR[k] += r; sumG[k] += g; sumB[k] += b;
            int lb = (int) (luminance(c) * 63.0);
            if (lb < 0) lb = 0; else if (lb > 63) lb = 63;
            lumCnt[lb]++; lumR[lb] += r; lumG[lb] += g; lumB[lb] += b;
        }
        if (n == 0) return out;

        out[1] = Color.argb(255, (int) (sr / n), (int) (sg / n), (int) (sb / n));

        int best = 0;
        for (int k = 1; k < BUCKETS; k++) {
            if (cnt[k] > cnt[best]) best = k;
        }
        out[0] = (cnt[best] > 0)
                ? Color.argb(255, sumR[best] / cnt[best], sumG[best] / cnt[best], sumB[best] / cnt[best])
                : out[1];

        int want = Math.max(1, n / 8);                  // 两端各取 ~1/8（O(n)，不排序）
        out[2] = endAverage(lumCnt, lumR, lumG, lumB, want, true);
        out[3] = endAverage(lumCnt, lumR, lumG, lumB, want, false);
        return out;
    }

    /** 从亮度直方图的**亮端**（bright=true）或**暗端**累计 want 个像素求平均 */
    private static int endAverage(int[] cnt, long[] sr, long[] sg, long[] sb,
                                  int want, boolean bright) {
        long r = 0, g = 0, b = 0;
        int got = 0;
        for (int i = 0; i < 64 && got < want; i++) {
            int idx = bright ? (63 - i) : i;
            if (cnt[idx] == 0) continue;
            int take = Math.min(cnt[idx], want - got);
            r += sr[idx] * take / cnt[idx];
            g += sg[idx] * take / cnt[idx];
            b += sb[idx] * take / cnt[idx];
            got += take;
        }
        if (got == 0) return 0xFF808080;
        return Color.argb(255, (int) (r / got), (int) (g / got), (int) (b / got));
    }

    /**
     * 读**当前图片背景**并取色；没有背景图 / 解码失败返回 null。
     *
     * <p>★ 先按 {@code inSampleSize} 缩到 ≤128px 再取像素：用户原图可能几千万像素，
     * 全量 {@code getPixels} 会明显卡一下，而取色只需要"大致是什么颜色"。
     */
    public static int[] paletteFromBackgroundImage(Context c) {
        try {
            java.io.File f = Prefs.imageBgFile(c);
            if (f == null || !f.isFile()) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int max = Math.max(o.outWidth, o.outHeight);
            int sample = 1;
            while (max / sample > 128) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(
                    f.getAbsolutePath(), o);
            if (bm == null) return null;
            int w = bm.getWidth(), h = bm.getHeight();
            if (w <= 0 || h <= 0) return null;
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            try { bm.recycle(); } catch (Throwable ignore) {}
            return paletteFrom(px);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== P2：Mesh 光斑可调（轮次55）====================

    /**
     * 按用户的覆盖算出这一套的 Mesh 光斑（纯函数）。
     *
     * <p>只动三样：**α 乘浓度**、**半径乘扩散**、**颜色可选跟随主色**。
     * 位置（cx/cy）与光斑**个数**都不动 —— 那属于"逐光斑可调"，与"分组调、
     * 不暴露每个令牌"的既有边界冲突（也更容易调出糊成一团的结果）。
     *
     * <p>★ 三项都没设时**原样返回内置数组**（零变化承诺的 Mesh 那一半）。
     */
    public static Tokens.Spot[] meshOf(Tokens.Spot[] base, V v, int accent) {
        if (base == null || base.length == 0) return base;
        float s = (v == null || v.meshStrength == null) ? 1f
                : clampA(v.meshStrength, 0f, MAX_MESH);
        float sc = (v == null || v.meshScale == null) ? 1f
                : clampA(v.meshScale, MIN_MESH_SCALE, MAX_MESH_SCALE);
        boolean follow = (v != null) && "accent".equals(v.meshTint);
        if (s == 1f && sc == 1f && !follow) return base;
        Tokens.Spot[] out = new Tokens.Spot[base.length];
        for (int i = 0; i < base.length; i++) {
            Tokens.Spot b = base[i];
            int col = follow ? retint(b.color, accent) : b.color;
            out[i] = new Tokens.Spot(b.cx, b.cy, b.r * sc,
                    withAlpha(col, clampA(alphaOf(col) * s, 0f, 1f)));
        }
        return out;
    }

    /**
     * 把一团光斑的颜色换成"主色的色相"。
     *
     * <p>★ 不是把每团都染成主色本身：那 4~5 团会变成**同一个色号**、Mesh 立刻塌成一块平板。
     * 取主色的**色相 + 饱和度**、留光斑自己的**明度** —— 既跟着主色走，
     * 又保住"几团深浅不同的光"这层结构。
     */
    public static int retint(int spot, int accent) {
        float[] a = new float[3];
        android.graphics.Color.colorToHSV(accent, a);
        float[] s = new float[3];
        android.graphics.Color.colorToHSV(spot, s);
        s[0] = a[0];
        s[1] = a[1];
        return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(spot), s);
    }

    /**
     * 光斑背景的**极值色** {@code [最暗, 最亮]} —— 文字真正可能落到的最坏底色。
     *
     * <h3>★ 为什么必须有它（体检的"口径"问题）</h3>
     * 轮次54 的体检量的是"卡片叠**底色**"，而卡片底下其实是**底色 + 几团光斑**。
     * `Tokens` 里那条注释就是证据：`text3` 从 3.99:1 调到 4.82:1，是因为
     * "实测分组标题的背景正好落在 **Mesh 较亮的那一块**（#AEC9EC）"。
     * 用户一旦能把光斑调浓，只量底色就会**假绿**：读数达标、实际看不清。
     * 这就是路线图里"体检是光斑可调的安全网"那句话的落点。
     *
     * <h3>算法（与 {@code UiKit.MeshDrawable} 同一套数学）</h3>
     * 底色铺满，再按顺序把每团光斑用 RadialGradient 的线性衰减叠上去
     * （{@code α·(1−d/r)}，{@code d ≥ r} 处为 0），在一个 N×N 网格上取最暗/最亮的像素。
     * <p>★ 网格按**正方形**近似（真实页面 w&lt;h，而渲染时半径按 {@code max(w,h)} 算）——
     * 这里要的是"最坏能坏到哪"，极值对宽高比不敏感，不做像素级还原。
     * ★ 纯函数（只吃令牌集）⇒ 离线可断言、不碰 Context。
     */
    public static int[] meshExtremes(Tokens.Set t) {
        int bg = withAlpha((t == null) ? 0xFFFFFFFF : t.bg, 1f);
        int[] out = { bg, bg };
        if (t == null || t.mesh == null || t.mesh.length == 0) return out;
        final int N = 48;
        double loL = luminance(bg), hiL = loL;
        for (int iy = 0; iy < N; iy++) {
            for (int ix = 0; ix < N; ix++) {
                float px = (ix + 0.5f) / N, py = (iy + 0.5f) / N;
                int c = bg;
                for (Tokens.Spot s : t.mesh) {
                    float dx = px - s.cx, dy = py - s.cy;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float rad = (s.r < 1e-4f) ? 1e-4f : s.r;
                    if (d >= rad) continue;
                    c = composite(withAlpha(s.color, alphaOf(s.color) * (1f - d / rad)), c);
                }
                double l = luminance(c);
                if (l < loL) { loL = l; out[0] = c; }
                if (l > hiL) { hiL = l; out[1] = c; }
            }
        }
        return out;
    }

    /**
     * 这一套 Mesh 覆盖的标签，形如 {@code 1.40x1.00/accent}（没覆盖时 {@code builtin}）。
     * <p>★ 它是**设备侧的唯一证据**：`DSTheme` 日志带上它，设备断言才能确认
     * "文件里写的浓度真的生效了"（界面文字只能证明界面，证明不了渲染）。
     */
    public static String meshTag(V v) {
        if (v == null || v.isEmpty()) return "builtin";
        float s = (v.meshStrength == null) ? 1f : v.meshStrength;
        float sc = (v.meshScale == null) ? 1f : v.meshScale;
        return String.format(java.util.Locale.US, "%.2fx%.2f/%s", s, sc,
                "accent".equals(v.meshTint) ? "accent" : "builtin");
    }

    // ==================== P2：配色体检读数（轮次54）====================

    /** 一条体检结果 */
    public static final class Audit {
        public final String name;
        public final double ratio;
        public final boolean pass;
        public final double want;
        Audit(String name, double ratio, double want) {
            this.name = name;
            this.ratio = ratio;
            this.want = want;
            this.pass = ratio >= want;
        }
    }

    /**
     * **配色体检**：把当前令牌下每一处"文字落在什么底上"的真实对比度算出来。
     *
     * <p>★ 为什么值得做：本工程的每个内置色值背后都有一串手调对比度的注释
     * （`Tokens` 里写着"原值 3.99:1，改后 4.82:1"这种）。用户一旦能自定义，
     * 那些手调的保证就**不再自动成立**了 —— 与其在文档里叮嘱，不如**把读数摆到他眼前**。
     *
     * <p>★ 量的口径（轮次55 升级）：文字落在"**卡片叠光斑极值**"上 —— 玻璃卡片是半透明的，
     * 所以先与**最暗/最亮的光斑**各自合成出真实底色，再取两者里**更差**的那个读数
     * （深色字怕亮底、浅色字怕暗底，两个方向都得量）。这正是"调低卡片透明度"
     * 与"把光斑调浓"时最容易被忽略、也最容易翻车的地方。
     *
     * <p>阈值照 WCAG AA：正文/小字 **4.5:1**；大字号与 UI 部件 **3:1**。
     */
    public static java.util.List<Audit> audit(Tokens.Set t) {
        java.util.List<Audit> out = new java.util.ArrayList<Audit>();
        if (t == null) return out;
        int[] ex = meshExtremes(t);
        final int underDark = composite(t.surface, ex[0]);      // 最暗的那团光斑底下
        final int underBright = composite(t.surface, ex[1]);    // 最亮的那团光斑底下
        out.add(new Audit("正文", worst(t.text1, underDark, underBright), 4.5));
        out.add(new Audit("次级文字", worst(t.text2, underDark, underBright), 4.5));
        out.add(new Audit("三级提示 12sp", worst(t.text3, underDark, underBright), 4.5));
        out.add(new Audit("小字强调色", worst(t.accentInk, underDark, underBright), 4.5));
        out.add(new Audit("主色（按钮/大字号）", worst(t.accent, underDark, underBright), 3.0));
        out.add(new Audit("危险色", worst(t.danger, underDark, underBright), 3.0));
        return out;
    }

    /** 前景色在"最暗底 / 最亮底"上的**更差**读数（体检要报最坏情况，不是平均值） */
    public static double worst(int fg, int underDark, int underBright) {
        return Math.min(contrast(fg, underDark), contrast(fg, underBright));
    }

    /** 体检里的**最低**对比度（设置页那行读数用） */
    public static double minRatio(Tokens.Set t) {
        double min = Double.MAX_VALUE;
        for (Audit a : audit(t)) min = Math.min(min, a.ratio);
        return (min == Double.MAX_VALUE) ? 0 : min;
    }

    /** 体检里不达标的条数 */
    public static int failCount(Tokens.Set t) {
        int n = 0;
        for (Audit a : audit(t)) if (!a.pass) n++;
        return n;
    }

    /** 体检读数 → "4.9:1 ✓" / "1.0:1 ⚠ 2 项不达标"（设置页行文案） */
    public static String auditSummary(Tokens.Set t) {
        int bad = failCount(t);
        String r = String.format(java.util.Locale.US, "%.1f:1", minRatio(t));
        return (bad == 0) ? (r + " \u2713") : (r + " \u26A0 " + bad + " 项不达标");
    }

    /** 体检结果 → 多行文本（弹窗里逐条列） */
    public static String auditReport(Tokens.Set t) {
        StringBuilder sb = new StringBuilder();
        for (Audit a : audit(t)) {
            sb.append(a.pass ? "\u2713 " : "\u26A0 ")
              .append(a.name).append("　")
              .append(String.format(java.util.Locale.US, "%.2f:1", a.ratio))
              .append("（需 ").append(a.want >= 4.5 ? "4.5" : "3.0").append(":1）\n");
        }
        int bad = failCount(t);
        sb.append(bad == 0 ? "\n全部达标（量的是“卡片叠光斑极值”，最坏的那一处也够）。"
                : "\n有 " + bad + " 项不达标 —— 建议把卡片透明度调高、把 Mesh 光斑调淡，"
                + "或换个更浅/更深的底色（文字颜色会自动跟着重推）。");
        return sb.toString();
    }


    // ==================== P1：配色方案的导出 / 导入（轮次53）====================

    /**
     * 从一段文本解析配色方案；**坏文件返回 null**（导入的第一道闸）。
     *
     * <p>★ 为什么单独一个入口、而不是直接用 {@link #parse}：导入的是**别人的文件**。
     * {@code parse} 的语义是"能认多少认多少"（本地读坏了也不该让界面花掉），
     * 而导入必须能**明确告诉用户"这不是配色方案"**，不能默默套一个空方案上去。
     */
    public static V fromJsonText(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.length() == 0 || s.length() > 64 * 1024) return null;   // 配色方案不该有几十 KB
        try {
            V v = parse(new JSONObject(s));
            return v.isEmpty() ? null : v;      // 一项都没认出来 ⇒ 不是配色方案
        } catch (Throwable t) {
            return null;
        }
    }

    /** 导入前的"里面是什么"摘要（让用户看清再决定，而不是盲套） */
    public static String describe(V v) {
        if (v == null || v.isEmpty()) return "（空方案）";
        StringBuilder sb = new StringBuilder();
        if (v.accent != null) sb.append("主色调 ").append(hex(v.accent));
        if (v.bg != null) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("背景底色 ").append(hex(v.bg));
        }
        if (v.alphaBg != null || v.alphaSurface != null || v.alphaModal != null) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("透明度：底色 ").append(pct(v.alphaBg, 1f))
              .append(" · 卡片 ").append(pct(v.alphaSurface, 0.7f))
              .append(" · 弹窗 ").append(pct(v.alphaModal, 0.94f));
        }
        String tone = (v.textTone == null) ? "auto" : v.textTone;
        if (!"auto".equals(tone)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("文字：").append("light".equals(tone) ? "强制浅色" : "强制深色");
        }
        if (v.meshStrength != null || v.meshScale != null || "accent".equals(v.meshTint)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Mesh 光斑：浓度 ")
              .append(Math.round((v.meshStrength == null ? 1f : v.meshStrength) * 100f)).append("%")
              .append(" · 扩散 ")
              .append(Math.round((v.meshScale == null ? 1f : v.meshScale) * 100f)).append("%")
              .append("accent".equals(v.meshTint) ? " · 跟随主色" : "");
        }
        return sb.toString();
    }

    private static String pct(Float f, float dflt) {
        float x = (f == null) ? dflt : f;
        return Math.round(x * 100f) + "%";
    }

    /** 导出时的文件名建议（不含路径） */
    public static String suggestedFileName(Context c) {
        String s = slot(c);
        return "DeepSeekPet配色-" + (s == null ? "glass" : s) + ".json";
    }

    /**
     * 当前这一套的 JSON 文本（导出用）。
     *
     * <p>★ 导出的是**用户真正改过的项**（{@link #toJson} 只写非 null 的字段）⇒
     * 别人导入后会**继承他那边的内置配色**、只覆盖你改过的部分 ——
     * 这正是分享时想要的：不会把"我的整套深色玻璃"强加给用浅色的朋友。
     */
    public static String exportText(Context c) {
        return toJson(load(c)).toString();
    }
}
