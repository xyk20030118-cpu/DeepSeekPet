package com.deepseek.pet;

/**
 * 形象来源的**纯逻辑** —— <b>零 Android 依赖</b>（照 {@link CharPicker} / {@link ClipNaming}
 * 的手法写，所以能在 {@code build/fpstest} 里做**真断言**）。
 *
 * <h3>它解决什么（待办 0h 的 P5：形象来源纳入角色模型）</h3>
 * P0～㉒ 把"角色 = 动作 + 台词 + 表情 + 性格 + 各自的绑定"这一层做完了，但
 * <b>这只桌宠长什么样</b>一直是**全局的**：{@code Prefs.use_custom_image / use_custom_anim /
 * use_custom_model / use_custom_l2d} 四个 pref + {@code filesDir/custom_pet.png} 这类全局文件。
 * 于是"切了角色，形象还是上一个角色的脸" —— 角色系统缺了最直观的那一半。
 *
 * <p>本轮把形象来源抽成"**每个角色一份**"的值，本类只负责四件容易出错的事：
 * <ol>
 *   <li><b>取值合法化</b>（{@link #normalize}）：脏 pref / 老包 / 手改的 json 一律回内置，
 *       绝不返回 null 让调用方去猜；</li>
 *   <li><b>素材缺失时回退谁</b>（{@link #resolve}）：选了"我的图片"但文件被清理了 ⇒ 回内置，
 *       而不是留一块空白（旧代码在 {@code FloatingService.applyPetDrawable} 里就地回退，
 *       每加一种来源就要再写一遍）；</li>
 *   <li><b>关掉一个来源 ≠ 关掉全部</b>（{@link #afterDisable}）：老代码里
 *       {@code saveCustomPet} 的顺序是"先开 anim、再把 image 置 false"，谁后写谁赢 ——
 *       按"关掉的正好是当前那个才回内置"来判，顺序就无关了；</li>
 *   <li><b>清单行怎么写</b>（{@link #rowLabel}）：● 当前 / ○ 其他 / 素材没导入的标出来。</li>
 * </ol>
 *
 * <h3>★ 为什么没有"内置伪 Live2D"这一项</h3>
 * {@code Prefs.useLive2D}（内置分层伪 Live2D）看着像第 5 种来源，实际是**死键**：
 * {@code assets/live2d/} 这个目录在本工程里<b>不存在</b>（{@code Live2DEngine.load} 读的
 * {@code live2d/meta.json} 必然失败 ⇒ {@code isReady()} 恒 false），而且
 * {@code FloatingService.applyPetDrawable} 从没调过 {@code petImage.setLive2D(true)}。
 * 所以形象来源只有五种，那个键保留原样（旧 pref 不去动它），只是不再出现在界面上。
 */
public final class Appearance {

    private Appearance() {}

    /** 内置角色包动画（{@code assets/webp/*.webp}，106 个动作）—— 默认来源，不需要素材文件 */
    public static final String BUILTIN = "builtin";
    /** 上传的静态图片（png / jpg） */
    public static final String IMAGE = "image";
    /** 上传的 GIF / 动态 WebP */
    public static final String ANIM = "anim";
    /** 序列帧模型包（zip 解压后的目录） */
    public static final String MODEL = "model";
    /** 导入的 Live2D 模型包（{@code meta.json} + 分层 PNG） */
    public static final String L2D = "custom_l2d";

    /** 默认来源 */
    public static final String DEFAULT = BUILTIN;

    /**
     * 全部来源（清单顺序 = 界面顺序）。
     * <p>★ 这些字符串**同时也是** {@code FloatingService.applyPetDrawable} 的 mode 值 ——
     * 故意保持一致，这样"运行期按角色取形象"不需要在中间再翻译一层
     * （翻译层就是漂移的来源：加了新来源忘了改映射，表现成"选了没反应"）。
     */
    public static final String[] ALL = {BUILTIN, IMAGE, ANIM, MODEL, L2D};

    /** 需要素材文件的那几种（除内置外全部） */
    public static final String[] NEEDS_FILE = {IMAGE, ANIM, MODEL, L2D};

    /** 合法来源？ */
    public static boolean valid(String src) {
        if (src == null) return false;
        for (String s : ALL) if (s.equals(src)) return true;
        return false;
    }

    /** 取值合法化：未知 / 空 / 脏值一律回内置（绝不返回 null） */
    public static String normalize(String src) {
        return valid(src) ? src : DEFAULT;
    }

    /** 是不是"用户自己导入的"形象（内置不算） */
    public static boolean isCustom(String src) {
        return !DEFAULT.equals(normalize(src));
    }

    /** 这种来源要不要素材文件（内置角色包在 APK assets 里，不需要） */
    public static boolean needsFile(String src) {
        String s = normalize(src);
        for (String x : NEEDS_FILE) if (x.equals(s)) return true;
        return false;
    }

    /** 界面上的名字 */
    public static String label(String src) {
        String s = normalize(src);
        if (IMAGE.equals(s)) return "上传的图片";
        if (ANIM.equals(s))  return "上传的动图（GIF / 动态 WebP）";
        if (MODEL.equals(s)) return "序列帧模型包";
        if (L2D.equals(s))   return "Live2D 模型包";
        return "内置角色包";
    }

    /** 清单里的短名字（对话框一行放得下） */
    public static String shortLabel(String src) {
        String s = normalize(src);
        if (IMAGE.equals(s)) return "图片";
        if (ANIM.equals(s))  return "动图";
        if (MODEL.equals(s)) return "模型包";
        if (L2D.equals(s))   return "Live2D";
        return "内置角色包";
    }

    /** 素材齐不齐（内置永远齐） */
    public static boolean available(String src, boolean hasImage, boolean hasAnim,
                                    boolean hasModel, boolean hasL2d) {
        String s = normalize(src);
        if (BUILTIN.equals(s)) return true;
        if (IMAGE.equals(s))   return hasImage;
        if (ANIM.equals(s))    return hasAnim;
        if (MODEL.equals(s))   return hasModel;
        if (L2D.equals(s))     return hasL2d;
        return false;
    }

    /**
     * 真正该用的来源：想要的这个若**素材不在**就回退内置。
     * <p>回退只发生一次（内置不需要素材），所以不会出现"回退到一个也不存在的东西"。
     */
    public static String resolve(String want, boolean hasImage, boolean hasAnim,
                                 boolean hasModel, boolean hasL2d) {
        String s = normalize(want);
        // ★★ 轮次61 收尾：这一段函数体在"强杀后台套件"的事故里丢了
        //   （`reverse_check_appearance_bindings.py` 的 b 项会把它临时改成 `return s;`，
        //    套件被强杀时 finally 跑不到 ⇒ 破坏态留在盘上）。
        //   现已按**原版那一行**（见该反向项的 old 值）逐字恢复：
        return available(s, hasImage, hasAnim, hasModel, hasL2d) ? s : DEFAULT;
    }

    /**
     * 把 {@code turningOff} 这个来源关掉之后的来源。
     * <p>★ 判据是"<b>关掉的正好是当前在用的那个</b>才回内置"，不是"关掉就回内置"：
     * 老代码里"启用动图"要顺手把图片关掉（{@code setUseCustomImage(false)}），
     * 若关掉一律回内置，那两行的**先后顺序**就决定了最终用哪张图 ——
     * 这种"谁后写谁赢"的 bug 在设置页里表现为"传了动图还是显示旧图片"。
     */
    public static String afterDisable(String current, String turningOff) {
        String cur = normalize(current);
        String off = normalize(turningOff);
        return cur.equals(off) ? DEFAULT : cur;
    }

    /**
     * 清单里的一行：{@code ● 内置角色包（当前）} / {@code ○ 图片}。
     * <p>素材还没导入的标「（未导入）」，但**不隐藏** —— 用户得知道有这条路、
     * 以及为什么现在点不了。
     */
    public static String rowLabel(String src, boolean current, boolean available) {
        StringBuilder sb = new StringBuilder();
        sb.append(current ? "\u25CF " : "\u25CB ");
        sb.append(shortLabel(src));
        if (!available) sb.append("（未导入）");
        if (current) sb.append("（当前）");
        return sb.toString();
    }

    /** 设置页那一行的文案（emoji 前缀由调用点加，本类不拼 emoji） */
    public static String sourceRowLabel(String src) {
        return "形象来源：" + label(src) + "（当前角色）";
    }

    /** 点了"还没导入"的那一项时给的提示（说清下一步做什么） */
    public static String missingHint(String src) {
        String s = normalize(src);
        if (IMAGE.equals(s)) return "还没有给这个角色传过图片，先点「上传图片 / GIF 动图」";
        if (ANIM.equals(s))  return "还没有给这个角色传过动图，先点「上传图片 / GIF 动图」选一张 GIF/动态 WebP";
        if (MODEL.equals(s)) return "还没有给这个角色导入过模型包，先点「导入桌宠模型」";
        if (L2D.equals(s))   return "还没有给这个角色导入过 Live2D 模型，先点「导入 Live2D 模型」";
        return "";
    }

    /**
     * 「恢复默认形象」的确认文案（写清只影响当前角色）。
     * <p>★ 这里**不许出现 markdown 的 `**`**：它是给用户看的对话框正文，
     * 不是给开发者看的注释 —— 星号会原样显示出来（fpstest [20] 钉住）。
     */
    public static String resetConfirmMessage(String charName, String currentLabel) {
        String n = (charName == null || charName.trim().isEmpty()) ? "当前角色" : charName.trim();
        return "「" + n + "」的形象会从「" + label(currentLabel) + "」回到内置角色包，"
                + "并为这个角色删掉上传的图片 / 动图 / 模型包文件。\n"
                + "其他角色的形象不受影响，内置角色包本身也不会被动。";
    }
}
