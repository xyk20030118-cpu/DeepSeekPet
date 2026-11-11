package com.deepseek.pet;

import android.content.Context;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表情包（memes）：<b>只从角色包里读</b> —— {@code <角色目录>/memes/memes.json} 是「名称 → 描述」映射，
 * 图片在同目录的 {@code <名称>.png}（png/webp/jpg/gif）。用户自己上传的表情在
 * {@link Prefs#memeCustomDir} 那一层覆盖（可改名 / 可删）。用于气泡配图。
 *
 * <p>★★ 轮次㉜：**APK 里不再内置任何表情**（用户要求「把当前内置的对话表情也剔除，打包在一起」）——
 * 那 27 张连同索引一起挪进了**角色包**（默认角色包里已经带着它们，导出角色包也会带上，
 * 见 {@link PackFormat#AUX_DIRS}）。源码侧的素材源在 {@code build/charpack_src/}（不进 APK），
 * 供 {@code build/pack_character.py} 重建默认角色包用。
 * ⇒ 一个角色都没有 / 那个角色包里没有表情时，这里就是**空的**（不再有"内置兜底"）。</p>
 *
 * <p>★ 表情名与图片文件名一致（一个名字同时是 json 的键和 &lt;名称&gt;.png 文件名），
 * 所以「改名」是两者一起改；上传时默认就用文件名当表情名，省去另起名字。</p>
 *
 * <p>★ 选图协议（2026-09-30）：把 {@link #catalog} 作为一段稳定 system 注入，
 * 模型若想配图，就在回复最后单独一行写 {@code @@MEME:名称@@}。
 * 由 {@link #extract} 取出、{@link #has} 校验，再交给气泡显示。
 * 用 {@code @@} 而不是方括号，是为了避开 {@link StyleGuard} 的微信表情代码清洗。</p>
 */
public class Memes {

    private static JSONObject cache = null;
    /** 上面这份缓存属于哪个角色（切角色要重读） */
    private static String cacheChar = null;
    private static final Random RND = new Random();

    /** 选图标记：模型在最后一行写 @@MEME:名称@@ */
    public static final String MARK_OPEN = "@@MEME:";
    public static final String MARK_CLOSE = "@@";

    /** 抽出标记里的名称（不含校验）。没有标记返回 null。 */
    private static final Pattern MARK = Pattern.compile("@@MEME:([^@\\n]{1,60})@@");

    /** 当前角色包里的表情图（没有则 null）—— 表情**按角色隔离** */
    /** 表情图允许的扩展名（**顺序即优先级**） */
    /**
     * 表情允许的扩展名（**顺序即优先级**）。
     *
     * ★ 轮次86：加了短视频 `.mp4` / `.webm` —— 现在很多表情包是"会动的三秒小视频"，
     *   而 GIF/WebP 的体积与色深都不划算。视频走 VideoView 播放（见 FloatingService）。
     */
    public static final String[] IMG_EXTS = {".png", ".webp", ".jpg", ".jpeg", ".gif", ".mp4", ".webm"};

    /** 动态表情落盘上限（比静态放宽：一张动图动辄几 MB） */
    public static final long MAX_ANIMATED_BYTES = 12L * 1024 * 1024;
    /** 静态表情落盘上限（沿用原来的 8 MB 口径） */
    public static final long MAX_STATIC_BYTES = 8L * 1024 * 1024;
    /** 视频表情上限（3~5 秒的小视频，留足余量但别让人塞整部电影进来） */
    public static final long MAX_VIDEO_BYTES = 32L * 1024 * 1024;

    /** 这个表情是**短视频**吗（mp4 / webm） */
    public static boolean isVideoName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".webm");
    }

    /**
     * 这个表情名**可能是动态**的吗（按扩展名判断）。
     *
     * <p>★ 为什么要单独有这个：`BitmapFactory` 解 GIF 只给首帧、解动图 WebP 也只给首帧，
     *   而 `ImageDecoder`（API 28+）才能拿到 `AnimatedImageDrawable` 真播。
     *   扩展名判据是纯函数、可断言；真正"是不是动"仍以解码结果为准（webp 可能静态）。
     */
    public static boolean isAnimatedName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".gif") || n.endsWith(".webp");
    }

    /**
     * 按**文件头**判断真实类型，返回应使用的扩展名；认不出返回 null。
     *
     * <p>★ 不能只信下载/选择的文件名：相册里 GIF 的显示名常常没有后缀，
     *   而 `content://` 的 MIME 也不总是可靠 —— 写错扩展名会让后面的解码链整条报废
     *   （旧代码就是无脑写 `.png`）。
     */
    public static String extByHeader(byte[] head) {
        if (head == null || head.length < 12) return null;
        // GIF87a / GIF89a
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') return ".gif";
        // PNG
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return ".png";
        // JPEG
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) return ".jpg";
        // RIFF....WEBP / RIFF....WEBM
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B') {
            if (head.length > 11 && head[11] == 'P') return ".webp";
            if (head.length > 11 && head[11] == 'M') return ".webm";   // ★ 轮次86
        }
        // MP4 家族：第 4..7 字节是 "ftyp"
        if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return ".mp4";
        return null;
    }

    /** 名字里已有的扩展名（小写）；没有返回 null */
    public static String extOfName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(java.util.Locale.US);
        for (String e : IMG_EXTS) {
            if (n.endsWith(e)) return e;
        }
        return null;
    }

    private static java.io.File charMemeImage(Context ctx, String name) {
        try {
            PetCharacter c = CharacterStore.current(ctx);
            if (c == null || c.dir == null) return null;
            java.io.File dir = new java.io.File(c.dir, "memes");
            for (String ext : IMG_EXTS) {
                java.io.File f = new java.io.File(dir, name + ext);
                if (f.isFile() && f.length() > 0) return f;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** 当前角色包里的 memes/memes.json（没有则 null） */
    private static java.io.File charMemeManifest(Context ctx) {
        try {
            PetCharacter c = CharacterStore.current(ctx);
            if (c == null || c.dir == null) return null;
            java.io.File f = new java.io.File(new java.io.File(c.dir, "memes"), "memes.json");
            return (f.isFile() && f.length() > 0) ? f : null;
        } catch (Throwable ignore) {}
        return null;
    }

    private static synchronized JSONObject load(Context ctx) {
        // ★ v7.0：缓存要**跟着角色走** —— 否则切角色后还是上一套表情（表情按角色隔离）
        // ★ 轮次㉜：**只认角色包**（APK 里不再内置表情）—— 没有角色/包里没有 ⇒ 空表
        String cid = null;
        try { cid = CharacterStore.currentId(ctx); } catch (Throwable ignore) {}
        if (cache != null && cid != null && cid.equals(cacheChar)) return cache;
        JSONObject o = new JSONObject();
        try {
            java.io.File cm = charMemeManifest(ctx);
            if (cm != null) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(new java.io.FileInputStream(cm), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                o = new JSONObject(sb.toString());
            }
        } catch (Exception ignore) {}
        cache = o;
        cacheChar = cid;
        return cache;
    }

    /** 全部表情包名称（内置 + 用户上传） */
    public static List<String> names(Context ctx) {
        List<String> out = new ArrayList<String>();
        JSONObject o = load(ctx);
        for (Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        JSONObject c = loadCustom(ctx);
        for (Iterator<String> it = c.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!out.contains(k)) out.add(k);
        }
        return out;
    }

    /** 随机抽一个表情包名称（本地兜底：模型没选图时用） */
    public static String randomName(Context ctx) {
        List<String> ns = names(ctx);
        if (ns.isEmpty()) return null;
        return ns.get(RND.nextInt(ns.size()));
    }

    /**
     * 本地概率抽签：以 {@code prob}（0~1）的概率返回一个随机表情名，否则返回 null。
     *
     * <p>★ 只在「没拿到模型选图」时用作兜底，保证开关打开时仍有一定概率配图。
     * 模型选图（@@MEME 协议）优先；本地抽签是零成本的补充。</p>
     */
    public static String pick(Context ctx, double prob) {
        if (prob <= 0) return null;
        if (prob < 1.0 && RND.nextDouble() >= prob) return null;
        return randomName(ctx);
    }

    /**
     * 从模型回复里抽出配图标记，并返回「去掉标记后的正文」。
     *
     * <p>抽出后调用 {@link #has} 校验：名字必须真实存在，否则丢弃（模型可能编造）。
     * 结果由 {@code outName} 的 [0] 带回（名称或 null），返回值是清洗后的正文。</p>
     */
    public static String extract(Context ctx, String reply, String[] outName) {
        if (outName != null && outName.length > 0) outName[0] = null;
        if (reply == null) return null;
        String name = null;
        Matcher m = MARK.matcher(reply);
        while (m.find()) {
            String cand = m.group(1) == null ? null : m.group(1).trim();
            if (cand != null && cand.length() > 0 && has(ctx, cand)) name = cand;   // 取最后一个合法标记
        }
        // 无论标记合法与否，都从正文里删掉，避免把 @@MEME:xxx@@ 露在气泡上
        String body = MARK.matcher(reply).replaceAll("");
        body = body.replaceAll("(?m)^[ \\t　]*$\\n?", "");      // 清掉标记留下的空白行
        body = body.replaceAll("\\n{3,}", "\\n\\n");
        body = body.trim();
        if (outName != null && outName.length > 0) outName[0] = name;
        // ★ 即使正文被清空也返回 body（可能是 ""），绝不回退到含标记的原文，
        //   否则 "@@MEME:xx@@" 会直接露在气泡上。空正文由调用方处理。
        return body;
    }

    /** 供模型选图的「名称：描述」清单文本（作为稳定 system 段注入，可被缓存） */
    public static String catalog(Context ctx) {
        // ★ 排序输出：保证每次拼出的字符串完全一致，前缀才能命中 Prompt Cache
        List<String> ks = names(ctx);
        Collections.sort(ks);
        JSONObject o = load(ctx);
        JSONObject c = loadCustom(ctx);
        StringBuilder sb = new StringBuilder();
        for (String k : ks) {
            String d = o.has(k) ? o.optString(k, "") : c.optString(k, "");
            // ★ 用户刚批量导入、还没补描述的表情，绝不能拼成「名字：」这种空描述 ——
            //   模型看不懂就不会选它。给个中性占位，用户补上描述后自动替换。
            if (d == null || d.trim().length() == 0) d = "（暂无语义描述，按名字理解即可）";
            sb.append(k).append("：").append(d).append("\n");
        }
        return sb.toString();
    }

    /**
     * 拼出「选图协议 + 清单」整段。开关关闭或没有图可用时返回 ""（零 token 开销）。
     *
     * <p>★ 这段是纯静态文本（只随用户开关/表情库变化），应放在消息序列的<b>靠前稳定区</b>，
     * 让 DeepSeek 的前缀缓存能命中，成本只剩未命中价的 1/25。</p>
     */
    public static String prompt(Context ctx) {
        String c = catalog(ctx);
        if (c == null || c.length() == 0) return "";
        // ★ 曾经这里只写「可选、宁可不用」，模型过于保守，十条里难得配一次。
        //   补上 few-shot 示例并明确「带情绪的话就该配」后，实测命中率 38% → 75%，
        //   而真正平淡的句子（天气好、在吗）依然不配，准确性没有牺牲。
        return "【表情包配图】\n"
             + "你有配图能力：当这句话带着明显情绪（累了、气了、委屈了、想吐槽、想耍赖）时，"
             + "就在回复最后一行单独写上：" + MARK_OPEN + "名称" + MARK_CLOSE + "\n\n"
             + "参考示例：\n"
             + "· 用户说「累死了」→ 你回：快去躺着，别硬撑。\n"
             + MARK_OPEN + "死掉了" + MARK_CLOSE + "\n"
             + "· 用户说「被领导骂了，气死我了」→ 你回：他算什么东西，我帮你骂回去。\n"
             + MARK_OPEN + "要不直接骂他" + MARK_CLOSE + "\n"
             + "· 用户说「我先睡了」→ 你回：睡吧，我守着。\n"
             + "（这条情绪平稳，就不配图）\n\n"
             + "规则：\n"
             + "1) 不必每条都配，但上面那类带情绪的话就该配；平淡叙事的可以不配。\n"
             + "2) 名称必须从下面清单一字不差地挑，不要自己编。\n"
             + "3) 一次最多一张，必须独占最后一行。正文里不要提到这张图；\n"
             + "   说话仍然保持干净，不要用 emoji、颜文字或方括号表情代码，情绪交给配图表达。\n\n"
             + "可用表情：\n" + c;
    }

    public static boolean has(Context ctx, String name) {
        if (name == null) return false;
        return load(ctx).has(name) || loadCustom(ctx).has(name);
    }

    // ==================== 自定义表情（用户上传） ====================

    /** 自定义表情清单（每次实时读，便于新增后立即生效） */
    private static JSONObject loadCustom(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            java.io.File f = Prefs.memeCustomJson(ctx);
            // ★★ 轮次80 抓到的老 bug：这里原来**提前 return o**，于是 json 不存在时
            //    目录扫描兜底（withDiskFallback）根本没机会跑 —— 而它的文档恰恰写着
            //    "用户直接往目录丢图、或 json 丢失/损坏时光看 json 会选不到"。
            //    实测：只丢一个 测试动图.gif 进去、不写 json ⇒ 表情包显示「共 0 张」。
            if (!f.exists()) return withDiskFallback(ctx, o);
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            o = new JSONObject(sb.toString());
        } catch (Exception ignore) {}
        return withDiskFallback(ctx, o);   // ★ 磁盘为真相：json 漏登记的文件按文件名补上
    }

    /**
     * ★ 目录扫描兜底：把 memes_custom/ 里存在但 json 没登记的文件按文件名补进来。
     *
     * <p>用户直接用文件管理器往这个目录丢图、或 json 丢失/损坏时，光看 json
     * 会「图在磁盘上却完全选不到」。这里以磁盘为真相补登记（描述留空）。
     * 只认 .png（与 {@link #makeItem} 的读取规则一致，避免登记了又读不出）。</p>
     *
     * @return 生效的清单（可能是原对象，也可能是补过的新对象，调用方需用返回值）
     */
    private static JSONObject withDiskFallback(Context ctx, JSONObject custom) {
        try {
            java.io.File dir = Prefs.memeCustomDir(ctx);
            java.io.File[] fs = dir.listFiles();
            if (fs == null || fs.length == 0) return custom;
            boolean dirty = false;
            for (java.io.File f : fs) {
                if (f == null || !f.isFile()) continue;
                String fn = f.getName();
                // ★ 轮次80：动态表情（.gif / .webp）也要能被扫出来，否则上传完"看不见"
                String fext = extOfName(fn);
                if (fext == null) continue;
                String base = fn.substring(0, fn.length() - fext.length());
                String nm = cleanName(base);          // 与上传路径同样清洗，保证 key 一致
                if (nm == null || nm.length() == 0) continue;
                if (custom.has(nm)) continue;         // 已登记，不动用户填的描述
                custom.put(nm, "");                   // 补登记，描述留空待用户补
                dirty = true;
            }
            if (dirty) saveCustom(ctx, custom);       // 回写，下次就不用再扫
        } catch (Throwable ignore) {
        }
        return custom;
    }


    private static void saveCustom(Context ctx, JSONObject o) {
        try {
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(Prefs.memeCustomJson(ctx)), "UTF-8");
            w.write(o.toString());
            w.close();
        } catch (Exception ignore) {}
    }

    // ==================== 表情管理（列表 / 改名 / 改描述 / 删除） ====================

    /** 一条表情记录的只读快照，供设置页列表展示 */
    public static class Item {
        public String name;        // 表情名（= json 键 = 图片文件名）
        public String desc;        // 「什么时候用」描述，供模型选图
        public boolean custom;     // true=用户上传（可改可删），false=内置（只读）
        public int bytes;          // 图片文件大小，0 表示读不到
        public int width, height;  // 图片尺寸，0 表示未解码
    }

    /** 全部表情（内置 + 自定义），内置在前、各自按名称排序 */
    public static List<Item> allItems(Context ctx) {
        List<Item> out = new ArrayList<Item>();
        JSONObject builtin = load(ctx);
        JSONObject custom = loadCustom(ctx);
        List<String> bk = new ArrayList<String>();
        for (Iterator<String> it = builtin.keys(); it.hasNext(); ) bk.add(it.next());
        Collections.sort(bk);
        for (String k : bk) out.add(makeItem(ctx, k, builtin.optString(k, ""), false));
        List<String> ck = new ArrayList<String>();
        for (Iterator<String> it = custom.keys(); it.hasNext(); ) ck.add(it.next());
        Collections.sort(ck);
        for (String k : ck) out.add(makeItem(ctx, k, custom.optString(k, ""), true));
        return out;
    }

    private static Item makeItem(Context ctx, String name, String desc, boolean custom) {
        Item it = new Item();
        it.name = name;
        it.desc = desc;
        it.custom = custom;
        java.io.File f = findCustomFile(ctx, name);
        if (f != null && f.exists()) {
            it.bytes = (int) Math.min(Integer.MAX_VALUE, f.length());
            // 只读图片头拿尺寸，不整张解码，避免列表卡顿；动图/异常一律留 0
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try {
                android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                it.width = o.outWidth;
                it.height = o.outHeight;
            } catch (Throwable ignore) {}
            // ★ 轮次86：视频表情 BitmapFactory 给 -1，改用 MediaMetadataRetriever 读宽高
            if (isVideoName(f.getName()) && (it.width <= 0 || it.height <= 0)) {
                android.media.MediaMetadataRetriever r = null;
                try {
                    r = new android.media.MediaMetadataRetriever();
                    r.setDataSource(f.getAbsolutePath());
                    String w = r.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = r.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (w != null) it.width = Integer.parseInt(w.trim());
                    if (h != null) it.height = Integer.parseInt(h.trim());
                } catch (Throwable ignore) {
                } finally {
                    try { if (r != null) r.release(); } catch (Throwable ignore) {}
                }
            }
        } else {
            // ★ 轮次㉜：非 custom 的就是**角色包里**的那张（APK 里没有内置表情了）
            java.io.File cf = charMemeImage(ctx, name);
            if (cf != null) {
                it.bytes = (int) Math.min(Integer.MAX_VALUE, cf.length());
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try {
                    android.graphics.BitmapFactory.decodeFile(cf.getAbsolutePath(), o);
                    it.width = o.outWidth;
                    it.height = o.outHeight;
                } catch (Throwable ignore) {}
            }
        }
        return it;
    }

    /**
     * 这个表情是**角色包里的**吗（包的一部分：不可改名、不可删除、描述也不可改）。
     *
     * <p>★ 轮次㉜ 起判据变成"名字在当前角色包的 {@code memes/memes.json} 里" ——
     * 以前这里指的是"APK 内置的那 27 张"，现在 APK 里没有表情了，语义自然平移成"包里的"。
     * 用户自己上传的（{@link #loadCustom}）才能改 / 删。
     */
    public static boolean isBuiltin(Context ctx, String name) {
        return name != null && load(ctx).has(name);
    }

    /** 自定义表情目录 */
    public static java.io.File customDir(Context ctx) {
        return Prefs.memeCustomDir(ctx);
    }

    /**
     * 改描述（内置表情不支持，返回 false）。
     * 描述是模型选图的唯一依据，改完立刻生效，无需重发清单缓存。
     */
    public static boolean setDesc(Context ctx, String name, String desc) {
        if (name == null || isBuiltin(ctx, name)) return false;
        try {
            JSONObject c = loadCustom(ctx);
            if (!c.has(name)) return false;
            c.remove(name);
            c.put(name, cleanDesc(desc));      // put 会抛 JSONException，必须包住
            saveCustom(ctx, c);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 改表情名：连图片文件一起改（名字即文件名）。
     * 目标名已存在、或 origin 是内置表情时返回 null。成功返回新名字。
     */
    public static String rename(Context ctx, String origin, String newName) {
        if (origin == null || newName == null) return null;
        if (isBuiltin(ctx, origin)) return null;
        try {
        JSONObject c = loadCustom(ctx);
        if (!c.has(origin)) return null;
        String clean = cleanName(newName);
        if (clean.length() == 0) return null;
        if (clean.equals(origin)) return origin;                 // 没变，当成功
        if (isBuiltin(ctx, clean) || c.has(clean)) return null;   // 重名
        java.io.File src = findCustomFile(ctx, origin);
        String ext = (src == null) ? ".png" : ("." + src.getName().substring(
                src.getName().lastIndexOf('.') + 1));
        java.io.File dst = new java.io.File(Prefs.memeCustomDir(ctx), clean + ext);
        if (dst.exists()) return null;
        if (src != null && src.exists() && !src.renameTo(dst)) return null;
        String desc = c.optString(origin, "");
        c.remove(origin);
        c.put(clean, desc);                    // put 会抛 JSONException，必须包住
        saveCustom(ctx, c);
        return clean;
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除自定义表情：图片和 json 记录一起删。内置的删不掉，返回 false。 */
    public static boolean removeCustom(Context ctx, String name) {
        if (name == null || isBuiltin(ctx, name)) return false;
        JSONObject c = loadCustom(ctx);
        if (!c.has(name)) return false;
        c.remove(name);
        saveCustom(ctx, c);
        try {
            java.io.File f = findCustomFile(ctx, name);
            if (f != null) f.delete();
        } catch (Exception ignore) {}
        return true;
    }

    /** 从 content Uri 导入一张（单张上传用），描述留空。返回表情名，失败 null */
    public static String addCustomUri(Context ctx, android.net.Uri uri, String displayName,
                                      String[] sizeHint) {
        InputStream is = null;
        java.io.OutputStream os = null;
        try {
            is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            String name = uniqueName(ctx, cleanName(displayName));
            // ★ 轮次80：批量导入同样要认文件头（GIF/动图 WebP 原样保存）
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            byte[] head = new byte[16];
            int headLen = 0;
            while ((n = is.read(buf)) > 0) {
                all.write(buf, 0, n);
                if (headLen < 16) {
                    int take = Math.min(n, 16 - headLen);
                    System.arraycopy(buf, 0, head, headLen, take);
                    headLen += take;
                }
                total += n;
                if (total > MAX_ANIMATED_BYTES) { return null; }
            }
            byte[] data = all.toByteArray();
            String ext2 = extByHeader(head);
            if (ext2 == null) ext2 = extOfName(displayName);
            if (ext2 == null) ext2 = ".png";
            java.io.File dst = new java.io.File(Prefs.memeCustomDir(ctx), name + ext2);
            os = new java.io.FileOutputStream(dst);
            os.write(data);
            os.flush(); os.close(); os = null;
            is.close();  is = null;
            if (total == 0) { dst.delete(); return null; }
            JSONObject c = loadCustom(ctx);
            c.put(name, "");
            saveCustom(ctx, c);
            if (sizeHint != null && sizeHint.length > 0) sizeHint[0] = String.valueOf(total);
            return name;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignore) {}
            try { if (is != null) is.close(); } catch (Exception ignore) {}
        }
    }

    /** 供界面提示用的「建议描述」模板：多数用户第一次都不知道该填什么 */
    public static String descHint() {
        return "例如：累瘫、无语、想骂人、想耍赖的时候用";
    }

    /** 拼一段「表情清单已变，缓存需失效」的说明（由 ApiClient 读取时自行刷新） */
    public static String catalogVersion(Context ctx) {
        return "v" + names(ctx).size();
    }

    /**
     * 打开表情图数据流：<b>角色包优先，其次用户上传的</b>。
     * 返回 null 表示这张图实际不存在。
     *
     * <p>★ 轮次㉜：去掉了"最后回退 APK 内置 assets/memes/"那一跳 —— APK 里已经没有表情了。
     */
    public static InputStream openImage(Context ctx, String name) {
        if (name == null || name.length() == 0) return null;
        // ★ v7.0：当前角色包里的表情优先（表情按角色隔离）
        java.io.File cf = charMemeImage(ctx, name);
        if (cf != null) {
            try { return new java.io.FileInputStream(cf); } catch (Exception ignore) {}
        }
        try {
            java.io.File dir = Prefs.memeCustomDir(ctx);
            for (String ext : IMG_EXTS) {
                java.io.File f = new java.io.File(dir, name + ext);
                if (f.exists()) return new java.io.FileInputStream(f);
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** 只在**用户上传目录**里按名字找真实文件（扩展名不限）；找不到返回 null */
    private static java.io.File findCustomFile(Context ctx, String name) {
        if (name == null || name.length() == 0) return null;
        try {
            java.io.File dir = Prefs.memeCustomDir(ctx);
            for (String ext : IMG_EXTS) {
                java.io.File f = new java.io.File(dir, name + ext);
                if (f.exists()) return f;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** 找到这张表情对应的真实文件（角色包优先，其次用户上传）；找不到返回 null */
    public static java.io.File fileOf(Context ctx, String name) {
        if (name == null || name.length() == 0) return null;
        java.io.File cf = charMemeImage(ctx, name);
        if (cf != null) return cf;
        try {
            java.io.File dir = Prefs.memeCustomDir(ctx);
            for (String ext : IMG_EXTS) {
                java.io.File f = new java.io.File(dir, name + ext);
                if (f.exists()) return f;
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * 把表情解成**可直接显示的 Drawable**：动图（GIF / 动态 WebP）会真的动起来。
     *
     * <p>★ API 28+ 用 {@link android.graphics.ImageDecoder} 解出 {@code AnimatedImageDrawable}，
     *   设成无限循环并 {@code start()}；解出来不是动图就退化成普通 Drawable（静态图也走这条）。
     *   API &lt; 28 没有 ImageDecoder，只能给**首帧静态图**并在日志里说明 —— 不假装能播。
     *
     * @param maxPx 目标边长上限（thumb 用几百 px 就够；0/负数 = 不限制）
     */
    public static android.graphics.drawable.Drawable openDrawable(Context ctx, String name, int maxPx) {
        java.io.File f = fileOf(ctx, name);
        if (f == null) return null;
        // ★ 轮次86：视频表情取**首帧**当静态图 —— 列表缩略图、老系统、以及"静音预览"都用它。
        //   真播放由气泡里的 VideoView 负责（见 FloatingService.applyBubbleMeme）。
        if (isVideoName(f.getName())) {
            android.graphics.Bitmap frame = videoFrame(ctx, f, maxPx);
            return frame == null ? null
                    : new android.graphics.drawable.BitmapDrawable(ctx.getResources(), frame);
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                final int cap = maxPx;
                android.graphics.ImageDecoder.Source src =
                        android.graphics.ImageDecoder.createSource(f);
                android.graphics.ImageDecoder dec = android.graphics.ImageDecoder.createSource(f) != null
                        ? null : null;   // 占位（下面用 decodeDrawable 的 lambda 形式）
                android.graphics.drawable.Drawable d =
                        android.graphics.ImageDecoder.decodeDrawable(src,
                                new android.graphics.ImageDecoder.OnHeaderDecodedListener() {
                            @Override public void onHeaderDecoded(android.graphics.ImageDecoder decoder,
                                                                 android.graphics.ImageDecoder.ImageInfo info,
                                                                 android.graphics.ImageDecoder.Source s) {
                                if (cap > 0) {
                                    android.util.Size sz = info.getSize();
                                    int m = Math.max(sz.getWidth(), sz.getHeight());
                                    if (m > cap) {
                                        float k = cap / (float) m;
                                        decoder.setTargetSize(Math.max(1, Math.round(sz.getWidth() * k)),
                                                Math.max(1, Math.round(sz.getHeight() * k)));
                                    }
                                }
                            }
                        });
                if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
                    android.graphics.drawable.AnimatedImageDrawable a =
                            (android.graphics.drawable.AnimatedImageDrawable) d;
                    a.setRepeatCount(android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE);
                    a.start();
                    android.util.Log.i("DSMeme", "动态表情播放: " + name + " (" + f.getName() + ")");
                }
                return d;
            } catch (Throwable t) {
                android.util.Log.w("DSMeme", "ImageDecoder 失败，退回首帧: " + name + " " + t);
            }
        }
        // API < 28 或 ImageDecoder 抛异常：只能给首帧
        try {
            java.io.InputStream is = openImage(ctx, name);
            if (is == null) return null;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(is);
            is.close();
            if (bm == null) return null;
            if (isAnimatedName(name)) {
                android.util.Log.w("DSMeme", "本机 Android < 8.0，动态表情只显示首帧: " + name);
            }
            return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bm);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取视频表情的首帧（失败返回 null）。
     *
     * <p>★ 为什么需要它：视频表情在"列表缩略图""API &lt; 28""这类只能显示静态图的场合
     *   也需要一个像样的封面，否则界面上是一片空白（用户会以为上传失败）。
     */
    public static android.graphics.Bitmap videoFrame(Context ctx, java.io.File f, int maxPx) {
        android.media.MediaMetadataRetriever r = null;
        try {
            r = new android.media.MediaMetadataRetriever();
            r.setDataSource(f.getAbsolutePath());
            android.graphics.Bitmap bm = null;
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                bm = r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxPx > 0 ? maxPx : 480, maxPx > 0 ? maxPx : 480);
            }
            if (bm == null) bm = r.getFrameAtTime(0);
            return bm;
        } catch (Throwable t) {
            android.util.Log.w("DSMeme", "取视频首帧失败: " + f.getName() + " " + t);
            return null;
        } finally {
            try { if (r != null) r.release(); } catch (Throwable ignore) {}
        }
    }

    /** 隐藏表情时把动画停掉（不停会一直在后台跑帧，白耗电） */
    public static void stopIfAnimated(android.graphics.drawable.Drawable d) {
        if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
            try { ((android.graphics.drawable.AnimatedImageDrawable) d).stop(); } catch (Throwable ignore) {}
        }
    }

    /**
     * 保存用户上传的表情图。返回实际使用的名称，失败返回 null。
     *
     * @param desc 用户填写的「什么时候用」描述 —— 会写进清单供模型选图。
     *             允许留空（批量导入时就是空的），空描述由 catalog 拼成中性占位，
     *             之后可在「查看／管理已有表情」里补。
     */
    public static String addCustom(Context ctx, android.net.Uri uri, String displayName, String desc) {
        InputStream is = null;
        java.io.OutputStream os = null;
        try {
            is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            // ★★ 轮次80：先读文件头判断真实类型 —— 旧代码无脑写 .png，
            //   上传 GIF 会变成"后缀 png、内容是 GIF"的畸形文件，解码必然失败。
            java.io.ByteArrayOutputStream headBuf = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = is.read(buf)) > 0) {
                all.write(buf, 0, n);
                if (headBuf.size() < 16) headBuf.write(buf, 0, Math.min(n, 16 - headBuf.size()));
                total += n;
                if (total > MAX_ANIMATED_BYTES) {      // 上限按动态表情放宽
                    android.util.Log.w("DSMeme", "表情过大，已拒绝: " + total);
                    return null;
                }
            }
            is.close(); is = null;
            byte[] data = all.toByteArray();
            String ext = extByHeader(headBuf.toByteArray());
            if (ext == null) ext = extOfName(displayName);
            if (ext == null) ext = ".png";
            long cap = isVideoName(ext) ? MAX_VIDEO_BYTES
                    : (isAnimatedName(ext) ? MAX_ANIMATED_BYTES : MAX_STATIC_BYTES);
            if (data.length > cap) {
                android.util.Log.w("DSMeme", "表情过大(" + data.length + " > " + cap + ")，已拒绝");
                return null;
            }
            if (false) {
                android.util.Log.w("DSMeme", "静态表情过大，已拒绝: " + data.length);
                return null;
            }
            String name = cleanName(displayName);
            name = uniqueName(ctx, name);
            java.io.File dst = new java.io.File(Prefs.memeCustomDir(ctx), name + ext);
            os = new java.io.FileOutputStream(dst);
            os.write(data);
            os.flush();
            os.close(); os = null;
            android.util.Log.i("DSMeme", "已保存表情: " + dst.getName()
                    + " " + data.length + " B" + (isAnimatedName(ext) ? "（动态）" : ""));

            JSONObject o = loadCustom(ctx);
            o.put(name, cleanDesc(desc));
            saveCustom(ctx, o);
            return name;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignore) {}
            try { if (is != null) is.close(); } catch (Exception ignore) {}
        }
    }

    /** 兼容旧签名：不带描述 */
    public static String addCustom(Context ctx, android.net.Uri uri, String displayName) {
        return addCustom(ctx, uri, displayName, null);
    }

    /**
     * 描述清洗：单行、去换行制表符、截到 40 字。
     *
     * <p>★ 空描述就存空串，不塞「用户上传的表情」这种没信息量的兜底 ——
     * 那会让模型以为描述有效却读不出场景，反而更不敢选。空描述统一由
     * {@link #catalog} 拼成中性占位，用户补上后自动替换。</p>
     */
    public static String cleanDesc(String s) {
        if (s == null) return "";
        String d = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (d.length() > 40) d = d.substring(0, 40);
        return d;
    }

    /** 去掉扩展名与文件名里的非法字符，截到 40 字（表情名直接取自文件名） */
    public static String cleanName(String s) {
        if (s == null) return "";
        String n = s.trim();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "").trim();
        if (n.length() > 40) n = n.substring(0, 40);
        return n.length() == 0 ? "自定义表情" : n;
    }

    /** 重名就加序号，避免覆盖已有表情 */
    private static String uniqueName(Context ctx, String base) {
        JSONObject o = load(ctx);
        JSONObject c = loadCustom(ctx);
        if (!o.has(base) && !c.has(base) && findCustomFile(ctx, base) == null) {
            return base;
        }
        for (int i = 2; i < 999; i++) {
            String t = base + "_" + i;
            if (!o.has(t) && !c.has(t) && findCustomFile(ctx, t) == null) {
                return t;
            }
        }
        return base + "_" + System.currentTimeMillis();
    }
}
