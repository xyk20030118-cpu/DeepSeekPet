package com.deepseek.pet;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 本地 GGUF 模型的**落盘位置与清单**（轮次62 · 第二轮 · 导入层）。
 *
 * <h3>★★ 落盘位置为什么是 {@code filesDir/models/}</h3>
 * <ul>
 *   <li><b>不用外部存储</b>：Android 10+ 的分区存储让"自己挑一个外部目录"变得又麻烦又容易被
 *       别的应用/清理软件动到；而 {@code filesDir} 是**应用私有**、随应用卸载自动清干净 ——
 *       一个几百 MB 的模型，用户卸载后不该继续躺在别人手机上；</li>
 *   <li><b>不用 {@code cacheDir}</b>：cacheDir 会在**系统空间紧张时被系统主动清掉**
 *       （用户会看到"模型莫名其妙没了"，而且每次都得重导）；</li>
 *   <li>模型是"用户自己导入的资产"，语义上就是私有数据 ⇒ {@code filesDir} 最合适；</li>
 *   <li>目录名单独放在 {@link ModelFile#DIR}，改一处就够。</li>
 * </ul>
 *
 * <h3>★ 允许并存多个，但**只有一个"当前"**</h3>
 * 一个手机可以放好几个小模型（0.5B 的日常聊天 + 3B 的偶尔问难题），
 * 但对话时必须有明确的一个 ⇒ 当前模型的名字记在 {@link Prefs#getLocalModel}。
 * ★ 记的是**文件名**不是绝对路径：路径会随重装/清 data 失效，名字不会。
 *
 * <h3>★ 目录清单的口径</h3>
 * 只有 {@code *.gguf} 算模型（{@code *.gguf.part} 是半拉文件，不算、且会被清）。
 */
public final class ModelStore {

    private static final String TAG = "DSPModel";

    private ModelStore() {}

    /** 模型目录（不存在就建）。 */
    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), ModelFile.DIR);
        if (!d.isDirectory()) {
            // ★ mkdirs 失败不抛：调用方按"文件在不在"分话术即可（不许把桌宠搞崩）
            if (!d.mkdirs()) Log.w(TAG, "建模型目录失败：" + d.getAbsolutePath());
        }
        return d;
    }

    /** 目录里现有模型文件（按名字排序；不含 {@code .part}）。 */
    public static List<File> list(Context ctx) {
        File d = dir(ctx);
        File[] fs = d.listFiles();
        List<File> out = new ArrayList<File>();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && ModelFile.isModelName(f.getName())) out.add(f);
            }
        }
        Collections.sort(out, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    /** 名字 → 文件（名字会先净化；净化不了 ⇒ null）。 */
    public static File file(Context ctx, String name) {
        String n = ModelFile.sanitizeName(name);
        if (n == null) return null;
        return new File(dir(ctx), n);
    }

    /**
     * 当前选中的模型文件名。
     *
     * <p>★★ 文件**真的在**才算数：用户可能在文件管理器/系统设置里清了数据，
     * 那时偏好里还留着名字 —— 直接返回它会让界面显示一个不存在的模型，
     * 而对话时 {@code LocalLlm.load} 才会失败（"看起来导入了，其实没有"）。
     * 所以这里顺手把失效的选择清掉。
     */
    public static String current(Context ctx) {
        String n = Prefs.getLocalModel(ctx);
        if (n == null) return null;
        File f = new File(dir(ctx), n);
        if (!f.isFile()) {
            Log.w(TAG, "当前模型的文件已不在，清掉选择：" + n);
            Prefs.setLocalModel(ctx, null);
            return null;
        }
        return n;
    }

    /** 当前模型文件；没有 ⇒ null。 */
    public static File currentFile(Context ctx) {
        String n = current(ctx);
        return n == null ? null : new File(dir(ctx), n);
    }

    public static void setCurrent(Context ctx, String name) {
        Prefs.setLocalModel(ctx, name);
        Log.i(TAG, "当前本地模型 = " + name);
    }

    /** 已占用的字节数（设置页显示"共占 X MB"用）。 */
    public static long usedBytes(Context ctx) {
        long n = 0;
        for (File f : list(ctx)) n += f.length();
        return n;
    }

    /**
     * 目标卷的**可用**空间。
     *
     * <p>★ 用 {@code getUsableSpace()} 而不是 {@code getFreeSpace()}：
     * 后者会把系统给应用留的"配额/保留块"也算成可用，在快满的机器上
     * 会得出"够"然后写到一半失败。
     */
    public static long freeBytes(Context ctx) {
        try {
            return dir(ctx).getUsableSpace();
        } catch (Throwable t) {
            Log.w(TAG, "读可用空间失败：" + t);
            return -1;
        }
    }

    /**
     * 删掉一个模型。
     *
     * @return true = 删成功（或本来就不在）；false = 删不掉（文件被占着等）
     */
    public static boolean delete(Context ctx, String name) {
        File f = file(ctx, name);
        if (f == null) return false;
        boolean ok = !f.exists() || f.delete();
        if (ok) {
            // ★ 删的正好是当前选中的 ⇒ 选择也要清掉，否则界面会指着一个不存在的文件
            if (ModelFile.sameName(Prefs.getLocalModel(ctx), name)) Prefs.setLocalModel(ctx, null);
            Log.i(TAG, "已删除本地模型 " + name);
        } else {
            Log.w(TAG, "删除失败：" + f.getAbsolutePath());
        }
        return ok;
    }

    /**
     * 清掉目录里的半拉文件（上一次导入被中途杀掉留下的）。
     *
     * <p>★ 为什么必须清：{@code xxx.gguf.part} 既不是完整模型、又白占几百 MB，
     * 而且用户会在文件管理器里看到"两个几乎同名的文件"而困惑。
     * 导入开始前清一次就够 —— 同一时刻不会有两个导入在跑（界面是模态进度框）。
     *
     * @return 清掉几个
     */
    public static int cleanStaleParts(Context ctx) {
        File d = dir(ctx);
        File[] fs = d.listFiles();
        int n = 0;
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && ModelFile.isPartName(f.getName()) && f.delete()) {
                    n++;
                    Log.i(TAG, "清掉半拉文件 " + f.getName());
                }
            }
        }
        return n;
    }

    /** 设置页面板上那句"已导入 N 个 · 共占 X · 可用 Y"。 */
    public static String describe(Context ctx) {
        List<File> fs = list(ctx);
        return "已导入 " + fs.size() + " 个模型 · 共占 " + ModelFile.humanSize(usedBytes(ctx))
                + " · 本机可用空间 " + ModelFile.humanSize(freeBytes(ctx));
    }

    /** 目录里所有模型的名字（下拉/单选列表用）。 */
    public static String[] names(Context ctx) {
        List<File> fs = list(ctx);
        String[] out = new String[fs.size()];
        for (int i = 0; i < fs.size(); i++) out[i] = fs.get(i).getName();
        return out;
    }

    /** 把名字数组按"当前在前"的顺序排（界面显示用；不改盘上任何东西）。 */
    public static List<String> sortedWithCurrentFirst(Context ctx) {
        List<String> ns = new ArrayList<String>(Arrays.asList(names(ctx)));
        final String cur = current(ctx);
        if (cur == null) return ns;
        // ★ 大小写不敏感地去掉：某些文件系统上 偏好里的 "A.gguf" 与盘上的 "a.gguf"
        //   是同一个文件，用 equals 去不掉 ⇒ 列表里会出现两条一样的。
        for (int i = ns.size() - 1; i >= 0; i--) {
            if (ModelFile.sameName(ns.get(i), cur)) ns.remove(i);
        }
        ns.add(0, cur);
        return ns;
    }
}
