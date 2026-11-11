package com.deepseek.pet.dsh;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能（Skill）—— 与 DSH 的 {@code skills/<name>/SKILL.md} <b>同一套目录与文件格式</b>。
 *
 * <h3>格式（照抄上游，实测自 {@code %APPDATA%\dsh-desktop\harness\skills}）</h3>
 * <pre>
 * ---
 * name: android-dev
 * description: Build, run, debug, or ship Android apps … Use when the task mentions Android, adb, …
 * ---
 *
 * # Android development on this machine
 * （正文：Markdown，可以很长；正文**不注入**系统提示，只按需加载）
 * </pre>
 *
 * <p>★ 关键取舍（和上游一致）：系统提示里只放**名字 + 一句话描述**（"什么时候该用我"），
 * 完整正文等模型自己调 {@code skill} 工具取 —— 否则几十个技能一次性塞进去，
 * 上下文窗口和注意力都会被吃掉。
 *
 * <p>★ 这个类是**纯类**：只吃 {@link File}，不认识 Android。
 */
public final class DshSkill {

    public final String name;
    public final String description;
    /** SKILL.md 正文（front-matter 之后的部分）；懒加载，第一次用时才读。 */
    public String body = "";
    /** 技能所在目录（绝对路径），用于读取 references/ scripts/ 等附属文件。 */
    public final String dir;
    /** SKILL.md 绝对路径。 */
    public final String file;

    DshSkill(String name, String description, String dir, String file) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.dir = dir == null ? "" : dir;
        this.file = file == null ? "" : file;
    }

    @Override public String toString() { return name; }

    /** 技能清单提示块 —— 上游会话里那段 {@code <system-reminder><available_skills>}。 */
    public static String promptBlock(List<DshSkill> skills) {
        if (skills == null || skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<system-reminder>\n");
        sb.append("A skill is a reusable set of task-specific instructions. ");
        sb.append("The following skills are available in this session:\n\n");
        sb.append("<available_skills>\n");
        for (DshSkill s : skills) {
            sb.append("- `").append(s.name).append("`: ").append(s.description).append('\n');
        }
        sb.append("</available_skills>\n\n");
        sb.append("Only the name and description are shown here — the full instructions are NOT loaded. ");
        sb.append("When a task matches a skill, call the `skill` tool with {\"name\":\"<skill-name>\"} ");
        sb.append("to load its complete instructions before acting.\n");
        sb.append("</system-reminder>");
        return sb.toString();
    }

    /** 解析一个 SKILL.md 的 front-matter（只认 {@code name} / {@code description}，其余键忽略）。 */
    public static DshSkill parse(File md) {
        if (md == null || !md.isFile()) return null;
        String raw = DshFs.readTextQuiet(md, 256 * 1024);
        if (raw == null) return null;
        Map<String, String> fm = new LinkedHashMap<String, String>();
        String body = raw;
        int start = -1;
        // front-matter 必须在文件最开头（允许前面有空行）
        String[] lines = raw.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].trim().length() == 0) i++;
        if (i < lines.length && lines[i].trim().equals("---")) {
            start = i;
            int end = -1;
            for (int j = i + 1; j < lines.length; j++) {
                if (lines[j].trim().equals("---")) { end = j; break; }
            }
            if (end > 0) {
                for (int j = i + 1; j < end; j++) {
                    String line = lines[j];
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    String k = line.substring(0, colon).trim();
                    String v = line.substring(colon + 1).trim();
                    if (v.length() > 0 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
                        char q = v.charAt(0);
                        if (v.length() > 1 && v.charAt(v.length() - 1) == q) {
                            v = v.substring(1, v.length() - 1);
                        }
                    }
                    fm.put(k, v);
                }
                StringBuilder rest = new StringBuilder();
                for (int j = end + 1; j < lines.length; j++) {
                    if (rest.length() > 0) rest.append('\n');
                    rest.append(lines[j]);
                }
                body = rest.toString();
            }
        }
        String name = fm.get("name");
        if (name == null || name.trim().length() == 0) {
            // 没写 name 就用目录名兜底（上游也允许只写 description）
            name = md.getParentFile() != null ? md.getParentFile().getName() : md.getName();
        }
        String desc = fm.get("description");
        if (desc == null) desc = "";
        DshSkill s = new DshSkill(name.trim(), desc.trim(),
                md.getParentFile() == null ? "" : md.getParentFile().getAbsolutePath(),
                md.getAbsolutePath());
        s.body = body.trim();
        return s;
    }

    /** 加载正文（已经解析过就直接给）。 */
    public String loadBody() {
        if (body.length() > 0) return body;
        String raw = DshFs.readTextQuiet(new File(file), 512 * 1024);
        if (raw == null) return "";
        body = raw.trim();
        return body;
    }

    /**
     * 扫描技能根目录：既支持 {@code <root>/<name>/SKILL.md}，也支持 {@code <root>/SKILL.md}。
     * <p>找不到目录就返回空列表（**不抛**）：没有技能是正常状态。
     */
    public static List<DshSkill> scan(File root) {
        List<DshSkill> out = new ArrayList<DshSkill>();
        if (root == null || !root.isDirectory()) return out;
        File[] kids = root.listFiles();
        if (kids == null) return out;
        // 根下直接放一份 SKILL.md 的写法
        File direct = new File(root, "SKILL.md");
        if (direct.isFile()) {
            DshSkill s = parse(direct);
            if (s != null) out.add(s);
        }
        for (File d : kids) {
            if (d == null || !d.isDirectory()) continue;
            File md = new File(d, "SKILL.md");
            if (!md.isFile()) continue;
            DshSkill s = parse(md);
            if (s != null && s.name.length() > 0) out.add(s);
        }
        return out;
    }

    /** 按名字找（大小写不敏感）。 */
    public static DshSkill find(List<DshSkill> skills, String name) {
        if (skills == null || name == null) return null;
        String n = name.trim();
        for (DshSkill s : skills) {
            if (s.name.equalsIgnoreCase(n)) return s;
        }
        return null;
    }
}
