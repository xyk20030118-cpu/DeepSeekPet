package com.deepseek.pet;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天记录 → 人格（persona.md）蒸馏器。
 *
 * 与 {@link SkillImporter} 的分工：
 *   SkillImporter    ：导入「已经生成好的」persona.md / memories.md（产物 → 预设）
 *   PersonaDistiller ：从「原始聊天记录」现场生成 persona.md（原料 → 产物）
 *
 * 设计要点：
 *  1) 解析与统计全部本地完成，不联网 —— 聊天记录是最私密的数据，不该为了统计上传。
 *  2) 只有「请 LLM 把它写成 5 层结构」这一步需要联网，且只发抽样后的片段。
 *  3) 抽样保证长记录也有代表性：短句（口头禅）、长句（思维方式）、情绪句分桶取样。
 *  4) 产物直接是 persona.md 文本，可保存为文件、可交给 SkillImporter 导入即用。
 *
 * 产出与 friend-distiller skill 的 persona.md 结构一致（Layer 0~5），
 * 因此两条链路（手机内生成 / 电脑上生成）可以互通。
 */
public class PersonaDistiller {

    /** 送入 LLM 前的硬上限，避免超长记录把上下文打爆 */
    public static final int MAX_SAMPLE_CHARS = 12000;
    /** 生成结果的字符上限（与 SkillImporter.MAX_CHARS 对齐，保证可导入） */
    public static final int MAX_RESULT_CHARS = 40000;

    // ---------------- 解析结果 ----------------

    /** 一条消息 */
    public static class Msg {
        public String who = "";
        public String text = "";
        public String time = "";

        public Msg(String who, String text, String time) {
            this.who = who;
            this.text = text;
            this.time = time;
        }
    }

    /** 解析 + 统计的综合结果 */
    public static class Dig {
        public String target = "";                     // 目标人物
        public int total = 0;                          // 目标人物消息条数
        public String[] others = new String[0];        // 记录里出现过的其他说话人
        public List<Msg> msgs = new ArrayList<>();     // 目标人物的消息
        public String sample = "";                     // 抽样后的送模文本
        public String statsReport = "";                // 可读的风格统计
        public String error = "";

        public boolean ok() {
            return error.length() == 0 && total > 0;
        }
    }

    // ---------------- 说话人识别 ----------------

    /**
     * 一行记录里「谁: 内容」的分隔，兼容：
     *   2024-03-01 20:15 老王: 在吗
     *   老王: 在吗 / 老王：在吗
     *   [2024-03-01 20:15] 老王: 在吗
     *   【老王】在吗
     *   &lt;老王&gt; 在吗
     */
    private static final Pattern LINE_WHO = Pattern.compile(
            // 时间前缀必须严格，不能用 [^\]]* 之类通配 —— 否则会贪婪吃掉人名再回溯，
            // 把「2024-03-01 20:16 我: 在」错切成说话人「王」（真实踩过的坑）
            "^\\s*(?:\\[?(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})"
          + "(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?\\]?"
          + "|\\d{1,2}:\\d{2}(?::\\d{2})?)?\\s*"
          + "(?:\\[|【|<)?([^\\s\\[\\]【】<>：:]{1,24})(?:\\]|】|>)?\\s*[：:]\\s*(.+)$");

    /** 备用格式：`【人名】内容`（导出工具常见，无冒号） */
    private static final Pattern LINE_BRACKET = Pattern.compile(
            "^\\s*[【\\[](\\S{1,24})[】\\]]\\s*([^\\s].*)$");

    /** 这些不是人名，是媒体/系统占位符 */
    private static final java.util.Set<String> NOISE_WHO = new java.util.HashSet<>(java.util.Arrays.asList(
            "图片", "表情", "动图", "动画表情", "语音", "视频", "文件", "链接", "转账", "红包",
            "位置", "名片", "聊天记录", "视频通话", "语音通话", "系统消息", "撤回"));

    private static final Pattern TIME_AT_HEAD = Pattern.compile(
            "^\\s*\\[?(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?)\\]?\\s*");

    /** 媒体/系统占位符，不参与风格统计 */
    private static final Pattern NOISE = Pattern.compile(
            "^[\\[【(（](图片|表情|动图|语音|视频|文件|链接|转账|红包|位置|名片|聊天记录|动画表情|视频通话|语音通话)[^\\]】)）]*[\\]】)）]$");

    /**
     * 从自由文本聊天记录里解析出目标人物的消息。
     *
     * @param raw    记录全文
     * @param target 目标人物名字（空串 = 取出现最多的说话人）
     */
    public static Dig parse(String raw, String target) {
        Dig d = new Dig();
        if (raw == null || raw.trim().length() == 0) {
            d.error = "内容是空的。";
            return d;
        }

        // 先按「谁: 内容」切
        List<Msg> all = new ArrayList<>();
        String[] lines = raw.split("\\r?\\n");
        String lastWho = null;
        StringBuilder pending = null;
        String pendingTime = "";

        for (String line : lines) {
            String s = line.trim();
            if (s.length() == 0) continue;

            Matcher m = LINE_WHO.matcher(s);
            String whoHit = null;
            String bodyHit = null;
            if (m.matches()) {
                whoHit = m.group(1).trim();
                bodyHit = m.group(2);
            } else {
                Matcher mb = LINE_BRACKET.matcher(s);
                if (mb.matches()) {
                    String cand = mb.group(1).trim();
                    if (NOISE_WHO.contains(cand)) {
                        continue;              // 整行丢弃，别把占位符当说话人
                    }
                    whoHit = cand;
                    bodyHit = mb.group(2);
                }
            }
            if (whoHit != null) {
                // 收尾上一条
                if (lastWho != null) {
                    all.add(new Msg(lastWho, pending.toString().trim(), pendingTime));
                }
                // 新的一条
                String t = "";
                Matcher tm = TIME_AT_HEAD.matcher(s);
                if (tm.find()) t = tm.group(1);
                lastWho = whoHit;
                pending = new StringBuilder(bodyHit);
                pendingTime = t;
            } else if (lastWho != null) {
                // 续行：同一说话人的连续文本
                pending.append("\n").append(s);
            }
        }
        if (lastWho != null && pending != null) {
            all.add(new Msg(lastWho, pending.toString().trim(), pendingTime));
        }

        if (all.isEmpty()) {
            d.error = "没认出任何「说话人：内容」格式。\n\n"
                    + "请确认记录里每行形如「老王: 在吗」，\n"
                    + "或先用导出工具导出成这种格式。";
            return d;
        }

        // 统计说话人
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Msg msg : all) {
            Integer c = count.get(msg.who);
            count.put(msg.who, c == null ? 1 : c + 1);
        }

        if (target == null) target = "";
        target = target.trim();

        if (target.length() == 0) {
            // 取出现最多的人
            String best = null;
            int bestN = -1;
            for (Map.Entry<String, Integer> e : count.entrySet()) {
                if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
            }
            target = best == null ? "" : best;
        }

        if (!count.containsKey(target)) {
            // 允许模糊包含
            String hit = null;
            for (String w : count.keySet()) {
                if (w.contains(target) || target.contains(w)) { hit = w; break; }
            }
            if (hit == null) {
                StringBuilder sb = new StringBuilder();
                for (String w : count.keySet()) {
                    if (sb.length() > 0) sb.append("、");
                    sb.append(w).append("(").append(count.get(w)).append(")");
                }
                d.error = "记录里没有找到「" + target + "」。\n\n"
                        + "识别到的说话人：" + sb;
                return d;
            }
            target = hit;
        }

        d.target = target;
        List<String> otherNames = new ArrayList<>();
        for (String w : count.keySet()) if (!w.equals(target)) otherNames.add(w);
        d.others = otherNames.toArray(new String[0]);

        // 只留目标人物的话
        for (Msg msg : all) {
            if (!msg.who.equals(target)) continue;
            String txt = clean(msg.text);
            if (txt.length() == 0) continue;
            d.msgs.add(new Msg(msg.who, txt, msg.time));
        }
        d.total = d.msgs.size();
        if (d.total == 0) {
            d.error = "「" + target + "」没有可用的文本消息。";
            return d;
        }

        d.statsReport = stats(d);
        d.sample = sample(d);
        return d;
    }

    /** 去噪：剥离媒体占位符、空白行 */
    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\\n")) {
            String t = line.trim();
            if (t.length() == 0) continue;
            if (NOISE.matcher(t).matches()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(t);
        }
        return sb.toString().trim();
    }

    // ---------------- 风格统计（与 friend-distiller 的 style_stats.py 同思路） ----------------

    private static final String[] IDIOMS = {
            "哈哈哈", "哈哈", "呵呵", "嘿嘿", "笑死", "无语", "离谱", "绝了", "服了",
            "算了", "行吧", "好的", "收到", "没问题", "没事", "随便", "都行",
            "牛逼", "厉害", "卧槽", "我靠", "真的假的", "不是吧", "什么鬼",
            "咋了", "咋回事", "干嘛", "在吗", "在不在", "有一说一", "说实话",
            "讲道理", "说真的", "就这", "什么玩意"
    };

    private static final Pattern EMOJI = Pattern.compile(
            "[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF]");

    /** 句末标点/语气词 */
    private static final String TAIL_PARTICLES = "嘛啦呀哦嗯吧呢啊哈哼咦哎喔耶喽";

    public static String stats(Dig d) {
        int n = d.total;
        StringBuilder sb = new StringBuilder();

        // 句长
        int shortN = 0, midN = 0, longN = 0, sum = 0;
        int noEnd = 0, exclaim = 0, quest = 0, ellipsis = 0, emojiN = 0;
        Map<String, Integer> tail = new LinkedHashMap<>();
        for (Msg m : d.msgs) {
            int len = m.text.length();
            sum += len;
            if (len <= 8) shortN++;
            else if (len <= 30) midN++;
            else longN++;

            String t = m.text;
            char last = t.charAt(t.length() - 1);
            if ("。！？!?…".indexOf(last) < 0) noEnd++;
            if (t.indexOf('!') >= 0 || t.indexOf('！') >= 0) exclaim++;
            if (t.indexOf('?') >= 0 || t.indexOf('？') >= 0) quest++;
            if (t.indexOf('…') >= 0) ellipsis++;
            if (EMOJI.matcher(t).find()) emojiN++;

            // 句尾语气词
            int end = t.length();
            while (end > 0 && "~～!！?？.。… ".indexOf(t.charAt(end - 1)) >= 0) end--;
            if (end > 0 && TAIL_PARTICLES.indexOf(t.charAt(end - 1)) >= 0) {
                String k = String.valueOf(t.charAt(end - 1));
                Integer c = tail.get(k);
                tail.put(k, c == null ? 1 : c + 1);
            }
        }

        sb.append("消息条数：").append(n).append("\n");
        sb.append("平均句长：").append(String.format(Locale.US, "%.1f", sum * 1.0 / n)).append(" 字\n");
        sb.append("短句(≤8字)：").append(shortN).append(" 条(").append(pct(shortN, n)).append(")\n");
        sb.append("中句(9-30字)：").append(midN).append(" 条(").append(pct(midN, n)).append(")\n");
        sb.append("长句(>30字)：").append(longN).append(" 条(").append(pct(longN, n)).append(")\n");
        sb.append("句末不加标点：").append(noEnd).append(" 条(").append(pct(noEnd, n)).append(")\n");
        sb.append("含感叹号：").append(exclaim).append(" 条；含问号：").append(quest)
          .append(" 条；含省略号：").append(ellipsis).append(" 条\n");
        sb.append("含 emoji/颜文字：").append(emojiN).append(" 条(").append(pct(emojiN, n)).append(")\n");

        if (!tail.isEmpty()) {
            sb.append("句尾语气词：");
            int i = 0;
            for (Map.Entry<String, Integer> e : tail.entrySet()) {
                if (i++ > 0) sb.append("、");
                sb.append(e.getKey()).append("(").append(e.getValue()).append(")");
            }
            sb.append("\n");
        }

        // 高频 2-4 字（只在标点切出的语块内，避免跨词碎片）
        sb.append(ngramLine(d, 2, "高频二字词"));
        sb.append(ngramLine(d, 3, "高频三字词"));
        sb.append(ngramLine(d, 4, "高频四字词"));

        // 口头禅命中（按消息条数）
        List<String> hits = new ArrayList<>();
        for (String w : IDIOMS) {
            int c = 0;
            for (Msg m : d.msgs) if (m.text.contains(w)) c++;
            if (c > 0) hits.add(w + "×" + c);
        }
        if (!hits.isEmpty()) {
            sb.append("口头禅命中：");
            for (int i = 0; i < hits.size() && i < 20; i++) {
                if (i > 0) sb.append("、");
                sb.append(hits.get(i));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String pct(int a, int b) {
        return String.format(Locale.US, "%.1f%%", b == 0 ? 0.0 : a * 100.0 / b);
    }

    private static final Pattern SEG = Pattern.compile(
            "[，。！？!?、；;：:…\\s~～\\-—/\\\\【】\\[\\]（）()\"'“”‘’]+");

    private static String ngramLine(Dig d, int n, String label) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (Msg m : d.msgs) {
            java.util.Set<String> hit = new java.util.HashSet<>();
            for (String seg : SEG.split(m.text)) {
                String cl = seg.replaceAll("[^\\u4e00-\\u9fffA-Za-z]", "");
                for (int i = 0; i + n <= cl.length(); i++) {
                    hit.add(cl.substring(i, i + n));
                }
            }
            for (String w : hit) {
                Integer v = c.get(w);
                c.put(w, v == null ? 1 : v + 1);
            }
        }
        // 取计数≥2 的前 15 个
        List<Map.Entry<String, Integer>> list = new ArrayList<>(c.entrySet());
        java.util.Collections.sort(list, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : list) {
            if (e.getValue() < 2 || shown >= 15) break;
            if (shown == 0) sb.append(label).append("：");
            else sb.append("、");
            sb.append(e.getKey()).append("(").append(e.getValue()).append(")");
            shown++;
        }
        if (shown > 0) sb.append("\n");
        return sb.toString();
    }

    // ---------------- 抽样 ----------------

    /**
     * 分桶抽样：短句给口头禅，长句给思维方式，情绪句给性格。
     * 保证长记录也有代表性、且总量受控。
     */
    public static String sample(Dig d) {
        List<Msg> small = new ArrayList<>();
        List<Msg> big = new ArrayList<>();
        List<Msg> emo = new ArrayList<>();

        String[] EMO_WORDS = {"生气", "烦", "气死", "难受", "开心", "高兴", "哈哈", "哭",
                "无语", "离谱", "服了", "崩溃", "累", "郁闷", "讨厌", "喜欢", "爱",
                "他妈", "卧槽", "靠", "草", "哎", "唉"};

        for (Msg m : d.msgs) {
            int len = m.text.length();
            // 短句留口头禅；其余（含中句）都进「中长」桶，否则中句会被整个漏掉
            if (len <= 10) small.add(m);
            else big.add(m);
            for (String w : EMO_WORDS) {
                if (m.text.contains(w)) { emo.add(m); break; }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【目标人物】").append(d.target).append("\n");
        sb.append("【总消息数】").append(d.total).append("\n\n");

        // 短句取最多（口头禅密集）
        appendBucket(sb, "短消息（口头禅/语气）", small, 200, 3000);
        appendBucket(sb, "中长消息（思维方式）", big, 80, 4000);
        appendBucket(sb, "情绪类消息", emo, 60, 2000);

        String out = sb.toString();
        if (out.length() > MAX_SAMPLE_CHARS) {
            out = out.substring(0, MAX_SAMPLE_CHARS)
                    + "\n\n（样本已截断，仅取前 " + MAX_SAMPLE_CHARS + " 字）";
        }
        return out;
    }

    private static void appendBucket(StringBuilder sb, String title,
                                     List<Msg> src, int maxItems, int maxChars) {
        if (src.isEmpty()) return;
        // 均匀取样，避免只取到某一段时间
        List<Msg> picked = new ArrayList<>();
        int step = Math.max(1, src.size() / maxItems);
        for (int i = 0; i < src.size() && picked.size() < maxItems; i += step) {
            picked.add(src.get(i));
        }
        StringBuilder body = new StringBuilder();
        for (Msg m : picked) {
            String line = (m.time.length() > 0 ? "[" + m.time + "] " : "") + m.text;
            if (body.length() + line.length() + 3 > maxChars) break;
            body.append("- ").append(line).append("\n");
        }
        if (body.length() == 0) return;
        sb.append("## ").append(title).append("\n").append(body).append("\n");
    }

    // ---------------- 提示词 ----------------

    /** 生成人格用的 system 提示词（与 friend-distiller 的 5 层模板一致） */
    public static String buildSystemPrompt() {
        return "你是一个「人物性格蒸馏器」。用户会给你某个人的聊天记录样本和客观统计。"
             + "请把这个人的性格还原成一份可直接当 AI 系统提示词使用的 persona 文档。\n\n"
             + "严格输出以下 Markdown 结构，不要输出任何额外解释：\n\n"
             + "# {称呼} — Persona\n\n"
             + "## Layer 0：核心性格（最高优先级）\n"
             + "5-8 条，每条必须是「在什么情况下会怎么做」的可执行规则，禁止只写形容词。\n\n"
             + "## Layer 1：身份\n"
             + "他是谁、和对方什么关系、性格底色、对他的主观印象。\n\n"
             + "## Layer 2：表达风格\n"
             + "### 口头禅与高频词\n### 说话方式\n（句长、连发习惯、语气词、emoji、标点习惯）\n"
             + "### 你会怎么说\n用「> 场景：」+「> 你：原句」的形式，给 6 组以上具体例句。\n\n"
             + "## Layer 3：情感逻辑\n在乎什么、什么时候真心帮忙、什么时候敷衍、如何表达不爽。\n\n"
             + "## Layer 4：关系行为\n日常相处、有事相求、群聊、深夜、压力大时的表现。\n\n"
             + "## Layer 5：边界与雷区\n不喜欢什么、底线、会回避的话题。\n\n"
             + "硬性要求：\n"
             + "1. Layer 0 至少 5 条，且必须带具体场景。\n"
             + "2. Layer 2 的例句必须直接引用样本里真实出现过的说法，不要自己编。\n"
             + "3. 风格描述必须贴合给出的统计（如果 90% 不加句号，就要写明这点）。\n"
             + "4. 证据不足的层，写「（记录不足，基于现有样本推断）」，不要编造。\n"
             + "5. 如果检测到手机号/身份证/银行卡/邮箱，用 [电话] 之类占位符替换。\n"
             + "6. 全文不超过 12000 字。\n"
             + "直接输出文档，第一个字符就是「#」。";
    }

    /** 拼出发给 LLM 的 user 消息 */
    public static String buildUserPrompt(Dig d) {
        return "下面是「" + d.target + "」的聊天记录样本，以及客观统计。请据此生成 persona 文档。\n\n"
             + "===== 客观统计 =====\n" + d.statsReport + "\n"
             + "===== 聊天样本 =====\n" + d.sample;
    }

    // ---------------- 文件读取 ----------------

    public static String readUri(Context ctx, Uri uri) throws Exception {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new Exception("无法打开所选文件");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] data = bos.toByteArray();
            // 去 BOM
            int off = 0;
            if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                    && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
                off = 3;
            }
            return new String(data, off, data.length - off, StandardCharsets.UTF_8);
        } finally {
            try { is.close(); } catch (Exception ignore) {}
        }
    }

    // ---------------- 隐私体检（与 friend-distiller 的 check_persona.py 一致） ----------------

    private static final Pattern P_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern P_IDCARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern P_BANK = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern P_MAIL = Pattern.compile("[\\w.\\-]+@[\\w\\-]+\\.[a-zA-Z]{2,}");

    /** 产物体检：返回问题列表，空表示通过 */
    public static List<String> check(String persona) {
        List<String> issues = new ArrayList<>();
        if (persona == null || persona.trim().length() == 0) {
            issues.add("内容是空的。");
            return issues;
        }
        int n = persona.length();
        if (n > MAX_RESULT_CHARS) {
            issues.add("超出导入上限（" + n + " > " + MAX_RESULT_CHARS + " 字），导入桌宠会被拒收。");
        }
        // 一级标题
        boolean hasH1 = false;
        int layer0 = 0;
        for (String line : persona.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("# ")) hasH1 = true;
            if (t.startsWith("- ") || t.startsWith("* ")) layer0++;
        }
        if (!hasH1) {
            issues.add("缺少一级标题（`# 名字 — Persona`），桌宠靠它取名字。");
        }
        if (layer0 < 3) {
            issues.add("列表条目只有 " + layer0 + " 条，Layer 0 质量可能不足（建议 ≥5 条）。");
        }
        // 隐私
        if (P_PHONE.matcher(persona).find()) issues.add("检测到手机号，建议脱敏为 [电话]。");
        if (P_IDCARD.matcher(persona).find()) issues.add("检测到身份证号，建议脱敏。");
        if (P_BANK.matcher(persona).find()) issues.add("检测到疑似银行卡号，建议脱敏。");
        if (P_MAIL.matcher(persona).find()) issues.add("检测到邮箱，建议脱敏。");
        return issues;
    }

    /** 自动脱敏，返回替换后的文本 */
    public static String redact(String persona) {
        if (persona == null) return "";
        String s = P_PHONE.matcher(persona).replaceAll("[电话]");
        s = P_IDCARD.matcher(s).replaceAll("[身份证]");
        s = P_MAIL.matcher(s).replaceAll("[邮箱]");
        s = P_BANK.matcher(s).replaceAll("[卡号]");
        return s;
    }

    /** 剥掉模型可能加的 ``` 围栏（DeepSeek 偶尔会包一层） */
    public static String stripFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (!t.startsWith("```")) return t;
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(nl + 1);
        int end = t.lastIndexOf("```");
        if (end >= 0) t = t.substring(0, end);
        return t.trim();
    }

    /** 从 persona 文本里取名字（与 SkillImporter 同规则） */
    public static String guessName(String persona) {
        if (persona == null) return "导入的性格";
        for (String line : persona.split("\\n")) {
            String t = line.trim();
            if (!t.startsWith("# ")) continue;
            t = t.substring(2).trim();
            int cut = t.indexOf('\u2014');
            if (cut < 0) cut = t.indexOf(" - ");
            if (cut > 0) t = t.substring(0, cut).trim();
            if (t.length() > 0 && t.length() <= 40) return t;
        }
        return "导入的性格";
    }
}
