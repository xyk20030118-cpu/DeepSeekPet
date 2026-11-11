package com.deepseek.pet;

/**
 * 挂边探头（"只露个头在屏幕边缘"）的**姿态纯计算** —— <b>零 Android 依赖</b>
 * （照 {@link ContentInset} / {@link FpsMath} 的手法写，所以能在 {@code build/fpstest} 里做真断言）。
 *
 * <h3>它解决什么</h3>
 * 桌宠贴到左右边缘后，有两种"藏起来"的观感可选：
 * <ul>
 *   <li><b>压扁竖条</b>（{@code PetDockState.COLLAPSED} 那一套）：形象被压成细条，
 *       本质是"收进边里"，看不出它是个活物；</li>
 *   <li><b>挂边探头</b>（本类）：身体藏出屏幕外，**只留脑袋和一点肩**贴在边上，
 *       还会呼吸、隔一会儿再探一下 —— 像躲门后偷看。</li>
 * </ul>
 * 本类只算"姿态"：露出多少、歪多少、缩放多少。**位移与窗口钳制在调用方**，
 * 渲染变换也由 {@code PetView} 施加（本类不碰 View / 不碰 WindowManager）。
 *
 * <h3>★ 三条原创口径（为什么和"整只斜挂"不是一回事）</h3>
 * <ol>
 *   <li><b>露头量锚在"头肩高度"，不锚在"投影包围盒"。</b>
 *       若按整只角色的可见包围盒去算露出比例，那么动作一大（伸手、甩尾、放烟花）
 *       包围盒就变宽 ⇒ 同一份姿态会把身体多拖出来一截，观感是"越演越往外跑"。
 *       本类只认 <b>身体框宽度</b>（{@link ContentInset} 同一套口径），
 *       与当前播的是哪段动画<b>无关</b>：待机和伸手，露出来的量一模一样。</li>
 *   <li><b>角度只到十来度，而且是"侧倾"不是"斜挂"。</b>
 *       大角度旋转会让脚/尾巴翘到屏幕上空，看着像贴纸被歪着粘在边上；
 *       真正从墙后探头的人，肩是贴着墙的、只有头向外侧偏。
 *       所以旋转轴取在<b>身体高度 34% 处（肩线）</b>，角度 {0, 3.5°, 12°}三档，
 *       并叠 $\pm$1.4° 的呼吸摆动。</li>
 *   <li><b>挂住之后不是静止贴图。</b>两件事让它"活着"：呼吸（2.6 s 周期，横向 $\pm$1.2 dp +
 *       角度 $\pm$1.4°），以及<b>小探头</b>（每 7.2–11.8 s 再探出去一点、马上缩回）。
 *       小探头的间隔用<b>种子确定的伪随机</b>（{@link #nextNudgeDelayMs}），
 *       不做 {@code Math.random()} —— 同一颗种子永远同一串间隔，断言才钉得住。</li>
 * </ol>
 *
 * <h3>七个阶段（时间轴）</h3>
 * <pre>
 *   OFF ──贴边──▶ SQUEEZE(挤一下) ──▶ PROBE(探出去，带过冲) ──▶ PERCH(挂住，呼吸)
 *                    ▲                                            │
 *                    │                              ┌─────────────┼──────────────┐
 *                    │                              ▼             ▼              ▼
 *                    │                          NUDGE(再探)   LIFT(被点→抬头)  WITHDRAW(缩回屏内)
 *                    │                              │             │              │
 *                    └──────────────────────────────┴──回到 PERCH┴──────────────┴──▶ OFF
 * </pre>
 * {@code WITHDRAW} 接受 {@code fromKeep}：从任意露出量起步都<b>不跳变</b>
 * （用户在半截处拖动／点退出时，不会"啪"地一下弹回原位）。
 *
 * <h3>不变量（{@code build/fpstest} 的 [35] 段钉着）</h3>
 * <ul>
 *   <li>{@code keep} 恒在 (0,1]；{@code PROBE} 的谷底必须&lt;{@link #PEER_KEEP}（过冲真实存在），
 *       收尾必须等于 {@link #PEER_KEEP}；</li>
 *   <li>{@code PERCH} 全程 {@code keep} 恒等于 {@link #PEER_KEEP}（呼吸不许改变露头量）；</li>
 *   <li>角度量级不超过 {@code LEAN_PEER_DEG + LEAN_NUDGE_EXTRA_DEG + BREATH_DEG}；</li>
 *   <li>左右边只是符号翻转（{@link #leanSigned} / {@link #peekedX}），几何不重写第二遍；</li>
 *   <li>垃圾输入（负时刻、未知阶段）一律退化成 {@link #OFF} 姿态，绝不抛异常。</li>
 * </ul>
 */
public final class PeekPose {

    private PeekPose() {}

    // ==================== 阶段 ====================

    /** 正常态（不挂边） */
    public static final int OFF = 0;
    /** 贴边挤压：原地"挤一下"做准备，<b>此时还不许往外探</b> */
    public static final int SQUEEZE = 1;
    /** 探出去：露出量 1 → {@link #PEER_KEEP}，中途带过冲 */
    public static final int PROBE = 2;
    /** 挂住：只露头肩，呼吸摆动 */
    public static final int PERCH = 3;
    /** 小探头：隔一段时间再探出去一点又缩回 */
    public static final int NUDGE = 4;
    /** 被点：抬头站直（露出 {@link #LIFT_KEEP}），停一会儿再自己缩回挂边 */
    public static final int LIFT = 5;
    /** 缩回屏内：露出量 → 1，角度 → 0（从任意 {@code fromKeep} 起步） */
    public static final int WITHDRAW = 6;
    /**
     * 回落到常驻挂边位（从任意 {@code fromKeep} 起步，朝 {@link #PEER_KEEP} 收敛）。
     *
     * <p>★ 拖动松手时用：用户把桌宠往外拖到一半又松手 ⇒ 露头量从当前值**平滑收回 0.62**，
     * 而不是"啪"地跳回常驻位。也是"拖一下没拉出来就自动挂回去"的那一段。
     */
    public static final int SETTLE = 7;

    // ==================== 时长（ms） ====================

    public static final int SQUEEZE_MS = 200;
    public static final int PROBE_MS = 340;
    public static final int WITHDRAW_MS = 280;
    public static final int LIFT_MS = 170;
    public static final int NUDGE_MS = 260;
    /** 拖动松手回落到常驻位 */
    public static final int SETTLE_MS = 260;
    /** 抬头后自己缩回挂边的时间（用户没再动手的话） */
    public static final long LIFT_HOLD_MS = 4500L;
    /** 呼吸周期 */
    public static final int BREATH_PERIOD_MS = 2600;
    /** 小探头间隔区间（含端点） */
    public static final long NUDGE_MIN_MS = 7200L;
    public static final long NUDGE_MAX_MS = 11800L;

    // ==================== 姿态常量 ====================

    /** 挂住时<b>留在屏内</b>的身体宽度比例（0.62 ≈ 头 + 一点肩） */
    public static final float PEER_KEEP = 0.62f;
    /** 探出时的过冲量（先多露 7%，再收回到 {@link #PEER_KEEP}） */
    public static final float PROBE_OVERSHOOT = 0.07f;
    /** 小探头再往外探的深度 */
    public static final float NUDGE_DEPTH = 0.10f;
    /** 被点抬头后的露出比例 */
    public static final float LIFT_KEEP = 0.90f;

    /** 挂住时的侧倾角（度，量级） */
    public static final float LEAN_PEER_DEG = 16f;
    /** 挤压阶段的预备角（度，量级） */
    public static final float LEAN_SQUEEZE_DEG = 3.5f;
    /** 小探头时额外多歪一点（度） */
    public static final float LEAN_NUDGE_EXTRA_DEG = 3f;
    /** 呼吸的角度摆幅（度） */
    public static final float BREATH_DEG = 1.4f;
    /** 呼吸的横向摆幅（dp，调用方按密度换算成 px） */
    public static final float BREATH_DP = 1.2f;

    /** 挤压时的缩放（宽收、高拉 —— "被门缝夹了一下"的预备感） */
    public static final float SQUEEZE_SX = 0.94f;
    public static final float SQUEEZE_SY = 1.05f;
    /** 探出时的过冲缩放（宽放、高压） */
    public static final float PROBE_SX = 1.03f;
    public static final float PROBE_SY = 0.975f;

    /** 旋转轴在身体高度上的位置（0 = 头顶，1 = 脚底）：肩线取 0.34 */
    public static final float SHOULDER_FRAC = 0.34f;

    /**
     * 可调参数（用户在设置页「🪝 贴边与挂边」里改）：
     * <ul>
     *   <li>{@code peerKeep} —— 挂边时**留在屏内**的身体宽度比例（越小 = 挂得越深）；</li>
     *   <li>{@code leanDeg} —— 挂边时的侧倾角度（0 = 不歪）。</li>
     * </ul>
     * 默认值与常量一致（0.62 / 16°）。所有 {@code at(...)} 重载不带 Tuning 时都用 {@link #DEFAULT}。
     */
    public static final class Tuning {
        public final float peerKeep;
        public final float leanDeg;

        public Tuning(float peerKeep, float leanDeg) {
            this.peerKeep = peerKeep;
            this.leanDeg = leanDeg;
        }

        /** 越界一律夹回安全区间：露出 30%~95%、角度 0°~45° */
        public static Tuning sanitized(float keep, float lean) {
            float k = keep < 0.30f ? 0.30f : (keep > 0.95f ? 0.95f : keep);
            float l = lean < 0f ? 0f : (lean > 45f ? 45f : lean);
            return new Tuning(k, l);
        }

        /** 默认：0.62 露出 / 16° 侧倾 */
        public static final Tuning DEFAULT = new Tuning(PEER_KEEP, LEAN_PEER_DEG);

        @Override public String toString() {
            return "Tuning{keep=" + peerKeep + ", lean=" + leanDeg + "}";
        }
    }

    /** 贴在屏幕左边（窗口 x 越大越进屏内） */
    public static final int EDGE_LEFT = -1;
    /** 贴在屏幕右边（窗口 x 越小越进屏内） */
    public static final int EDGE_RIGHT = 1;

    // ==================== 姿态输出 ====================

    /**
     * 一帧的姿态。全部是"相对当前形象"的增量，调用方直接喂给渲染层。
     */
    public static final class Pose {
        /** 所属阶段 */
        public final int phase;
        /** 留在屏内的身体宽度比例（0~1，1 = 完全在屏内） */
        public final float keep;
        /** 侧倾角度（量级，非负；方向由调用方按边取符号） */
        public final float lean;
        public final float scaleX;
        public final float scaleY;
        /** 透明度（挂边全程 1；留给将来"半透明探头"用） */
        public final float alpha;
        /** 呼吸横向偏移（dp，调用方乘密度） */
        public final float breathDp;

        Pose(int phase, float keep, float lean, float scaleX, float scaleY,
             float alpha, float breathDp) {
            this.phase = phase;
            this.keep = keep;
            this.lean = lean;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.alpha = alpha;
            this.breathDp = breathDp;
        }

        @Override public String toString() {
            return "Pose{ph=" + phase + ", keep=" + fmt(keep) + ", lean=" + fmt(lean)
                    + ", sx=" + fmt(scaleX) + ", sy=" + fmt(scaleY) + "}";
        }

        private static String fmt(float v) {
            return String.format(java.util.Locale.US, "%.3f", v);
        }
    }

    /** 恒等姿态（正常态） */
    public static final Pose IDENTITY = new Pose(OFF, 1f, 0f, 1f, 1f, 1f, 0f);

    // ==================== 主入口 ====================

    /**
     * 取某一阶段、某一时刻的姿态。
     *
     * @param phase     {@link #OFF} … {@link #WITHDRAW}
     * @param tMs       本阶段内已过的毫秒（负数按 0，超过时长按末尾）
     * @param perchAgeMs 从进入 {@link #PERCH} 起算的毫秒（呼吸相位用；其余阶段忽略）
     */
    public static Pose at(int phase, long tMs, long perchAgeMs) {
        return at(phase, tMs, perchAgeMs, PEER_KEEP);
    }

    /**
     * 同上，但 {@link #WITHDRAW} 可以从任意露出量起步（不跳变）。
     *
     * @param fromKeep 缩回的起点；只有 {@link #WITHDRAW} 用它
     */
    public static Pose at(int phase, long tMs, long perchAgeMs, float fromKeep) {
        return at(phase, tMs, perchAgeMs, fromKeep, Tuning.DEFAULT);
    }

    /** 带可调参数（用户设的角度/深度）的版本 */
    public static Pose at(int phase, long tMs, long perchAgeMs, float fromKeep, Tuning tune) {
        final Tuning tn = (tune == null) ? Tuning.DEFAULT : tune;
        final float peer = tn.peerKeep;
        final float leanMax = tn.leanDeg;
        long t = tMs < 0 ? 0 : tMs;
        switch (phase) {
            case SQUEEZE: {
                float p = progress(t, SQUEEZE_MS);
                // 挤压脉冲：sin(πp) 起落，所以首尾都是恒等，只有中间"夹"了一下
                float pulse = (float) Math.sin(Math.PI * p);
                float sx = 1f - (1f - SQUEEZE_SX) * pulse;
                float sy = 1f + (SQUEEZE_SY - 1f) * pulse;
                return new Pose(SQUEEZE, 1f, LEAN_SQUEEZE_DEG * p, sx, sy, 1f, 0f);
            }
            case PROBE: {
                float p = progress(t, PROBE_MS);
                float e = easeOutCubic(p);
                float keep = 1f - (1f - peer) * e
                        - PROBE_OVERSHOOT * (float) Math.sin(Math.PI * p) * e;
                if (keep < 0.05f) keep = 0.05f;
                float pulse = (float) Math.sin(Math.PI * p) * e;
                float sx = 1f + (PROBE_SX - 1f) * pulse;
                float sy = 1f - (1f - PROBE_SY) * pulse;
                // ★★ 轮次78：这里**不能再乘 (leanMax / LEAN_PEER_DEG)** ——
                //   多乘一次会让"探到底"的角度超过用户设的值（30° 时算成 ≈53°），
                //   紧接着 PERCH 落回 30° ⇒ 观感是"歪过头再闪回来"（真机报障）。
                //   PROBE 的终点必须**正好**是 leanMax。
                float lean = LEAN_SQUEEZE_DEG + (leanMax - LEAN_SQUEEZE_DEG) * e;
                return new Pose(PROBE, keep, lean, sx, sy, 1f, 0f);
            }
            case PERCH: {
                // ★ keep 恒等于 PEER_KEEP：呼吸只动角度与横向，不许改变露头量
                float lean = leanMax + breathLean(perchAgeMs);
                return new Pose(PERCH, peer, lean, 1f, 1f, 1f, breathDp(perchAgeMs));
            }
            case NUDGE: {
                float p = progress(t, NUDGE_MS);
                float bump = (float) Math.sin(Math.PI * p);
                float keep = peer - NUDGE_DEPTH * bump;
                float lean = leanMax + LEAN_NUDGE_EXTRA_DEG * bump;
                return new Pose(NUDGE, keep, lean, 1f, 1f, 1f, breathDp(perchAgeMs));
            }
            case LIFT: {
                float p = progress(t, LIFT_MS);
                float e = easeOutCubic(p);
                float keep = peer + (LIFT_KEEP - peer) * e;
                float lean = leanMax * (1f - e);
                return new Pose(LIFT, keep, lean, 1f, 1f, 1f, 0f);
            }
            case WITHDRAW: {
                float p = progress(t, WITHDRAW_MS);
                float from = clamp01(fromKeep);
                if (from < peer) from = peer;   // 收缩的方向只能朝"更多露出来"
                float keep = from + (1f - from) * smooth(p);
                float lean = leanMax * (1f - smooth(p));
                return new Pose(WITHDRAW, keep, lean, 1f, 1f, 1f, 0f);
            }
            case SETTLE: {
                // ★ 从任意露出量收敛回常驻位（拖动松手用）：方向和 WITHDRAW 相反
                float p = progress(t, SETTLE_MS);
                float from = clamp01(fromKeep);
                if (from < peer) from = peer;
                float keep = from + (peer - from) * easeOutCubic(p);
                return new Pose(SETTLE, keep, leanForKeep(keep, leanMax, peer), 1f, 1f, 1f, 0f);
            }
            case OFF:
            default:
                return IDENTITY;
        }
    }

    /**
     * **拖动中的姿态**：露头量由手指拖到哪决定，倾角由露头量决定。
     *
     * <p>★ 这是"跟手"的关键：拖动期间不再走时间轴（{@link #at}），而是每帧用
     * 当前窗口 x 反推出 {@code keep}（{@link #keepForX}）再调这里 ⇒
     * 手指停在哪，它就露多少、歪多少，没有"先缩回再拖"的闪跳。
     *
     * @param keep 当前留在屏内的身体宽度比例
     */
    public static Pose dragPose(float keep) {
        return dragPose(keep, Tuning.DEFAULT);
    }

    /** 带可调参数的拖动姿态 */
    public static Pose dragPose(float keep, Tuning tune) {
        Tuning tn = (tune == null) ? Tuning.DEFAULT : tune;
        float k = clamp01(keep);
        if (k < tn.peerKeep) k = tn.peerKeep;    // 拖动阶段不许比常驻位探得更出去
        return new Pose(SETTLE, k, leanForKeep(k, tn.leanDeg, tn.peerKeep), 1f, 1f, 1f, 0f);
    }

    /**
     * **露头量 ⟺ 倾角** 的唯一映射：完全在屏内 = 站直(0°)，挂到常驻位 = 12°。
     *
     * <p>拖动、回落、过渡三个阶段都走这一条，避免"位置一个口径、角度另一个口径"。
     */
    public static float leanForKeep(float keep) {
        return leanForKeep(keep, LEAN_PEER_DEG, PEER_KEEP);
    }

    /** 带可调参数的倾角映射：拉出到 keep=1 时归零 */
    public static float leanForKeep(float keep, float leanDeg, float peerKeep) {
        float k = clamp01(keep);
        if (k <= peerKeep) return leanDeg;
        float t = (k - peerKeep) / (1f - peerKeep);   // 0 = 常驻位，1 = 完全在内
        return leanDeg * (1f - t);
    }

    /**
     * {@link #peekedX} 的逆运算：由窗口 x 反推"还露着多少"。
     *
     * <p>拖动跟手时每帧都要它 —— 手指把窗口拖进屏内多少，{@code keep} 就自动变大多少。
     *
     * @param baseX       内容正好贴边时的窗口 x
     * @param bodyWidthPx 身体框宽度（px）
     * @param x           当前窗口 x
     * @param edge        {@link #EDGE_LEFT} / {@link #EDGE_RIGHT}
     */
    public static float keepForX(int baseX, int bodyWidthPx, int x, int edge) {
        int bodyW = Math.max(0, bodyWidthPx);
        if (bodyW <= 0) return 1f;
        int hidden = (edge == EDGE_LEFT) ? (baseX - x) : (x - baseX);
        if (hidden <= 0) return 1f;                      // 已经完全在屏内
        float keep = 1f - hidden / (float) bodyW;
        if (keep < 0.05f) keep = 0.05f;
        if (keep > 1f) keep = 1f;
        return keep;
    }

    /** 只取露出量（断言与位移换算用，省一次对象分配） */
    public static float keepOf(int phase, long tMs, float fromKeep) {
        return at(phase, tMs, 0L, fromKeep).keep;
    }

    /** 侧倾角量级（非负） */
    public static float leanOf(int phase, long tMs) {
        return at(phase, tMs, 0L).lean;
    }

    /**
     * 按边取符号：贴左边 = 顺时针（正），贴右边 = 逆时针（负）。
     * <p>同一套几何只算一次，左右只是符号 —— 不写第二份实现。
     */
    public static float leanSigned(float lean, int edge) {
        return (edge == EDGE_LEFT) ? lean : -lean;
    }

    /** 呼吸的横向位移（dp）：2.6 s 一个周期，首尾归零 */
    public static float breathDp(long perchAgeMs) {
        if (perchAgeMs <= 0) return 0f;
        double ph = (perchAgeMs % BREATH_PERIOD_MS) / (double) BREATH_PERIOD_MS;
        return (float) (Math.sin(2 * Math.PI * ph) * BREATH_DP);
    }

    /** 呼吸的角度摆幅（度）：与 {@link #breathDp} 同相位 */
    public static float breathLean(long perchAgeMs) {
        if (perchAgeMs <= 0) return 0f;
        double ph = (perchAgeMs % BREATH_PERIOD_MS) / (double) BREATH_PERIOD_MS;
        return (float) (Math.sin(2 * Math.PI * ph) * BREATH_DEG);
    }

    /**
     * 下一次小探头该等多久（ms）：用种子做确定性的伪随机，落在
     * [{@link #NUDGE_MIN_MS}, {@link #NUDGE_MAX_MS}]。
     *
     * <p>★ 不用 {@code Math.random()}：同一个种子永远同一串间隔，
     * 断言能钉、复现能对上（{@code build/fpstest} 就是这么验的）。
     */
    public static long nextNudgeDelayMs(int seed) {
        long span = NUDGE_MAX_MS - NUDGE_MIN_MS;
        long h = mix32(seed);
        return NUDGE_MIN_MS + Math.floorMod(h, span + 1);
    }

    /**
     * 探头后的窗口 x：把<b>身体</b>往屏幕外挪 {@code (1-keep)} 个身体宽。
     *
     * @param baseX        内容正好贴住屏幕边时的窗口 x（正常"贴边吸附位"）
     * @param bodyWidthPx  身体框宽度（px）
     * @param keep         留在屏内的比例
     * @param edge         {@link #EDGE_LEFT} / {@link #EDGE_RIGHT}
     */
    public static int peekedX(int baseX, int bodyWidthPx, float keep, int edge) {
        int bodyW = Math.max(0, bodyWidthPx);
        float k = clamp01(keep);
        int hidden = (int) (bodyW * (1f - k) + 0.5f);
        return baseX + (edge == EDGE_LEFT ? -hidden : hidden);
    }

    /** 旋转轴的 y（相对身体框，px）：肩线位置 */
    public static int shoulderPivotY(int bodyTopPx, int bodyHeightPx) {
        int h = Math.max(1, bodyHeightPx);
        return bodyTopPx + (int) (h * SHOULDER_FRAC);
    }

    // ==================== 内部工具 ====================

    private static float progress(long t, int durMs) {
        if (durMs <= 0) return 1f;
        float p = t / (float) durMs;
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }

    private static float easeOutCubic(float p) {
        float q = 1f - p;
        return 1f - q * q * q;
    }

    private static float smooth(float p) {
        return p * p * (3f - 2f * p);
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** 32 位混合（避免依赖 java.util.Random 的序列，纯函数、可跨平台对上） */
    private static long mix32(int seed) {
        long x = seed & 0xFFFFFFFFL;
        x ^= (x >>> 16);
        x *= 0x7feb352dL;
        x &= 0xFFFFFFFFL;
        x ^= (x >>> 15);
        x *= 0x846ca68bL;
        x &= 0xFFFFFFFFL;
        x ^= (x >>> 16);
        return x & 0xFFFFFFFFL;
    }
}
