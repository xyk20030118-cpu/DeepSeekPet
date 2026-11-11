package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.List;

/**
 * `/` 指令表与解析 —— 方案 03 的第 1 项（"命令列表、参数解析、帮助和未知命令错误"）。
 *
 * <h3>★ 一条铁律：没实现的命令要**明说没实现**</h3>
 * 方案原文：「未实现的按钮应禁用或提示未支持，**不能只 Toast 却呈现为已实现**」。
 * 所以表里每个命令都带 {@link Spec#supported}：`/plan` 这类还没做的，解析结果是**明确的拒绝**，
 * 而不是"看起来执行了"。
 *
 * <h3>为什么命令表放在纯类里</h3>
 * 命令的**名字、参数、说明、支持与否**是产品口径，散在 UI 的 if-else 里就一定会走样；
 * 放这儿以后：工作台拿它做候选列表、断言拿它做判据、文档拿它做清单 —— 同一份。
 */
public final class DshCommand {

    private DshCommand() {}

    /** 一条命令的规格。 */
    public static final class Spec {
        public final String name;      // 不含斜杠
        public final String argsHint;  // 参数提示，可为空串
        public final String desc;      // 一句话说明
        public final boolean supported;

        Spec(String name, String argsHint, String desc, boolean supported) {
            this.name = name;
            this.argsHint = argsHint == null ? "" : argsHint;
            this.desc = desc == null ? "" : desc;
            this.supported = supported;
        }

        /** 候选列表里那一行：{@code /model [id] — 查看或切换模型}。 */
        public String line() {
            String head = "/" + name + (argsHint.length() == 0 ? "" : " " + argsHint);
            return head + " — " + desc + (supported ? "" : "（未支持）");
        }
    }

    /** 解析结果。 */
    public static final class Parsed {
        public boolean isCommand;
        public String name = "";
        public String args = "";
        public Spec spec;
        /** 非空表示这条命令**不能执行**（未知 / 参数不对 / 未支持）—— 原文给用户看。 */
        public String error = "";

        public boolean ok() {
            return isCommand && error.length() == 0 && spec != null && spec.supported;
        }
    }

    private static final List<Spec> SPECS = new ArrayList<Spec>();

    static {
        SPECS.add(new Spec("help", "", "列出所有命令", true));
        SPECS.add(new Spec("new", "", "开一个新会话", true));
        SPECS.add(new Spec("model", "[id]", "查看当前模型；带参数则切换", true));
        SPECS.add(new Spec("perm", "[read|write|full]", "查看权限档；带参数则切换", true));
        SPECS.add(new Spec("stop", "", "停止当前任务（传到执行层）", true));
        SPECS.add(new Spec("status", "", "查看当前任务状态", true));
        // 还没做的，明确列出来并标"未支持" —— 让用户知道"没这功能"，而不是以为点坏了
        SPECS.add(new Spec("plan", "", "先出计划再执行（尚未实现）", false));
        SPECS.add(new Spec("attach", "", "添加附件（尚未实现）", false));
    }

    public static List<Spec> all() {
        return SPECS;
    }

    public static Spec byName(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase();
        for (Spec s : SPECS) {
            if (s.name.equals(n)) return s;
        }
        return null;
    }

    /**
     * 这一行是不是命令（以 `/` 开头、不是纯 `/`、**且前面没有空格**）。
     *
     * <p>★ "前导空格就不当命令"是用户能自救的一条规则：想发一句真的以 `/` 开头的普通消息，
     * 前面加个空格就行。所以这里**不能 trim** —— trim 了这条规则就没了。
     */
    public static boolean looksLikeCommand(String line) {
        if (line == null) return false;
        if (line.length() > 0 && Character.isWhitespace(line.charAt(0))) return false;
        return line.length() > 1 && line.charAt(0) == '/';
    }

    /**
     * 解析一行输入。
     * <p>不是命令 ⇒ {@code isCommand=false}（其它字段为空，调用方当普通消息处理）。
     * 是命令但未知/未支持/参数不对 ⇒ {@code error} 有值，**不要执行**。
     */
    public static Parsed parse(String line) {
        Parsed p = new Parsed();
        if (!looksLikeCommand(line)) return p;
        p.isCommand = true;
        String s = line.substring(1).trim();
        if (s.length() == 0) {
            p.error = "空命令 —— 输入 /help 看全部";
            return p;
        }
        int sp = s.indexOf(' ');
        String name = sp < 0 ? s : s.substring(0, sp);
        p.name = name.toLowerCase();
        p.args = sp < 0 ? "" : s.substring(sp + 1).trim();
        p.spec = byName(p.name);
        if (p.spec == null) {
            p.error = "未知命令：/" + p.name + "（输入 /help 看全部）";
            return p;
        }
        if (!p.spec.supported) {
            p.error = "「/" + p.name + "」本轮还没实现（不假装执行）";
            return p;
        }
        // 参数校验（只对需要参数的两个做，避免"多写了就当没写"）
        if (p.name.equals("perm") && p.args.length() > 0) {
            String a = p.args.toLowerCase();
            if (!a.equals("read") && !a.equals("write") && !a.equals("full")) {
                p.error = "参数不对：/perm 只接受 read | write | full";
            }
        }
        return p;
    }

    /** `/help` 的正文（工作台直接显示，别在 UI 里再拼一遍）。 */
    public static String helpText() {
        StringBuilder sb = new StringBuilder("可用命令：\n");
        for (Spec s : SPECS) sb.append("  ").append(s.line()).append('\n');
        sb.append("提示：以 / 开头的整行都会被当作命令；想发普通的斜杠内容，前面加一个空格。");
        return sb.toString();
    }
}
