package com.deepseek.pet;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 内置素材包：assets/webp/*.webp（VP9-alpha 预转的 animated WebP）+ assets/pet_anim.json。
 *
 * 按状态（待机/转向/拖动/点击/移动）自动切换播放不同动画，
 * 用 ImageDecoder + AnimatedImageDrawable 播放（Android 原生支持 alpha）。
 */
public class PetAnimPack {

    private static final String TAG = "PetAnimPack";
    private static final String CONFIG = "pet_anim.json";
    private static final String LINES = "anim_lines.json";
    private static final String DIR = "webp/";   // assets 内 webp 目录
    /**
     * 解码长边上限（大图保护）。
     * <p>★ 待办 0j 的 C 之后，用户能从相册里选**任意**图片当动作：一张手机照片
     * 4000×3000 按原尺寸解码就是约 48 MB 位图，几张就能把 App 撑到 OOM。
     * 内置素材都是 500×556 级别 ⇒ 走不到这条分支，对既有行为零影响。
     */
    private static final int MAX_DECODE_EDGE = 1024;

    /** 一个动画条目 */
    public static class Clip {
        public final String id;      // 动画名（= 文件名，无扩展名）
        public final int weight;
        public final boolean noMirror;
        Clip(String id, int weight, boolean noMirror) {
            this.id = id; this.weight = weight; this.noMirror = noMirror;
        }
    }

    /** 一组同类动画（带权重），用于随机挑选 */
    public static class Group {
        public final String id;
        public final List<Clip> clips = new ArrayList<>();
        public int totalWeight = 0;
        Group(String id) { this.id = id; }
        void add(Clip c) { clips.add(c); totalWeight += Math.max(1, c.weight); }
        public Clip pick(Random r) {
            if (clips.isEmpty()) return null;
            int t = r.nextInt(Math.max(1, totalWeight));
            int acc = 0;
            for (Clip c : clips) {
                acc += Math.max(1, c.weight);
                if (t < acc) return c;
            }
            return clips.get(clips.size() - 1);
        }
    }

    // ===== 状态 =====
    private boolean loaded = false;
    private final List<Clip> allClips = new ArrayList<>();   // 全量动画池（去重）
    private String lastRotateId = null;                       // 轮播上一个，避免重复
    private final Map<String,Integer> durCache = new HashMap<>(); // 动画时长缓存(ms)
    private boolean failed = false;

    private final List<Clip> idle = new ArrayList<>();
    private final List<Clip> turn = new ArrayList<>();
    private final List<Clip> drag = new ArrayList<>();
    private final List<Clip> clicks = new ArrayList<>();  // 点击回应
    private final Map<String, Group> moveGroups = new HashMap<>();  // 移动（按方向/动作组）
    private final List<Clip> moveDefault = new ArrayList<>();
    /**
     * ★ 移动动画（走路用）。现行 {@code pet_anim.json} 的 {@code moves.actions} 是
     * <pre>{"name": "螃蟹走路"}</pre> 这种**单个动画**，而不是 {@code {"id":…, "actions":[….]}}
     * 的嵌套组 —— 老加载器只认后者，于是这 3 个动画永远加载不进来（空组）。
     * 见 {@link #load} 里的兼容分支。
     */
    private final List<Clip> moveClips = new ArrayList<>();
    private final List<Group> categories = new ArrayList<>();      // 事件/时段等
    /**
     * 2.0 配置（{@code scenes}）里出现的全部动作。
     * <p>素材层不关心"这个动作属于哪个场景"，但必须认识它们：{@link #clipByName} 找不到
     * 就等于"场景配好了却播不了"。
     */
    private final List<Clip> sceneClips = new ArrayList<>();
    /**
     * ★ 事件动画组（{@code pet_anim.json} 的 {@code events}: balance / whisper / workStatus）。
     * <p>以前**这个节点全工程没人读**（{@code load()} 里没有 {@code optJSONObject("events")}，
     * {@code pickEvent()} 只有定义、零调用点），于是 15 个动画躺在包里永远不播。
     */
    private final java.util.Map<String, Group> eventGroups = new java.util.LinkedHashMap<>();
    private final Map<String, List<String>> lines = new HashMap<>();  // 动画->台词
    /** 全部**可播**动画名（轮播池 + 事件组 + 移动）：动作菜单与"按名播放"都看它 */
    private volatile java.util.List<String> allNames = new ArrayList<>();
    /** 上面那份名单的 id→Clip 查找表（含事件/移动），{@link #clipByName} 用它 */
    private final java.util.Map<String, Clip> master = new java.util.LinkedHashMap<>();
    private final Map<String, Object> weights = new HashMap<>();

    private final Random rnd = new Random();
    private final Map<String, Drawable> cache = new HashMap<>();   // 已解码动图缓存
    private Context ctx;
    private Handler ui;
    /** 解析后的配置根（含外部合并结果）。{@link ActionSystem} 用它判断 2.0 / 1.x。 */
    private JSONObject cfgRoot;
    /**
     * 当前角色目录（用户角色包）；为 null 表示**内置角色** —— 配置与素材都从 APK 的
     * {@code assets/} 读。见 {@link PetCharacter} 与 {@link CharacterStore}。
     */
    private File charDir;
    /**
     * 当前角色 id —— 用户素材目录按它查（见 {@link SceneBindings#clipsDirFor}）。
     * <p>用户角色的目录名就是 id（导入器约定「落地目录名 = 净化后的 id」）；
     * 内置角色用 {@link PetCharacter#BUILTIN_ID}（净化后正好是 {@code builtin_default}）。
     */
    private String charId = PetCharacter.BUILTIN_ID;
    /**
     * 离线装配（给 {@code build/} 里的"导出→导入往返"断言用）：非 null 时**不读 assets**，
     * 直接拿这两段文本当"内置基底配置/台词"。
     * <p>产品路径上恒为 null ⇒ 行为与以前完全一致；存在的意义是让往返断言能在
     * 没有 Android 运行时（AssetManager 是 final 类、没法替身）的桌面 JVM 上跑真代码。
     */
    private String offlineConfig;
    private String offlineLines;
    /** 离线装配时的 filesDir 替身（{@code filesDir/scenes/...} 由它派生）；产品路径恒为 null */
    private File filesRoot;

    /** 角色包里的三个标准文件（与 {@link PetCharacter} 的常量保持一致） */
    private File charConfigFile() { return charDir == null ? null : new File(charDir, "anim/pack.json"); }
    private File charLinesFile()  { return charDir == null ? null : new File(charDir, "anim/lines.json"); }
    private File charClipFile(String id, String ext) {
        return charDir == null ? null : new File(new File(charDir, "anim/clips"), id + "." + ext);
    }

    /**
     * 用户素材目录：{@code filesDir/scenes/<角色 id>/clips/}（见 {@link SceneBindings#clipsDirFor}）。
     * <p>产品路径用 Context 取 filesDir；离线装配用调用方给的 {@code filesRoot}
     * （桌面 JVM 上造不出 Context —— 它有 104 个抽象方法，且 {@code Context.<init>} 本身就是 Stub）。
     */
    private File userClipDir() {
        if (ctx != null) return SceneBindings.clipsDirFor(ctx, charId);
        if (filesRoot != null) return SceneBindings.clipsDirIn(filesRoot, charId);
        return null;
    }

    /** 用户素材文件：{@code <用户素材目录>/<名字>.<扩展名>} */
    private File userClipFile(String id, String ext) {
        File d = userClipDir();
        return d == null ? null : new File(d, id + "." + ext);
    }

    /** 解析后的配置根；没加载成功时为 null */
    public JSONObject configRoot() { return cfgRoot; }

    /** 加载配置（内置角色）。失败则 isFailed()=true */
    public void load(Context c) { load(c, null); }

    /**
     * 离线装配：不读 assets、也不碰 Context，直接用给的文本当"内置基底配置/台词"。
     *
     * <p>除"从哪取那两段文本 / filesDir 从哪来"之外，走的是**与
     * {@link #load(Context, File)} 完全相同的代码** —— 合并角色包配置、扫目录补登记、
     * 建 master 名单。给 {@code build/rt_test} 的"导出→导入往返"断言用。
     *
     * @param filesRoot    filesDir 的替身（用户素材目录 {@code scenes/<id>/clips} 从它派生）
     * @param configJson   内置基底配置（等于 APK 里 {@code assets/pet_anim.json} 的内容）
     * @param linesJson    内置台词（等于 {@code assets/anim_lines.json}）
     * @param characterDir 角色包目录，可为 null（内置）
     */
    public void loadOffline(File filesRoot, String configJson, String linesJson, File characterDir) {
        this.filesRoot = filesRoot;
        this.offlineConfig = configJson;
        this.offlineLines = linesJson;
        load(null, characterDir);
    }

    /** 内置基底配置文本（离线模式给什么用什么） */
    private String readBaseConfig() throws Exception {
        return offlineConfig != null ? offlineConfig : readAsset(CONFIG);
    }

    /** 内置台词文本（离线模式给什么用什么） */
    private String readBaseLines() throws Exception {
        return offlineLines != null ? offlineLines : readAsset(LINES);
    }

    /**
     * 加载配置。失败则 isFailed()=true。
     *
     * @param characterDir 用户角色包目录；null = 内置角色（读 assets）。
     *                     ★ v7.0：角色包里的 {@code anim/pack.json} 与
     *                     {@code anim/clips/*.webp} 优先，读不到再回退 APK 内置。
     */
    public void load(Context c, File characterDir) {
        this.charDir = characterDir;
        // ★ 目录名 = 落地后的角色 id（导入器约定），内置角色用 BUILTIN_ID。
        //   用户素材目录 filesDir/scenes/<charId>/clips 就是按它查的（见 userClipFile）。
        this.charId = (characterDir != null) ? characterDir.getName() : PetCharacter.BUILTIN_ID;
        // ★ c == null = 离线装配（见 loadOffline）：那时**不碰任何 Android API**
        //   （Context.<init> 在 android.jar 里本身就是 Stub，桌面 JVM 造不出可用实例）。
        if (c != null) {
            this.ctx = c.getApplicationContext();
            this.ui = new Handler(Looper.getMainLooper());
        }
        try {
            // ★ 内置配置**永远是基底**：角色包只做"追加/覆盖"。
            //   这样"只想换拖拽动画"的角色包，其余场景照常走内置（见 角色包格式.md 第八节）。
            JSONObject root = new JSONObject(readBaseConfig());
            File cf = charConfigFile();
            if (cf != null && cf.isFile() && cf.length() > 0) {
                JSONObject ext = new JSONObject(readFileText(cf));
                mergeV2(root, ext);         // 2.0 scenes/events 追加
                mergeAnimConfig(root, ext); // 1.x 老字段（老素材包）也能合并
                Log.i(TAG, "角色包配置已并入: " + cf.getAbsolutePath());
            }
            cfgRoot = root;
            readClipArray(root.optJSONArray("idle"), idle);
            readClipArray(root.optJSONArray("turn"), turn);
            readClipArray(root.optJSONArray("drag"), drag);
            readClipArray(root.optJSONArray("clicks"), clicks);

            JSONObject moves = root.optJSONObject("moves");
            if (moves != null) {
                readClipArray(moves.optJSONArray("default"), moveDefault);
                JSONArray acts = moves.optJSONArray("actions");
                if (acts != null) {
                    for (int i = 0; i < acts.length(); i++) {
                        JSONObject a = acts.getJSONObject(i);
                        JSONArray sub = a.optJSONArray("actions");
                        if (sub != null) {
                            // 老写法：actions 里再嵌一组动画（外部素材包可能还在用）
                            String gid = a.optString("id", "move" + i);
                            Group g = new Group(gid);
                            readClipsInto(sub, g);
                            if (!g.clips.isEmpty()) {
                                moveGroups.put(gid, g);
                                moveClips.addAll(g.clips);
                            }
                        } else {
                            // ★ 现行写法：{"name": "螃蟹走路", "params": {…}}
                            //   以前这里只读 optJSONArray("actions")（= null）⇒ 建成空组，
                            //   于是 3 个移动动画在包里但永远不播。
                            String id = a.optString("id", a.optString("name", null));
                            if (id != null && !id.isEmpty()) {
                                moveClips.add(new Clip(id, a.optInt("weight", 1),
                                        a.optBoolean("noMirror", false)));
                            }
                        }
                    }
                }
            }

            JSONArray cats = root.optJSONArray("categories");
            if (cats != null) {
                for (int i = 0; i < cats.length(); i++) {
                    JSONObject cj = cats.getJSONObject(i);
                    Group g = new Group(cj.optString("id", "cat" + i));
                    readClipsInto(cj.optJSONArray("actions"), g);
                    if (!g.clips.isEmpty()) categories.add(g);
                }
            }

            // ★ 事件组（balance / whisper / workStatus）：此前整个节点没被读过，
            //   15 个事件动画因此永远不播（余额档位、主动搭话碎碎念、工作场景）。
            JSONObject evs = root.optJSONObject("events");
            if (evs != null) {
                java.util.Iterator<String> eit = evs.keys();
                while (eit.hasNext()) {
                    String gid = eit.next();
                    JSONArray arr = clipsArrayOf(evs.opt(gid));
                    if (arr == null) continue;
                    Group g = new Group(gid);
                    readClipsInto(arr, g);
                    if (!g.clips.isEmpty()) eventGroups.put(gid, g);
                }
            }

            // ★★ 2.0 配置（{@code scenes} / {@code events}）：素材层**不关心场景归属**
            //   （那是 ActionSystem 的活），但必须把所有场景里提到的动作都登记进来 ——
            //   否则 {@code clipByName()} 找不到、{@code getDrawable()} 也就无从解码，
            //   ActionSystem 里配好的场景会全部"看得到、播不了"，而且 loaded=false
            //   会让整个动画包被判为失败（实测踩过：桌宠直接不显示）。
            JSONObject  sc = root.optJSONObject("scenes");
            if (sc != null) {
                java.util.Iterator<String> sit = sc.keys();
                while (sit.hasNext()) {
                    Group g = new Group(sit.next());
                    readClipsInto(clipsArrayOf(sc.opt(g.id)), g);
                    for (Clip cl : g.clips) if (!containsClipId(sceneClips, cl.id)) sceneClips.add(cl);
                }
            }

            JSONObject w = root.optJSONObject("weights");
            if (w != null) {
                java.util.Iterator<String> it = w.keys();
                while (it.hasNext()) { String k = it.next(); weights.put(k, w.opt(k)); }
            }

            // ★ 待办 0j 的 C 需要的最后一块：把角色包 anim/clips/ 里**实际存在**的文件
            //   也登记进"可播名单"。为什么必须有这一步：App 内「添加动作」是把图直接
            //   复制进 clips/ 的（不必重导整包），如果只认配置里列过的名字，
            //   用户选完图之后**动作库里根本看不到它** —— 又是"选了没反应"。
            //   放在配置读取之后、buildAllClips() 之前（名字进 sceneClips 才进 master）。
            scanCharClips();

            buildAllClips();
            loadLines();
            buildAllClips();
            loaded = !idle.isEmpty() || !categories.isEmpty() || !clicks.isEmpty()
                    || !sceneClips.isEmpty();
            Log.i(TAG, "loaded=" + loaded + " idle=" + idle.size() + " turn=" + turn.size()
                    + " drag=" + drag.size() + " clicks=" + clicks.size()
                    + " moveGroups=" + moveGroups.size() + " cats=" + categories.size());
        } catch (Throwable t) {
            failed = true;
            Log.e(TAG, "load failed: " + t);
        }
    }

    public boolean isFailed() { return failed || !loaded; }
    public boolean isReady()  { return loaded && !failed; }

    private void readClipArray(JSONArray arr, List<Clip> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof String) out.add(new Clip((String) o, 1, false));
        }
    }
    private void readClipsInto(JSONArray arr, Group g) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof String) {
                g.add(new Clip((String) o, 1, false));
            } else if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                String id = j.optString("id", j.optString("name", null));
                if (id == null || id.isEmpty()) continue;
                g.add(new Clip(id, j.optInt("weight", 1), j.optBoolean("noMirror", false)));
            }
        }
    }

    /**
     * 取一个"动作数组"。2.0 的场景既可能写成简写数组
     * （{@code "idle":["动作名", …]}），也可能写成对象（{@code "idle":{"clips":[…]}}）；
     * 事件组同理。这里统一成 {@link JSONArray}，两种写法都能吃。
     */
    private JSONArray clipsArrayOf(Object o) {
        if (o instanceof JSONArray) return (JSONArray) o;
        if (o instanceof JSONObject) return ((JSONObject) o).optJSONArray("clips");
        return null;
    }

    private boolean containsClipId(List<Clip> list, String id) {
        for (Clip c : list) if (c.id.equals(id)) return true;
        return false;
    }

    /**
     * 扫"用户素材目录"与角色包 {@code anim/clips/}，把目录里**真的存在**的图片都登记为可播动作。
     *
     * <p>为什么需要它（待办 0j 的 C / 场景内导入）：新动作是**直接复制文件**进来的
     * （不必重导整包、也不必改角色包配置）。若素材层只认识配置里列过的名字，
     * 那么"只进动作库、先不绑定"的那张图**不会出现在任何名单里** ⇒ 用户看不到它。
     *
     * <p>两个目录都扫：用户素材目录（{@code filesDir/scenes/<角色 id>/clips}）对内置角色
     * 同样有效，角色包目录则兼容"用户手动往包里塞图"的用法。
     */
    private void scanCharClips() {
        int n = 0;
        File ud = userClipDir();
        if (ud != null) n += scanClipDir(ud);
        if (charDir != null) n += scanClipDir(new File(charDir, PetCharacter.F_CLIPS));
        if (n > 0) Log.i(TAG, "角色包 clips 目录补登记 " + n + " 个动作");
    }

    private int scanClipDir(File d) {
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        java.util.Arrays.sort(fs);
        int n = 0;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            String nm = f.getName();
            if (!ClipNaming.isImageName(nm)) continue;
            String id = ClipNaming.baseNameOf(nm);
            if (id.isEmpty() || containsClipId(sceneClips, id)) continue;
            sceneClips.add(new Clip(id, 1, false));
            n++;
        }
        return n;
    }

    // ===== 外部素材覆盖层：先查 filesDir/custom_webp，再回退 assets/webp =====

    /**
     * 按动画 id 读取原始字节：**当前角色包优先**，找不到再回退 APK 的 assets。
     * 找不到返回 null（调用方按"这个动作播不了"处理）。
     *
     * <p>★ 待办 0j 的 D：**挨个试全部图片扩展名**（{@link ClipNaming#IMAGE_EXTS}）。
     * 原来只试 {@code webp/gif}，于是 App 内「添加动作」从相册选一张 PNG 落盘之后
     * 素材层根本找不到它 —— 表现就是"选了图什么都不发生"。
     * {@link ImageDecoder} 对 PNG/JPG 一样能解码，只是得到**单帧** drawable
     * （{@link #startDrawable} 对非动图是空操作，正好就是"静态动作"的语义）。
     */
    private byte[] readClipBytes(String id, String ext) {
        java.util.List<String> exts = new ArrayList<String>();
        if (ext != null && !ext.isEmpty()) exts.add(ext.toLowerCase());
        for (String e : ClipNaming.IMAGE_EXTS) if (!exts.contains(e)) exts.add(e);
        // 1) ★ 用户素材目录（用户自己加的，优先级最高：同名以用户为准）
        try {
            for (String e : exts) {
                File f = userClipFile(id, e);
                if (f == null || !f.isFile() || f.length() <= 0) continue;
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                in.close();
                return bos.toByteArray();
            }
        } catch (Throwable ignore) {}
        // 2) 当前角色包的 anim/clips/
        try {
            for (String e : exts) {
                File f = charClipFile(id, e);
                if (f == null || !f.isFile() || f.length() <= 0) continue;
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                in.close();
                return bos.toByteArray();
            }
        } catch (Throwable ignore) {}
        // 3) assets 回退（内置角色）
        for (String e : exts) {
            try {
                InputStream is = ctx.getAssets().open(DIR + id + "." + e);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[65536]; int n;
                while ((n = is.read(b)) > 0) bos.write(b, 0, n);
                is.close();
                return bos.toByteArray();
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** 读取文本：外部 json 优先（用于配置文件），否则 assets */
    private String readTextPreferExternal(String assetName, File external) {
        try {
            if (external != null && external.isFile() && external.length() > 0) {
                java.io.FileInputStream in = new java.io.FileInputStream(external);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                in.close();
                return new String(bos.toByteArray(), "UTF-8");
            }
        } catch (Throwable ignore) {}
        try { return readAsset(assetName); } catch (Throwable t) { return null; }
    }

    private boolean clipExistsAnywhere(String id) {
        if (id == null) return false;
        if (containsClip(idle, id) || containsClip(turn, id) || containsClip(drag, id)
                || containsClip(clicks, id) || containsClip(moveDefault, id)) return true;
        for (Group g : moveGroups.values()) if (containsClip(g.clips, id)) return true;
        for (Group g : categories) if (containsClip(g.clips, id)) return true;
        return false;
    }

    private boolean containsClip(List<Clip> list, String id) {
        if (list == null) return false;
        for (Clip c : list) if (c != null && id.equals(c.id)) return true;
        return false;
    }

    /** 读一个文件为 UTF-8 文本（读不到返回空串） */
    private String readFileText(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) { return ""; }
    }

    /**
     * 2.0 配置合并：角色包的 {@code scenes}/{@code events} **追加**到基底同名场景后面（按名字去重）。
     *
     * <p>为什么是追加而不是覆盖：角色包常常只想改一个场景（例如只换拖拽动画），
     * 其余场景应继续用内置的；反过来若整包替换，用户就得把 106 个动作全抄一遍。
     * 去重很重要 —— 完整角色包里的 {@code idle} 与内置的 {@code 待机呼吸休闲} 是同一个动作。
     */
    private void mergeV2(JSONObject base, JSONObject ext) {
        try {
            mergeV2Group(base, ext, "scenes");
            mergeV2Group(base, ext, "events");
        } catch (Throwable t) {
            Log.w(TAG, "mergeV2 failed: " + t);
        }
    }

    private void mergeV2Group(JSONObject base, JSONObject ext, String key) throws Exception {
        JSONObject e = ext.optJSONObject(key);
        if (e == null) return;
        JSONObject b = base.optJSONObject(key);
        if (b == null) { b = new JSONObject(); base.put(key, b); }
        java.util.Iterator<String> it = e.keys();
        while (it.hasNext()) {
            String sid = it.next();
            JSONArray ea = clipsArrayOf(e.opt(sid));
            if (ea == null) continue;
            JSONArray ba = b.optJSONArray(sid);
            if (ba == null) {           // 基底没有这个场景 → 直接放进去
                JSONArray copy = new JSONArray();
                for (int i = 0; i < ea.length(); i++) copy.put(ea.opt(i));
                b.put(sid, copy);
                continue;
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < ba.length(); i++) {
                Object o = ba.opt(i);
                if (o instanceof String) seen.add((String) o);
                else if (o instanceof JSONObject)
                    seen.add(((JSONObject) o).optString("id", ((JSONObject) o).optString("name", "")));
            }
            for (int i = 0; i < ea.length(); i++) {
                Object o = ea.opt(i);
                String id = (o instanceof String) ? (String) o
                        : (o instanceof JSONObject
                           ? ((JSONObject) o).optString("id",
                                 ((JSONObject) o).optString("name", null)) : null);
                if (id == null || id.isEmpty() || seen.contains(id)) continue;
                seen.add(id);
                ba.put(o);
            }
        }
    }

    private String readAsset(String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ===== 状态 → 动画选择 =====

    /** 待机动画（随机） */
    public Clip pickIdle() {
        if (!idle.isEmpty()) return idle.get(rnd.nextInt(idle.size()));
        return pickAny();
    }
    /** 转向 */
    public Clip pickTurn() {
        if (!turn.isEmpty()) return turn.get(rnd.nextInt(turn.size()));
        return pickIdle();
    }
    /** 拖动 */
    public Clip pickDrag() {
        if (!drag.isEmpty()) return drag.get(rnd.nextInt(drag.size()));
        return pickIdle();
    }
    /** 点击回应 */
    public Clip pickClick() {
        if (!clicks.isEmpty()) return clicks.get(rnd.nextInt(clicks.size()));
        return pickAny();
    }
    /** 移动（可选方向组；否则用 moves.actions 那组走路动画） */
    public Clip pickMove(String groupId) {
        if (groupId != null) {
            Group g = moveGroups.get(groupId);
            if (g != null) { Clip c = g.pick(rnd); if (c != null) return c; }
        }
        if (!moveDefault.isEmpty()) return moveDefault.get(rnd.nextInt(moveDefault.size()));
        // ★ moves.actions（螃蟹走路 / 原地漂浮踏步 / 原地左转奔跑）
        if (!moveClips.isEmpty()) return moveClips.get(rnd.nextInt(moveClips.size()));
        if (!moveGroups.isEmpty()) {
            Group g = moveGroups.values().iterator().next();
            Clip c = g.pick(rnd); if (c != null) return c;
        }
        return pickIdle();
    }
    /** 事件/时段随机（带权重） */
    public Clip pickEvent() {
        if (!categories.isEmpty()) {
            Group g = categories.get(rnd.nextInt(categories.size()));
            Clip c = g.pick(rnd);
            if (c != null) return c;
        }
        return pickAny();
    }
    private Clip pickAny() {
        if (!clicks.isEmpty()) return clicks.get(rnd.nextInt(clicks.size()));
        if (!idle.isEmpty()) return idle.get(rnd.nextInt(idle.size()));
        if (!categories.isEmpty()) {
            Clip c = categories.get(0).pick(rnd); if (c != null) return c;
        }
        return null;
    }

    // ===== 待机轮播：全量动画池 + 时长解析 =====

    /** 汇总所有动画到一个去重池，供待机轮播随机挑选 */

    // ===== 动画 -> 台词 =====

    /** 加载内置台词：动画名 -> 台词数组（离线装配时用传入的文本） */
    private void loadLines() {
        try {
            JSONObject root = new JSONObject(readBaseLines());
            File lf = charLinesFile();
            if (lf != null && lf.isFile() && lf.length() > 0) {
                try { mergeLines(root, new JSONObject(readFileText(lf))); } catch (Throwable ignore) {}
            }
            java.util.Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray arr = root.optJSONArray(k);
                if (arr == null) continue;
                List<String> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, null);
                    if (s != null && !s.isEmpty()) list.add(s);
                }
                if (!list.isEmpty()) lines.put(k, list);
            }
            Log.i(TAG, "lines loaded=" + lines.size());
        } catch (Throwable t) {
            Log.w(TAG, "loadLines failed: " + t);
        }
    }

    /**
     * 把外部 pet_anim.json 合并进内置配置 root：
     *  - idle/turn/drag/clicks：追加动作（去重）
     *  - categories：同名分类追加动作，新分类新增
     *  - moves：同名组追加动作
     *  - weights：合并键值
     * 任何情况下都不删除内置内容。
     */
    private void mergeAnimConfig(JSONObject root, JSONObject ext) {
        try {
            mergeStringArray(root, ext, "idle");
            mergeStringArray(root, ext, "turn");
            mergeStringArray(root, ext, "drag");
            mergeStringArray(root, ext, "clicks");

            // categories：按 id 合并 actions
            JSONArray extCats = ext.optJSONArray("categories");
            JSONArray baseCats = root.optJSONArray("categories");
            if (extCats != null) {
                if (baseCats == null) { baseCats = new JSONArray(); root.put("categories", baseCats); }
                for (int i = 0; i < extCats.length(); i++) {
                    JSONObject ec = extCats.optJSONObject(i);
                    if (ec == null) continue;
                    String cid = ec.optString("id", null);
                    JSONObject match = null;
                    for (int j = 0; j < baseCats.length(); j++) {
                        JSONObject bc = baseCats.optJSONObject(j);
                        if (bc != null && cid != null && cid.equals(bc.optString("id", null))) {
                            match = bc; break;
                        }
                    }
                    if (match == null) {
                        baseCats.put(ec);
                    } else {
                        joinArrays(match, ec, "actions");
                    }
                }
            }

            // events：同名事件组追加动作（与 categories 同规则；外部素材包可加自己的事件动画）
            JSONObject extEv = ext.optJSONObject("events");
            if (extEv != null) {
                JSONObject baseEv = root.optJSONObject("events");
                if (baseEv == null) { baseEv = new JSONObject(); root.put("events", baseEv); }
                java.util.Iterator<String> eit = extEv.keys();
                while (eit.hasNext()) mergeStringArray(baseEv, extEv, eit.next());
            }

            // moves：同名 action 组追加
            JSONObject extMoves = ext.optJSONObject("moves");
            if (extMoves != null) {
                JSONObject baseMoves = root.optJSONObject("moves");
                if (baseMoves == null) { baseMoves = new JSONObject(); root.put("moves", baseMoves); }
                mergeStringArray(baseMoves, extMoves, "default");
                JSONArray extActs = extMoves.optJSONArray("actions");
                JSONArray baseActs = baseMoves.optJSONArray("actions");
                if (extActs != null) {
                    if (baseActs == null) { baseActs = new JSONArray(); baseMoves.put("actions", baseActs); }
                    for (int i = 0; i < extActs.length(); i++) {
                        JSONObject ea = extActs.optJSONObject(i);
                        if (ea == null) continue;
                        String aid = ea.optString("id", null);
                        JSONObject match = null;
                        for (int j = 0; j < baseActs.length(); j++) {
                            JSONObject ba = baseActs.optJSONObject(j);
                            if (ba != null && aid != null && aid.equals(ba.optString("id", null))) {
                                match = ba; break;
                            }
                        }
                        if (match == null) baseActs.put(ea);
                        else joinArrays(match, ea, "actions");
                    }
                }
            }

            // weights：合并
            JSONObject extW = ext.optJSONObject("weights");
            if (extW != null) {
                JSONObject baseW = root.optJSONObject("weights");
                if (baseW == null) { baseW = new JSONObject(); root.put("weights", baseW); }
                java.util.Iterator<String> it = extW.keys();
                while (it.hasNext()) { String k = it.next(); baseW.put(k, extW.opt(k)); }
            }
        } catch (Throwable ignore) {}
    }

    /** 把 ext 的数组字段追加进 base 的同名字段（去重） */
    private void mergeStringArray(JSONObject base, JSONObject ext, String key) {
        try {
            JSONArray ea = ext.optJSONArray(key);
            if (ea == null) return;
            JSONArray ba = base.optJSONArray(key);
            if (ba == null) { ba = new JSONArray(); base.put(key, ba); }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < ba.length(); i++) {
                Object o = ba.opt(i);
                if (o instanceof String) seen.add((String) o);
            }
            for (int i = 0; i < ea.length(); i++) {
                Object o = ea.opt(i);
                if (o instanceof String && !seen.contains((String) o)) {
                    ba.put((String) o);
                    seen.add((String) o);
                }
            }
        } catch (Throwable ignore) {}
    }

    /** 把 ext 的数组字段追加进 base 的数组字段（用于 categories/moves 内部 actions） */
    private void joinArrays(JSONObject base, JSONObject ext, String key) {
        mergeStringArray(base, ext, key);
    }

    /** 把 ext 里同名动作的台词追加进 base（不覆盖，避免丢内置台词） */
    private void mergeLines(JSONObject base, JSONObject ext) {
        java.util.Iterator<String> it = ext.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray ea = ext.optJSONArray(k);
            if (ea == null) continue;
            try {
                JSONArray ba = base.optJSONArray(k);
                if (ba == null) {
                    ba = new JSONArray();
                    base.put(k, ba);
                }
                for (int i = 0; i < ea.length(); i++) {
                    String v = ea.optString(i, null);
                    if (v != null && !v.isEmpty()) ba.put(v);
                }
            } catch (Throwable ignore) {}
        }
    }

    /** 取指定动画的一句随机台词；没有则返回 null */
    public String lineFor(String id) {
        if (id == null) return null;
        List<String> l = lines.get(id);
        if (l == null || l.isEmpty()) return null;
        return l.get(rnd.nextInt(l.size()));
    }

    /**
     * 取指定动画的**全部**台词（导出角色包时要把台词一起带走）。
     * <p>{@link #lineFor} 是随机取一句，导出要的是完整清单，所以必须另开一个口子。
     * 没有台词返回 null。
     */
    public List<String> linesOf(String id) {
        if (id == null) return null;
        List<String> l = lines.get(id);
        return (l == null || l.isEmpty()) ? null : new ArrayList<String>(l);
    }

    /** 全部有台词的动作名（导出时对账用） */
    public java.util.Set<String> lineIds() { return new java.util.LinkedHashSet<String>(lines.keySet()); }

    /** 全部可播动画名（有序，去重）= 轮播池 + 事件组 + 移动，供动作选择气泡使用 */
    public java.util.List<String> allNames() { return allNames; }

    /** 取指定动画名的 Clip，不存在返回 null（含事件/移动动画） */
    public Clip clipByName(String id) {
        if (id == null) return null;
        return master.get(id);
    }

    /**
     * 事件组里随机取一个动画（balance / whisper / workStatus）。
     * <p>找不到该组或该组为空时返回 null —— 调用方应视为"这次不演"，
     * 不要回退成随便播一个，否则"工作场景"会冒出吃东西的动画。
     */
    public Clip pickEventOf(String group) {
        if (group == null) return null;
        Group g = eventGroups.get(group);
        if (g == null) return null;
        return g.pick(rnd);
    }

    /** {@link #pickEventOf} 的取名版本；没有则返回 null */
    public String pickEventName(String group) {
        Clip c = pickEventOf(group);
        return c != null ? c.id : null;
    }

    /** 事件组名（供菜单/断言用） */
    public java.util.List<String> eventGroupIds() {
        return new ArrayList<>(eventGroups.keySet());
    }

    /** 事件组的展示名（设置页分类列表用） */
    public static String eventLabel(String group) {
        if ("balance".equals(group)) return "余额";
        if ("whisper".equals(group)) return "碎碎念";
        if ("workStatus".equals(group)) return "工作状态";
        return group;
    }

    // ==================== 1.x → 场景 的兼容映射（给 ActionSystem 用）====================

    /**
     * 1.x 组 → 新场景 的兼容映射。
     *
     * <p>改造前的老配置（{@code pet_anim.json}）与用户已有的素材包**不用改**就能接进场景体系：
     * {@code idle/turn/drag/clicks} → 同名场景，{@code moves} → {@code roam}，
     * {@code categories}（那 80 个随机动作）→ {@code pool}。
     */
    public java.util.Map<String, java.util.List<String>> legacySceneMap() {
        java.util.Map<String, java.util.List<String>> m = new java.util.LinkedHashMap<>();
        m.put(SceneRegistry.IDLE, idsOf(idle));
        m.put(SceneRegistry.TURN, idsOf(turn));
        m.put(SceneRegistry.DRAG, idsOf(drag));
        m.put(SceneRegistry.CLICK, idsOf(clicks));
        java.util.List<String> roam = new java.util.ArrayList<>();
        for (Clip c : moveDefault) if (!roam.contains(c.id)) roam.add(c.id);
        for (Group g : moveGroups.values()) for (Clip c : g.clips) if (!roam.contains(c.id)) roam.add(c.id);
        for (Clip c : moveClips) if (!roam.contains(c.id)) roam.add(c.id);
        m.put(SceneRegistry.ROAM, roam);
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (Group g : categories) for (Clip c : g.clips) if (!pool.contains(c.id)) pool.add(c.id);
        m.put(SceneRegistry.POOL, pool);
        return m;
    }

    /** 事件组 → 事件场景 的映射 */
    public java.util.Map<String, java.util.List<String>> eventMap() {
        java.util.Map<String, java.util.List<String>> m = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Group> e : eventGroups.entrySet()) {
            m.put(e.getKey(), idsOf(e.getValue().clips));
        }
        return m;
    }

    private java.util.List<String> idsOf(java.util.List<Clip> src) {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (Clip c : src) l.add(c.id);
        return l;
    }

    private void buildAllClips() {
        allClips.clear();
        java.util.LinkedHashMap<String, Clip> uniq = new java.util.LinkedHashMap<>();
        addUniq(uniq, idle);
        addUniq(uniq, turn);
        addUniq(uniq, drag);
        addUniq(uniq, clicks);
        addUniq(uniq, moveDefault);
        for (Group g : moveGroups.values()) addUniq(uniq, g.clips);
        for (Group g : categories) addUniq(uniq, g.clips);
        allClips.addAll(uniq.values());
        buildMaster();
        Log.i(TAG, "rotate pool=" + allClips.size() + " playable=" + allNames.size()
                + " events=" + eventGroups.size() + " moves=" + moveClips.size());
    }

    /**
     * 建「全部**可播**动画」名单 + id→Clip 查找表。
     *
     * <p>{@link #allClips}（轮播池）只管**待机随机**；事件动画（余额/碎碎念/工作状态）
     * 与移动动画是**按需触发**的，刻意**不混进随机轮播** —— 但它们必须能被动作菜单列出、
     * 能被 {@link #clipByName} 找到。否则就是之前的局面：用户看着"106 个动画"，
     * 实际只选得到 88 个，另外 18 个连手动播都播不了。
     */
    private void buildMaster() {
        java.util.LinkedHashMap<String, Clip> uniq = new java.util.LinkedHashMap<>();
        addUniq(uniq, allClips);
        for (Group g : eventGroups.values()) addUniq(uniq, g.clips);
        addUniq(uniq, moveClips);
        // ★ 2.0 场景里的动作（含 pool/festival/tap_* 等新场景）也要进"可播"名单，
        //   否则动作菜单与按名播放都会漏掉它们。
        addUniq(uniq, sceneClips);
        master.clear();
        master.putAll(uniq);
        allNames = new ArrayList<>(uniq.keySet());
    }
    private void addUniq(java.util.LinkedHashMap<String, Clip> uniq, List<Clip> src) {
        if (src == null) return;
        for (Clip c : src) if (c != null && !uniq.containsKey(c.id)) uniq.put(c.id, c);
    }

    /** 全部动画名（去重后的轮播池顺序） */
    public List<String> allIds() { List<String> l = new ArrayList<>(); for (Clip c : allClips) l.add(c.id); return l; }
    /** 全部分类名（含 idle/click 等特殊组前置，以及事件组与移动） */
    public List<String> categoryIds() {
        List<String> l = new ArrayList<>();
        if (!idle.isEmpty()) l.add("待机");
        if (!clicks.isEmpty()) l.add("点击回应");
        for (Group g : categories) l.add(g.id);
        // ★ 事件组与移动也列出来，否则设置页选择器对它们"看不见"（只能靠随机触发）
        for (String k : eventGroups.keySet()) l.add(eventLabel(k));
        if (!moveClips.isEmpty()) l.add("移动");
        return l;
    }
    /** 指定分类下的动画名；"待机"/"点击回应"/事件组/"移动" 为内置组 */
    public List<String> idsOfCategory(String cat) {
        List<String> l = new ArrayList<>();
        if ("待机".equals(cat)) { for (Clip c : idle) l.add(c.id); return l; }
        if ("点击回应".equals(cat)) { for (Clip c : clicks) l.add(c.id); return l; }
        for (Group g : categories) if (g.id.equals(cat)) { for (Clip c : g.clips) l.add(c.id); }
        for (java.util.Map.Entry<String, Group> e : eventGroups.entrySet()) {
            if (eventLabel(e.getKey()).equals(cat)) {
                for (Clip c : e.getValue().clips) l.add(c.id);
                return l;
            }
        }
        if ("移动".equals(cat)) { for (Clip c : moveClips) l.add(c.id); return l; }
        return l;
    }
    public int rotatePoolSize() { return allClips.size(); }

    /** 待机轮播：从全量动画池随机取一个，尽量不与上一个重复 */
    public Clip pickRotate() {
        if (allClips.isEmpty()) return pickIdle();
        if (allClips.size() == 1) { Clip c = allClips.get(0); lastRotateId = c.id; return c; }
        Clip c;
        int guard = 0;
        do {
            c = allClips.get(rnd.nextInt(allClips.size()));
        } while (c.id.equals(lastRotateId) && ++guard < 8);
        lastRotateId = c.id;
        return c;
    }

    /** 加权随机轮播：preferredId 对应动画权重 ×weight，其余动作权重 1。
     *  让节日/时段动作更常出现，但普通动作照常参与随机，不独占待机。 */
    public Clip pickWeightedRotate(String preferredId, double weight) {
        if (allClips.isEmpty()) return pickIdle();
        if (preferredId == null || weight <= 1.0 || allClips.size() == 1) return pickRotate();
        double total = 0;
        for (Clip c : allClips) total += (c != null && preferredId.equals(c.id)) ? weight : 1.0;
        if (total <= 0) return pickRotate();
        Clip fallback = allClips.get(allClips.size() - 1);
        // 最多抽 8 次，避免连续两次同一动画；抽不到就退回普通随机
        for (int attempt = 0; attempt < 8; attempt++) {
            double r = Math.random() * total;
            for (Clip c : allClips) {
                if (c == null) continue;
                r -= preferredId.equals(c.id) ? weight : 1.0;
                if (r <= 0) {
                    fallback = c;
                    break;
                }
            }
            if (!fallback.id.equals(lastRotateId)) { lastRotateId = fallback.id; return fallback; }
        }
        lastRotateId = fallback.id;
        return fallback;
    }

    /**
     * 读取动画完整播放一轮的时长（毫秒），解析 animated WebP 的 ANMF 帧时长并累加。
     * 结果缓存；解析失败返回默认值 defaultMs。
     */
    public int clipDurationMs(String id, int defaultMs) {
        if (id == null) return defaultMs;
        Integer c = durCache.get(id);
        if (c != null) return c;
        int total = defaultMs;
        try {
            byte[] d = readClipBytes(id, "webp");
            if (d == null) throw new Exception("not found");
            total = parseWebpDuration(d);
            if (total <= 0) total = defaultMs;
        } catch (Throwable t) {
            total = defaultMs;
        }
        durCache.put(id, total);
        return total;
    }

    /** 解析 WebP(VP8X + ANIM + ANMF) 每帧 duration 累加。 */
    private static int parseWebpDuration(byte[] d) {
        if (d == null || d.length < 12) return 0;
        if (!(d[0]=='R'&&d[1]=='I'&&d[2]=='F'&&d[3]=='F')) return 0;
        if (!(d[8]=='W'&&d[9]=='E'&&d[10]=='B'&&d[11]=='P')) return 0;
        int off = 12, total = 0;
        while (off + 8 <= d.length) {
            // ★★ 实测 bug（轮次㉑ 模拟器上发现"预览显示约 0 ms"）：fourcc 原来被当成
            //   **小端整数**读出来再与 0x4E414E4D（= 'FMNA'）比 —— 真正的 'ANMF' 字节是
            //   41 4E 4D 46，按小端读是 0x464D4E41 ⇒ **永远匹配不上**，
            //   于是每个动画的时长都解析成 0（"播一次"的场景拿不到真实时长、
            //   待机轮播也只好用兜底值）。判据：内置那个 189 帧的 webp 应当算出 10050 ms。
            //   直接比字节最不容易错，也不再依赖对字节序的理解。
            boolean isAnmf = (d[off] == 'A' && d[off+1] == 'N' && d[off+2] == 'M' && d[off+3] == 'F');
            int size = (d[off+4]&0xFF) | ((d[off+5]&0xFF)<<8)
                     | ((d[off+6]&0xFF)<<16) | ((d[off+7]&0xFF)<<24);
            int dataOff = off + 8;
            if (isAnmf) {
                if (dataOff + 15 < d.length) {
                    int p = dataOff + 12;                     // 帧头第 13~15 字节 = duration（24 位小端）
                    int dur = (d[p]&0xFF) | ((d[p+1]&0xFF)<<8) | ((d[p+2]&0xFF)<<16);
                    total += dur;
                }
            }
            off = dataOff + size + (size & 1);
            if (size < 0) break;
        }
        return total;
    }

    // ===== 解码 =====

    /**
     * 解码前按需缩尺寸（长边 &gt; {@link #MAX_DECODE_EDGE} 才缩，且**等比**）。
     * <p>内置素材（约 500×556）走不到这里；只有用户在「添加动作」里选进来的大图才生效。
     */
    private static void capDecodeSize(ImageDecoder dec, ImageDecoder.ImageInfo info) {
        try {
            android.util.Size s = info.getSize();
            int w = s.getWidth(), h = s.getHeight();
            int max = Math.max(w, h);
            if (max <= MAX_DECODE_EDGE) return;
            float k = (float) MAX_DECODE_EDGE / (float) max;
            dec.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
        } catch (Throwable ignore) {}
    }

    /** 同步解码指定动画为 Drawable（带缓存）。失败返回 null。 */
    public Drawable getDrawable(String id) {
        if (id == null) return null;
        Drawable d = cache.get(id);
        if (d != null) return d;
        try {
            final byte[] data = readClipBytes(id, "webp");
            if (data == null) return null;

            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(data);
                d = ImageDecoder.decodeDrawable(src, new ImageDecoder.OnHeaderDecodedListener() {
                    @Override public void onHeaderDecoded(ImageDecoder dec, ImageDecoder.ImageInfo info,
                                                          ImageDecoder.Source source) {
                        dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        capDecodeSize(dec, info);
                    }
                });
                cache.put(id, d);
                return d;
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "decode " + id + " failed: " + t);
            return null;
        }
    }

    /**
     * 是否处于"停播"状态。
     *
     * <p>★ 为什么必须有这个标志位：{@link #startDrawable} 注册了
     * {@code onAnimationEnd} 回调，动画一结束就自动重新 {@code start()}。
     * 所以单纯调用 {@code stopAll()} **停不住** —— 回调会立刻把它拉起来。
     * 实测（build/who_renders.py）：停播后 {@code AnimatedImageTh} 仍占 2.6%，
     * 就是这个原因。
     *
     * <p>加了标志位后，回调会让位；恢复时由 {@link #resumeAfterStop} 统一重启。
     */
    private volatile boolean playbackStopped = false;

    /** 停播：压制自动重启，并停掉所有动图。省电档闲置 / 熄屏时调用 */
    public void stopAllAndHold() {
        playbackStopped = true;
        stopAll();
    }

    /** 恢复：解除压制并重启传入的动图 */
    public void resumeAfterStop(Drawable d) {
        playbackStopped = false;
        if (d != null) startDrawable(d);
    }

    public boolean isPlaybackStopped() { return playbackStopped; }

    /** 启动动图循环播放 */
    public void startDrawable(final Drawable d) {
        if (Build.VERSION.SDK_INT >= 28 && d instanceof AnimatedImageDrawable) {
            final AnimatedImageDrawable a = (AnimatedImageDrawable) d;
            try {
                a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                a.registerAnimationCallback(new android.graphics.drawable.Animatable2.AnimationCallback() {
                    @Override public void onAnimationEnd(Drawable w) {
                        // ★ 停播期间不许自动重启，否则 stopAll 形同虚设
                        if (playbackStopped) return;
                        try { a.start(); } catch (Throwable ignore) {}
                    }
                });
                a.start();
            } catch (Throwable ignore) {}
        }
    }

    /** 播放一次（不循环），播完回调 onEnd（主线程）。 */
    public void startDrawableOnce(final Drawable d, final Runnable onEnd) {
        if (Build.VERSION.SDK_INT >= 28 && d instanceof AnimatedImageDrawable) {
            final AnimatedImageDrawable a = (AnimatedImageDrawable) d;
            try {
                a.setRepeatCount(0);
                a.registerAnimationCallback(new android.graphics.drawable.Animatable2.AnimationCallback() {
                    @Override public void onAnimationEnd(Drawable w) {
                        if (onEnd != null) ui.post(onEnd);
                    }
                });
                a.start();
            } catch (Throwable ignore) {
                if (onEnd != null) ui.post(onEnd);
            }
        } else {
            if (onEnd != null) ui.post(onEnd);
        }
    }

    /** 停止所有动图 */
    public void stopAll() {
        for (Drawable d : cache.values()) {
            if (Build.VERSION.SDK_INT >= 28 && d instanceof AnimatedImageDrawable) {
                try { ((AnimatedImageDrawable) d).stop(); } catch (Throwable ignore) {}
            }
        }
    }

    // ===== 内容边界（用于贴边 / 气泡对位） =====
    /**
     * 已算过的动画内容边界缓存：画布尺寸 + 画布空间的留白比例。
     *
     * <p>★ 为什么连**画布尺寸**一起存：clip 的画布长宽比不一定等于视口长宽比
     * （角色包格式文档推荐"竖构图 ~500×556"，而视口是 16:9），
     * 中间隔着"等比缩放 + 居中留白"这一步。只存留白比例的话，
     * 那块居中留白会被漏掉，表现成"贴边差 156px"（见 {@link ContentInset#of}）。
     */
    private final Map<String, ContentInsetProbe.Measured> insetCache = new HashMap<>();

    /**
     * 量一个动画的内容边界（alpha 非透明区域的包围盒 + 画布尺寸）。
     * 结果会缓存，每个动画只解码扫描一次 —— <b>必须在后台线程调用</b>。
     *
     * @return null = 量不出来（API&lt;28 / 素材缺失 / 全透明），调用方按"无留白"处理
     */
    public ContentInsetProbe.Measured measureClip(String id) {
        if (id == null) return null;
        ContentInsetProbe.Measured c = insetCache.get(id);
        if (c != null) return c;
        if (Build.VERSION.SDK_INT < 28) return null;
        try {
            final byte[] data = readClipBytes(id, "webp");
            if (data == null) return null;

            ImageDecoder.Source src = ImageDecoder.createSource(data);
            android.graphics.Bitmap bm = ImageDecoder.decodeBitmap(src,
                    new ImageDecoder.OnHeaderDecodedListener() {
                        @Override public void onHeaderDecoded(ImageDecoder dec, ImageDecoder.ImageInfo info,
                                                              ImageDecoder.Source source) {
                            dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            capDecodeSize(dec, info);
                        }
                    });
            if (bm == null) return null;
            int W = bm.getWidth(), H = bm.getHeight();
            int[] bb = ContentInsetProbe.alphaBBox(bm);   // 与"上传图片/动图"共用同一套扫描
            bm.recycle();
            if (bb == null) return null;
            ContentInsetProbe.Measured m = new ContentInsetProbe.Measured(W, H, new float[]{
                    (float) bb[0] / W,
                    (float) (W - 1 - bb[2]) / W,
                    (float) bb[1] / H,
                    (float) (H - 1 - bb[3]) / H
            });
            insetCache.put(id, m);
            Log.i(TAG, "inset " + id + " " + W + "x" + H
                    + " L=" + m.alpha[0] + " R=" + m.alpha[1]
                    + " T=" + m.alpha[2] + " B=" + m.alpha[3]);
            return m;
        } catch (Throwable t) {
            Log.w(TAG, "measureClip " + id + " failed: " + t);
            return null;
        }
    }

    /**
     * 只查缓存、**不解码**的版本（UI 线程可用）。
     *
     * <p>为什么需要它：换动作时若只能"清空边距 + 异步重算"，那么紧接着的
     * 贴边判定就会读到空边距 ⇒ 桌宠被拉回屏内（2026-10-08 报障的根因）。
     * 命中缓存时同步拿到值，这个空窗根本不存在。
     */
    public ContentInsetProbe.Measured cachedClipInset(String id) {
        if (id == null) return null;
        return insetCache.get(id);
    }

    public void recycle() {
        stopAll();
        cache.clear();
        insetCache.clear();
    }

    public int clipCount() {
        return cache.size();
    }
}
