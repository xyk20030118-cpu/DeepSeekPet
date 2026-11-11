package com.deepseek.pet;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;

/** 本地保存用户配置：API Key、桌宠大小、透明度、位置、自定义图片 */
public class Prefs {
    private static final String NAME = "deepseek_pet";
    private static final String KEY_API = "api_key";
    private static final String KEY_SCALE = "pet_scale";
    private static final String KEY_ALPHA = "pet_alpha";
    private static final String KEY_USE_CUSTOM = "use_custom_image";
    private static final String KEY_POS_X = "pos_x";
    private static final String KEY_POS_Y = "pos_y";
    private static final String KEY_KEEP_RATIO = "keep_ratio";
    private static final String KEY_SHOW_BALANCE = "show_balance";
    /** ★ 轮次76：余额气泡总开关（默认开；关掉连轮询一起停）。 */
    private static final String KEY_BALANCE_BUBBLE_ON = "balance_bubble_on";
    private static final String KEY_USE_CUSTOM_ANIM = "use_custom_anim";
    private static final String KEY_PERSONA = "chat_persona";
    private static final String KEY_PRESETS = "chat_presets";       // JSON 数组 [{name,content}]
    private static final String KEY_PRESET_IDX = "chat_preset_idx"; // 当前选中下标
    private static final String KEY_CHAT_ENABLED = "chat_enabled";
    private static final String KEY_CHAT_MODEL = "chat_model";
    private static final String KEY_REASONING_EFFORT = "reasoning_effort";  // "" 不发送 / minimal / low / medium / high / none
    private static final String KEY_THINKING_TYPE = "thinking_type";        // "" 不发送 / enabled / disabled
    private static final String KEY_PROACTIVE = "proactive_chat";       // 主动聊天开关
    private static final String KEY_PROACTIVE_MIN = "proactive_minutes"; // 间隔分钟
    private static final String KEY_MOOD_WATCH = "mood_watch";           // 识别主人在干嘛并搭话
    private static final String KEY_MOOD_CD_MIN = "mood_cd_minutes";     // 场景搭话最小间隔(分钟)【旧】
    private static final String KEY_MOOD_CD_SEC = "mood_cd_sec";          // 场景搭话最小间隔(秒)
    private static final String KEY_MOOD_STAY_SEC = "mood_stay_sec";      // 久留阈值(秒)
    private static final String KEY_QUIET_SEC = "quiet_sec";              // 碰过桌宠后多久内不主动(秒)
    private static final String KEY_INTERACT_CD_MS = "interact_cd_ms";     // 互动对话冷却(毫秒，-1=纯本地不调API)
    private static final String KEY_PET_SHOULD_RUN = "pet_should_run";      // 用户上次是否让桌宠运行（决定开机自启）
    private static final String KEY_KEEPALIVE = "keep_alive_enabled";        // 看门狗：被系统清掉后自动恢复
    private static final String KEY_KEEPALIVE_MIN = "keep_alive_minutes";    // 巡检间隔(分钟)
    private static final String KEY_GREETING_ON = "greeting_on";            // 启动/退出问候语总开关
    
    private static final String KEY_BAL_NOTIFY_ON = "bal_notify_on";       // 余额不足时让桌宠提醒
    private static final String KEY_USAGE_NOTIFY_ON = "usage_notify_on";   // 用量增长时让桌宠播报
    private static final String KEY_MOOD_STAY_MIN = "mood_stay_minutes";   // 同一应用停留多久算“久留”(分钟)
    private static final String KEY_USE_CUSTOM_MODEL = "use_custom_model"; // 是否启用导入的模型包
    private static final String KEY_PET_MODE = "pet_mode";                 // builtin / image / anim / model / live2d
    private static final String KEY_USE_LIVE2D = "use_live2d";            // 是否启用分层伪Live2D形象
    private static final String KEY_USE_CUSTOM_L2D = "use_custom_l2d";     // 是否启用导入的 Live2D 模型包
    private static final String KEY_USE_BUILTIN_PACK = "use_builtin_pack"; // 是否启用内置素材包（webp 动画）



    // ———— 回复连发（把一条回复拆成多条短消息，更像真人发消息）————
    private static final String KEY_SPLIT_REPLY = "split_reply";
    private static final String KEY_SPLIT_MAX = "split_max";
    private static final String KEY_SPLIT_DELAY = "split_delay_ms";


    public static final int SPLIT_MAX_MIN = 2;
    public static final int SPLIT_MAX_MAX = 5;
    public static final int SPLIT_DELAY_MIN = 300;
    public static final int SPLIT_DELAY_MAX = 5000;

    // ———— 性能优化（功耗 / 发热）————
    //
    // 背景：部分用户反馈"占用高、发热高"。桌宠是**常驻悬浮窗**，
    // 一旦导入模型包或 Live2D，就会以约 60fps 持续重绘 —— 而它
    // **全天都在跑**，所以哪怕每帧很便宜，累积起来也会明显发热。
    //
    // 三档与"自动调节"的分工：
    //   - 档位决定**基础帧率上限**（高性能/均衡/省电）
    //   - 自动调节在此之上再按"熄屏 / 被遮挡 / 长时间无交互"临时降频
    // 这样用户既有一个可预期的固定档，也能享受无感的自动优化。

    private static final String KEY_PERF_MODE = "perf_mode";
    private static final String KEY_PERF_AUTO = "perf_auto";

    /** 高性能：不限帧（维持原有 60fps 手感，适合高端机或演示） */
    public static final int PERF_HIGH = 0;
    /** 均衡（默认）：30fps —— 动作仍流畅，发热明显下降 */
    public static final int PERF_BALANCED = 1;
    /** 省电：15fps + 熄屏/遮挡时完全停绘 */
    public static final int PERF_SAVER = 2;

    /**
     * 性能档位，默认 {@link #PERF_BALANCED}。
     *
     * <p><b>为什么默认是「均衡」而不是「高性能」：</b>
     * 用户反馈的痛点是"占用高、发热高"，默认值必须直接缓解它 ——
     * 否则绝大多数人不会主动去设置里改。30fps 对这类小尺寸桌宠动画
     * 已经足够流畅（它不是游戏），而重绘次数直接减半。
     * 想要 60fps 手感的人可以在设置里选「高性能」。
     */
    public static int perfMode(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_PERF_MODE, PERF_BALANCED);
        if (v < PERF_HIGH || v > PERF_SAVER) v = PERF_BALANCED;
        return v;
    }

    public static void setPerfMode(Context ctx, int v) {
        if (v < PERF_HIGH || v > PERF_SAVER) v = PERF_BALANCED;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_PERF_MODE, v).apply();
    }

    /** 是否开启自动调节（熄屏/遮挡/空闲时自动降耗），默认开 */
    public static boolean perfAuto(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_PERF_AUTO, true);
    }

    public static void setPerfAuto(Context ctx, boolean v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_PERF_AUTO, v).apply();
    }

    /**
     * 该档位对应的**最小重绘间隔**（毫秒）；0 表示不限帧。
     *
     * <p>给 {@code PetView} 用：它按"经过时间取模"算当前帧号，
     * 所以只要限制重绘频率，实际播放速度**不会变慢** ——
     * 只是采样没那么密（30fps 下每帧显示两次）。
     */
    public static int perfFrameDelayMs(Context ctx) {
        switch (perfMode(ctx)) {
            case PERF_HIGH: return 0;
            case PERF_SAVER: return 66;      // ≈15fps
            default: return 33;              // ≈30fps
        }
    }

    /** 回复连发：默认关闭（关闭时行为与旧版完全一致） */
    public static boolean splitReply(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_SPLIT_REPLY, false);
    }

    public static void setSplitReply(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_SPLIT_REPLY, on).apply();
    }

    /** 一次最多拆几条 */
    public static int splitMax(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_SPLIT_MAX, 3);
        if (v < SPLIT_MAX_MIN) v = SPLIT_MAX_MIN;
        if (v > SPLIT_MAX_MAX) v = SPLIT_MAX_MAX;
        return v;
    }

    public static void setSplitMax(Context ctx, int v) {
        if (v < SPLIT_MAX_MIN) v = SPLIT_MAX_MIN;
        if (v > SPLIT_MAX_MAX) v = SPLIT_MAX_MAX;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_SPLIT_MAX, v).apply();
    }

    /** 逐条之间的基础间隔（毫秒，实际会加随机抖动） */
    public static int splitDelayMs(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_SPLIT_DELAY, 900);
        if (v < SPLIT_DELAY_MIN) v = SPLIT_DELAY_MIN;
        if (v > SPLIT_DELAY_MAX) v = SPLIT_DELAY_MAX;
        return v;
    }

    public static void setSplitDelayMs(Context ctx, int v) {
        if (v < SPLIT_DELAY_MIN) v = SPLIT_DELAY_MIN;
        if (v > SPLIT_DELAY_MAX) v = SPLIT_DELAY_MAX;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_SPLIT_DELAY, v).apply();
    }

    // ==================== 桌宠名字（轮次81：主页可自定义）====================

    /** 默认名字（与首页原硬编码一致，改这里会同时改默认人设里的自称） */
    public static final String DEFAULT_PET_NAME = "小深";

    private static final String KEY_PET_NAME = "pet_name";
    /** 名字长度上限（太长的名字在首页/气泡里会撑破布局） */
    public static final int PET_NAME_MAX = 12;

    /**
     * 清洗用户输入的名字：去首尾空白、压掉换行与控制字符、限长。
     *
     * <p>★ 空/全空白 ⇒ 回落默认名 —— 与工程里"空值不许变成空字符串"的一贯口径一致
     *   （空名字会让首页那行变空白、AI 自称也不见了）。
     */
    public static String cleanPetName(String raw) {
        if (raw == null) return DEFAULT_PET_NAME;
        String s = raw.replace("\r", " ").replace("\n", " ").replace("\t", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < PET_NAME_MAX; i++) {
            char c = s.charAt(i);
            if (c >= 32 && c != 127) sb.append(c);
        }
        String out = sb.toString().trim();
        return out.length() == 0 ? DEFAULT_PET_NAME : out;
    }

    /**
     * 当前名字（永远非空）。
     *
     * <p>★ 轮次85：改成**按角色**存 —— 每个角色一个名字，换角色就换名字；
     *   该角色没设过就回落到全局名（{@link #KEY_PET_NAME}，也就是最后一次设的名字），
     *   再没有就用 {@link #DEFAULT_PET_NAME}。这样老用户的设置不会丢，
     *   新角色也能自然继承一个顺手的名字。
     */
    public static String petName(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String cid = currentCharIdSafe(ctx);
        if (cid != null) {
            String per = sp.getString(KEY_PET_NAME + "_" + cid, null);
            if (per != null && per.trim().length() > 0) return cleanPetName(per);
        }
        return cleanPetName(sp.getString(KEY_PET_NAME, DEFAULT_PET_NAME));
    }

    /** 给「当前角色」设名字（同时记一份全局默认，供还没设过的角色继承） */
    public static void setPetName(Context ctx, String name) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String clean = cleanPetName(name);
        SharedPreferences.Editor e = sp.edit().putString(KEY_PET_NAME, clean);
        String cid = currentCharIdSafe(ctx);
        if (cid != null) e.putString(KEY_PET_NAME + "_" + cid, clean);
        e.apply();
    }

    /** 取当前角色 id（拿不到就 null，绝不抛） */
    private static String currentCharIdSafe(Context ctx) {
        try {
            String cid = com.deepseek.pet.CharacterStore.currentId(ctx);
            return (cid == null || cid.length() == 0) ? null : cid;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把人设文本里**默认名**换成本次自定义的名字。
     *
     * <p>★ 只替换 {@link #DEFAULT_PET_NAME}（「小深」），不动别的字：
     *   用户自己写的人设/预设里若没写小深就一个字都不改；名字＝默认时直接原样返回。
     *   这样"改名"与"改人设"互不干扰。
     */
    public static String applyPetNameToPersona(Context ctx, String persona) {
        if (persona == null) return null;
        String me = petName(ctx);
        if (me.equals(DEFAULT_PET_NAME)) return persona;
        return persona.replace(DEFAULT_PET_NAME, me);
    }

    /** 默认性格：一只傲娇又贴心的电子桌宠 */
    public static final String DEFAULT_PERSONA =
            "你是「小深」，一只住在手机屏幕上的电子桌宠。性格：傲娇、毒舌但心里很关心主人，"
          + "说话短促活泼，偶尔用可爱的语气词（如\"哼\"\"才、才不是呢\"），每次回复控制在 60 字以内，"
          + "不要长篇大论，不要用 Markdown 分点，像微信聊天一样自然。";

    /** 请求 MainActivity 打开选图（由悬浮菜单触发） */
    public static final String ACTION_PICK_IMAGE = "com.deepseek.pet.PICK_IMAGE";

    /** 请求 MainActivity 打开“导入模型”（由悬浮菜单触发） */
    public static final String ACTION_IMPORT_MODEL = "com.deepseek.pet.IMPORT_MODEL";

    /** 请求桌宠用气泡回答问题：extra "q" 为问题文本 */
    public static final String ACTION_ASK = "com.deepseek.pet.ASK";

    /** 内置性格预设（首次运行时初始化） */
    public static String[][] builtinPresets() {
        return new String[][]{
            {"傲娇小深",
             "你是「小深」，一只住在手机屏幕上的电子桌宠。性格：傲娇、毒舌但心里很关心主人，"
           + "说话短促活泼，偶尔用可爱的语气词（如\"哼\"\"才、才不是呢\"），每次回复控制在 60 字以内，"
           + "不要长篇大论，不要用 Markdown 分点，像微信聊天一样自然。"},
            {"温柔贴心",
             "你是「小暖」，一只温柔贴心的电子桌宠。性格：温柔、耐心、善解人意，"
           + "总是用温暖鼓励的语气回应主人，会在意主人的心情。回复简洁自然，控制在 60 字以内，"
           + "可适当用可爱的语气词，像朋友聊天一样亲切。"},
            {"毒舌吐槽",
             "你是「小刺」，一只毒舌爱吐槽的电子桌宠。性格：犀利、爱开玩笑、嘴上不饶人但没恶意，"
           + "喜欢调侃主人。说话幽默简短，控制在 60 字以内，不用 Markdown，像损友聊天一样。"},
        };
    }

    // ==================== 形象素材路径（P5：**按角色**）====================
    //
    // ★ P5 之前这四个路径是**全局**的（filesDir/custom_pet.png 等），所以"切了角色、
    //   形象不跟着走"。现在统一由 AppearanceStore 按**当前角色**算：
    //   filesDir/appearance/<角色 id>/{image.png, anim.gif, model/, l2d/}。
    //
    // ★ 为什么这里保留这几个**旧名字**：全工程 13 处调用点（导入 / 预览 / 运行期装配）
    //   都是"拿路径"，让它们自动变成按角色的，比在 13 处各写一遍角色逻辑安全得多
    //   —— 少写一处就是"某个入口还在用上一个角色的形象"。
    //
    // ★ 这四个是**写目标**（调用方直接 new FileOutputStream），所以它们会**建目录**；
    //   只读的判断走 useCustomImage/isCustomAnim/…（那些绝不建目录）。

    /** 自定义图片保存路径（当前角色） */
    public static File customImageFile(Context ctx) {
        return AppearanceStore.ensureImageCurrent(ctx);
    }

    /** 自定义动图（GIF/动态WebP）保存路径（当前角色） */
    public static File customAnimFile(Context ctx) {
        return AppearanceStore.ensureAnimCurrent(ctx);
    }

    /** 导入的模型包 zip 路径（临时文件，全局即可） */
    public static File customModelZip(Context ctx) {
        return new File(ctx.getFilesDir(), "custom_pet_model.zip");
    }

    /** 导入的模型包解压目录（当前角色） */
    public static File customModelDir(Context ctx) {
        return AppearanceStore.ensureModelDirCurrent(ctx);
    }

    /** 导入的 Live2D 模型包目录（解压后，当前角色） */
    public static File customL2DDir(Context ctx) {
        return AppearanceStore.ensureL2dDirCurrent(ctx);
    }

    // ===== 外部动画素材覆盖层（第三方自制模型导入）=====
    // 默认素材是 assets/webp/*.webp + pet_anim.json + anim_lines.json。
    // 这里提供同构的"外部文件接口"：放在 filesDir 下的同名文件优先级高于 assets，
    // 让第三方可以追加/覆盖单个动作，而不用整套替换角色。

    // ===== 旧素材层（已废弃，2026-10-07）=====
    //
    // 旧格式：filesDir/custom_webp/*.webp|gif + custom_pet_anim.json + custom_anim_lines.json，
    // 由「导入动画素材」界面写入、并在运行时与内置配置合并。
    // ★ 用户决定把动作系统独立成「角色包」（见 SceneRegistry / ActionSystem / PetCharacter），
    //   所以**运行时不再读取**这些文件；但**不主动删用户的文件**，只提供计数与清理入口
    //   （设置页「清理旧素材」）。新角色包格式的导入/导出在后续轮次接上。
    private static File legacyWebpDir(Context ctx) {
        return new File(ctx.getFilesDir(), "custom_webp");
    }

    /** 旧素材文件数（供「清理旧素材」显示） */
    public static int legacyAssetCount(Context ctx) {
        File[] fs = legacyWebpDir(ctx).listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            String nm = f.getName().toLowerCase();
            if (f.isFile() && (nm.endsWith(".webp") || nm.endsWith(".gif"))) n++;
        }
        return n;
    }

    /** 删掉旧素材目录与两份旧配置，返回删除的文件数 */
    public static int clearLegacyAssets(Context ctx) {
        int n = 0;
        File[] fs = legacyWebpDir(ctx).listFiles();
        if (fs != null) for (File f : fs) { if (f.isFile() && f.delete()) n++; }
        try { if (new File(ctx.getFilesDir(), "custom_pet_anim.json").delete()) n++; } catch (Exception ignore) {}
        try { if (new File(ctx.getFilesDir(), "custom_anim_lines.json").delete()) n++; } catch (Exception ignore) {}
        try { legacyWebpDir(ctx).delete(); } catch (Exception ignore) {}
        return n;
    }

    // ===== 上传图片 / GIF 作为桌宠形象（独立于素材覆盖层）=====
    /** 外部上传的单张图片 / 动图目录 */
    public static File uploadedImageDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "custom_upload");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ==================== 漫游（v7.0 恢复；用户要求默认开）====================
    //
    // 背景：`FloatingService.wanderEnabled` 原来是 `= false; // 漫游开关（已取消漫游）`，
    // 且**全工程没人置 true** —— 于是漫游、以及依赖它的 `roam`/`turn` 两个场景一起休眠。
    // 用户 2026-10-07 决定：**恢复漫游、默认开**，并给开关与三个默认行为
    // （到边缘自动贴边、避开屏幕顶部 1/3、速度）。
    private static final String KEY_WANDER = "wander_on";
    private static final String KEY_WANDER_AVOID_TOP = "wander_avoid_top";
    private static final String KEY_WANDER_AUTODOCK = "wander_autodock";
    private static final String KEY_WANDER_SPEED = "wander_speed";     // 0 慢 / 1 标准 / 2 快
    private static final String KEY_WANDER_LEAD = "wander_lead_ms";    // 前摇（ms）
    private static final String KEY_WANDER_TAIL = "wander_tail_ms";    // 后摇（ms）

    /** 自动漫游（默认**开**） */
    public static boolean wanderEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_WANDER, true);
    }
    public static void setWanderEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_WANDER, on).apply();
    }

    /**
     * 漫游时避开屏幕顶部 1/3 —— ★★ 轮次70：**默认改成关**（原来是 2026-10-07 选的默认开）。
     *
     * <p>依据（用户 2026-10-16 原话，逐字）：「<b>漫游的时候，无论主动还是被动，都会强制位移到
     * 中间然后开始漫游，更改一下漫游逻辑在任何位置都可以漫游</b>」；追问下确认：
     * 「<b>就上下跑到中间了，漫游就在桌宠当前位置开始漫游就行了</b>」。
     * ⇒ 那条"先把桌宠从顶部 1/3 滑下来"的前置位移就是用户看到的"凭空移动到中间"
     * （1080×2400 机 ⇒ y 从 &lt;800 滑到 800 = 竖直 1/3 处），与"漫游就在当前位置开始"直接冲突
     * ⇒ **默认关**。
     * ★ 开关本身**保留**（不是删功能）：想让它先挪一下的用户自己打开，
     * 设置页那一行的文案已写明"会先动一下"。
     */
    public static boolean wanderAvoidTop(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_WANDER_AVOID_TOP, false);
    }
    public static void setWanderAvoidTop(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_WANDER_AVOID_TOP, on).apply();
    }

    /** 漫游走到屏幕边缘时自动贴边吸附（默认开） */
    public static boolean wanderAutoDock(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_WANDER_AUTODOCK, true);
    }
    public static void setWanderAutoDock(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_WANDER_AUTODOCK, on).apply();
    }

    /** 漫游速度档：0 慢 / 1 标准 / 2 快（默认 1） */
    public static int wanderSpeed(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_WANDER_SPEED, 1);
    }
    public static void setWanderSpeed(Context ctx, int v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_WANDER_SPEED, Math.max(0, Math.min(2, v))).apply();
    }

    /**
     * 漫游**前摇**（ms，默认 1000）：动画先播这么久，桌宠**才开始移动**。
     *
     * <p>★ 轮次㊻（用户原话：「**动画播放逻辑不对 —— 在漫游动作里面加一个漫游开始的延迟
     * 还有结束的延迟，用来控制动画前后摇的逻辑**」）：漫游动作的头尾各有一段
     * "原地蓄力 / 收尾"（素材里实测：原地左转奔跑前 ~2 秒内容几乎不动、末 ~1.5 秒也是），
     * 而旧逻辑是"开播即走、走到即停" ⇒ 位移与画面里的迈步完全对不上：
     * 动画还在蓄力时人就窜出去了，动画还在收尾时人已经站住不动了。
     *
     * <p>0 = 关掉前摇（立刻走）。
     */
    public static int wanderLeadMs(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return Math.max(0, Math.min(4000, sp.getInt(KEY_WANDER_LEAD, 1000)));
    }
    public static void setWanderLeadMs(Context ctx, int v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_WANDER_LEAD, Math.max(0, Math.min(4000, v))).apply();
    }

    /**
     * 漫游**后摇**（ms，默认 1000）：人已经停住，动画**再播这么久**才回待机。
     *
     * <p>★ 与 {@link #wanderLeadMs} 是一对（前摇/后摇），语义见那里的说明。
     * 0 = 走到就回待机（旧行为）。
     */
    public static int wanderTailMs(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return Math.max(0, Math.min(4000, sp.getInt(KEY_WANDER_TAIL, 1000)));
    }
    public static void setWanderTailMs(Context ctx, int v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_WANDER_TAIL, Math.max(0, Math.min(4000, v))).apply();
    }

    public static void saveApiKey(Context ctx, String apiKey) {
        // ★ 按**当前服务商**分别保存。
        //   为什么要分开：不同服务商的 Key 互不通用（Kimi 的 .ai 与 .com
        //   甚至都不通用），共用一个槽位的话，用户每换一家就要重新粘贴一次，
        //   而且换回去时旧 Key 已经被覆盖、只能重填。
        //   分开存之后，切换服务商 = 自动切到那家已保存的 Key。
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit()
          .putString(apiKeyKey(ctx), apiKey == null ? "" : apiKey.trim())
          // ★ 标记"已经用上了分槽存储"。见 getApiKey 里停止回退的条件。
          .putBoolean(KEY_API_MIGRATED, true)
          .apply();
    }

    /** 是否已进入"按服务商分槽"的存储模式（用于终止旧槽位回退） */
    private static final String KEY_API_MIGRATED = "api_key_migrated";

    /** 某服务商的 Key 存储键 */
    private static String apiKeyKey(Context ctx) {
        String pid = getChatProvider(ctx);
        if (pid == null || pid.length() == 0) pid = "deepseek";
        return KEY_API + "_" + pid;
    }

    /**
     * 取**当前服务商**的 Key。
     *
     * <p>所有调用点（对话、余额、语音、记忆点提炼……约 40 处）都走这里，
     * 所以只要这个函数返回"当前服务商的 Key"，全局就自动正确 ——
     * 不需要逐个改调用方。这是刻意的设计：改动面越小越不容易漏。
     *
     * <p><b>旧数据迁移</b>：升级前只有一个 {@code api_key} 槽位。
     * 若该服务商还没有自己的 Key，就回退到旧槽位的值，
     * 这样老用户**不用重新填 Key**（否则升级后桌宠会突然"没 Key 了"）。
     * 只在读取时回退、不写回，避免把旧 Key 复制到每个服务商名下。
     *
     * <p><b>★ 回退必须是"一次性的"</b>：一旦用户在本版本里保存过任何 Key
     * （{@code api_key_migrated} = true），就不再回退。
     *
     * <p>为什么必须终止：老槽位里存的是**某一家**的 Key（通常是 DeepSeek）。
     * 若长期回退，用户切到 Kimi 但还没填 Key 时，会**拿着 DeepSeek 的 Key
     * 去请求 Kimi** —— 报 401，而用户完全看不出"用的是别家的 Key"
     * （设置界面显示的是"未保存"，与实际行为矛盾）。
     * 迁移只需要发生一次，之后"没填就是没填"，行为才可预期。
     */
    public static String getApiKey(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(apiKeyKey(ctx), "");
        if (v != null && v.length() > 0) return v;
        // 已迁移过 ⇒ 不再回退（"这家没填"就该是空，而不是借别家的）
        if (sp.getBoolean(KEY_API_MIGRATED, false)) return "";
        // 兼容旧版单槽位（仅迁移前生效）
        return sp.getString(KEY_API, "");
    }

    public static boolean hasApiKey(Context ctx) {
        String k = getApiKey(ctx);
        return k != null && k.length() > 0;
    }

    /**
     * 某服务商是否已单独保存过 Key（不含旧槽位回退）。
     * 用于设置界面显示"已保存 / 未保存"，避免把回退值误显示成"这家存过"。
     */
    public static boolean hasApiKeyFor(Context ctx, String providerId) {
        if (providerId == null || providerId.length() == 0) return false;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_API + "_" + providerId, "");
        return v != null && v.length() > 0;
    }

    /** 清除某服务商的 Key（用户主动删除时用） */
    public static void clearApiKeyFor(Context ctx, String providerId) {
        if (providerId == null || providerId.length() == 0) return;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_API + "_" + providerId).apply();
    }

    /** 自定义 API 基地址（默认 DeepSeek 官方）。非 DeepSeek 网关请填其兼容 OpenAI 的 base，如 https://xxx/v1 */
    private static final String KEY_BASE_URL = "api_base_url";
    public static String getBaseUrl(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_BASE_URL, "https://api.deepseek.com");
        if (v == null || v.trim().length() == 0) v = "https://api.deepseek.com";
        return v.trim();
    }
    public static void saveBaseUrl(Context ctx, String url) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_BASE_URL, url == null ? "" : url.trim()).apply();
    }

    // ———— 对话服务商（决定发哪些参数，见 ChatProvider）————

    private static final String KEY_CHAT_PROVIDER = "chat_provider";
    private static final String KEY_CHAT_FORCE_PARAMS = "chat_force_params";

    /**
     * 当前对话服务商 id（deepseek / kimi / siliconflow / ... / custom）。
     *
     * <p>没存过时按 base URL 反推 —— 这样升级到这个版本的老用户
     * （已经手填过 Kimi 地址的）**不用重新设置**就能自动修好 400。
     */
    public static String getChatProvider(Context ctx) {
        if (ctx == null) return "deepseek";
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_CHAT_PROVIDER, "");
        if (v != null && v.trim().length() > 0) {
            // ★★★ 轮次75：本地模型入口下线期间，历史选择过 local 的用户**读取时**映射回在线
            //     （★ 绝**不**改写磁盘上的值 —— 恢复功能时他原来的选择原样回来）。
            return LocalModelFeature.resolveProvider(v.trim());
        }
        return ChatProvider.detectId(getBaseUrl(ctx));
    }

    public static void setChatProvider(Context ctx, String id) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_CHAT_PROVIDER, id == null ? "" : id.trim()).apply();
    }

    /**
     * "自定义"服务商下是否强制发送温度等参数。
     *
     * <p>只为自定义地址提供：未知服务的参数约束无从得知，默认**不发**
     * （见 ChatProvider 类注释）。但有些自建网关其实完全支持这些参数，
     * 这时用户可以手动打开 —— 属于"明知风险的自愿行为"。
     * 预置服务商一律按能力表，不给这个开关，避免用户把自己弄 400。
     */
    public static boolean chatForceParams(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_CHAT_FORCE_PARAMS, false);
    }

    public static void setChatForceParams(Context ctx, boolean v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_CHAT_FORCE_PARAMS, v).apply();
    }

    /** 桌宠缩放倍数，默认 1.0（范围 0.3 ~ 1.3） */
    public static final float SCALE_MIN = 0.3f;
    public static final float SCALE_MAX = 1.3f;

    public static float getScale(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_SCALE, 1.0f);
        if (v < SCALE_MIN) v = SCALE_MIN;
        if (v > SCALE_MAX) v = SCALE_MAX;
        return v;
    }

    public static void saveScale(Context ctx, float scale) {
        if (scale < SCALE_MIN) scale = SCALE_MIN;
        if (scale > SCALE_MAX) scale = SCALE_MAX;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_SCALE, scale).apply();
    }

    // ==================== 视频背景（App 自身界面） ====================
    //
    // 注意：这是**设置页自己的背景**，不是桌宠悬浮窗的背景。
    // 之前那套「自定义桌面背景」（铺在桌宠下面、跟随桌宠移动）已被用户明确
    // 撤除，不要跟这套混起来 —— 那套的痕迹已全部删除。

    private static final String KEY_VIDEO_BG = "app_video_bg";
    private static final String KEY_VIDEO_BG_DIM = "app_video_bg_dim";
    private static final String KEY_VIDEO_BG_SOUND = "app_video_bg_sound";
    private static final String KEY_VIDEO_BG_FIT = "app_video_bg_fit";
    private static final String KEY_VIDEO_BG_CROP = "app_video_bg_crop";

    /** 视频背景文件：拷进 filesDir，不能只记 Uri（相册授权是一次性的） */
    public static File videoBgFile(Context ctx) {
        return new File(ctx.getFilesDir(), "app_bg_video.mp4");
    }

    /** 视频背景是否启用（文件不存在即视为关闭，避免"开关开着但没画面"） */
    public static boolean videoBgEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_VIDEO_BG, false) && videoBgFile(ctx).exists();
    }

    public static void setVideoBgEnabled(Context ctx, boolean v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_VIDEO_BG, v).apply();
    }

    /**
     * 压暗遮罩强度 0 ~ 0.8，默认 0.45。
     *
     * <p>为什么默认不是 0：视频背景本身明暗不可控，而页面上的文字颜色是
     * 按「浅色底」调的。不压暗的话，遇到亮画面（雪景、白墙）正文会看不清。
     * 45% 是「视频还看得清 + 文字能读」的折中值。
     */
    public static float videoBgDim(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_VIDEO_BG_DIM, 0.45f);
        if (v < 0f) v = 0f;
        if (v > 0.8f) v = 0.8f;
        return v;
    }

    public static void setVideoBgDim(Context ctx, float v) {
        if (v < 0f) v = 0f;
        if (v > 0.8f) v = 0.8f;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_VIDEO_BG_DIM, v).apply();
    }

    /** 视频背景是否出声，默认静音 —— 背景视频出声会打断桌宠的语音朗读 */
    public static boolean videoBgSound(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_VIDEO_BG_SOUND, false);
    }

    public static void setVideoBgSound(Context ctx, boolean v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_VIDEO_BG_SOUND, v).apply();
    }

    // ==================== 图片背景（第二十一轮新增）====================
    //
    // ★ 用户要求「加入图片背景导入功能（现在只能导入视频）」。
    //
    // 为什么单独一套 pref 而不是和视频共用：
    //   两者可以**同时存在**（用户先选了视频，又选了图片），谁生效要能选。
    //   共用一个 "背景文件" 会导致"选图片就把视频覆盖没了"，用户想切回去
    //   还得重新选一次视频。分开存 + 一个"用哪个"的开关，两个资源都留着。
    //
    // 谁优先：图片优先于视频。
    //   理由：图片是**静态**的、对性能友好，而且用户刚导入图片时
    //   显然想看图片。视频是更重的资源，让它退居其次更符合预期。

    private static final String KEY_IMAGE_BG = "app_image_bg";
    private static final String KEY_IMAGE_BG_DIM = "app_image_bg_dim";
    private static final String KEY_IMAGE_BG_CROP = "app_image_bg_crop";
    /** 图片/视频同时存在时用哪个：true = 优先图片 */
    private static final String KEY_IMAGE_BG_PRIORITY = "app_image_bg_priority";

    /** 图片背景文件：拷进 filesDir，不能只记 Uri（相册授权是一次性的） */
    public static File imageBgFile(Context ctx) {
        return new File(ctx.getFilesDir(), "app_bg_image.img");
    }

    /** 图片背景是否启用（文件不存在即视为关闭） */
    public static boolean imageBgEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_IMAGE_BG, false) && imageBgFile(ctx).exists();
    }

    public static void setImageBgEnabled(Context ctx, boolean v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_IMAGE_BG, v).apply();
    }

    /** 图片背景压暗 0 ~ 0.8，默认 0.35（比视频略轻：图片静态、没有运动干扰） */
    public static float imageBgDim(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_IMAGE_BG_DIM, 0.35f);
        if (v < 0f) v = 0f;
        if (v > 0.8f) v = 0.8f;
        return v;
    }

    public static void setImageBgDim(Context ctx, float v) {
        if (v < 0f) v = 0f;
        if (v > 0.8f) v = 0.8f;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_IMAGE_BG_DIM, v).apply();
    }

    /** 图片裁剪框（归一化 0~1），null = 用屏幕比例的居中默认值 */
    public static float[] imageBgCrop(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_IMAGE_BG_CROP, null);
        if (s == null) return null;
        try {
            String[] p = s.split(",");
            if (p.length != 4) return null;
            float[] r = new float[4];
            for (int i = 0; i < 4; i++) r[i] = Float.parseFloat(p[i].trim());
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    public static void setImageBgCrop(Context ctx, float[] c) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        if (c == null || c.length != 4) {
            sp.edit().remove(KEY_IMAGE_BG_CROP).apply();
            return;
        }
        sp.edit().putString(KEY_IMAGE_BG_CROP,
                c[0] + "," + c[1] + "," + c[2] + "," + c[3]).apply();
    }

    /** 清掉图片裁剪框（Import 新图时调，避免把旧图的框套到新图上） */
    public static void clearImageBgCrop(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_IMAGE_BG_CROP).apply();
    }

    /** 图片是否优先于视频 */
    public static boolean imageBgPriority(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_IMAGE_BG_PRIORITY, true);
    }

    public static void setImageBgPriority(Context ctx, boolean v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_IMAGE_BG_PRIORITY, v).apply();
    }

    /**
     * 当前实际生效的是哪种背景。null = 都没启用（走 Mesh 渐变）。
     *
     * <p>把这个判断收在 Prefs 里、只写一份：渲染层与设置页都要用它，
     * 两处各写一遍必然漂移（"设置页说用图片、实际渲染用视频"）。
     *
     * <p>第二十二轮起设置页用**一个类型选择器**（无/图片/视频）取代了两个
     * 独立开关，所以正常路径下两者互斥。仍保留"两者都开"时的优先级判断，
     * 是为了兼容旧版本存下来的状态（那时确实可能两个都开着）。
     */
    public static String activeBgKind(Context ctx) {
        boolean img = imageBgEnabled(ctx);
        boolean vid = videoBgEnabled(ctx);
        if (img && vid) return imageBgPriority(ctx) ? "image" : "video";
        if (img) return "image";
        if (vid) return "video";
        return null;
    }

    /** 背景种类常量（供 setBgKind / activeBgKind 用） */
    public static final String BG_NONE = "none";
    public static final String BG_IMAGE = "image";
    public static final String BG_VIDEO = "video";

    /**
     * 原子地设置"用哪种背景"。
     *
     * <h3>★ 为什么必须由 Prefs 提供、而不是 UI 里分别调两个 setter</h3>
     * 之前设置页是两个独立开关，用户可以把两个都打开 ——
     * 于是渲染层只能靠一个含糊的"优先级"开关来裁决，用户看到的
     * 「开了没反应」正是这么来的（图片盖住了视频）。
     *
     * <p>合并成一个类型选择器后，语义变成**三选一**，天然互斥。
     * 但"互斥"这件事必须由**一处**保证：若 UI 里分别调
     * {@code setImageBgEnabled} 和 {@code setVideoBgEnabled}，
     * 中间任何一个分支漏写，就会出现"两个都开着"的脏状态。
     * 收在一个方法里，UI 只可能给出三种结果之一。
     *
     * <p><b>不删文件</b>：切换类型只是改"用哪个"，两个已导入的文件都保留 ——
     * 用户来回切换时不必重新导入，这正是合并后的主要好处。
     *
     * @param kind {@link #BG_NONE} / {@link #BG_IMAGE} / {@link #BG_VIDEO}
     */
    public static void setBgKind(Context ctx, String kind) {
        boolean img = BG_IMAGE.equals(kind);
        boolean vid = BG_VIDEO.equals(kind);
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putBoolean(KEY_IMAGE_BG, img)
                .putBoolean(KEY_VIDEO_BG, vid)
                .apply();
    }

    // ==================== 视频填充方式与裁剪 ====================

    /** 完整显示（等比缩小到装得下，边上留黑） */
    public static final int FIT_CONTAIN = 0;
    /** 铺满（等比放大到短边铺满、长边裁掉）—— 默认 */
    public static final int FIT_COVER = 1;

    /**
     * 填充方式，默认 {@link #FIT_COVER}（铺满）。
     *
     * <h3>★ 只有两种，且都绝不变形</h3>
     * 用户要求「裁剪视频不要做任何拉伸变形，只保留裁剪部分」，
     * 所以原先的 {@code FIT_STRETCH = 2}（两轴各自铺满 = 必然变形）
     * **已被彻底删除**。现在两种模式都只做**等比**缩放：
     * <ul>
     *   <li>{@code FIT_COVER}（默认）：填满屏幕、不变形，多出来的裁掉。</li>
     *   <li>{@code FIT_CONTAIN}：完整可见、不变形，边上留黑。</li>
     * </ul>
     *
     * <p><b>为什么默认是"铺满"：</b>用户明确要求
     * 「视频背景按照屏幕大小纵向裁切，不要横屏，截取视频中的一段就好，
     *   不用完整平铺」。横屏视频配竖屏手机若走 CONTAIN，上下会留下
     * **两条大黑边**，当背景很难看。
     * 所以默认按屏幕比例裁一条竖条（见 {@link VideoFitMath#defaultCrop}）
     * + 默认铺满 ⇒ 背景填满屏幕、不变形、无黑边。
     *
     * <p>★ 历史遗留的 {@code 2} 会落到 CONTAIN 分支（见函数体），
     * 即"退化成留黑边"，**绝不会退化成拉伸变形**。
     */
    public static int videoBgFit(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_VIDEO_BG_FIT, FIT_COVER);
        // 只接受 COVER / CONTAIN；其余（含历史遗留的 2）一律当成 CONTAIN ——
        // 不能把它们当 COVER，因为那会让"以前存过 2 的用户"看到画面被裁，
        // 而他本来选的是"两种都填满"的语义。
        return (v == FIT_COVER) ? FIT_COVER : FIT_CONTAIN;
    }

    public static void setVideoBgFit(Context ctx, int v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_VIDEO_BG_FIT, v).apply();
    }

    /**
     * 用户裁出来的画面（相对视频的**归一化**矩形）。
     *
     * <p>存成归一化的 {@code [left, top, right, bottom]}（0~1，相对视频原始宽高），
     * 而不是像素：视频分辨率各不相同，存像素就得连原始尺寸一起存、还得在
     * 每次读取时换算；归一化值直接乘上视频宽高即可，且换设备/换分辨率都成立。
     *
     * <p>返回 {@code null} 表示"没有裁过"（用整个画面）。
     * 校验：四值必须都在 0~1、且 left&lt;right、top&lt;bottom，
     * 否则视为无效返回 null —— 坏数据不该让背景变成一块空白。
     */
    public static float[] videoBgCrop(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_VIDEO_BG_CROP, null);
        if (s == null) return null;
        try {
            String[] parts = s.split(",");
            if (parts.length != 4) return null;
            float[] r = new float[4];
            for (int i = 0; i < 4; i++) {
                r[i] = Float.parseFloat(parts[i].trim());
                if (r[i] < 0f || r[i] > 1f) return null;
            }
            if (r[0] >= r[2] || r[1] >= r[3]) return null;
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setVideoBgCrop(Context ctx, float l, float t, float r, float b) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_VIDEO_BG_CROP,
                l + "," + t + "," + r + "," + b).apply();
    }

    public static void clearVideoBgCrop(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_VIDEO_BG_CROP).apply();
    }

    /** 删掉视频文件并关开关（"清除"按钮用） */
    public static void clearVideoBg(Context ctx) {
        try {
            File f = videoBgFile(ctx);
            if (f.exists()) f.delete();
        } catch (Exception ignore) {}
        setVideoBgEnabled(ctx, false);
        // 裁剪矩形是针对**那一段视频**的，视频没了就该一起清掉 ——
        // 否则下次导入的新视频会被套用旧片子的裁剪框（画面上会
        // 出现一块莫名其妙的偏移/缩放）。填充方式同理回到默认。
        clearVideoBgCrop(ctx);
        setVideoBgFit(ctx, FIT_COVER);
    }

    /** 桌宠透明度 0.2 ~ 1.0，默认 1.0 */
    public static float getAlpha(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float a = sp.getFloat(KEY_ALPHA, 1.0f);
        if (a < 0.2f) a = 0.2f;
        if (a > 1.0f) a = 1.0f;
        return a;
    }

    public static void saveAlpha(Context ctx, float a) {
        if (a < 0.2f) a = 0.2f;
        if (a > 1.0f) a = 1.0f;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_ALPHA, a).apply();
    }

    // ==================== 形象来源（P5：**按角色**）====================
    //
    // 语义从"这四个 pref 各是一个开关"变成"当前角色**正在用哪一种**来源"：
    // 判据全部落在 AppearanceStore（读当前角色的 appearance.json + 看素材在不在），
    // 那四个旧 pref 从此**只在迁移时读一次**（见 legacyPetMode / migrateLegacyAppearance）。
    //
    // ★ 于是老调用点的写法照旧能用，而且顺序不再重要：老代码里
    //   `setCustomAnim(true); setUseCustomImage(false);` 是"先开一个再把另一个关掉"，
    //   按新语义第二行是**空操作**（当前用的不是 image）—— 见 Appearance.afterDisable。

    /** 当前角色正在用「上传的静态图片」 */
    public static boolean useCustomImage(Context ctx) {
        return Appearance.IMAGE.equals(AppearanceStore.modeFor(ctx));
    }

    /** use=true 切到图片；use=false 只在**当前用的就是图片**时回内置 */
    public static void setUseCustomImage(Context ctx, boolean use) {
        if (use) AppearanceStore.setSourceFor(ctx, Appearance.IMAGE);
        else AppearanceStore.disableFor(ctx, Appearance.IMAGE);
    }

    /** 当前角色正在用「上传的动图（GIF / 动态 WebP）」 */
    public static boolean isCustomAnim(Context ctx) {
        return Appearance.ANIM.equals(AppearanceStore.modeFor(ctx));
    }

    public static void setCustomAnim(Context ctx, boolean anim) {
        if (anim) AppearanceStore.setSourceFor(ctx, Appearance.ANIM);
        else AppearanceStore.disableFor(ctx, Appearance.ANIM);
    }

    /** 当前角色正在用「序列帧模型包」 */
    public static boolean useCustomModel(Context ctx) {
        return Appearance.MODEL.equals(AppearanceStore.modeFor(ctx));
    }

    public static void setUseCustomModel(Context ctx, boolean use) {
        if (use) AppearanceStore.setSourceFor(ctx, Appearance.MODEL);
        else AppearanceStore.disableFor(ctx, Appearance.MODEL);
    }

    /**
     * 内置分层伪 Live2D 形象 —— <b>遗留死键</b>，保留读取只为不动旧 pref。
     * <p>★ 实测确认它从来没有生效过：{@code assets/live2d/} 这个目录在本工程里
     * <b>不存在</b>（{@code Live2DEngine.load} 要读 {@code live2d/meta.json}，必然失败），
     * 而且 {@code FloatingService.applyPetDrawable} 从没调过 {@code petImage.setLive2D(true)}。
     * 所以它**不是**形象来源的一项（P5 的清单里没有它），也不提供界面入口。
     */
    public static boolean useLive2D(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_USE_LIVE2D, false);
    }

    public static void setUseLive2D(Context ctx, boolean use) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_USE_LIVE2D, use).apply();
    }

    /** 当前角色正在用「导入的 Live2D 模型包」 */
    public static boolean useCustomL2D(Context ctx) {
        return Appearance.L2D.equals(AppearanceStore.modeFor(ctx));
    }

    public static void setUseCustomL2D(Context ctx, boolean use) {
        if (use) AppearanceStore.setSourceFor(ctx, Appearance.L2D);
        else AppearanceStore.disableFor(ctx, Appearance.L2D);
    }

    /** 当前角色是否在用**自定义**形象（内置角色包不算） */
    public static boolean hasCustomPet(Context ctx) {
        return Appearance.isCustom(AppearanceStore.modeFor(ctx));
    }

    /** 是否启用内置素材包（assets/webp 的 animated WebP 动画包） */
    public static boolean useBuiltinPack(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_USE_BUILTIN_PACK, true);
    }

    public static void setUseBuiltinPack(Context ctx, boolean use) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_USE_BUILTIN_PACK, use).apply();
    }

    // ==================== 动作系统：待机随机池 ====================
    //
    // 用户 2026-10-07 明确要求"取消所有待机动作、只保留默认那一个"。
    // 落地方式：待机只播 idle 场景（默认 1 个动作）；原来那 80 个随机动作进 pool 场景，
    // **默认不参与待机抽取**。想要"待机会换动作"时把这个开关打开即可（可逆）。
    private static final String KEY_ANIM_POOL = "anim_pool_on";

    /** 待机是否从随机池抽动作（默认**关** = 待机只播默认那一个） */
    public static boolean animPoolEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ANIM_POOL, false);
    }

    public static void setAnimPoolEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_ANIM_POOL, on).apply();
    }

    /**
     * 当前形象模式（= **当前角色**的形象来源）：
     *   "custom_l2d" 导入的 Live2D 模型包
     *   "model"      导入的序列帧模型包
     *   "anim"       GIF / 动态 WebP
     *   "image"      静态自定义图片
     *   "builtin"    内置角色包（assets/webp 的 106 个动画）
     *   "none"       连内置素材包也被关掉了（见 {@link #useBuiltinPack}）
     *
     * <p>★ 取值同时就是 {@link Appearance} 的来源 id（故意同名 —— 中间再翻译一层
     * 就是漂移的来源：加了新来源忘了改映射，表现成"选了没反应"）。
     * <p>★ 素材缺失时 {@link Appearance#resolve} 会**回退内置**（选过"我的图片"
     * 但文件被清理 ⇒ 回内置，而不是留一块空白），所以这里返回的已经是真能播的那个。
     */
    public static String petMode(Context ctx) {
        String mode = AppearanceStore.modeFor(ctx);
        if (Appearance.BUILTIN.equals(mode) && !useBuiltinPack(ctx)) return "none";
        return mode;
    }

    /**
     * **旧的**（P5 之前）形象模式口径 —— 只给 {@link #migrateLegacyAppearance} 用。
     * <p>升级时要把用户当时看到的形象原样搬进角色目录，就得按**旧规则**算一次：
     * 旧规则是四个全局开关 + 四个全局文件，优先级 l2d &gt; model &gt; anim &gt; image。
     */
    public static String legacyPetMode(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        File files = ctx.getFilesDir();
        if (sp.getBoolean(KEY_USE_CUSTOM_L2D, false)
                && new File(new File(files, "custom_l2d_model"), "meta.json").exists()) {
            return Appearance.L2D;
        }
        if (sp.getBoolean(KEY_USE_CUSTOM_MODEL, false)
                && new File(files, "custom_pet_model").isDirectory()) {
            return Appearance.MODEL;
        }
        if (sp.getBoolean(KEY_USE_CUSTOM_ANIM, false)
                && new File(files, "custom_pet_anim.gif").isFile()) {
            return Appearance.ANIM;
        }
        if (sp.getBoolean(KEY_USE_CUSTOM, false)
                && new File(files, "custom_pet.png").isFile()) {
            return Appearance.IMAGE;
        }
        return Appearance.BUILTIN;
    }

    /**
     * P5 升级：把**全局**的老形象搬进当前角色的形象目录（幂等，见
     * {@link AppearanceStore#migrateLegacyIn}）。
     * <p>搬完把四个旧开关清掉 —— 它们已经不再被读取，留着只会让下一个人以为还有效。
     *
     * @return 实际搬了几样素材（0 = 没有老形象 / 用户本来就在用内置）
     */
    public static int migrateLegacyAppearance(Context ctx) {
        int n = AppearanceStore.migrateLegacyIn(ctx.getFilesDir(),
                CharacterStore.currentId(ctx), legacyPetMode(ctx));
        if (n > 0) {
            SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
            sp.edit().putBoolean(KEY_USE_CUSTOM, false)
                    .putBoolean(KEY_USE_CUSTOM_ANIM, false)
                    .putBoolean(KEY_USE_CUSTOM_MODEL, false)
                    .putBoolean(KEY_USE_CUSTOM_L2D, false)
                    .apply();
        }
        return n;
    }

    /** 上传图片是否等比缩放（true=完整显示不变形，false=拉伸铺满） */
    public static boolean keepRatio(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_KEEP_RATIO, true);
    }

    public static void setKeepRatio(Context ctx, boolean keep) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_KEEP_RATIO, keep).apply();
    }

    /** 是否显示余额气泡（默认显示）—— ★ 这是"这一刻显不显示"，用户单击桌宠会改它。 */
    public static boolean showBalance(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_SHOW_BALANCE, true);
    }

    public static void setShowBalance(Context ctx, boolean show) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_SHOW_BALANCE, show).apply();
    }

    /**
     * ★★★ 轮次76：余额气泡的**总开关**（用户原话：「增加气泡显示的开关，可以关闭，
     * 不显示余额气泡」）。
     *
     * <p>与 {@link #showBalance} 的区别：
     * <ul>
     *   <li>{@code showBalance} = 此刻显不显示（单击桌宠会翻转它）；</li>
     *   <li>{@code balanceBubbleOn} = **功能开不开**：关掉之后气泡不再出现、
     *       单击不再切换、**余额轮询也停掉**（那是一串真实网络请求）。</li>
     * </ul>
     * ★ 默认 {@code true}（与这一版之前的行为一致：什么都没动的人零影响）。
     */
    public static boolean balanceBubbleOn(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_BALANCE_BUBBLE_ON, true);
    }

    public static void setBalanceBubbleOn(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_BALANCE_BUBBLE_ON, on).apply();
    }


    //
    // 桌宠是常驻悬浮窗，会盖在**本 App 自己的设置页**上面。实测：设置首页的
    // 「外观与显示 / 对话与人格 / 记忆与省 TOKEN」三行和两个气泡被桌宠压住，
    // 根本点不到。这不是设计取舍，是挡了自己的路。
    //
    // 默认开 = 进 App 时把桌宠临时藏起来，退出 App 自动恢复。
    // 之所以做成开关而不是写死：有人就喜欢看着它蹲在设置页角落，不该被剥夺。
    // 注意这里**不停止服务**，只改可见性 —— 停止服务会丢状态（心情/漫游位置）。

    private static final String KEY_HIDE_IN_APP = "hide_pet_in_app";

    /** 打开本 App 时是否隐藏桌宠（默认隐藏） */
    public static boolean hidePetInApp(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_HIDE_IN_APP, true);
    }

    public static void setHidePetInApp(Context ctx, boolean hide) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_HIDE_IN_APP, hide).apply();
    }

    /** ★ 第七版：**工作模式里也显示桌宠**（默认 true）。
     *  这与 {@link #hidePetInApp} 分开：那个管"在整个 App 里藏起来"，
     *  这个管"工作模式这一页要不要把它拉出来"。 */
    // ==================== 工作模式（DSH）自己的设置（独立一页） ====================
    //
    // ★ 为什么要单独放：桌面端 DeepSeek Harness 的设置是**一个独立面板**
    //   （通用设置 / 模型 / 插件 /…），不跟"陪伴"那套混在一起。

    /** 工作模式正文字号（px，对应桌面端"字号大小 14 px"） */
    private static final String KEY_DSH_FONT_PX = "dsh_font_px";

    public static int dshFontPx(Context ctx) {
        int v = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_DSH_FONT_PX, 14);
        return (v < 12 || v > 22) ? 14 : v;
    }

    public static void setDshFontPx(Context ctx, int px) {
        int v = Math.max(12, Math.min(22, px));
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DSH_FONT_PX, v).apply();
    }

    /**
     * 繁忙时按发送键的行为（对应桌面端"繁忙时的发送键行为：排队发送"）。
     * <p>插话打断本轮**未支持**：选了也会退回排队并明说，不假装能拦。
     */
    public static final int DSH_BUSY_QUEUE = 0;
    public static final int DSH_BUSY_INTERRUPT = 1;
    private static final String KEY_DSH_BUSY_SEND = "dsh_busy_send";

    public static int dshBusySend(Context ctx) {
        int v = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_DSH_BUSY_SEND, DSH_BUSY_QUEUE);
        return v == DSH_BUSY_INTERRUPT ? DSH_BUSY_INTERRUPT : DSH_BUSY_QUEUE;
    }

    public static void setDshBusySend(Context ctx, int v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DSH_BUSY_SEND, v == DSH_BUSY_INTERRUPT ? DSH_BUSY_INTERRUPT : DSH_BUSY_QUEUE)
                .apply();
    }

    private static final String KEY_HARNESS_SHOW_PET = "harness_show_pet";

    public static boolean harnessShowPet(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_HARNESS_SHOW_PET, true);
    }

    public static void setHarnessShowPet(Context ctx, boolean on) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HARNESS_SHOW_PET, on).apply();
    }

    // ———— 气泡外观：余额气泡 / 对话气泡 大小 ————
    private static final String KEY_BALANCE_SCALE = "balance_scale";     // 余额气泡字号缩放
    private static final String KEY_BUBBLE_SCALE = "bubble_scale";       // 对话气泡字号缩放
    private static final String KEY_BUBBLE_DUR_SCALE = "bubble_dur_scale"; // 对话气泡时长倍数
    // ———— 展开菜单（动作气泡）布局微调 ————
    private static final String KEY_MENU_CONTENT_SCALE = "menu_content_scale"; // 菜单内容缩放
    private static final String KEY_PANEL_SCALE = "menu_panel_scale";          // 背景底板缩放
    private static final String KEY_MENU_INSET = "menu_inset_ratio";           // 菜单相对背景内边距比例
    private static final String KEY_MENU_OFFSET_X = "menu_offset_x";           // 水平偏移(dp)
    private static final String KEY_MENU_OFFSET_Y = "menu_offset_y";           // 垂直偏移(dp)

    public static final float BUBBLE_SCALE_MIN = 0.5f;
    public static final float BUBBLE_SCALE_MAX = 2.5f;
    public static final float BUBBLE_DUR_MIN = 0.5f;
    public static final float BUBBLE_DUR_MAX = 3.0f;

    public static float getBalanceScale(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_BALANCE_SCALE, 1.0f);
        if (v < BUBBLE_SCALE_MIN) v = BUBBLE_SCALE_MIN;
        if (v > BUBBLE_SCALE_MAX) v = BUBBLE_SCALE_MAX;
        return v;
    }

    public static void saveBalanceScale(Context ctx, float v) {
        if (v < BUBBLE_SCALE_MIN) v = BUBBLE_SCALE_MIN;
        if (v > BUBBLE_SCALE_MAX) v = BUBBLE_SCALE_MAX;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_BALANCE_SCALE, v).apply();
    }

    public static float getBubbleScale(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_BUBBLE_SCALE, 1.0f);
        if (v < BUBBLE_SCALE_MIN) v = BUBBLE_SCALE_MIN;
        if (v > BUBBLE_SCALE_MAX) v = BUBBLE_SCALE_MAX;
        return v;
    }

    public static void saveBubbleScale(Context ctx, float v) {
        if (v < BUBBLE_SCALE_MIN) v = BUBBLE_SCALE_MIN;
        if (v > BUBBLE_SCALE_MAX) v = BUBBLE_SCALE_MAX;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_BUBBLE_SCALE, v).apply();
    }

    public static float getBubbleDurScale(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_BUBBLE_DUR_SCALE, 1.0f);
        if (v < BUBBLE_DUR_MIN) v = BUBBLE_DUR_MIN;
        if (v > BUBBLE_DUR_MAX) v = BUBBLE_DUR_MAX;
        return v;
    }

    public static void saveBubbleDurScale(Context ctx, float v) {
        if (v < BUBBLE_DUR_MIN) v = BUBBLE_DUR_MIN;
        if (v > BUBBLE_DUR_MAX) v = BUBBLE_DUR_MAX;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_BUBBLE_DUR_SCALE, v).apply();
    }

    // ======== 展开菜单（动作气泡）布局微调参数 ========
    public static final float MENU_CONTENT_MIN = 0.5f;
    public static final float MENU_CONTENT_MAX = 16.0f;
    public static final float MENU_PANEL_MIN = 0.03f;
    public static final float MENU_PANEL_MAX = 4.0f;
    public static final float MENU_INSET_MIN = 0.0f;
    public static final float MENU_INSET_MAX = 0.3f;
    public static final int   MENU_OFFSET_MIN = -400;
    public static final int   MENU_OFFSET_MAX = 400;

    /** 菜单内容缩放（越大文字/项越大） */
    public static float getMenuContentScale(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_MENU_CONTENT_SCALE, 4.0f);
        return clampF(v, MENU_CONTENT_MIN, MENU_CONTENT_MAX);
    }
    public static void saveMenuContentScale(Context ctx, float v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_MENU_CONTENT_SCALE, clampF(v, MENU_CONTENT_MIN, MENU_CONTENT_MAX)).apply();
    }

    /** 背景底板缩放（气泡整体大小） */
    public static float getMenuPanelScale(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_PANEL_SCALE, 0.125f);
        return clampF(v, MENU_PANEL_MIN, MENU_PANEL_MAX);
    }
    public static void saveMenuPanelScale(Context ctx, float v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_PANEL_SCALE, clampF(v, MENU_PANEL_MIN, MENU_PANEL_MAX)).apply();
    }

    /** 菜单相对背景的边距比例（上下各约该值，左右对称） */
    public static float getMenuInsetRatio(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_MENU_INSET, 0.05f);
        return clampF(v, MENU_INSET_MIN, MENU_INSET_MAX);
    }
    public static void saveMenuInsetRatio(Context ctx, float v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_MENU_INSET, clampF(v, MENU_INSET_MIN, MENU_INSET_MAX)).apply();
    }

    /** 菜单相对默认位置的水平偏移(dp) */
    public static int getMenuOffsetX(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return clampI(sp.getInt(KEY_MENU_OFFSET_X, 0), MENU_OFFSET_MIN, MENU_OFFSET_MAX);
    }
    public static void saveMenuOffsetX(Context ctx, int v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MENU_OFFSET_X, clampI(v, MENU_OFFSET_MIN, MENU_OFFSET_MAX)).apply();
    }

    /** 菜单相对默认位置的垂直偏移(dp) */
    public static int getMenuOffsetY(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return clampI(sp.getInt(KEY_MENU_OFFSET_Y, 0), MENU_OFFSET_MIN, MENU_OFFSET_MAX);
    }
    public static void saveMenuOffsetY(Context ctx, int v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MENU_OFFSET_Y, clampI(v, MENU_OFFSET_MIN, MENU_OFFSET_MAX)).apply();
    }

    private static float clampF(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
    private static int clampI(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // ———— 菜单布局参数是否已锁定 ————
    private static final String KEY_MENU_LOCKED = "menu_locked";

    public static boolean menuLocked(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_MENU_LOCKED, false);
    }
    public static void setMenuLocked(Context ctx, boolean v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_MENU_LOCKED, v).apply();
    }

    // ==================== 联网检索（待办 0d 路线 C）====================
    //
    // 语义：开关**默认关**（用户 2026-10-07 定的：联网默认关、要用户明确打开）。
    // 开着时，聊天窗口/问一句发出去的**问句式**消息会先由 App 自己搜一次
    // （必应 RSS，见 WebSearch），把资料拼进这一次请求 —— App 侧实现，用同一把 Key，
    // 不依赖 function calling、不加任何额外服务。
    private static final String KEY_WEB_SEARCH = "web_search_on";

    /** 联网检索开关（默认**关**） */
    public static boolean webSearch(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WEB_SEARCH, false);
    }

    public static void setWebSearch(Context ctx, boolean on) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WEB_SEARCH, on).apply();
    }

    /** 联网检索开关的说明文案（设置页与断言共用一份，别在两处各写一遍） */
    public static String webSearchLabel() {
        return "\uD83C\uDF10 联网检索（问最新的事时先自己搜一次）";
    }

    /**
     * 上次问过的**城市**（轮次㉗）。
     *
     * <p>为什么记它：用户第二次往往就一句话「今天天气怎么样」，不提城市 —— 天气接口必须给
     * {@code 101xxxxxx} 城市码。有了这个记忆，桌宠就能接着上次的城市答；没记忆时**反问一句**
     * （比瞎猜一个城市或答一段废话都好）。
     */
    private static final String KEY_LAST_CITY = "web_last_city";

    public static String lastCity(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CITY, "");
    }

    public static void setLastCity(Context ctx, String city) {
        if (city == null || city.trim().length() == 0) return;
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_CITY, city.trim()).apply();
    }

    // ==================== 搜索后端（轮次㉘：可插拔）====================
    //
    // 用户要求「不用 kimi 的搜索，要让任何 API 接入进来都能搜索」⇒ 把"搜索服务"做成配置
    // （见 SearchProvider）：这里只存三样东西 —— 选了哪个、各自的 Key、以及用户自定义的模板。

    /** 选中的搜索服务 id（默认 {@link SearchProvider#BING}：免费、零配置） */
    private static final String KEY_SEARCH_PROVIDER = "search_provider";

    /** 自定义服务模板（一整段 JSON 数组文本） */
    private static final String KEY_SEARCH_CUSTOM = "search_custom_providers";

    public static String searchProvider(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_SEARCH_PROVIDER, SearchProvider.BING);
    }

    public static void setSearchProvider(Context ctx, String id) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SEARCH_PROVIDER, id == null ? SearchProvider.BING : id).apply();
    }

    public static String customProviders(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_SEARCH_CUSTOM, "");
    }

    public static void setCustomProviders(Context ctx, String json) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SEARCH_CUSTOM, json == null ? "" : json).apply();
    }

    /**
     * 每个搜索服务**各自一把 Key**（key = {@code search_key_<providerId>}）。
     *
     * <p>为什么按服务分开存：用户可能既配了 Kimi 又配了自建 SearXNG，切来切去时不该让他重填。
     * ★ 这些 Key 和 AI Key 一样只存在本机 prefs 里，**不写进 APK、不进交付包、不进日志**。
     */
    public static String searchKey(Context ctx, String providerId) {
        if (providerId == null || providerId.length() == 0) return "";
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString("search_key_" + providerId, "");
    }

    public static void setSearchKey(Context ctx, String providerId, String key) {
        if (providerId == null || providerId.length() == 0) return;
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putString("search_key_" + providerId, key == null ? "" : key.trim()).apply();
    }

    /** 搜索后端那一行的文案（设置页与断言共用一份） */
    public static String searchProviderLabel() {
        return "\uD83D\uDD0E 搜索服务（联网检索走哪家）";
    }


    /** 主动聊天开关（默认开启） */
    private static final String KEY_MEME = "meme_enabled";

    /** 气泡表情包配图开关（默认开）*/
    public static boolean memeEnabled(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MEME, true);
    }

    public static void setMemeEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MEME, on).apply();
    }

    private static final String KEY_MEME_SCALE = "meme_scale";

    /** 气泡表情包缩放范围（相对默认 160dp 的倍数） */
    public static final float MEME_SCALE_MIN = 0.4f;
    public static final float MEME_SCALE_MAX = 2.5f;

    /** 气泡表情包图片缩放（默认 1.0 倍） */
    public static float getMemeScale(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_MEME_SCALE, 1.0f);
        if (v < MEME_SCALE_MIN) v = MEME_SCALE_MIN;
        if (v > MEME_SCALE_MAX) v = MEME_SCALE_MAX;
        return v;
    }

    public static void setMemeScale(Context ctx, float v) {
        if (v < MEME_SCALE_MIN) v = MEME_SCALE_MIN;
        if (v > MEME_SCALE_MAX) v = MEME_SCALE_MAX;
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_MEME_SCALE, v).apply();
    }

    /** 用户上传的表情包存放目录（与内置 assets/memes 合并使用） */
    public static java.io.File memeCustomDir(Context ctx) {
        java.io.File d = new java.io.File(ctx.getFilesDir(), "memes_custom");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 自定义表情清单文件：名称 → 描述 */
    public static java.io.File memeCustomJson(Context ctx) {
        return new java.io.File(ctx.getFilesDir(), "memes_custom.json");
    }

    // ———— 气泡配图：选图方式 / 频率 / 连发策略（2026-09-30）————

    private static final String KEY_MEME_MODEL_PICK = "meme_model_pick";

    // ———— 点击音效（轮次57：戳桌宠时的一声短反馈音，波形运行时合成）————

    private static final String KEY_CLICK_SOUND = "click_sound_on";
    private static final String KEY_CLICK_STYLE = "click_sound_style";
    private static final String KEY_CLICK_VOLUME = "click_sound_volume";

    /**
     * 点击音效开关。**默认开** —— 用户明确要这个功能
     * （「新增点击音效」），默认关的话他还要自己去翻设置才听得到。
     */
    public static boolean clickSoundOn(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_CLICK_SOUND, true);
    }

    public static void setClickSoundOn(Context ctx, boolean on) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CLICK_SOUND, on).apply();
    }

    /** 音色（0=啵 / 1=哒 / 2=叮）；脏值由 {@code ClickSound.styleIndex} 兜底 */
    public static int clickSoundStyle(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CLICK_STYLE, ClickSound.STYLE_POP);
    }

    public static void setClickSoundStyle(Context ctx, int style) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_CLICK_STYLE, ClickSound.styleIndex(style)).apply();
    }

    /** 音量（0~1，默认"中"）；越界由 {@code ClickSound.clamp01} 兜底 */
    public static float clickSoundVolume(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_CLICK_VOLUME, ClickSound.VOL_DEFAULT);
    }

    public static void setClickSoundVolume(Context ctx, float v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_CLICK_VOLUME, ClickSound.clamp01(v)).apply();
    }

    /** 是否把表情清单交给模型、由模型按语义选图（默认开）。
     *
     * <p>开启后清单会作为一段稳定 system 注入（可命中前缀缓存）；
     * 关闭则完全不给模型清单、零 token，只在本地按概率随机配图。</p>
     */
    public static boolean memeModelPick(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MEME_MODEL_PICK, true);
    }

    public static void setMemeModelPick(Context ctx, boolean on) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MEME_MODEL_PICK, on).apply();
    }

    private static final String KEY_MEME_CHANCE = "meme_chance";

    /** 气泡配图概率（0~100，默认 30）。0=从不配图，100=每条都配。 */
    public static int getMemeChance(Context ctx) {
        int v = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MEME_CHANCE, 30);
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public static void setMemeChance(Context ctx, int v) {
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MEME_CHANCE, v).apply();
    }

    private static final String KEY_MEME_SPLIT = "meme_split";

    /**
     * 回复连发时是否也给气泡配图（默认关）。
     *
     * <p>连发本身已经在「跳」，再每段都配图会很吵；默认关闭。
     * 开启后一次回复只在<b>第一段</b>配图，后续段共用同一张（不再逐段换图）。</p>
     */
    public static boolean memeSplitEnabled(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MEME_SPLIT, false);
    }

    public static void setMemeSplitEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MEME_SPLIT, on).apply();
    }

    private static final String KEY_EDGE_SNAP = "edge_snap_enabled";

    /** 拖拽松手后是否吸附到左右边缘（默认开）。关闭后可自由停放在任意位置。 */
    public static boolean edgeSnap(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_EDGE_SNAP, true);
    }

    public static void setEdgeSnap(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_EDGE_SNAP, on).apply();
    }

    // ==================== 贴边吸附（细长竖条 / 滑入展开） ====================
    //
    // 与 KEY_EDGE_SNAP（拖拽松手近边缘吸附）的分工：
    //   edgeSnap 管"拖完自动贴"，这里管"贴边后的形态与行为" ——
    //   吸附态只认向内滑动+长按、原形象压扁+半透明+暂停动画、
    //   idle 超时自动回贴、边缘提示动画。两套开关互不干扰。

    private static final String KEY_DOCK_ENABLED = "dock_enabled";
    private static final String KEY_DOCK_IDLE_MS = "dock_idle_ms";
    private static final String KEY_DOCK_HINT = "dock_edge_hint";

    /** 长按菜单里是否显示"贴边吸附 / 展开"项（默认开）。 */
    public static boolean dockEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_DOCK_ENABLED, true);
    }

    public static void setDockEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_DOCK_ENABLED, on).apply();
    }

    // ---- 贴边形态（轮次77）：贴边吸附的两种观感，二选一 ----

    private static final String KEY_EDGE_DOCK_STYLE = "edge_dock_style";
    /** 压扁竖条（老样子：形象压成细条贴在边上） */
    public static final int DOCK_STYLE_STRIP = 0;
    /** 挂边露头（轮次77 新增：身体挂出屏幕，只留头肩贴在边上，会呼吸） */
    public static final int DOCK_STYLE_PEEK = 1;

    /**
     * 贴边形态（默认 {@link #DOCK_STYLE_PEEK} 挂边露头）。
     *
     * <p>★ 这是**用户点名要的功能**（"新增贴边挂边吸附功能"），所以新形态做默认；
     * 老观感（压扁竖条）在设置页「🪝 贴边与挂边」里一键切回，不是删掉。
     * 反过来说：以后再加别的观感类改动，仍要守"用户明确要了才改默认"这条。
     */
    public static int edgeDockStyle(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_EDGE_DOCK_STYLE, DOCK_STYLE_PEEK);
        return (v == DOCK_STYLE_STRIP) ? DOCK_STYLE_STRIP : DOCK_STYLE_PEEK;
    }

    public static void setEdgeDockStyle(Context ctx, int style) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_EDGE_DOCK_STYLE,
                style == DOCK_STYLE_STRIP ? DOCK_STYLE_STRIP : DOCK_STYLE_PEEK).apply();
    }

    // ---- 挂边角度 / 深度（轮次77 追加：用户要求可自己调）----

    private static final String KEY_PEEK_LEAN = "edge_peek_lean";
    private static final String KEY_PEEK_KEEP = "edge_peek_keep";

    // ==================== ⑥ 挂边预设三档（轮次85）====================

    /** 预设：只露头（藏得最深、歪得最多） */
    public static final int PEEK_PRESET_HEAD = 0;
    /** 预设：半身（默认） */
    public static final int PEEK_PRESET_HALF = 1;
    /** 预设：全身（几乎不藏、几乎不歪） */
    public static final int PEEK_PRESET_FULL = 2;

    /**
     * 三档预设对应的 (lean 角度, keep 露出比例)。
     *
     * ★ 为什么给预设：原来要分别调两个滑块才能得到"只露个头"，
     *   而用户第一次要的往往就是三种大概的观感 —— 一键切比拧两个滑块快。
     */
    public static float[] peekPresetDims(int preset) {
        switch (preset) {
            case PEEK_PRESET_HEAD: return new float[]{24f, 30f};   // 24°、露出 30%
            case PEEK_PRESET_FULL: return new float[]{8f, 78f};    //  8°、露出 78%
            default:               return new float[]{16f, 62f};   // 16°、露出 62%（默认）
        }
    }

    public static String peekPresetName(int preset) {
        switch (preset) {
            case PEEK_PRESET_HEAD: return "只露头";
            case PEEK_PRESET_FULL: return "全身";
            default:               return "半身";
        }
    }

    /** 套用预设（写到角度/深度两个键上，后续渲染只认这两个键） */
    public static void applyPeekPreset(Context ctx, int preset) {
        float[] d = peekPresetDims(preset);
        setPeekLeanDeg(ctx, Math.round(d[0]));
        setPeekKeepPercent(ctx, Math.round(d[1]));
    }

    /** 当前最接近哪一档（用于设置页显示"当前：半身"） */
    public static int matchPeekPreset(Context ctx) {
        int lean = peekLeanDeg(ctx);
        int keep = peekKeepPercent(ctx);
        int best = PEEK_PRESET_HALF;
        int bestD = Integer.MAX_VALUE;
        for (int p = 0; p <= 2; p++) {
            float[] d = peekPresetDims(p);
            int diff = Math.abs(Math.round(d[0]) - lean) + Math.abs(Math.round(d[1]) - keep);
            if (diff < bestD) { bestD = diff; best = p; }
        }
        return best;
    }


    /** 挂边角度（度，0~45，默认 16） */
    public static int peekLeanDeg(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_PEEK_LEAN, 16);
        return v < 0 ? 0 : (v > 45 ? 45 : v);
    }

    public static void setPeekLeanDeg(Context ctx, int deg) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_PEEK_LEAN, deg < 0 ? 0 : (deg > 45 ? 45 : deg)).apply();
    }

    /** 挂边"露出比例"（百分比，30~95，默认 62）；UI 上按"挂出深度 = 100-露出"给用户看 */
    public static int peekKeepPercent(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_PEEK_KEEP, 62);
        return v < 30 ? 30 : (v > 95 ? 95 : v);
    }

    public static void setPeekKeepPercent(Context ctx, int pct) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_PEEK_KEEP, pct < 30 ? 30 : (pct > 95 ? 95 : pct)).apply();
    }

    /** 当前形态是不是"挂边露头"（FloatingService 只看这一个判断） */
    public static boolean peekEnabled(Context ctx) {
        return edgeDockStyle(ctx) == DOCK_STYLE_PEEK;
    }

    public static void setPeekEnabled(Context ctx, boolean on) {
        setEdgeDockStyle(ctx, on ? DOCK_STYLE_PEEK : DOCK_STYLE_STRIP);
    }

    /** 无操作自动回贴超时 ms（恒 0 = 关闭，只能手动贴边）。 */
    public static long dockIdleMs(Context ctx) {
        // 历史版本默认 8000（自动贴边）；现改为只手动吸附。
        // 老用户残留值强制归零，避免升级后桌宠自己跑去贴边。
        // 仍保留 setDockIdleMs（调试广播用），但 getter 恒返回 0。
        try {
            SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
            if (sp.getLong(KEY_DOCK_IDLE_MS, 0L) != 0L) {
                sp.edit().putLong(KEY_DOCK_IDLE_MS, 0L).apply();
            }
        } catch (Throwable ignore) {}
        return 0L;
    }

    public static void setDockIdleMs(Context ctx, long ms) {
        if (ms < 0) ms = 0;
        if (ms > 600000L) ms = 600000L;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putLong(KEY_DOCK_IDLE_MS, ms).apply();
    }

    /** 贴边后是否播一次边缘提示动画（默认开）。 */
    public static boolean dockEdgeHint(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_DOCK_HINT, true);
    }

    public static void setDockEdgeHint(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_DOCK_HINT, on).apply();
    }

    // ==================== 游戏助手（前台应用实时帧率） ====================
    //
    // 依赖 Shizuku（免 root 拿 shell 权限）。桌宠本体不依赖它 ——
    // 没装 Shizuku 只是这个功能不可用，其余一切照常。

    private static final String KEY_GAME_ASSIST = "game_assistant";

    /** 游戏助手总开关（默认关：要装 Shizuku 才能用，不该默认开启）。 */
    public static boolean gameAssistant(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_GAME_ASSIST, false);
    }

    public static void setGameAssistant(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_GAME_ASSIST, on).apply();
    }

    // ★ 一个已删除的键（留个记号，免得下一个窗口再去翻）：
    //   game_assistant_auto —— 那个「只在游戏前台时显示」开关**只有 setter、
    //     全工程零读取点**，用户点了完全没效果（2026-10-07 反馈），界面已撤。
    //
    // ★ game_apps（轮次⑥ 的白名单）在轮次⑦ 被撤除、本轮（2026-10-08）又以
    //   **新的键名 `monitor_apps`** 回来 —— 用户要求"全局 / 仅选定应用"
    //   两种模式集成在一起（默认全局）。**故意不复用旧键名**：
    //   轮次⑥ 选过名单的用户，这一次从"干净的空名单"开始，
    //   不会因为翻出半年前的勾选而出现"我没选它却在显示"。

    // ==================== 监测条（八项系统指标，自由选三项）====================

    private static final String KEY_MONITOR_BAR = "monitor_bar";
    private static final String KEY_MONITOR_TOP = "monitor_slot_top";
    private static final String KEY_MONITOR_MID = "monitor_slot_mid";
    private static final String KEY_MONITOR_BOTTOM = "monitor_slot_bottom";

    /** 监测范围：{@code true} = 只在选定的应用里显示。 */
    private static final String KEY_MONITOR_SCOPE = "monitor_only_selected";
    /** 选定的应用包名集合（仅"部分应用"模式用）。 */
    private static final String KEY_MONITOR_APPS = "monitor_apps";
    /** 侧栏监测条与桌宠的间距（dp，可负）。 */
    private static final String KEY_MONITOR_GAP = "monitor_gap_dp";

    /**
     * 监测条显示开关（默认开）。
     *
     * <p>与"游戏助手总开关"分工不同：总开关决定**功能开不开**（要不要采样、
     * 设置页要不要显示读数），本项只决定**悬浮层上的条显不显示**。
     * 长按菜单里那个「📊 监测条」就是它 —— 用户想临时收起这条东西时，
     * 不该把整个功能连带关掉。
     */
    public static boolean monitorBar(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_MONITOR_BAR, true);
    }

    public static void setMonitorBar(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_MONITOR_BAR, on).apply();
    }

    /**
     * 侧栏监测条与桌宠的间距（dp，可负 = 压进桌宠画布的透明留白里）。
     *
     * <p>★ 用户要求"距离桌宠太远 + 加一个可调节的选项"：不再写死 6dp。
     * 读出时统一过 {@link SysMetrics#clampMonitorGapDp}（跨版本的脏值/NaN 都能兜住），
     * 默认 {@link SysMetrics#MONITOR_GAP_DEFAULT_DP}。
     */
    public static float monitorGapDp(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return SysMetrics.clampMonitorGapDp(
                sp.getFloat(KEY_MONITOR_GAP, SysMetrics.MONITOR_GAP_DEFAULT_DP));
    }

    public static void setMonitorGapDp(Context ctx, float dp) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_MONITOR_GAP, SysMetrics.clampMonitorGapDp(dp)).apply();
    }

    /**
     * 监测范围：{@code true} = 只在选定的应用里显示；{@code false}（默认）= 所有应用。
     *
     * <p>★ 默认必须是 {@code false}：这是"全局"那一档，也就是轮次⑦ 之后用户
     * 一直在用的行为。默认写成 {@code true} 会让所有人升级后突然"监测条不见了"
     * （名单是空的），而且看上去像 bug 而不是设置。
     */
    public static boolean monitorOnlySelected(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_MONITOR_SCOPE, false);
    }

    public static void setMonitorOnlySelected(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_MONITOR_SCOPE, on).apply();
    }

    /**
     * 选定的应用包名集合（**永远是副本**）。
     *
     * <p>★ 两个必须遵守的约定，都来自 SharedPreferences 的文档：
     * <ol>
     *   <li>它返回的 Set **不允许修改**（改它行为未定义）；</li>
     *   <li>写回时也必须传一个新 Set —— 把刚读出来的那个实例原样传回去，
     *       框架可能认为"没变"而不落盘。</li>
     * </ol>
     * 所以这里读出即拷贝，写入前也拷贝，调用方拿到的永远是可以随便改的普通 HashSet。
     */
    public static java.util.Set<String> monitorApps(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        java.util.Set<String> raw = sp.getStringSet(KEY_MONITOR_APPS, null);
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (raw != null) out.addAll(raw);
        return out;
    }

    public static int monitorAppCount(Context ctx) {
        return monitorApps(ctx).size();
    }

    public static boolean isMonitorApp(Context ctx, String pkg) {
        return pkg != null && monitorApps(ctx).contains(pkg);
    }

    /** 勾选 / 取消一个应用。传空包名直接忽略（会把 prefs 写脏）。 */
    public static void setMonitorApp(Context ctx, String pkg, boolean on) {
        if (pkg == null || pkg.length() == 0) return;
        java.util.Set<String> set = monitorApps(ctx);
        boolean changed = on ? set.add(pkg) : set.remove(pkg);
        if (!changed) return;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putStringSet(KEY_MONITOR_APPS, set).apply();
    }

    public static void clearMonitorApps(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        // 传一个空的新 Set（而不是 remove 掉键）：语义是"名单是空的"，
        // 与"从来没有设过名单"在读出来时等价（都返回空集合）。
        sp.edit().putStringSet(KEY_MONITOR_APPS, new java.util.HashSet<String>()).apply();
    }

    private static String monitorSlotKey(int slot) {
        return slot == 0 ? KEY_MONITOR_TOP : (slot == 1 ? KEY_MONITOR_MID : KEY_MONITOR_BOTTOM);
    }

    /** 槽位默认值：上=CPU、中=FPS（原来挂牌的位置）、下=RAM。 */
    public static String monitorSlotDefault(int slot) {
        return slot == 0 ? SysMetrics.CPU : (slot == 1 ? SysMetrics.FPS : SysMetrics.RAM);
    }

    /**
     * 某个槽位放哪个指标。{@code slot}: 0=上 1=中 2=下。
     *
     * <p>★ 读出后必须过一遍 {@link SysMetrics#isValid}：prefs 是**跨版本**的，
     * 万一以后删掉某个指标 id，老用户存的字符串就变成了未知值 ——
     * 直接拿去渲染会得到空白格，而且没有任何报错（最难查的那种）。
     */
    public static String monitorSlot(Context ctx, int slot) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String def = monitorSlotDefault(slot);
        String v = sp.getString(monitorSlotKey(slot), def);
        return SysMetrics.isValid(v) ? v : def;
    }

    public static void setMonitorSlot(Context ctx, int slot, String id) {
        if (!SysMetrics.isValid(id)) return;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(monitorSlotKey(slot), id).apply();
    }

    /** 每小时整点报时开关（默认开） */
    private static final String KEY_HOUR_CHIME = "hour_chime_enabled";

    public static boolean hourChimeEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_HOUR_CHIME, true);
    }

    public static void setHourChimeEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_HOUR_CHIME, on).apply();
    }

    /** 上次已报时的"小时戳"（hour*10000 + dayOfYear），防止同一小时重复报 */
    private static final String KEY_LAST_CHIME = "last_chime_hour";

    public static long getLastChimeHour(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getLong(KEY_LAST_CHIME, 0L);
    }

    public static void setLastChimeHour(Context ctx, long v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putLong(KEY_LAST_CHIME, v).apply();
    }

    /** 时节素材联动开关（节日/节气/时段专属动作，默认开） */
    private static final String KEY_SEASONAL_ANIM = "seasonal_anim_enabled";

    public static boolean seasonalAnimEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_SEASONAL_ANIM, true);
    }

    public static void setSeasonalAnimEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_SEASONAL_ANIM, on).apply();
    }

    public static boolean proactiveChat(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_PROACTIVE, true);
    }

    public static void setProactiveChat(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_PROACTIVE, on).apply();
    }

    /** 主动聊天间隔（分钟，默认 20，范围 3~240） */
    public static int getProactiveMinutes(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_PROACTIVE_MIN, 20);
        if (v < 3) v = 3;
        if (v > 240) v = 240;
        return v;
    }

    public static void setProactiveMinutes(Context ctx, int min) {
        if (min < 3) min = 3;
        if (min > 240) min = 240;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_PROACTIVE_MIN, min).apply();
    }

    /** 是否开启「识别主人在干嘛并主动搭话」 */
    public static boolean moodWatch(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_MOOD_WATCH, true);
    }

    public static void setMoodWatch(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_MOOD_WATCH, on).apply();
    }

    /** 场景搭话的最小间隔（分钟，默认 20，范围 3~180）。档位越低越省。 */
    public static int getMoodCooldownMinutes(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_MOOD_CD_MIN, 20);
        if (v < 3) v = 3;
        if (v > 180) v = 180;
        return v;
    }

    public static void setMoodCooldownMinutes(Context ctx, int min) {
        if (min < 3) min = 3;
        if (min > 180) min = 180;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_MOOD_CD_MIN, min).apply();
    }

    /** 同一应用连续停留多久算“久留”（分钟，默认 30，范围 5~180） */
    public static int getMoodStayMinutes(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_MOOD_STAY_MIN, 30);
        if (v < 5) v = 5;
        if (v > 180) v = 180;
        return v;
    }

    public static void setMoodStayMinutes(Context ctx, int min) {
        if (min < 5) min = 5;
        if (min > 180) min = 180;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_MOOD_STAY_MIN, min).apply();
    }

    // ===================== 秒级档位（新） =====================

    /** 场景搭话最小间隔（秒，默认 300=5分钟，范围 30~10800）。 */
    public static int getMoodCooldownSec(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_MOOD_CD_SEC, 300);
        if (v < 30) v = 30;
        if (v > 10800) v = 10800;
        return v;
    }
    public static void setMoodCooldownSec(Context ctx, int sec) {
        if (sec < 30) sec = 30;
        if (sec > 10800) sec = 10800;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_MOOD_CD_SEC, sec).apply();
    }

    /** 同一应用连续停留多久算「久留」（秒，默认 1800=30分钟，范围 30~10800）。 */
    public static int getMoodStaySec(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_MOOD_STAY_SEC, 1800);
        if (v < 30) v = 30;
        if (v > 10800) v = 10800;
        return v;
    }
    public static void setMoodStaySec(Context ctx, int sec) {
        if (sec < 30) sec = 30;
        if (sec > 10800) sec = 10800;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_MOOD_STAY_SEC, sec).apply();
    }

    /** 碰过桌宠后多久内不主动开口（秒，默认 300=5分钟，范围 0~3600）。0 表示不设门槛。 */
    public static int getQuietSec(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_QUIET_SEC, 0);
        if (v < 0) v = 0;
        if (v > 3600) v = 3600;
        return v;
    }
    public static void setQuietSec(Context ctx, int sec) {
        if (sec < 0) sec = 0;
        if (sec > 3600) sec = 3600;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_QUIET_SEC, sec).apply();
    }

    /**
     * 点击 / 拖动桌宠时的对话冷却（毫秒）。
     * 冷却期内只做动画反馈，不再触发说话，避免连点把 API 刷爆。
     * 0 = 关闭冷却（每次必说）。
     */
    public static final long INTERACT_CD_MIN_MS = -1L;   // -1 = 纯本地模式
    public static final long INTERACT_CD_MAX_MS = 60000L;
    public static final long INTERACT_CD_DEFAULT_MS = 2000L;

    public static long getInteractCdMs(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        long v = sp.getLong(KEY_INTERACT_CD_MS, INTERACT_CD_DEFAULT_MS);
        if (v < INTERACT_CD_MIN_MS) v = INTERACT_CD_MIN_MS;
        if (v > INTERACT_CD_MAX_MS) v = INTERACT_CD_MAX_MS;
        return v;
    }

    public static void setInteractCdMs(Context ctx, long ms) {
        if (ms < INTERACT_CD_MIN_MS) ms = INTERACT_CD_MIN_MS;
        if (ms > INTERACT_CD_MAX_MS) ms = INTERACT_CD_MAX_MS;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putLong(KEY_INTERACT_CD_MS, ms).apply();
    }

    /** 纯本地模式：互动时只念本地台词，绝不调用 API（省额度/断网也能玩） */
    public static final long INTERACT_LOCAL_ONLY = -1L;

    public static boolean interactLocalOnly(Context ctx) {
        return getInteractCdMs(ctx) == INTERACT_LOCAL_ONLY;
    }

    // ==================== 纯本地 · 自定义发言（用户 2026-10-07 要求）====================
    //
    // 用户要求："纯本地增加自定义发言的选项，可以自定义各个场景的宠物说话发言"。
    // 每个场景一条原始多行文本；**留空 = 用内置台词池**（语义在 LocalLines 里说清了）。

    private static final String KEY_LOCAL_LINE_PREFIX = "local_line_";

    /** 某场景的自定义台词（原始多行文本；没设过 = 空串）。 */
    public static String localLines(Context ctx, String scene) {
        if (!LocalLines.isValid(scene)) return "";
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        // ★ 轮次㊲：场景 id 与 **prefs 键后缀**不再一一相同（四个历史场景有自己的键名，
        //   见 LocalLines.keyOf）—— 键名映射只在这一处做，调用方只管传场景 id。
        String v = sp.getString(KEY_LOCAL_LINE_PREFIX + LocalLines.keyOf(scene), "");
        return v == null ? "" : v;
    }

    public static void setLocalLines(Context ctx, String scene, String raw) {
        if (!LocalLines.isValid(scene)) return;      // 脏键写进去就再也清不掉了
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_LOCAL_LINE_PREFIX + LocalLines.keyOf(scene),
                raw == null ? "" : raw).apply();
    }

    // ==================== 互动发言开关（关 = 纯本地，开 = 调用 API）====================
    //
    // 用户原话："做成开关，关闭就是纯本地，打开就是调用api。"
    // 它和「互动对话冷却」是**同一个轴**（-1 = 纯本地），所以两者写同一个键：
    // 开关只做"一键切换"，冷却那一行仍可细调。打开时**恢复上次的非纯本地值**，
    // 而不是硬回到默认 —— 否则用户设的 10 秒冷却会被开关悄悄改掉。

    private static final String KEY_INTERACT_SPEAK_LAST = "interact_cd_ms_last";

    /** 互动时是否让桌宠发言（true = 调用 API；false = 纯本地台词）。 */
    public static boolean interactSpeakEnabled(Context ctx) {
        return !interactLocalOnly(ctx);
    }

    public static void setInteractSpeakEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        long cur = getInteractCdMs(ctx);
        if (!on) {
            if (cur != INTERACT_LOCAL_ONLY) {
                sp.edit().putLong(KEY_INTERACT_SPEAK_LAST, cur).apply();
            }
            setInteractCdMs(ctx, INTERACT_LOCAL_ONLY);
            return;
        }
        long last = sp.getLong(KEY_INTERACT_SPEAK_LAST, INTERACT_CD_DEFAULT_MS);
        if (last <= INTERACT_LOCAL_ONLY) last = INTERACT_CD_DEFAULT_MS;
        if (last > INTERACT_CD_MAX_MS) last = INTERACT_CD_MAX_MS;
        setInteractCdMs(ctx, last);
    }

    // ———— 余额 / 用量刷新：固定为「实时」 ————————————————
    // （原「刷新频率」选项已按需求移除：一律实时刷新）
    public static final long BAL_REFRESH_REALTIME = -1L;

    /** 始终返回实时档，保证余额与用量都走官网最新数据 */
    public static long getBalanceRefreshSec(Context ctx) {
        return BAL_REFRESH_REALTIME;
    }

    /** 实时档的兜底轮询间隔：30 秒（此外每次互动后还会立刻刷一次） */
    public static long balanceRefreshMs(Context ctx) {
        // ★ 熄屏/后台由 FloatingService.startLoop 负责跳过，不在这里改间隔 ——
        //   这里保持"实时"的语义：亮屏时余额要跟着官网走。
        return 30_000L;
    }

    // 后台/熄屏省电复用的是性能优化的同一个开关（perf_auto）：
    // 它表达的就是"看不见的时候别折腾"，用户不必理解两套概念。
    // 涉及三处后台行为：
    //   ① 视频背景暂停（MainActivity.onPause）
    //   ② 余额轮询跳过（FloatingService.startLoop）
    //   ③ 场景识别轮询跳过（FloatingService.startMoodLoop）
    // 实测：熄屏后进程 CPU 从约 10~12% 降到 0.2%。

    /** 余额不足时，让桌宠主动提醒（默认开） */
    public static boolean balanceNotify(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BAL_NOTIFY_ON, true);
    }

    public static void setBalanceNotify(Context ctx, boolean on) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BAL_NOTIFY_ON, on).apply();
    }

    /** 用量增长时，让桌宠播报今日用量与缓存命中率（默认开） */
    public static boolean usageNotify(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_USAGE_NOTIFY_ON, true);
    }

    public static void setUsageNotify(Context ctx, boolean on) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USAGE_NOTIFY_ON, on).apply();
    }

    /** 把毫秒冷却显示成人话：-1 → 纯本地，0 → 关闭 */
    public static String interactCdLabel(Context ctx) {
        long ms = getInteractCdMs(ctx);
        if (ms == INTERACT_LOCAL_ONLY) return "纯本地（互动只念本地台词）";
        if (ms <= 0) return "关闭（每次都说）";
        if (ms < 1000) return ms + " 毫秒";
        if (ms % 1000 == 0) {
            long sec = ms / 1000;
            if (sec < 60) return sec + " 秒";
            return (sec / 60) + " 分钟" + (sec % 60 == 0 ? "" : " " + (sec % 60) + " 秒");
        }
        return String.format(java.util.Locale.US, "%.1f 秒", ms / 1000.0);
    }

    public static int getPosX(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_POS_X, 100);
    }

    public static int getPosY(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_POS_Y, 300);
    }

    public static void savePos(Context ctx, int x, int y) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply();
    }

    /** 读取所有预设，返回列表；每项 String[]{name, content}。首次访问写入内置预设 */
    public static java.util.List<String[]> getPresets(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_PRESETS, "");
        java.util.List<String[]> list = new java.util.ArrayList<>();
        if (json != null && json.length() > 0) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    list.add(new String[]{o.optString("name", "未命名"), o.optString("content", "")});
                }
            } catch (Exception ignore) {}
        }
        if (list.isEmpty()) {
            for (String[] p : builtinPresets()) list.add(p);
            savePresets(ctx, list);
        }
        return list;
    }

    public static void savePresets(Context ctx, java.util.List<String[]> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (String[] p : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", p.length > 0 ? p[0] : "未命名");
                o.put("content", p.length > 1 ? p[1] : "");
                arr.put(o);
            }
        } catch (Exception ignore) {}
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_PRESETS, arr.toString()).apply();
    }

    public static int getPresetIndex(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_PRESET_IDX, 0);
    }

    public static void setPresetIndex(Context ctx, int idx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_PRESET_IDX, Math.max(0, idx)).apply();
    }

    /** 桌宠性格设定（system prompt） */
    public static String getPersona(Context ctx) {
        // ★ v7.0：角色包里的 persona.md 优先（性格**按角色隔离**；
        //   没有这个文件才走"预设/自定义文本"，兼容老用户）
        try {
            PetCharacter ch = CharacterStore.current(ctx);
            if (ch != null && ch.dir != null) {
                java.io.File f = new java.io.File(ch.dir, "persona.md");
                if (f.isFile() && f.length() > 0) {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int k;
                    while ((k = in.read(b)) > 0) bos.write(b, 0, k);
                    in.close();
                    String t = new String(bos.toByteArray(), "UTF-8").trim();
                    if (t.length() > 0) return t;
                }
            }
        } catch (Throwable ignore) {}
        // 优先使用当前选中的预设
        java.util.List<String[]> presets = getPresets(ctx);
        int idx = getPresetIndex(ctx);
        if (idx >= 0 && idx < presets.size()) {
            String c = presets.get(idx)[1];
            if (c != null && c.trim().length() > 0) return c.trim();
        }
        // 兜底：自定义文本
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_PERSONA, "");
        if (v == null || v.trim().length() == 0) return DEFAULT_PERSONA;
        return v;
    }

    /** 取人设并按当前名字替换默认自称（{@link #applyPetNameToPersona}）*/
    public static String personaForPet(Context ctx) {
        return applyPetNameToPersona(ctx, getPersona(ctx));
    }
    /** 当前选中预设的名字；无则返回"自定义" */
    public static String currentPresetName(Context ctx) {
        java.util.List<String[]> presets = getPresets(ctx);
        int idx = getPresetIndex(ctx);
        if (idx >= 0 && idx < presets.size()) return presets.get(idx)[0];
        return "自定义";
    }

    public static void savePersona(Context ctx, String persona) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_PERSONA, persona == null ? "" : persona.trim()).apply();
    }

    /** 是否开启点击对话功能 */
    public static boolean chatEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_CHAT_ENABLED, true);
    }

    public static void setChatEnabled(Context ctx, boolean enabled) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_CHAT_ENABLED, enabled).apply();
    }

    /** 对话模型名，默认 deepseek-chat */
    public static String getChatModel(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String m = sp.getString(KEY_CHAT_MODEL, "deepseek-chat");
        if (m == null || m.trim().length() == 0) return "deepseek-chat";
        return m.trim();
    }

    public static void setChatModel(Context ctx, String model) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_CHAT_MODEL, model == null ? "" : model.trim()).apply();
    }

    // ———— 本地 GGUF 模型（轮次62 · 第二轮 · 导入层）————

    /** 当前选中的本地模型文件名（只存**文件名**，目录固定 —— 见 ModelStore）。 */
    private static final String KEY_LOCAL_MODEL = "local_llm_model";

    /**
     * 当前用哪个本地模型（文件名，如 {@code Qwen2.5-0.5B-Q4_K_M.gguf}）。
     *
     * <p>★ 只存文件名、不存绝对路径：绝对路径会随"重装 / 换用户 / 系统清 data"失效，
     * 而 {@code filesDir} 位置本来就由系统给 —— 存文件名就永远找得到。
     * 名字对应的文件不在时由 {@code ModelStore.current()} 兜底清掉。
     *
     * @return 文件名；没选过 / 已清空 ⇒ null
     */
    public static String getLocalModel(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_LOCAL_MODEL, "");
        if (v == null) return null;
        v = v.trim();
        return v.length() == 0 ? null : v;
    }

    public static void setLocalModel(Context ctx, String fileName) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_LOCAL_MODEL,
                fileName == null ? "" : fileName.trim()).apply();
    }

    // ———— 本地模型的 GPU 加速（轮次66 建立「开/关」· 轮次68 三态 · **轮次69 收回两态**）————

    /**
     * 轮次66 的「⚡ GPU 加速」**开关**键（boolean）。★ 保留 —— 只为**兼容升级上来的用户**。
     *
     * <p>它的语义是"开 = 用 GPU"（= 现在的 {@link GpuMode#ON}）。
     * ★ 默认值仍然是 {@code true}：兼容链读它的时候，"明确关过"才算 off。
     */
    private static final String KEY_LOCAL_GPU = "local_llm_gpu";

    /**
     * GPU 档位键（字符串）：{@code on} / {@code off}；★ 轮次68 写进去的 {@code force} / {@code auto}
     * 也**认**（都读成 {@link GpuMode#ON} —— 见 {@link GpuMode#normalize}）。
     *
     * <p>★★★ 用户原话「把加速做成开GPU」⇒ 默认值 = {@link GpuMode#DEFAULT_MODE}（**开**）。
     * ★ 键名**故意不改**（还是 {@code local_llm_gpu_mode}）：升级上来的用户那一格里躺着旧值，
     * 我们靠 {@code normalize} 的兼容规则把它读成两态 —— 比换键名（旧值成为孤儿）安全得多。
     * 为什么用字符串而不是 int：设置页/文档/日志里都直接可读，坏值也不会"碰巧是个合法整数"。
     */
    private static final String KEY_LOCAL_GPU_MODE = "local_llm_gpu_mode";

    /** 旧 boolean 的**原始**读数（只给兼容链用；默认 true 见测试口径）。 */
    private static boolean localGpuLegacy(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCAL_GPU, true);
    }

    /**
     * 本地推理用哪个 GPU 档位：{@link GpuMode#ON} / {@link GpuMode#OFF}。
     *
     * <p>★★★ 默认 {@link GpuMode#ON}（用户原话「把加速做成开GPU」）。
     *
     * <h3>★ 兼容链（升级上来的用户读到什么 —— 有断言钉着）</h3>
     * <ol>
     *   <li>新键存在且认得出 ⇒ 照它。★ 轮次68 写进去的 {@code force} / {@code auto}
     *       **都算认得出**（{@link GpuMode#normalize} 把两者都读成「开」——
     *       它们本来就是"用 GPU"，本轮只是把"静默回落"那一档去掉）；</li>
     *   <li>新键存在但**认不出**（真脏值）⇒ 当"没设置过"继续往下走
     *       （★ 既不把脏值当开、也不当关 —— 脏字符串不该决定"用不用 GPU"）；</li>
     *   <li>只存过旧的 boolean：**明确关过**（false）⇒ {@link GpuMode#OFF}（尊重他的选择）；
     *       开过（true）⇒ 本轮默认 {@link GpuMode#ON}；</li>
     *   <li>两个键都没有（新装 / 从没碰过）⇒ {@link GpuMode#ON}。</li>
     * </ol>
     *
     * <p>★ 这台设备到底有没有可用的 GPU 不在这里判断 —— 那只有 ggml 自己知道
     * （驱动、机型、内存都可能让它起不来）。原生侧按档位分流：
     * {@code off} 纯 CPU；{@code on} **只走 GPU**、失败**不回落**、把原因交回来。
     */
    public static String localGpuMode(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String m = GpuMode.normalize(sp.getString(KEY_LOCAL_GPU_MODE, null));
        if (m != null) return m;
        if (sp.contains(KEY_LOCAL_GPU)) return GpuMode.migrate(true, localGpuLegacy(ctx));
        return GpuMode.migrate(false, true);
    }

    /**
     * 写两态。★ 认不出的值**不落盘**（否则下一次读出来又是一个要兜底的东西）。
     * ★ 旧的 boolean 键**一起写**：两边永远不自相矛盾（万一还有别处读它）。
     */
    public static void setLocalGpuMode(Context ctx, String mode) {
        final String m = GpuMode.normalize(mode);
        if (m == null) return;
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LOCAL_GPU_MODE, m)
                .putBoolean(KEY_LOCAL_GPU, !GpuMode.OFF.equals(m))
                .apply();
    }

    /**
     * 要不要用 GPU（"关"之外都是"用"）。
     * ★ 保留旧名字给老调用点；真正的两态判断走 {@link #localGpuMode(Context)}。
     */
    public static boolean localGpu(Context ctx) {
        return GpuMode.usesGpu(localGpuMode(ctx));
    }

    /**
     * 旧的 boolean setter（轮次66 的调用点还在用）。
     * ★ 映射：{@code true} → {@link GpuMode#ON}、{@code false} → {@link GpuMode#OFF}。
     * 轮次69 之后两态与 boolean 是**一一对应**的（不再有"自动"那一档要映射）。
     */
    public static void setLocalGpu(Context ctx, boolean on) {
        setLocalGpuMode(ctx, on ? GpuMode.ON : GpuMode.OFF);
    }

    /**
     * GPU 那一行的文案（设置页与断言共用一份，别在两处各写一遍 ——
     * 轮次65 刚吃过"同一个事实抄两处就一定会漂"的教训）。
     */
    public static String localGpuLabel(Context ctx) {
        return GpuMode.label(localGpuMode(ctx));
    }

    /** GPU 那一行的说明（点下去时用）。 */
    public static String localGpuHint() {
        return GpuMode.hint();
    }

    // ———— 本地推理的**进阶调优旋钮**（轮次71：把"只能在 PC 上做的 A/B"搬进设置页）————

    /**
     * 调优串那个键（字符串）：{@code adreno=auto;flash=auto;ubatch=512;…}
     * （格式与取值域见 {@link LocalTune}，它是**唯一**的定义处）。
     *
     * <p>★★★ 为什么 8 个旋钮只占**一个**键：磁盘上只留一条串，
     * 就不会出现"flash 在键 A 里、ubatch 在键 B 里"这种半新半旧的状态；
     * 而且 {@link LocalTune#normalize(String)} 会把它规范掉 ——
     * 脏值既不会传进原生层，也**不会**被写回去。
     */
    private static final String KEY_LOCAL_TUNE = "local_llm_tune";

    /**
     * 这 8 个调优旋钮现在是什么值（**一条规范串**）。
     *
     * <p>★ 没设置过 / 串脏了 ⇒ 出厂默认那一串（{@link LocalTune#defaults()}）——
     * 也就是**本轮之前的行为完全一样**。用户什么都不动的时候，
     * 这一整套旋钮对他是**零影响**的（有断言钉着这条）。
     */
    public static String localTune(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return LocalTune.normalize(sp.getString(KEY_LOCAL_TUNE, null));
    }

    /** 写调优串。★ 规范过才落盘（认不出的键/值一律换成默认值，绝不写脏东西进去）。 */
    public static void setLocalTune(Context ctx, String tune) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LOCAL_TUNE, LocalTune.normalize(tune)).apply();
    }

    /**
     * 改**一个**旋钮并落盘，返回改完之后那条串。
     * ★ 值认不出 ⇒ 原样返回（{@link LocalTune#set(String, String, String)} 的语义）。
     */
    public static String setLocalTuneKnob(Context ctx, String key, String val) {
        final String next = LocalTune.set(localTune(ctx), key, val);
        setLocalTune(ctx, next);
        return next;
    }

    /** 调优那一行的文案（设置页与断言共用一份）。 */
    public static String localTuneLabel(Context ctx) {
        return LocalTune.label(localTune(ctx));
    }

    /** 推理强度："" 不发送 / minimal / low / medium / high / none（禁用） */
    public static String getReasoningEffort(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_REASONING_EFFORT, "");
        return v == null ? "" : v.trim();
    }
    public static void setReasoningEffort(Context ctx, String v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_REASONING_EFFORT, v == null ? "" : v.trim()).apply();
    }

    /** 思考开关："" 不发送 / enabled / disabled */
    public static String getThinkingType(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_THINKING_TYPE, "");
        return v == null ? "" : v.trim();
    }
    public static void setThinkingType(Context ctx, String v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_THINKING_TYPE, v == null ? "" : v.trim()).apply();
    }

    /** 由悬浮菜单触发：让 MainActivity 弹出选图界面 */
    public static void requestPick(Context ctx) {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.setAction(ACTION_PICK_IMAGE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignore) {
        }
    }

    public static void requestImportModel(Context ctx) {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.setAction(ACTION_IMPORT_MODEL);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignore) {
        }
    }

    /**
     * 桌宠是否「应该」在运行。
     * 用户主动「退出桌宠」时置 false，启动时置 true。
     * 开机自启只在这个值为 true 时才拉起 —— 尊重用户意愿，
     * 不会在用户明明退出后重启手机又自己冒出来。
     */
    public static boolean petShouldRun(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_PET_SHOULD_RUN, true);   // 默认 true：装完就能自启
    }

    /** 看门狗开关：被系统清掉后自动把桌宠拉回来（默认开） */
    public static boolean keepAliveEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_KEEPALIVE, true);
    }
    public static void setKeepAliveEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_KEEPALIVE, on).apply();
    }

    /**
     * 启动 / 退出问候语总开关（默认开）。
     * 关掉后：启动不说「刚睡醒」台词、退出不道别、
     * 也不会自动接上次的话题；主动聊天 / 场景搭话等其他行为不受影响。
     */
    public static boolean greetingOn(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_GREETING_ON, true);
    }

    public static void setGreetingOn(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_GREETING_ON, on).apply();
    }

    /** 看门狗巡检间隔（毫秒），默认 15 分钟；范围 5~60 分钟 */
    public static long keepAliveIntervalMs(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int min = sp.getInt(KEY_KEEPALIVE_MIN, 15);
        if (min < 5) min = 5;
        if (min > 60) min = 60;
        return min * 60L * 1000L;
    }
    public static int keepAliveMinutes(Context ctx) {
        return (int) (keepAliveIntervalMs(ctx) / 60000L);
    }
    public static void setKeepAliveMinutes(Context ctx, int min) {
        if (min < 5) min = 5;
        if (min > 60) min = 60;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_KEEPALIVE_MIN, min).apply();
    }

    public static void setPetShouldRun(Context ctx, boolean run) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_PET_SHOULD_RUN, run).apply();
    }

    /** 通知悬浮窗视图刷新（大小/图片/透明度变化后） */
    public static void reloadPet(Context ctx) {
        try {
            Intent i = new Intent(ctx, FloatingService.class);
            i.setAction(FloatingService.ACTION_RELOAD);
            ctx.startService(i);
        } catch (Exception ignore) {
        }
    }

    /**
     * 只让桌宠重排悬浮层位置（不重建视图）—— 给"拖动滑块要实时看到效果"的设置用。
     *
     * <p>典型场景：监测条间距。用 {@link #reloadPet} 的话每动一格就重建整套视图
     * （还有余额轮询重启），滑块会又卡又闪。
     */
    public static void repositionPet(Context ctx) {
        try {
            Intent i = new Intent(ctx, FloatingService.class);
            i.setAction(FloatingService.ACTION_REPOSITION);
            ctx.startService(i);
        } catch (Exception ignore) {
        }
    }

    // ==================== 云端语音合成（TTS · 让桌宠说话 · 多协议） ====================
    //
    // 重做要点（对着老版本的毛病改）：
    //   1. 老版本把「对话地址 + /audio/speech」当默认，而对话默认是 api.deepseek.com，
    //      那家没有语音接口 → 必然 404。现在地址一律来自所选服务商预设，
    //      **绝不再从对话地址派生**。
    //   2. 老版本路径写死拼 /audio/speech、response_format 写死 mp3、鉴权写死 Bearer，
    //      只能接 OpenAI 那一套。现在协议细节在 TtsCodec / TtsProvider，
    //      按 tts_provider 选中的服务商分发。
    //   3. 老版本鉴权头对火山是错的（火山要 `Bearer;<token>`）。

    private static final String KEY_TTS_ENABLED   = "tts_enabled";      // 让桌宠把回复念出来
    private static final String KEY_TTS_PROVIDER    = "tts_provider";     // 服务商 id
    private static final String KEY_TTS_CLOUD_URL   = "tts_cloud_url";    // 完整接口地址（留空=用预设）
    private static final String KEY_TTS_CLOUD_KEY   = "tts_cloud_key";    // 专用 Key（留空=复用对话 Key）
    private static final String KEY_TTS_MODEL       = "tts_model";
    private static final String KEY_TTS_VOICE       = "tts_voice";
    private static final String KEY_TTS_FORMAT      = "tts_format";       // mp3 / wav / pcm …
    private static final String KEY_TTS_APPID       = "tts_appid";        // 火山 AppId
    private static final String KEY_TTS_GROUPID     = "tts_group_id";     // MiniMax GroupId
    private static final String KEY_TTS_SPEED       = "tts_speed";
    private static final String KEY_TTS_MIN_CHARS   = "tts_min_chars";
    private static final String KEY_TTS_CLONE_REF_AUDIO = "tts_clone_ref_audio";
    private static final String KEY_TTS_CLONE_REF_TEXT  = "tts_clone_ref_text";

    /** 让桌宠把回复念出来（默认关） */
    public static boolean ttsEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_TTS_ENABLED, false);
    }
    public static void setTtsEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_TTS_ENABLED, on).apply();
    }

    /**
     * 当前服务商 id。首次访问（老版本升上来）会从已填地址反推一次并落盘，
     * 认不出就落到默认的硅基流动 —— 那是目前唯一实测通过、且零配置可用的。
     */
    public static String ttsProvider(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_PROVIDER, "");
        if (v != null && v.length() > 0 && TtsProvider.find(v) != null) return v;

        // 迁移：按老配置里的地址猜一次
        String legacyUrl = sp.getString(KEY_TTS_CLOUD_URL, "");
        String guess = TtsProvider.detectId(legacyUrl);
        if (guess == null) guess = TtsProvider.def().id;
        sp.edit().putString(KEY_TTS_PROVIDER, guess).apply();
        return guess;
    }

    /**
     * 切换服务商：把该预设的默认地址/模型/音色/格式一并写入，
     * 这样用户切完就能直接试听，不用挨个填。Key 保留不动（通常同一个 Key 通用，
     * 不通用时用户自己改）。
     */
    public static void setTtsProvider(Context ctx, String id) {
        TtsProvider.Entry e = TtsProvider.findOrDef(id);
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = sp.edit();
        ed.putString(KEY_TTS_PROVIDER, e.id);
        ed.putString(KEY_TTS_CLOUD_URL, e.url);
        ed.putString(KEY_TTS_MODEL, e.model);
        ed.putString(KEY_TTS_VOICE, e.voice);
        ed.putString(KEY_TTS_FORMAT, e.format);
        ed.putString(KEY_TTS_APPID, "");
        ed.putString(KEY_TTS_CLONE_REF_AUDIO, "");
        ed.putString(KEY_TTS_CLONE_REF_TEXT, "");
        ed.apply();
    }

    /** 当前协议类型（openai / dashscope / volc / minimax / raw） */
    public static String ttsKind(Context ctx) {
        return TtsProvider.findOrDef(ttsProvider(ctx)).kind;
    }

    /** 用户填的完整接口地址；留空表示「用所选服务商的预设地址」 */
    public static String ttsCloudUrl(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_CLOUD_URL, "");
        return v == null ? "" : v.trim();
    }
    public static void setTtsCloudUrl(Context ctx, String url) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TTS_CLOUD_URL, url == null ? "" : url.trim()).apply();
    }

    /** 语音合成专用 Key；留空则自动复用「对话用的 API Key」 */
    public static String ttsCloudKey(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_CLOUD_KEY, "");
        return v == null ? "" : v.trim();
    }
    public static void setTtsCloudKey(Context ctx, String k) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TTS_CLOUD_KEY, k == null ? "" : k.trim()).apply();
    }

    /** 火山引擎需要的 AppId（其他服务商忽略） */
    public static String ttsAppId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_APPID, "");
        return v == null ? "" : v.trim();
    }
    public static void setTtsAppId(Context ctx, String v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TTS_APPID, v == null ? "" : v.trim()).apply();
    }

    /** MiniMax 的 GroupId（官方要求拼在接口地址末尾，不填无法调用） */
    public static String ttsGroupId(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_GROUPID, "");
        return v == null ? "" : v.trim();
    }

    public static void setTtsGroupId(Context ctx, String s) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TTS_GROUPID, s == null ? "" : s.trim()).apply();
    }

    /** 用户填的模型名；留空表示用预设默认 */
    public static String ttsModel(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_MODEL, "");
        return v == null ? "" : v.trim();
    }
    public static void setTtsModel(Context ctx, String m) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TTS_MODEL, m == null ? "" : m.trim()).apply();
    }

    /** 用户填的音色；留空表示用预设默认 */
    public static String ttsVoice(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_VOICE, "");
        return v == null ? "" : v.trim();
    }
    public static void setTtsVoice(Context ctx, String v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TTS_VOICE, v == null ? "" : v.trim()).apply();
    }

    /** 返回音频格式：mp3 / wav / pcm / ogg_opus / opus / flac */
    public static String ttsFormat(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_FORMAT, "");
        return v == null ? "" : v.trim();
    }
    public static void setTtsFormat(Context ctx, String v) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TTS_FORMAT, v == null ? "" : v.trim()).apply();
    }

    // ———— 音色克隆（仅 OpenAI 兼容协议支持：voice 传空 + references）————

    public static String ttsCloneRefAudio(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_CLONE_REF_AUDIO, "");
        return v == null ? "" : v.trim();
    }
    public static void setTtsCloneRefAudio(Context ctx, String url) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TTS_CLONE_REF_AUDIO, url == null ? "" : url.trim()).apply();
    }

    /** 参考音频里「说的是什么」——克隆必须提供，用于对齐音素 */
    public static String ttsCloneRefText(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_TTS_CLONE_REF_TEXT, "");
        return v == null ? "" : v.trim();
    }
    public static void setTtsCloneRefText(Context ctx, String t) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TTS_CLONE_REF_TEXT, t == null ? "" : t.trim()).apply();
    }

    /** 克隆模式是否已就绪（地址 + 参考文本都填了，且当前协议支持） */
    public static boolean ttsCloneReady(Context ctx) {
        if (!TtsProvider.supportsClone(ttsKind(ctx))) return false;
        return ttsCloneRefAudio(ctx).length() > 0 && ttsCloneRefText(ctx).length() > 0;
    }

    // ———— 实际生效值的解析：用户填了就用，没填就回落到所选预设 ————

    /**
     * 实际使用的接口地址。
     *
     * <p><b>只认「用户填的地址」和「所选服务商的预设地址」两个来源</b>——
     * 绝不拿对话 API 地址去派生（老版本就是这么 404 的）。</p>
     *
     * <p>OpenAI 兼容协议额外做一次友好处理：若填的是「基址」（如
     * {@code https://xxx/v1} 或只有域名），自动补 {@code /audio/speech}；
     * 若看着已经是完整端点（如 {@code https://my.proxy/tts}），原样使用。
     * 拿不准就用「原样直发」预设，那条路径一个字都不改。</p>
     */
    public static String ttsEffectiveUrl(Context ctx) {
        String kind = ttsKind(ctx);
        String custom = ttsCloudUrl(ctx);
        String url;
        if (custom.length() > 0) {
            url = TtsProvider.K_OPENAI.equals(kind) ? maybeAppendSpeech(custom) : custom;
        } else {
            TtsProvider.Entry e = TtsProvider.find(ttsProvider(ctx));
            url = e == null ? "" : e.url;
        }
        return appendGroupId(kind, url, ttsGroupId(ctx));
    }

    /**
     * MiniMax 的 GroupId 拼接 —— <b>已废弃，现在什么都不拼，原样返回 url。</b>
     *
     * <p><b>★ 这里原先拼 {@code ?GroupId=xxx}，注释还写着「官方要求拼在地址末尾，
     * 不拼就必然鉴权失败」—— 那是旧版 API 的用法，现已作废，而且是有害的：</b>
     * 网关上还留着旧分支，URL 里只要出现<em>大小写完全匹配</em>的 {@code GroupId}，
     * 就会切到 group 鉴权，拿新版 Key 去比对一个不存在的分组，
     * 返回 {@code 1004: token not match group}。小写 {@code group_id} 会被忽略。</p>
     *
     * <p>官方现行文档（platform.minimax.cn）里 {@code /v1/t2a_v2}、
     * {@code /v1/files/upload}、{@code /v1/voice_clone} 的示例都只带
     * {@code Authorization: Bearer}，URL 上没有任何查询参数。
     * 所以本函数保留签名只为不动调用点，实际不再追加任何东西。</p>
     *
     * <p>另外：MiniMax 对「鉴权失败」和「GroupId 不匹配」都返回 {@code 1004}，
     * 只能靠 {@code status_msg} 区分 ——
     * {@code token not match group} = URL 里有多余 GroupId；
     * {@code login fail: Please carry the API secret key...} = Authorization 头写错了。</p>
     */
    static String appendGroupId(String kind, String url, String gid) {
        return url;
    }

    /** 只对「像基址」的地址补 /audio/speech，完整端点一律原样保留 */
    static String maybeAppendSpeech(String url) {
        String u = url == null ? "" : url.trim();
        if (u.length() == 0) return u;
        if (u.indexOf("audio/speech") >= 0) return u;
        String noProto = u.replaceFirst("^https?://", "");
        int slash = noProto.indexOf('/');
        boolean baseLike;
        if (slash < 0) {
            baseLike = true;                                  // https://host
        } else {
            String path = noProto.substring(slash);
            baseLike = path.equals("/")
                    || path.matches("/v\\d+/")     || path.matches("/v\\d+")
                    || path.matches("/v\\d+/openai/") || path.matches("/v\\d+/openai");
        }
        if (!baseLike) return u;                              // 看着就是完整端点
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/audio/speech";
    }

    /** 实际使用的 Key：填了专用的就用它，否则复用对话 Key */
    public static String ttsEffectiveKey(Context ctx) {
        String k = ttsCloudKey(ctx);
        return k.length() > 0 ? k : getApiKey(ctx);
    }

    /** 实际使用的模型名：填了就用，没填回落预设默认 */
    public static String ttsModelOrDefault(Context ctx) {
        String m = ttsModel(ctx);
        if (m.length() > 0) return m;
        TtsProvider.Entry e = TtsProvider.findOrDef(ttsProvider(ctx));
        return e.model;
    }

    /** 实际使用的音色 */
    public static String ttsVoiceOrDefault(Context ctx) {
        String v = ttsVoice(ctx);
        if (v.length() > 0) return v;
        TtsProvider.Entry e = TtsProvider.findOrDef(ttsProvider(ctx));
        return e.voice;
    }

    /** 实际使用的返回格式（老版本写死 mp3，现在可配，火山/百炼可能要 wav/pcm） */
    public static String ttsFormatOrDefault(Context ctx) {
        String f = ttsFormat(ctx);
        if (f.length() > 0) return f;
        TtsProvider.Entry e = TtsProvider.findOrDef(ttsProvider(ctx));
        return e.format.length() > 0 ? e.format : "mp3";
    }

    /** 语速，范围 0.5 ~ 2.0（默认 1.0） */
    public static float ttsSpeed(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_TTS_SPEED, 1.0f);
        if (v < 0.5f) v = 0.5f;
        if (v > 2.0f) v = 2.0f;
        return v;
    }
    public static void setTtsSpeed(Context ctx, float v) {
        if (v < 0.5f) v = 0.5f;
        if (v > 2.0f) v = 2.0f;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_TTS_SPEED, v).apply();
    }

    /** 太短的话不念（默认 2 字），避免「哦」「嗯」之类频繁发声 */
    public static int ttsMinChars(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_TTS_MIN_CHARS, 2);
        if (v < 1) v = 1;
        if (v > 20) v = 20;
        return v;
    }
    public static void setTtsMinChars(Context ctx, int v) {
        if (v < 1) v = 1;
        if (v > 20) v = 20;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_TTS_MIN_CHARS, v).apply();
    }

    /** 自检：当前配置是否具备发声条件；返回 null 表示没问题，否则返回原因 */
    public static String ttsCheck(Context ctx) {
        String url = ttsEffectiveUrl(ctx);
        if (url.length() == 0) return "还没填接口地址";
        String kind = ttsKind(ctx);
        if (ttsEffectiveKey(ctx).length() == 0 && !TtsProvider.K_RAW.equals(kind)) {
            return "还没填 API Key";
        }
        if (TtsProvider.K_VOLC.equals(kind) && ttsAppId(ctx).length() == 0) {
            return "火山引擎需要填 AppId";
        }
        return null;
    }
}
