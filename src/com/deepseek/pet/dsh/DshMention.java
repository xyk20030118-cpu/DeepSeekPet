package com.deepseek.pet.dsh;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * `@file` 与 `@conversation` 的解析与注入 —— 方案 03 的第 2、3 项。
 *
 * <h3>语法（定死，免得模型和用户各猜一套）</h3>
 * <pre>
 * &#64;file:notes.md      明确引用工作区里的一个文件
 * &#64;conv:20261023-…     引用某个会话（注入它的最近几条消息摘要）
 * &#64;notes.md            裸写按"文件"处理（方便手打）
 * </pre>
 * 只认 {@code @} 前面是**行首或空白**的那种 —— 否则 `a@b.com` 会被当成引用。
 *
 * <h3>三条硬规矩</h3>
 * <ol>
 *   <li><b>路径必须过沙箱</b>：一律走 {@link DshPolicy#resolve}，不许自己拼 {@code File}；</li>
 *   <li><b>大小有上限</b>：单个文件 ≤ {@link #MAX_FILE_BYTES}，整段注入 ≤ {@link #MAX_BLOCK_CHARS}，
 *       超了**明确报错**，不静默截断（截断的上下文比没有更容易误导模型）；</li>
 *   <li><b>引用不静默失败</b>：文件不存在/不读不了/不是文件，都要回一句人话给用户。</li>
 * </ol>
 *
 * <p>纯类：文件内容由调用方给（{@link #resolveFiles} 只做校验与读取），不认识 Android。
 */
public final class DshMention {

    private DshMention() {}

    public static final int MAX_FILE_BYTES = 256 * 1024;   // 256 KB
    public static final int MAX_BLOCK_CHARS = 32 * 1024;   // 注入块总长上限 32 K 字符

    /** 解析结果。 */
    public static final class Parsed {
        /** 原文去掉引用记号后的正文（用于气泡显示与发给模型）。 */
        public String clean = "";
        public final List<String> files = new ArrayList<String>();
        public final List<String> conversations = new ArrayList<String>();
        /** 非空 = 引用写法有问题（例如 `@file:` 后面空的）。 */
        public String error = "";
    }

    /** 一个解析出来的文件内容。 */
    public static final class Ref {
        public final String name;
        public final String content;

        public Ref(String name, String content) {
            this.name = name;
            this.content = content;
        }
    }

    /** 扫出所有引用。 */
    public static Parsed parse(String text) {
        Parsed p = new Parsed();
        if (text == null) {
            p.clean = "";
            return p;
        }
        StringBuilder clean = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            boolean atStart = (i == 0) || Character.isWhitespace(text.charAt(i - 1));
            if (c == '@' && atStart) {
                int j = i + 1;
                while (j < n && !Character.isWhitespace(text.charAt(j))
                        && text.charAt(j) != '@') {
                    j++;
                }
                String token = text.substring(i + 1, j);
                if (token.length() == 0) {
                    clean.append(c);
                    i++;
                    continue;
                }
                if (token.startsWith("file:")) {
                    String name = token.substring(5).trim();
                    if (name.length() == 0) {
                        p.error = "引用写法不对：`@file:` 后面要跟文件名";
                    } else {
                        p.files.add(name);
                    }
                } else if (token.startsWith("conv:")) {
                    String id = token.substring(5).trim();
                    if (id.length() == 0) {
                        p.error = "引用写法不对：`@conv:` 后面要跟会话 id";
                    } else {
                        p.conversations.add(id);
                    }
                } else {
                    // 裸写：当文件处理（但 `a@b.com` 这种不会进来，因为前面不是空白）
                    p.files.add(token);
                }
                i = j;
                continue;
            }
            clean.append(c);
            i++;
        }
        p.clean = clean.toString().replaceAll("[ \\t]{2,}", " ").trim();
        return p;
    }

    /**
     * 校验并读取引用的文件。
     *
     * @param policy 沙箱（路径必须过它）
     * @return 读到的文件；任何一项不合格就把原因写进 {@code outError[0]} 并返回已读到的部分
     */
    public static List<Ref> resolveFiles(DshPolicy policy, List<String> names, String[] outError) {
        List<Ref> out = new ArrayList<Ref>();
        if (names == null || names.isEmpty()) return out;
        for (String raw : names) {
            String abs;
            try {
                abs = policy.resolve(raw);
            } catch (DshTool.ToolException te) {
                if (outError != null && outError.length > 0) outError[0] = te.getMessage();
                return out;
            }
            File f = new File(abs);
            if (!f.exists()) {
                if (outError != null && outError.length > 0) outError[0] = "引用的文件不存在：" + raw;
                return out;
            }
            if (f.isDirectory()) {
                if (outError != null && outError.length > 0) outError[0] = "这是一个目录，请引用具体文件：" + raw;
                return out;
            }
            if (f.length() > MAX_FILE_BYTES) {
                if (outError != null && outError.length > 0) {
                    outError[0] = "文件太大（" + DshFs.humanSize(f.length()) + "），"
                            + "上限 " + DshFs.humanSize(MAX_FILE_BYTES) + "：" + raw;
                }
                return out;
            }
            String content = DshFs.readTextQuiet(f, MAX_FILE_BYTES);
            if (content == null) {
                if (outError != null && outError.length > 0) outError[0] = "读不出来（可能不是文本文件）：" + raw;
                return out;
            }
            out.add(new Ref(raw, content));
        }
        return out;
    }

    /**
     * 组装注入块（**发给模型的那部分**，不显示在气泡里）。
     * <p>格式刻意写成"带标签的纯文本"：模型对 {@code <file path="…">} 这种边界最不容易看混。
     *
     * @param convs 会话摘要（每条是"标题 + 最近几条消息"的纯文本，由调用方准备）
     */
    public static String contextBlock(List<Ref> files, List<String[]> convs, String[] outError) {
        StringBuilder sb = new StringBuilder();
        if (files != null) {
            for (Ref r : files) {
                sb.append("<file path=\"").append(r.name).append("\">\n")
                        .append(r.content)
                        .append("\n</file>\n");
            }
        }
        if (convs != null) {
            for (String[] c : convs) {
                if (c == null || c.length < 2) continue;
                sb.append("<conversation id=\"").append(c[0]).append("\">\n")
                        .append(c[1])
                        .append("\n</conversation>\n");
            }
        }
        if (sb.length() > MAX_BLOCK_CHARS) {
            if (outError != null && outError.length > 0) {
                outError[0] = "引用的内容太长（" + sb.length() + " 字，上限 " + MAX_BLOCK_CHARS
                        + "），请只引用需要的部分";
            }
            return "";
        }
        return sb.toString();
    }
}
