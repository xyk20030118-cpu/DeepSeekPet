package com.deepseek.pet.dsh;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 工作区 —— 一个目录 + 一个会话目录（纯类，零 Android 依赖）。
 *
 * <h3>磁盘布局（老路径原样保留，老会话一条不丢）</h3>
 * <pre>
 * files/dsh/workspace/                ← 默认工作区（v7.6 起就是这个路径）
 * files/dsh/sessions/&lt;id&gt;.jsonl      ← 默认工作区的会话
 * files/dsh/workspaces/&lt;id&gt;/         ← 新建的工作区
 * files/dsh/workspaces/&lt;id&gt;/name.txt ← 显示名（人起的名字，目录名是 ASCII 安全名）
 * files/dsh/sessions/&lt;id&gt;/           ← 新建工作区的会话（id 以 ws- 开头，不会和老会话撞）
 * </pre>
 *
 * <p>★ 为什么不做"迁移"：默认工作区沿用老路径 ⇒ 升级后打开 App 还是原来的会话；
 * 只有用户主动新建的工作区才落到 {@code workspaces/} 下。迁移代码是"最不该写"的那类代码。
 *
 * <p>★ 为什么目录名要 slug：用户可以用中文/空格/emoji 起名，但目录名要能跨文件系统、
 * 不能有路径分隔符、也不能是 {@code ..}。显示名单独存在 {@code name.txt} 里。
 */
public final class DshWorkspace {

    /** 默认工作区 id（空串 = 老路径）。 */
    public static final String DEFAULT_ID = "";
    /** 默认工作区显示名（参考图左栏里那个 "DS"）。 */
    public static final String DEFAULT_NAME = "DS";
    /** 名字长度上限（超过就把左栏撑坏）。 */
    public static final int MAX_NAME = 24;

    public final String id;
    public final String name;
    /** 模型能读写的根目录。 */
    public final File dir;
    /** 本工作区的会话日志目录。 */
    public final File sessions;

    public DshWorkspace(String id, String name, File dir, File sessions) {
        this.id = id == null ? DEFAULT_ID : id;
        this.name = (name == null || name.trim().length() == 0) ? DEFAULT_NAME : name.trim();
        this.dir = dir;
        this.sessions = sessions;
    }

    public boolean isDefault() {
        return DEFAULT_ID.equals(id);
    }

    /** 默认工作区：沿用 v7.6 的 workspace/ 与 sessions/（老会话一条不丢）。 */
    public static DshWorkspace defaultWs(File base) {
        File b = base == null ? new File(".") : base;
        return new DshWorkspace(DEFAULT_ID, DEFAULT_NAME,
                new File(b, "workspace"), new File(b, "sessions"));
    }

    /**
     * 列出全部工作区：默认的排最前，其余按显示名排序。
     *
     * <p>只认 {@code workspaces/} 下的**目录**；目录里没有 {@code name.txt} 就用目录名当显示名
     * （用户可能在文件管理器里直接建了一个目录）。
     */
    public static List<DshWorkspace> list(File base) {
        File b = base == null ? new File(".") : base;
        List<DshWorkspace> out = new ArrayList<DshWorkspace>();
        out.add(defaultWs(b));
        File root = new File(b, "workspaces");
        File[] dirs = root.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs, new Comparator<File>() {
                @Override public int compare(File a, File c) {
                    return a.getName().compareToIgnoreCase(c.getName());
                }
            });
            for (File d : dirs) {
                if (d == null || !d.isDirectory()) continue;
                String id = d.getName();
                if (id.length() == 0 || id.startsWith(".")) continue;
                out.add(new DshWorkspace(id, readName(d, id), d, new File(new File(b, "sessions"), id)));
            }
        }
        // 默认工作区永远在最前（它不参与上面的排序）
        return out;
    }

    /** 按 id 找；找不到返回 null（调用方决定回退到默认）。 */
    public static DshWorkspace find(File base, String id) {
        List<DshWorkspace> all = list(base);
        for (DshWorkspace w : all) {
            if (w.id.equals(id == null ? DEFAULT_ID : id)) return w;
        }
        return null;
    }

    /** 校验新名字：能建返回 null，不能建返回一句人话原因。 */
    public static String validate(File base, String raw) {
        String n = raw == null ? "" : raw.trim();
        if (n.length() == 0) return "名字不能为空";
        if (n.length() > MAX_NAME) return "名字最多 " + MAX_NAME + " 个字";
        if (n.startsWith(".")) return "名字不能以点开头";
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < ' ') return "名字里不能有控制字符";
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                return "名字里不能有 / \\ : * ? \" < > |";
            }
        }
        for (DshWorkspace w : list(base)) {
            if (w.name.equalsIgnoreCase(n)) return "已有同名工作区：" + w.name;
        }
        return null;
    }

    /**
     * 新建一个工作区。
     *
     * @return 建好的工作区；名字不合法/建目录失败返回 null（原因用 {@link #validate} 取）
     */
    public static DshWorkspace create(File base, String raw) {
        File b = base == null ? new File(".") : base;
        if (validate(b, raw) != null) return null;
        String name = raw.trim();
        File root = new File(b, "workspaces");
        if (!root.isDirectory() && !root.mkdirs()) return null;
        String slug = slugOf(name);
        File dir = new File(root, slug);
        int n = 2;
        while (dir.exists() && n < 200) {
            dir = new File(root, slug + "-" + n);
            n++;
        }
        if (dir.exists() || !dir.mkdirs()) return null;
        writeName(dir, name);
        File sessions = new File(new File(b, "sessions"), dir.getName());
        if (!sessions.isDirectory()) sessions.mkdirs();
        return new DshWorkspace(dir.getName(), name, dir, sessions);
    }

    /** 名字 → 安全目录名：只留 ASCII 字母数字与 -，其余丢；空/中文名走哈希。 */
    public static String slugOf(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.US);
        StringBuilder sb = new StringBuilder("ws-");
        for (int i = 0; i < n.length() && sb.length() < 40; i++) {
            char c = n.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (c == '-' || c == '_' || c == ' ') sb.append('-');
        }
        // 去掉连续的 '-'（"a  b" 不要变成 "a--b"）
        String s = sb.toString().replaceAll("-+", "-").replaceAll("-$", "");
        if (s.length() <= 3) {
            s = "ws-" + Integer.toHexString((name == null ? "" : name).hashCode());
        }
        return s;
    }

    public static File nameFile(File dir) {
        return new File(dir, "name.txt");
    }

    /** 读显示名；读不到就用目录名（可能带 ws- 前缀，但总比空白强）。 */
    public static String readName(File dir, String fallback) {
        String s = DshFs.readTextQuiet(nameFile(dir), 4096);
        if (s == null) return fallback;
        s = s.trim();
        return s.length() == 0 ? fallback : s;
    }

    public static boolean writeName(File dir, String name) {
        try {
            DshFs.writeText(nameFile(dir), (name == null ? "" : name.trim()) + "\n");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 相对时间 —— 参考图左栏里那种「7小时 / 1天 / 3天」。
     *
     * <p>纯函数（now 由调用方给）⇒ 可以直接断言，不用等一天再跑测试。
     * 未来时间按「刚刚」处理（设备时钟回拨时不显示"-3小时"这种吓人的东西）。
     */
    public static String relativeTime(long now, long then) {
        if (then <= 0) return "";
        long d = now - then;
        if (d < 0) d = 0;
        long min = d / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + "分钟";
        long h = min / 60L;
        if (h < 24) return h + "小时";
        long day = h / 24L;
        if (day < 30) return day + "天";
        long mon = day / 30L;
        if (mon < 12) return mon + "个月";
        return (mon / 12L) + "年";
    }
}
