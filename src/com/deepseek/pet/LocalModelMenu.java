package com.deepseek.pet;

/**
 * 「🤖 本地模型」面板那几颗按钮、那几行状态、以及卸载/换模型那几句文案的
 * **唯一判据与唯一出处**（轮次69）—— 纯类，**一个 {@code android.*} 都没有**。
 *
 * <h3>★★★ 这个类存在的唯一理由：把"退路不许被堵死"变成**结构**</h3>
 * 用户这一轮报的原话是「**现在安装了之后模型无法更换其他的gguf模型**」。
 * 查下去发现根因不在界面，而在原生侧一个"以前失败过就再也不许试"的进程级粘性标记
 * （见 {@code jni/dspllm_jni.cpp} 与 {@code STATE.md}）——
 * 它让**一次** GPU 失败之后的每一次加载（换模型、重新导入都算）直接返回失败。
 * ⇒ 本轮的硬要求是：<b>GPU 失败 / 文件坏 / 加载失败时，用户仍能
 * 卸载、删除、换成别的、导入新的、切回在线服务商</b>。
 *
 * <p>落实方式有两条，缺一不可：
 * <ol>
 *   <li>**原生侧**：那个粘性标记整个删掉（"失败之后照样能再试"）；</li>
 *   <li>**界面侧**：五条退路的可用性**只由"导入了几个 / 引擎这台机器支不支持"决定** ——
 *       ★★★ 所以这个类里**那个图形加速的类型名（大小写两种写法）一次都不许出现**：
 *       断言直接查全文，反向项会往这里塞一句这样的判断 ⇒ 当场变红。
 *       <b>"GPU 出不出问题"根本不是这些判据的输入</b>，
 *       这是结构性的，不是靠某个人记得别这么写。</li>
 * </ol>
 *
 * <h3>★ 纯类的意义</h3>
 * 不 import android ⇒ 断言套件能拿 javac 把**出厂这一份源码**真编出来、用 java 真跑，
 * 把六组状态（0/1/2 个模型 × 引擎可用/不可用）的退路真值表**跑一遍**。
 */
public final class LocalModelMenu {

    private LocalModelMenu() {}

    // ------------------------------------------------------------------ 五条退路：可用性

    /**
     * 「⏏ 卸载当前模型」**永远可点** ——
     * 载着东西就把它卸掉、把内存交还；什么都没载就如实告诉他"本来就没载入"。
     * ★ 不许因为"没载入"或"上次加载失败"就把这颗按钮藏起来：卸载是**最便宜**的那条退路。
     */
    public static boolean canUnload() { return true; }

    /** 「🔄 更换模型」：导入过就能点（只有 1 个时它会告诉你"再导一个才能切"）。 */
    public static boolean canSwitch(int imported) { return imported >= 1; }

    /** 「🗑 删除本地模型」：导入过就能点（没导入时那一行会说明"当前没有可删的"）。 */
    public static boolean canDelete(int imported) { return imported >= 1; }

    /** 「📥 导入 .gguf 文件…」**永远可点**（引擎在这台机器上用不了，也该让人导进来）。 */
    public static boolean canImport() { return true; }

    /** 「🌐 切回在线服务商」**永远可点** —— 这是本地这条链彻底用不了时的最后一条出路。 */
    public static boolean canUseOnline() { return true; }

    /**
     * ★★★ 退路总表（纯函数，可离线真跑）。
     *
     * <p>返回 {@code [卸载, 更换, 删除, 导入, 切回在线]}。
     * ★★ 注意入参里**没有 GPU 这一维** —— 这正是"GPU 失败不许堵死退路"这条性质
     * 在代码形状上的表达：它连问都不问 GPU 怎么了。
     *
     * @param imported        已导入几个模型
     * @param engineAvailable 这台机器的原生推理库装上了没有
     */
    public static boolean[] routes(int imported, boolean engineAvailable) {
        return new boolean[] {
                canUnload(),
                canSwitch(imported),
                canDelete(imported),
                canImport(),
                canUseOnline(),
        };
    }

    /** 退路的名字（与 {@link #routes} 一一对应；断言/日志共用一份）。 */
    public static String[] routeNames() {
        return new String[] { "卸载", "更换模型", "删除", "导入", "切回在线服务商" };
    }

    // ------------------------------------------------------------------ 按钮文案

    /**
     * 「⏏ 卸载当前模型」那一行。
     *
     * @param loaded 内存里现在载没载
     * @param name   当前模型文件名（没载入时可以给，也可以为 null）
     */
    public static String unloadRow(boolean loaded, String name) {
        if (loaded) {
            return "\u23CF 卸载当前模型（" + (name == null ? "已载入" : name) + "，释放内存）…";
        }
        return "\u23CF 卸载当前模型（当前没有载入）…";
    }

    /** 卸载完的 Toast（★ 说清"内存交还了、文件还在"）。 */
    public static String unloadResult(boolean wasLoaded, String name) {
        if (wasLoaded) {
            return "已卸载" + (name == null ? "" : " " + name)
                    + "：内存已交还（★ 文件还在，下次对话会重新载入）";
        }
        return "内存里本来就没有载入模型（★ 文件还在，不受影响）";
    }

    /** 卸载的日志行（★ 真机上"到底卸没卸掉"的唯一现场证据）。 */
    public static String unloadLog(boolean wasLoaded, String name) {
        if (wasLoaded) {
            return "已卸载内存里的本地模型" + (name == null ? "" : "：" + name)
                    + " —— 内存已交还、★ 文件保留，一个字节都不动，"
                    + "下一次对话会重新载入（几秒到十几秒）";
        }
        return "要求卸载，但内存里本来就没有载入模型（文件保留，一个字节都不动、什么都没做）";
    }

    // ------------------------------------------------------------------ 状态行

    /**
     * 面板里那一行「内存里到底载没载」。
     *
     * <p>★★ 卸载之后必须**看得出来"当前没有载入模型"** —— 用户明确要的就是这件事，
     * 所以这里把两种状态的措辞写得完全不同（不是同一句话加个"没"字）。
     */
    public static String memoryRow(boolean loaded, String name) {
        if (loaded) {
            return "· 内存里已载入" + (name == null ? "当前模型" : "：" + name)
                    + "（对话时直接出话，不再等加载）。"
                    + "点下面「\u23CF 卸载当前模型」可以把它放掉、把内存交还（文件保留）。";
        }
        return "· 内存里**没有**载入模型（已卸载 / 还没载入）——"
                + "第一次用到它时会自动载入（几秒到十几秒），之后常驻。";
    }

    // ------------------------------------------------------------------ 换模型

    /** 换完模型的 Toast。 */
    public static String switchToast(String name) {
        return "已切到 " + name + "（下次对话就用它，首次要载入几秒）";
    }

    /** 换模型的日志行（★ 旧→新都要记，"换了没效果"那类坏法就靠它对上）。 */
    public static String switchLog(String from, String to) {
        return "换了当前模型：" + (from == null ? "（无）" : from) + " → " + to
                + "（内存里那个已卸掉，下一次对话载新的）";
    }
}
