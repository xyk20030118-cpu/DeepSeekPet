package com.deepseek.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 可导入的桌宠模型。
 *
 * 支持的模型包（.zip）结构（两种任选）：
 *   1) 扁平命名：  idle_0.png idle_1.png ... tap_0.png ... drag_0.png ... drop_0.png ... talk_0.png ...
 *   2) 分目录：    idle/0.png tap/0.png drag/0.png ...  （目录名即动作名）
 *
 * 动作名（不区分大小写，可用同义词）：
 *   idle 待机 / tap 被戳 / drag 拖拽 / drop 落地 / talk 说话 / happy 开心 / sad 难过
 *
 * 若只提供 idle 一套，则所有状态都播放 idle。
 * 若模型包只有单张图或一个 GIF/WebP，则退化为静态/动图（由 FloatingService 处理）。
 *
 * 全部帧解码后按屏幕尺寸缩放，缓存于内存。
 */
public class PetModel {

    public static final String ACT_IDLE = "idle";
    public static final String ACT_TAP  = "tap";
    public static final String ACT_DRAG = "drag";
    public static final String ACT_DROP = "drop";
    public static final String ACT_TALK = "talk";
    public static final String ACT_HAPPY = "happy";
    public static final String ACT_SAD  = "sad";

    private final Map<String, List<Bitmap>> frames = new HashMap<>();
    private final Map<String, Integer> fps = new HashMap<>();
    private String name = "";
    private boolean valid = false;

    public boolean isValid() { return valid; }
    public String getName() { return name; }

    public List<Bitmap> frames(String act) {
        List<Bitmap> l = frames.get(act);
        if (l == null || l.isEmpty()) l = frames.get(ACT_IDLE);
        return l;
    }
    public int fps(String act) {
        Integer f = fps.get(act);
        if (f == null) f = fps.get(ACT_IDLE);
        return f == null ? 12 : f;
    }

    /**
     * 当前应当显示的内容标识（动作名 + 帧号）。
     *
     * <p>给 {@code PetView} 做"按需重绘"用：帧号相同时画面**完全一样**，
     * 不必再画一遍。这样重绘次数就从"屏幕刷新率（约 60fps）"
     * 降到"动画自身的帧率"（通常 8~12fps）—— 对常驻悬浮窗来说
     * 这是数量级的省电。
     *
     * <p>只算标识、不绘制，所以可以在 {@code onDraw} 之外安全调用。
     */
    public String frameKey() {
        if (curAct == null) setAction(ACT_IDLE);
        List<Bitmap> list = frames(curAct);
        if (list == null || list.isEmpty()) return null;
        int f = fps(curAct);
        if (f <= 0) f = 12;
        long elapsed = SystemClock.uptimeMillis() - animStart;
        int idx = (int) (elapsed * f / 1000L) % list.size();
        return curAct + "#" + idx;
    }

    /**
     * 距离**下一帧**还有多少毫秒。
     *
     * <p>用于精确调度重绘：动画帧号 {@code idx = (elapsed*f/1000) % n}，
     * 所以只在"经过时间跨过 1/f 秒"时才会变。按这个时间点安排下一次绘制，
     * 就能做到**一次重绘对应一帧动画**，不多不少。
     *
     * <p>这比"每 16ms 醒来检查一次帧号变没变"更省 ——
     * 后者虽然不重绘，但仍会唤醒主线程。
     *
     * @return 毫秒；至少返回 1（避免 0 造成忙循环）
     */
    public long msToNextFrame() {
        if (curAct == null) setAction(ACT_IDLE);
        List<Bitmap> list = frames(curAct);
        if (list == null || list.isEmpty()) return 100;
        int f = fps(curAct);
        if (f <= 0) f = 12;
        long period = Math.max(1L, 1000L / f);          // 每帧毫秒
        long elapsed = SystemClock.uptimeMillis() - animStart;
        long into = elapsed % period;                    // 已在当前帧待了多久
        return Math.max(1L, period - into);
    }

    /** 已加载的所有动作名 */
    public java.util.Set<String> actions() { return frames.keySet(); }

    // ================= 加载 =================

    /** 从 zip 加载模型包。成功返回 true。 */
    public static PetModel loadZip(File zip) {
        PetModel m = new PetModel();
        try {
            ZipFile zf = new ZipFile(zip);
            m.name = zip.getName();
            Map<String, List<ZipEntry>> byAct = new HashMap<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                String low = n.toLowerCase();
                if (!(low.endsWith(".png") || low.endsWith(".webp")
                        || low.endsWith(".jpg") || low.endsWith(".jpeg"))) continue;
                String act = actFromPath(n);
                if (act == null) act = ACT_IDLE;
                List<ZipEntry> lst = byAct.get(act);
                if (lst == null) { lst = new ArrayList<ZipEntry>(); byAct.put(act, lst); }
                lst.add(e);
            }
            if (byAct.isEmpty()) { zf.close(); return m; }

            for (Map.Entry<String, List<ZipEntry>> me : byAct.entrySet()) {
                List<ZipEntry> list = me.getValue();
                java.util.Collections.sort(list, new java.util.Comparator<ZipEntry>() {
                    @Override public int compare(ZipEntry a, ZipEntry b) {
                        return naturalCompare(a.getName(), b.getName());
                    }
                });
                List<Bitmap> bmps = new ArrayList<>();
                for (ZipEntry e : list) {
                    InputStream is = zf.getInputStream(e);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap b = BitmapFactory.decodeStream(is, null, o);
                    if (is != null) is.close();
                    if (b != null) bmps.add(b);
                }
                if (!bmps.isEmpty()) {
                    m.frames.put(me.getKey(), bmps);
                    m.fps.put(me.getKey(), guessFps(me.getKey()));
                }
            }
            zf.close();
            m.valid = m.frames.containsKey(ACT_IDLE) || !m.frames.isEmpty();
            return m;
        } catch (Exception e) {
            return m;
        }
    }

    /** 从目录加载（目录里放 idle/ tap/ ... 子目录） */
    public static PetModel loadDir(File dir) {
        PetModel m = new PetModel();
        try {
            m.name = dir.getName();
            File[] subs = dir.listFiles();
            if (subs == null) return m;
            for (File sub : subs) {
                if (!sub.isDirectory()) continue;
                String act = normalizeAct(sub.getName());
                File[] imgs = sub.listFiles(new java.io.FilenameFilter() {
                    @Override public boolean accept(File d, String n) {
                        String l = n.toLowerCase();
                        return l.endsWith(".png") || l.endsWith(".webp")
                                || l.endsWith(".jpg") || l.endsWith(".jpeg");
                    }
                });
                if (imgs == null || imgs.length == 0) continue;
                java.util.Arrays.sort(imgs, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return naturalCompare(a.getName(), b.getName());
                    }
                });
                List<Bitmap> bmps = new ArrayList<>();
                for (File f : imgs) {
                    Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (b != null) bmps.add(b);
                }
                if (!bmps.isEmpty()) {
                    m.frames.put(act, bmps);
                    m.fps.put(act, guessFps(act));
                }
            }
            m.valid = !m.frames.isEmpty();
        } catch (Exception ignore) {}
        return m;
    }

    // ================= 绘制 =================

    private long animStart = 0;
    private String curAct = null;

    /** 切换动作（若变化则重置动画计时） */
    public void setAction(String act) {
        if (!frames.containsKey(act)) act = ACT_IDLE;
        if (!act.equals(curAct)) {
            curAct = act;
            animStart = SystemClock.uptimeMillis();
        }
    }

    /** 绘制当前动作的当前帧到 canvas（fitCenter） */
    public void draw(Canvas cv) {
        if (curAct == null) setAction(ACT_IDLE);
        List<Bitmap> list = frames(curAct);
        if (list == null || list.isEmpty()) return;
        int f = fps(curAct);
        long elapsed = SystemClock.uptimeMillis() - animStart;
        int idx = (int) (elapsed * f / 1000L) % list.size();
        Bitmap bmp = list.get(idx);
        if (bmp == null) return;

        int vw = cv.getWidth(), vh = cv.getHeight();
        float scale = Math.min(vw / (float) bmp.getWidth(), vh / (float) bmp.getHeight());
        int dw = (int) (bmp.getWidth() * scale);
        int dh = (int) (bmp.getHeight() * scale);
        int left = (vw - dw) / 2, top = (vh - dh) / 2;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(bmp, new Rect(0, 0, bmp.getWidth(), bmp.getHeight()),
                new Rect(left, top, left + dw, top + dh), p);
    }

    // ================= 工具 =================

    private static String actFromPath(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String dir = slash > 0 ? p.substring(0, slash) : "";
        String file = slash >= 0 ? p.substring(slash + 1) : p;
        // 1) 目录名是动作名
        if (!dir.isEmpty()) {
            String lastDir = dir.substring(dir.lastIndexOf('/') + 1);
            String a = normalizeAct(lastDir);
            if (a != null) return a;
        }
        // 2) 文件名前缀是动作名：idle_0.png / tap-1.png
        String base = file;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        for (String sep : new String[]{"_", "-", "."}) {
            int i = base.indexOf(sep);
            if (i > 0) {
                String a = normalizeAct(base.substring(0, i));
                if (a != null) return a;
            }
        }
        // 3) 整段文件名
        return normalizeAct(base);
    }

    private static String normalizeAct(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase();
        // 去掉结尾数字/序号
        switch (k) {
            case "idle": case "stand": case "wait": case "default":
            case "待机": case "默认": case "静止": return ACT_IDLE;
            case "tap": case "touch": case "poke": case "click":
            case "戳": case "点击": case "被戳": return ACT_TAP;
            case "drag": case "shake": case "carry": case "hold":
            case "拖拽": case "拖动": case "拎": return ACT_DRAG;
            case "drop": case "land": case "fall":
            case "落地": case "放下": case "掉落": return ACT_DROP;
            case "talk": case "speak": case "chat":
            case "说话": case "聊天": return ACT_TALK;
            case "happy": case "joy": case "smile":
            case "开心": case "高兴": return ACT_HAPPY;
            case "sad": case "cry":
            case "难过": case "伤心": return ACT_SAD;
        }
        // 前缀匹配（idle0 / idle_1 已在上面剥掉，这里兜底）
        if (k.startsWith("idle")) return ACT_IDLE;
        if (k.startsWith("tap") || k.startsWith("poke")) return ACT_TAP;
        if (k.startsWith("drag")) return ACT_DRAG;
        if (k.startsWith("drop")) return ACT_DROP;
        if (k.startsWith("talk")) return ACT_TALK;
        if (k.startsWith("happy")) return ACT_HAPPY;
        if (k.startsWith("sad")) return ACT_SAD;
        return null;
    }

    private static int guessFps(String act) {
        switch (act) {
            case ACT_TALK: return 14;
            case ACT_TAP:  return 16;
            case ACT_DRAG: return 12;
            default:       return 12;
        }
    }

    /** 自然排序：2 < 10 */
    private static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                long na = Long.parseLong(a.substring(si, i));
                long nb = Long.parseLong(b.substring(sj, j));
                if (na != nb) return Long.compare(na, nb);
            } else {
                if (ca != cb) return ca - cb;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    /** 递归删除目录 */
    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        f.delete();
    }

    /** 解压 zip 到目录 */
    public static boolean unzip(File zip, File dstDir) {
        try {
            dstDir.mkdirs();
            ZipFile zf = new ZipFile(zip);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                File out = new File(dstDir, e.getName());
                // 防目录穿越
                if (!out.getCanonicalPath().startsWith(dstDir.getCanonicalPath())) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                InputStream is = zf.getInputStream(e);
                FileOutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                is.close();
            }
            zf.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
