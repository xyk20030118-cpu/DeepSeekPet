package com.deepseek.pet.dsh;

import java.util.List;

/**
 * 系统提示装配 —— harness 的"人格与规矩"在这里。
 *
 * <p>上游把这件事拆成好几层（{@code dsh-agent-instructions} / {@code dsh-system-prompt} /
 * {@code dsh-persona} / {@code dsh-time-context} / {@code dsh-skill-badge}）。手机上不需要那么多插件，
 * 但**分层不能丢**，否则调一处就全乱。这里固定四层，顺序即优先级：
 * <ol>
 *   <li><b>身份</b>：我是谁、跑在哪（DeepSeek Harness 内核 · 桌宠 App）；</li>
 *   <li><b>铁律</b>：不许编造工具结果、不许声称做了没做的事、短话优先（这是手机上的桌宠）；</li>
 *   <li><b>环境</b>：工作区/权限档/当前时间/可用工具清单 —— 模型必须知道"能碰什么"；</li>
 *   <li><b>技能清单</b>：名字 + 一句话（正文按需加载）。</li>
 * </ol>
 *
 * <p>★ 主用户的对话会走同一条链路（{@code DshBridge} 把人格注入放在第 0 层），
 * 所以本类只负责"harness 自己的那部分"，人格由调用方拼在最前面 —— 换人格不用改这里。
 */
public final class DshPrompt {

    private DshPrompt() {}

    /** 组装 system 提示（不含人格；人格由调用方拼在最前）。 */
    public static String system(String timeContext, DshPolicy policy,
                               List<DshToolSpec> tools, List<DshSkill> skills) {
        StringBuilder sb = new StringBuilder(2048);

        sb.append("You are DeepSeek Harness, an agent kernel running **inside a phone app** ")
          .append("(DeepSeekPet / 桌宠). You are not a chat assistant that only talks: you can call tools ")
          .append("to read and write the app's own workspace, fetch web pages, load skills, and keep a todo list.\n\n");

        sb.append("## Hard rules\n")
          .append("1. Never claim you did something you did not do. If you call a tool, wait for its result; ")
          .append("if a tool fails, say what failed (the error text is the truth).\n")
          .append("2. Never invent file contents, page text, or tool output. If you have not read it, say so ")
          .append("and read it.\n")
          .append("3. Reply in the user's language. This is a phone pet: default to short answers ")
          .append("(1-3 sentences). Only go long when the user asks for detail or a task requires it.\n")
          .append("4. Tool arguments are JSON that must match the declared schema. If a call fails because of ")
          .append("arguments, fix the arguments and retry once instead of giving up.\n\n");

        sb.append("## Environment\n");
        if (timeContext != null && timeContext.length() > 0) {
            sb.append("- Now: ").append(timeContext).append('\n');
        }
        if (policy != null) {
            sb.append("- Workspace: `").append(policy.root).append("` (the app's private dir; ")
              .append("paths you pass to tools are relative to it)\n");
            sb.append("- Permission mode: `").append(DshPolicy.name(policy.mode)).append("` — ")
              .append(explainMode(policy.mode)).append('\n');
        }
        if (tools != null && !tools.isEmpty()) {
            sb.append("- Tools: ");
            for (int i = 0; i < tools.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('`').append(tools.get(i).name).append('`');
            }
            sb.append('\n');
        } else {
            sb.append("- Tools: none (tool calling is disabled; just talk)\n");
        }
        sb.append('\n');

        String skillsBlock = DshSkill.promptBlock(skills);
        if (skillsBlock.length() > 0) sb.append(skillsBlock).append('\n');

        return sb.toString();
    }

    static String explainMode(int mode) {
        switch (DshPolicy.normalize(mode)) {
            case DshPolicy.READ_ONLY:
                return "you may read the workspace and fetch pages, but you cannot write anything. "
                        + "If the user asks for a change, explain what you would write instead.";
            case DshPolicy.FULL_ACCESS:
                return "you may write inside the workspace without asking each time. "
                        + "Still never touch anything outside it.";
            default:
                return "you may write inside the workspace; the app may ask the user to confirm the first write. "
                        + "Never touch anything outside the workspace.";
        }
    }

    /** 人话时间上下文（喂给模型的那一句，别用 ISO 反人类格式）。 */
    public static String timeContext(long nowMillis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(nowMillis);
        String[] wd = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int hour = c.get(java.util.Calendar.HOUR_OF_DAY);
        String part;
        if (hour < 6) part = "凌晨";
        else if (hour < 11) part = "上午";
        else if (hour < 13) part = "中午";
        else if (hour < 18) part = "下午";
        else if (hour < 23) part = "晚上";
        else part = "深夜";
        return String.format(java.util.Locale.US, "%04d-%02d-%02d %s %s %02d:%02d",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH), wd[c.get(java.util.Calendar.DAY_OF_WEEK) - 1],
                part, hour, c.get(java.util.Calendar.MINUTE));
    }
}
