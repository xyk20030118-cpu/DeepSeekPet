package com.deepseek.pet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出风格护栏：修正模型的三类「格式化跑偏」。
 *
 * <p>由来（都是实际观测到的，不是猜的）：</p>
 * <ol>
 *   <li><b>「（刚刚）」</b> —— 原先 {@link ApiClient} 给<b>每一条</b>历史消息正文前面拼了
 *       「（相对时间）」前缀，而对话中每条都在 1 分钟内，于是整个上下文全是
 *       「（刚刚）嗷嗷」「（刚刚）你好你好~」。模型看到这个格式，就学着自己也写，
 *       结果每段回复都顶着一个「（刚刚）」。现在历史不再拼前缀，这里再明确禁止一次。</li>
 *   <li><b>微信表情代码「[菜刀]」</b> —— 导入的人格文档里写了
 *       「用微信表情：[流泪][坏笑][色][菜刀]…」，但桌宠的气泡是纯文本，
 *       不会把 [菜刀] 渲染成图，只会露出一行方括号字。统一改为要求用颜文字。</li>
 *   <li><b>emoji 图形字符</b> —— 同上，改用颜文字。</li>
 * </ol>
 *
 * <p><b>★ 本文件里一个 U+XXXX 转义都不能写。</b> Java 在词法分析<b>之前</b>就会先扫描
 * 整个源文件的字符序列处理 U+ 转义，连注释里都不放过：注释里写「U+XXXX」这种占位会直接
 * 报 illegal unicode escape；写成成对的代理转义又会被合并成单个码点，让字符类失效
 * （踩过：emoji 一个都没删掉，7 条测试失败）。所以正则一律用 \x{...} 码点写法，
 * 注释里描述码点也一律写成「U+」+ 十六进制。</p>
 *
 * <p>本类只用于日常闲聊；人格蒸馏那种「一次生成上万字文档」的长任务不走这里
 * （见 {@code ApiClient} 里按 maxTokens 的判断）。</p>
 */
public final class StyleGuard {

    private StyleGuard() {}

    /** 给模型的输出格式要求。拼成一条独立 system 消息，放在历史之后、用户消息之前。 */
    public static String prompt() {
        return "【输出格式要求（务必遵守，优先级高于人格文档里的同类描述）】\n"
             + "1. 直接说内容，不要给句子加时间标记。禁止出现「（刚刚）」「（1 分钟前）」"
             + "「【刚刚】」这类前缀，也不要每段都报一次时间。\n"
             + "2. 不要用方括号形式的微信表情代码，例如 [菜刀][流泪][坏笑][烟花]。\n"
             + "3. 【重要】说话要干净，不要任何图形符号和表情：\n"
             + "   不要 emoji（各种彩色图形字符，如笑脸、哭脸、手势、爱心、火焰）；\n"
             + "   不要颜文字（如 ^_^  >_<  TAT  orz  qwq  :( 这类）；\n"
             + "   不要用括号描写动作或神态，例如「（伸懒腰）」「（打哈欠）」「（揉眼睛）」；\n"
             + "   也不要用「哈——欠」「唔——」这种拉长的语气拟声或动作词。\n"
             + "   只写要说的话本身，像发短信一样干净自然。\n"
             + "4. 保持日常聊天的样子：短句、口语，不要 Markdown 标题和项目符号列表。";
    }

    // ==================== 历史清洗 ====================

    /** 「（刚刚）」「（1 分钟前）」「（今天 16:40）」这类被写进正文的时间标记 */
    private static final Pattern TIME_MARK = Pattern.compile(
            "(?m)^[ \\t]*[（(【]\\s*"
          + "(?:刚刚|一会儿前|\\d+\\s*分钟前|\\d+\\s*小时前|今天|昨天|前天|\\d+\\s*天前)"
          + "[^）)】]{0,16}[）)】][ \\t]*");

    /** 方括号表情代码：[菜刀] [流泪] [让我看看] —— 只吃 1~4 个汉字/字母，别误伤正常内容 */
    private static final Pattern BRACKET_EMOJI = Pattern.compile(
            "\\[[\\x{4E00}-\\x{9FA5}A-Za-z，、]{1,4}\\]");

    /**
     * emoji 图形字符。
     *
     * <p>主体是 Unicode 平面 1 的 U+1F000–U+1FBFF（Pictographs / Emoticons /
     * Transport / Supplemental / Symbols and Pictographs Extended-A），
     * 覆盖笑脸哭脸、手势、火焰、爱心这些主流 emoji。另补几个落在平面 0 的常见
     * emoji，以及 emoji 组合用的不可见字符。</p>
     *
     * <p>★ 特意<b>不</b>动 U+2600–U+27BF 区间里除白名单以外的符号 ——
     * 颜文字有大量零件落在这段，例如 (๑•̀ㅂ•́)و✧ 的 ✧(U+2727)、★(U+2605)。
     * 一刀切会把颜文字也砍坏。所以这一段只按白名单删掉少数
     * 「一定是 emoji、不可能是颜文字零件」的字符。</p>
     */
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F000}-\\x{1FBFF}]"                                    // 平面 1：emoji 全段
          + "|[\\x{FE0F}\\x{200D}]"                                      // 变体选择符 / 零宽连接符
          + "|[\\x{2705}\\x{274C}\\x{274E}\\x{2753}\\x{2757}\\x{2764}]"  // 圈勾/叉/问叹/爱心
          + "|[\\x{203C}\\x{2049}\\x{2B50}\\x{2B55}\\x{2714}\\x{2716}]"  // 双叹/双问/星/圈/勾/叉
          + "|[\\x{1F3FB}-\\x{1F3FF}]");                                 // 肤色修饰（保险）

    /**
     * 清洗一段文本：删掉残留的时间标记前缀、方括号表情代码与 emoji。
     *
     * <p>为什么要在读历史时也清一遍 —— 存量历史里已经写进去的「（刚刚）」
     * 会被当作范例喂给模型，光靠 system 指令压不住。只能从喂进去的内容里拿掉。</p>
     */
    public static String strip(String text) {
        if (text == null || text.length() == 0) return text;
        String s = text;
        try {
            s = TIME_MARK.matcher(s).replaceAll("");
            s = BRACKET_EMOJI.matcher(s).replaceAll("");
            s = EMOJI.matcher(s).replaceAll("");
            s = NONVERBAL.matcher(s).replaceAll("");
            s = ACT_SOUND.matcher(s).replaceAll("");
            s = KAOMOJI.matcher(s).replaceAll("");
            // 清完可能留下空行、行尾空格或连着的空格，收一下
            s = s.replaceAll("(?m)[ \\t]+$", "");
            s = s.replaceAll("\\n{3,}", "\n\n");
            s = s.replaceAll("[ \\t]{2,}", " ");
            s = s.trim();
        } catch (Throwable ignore) {
            return text;        // 清洗失败就用原文，绝不因美化而丢消息
        }
        return s.length() == 0 ? text : s;
    }

    /**
     * 常见颜文字。只枚举「几乎不可能出现在正常文字里」的精确模式，
     * 宁可少删也不误伤 —— 先前的教训是一刀切字符类会把正常标点也砍掉。
     */
    private static final Pattern KAOMOJI = Pattern.compile(
            "[>＞]\\s*[_\\uFF3F\\.\\u3002\\-]\\s*[<＜]"          // >_<  >.<  >-<
          + "|[<＜]\\s*[_\\uFF3F\\-]\\s*[>＞]"                      // <_>
          + "|[Tт]\\s*[_\\uFF3F\\.\\u3002]\\s*[Tт]"                // T_T  T.T
          + "|[\\^＾]\\s*[_\\uFF3F\\-]\\s*[\\^＾]"                // ^_^  ^-^
          + "|[_\\uFF3F]\\s*[\\^＾]\\s*[_\\uFF3F]"                  // _^_
          + "|=\\s*[_\\uFF3F\\-]\\s*="                              // =_=
          + "|[-]{1,2}\\s*[_\\uFF3F]\\s*[-]{1,2}"                       // -_-
          + "|[oO]\\s*[_\\uFF3F\\.\\u3002]\\s*[oO]"                 // o_O  O_o
          // 日式括号颜文字：括号内含颜文字专用符号
          + "|[（(【][^）)】\\n]{1,12}[๑ㅂㅠㅎㅈㅋฅಥಠ◕‿﹏╥╯╰づノεз∀◡⌒]"
          + "[^）)】\\n]{0,12}[）)】]"
          // 拉丁字母类
          // ★ 233/2333 已移除：「余额 233 元」这类正常文字会被误删
          + "|(?<![A-Za-z0-9])(?:TAT|tat|QAQ|QwQ|qwq|OwO|UwU|OvO|xD|XD|XDD)"
          + "(?![A-Za-z0-9])"
          // Orz 类纯字母，需按字母边界判定，否则会命中单词内部
          + "|(?<![A-Za-z])[Oo][Rr][Zz](?![A-Za-z])"
          + "|(?<![A-Za-z])[Oo][Tt][LlZz](?![A-Za-z])");

    /**
     * 裸的拟声动作词 —— 用户举的例子正是「哈——欠…晚上好呀」这种。
     *
     * <p>必须要求「有拉长符号」才删，否则「啊，好的」这类正常语气词会被误伤。</p>
     */
    private static final Pattern ACT_SOUND = Pattern.compile(
            // 哈——欠 / 哈~~欠。★必须带 >=2 个拉长符，否则「打了个哈欠」这种正常叙述会被吃掉
            "哈\\s*[-—–~～ー]{2,}\\s*欠[\\u2026。，、~～]*"
          // 单独拉长的语气词：唔——、嗯——、呼——、啊——、呜——
          + "|(?<![一-龥])(?:唔|嗯|呼|呜|呃|咦|欸|嘿|唉)\\s*[-—–~～ー]{1,}");

    /**
     * 括号描写的动作 / 神态 / 语气 —— 「（伸懒腰）」「(打哈欠)」「【叹气】」。
     *
     * <p>只吃「短括号 + 含动作关键词」的片段，「（明天）」这类正常括号不受影响。
     * 关键词表覆盖日常聊天里最常见的那批，不够可以再补。</p>
     */
    private static final String ACT_WORDS =
            "哈欠|伸懒腰|揉眼|叹气|歪头|点头|摇头|耸肩|摊手|眨眼|嘟嘴|撇嘴|掐腰|抱臂|"
          + "捂脸|拍桌|敲桌|挥手|鞠躬|打滚|转圈|蹦跳|踉跄|沉默|微笑|轻笑|大笑|苦笑|"
          + "脸红|小声|轻声|喃喃|嘀咕|低语|皱眉|紧张|害羞|犹豫|停顿|思考";

    private static final Pattern NONVERBAL = Pattern.compile(
            "[（(【][^）)】\\n]{0,10}(?:" + ACT_WORDS + ")[^）)】\\n]{0,8}[）)】]");

    /** 供调试：返回被清理掉的片段数量 */
    public static int countRemovable(String text) {
        if (text == null || text.length() == 0) return 0;
        int n = 0;
        Matcher a = TIME_MARK.matcher(text);
        while (a.find()) n++;
        Matcher b = BRACKET_EMOJI.matcher(text);
        while (b.find()) n++;
        Matcher c = EMOJI.matcher(text);
        while (c.find()) n++;
        Matcher d = NONVERBAL.matcher(text);
        while (d.find()) n++;
        Matcher e = ACT_SOUND.matcher(text);
        while (e.find()) n++;
        Matcher f = KAOMOJI.matcher(text);
        while (f.find()) n++;
        return n;
    }
}
