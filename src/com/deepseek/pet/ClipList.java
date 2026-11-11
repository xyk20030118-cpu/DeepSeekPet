package com.deepseek.pet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 动作列表的<b>搜索 / 排序 / 拖拽</b>判据（轮次㉚，待办 0m ③）。
 *
 * <h3>为什么要有这个类</h3>
 * 角色包里有一百多个动作，而"动作库""场景改绑""自定义分类"三处都是
 * {@code AlertDialog} 的纯滚动列表 —— 找一个动作只能靠滚，用户报的就是这个。
 * 但搜索/排序/拖拽的**语义**（怎么算命中、排序稳不稳、拖到第几行）
 * 全是纯逻辑，跟 Android 没关系，所以按本工程的老规矩抽成**零依赖纯类**：
 * fpstest [29] 直接编它、直接断言，不用跑设备。
 *
 * <h3>几条定死的语义（都进断言了）</h3>
 * <ul>
 *   <li><b>搜索是"分词 AND"</b>：{@code "点击 回应 3"} 三个词都要命中；
 *       大小写无关，且 {@code - _ 空格 · /} 这些分隔符在两边都被抹掉
 *       ⇒ 打 {@code 点击回应3} 也能找到 {@code 点击回应-3}（素材名里这种分隔符到处都是）。</li>
 *   <li><b>排序是稳定的</b>：同分（同名/都未绑定）保持原顺序 —— 否则每次打开列表
 *       顺序都可能变，用户会觉得"列表自己在跳"。</li>
 *   <li><b>拖拽</b>：{@link #move} 的 {@code to} 是<b>移动后</b>的下标（不是"插到谁前面"），
 *       {@link #dropIndex} 拿每行中线做命中测试、越界钳到首尾。</li>
 * </ul>
 *
 * <p>★ 诚实边界：中文排序按 <b>Unicode 码位</b>（{@code compareTo}），不是拼音序 ——
 * 引入拼音表要几百 KB 的字典，收益只有"按拼音排"，不值当。名称搜索同理（不做拼音首字母）。
 */
public final class ClipList {

    private ClipList() {}

    // ==================== 排序模式 ====================

    /** 原序：角色包 / 动作库自己的顺序（默认，也是"不排序"） */
    public static final int SORT_KEEP = 0;
    public static final int SORT_NAME_ASC = 1;
    public static final int SORT_NAME_DESC = 2;
    /** 已绑定优先（改绑页里 = 当前已绑到这个场景的优先；动作库里 = 已绑到某场景的优先） */
    public static final int SORT_BOUND_FIRST = 3;
    public static final int SORT_UNBOUND_FIRST = 4;
    public static final int SORT_COUNT = 5;

    public static String sortLabel(int mode) {
        switch (mode) {
            case SORT_NAME_ASC: return "名称 \u2191";
            case SORT_NAME_DESC: return "名称 \u2193";
            case SORT_BOUND_FIRST: return "已绑定优先";
            case SORT_UNBOUND_FIRST: return "未绑定优先";
            default: return "原序";
        }
    }

    /** 点一下换下一种（按钮循环，不弹二级对话框 —— 少一层就少一次点空） */
    public static int nextSort(int mode) {
        int m = mode + 1;
        if (m < 0 || m >= SORT_COUNT) return SORT_KEEP;
        return m;
    }

    /** 排序按钮上的文案（设备脚本读它判断"换没换"） */
    public static String sortButtonText(int mode) {
        return "排序：" + sortLabel(mode);
    }

    // ==================== 搜索 ====================

    /**
     * 归一化：抹掉分隔符、转小写。
     *
     * <p>素材名里的分隔符五花八门（{@code 点击回应-3} / {@code 工作_状态} / {@code 吃 Token}），
     * 用户打字时不会记得是哪个 —— 抹掉以后三种写法等价。
     */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '-' || ch == '_' || ch == ' ' || ch == '\u3000' || ch == '\u00B7'
                    || ch == '\u30FB' || ch == '/' || ch == '\uFF0F') continue;
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /** 查询分词：空格 / 逗号 / 顿号 / 分号 / 加号都当分隔；去重、去空 */
    public static String[] tokens(String query) {
        String q = (query == null) ? "" : query.trim();
        if (q.isEmpty()) return new String[0];
        String[] raw = q.split("[\\s,\uFF0C\u3001;\uFF1B+]+");
        List<String> out = new ArrayList<String>();
        for (String r : raw) {
            String n = norm(r);
            if (!n.isEmpty() && !out.contains(n)) out.add(n);
        }
        return out.toArray(new String[0]);
    }

    /** 一条动作名是否命中：<b>所有词都要命中</b>（AND）；空查询 = 全命中 */
    public static boolean matches(String id, String query) {
        String[] tk = tokens(query);
        if (tk.length == 0) return true;
        String n = norm(id);
        for (String t : tk) if (n.indexOf(t) < 0) return false;
        return true;
    }

    /** 过滤（保序）。ids 为 null 返回空表，绝不抛 */
    public static List<String> filter(List<String> ids, String query) {
        List<String> out = new ArrayList<String>();
        if (ids == null) return out;
        for (String id : ids) if (id != null && matches(id, query)) out.add(id);
        return out;
    }

    // ==================== 排序 ====================

    /**
     * 稳定排序。
     *
     * @param ids    待排的动作名
     * @param counts 与 ids <b>一一对应</b>的"已绑定场景数"（可 null ⇒ 全按 0）
     * @param mode   {@link #SORT_KEEP} 等
     */
    public static List<String> sorted(List<String> ids, int[] counts, int mode) {
        List<String> out = new ArrayList<String>();
        if (ids == null) return out;
        out.addAll(ids);
        if (mode == SORT_KEEP || mode < 0 || mode >= SORT_COUNT || out.size() < 2) return out;
        final int m = mode;
        final HashMap<String, Integer> order = new HashMap<String, Integer>();
        final HashMap<String, Integer> cnt = new HashMap<String, Integer>();
        for (int i = 0; i < out.size(); i++) {
            String id = out.get(i);
            if (id == null) continue;
            if (!order.containsKey(id)) order.put(id, i);
            int c = (counts != null && i < counts.length) ? counts[i] : 0;
            if (!cnt.containsKey(id)) cnt.put(id, c);
        }
        java.util.Collections.sort(out, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                int r = compareBy(a, b, cnt, m);
                if (r != 0) return r;
                // ★ 次级键 = 原下标 ⇒ 稳定（同名/同分不会互相换位）
                Integer ia = order.get(a), ib = order.get(b);
                int na = (ia == null) ? 0 : ia, nb = (ib == null) ? 0 : ib;
                return na - nb;
            }
        });
        return out;
    }

    private static int compareBy(String a, String b, HashMap<String, Integer> cnt, int mode) {
        if (a == null || b == null) return (a == null ? 0 : 1) - (b == null ? 0 : 1);
        switch (mode) {
            case SORT_NAME_ASC:
                return norm(a).compareTo(norm(b));
            case SORT_NAME_DESC:
                return norm(b).compareTo(norm(a));
            case SORT_BOUND_FIRST:
            case SORT_UNBOUND_FIRST: {
                Integer ca = cnt.get(a), cb = cnt.get(b);
                boolean ba = (ca != null && ca > 0), bb = (cb != null && cb > 0);
                if (ba != bb) {
                    if (mode == SORT_BOUND_FIRST) return ba ? -1 : 1;
                    return ba ? 1 : -1;
                }
                return norm(a).compareTo(norm(b));
            }
            default:
                return 0;
        }
    }

    // ==================== 拖拽 ====================

    /**
     * 把 {@code from} 处的元素移到 {@code to} 处。
     *
     * <p>{@code to} 是<b>移动之后</b>它应该待的下标（不是"插到谁前面"）——
     * 跟 {@link #dropIndex} 的返回值直接配套，调用方不用再 +1/-1（那种差一错误最烦）。
     * 越界自动钳制；from == to 或下标非法时原样返回。
     */
    public static List<String> move(List<String> ids, int from, int to) {
        List<String> out = new ArrayList<String>();
        if (ids != null) out.addAll(ids);
        int n = out.size();
        if (n < 2 || from < 0 || from >= n) return out;
        int t = to;
        if (t < 0) t = 0;
        if (t > n - 1) t = n - 1;
        if (t == from) return out;
        String v = out.remove(from);
        out.add(t, v);
        return out;
    }

    /**
     * 拖拽落点：给每行<b>中线</b>的 y（升序），返回手指 y 落在第几行；越界钳到首尾。
     * 空表返回 -1。
     *
     * <p>判据是 {@code y <= 中线} 的第一个（不是 {@code <}）：手指正好停在某行中线上时，
     * 算**那一行**而不是下一行 —— 否则"原地按一下"会被判成"往下挪了一格"。
     */
    public static int dropIndex(int[] rowMids, int y) {
        if (rowMids == null || rowMids.length == 0) return -1;
        for (int i = 0; i < rowMids.length; i++) if (y <= rowMids[i]) return i;
        return rowMids.length - 1;
    }

    /** 拖拽手柄那一行提示（也是设备脚本要找的文案） */
    public static String dragHint() {
        return "按住左侧 \u2261 上下拖动排序";
    }

    // ==================== 行文案 / 计数 ====================

    /** 动作库一行：{@code 动作名　（已绑 N 个场景）} / {@code 动作名　（未绑定）} */
    public static String rowLabel(String id, int boundCount) {
        return id + (boundCount > 0
                ? ("\u3000（已绑 " + boundCount + " 个场景）")
                : "\u3000（未绑定）");
    }

    /** 改绑多选一行：权重 &gt;1 才显示 ×N（列表才不至于全是 ×1） */
    public static String bindRowLabel(String id, int weight) {
        return (weight > 1) ? (id + "  \u00D7" + weight) : id;
    }

    /** 「只看已选」开关文案（勾选数也要看得见，否则用户不知道自己在筛什么） */
    public static String onlyPickedText(int picked, int total) {
        return "只看已选（" + picked + " / " + total + "）";
    }

    /**
     * 计数行：把"现在看到的是哪一部分"说清楚 —— 搜索时是命中数，
     * 开了「只看已选」时是显示数。**不写清楚用户会以为动作丢了**。
     */
    public static String countText(int total, int shown, String query) {
        String q = (query == null) ? "" : query.trim();
        if (!q.isEmpty()) return "命中 " + shown + " / " + total + " 个（搜索：" + q + "）";
        if (shown != total) return "显示 " + shown + " / " + total + " 个（只看已选）";
        return "共 " + total + " 个动作";
    }

    /** 按原始顺序取勾选结果（保存顺序不跟着搜索/排序变，免得文件内容每次都不同） */
    public static List<String> pickedOf(List<String> ids, List<String> picked) {
        List<String> out = new ArrayList<String>();
        if (ids == null || picked == null) return out;
        for (String id : ids) if (id != null && picked.contains(id)) out.add(id);
        return out;
    }
}
