package com.deepseek.pet.dsh;

/**
 * 权限与沙箱 —— 决定"这个工具、这个路径，现在准不准动"。
 *
 * <h3>三个档（与 DSH 的 {@code sandbox/mode} 同名）</h3>
 * <table border="1">
 *   <tr><th>档</th><th>能干什么</th><th>对应上游</th></tr>
 *   <tr><td>{@code read-only}</td><td>只读：读文件、列目录、抓网页、加载技能</td><td>沙箱默认档</td></tr>
 *   <tr><td>{@code workspace-write}</td><td>再加上写文件 / 待办落盘（**只能在沙箱根内**）</td><td>{@code workspace-write}</td></tr>
 *   <tr><td>{@code danger-full-access}</td><td>同 workspace-write，但不再提示"每次都要点同意"</td><td>{@code danger-full-access}</td></tr>
 * </table>
 *
 * <p>★ 手机上**没有**第四档"整个文件系统"：可写范围就是 App 私有目录（{@code /data/data/包名/files/dsh}）。
 * 这不是偷懒 —— Android 上除了 App 私有目录和用户显式授权的位置，本来就没有合法可写区；
 * 上游那档 {@code danger-full-access} 在手机上对应的正是"App 自己的地盘"。
 *
 * <p>★ 路径穿越必须在这里挡住：{@code ../../databases/prefs.xml} 这类参数只要漏一次，
 * 沙箱就等于没有。{@link #resolve} 是**唯一**的路径入口（工具实现不许自己拼路径）。
 */
public final class DshPolicy {

    public static final int READ_ONLY = 0;
    public static final int WORKSPACE_WRITE = 1;
    public static final int FULL_ACCESS = 2;

    public static final String NAME_READ_ONLY = "read-only";
    public static final String NAME_WORKSPACE_WRITE = "workspace-write";
    public static final String NAME_FULL_ACCESS = "danger-full-access";

    public final int mode;
    /** 沙箱根（绝对路径，不带结尾分隔符） */
    public final String root;

    public DshPolicy(int mode, String root) {
        this.mode = normalize(mode);
        String r = root == null ? "" : root.replace('\\', '/');
        while (r.endsWith("/") && r.length() > 1) r = r.substring(0, r.length() - 1);
        this.root = r;
    }

    public static DshPolicy of(String modeName, String root) {
        return new DshPolicy(parse(modeName), root);
    }

    public static int normalize(int m) {
        if (m < READ_ONLY) return READ_ONLY;
        if (m > FULL_ACCESS) return FULL_ACCESS;
        return m;
    }

    public static int parse(String name) {
        if (name == null) return WORKSPACE_WRITE;
        String s = name.trim().toLowerCase();
        if (s.equals(NAME_READ_ONLY) || s.equals("readonly") || s.equals("只读")) return READ_ONLY;
        if (s.equals(NAME_FULL_ACCESS) || s.equals("full") || s.equals("全部")) return FULL_ACCESS;
        if (s.equals(NAME_WORKSPACE_WRITE) || s.equals("write") || s.equals("可写")) return WORKSPACE_WRITE;
        return WORKSPACE_WRITE; // 认不出 ⇒ 保守档（不是最宽那档）
    }

    public static String name(int mode) {
        switch (normalize(mode)) {
            case READ_ONLY: return NAME_READ_ONLY;
            case FULL_ACCESS: return NAME_FULL_ACCESS;
            default: return NAME_WORKSPACE_WRITE;
        }
    }

    /** 人话说明（设置页直接用）。 */
    public static String describe(int mode) {
        switch (normalize(mode)) {
            case READ_ONLY:
                return "只读：能看文件、抓网页，不能改任何东西";
            case FULL_ACCESS:
                return "可写（不逐次确认）：能改私有目录里的文件";
            default:
                return "可写（逐次确认）：第一次改动前会问你";
        }
    }

    /** 这个工具在当前档下允许吗。 */
    public boolean allows(String toolName) {
        if (toolName == null) return false;
        return minModeOf(toolName) <= mode;
    }

    /** 工具所需的最低档。未知工具按"需要可写"处理（安全默认）。 */
    public static int minModeOf(String toolName) {
        if (toolName == null) return FULL_ACCESS;
        String n = toolName;
        if (n.equals("read") || n.equals("list") || n.equals("web_fetch")
                || n.equals("skill") || n.equals("now") || n.equals("pet_status")
                || n.equals("search") || n.equals("stat")) {
            // ★ 这些都只读：pet_status 读配置、search 读文件、stat 读元信息。
            //   漏一个就是默认档（workspace-write）下"模型一调就被拒"——子 agent 实测报告过这个坑。
            return READ_ONLY;
        }
        if (n.equals("write") || n.equals("todo")) {
            return WORKSPACE_WRITE;
        }
        return FULL_ACCESS;
    }

    /**
     * 把模型给的相对路径解析成沙箱内的绝对路径。
     *
     * @throws DshTool.ToolException 路径越界 / 为空 / 解析异常
     */
    public String resolve(String raw) throws DshTool.ToolException {
        if (raw == null || raw.trim().length() == 0) {
            throw new DshTool.ToolException("路径为空");
        }
        String p = raw.trim().replace('\\', '/');
        if (p.startsWith("/")) {
            // 绝对路径：只允许落在 root 之内（模型偶尔会拿日志里的绝对路径回来）
            if (root.length() > 0 && p.startsWith(root + "/")) {
                return clean(p);
            }
            throw new DshTool.ToolException("只允许访问沙箱目录内的路径：" + raw);
        }
        String joined = (root.length() == 0 ? "" : root + "/") + p;
        String c = clean(joined);
        if (root.length() > 0 && !c.equals(root) && !c.startsWith(root + "/")) {
            throw new DshTool.ToolException("路径越界（沙箱外）：" + raw);
        }
        return c;
    }

    /** 规范化 {@code .} / {@code ..} / 重复分隔符，不触碰文件系统。 */
    static String clean(String p) {
        String s = p.replace('\\', '/');
        boolean absolute = s.startsWith("/");
        String[] parts = s.split("/");
        java.util.ArrayList<String> stack = new java.util.ArrayList<String>();
        for (String part : parts) {
            if (part.length() == 0 || part.equals(".")) continue;
            if (part.equals("..")) {
                if (stack.isEmpty()) {
                    // 保留越界证据，交给调用方判定（绝对路径化后必然跑出 root）
                    stack.add("..");
                } else {
                    String top = stack.get(stack.size() - 1);
                    if (top.equals("..")) stack.add("..");
                    else stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(part);
        }
        StringBuilder sb = new StringBuilder();
        if (absolute) sb.append('/');
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(stack.get(i));
        }
        if (sb.length() == 0) sb.append('.');
        return sb.toString();
    }

    /** 相对沙箱根的路径（展示用；越界时原样返回）。 */
    public String display(String absolute) {
        if (absolute == null) return "";
        String a = absolute.replace('\\', '/');
        if (root.length() > 0 && a.startsWith(root + "/")) return a.substring(root.length() + 1);
        return a;
    }

    @Override public String toString() {
        return name(mode) + "@" + root;
    }
}
