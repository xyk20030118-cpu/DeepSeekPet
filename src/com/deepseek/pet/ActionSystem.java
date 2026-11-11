package com.deepseek.pet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 动作系统 —— <b>场景层</b>：把"某个场景该播哪个动作"从代码里搬进数据。
 *
 * <h3>与 {@link PetAnimPack} 的分工</h3>
 * <ul>
 *   <li>{@code PetAnimPack}＝**素材层**：解析配置、解码 animated WebP、缓存 drawable、
 *       记时长与内容边距、管台词。</li>
 *   <li>{@code ActionSystem}＝**场景层**：场景 → 动作清单（带权重）。谁在什么时候切哪个场景
 *       由 {@code FloatingService}/{@code PetView} 决定，但"那个场景有哪些动作"只在这里查。</li>
 * </ul>
 *
 * <h3>两种配置都能吃</h3>
 * <ul>
 *   <li><b>2.0</b>（{@code anim/pack.json}）：显式 {@code scenes} / {@code events}；</li>
 *   <li><b>1.x</b>（老的 {@code pet_anim.json}，以及用户已有的素材包）：
 *       由 {@link PetAnimPack#legacySceneMap()} 自动映射 —— idle/turn/drag/clicks→同名场景，
 *       moves→roam，categories→pool，events→events。用户升级后**不需要改自己的包**。</li>
 * </ul>
 *
 * <h3>待机为什么只播一个</h3>
 * 用户要求"取消所有待机动作、只保留默认的那一个"。这里落地为：{@code idle} 场景只有 1 个动作，
 * 而原来那 80 个随机动作进 {@code pool} 场景 —— <b>pool 默认不参与待机抽取</b>
 * （{@link #setPoolEnabled(boolean)} 为假），但动作本身仍在动作库里可手选、可绑到别的场景。
 * 这样"取消"是可逆的，也没有丢掉任何素材。
 *
 * <h3>用户覆盖层（P1）</h3>
 * 装配顺序固定为 <b>角色包配置 → 用户覆盖层</b>：
 * {@link #load(org.json.JSONObject)} 先建好角色包那套场景表，
 * {@link #applyOverrides(org.json.JSONObject)} 再把用户改过的场景**替换**上去
 * （只替换出现的，其余保持角色包绑定）。覆盖层本身由 {@link SceneBindings} 负责读写与按角色隔离。
 */
public class ActionSystem {

    private final Map<String, PetAnimPack.Group> scenes = new LinkedHashMap<>();
    private final Map<String, PetAnimPack.Group> events = new LinkedHashMap<>();
    private final Random rnd = new Random();

    private PetAnimPack pack;
    /** 待机随机池是否参与待机抽取（用户开关，默认关） */
    private boolean poolEnabled = false;
    /** 配置来源，仅用于日志/断言的自我说明 */
    private String source = "none";
    /**
     * 被**用户覆盖层**改过的场景（见 {@link SceneBindings}）。
     * <p>只用于设置页标"✎ 自定义"与断言，不参与抽取 —— 抽取只看 {@link #scenes}/{@link #events}，
     * 覆盖是在装配时就已经合并进去了（"改一个场景不影响其余 17 个"）。
     */
    private final Set<String> overridden = new LinkedHashSet<>();

    public void attach(PetAnimPack p) {
        this.pack = p;
    }

    /**
     * 给设置页用的**只读装配**：加载配置、建好场景表，但**不碰 drawable 缓存**
     * （{@link PetAnimPack#getDrawable} 是惰性解码的，所以这个调用很轻）。
     * 设置页只拿它读"每个场景绑了几个动作"，不影响悬浮窗里正在跑的那一份。
     *
     * <p>★ 两处必须跟着**当前角色**走（P1 修）：
     * <ol>
     *   <li>素材层要收角色包目录 —— 原来传 null 只读内置 assets，
     *       于是导入角色包之后设置页显示的绑定跟运行期用的**不是同一套**；</li>
     *   <li>装配完要应用 {@link SceneBindings} 覆盖层 —— 否则用户刚改完的绑定
     *       在设置页里看不见（仍显示角色包默认），"恢复默认"也会恢复错东西。</li>
     * </ol>
     */
    public static ActionSystem loadForDisplay(android.content.Context ctx) {
        ActionSystem as = new ActionSystem();
        PetAnimPack p = new PetAnimPack();
        String cid = PetCharacter.BUILTIN_ID;
        java.io.File chDir = null;
        try {
            PetCharacter ch = CharacterStore.current(ctx);
            if (ch != null) chDir = ch.dir;
            cid = CharacterStore.currentId(ctx);
        } catch (Throwable ignore) {}
        p.load(ctx.getApplicationContext(), chDir);
        as.attach(p);
        as.load(p.configRoot());
        try { as.applyOverrides(SceneBindings.loadRoot(ctx, cid)); } catch (Throwable ignore) {}
        return as;
    }

    /**
     * 按配置内容自动选 2.0 / 1.x。
     * <p>判据：有 {@code scenes} 节点 = 2.0；否则按 1.x 老分组映射。
     * 于是"老配置 / 老素材包 / 新角色包"三种输入共用同一条装配入口。
     */
    public void load(org.json.JSONObject root) {
        if (root != null && root.optJSONObject("scenes") != null) loadV2(root);
        else loadLegacy();
    }

    public PetAnimPack pack() { return pack; }

    public String source() { return source; }

    public void setPoolEnabled(boolean on) { this.poolEnabled = on; }

    public boolean isPoolEnabled() { return poolEnabled; }

    // ==================== 装配 ====================

    /** 1.x 兼容装配：直接用素材层已有的分组（老 schema / 老素材包） */
    public void loadLegacy() {
        scenes.clear();
        events.clear();
        overridden.clear();
        if (pack == null) return;
        for (Map.Entry<String, List<String>> e : pack.legacySceneMap().entrySet()) {
            putScene(scenes, e.getKey(), plainClips(e.getValue()));
        }
        for (Map.Entry<String, List<String>> e : pack.eventMap().entrySet()) {
            putScene(events, e.getKey(), plainClips(e.getValue()));
        }
        source = "legacy(1.x)";
        ensureAllScenes();
    }

    /**
     * 2.0 装配：{@code {"version":2,"scenes":{scene:{"clips":[...]}},"events":{group:[...]}}}
     * <p>允许 clips 里混写字符串与 {@code {"id":..,"weight":..}} 对象；
     * 也允许写成 {@code "scenes":{"idle":["动作名", ...]}} 这种简写。
     * <p>没被配置提到的场景会自动补成空场景（UI 才能显示"未绑定"）。
     */
    public void loadV2(org.json.JSONObject root) {
        scenes.clear();
        events.clear();
        overridden.clear();
        if (root != null) {
            readGroups(root.optJSONObject("scenes"), scenes);
            readGroups(root.optJSONObject("events"), events);
            source = "pack.json(2.0)";
        }
        ensureAllScenes();
    }

    private void readGroups(org.json.JSONObject obj, Map<String, PetAnimPack.Group> into) {
        if (obj == null) return;
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String id = it.next();
            putScene(into, id, clipListOf(obj.opt(id)));
        }
    }

    /**
     * 一个场景的配置值 → 动作清单（**带权重**）。
     *
     * <p>三种写法都吃：
     * <pre>"idle": ["动作名", …]                          // 简写，权重 1
     * "idle": {"clips": ["动作名", …]}                  // 展开写法
     * "idle": {"clips": [{"id":"吃Token","weight":3}]}  // ★ 带权重</pre>
     *
     * ★ 改造前这里只取名字、把 {@code weight} **丢掉**，而 {@link #putScene} 又一律按权重 1 登记
     * ⇒ 角色包 {@code pack.json} 里写的权重**从来没生效过**（P1 修掉）。
     */
    private List<PetAnimPack.Clip> clipListOf(Object v) {
        org.json.JSONArray arr = null;
        if (v instanceof org.json.JSONArray) arr = (org.json.JSONArray) v;
        else if (v instanceof org.json.JSONObject) arr = ((org.json.JSONObject) v).optJSONArray("clips");
        List<PetAnimPack.Clip> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof String) {
                String s = (String) o;
                if (!s.isEmpty()) out.add(new PetAnimPack.Clip(s, 1, false));
            } else if (o instanceof org.json.JSONObject) {
                org.json.JSONObject j = (org.json.JSONObject) o;
                String id = j.optString("id", j.optString("name", null));
                if (id != null && !id.isEmpty()) {
                    out.add(new PetAnimPack.Clip(id, Math.max(1, j.optInt("weight", 1)), false));
                }
            }
        }
        return out;
    }

    /** 一串名字 → 权重 1 的清单（1.x 兼容映射走这条） */
    private List<PetAnimPack.Clip> plainClips(List<String> ids) {
        List<PetAnimPack.Clip> out = new ArrayList<>();
        if (ids != null) {
            for (String s : ids) if (s != null && !s.isEmpty()) out.add(new PetAnimPack.Clip(s, 1, false));
        }
        return out;
    }

    /**
     * 把一批动作放进某个场景。
     * <p>★ <b>权重必须带过去</b>（{@code Math.max(1, c.weight)}）—— 这是"配置里的 weight 真的
     * 起作用"的唯一落点（{@link PetAnimPack.Group#pick} 按权重抽）。
     * {@code noMirror} 仍取自素材层的同名 clip（配置里不重复声明）。
     * <p>配置里写了、但素材包里没有这个文件：**仍然登记**（设置页要能提示"缺文件"），
     * 播放时 {@code getDrawable} 拿不到 ⇒ 由调用方按"不播"处理。
     */
    private void putScene(Map<String, PetAnimPack.Group> into, String scene,
                          List<PetAnimPack.Clip> clips) {
        if (scene == null || scene.isEmpty()) return;
        PetAnimPack.Group g = new PetAnimPack.Group(scene);
        if (clips != null) {
            for (PetAnimPack.Clip c : clips) {
                if (c == null || c.id == null || c.id.isEmpty()) continue;
                PetAnimPack.Clip base = (pack != null) ? pack.clipByName(c.id) : null;
                boolean noMirror = base != null && base.noMirror;
                g.add(new PetAnimPack.Clip(c.id, Math.max(1, c.weight), noMirror));
            }
        }
        into.put(scene, g);
    }

    // ==================== 用户覆盖层（P1）====================

    /**
     * 应用**用户覆盖层**（{@link SceneBindings} 的 JSON 根）。
     *
     * <p>语义：**只替换覆盖里出现的场景**，其余场景保持角色包的绑定不动 ——
     * 这是"合并"而不是"替换整套"，所以用户改一个场景不会把另外 17 个弄丢。
     * 覆盖里写的事件场景（balance / whisper / workStatus）进 {@code events} 表，其余进 {@code scenes} 表。
     *
     * <p>未知场景 id 直接忽略：覆盖层文件是设备上的数据，一条脏数据不该改变装配结果。
     */
    public void applyOverrides(org.json.JSONObject root) {
        overridden.clear();
        if (root == null) return;
        applyGroup(root.optJSONObject("scenes"), scenes);
        applyGroup(root.optJSONObject("events"), events);
        android.util.Log.i("DSPBind", "场景覆盖已应用 scenes=" + overridden.size());
    }

    private void applyGroup(org.json.JSONObject obj, Map<String, PetAnimPack.Group> into) {
        if (obj == null) return;
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String id = it.next();
            if (!SceneRegistry.isKnown(id)) continue;
            putScene(into, id, clipListOf(obj.opt(id)));
            overridden.add(id);
        }
    }

    /** 被用户覆盖层改过的场景个数 */
    public int overrideCount() { return overridden.size(); }

    /** 这个场景是不是被用户改过（true 时它是覆盖层给的绑定，不是角色包的） */
    public boolean isOverridden(String scene) { return overridden.contains(scene); }

    /** 被改过的场景 id（保持覆盖文件里的顺序） */
    public List<String> overriddenSceneIds() { return new ArrayList<>(overridden); }

    /** 配置没提到的场景补空组：设置页需要把它们也列出来 */
    private void ensureAllScenes() {
        for (String id : SceneRegistry.ids()) if (!scenes.containsKey(id)) scenes.put(id, new PetAnimPack.Group(id));
        for (String id : SceneRegistry.eventIds()) if (!events.containsKey(id)) events.put(id, new PetAnimPack.Group(id));
    }

    // ==================== 查询 ====================

    public List<String> sceneIds() {
        List<String> l = new ArrayList<>(scenes.keySet());
        return l;
    }

    public List<String> eventIds() {
        List<String> l = new ArrayList<>(events.keySet());
        return l;
    }

    public boolean isEvent(String scene) { return events.containsKey(scene) && !scenes.containsKey(scene); }

    public List<String> clipsOf(String scene) {
        PetAnimPack.Group g = isEvent(scene) ? events.get(scene) : scenes.get(scene);
        List<String> l = new ArrayList<>();
        if (g != null) for (PetAnimPack.Clip c : g.clips) l.add(c.id);
        return l;
    }

    /** 某个场景的绑定清单**（带权重）**——设置页要显示"×N"就得看这个，见 P1。 */
    public List<PetAnimPack.Clip> bindsOf(String scene) {
        PetAnimPack.Group g = isEvent(scene) ? events.get(scene) : scenes.get(scene);
        return (g != null) ? new ArrayList<>(g.clips) : new ArrayList<PetAnimPack.Clip>();
    }

    /** 某个场景里某个动作的权重；0 = 该场景没绑它 */
    public int weightOf(String scene, String clipId) {
        if (clipId == null) return 0;
        for (PetAnimPack.Clip c : bindsOf(scene)) if (clipId.equals(c.id)) return Math.max(1, c.weight);
        return 0;
    }

    /**
     * 动作库：本角色全部可用动作（含事件与池子，去重、保序）。
     *
     * <p>★ 除了"当前绑定里出现的"，还要并上素材层的**全部可播名单**
     * （{@link PetAnimPack#allNames()}）—— 否则用户把某个场景的绑定清空之后，
     * 只在那个场景里出现过的动作会**从动作库消失**，再也没法绑回来（自己把自己锁死）。
     */
    public List<String> allClipIds() {
        List<String> out = new ArrayList<>();
        for (PetAnimPack.Group g : scenes.values())
            for (PetAnimPack.Clip c : g.clips) if (!out.contains(c.id)) out.add(c.id);
        for (PetAnimPack.Group g : events.values())
            for (PetAnimPack.Clip c : g.clips) if (!out.contains(c.id)) out.add(c.id);
        if (pack != null) {
            List<String> all = pack.allNames();
            if (all != null) for (String n : all) if (n != null && !n.isEmpty() && !out.contains(n)) out.add(n);
        }
        return out;
    }

    public int clipCount() { return allClipIds().size(); }

    /**
     * 选一个要播的动作（返回动作名；没有可播的返回 null）。
     *
     * <p>待机特例：{@code pool} 只有用户打开"待机随机"时才参与 —— 参与时池内动作权重按 1 算，
     * 默认待机动作按 3 算（保证默认动作仍最常出现，不会被 80 个池子动作淹掉）。
     */
    public String pick(String scene) {
        if (scene == null) return null;
        if (isEvent(scene)) return idOf(events.get(scene));
        if (SceneRegistry.IDLE.equals(scene) && poolEnabled) {
            PetAnimPack.Group idle = scenes.get(SceneRegistry.IDLE);
            PetAnimPack.Group pool = scenes.get(SceneRegistry.POOL);
            if (pool != null && !pool.clips.isEmpty()) {
                PetAnimPack.Group mix = new PetAnimPack.Group("idle+pool");
                if (idle != null) for (PetAnimPack.Clip c : idle.clips)
                    mix.add(new PetAnimPack.Clip(c.id, Math.max(1, c.weight) * 3, c.noMirror));
                for (PetAnimPack.Clip c : pool.clips)
                    mix.add(new PetAnimPack.Clip(c.id, Math.max(1, c.weight), c.noMirror));
                return idOf(mix);
            }
        }
        PetAnimPack.Group g = scenes.get(scene);
        return idOf(g);
    }

    private String idOf(PetAnimPack.Group g) {
        if (g == null) return null;
        PetAnimPack.Clip c = g.pick(rnd);
        return c != null ? c.id : null;
    }

    /** 台词（转发素材层） */
    public String lineFor(String clipId) { return pack != null ? pack.lineFor(clipId) : null; }
}
