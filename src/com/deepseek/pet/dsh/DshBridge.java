package com.deepseek.pet.dsh;

import android.content.Context;
import android.content.SharedPreferences;

import com.deepseek.pet.ApiClient;
import com.deepseek.pet.DshTaskCoordinator;
import com.deepseek.pet.Prefs;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Android 侧的胶水层 —— 把纯内核接进桌宠这个 App。
 *
 * <h3>这一层只做三件事</h3>
 * <ol>
 *   <li><b>路径</b>：把 App 私有目录算成沙箱根（{@code files/dsh/workspace}）、技能目录、会话目录；</li>
 *   <li><b>设置</b>：开关 / 权限档 / 模型 / 最大步数（独立 pref 文件，不动老的 {@code Prefs} 大文件）；</li>
 *   <li><b>HTTP</b>：把 {@link DshAgentLoop.Llm} 实现成一次 OpenAI 兼容的 {@code /chat/completions} 调用
 *       （复用 {@code ApiClient.chatUrl()} 与用户已经填好的 Key，**不新增第二套联网配置**）。</li>
 * </ol>
 *
 * <p>★ 线程纪律：{@link #chat} 是**同步阻塞**的（要在后台线程调用）。
 * UI 线程上跑它等于自杀 —— 一次对话可能包含好几个 step，每步都是一次网络往返。
 *
 * <p>★ 会话与日志：事件通过 {@link DshSession.Sink} 一边落盘一边回调 UI，
 * 所以进程中途被杀也能从磁盘接着读（这是 harness 与"聊天记录"最大的区别）。
 */
public final class DshBridge {

    private DshBridge() {}

    private static volatile File appFiles;

    public static void init(Context ctx) {
        if (ctx == null) return;
        appFiles = ctx.getApplicationContext().getFilesDir();
    }

    // ==================== 路径 ====================

    /** {@code files/dsh}（工作区与会话的根；给 {@link DshWorkspace} 用）。 */
    public static File baseDir(Context ctx) {
        if (appFiles == null) init(ctx);
        return new File(appFiles, "dsh");
    }

    /** 全部工作区（默认工作区永远第一个）。 */
    public static List<DshWorkspace> workspaces(Context ctx) {
        return DshWorkspace.list(baseDir(ctx));
    }

    /** 当前工作区 id（pref；指向的工作区被删掉时自动回退到默认）。 */
    public static String activeWorkspaceId(Context ctx) {
        String id = prefs(ctx).getString(DshFeature.KEY_WORKSPACE, DshWorkspace.DEFAULT_ID);
        if (id == null) id = DshWorkspace.DEFAULT_ID;
        if (DshWorkspace.find(baseDir(ctx), id) == null) {
            // 目录没了 ⇒ 回默认，别让整个工作台打不开
            prefs(ctx).edit().putString(DshFeature.KEY_WORKSPACE, DshWorkspace.DEFAULT_ID).apply();
            return DshWorkspace.DEFAULT_ID;
        }
        return id;
    }

    public static DshWorkspace activeWorkspace(Context ctx) {
        DshWorkspace w = DshWorkspace.find(baseDir(ctx), activeWorkspaceId(ctx));
        return w == null ? DshWorkspace.defaultWs(baseDir(ctx)) : w;
    }

    /**
     * 切工作区（只写 pref；当前页面自己决定何时重绘）。
     *
     * <p>★ 不在这里取消任务、也不清会话指针：每个工作区各有自己的
     * {@code dsh_session_<id>}，切过去还能接着上次那个会话。
     */
    public static void setWorkspace(Context ctx, String id) {
        DshWorkspace w = DshWorkspace.find(baseDir(ctx), id);
        prefs(ctx).edit().putString(DshFeature.KEY_WORKSPACE,
                w == null ? DshWorkspace.DEFAULT_ID : w.id).apply();
    }

    /** 新建工作区；返回 null 表示名字不合法（原因用 {@link DshWorkspace#validate}）。 */
    public static DshWorkspace createWorkspace(Context ctx, String name) {
        return DshWorkspace.create(baseDir(ctx), name);
    }

    public static File workspaceDir(Context ctx) {
        File f = activeWorkspace(ctx).dir;
        if (!f.isDirectory()) f.mkdirs();
        return f;
    }

    public static File skillsDir(Context ctx) {
        File f = new File(baseDir(ctx), "skills");
        if (!f.isDirectory()) f.mkdirs();
        return f;
    }

    public static File sessionsDir(Context ctx) {
        File f = activeWorkspace(ctx).sessions;
        if (!f.isDirectory()) f.mkdirs();
        return f;
    }

    public static DshSessionLog log(Context ctx) {
        return new DshSessionLog(sessionsDir(ctx));
    }

    public static DshPolicy policy(Context ctx) {
        return new DshPolicy(mode(ctx), workspaceDir(ctx).getAbsolutePath());
    }

    // ==================== 设置 ====================

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(DshFeature.PREF_FILE, Context.MODE_PRIVATE);
    }

    /**
     * 功能是否对用户可见/可用（编译期开关 × 运行时开关）。
     *
     * <p>★★ 运行时开关**默认开**（第五版修）。
     * 原来默认关，而唯一能打开它的开关又藏在工作模式的控制台里 ——
     * 那就是个死锁：**点卡片提示"先打开总开关"，而总开关要进工作模式才能打开**。
     * 用户碰到的就是这个（「无法打开 deepseekharness，点了开关也没用」）。
     * 现在：默认能用；设置页里有一颗**真的开关**可以关掉；且点卡片时若被关掉会**自动开回来**。
     */
    public static boolean enabled(Context ctx) {
        if (!DshFeature.ENABLED) return false;
        return prefs(ctx).getBoolean(DshFeature.KEY_ON, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(DshFeature.KEY_ON, on).apply();
    }

    public static int mode(Context ctx) {
        return prefs(ctx).getInt(DshFeature.KEY_MODE, DshPolicy.WORKSPACE_WRITE);
    }

    public static void setMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(DshFeature.KEY_MODE, DshPolicy.normalize(mode)).apply();
    }

    public static int maxSteps(Context ctx) {
        return prefs(ctx).getInt(DshFeature.KEY_MAX_STEPS, DshFeature.DEFAULT_MAX_STEPS);
    }

    public static void setMaxSteps(Context ctx, int n) {
        prefs(ctx).edit().putInt(DshFeature.KEY_MAX_STEPS, Math.max(1, Math.min(12, n))).apply();
    }

    /** 一个工作区一个会话指针：切工作区再切回来，接的还是上次那个会话。 */
    private static String sessionKey(Context ctx) {
        String id = activeWorkspaceId(ctx);
        return DshFeature.KEY_SESSION + (id.length() == 0 ? "" : "_" + id);
    }

    /** 当前会话 id（没有就新建一个）。 */
    public static String currentSessionId(Context ctx) {
        String key = sessionKey(ctx);
        String id = prefs(ctx).getString(key, "");
        if (id == null || id.length() == 0) {
            id = newSessionId();
            prefs(ctx).edit().putString(key, id).apply();
        }
        return id;
    }

    public static void startNewSession(Context ctx) {
        prefs(ctx).edit().putString(sessionKey(ctx), newSessionId()).apply();
    }

    /** 打开指定会话（左栏点一条会话时用；键跟着当前工作区走）。 */
    public static void useSession(Context ctx, String sessionId) {
        String id = sessionId == null ? "" : sessionId.trim();
        if (id.length() == 0) return;
        prefs(ctx).edit().putString(sessionKey(ctx), id).apply();
    }

    static String newSessionId() {
        // 形如 20261023-021743-ab12（时间在前，文件列表天然按时间排）
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
        String tail = Integer.toHexString((int) (Math.random() * 0xffff) | 0x1000).substring(1);
        return f.format(new java.util.Date()) + "-" + tail;
    }

    // ==================== 技能 ====================

    public static List<DshSkill> skills(Context ctx) {
        return DshSkill.scan(skillsDir(ctx));
    }

    // ==================== 会话 ====================

    /**
     * 打开（或新建）一个会话，并把事件同时写盘 + 回调。
     *
     * @param ui 事件回调（可为 null）；它会在**当前线程**被调用，UI 侧自行 post 到主线程
     */
    public static DshSession openSession(final Context ctx, String sessionId, final DshSession.Sink ui) {
        final DshSessionLog log = log(ctx);
        currentId = (sessionId == null || sessionId.length() == 0) ? currentSessionId(ctx) : sessionId;
        // ★ 写盘用的 id 在**开方法时**就定下来（局部 final），不再读可变全局：
        //   定时任务 / 后台任务会和界面各开各的会话，靠全局变量写盘迟早串。
        final String sid = currentId;
        DshSession.Sink sink = new DshSession.Sink() {
            @Override public void onEvent(DshEvent e) {
                log.append(sid, e);
                if (ui != null) ui.onEvent(e);
            }
        };
        DshSession s = log.open(sid, workspaceDir(ctx).toString(), sink);
        if (s.events.isEmpty()) {
            s.appendSession(0);
            s.appendSandbox(DshPolicy.name(mode(ctx)));
            s.appendApproval("on-request");
        }
        return s;
    }

    /** 最近一个 sink 用到的会话 id（写盘时用；单线程使用，不做并发保护）。 */
    private static volatile String currentId = "";

    // ==================== 一轮对话（同步；必须在后台线程调用） ====================

    /** 回调（UI 展示用）。 */
    public interface Callback {
        /** @param tokens 估算 token 数；@param millis 这一步的用时（桌面端会显示） */
        void onText(String text, int tokens, long millis);
        /** @param millis 这次工具调用的用时 */
        void onTool(String name, String args, boolean isError, String result, long millis);
        void onDone(DshAgentLoop.Result result);
        void onError(String message);
    }

    /**
     * 跑一轮。
     *
     * @param userText 用户这句话
     * @param cb       进度回调（可为 null）
     * @return 结果（含正文/错误/步数）
     */
    public static DshAgentLoop.Result chat(Context ctx, String userText, Callback cb) {
        return chatIn(ctx, userText, null, cb);
    }

    /**
     * 跑一轮 —— 但写进**指定会话**（定时任务 / 后台任务用）。
     *
     * <p>★ 为什么需要它：定时任务有自己的会话（{@code sched-&lt;id&gt;}），
     * 不能因为"用户此刻正在看别的会话"就把定时结果写进用户那条会话里。
     *
     * @param sessionIdOverride 为空 ⇒ 与 {@link #chat} 一样用当前会话
     */
    public static DshAgentLoop.Result chatIn(Context ctx, String userText, String sessionIdOverride,
                                             Callback cb) {
        DshAgentLoop.Result out = new DshAgentLoop.Result();
        if (!enabled(ctx)) {
            out.error = "Harness 未开启";
            return out;
        }
        String key = Prefs.getApiKey(ctx);
        String model = Prefs.getChatModel(ctx);
        if (key == null || key.trim().length() == 0) {
            out.error = "还没填 API Key（设置 → 服务商）";
            return out;
        }
        if (model == null || model.trim().length() == 0) model = "deepseek-chat";

        DshPolicy policy = policy(ctx);
        List<DshSkill> skills = skills(ctx);
        java.util.Set<String> plugins = plugins(ctx);
        DshToolRegistry tools = DshTools.forPlugins(policy, skills, plugins, petStatus(ctx),
                new ScheduleHostImpl(ctx));

        String timeCtx = DshPrompt.timeContext(System.currentTimeMillis());
        String persona = "";
        try {
            persona = Prefs.personaForPet(ctx);
        } catch (Throwable ignore) {
            // 人格读不出来不算错，用中性提示
        }
        String system = (persona == null || persona.trim().length() == 0 ? "" : persona.trim() + "\n\n")
                + DshPrompt.system(timeCtx, policy, tools.specs(), skills)
                + (plugins.isEmpty() ? ""
                    : "\n## Plugins\n\u4ee5\u4e0b\u63d2\u4ef6\u5df2\u542f\u7528\uff08\u5173\u6389\u7684\u63d2\u4ef6\u4f60\u6839\u672c\u770b\u4e0d\u5230\u5b83\u7684\u5de5\u5177\uff09\uff1a\n"
                      + DshPlugin.promptBlock(plugins));

        DshSession session = openSession(ctx, sessionIdOverride, null);
        if (userText != null && userText.trim().length() > 0) {
            session.setTitle(userText.trim());
            session.userMessage(userText.trim());
        }

        final String fModel = model;
        DshAgentLoop.Config cfg = new DshAgentLoop.Config();
        cfg.maxSteps = maxSteps(ctx);
        final Callback c = cb;

        DshAgentLoop loop = new DshAgentLoop(new OpenAiLlm(key, fModel), tools, policy, cfg)
                .listener(new DshAgentLoop.Listener() {
                    @Override public void onAssistantText(String text, int tokens, long millis) {
                        if (c != null) c.onText(text, tokens, millis);
                    }
                    @Override public void onToolCall(String name, String arguments) {
                        // 调用那一半由 onToolResult 统一回调（避免 UI 收到两次）
                    }
                    @Override public void onToolResult(String name, boolean isError, String text, long millis) {
                        if (c != null) c.onTool(name, "", isError, text, millis);
                    }
                    @Override public void onStepStart(int turn, int step) {}
                    @Override public void onError(String message) {
                        if (c != null) c.onError(message);
                    }
                });

        runningLoop = loop;
        try {
            out = loop.run(session, system, fModel);
        } finally {
            runningLoop = null;
        }
        log(ctx).writeMeta(session.id, session);
        if (cb != null) cb.onDone(out);
        return out;
    }

    // ==================== LLM 通道 ====================

    /**
     * OpenAI 兼容的一次调用。
     * <p>★ 复用 {@code ApiClient.chatUrl()}：用户填的是哪家服务商、哪个地址，这里就跟哪家，
     * 不额外引入第二套配置（否则"设置里改了地址但 harness 还在打老地址"就是必然的 bug）。
     */
    static final class OpenAiLlm implements DshAgentLoop.Llm {
        private final String apiKey;
        private final String model;

        OpenAiLlm(String apiKey, String model) {
            this.apiKey = apiKey;
            this.model = model;
        }

        @Override public String complete(String body) throws Exception {
            String url = ApiClient.chatUrl();
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(120000);   // agent loop 的一次回复可能较长
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
                int code = c.getResponseCode();
                InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
                String text = readAll(in);
                if (code < 200 || code >= 300) {
                    // 错误体也交给 DshWire 解（它认得 error.message）
                    if (text != null && text.indexOf("\"error\"") >= 0) return text;
                    throw new java.io.IOException("HTTP " + code + "：" + DshJson.oneLineIfLong(text, 300));
                }
                return text;
            } finally {
                if (c != null) c.disconnect();
            }
        }

        private static String readAll(InputStream in) throws java.io.IOException {
            if (in == null) return "";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    // ==================== 插件（对齐官方「一切皆插件」） ====================

    /** 当前启用的插件集（没设过就是默认全开）。 */
    public static java.util.Set<String> plugins(Context ctx) {
        String raw = prefs(ctx).getString(DshFeature.KEY_PLUGINS, null);
        return DshPlugin.decode(raw);
    }

    public static void setPluginEnabled(Context ctx, String id, boolean on) {
        java.util.Set<String> cur = plugins(ctx);
        if (on) cur.add(id);
        else cur.remove(id);
        prefs(ctx).edit().putString(DshFeature.KEY_PLUGINS, DshPlugin.encode(cur)).apply();
    }

    public static boolean pluginEnabled(Context ctx, String id) {
        return plugins(ctx).contains(id);
    }

    /** 桌宠状态快照（给 pet_status 工具用；只读配置，不发网络）。 */
    public static DshTools.PetStatus petStatus(final Context ctx) {
        return new DshTools.PetStatus() {
            @Override public String snapshot() {
                StringBuilder sb = new StringBuilder();
                try {
                    sb.append("桌宠名字：").append(Prefs.petName(ctx)).append('\n');
                    sb.append("对话服务商：").append(Prefs.getChatProvider(ctx)).append('\n');
                    sb.append("对话模型：").append(Prefs.getChatModel(ctx)).append('\n');
                    sb.append("联网检索：").append(Prefs.webSearch(ctx) ? "开" : "关").append('\n');
                    sb.append("余额气泡：").append(Prefs.balanceBubbleOn(ctx) ? "开" : "关").append('\n');
                    sb.append("App 内隐藏桌宠：").append(Prefs.hidePetInApp(ctx) ? "是" : "否").append('\n');
                    sb.append("工作模式显示桌宠：").append(Prefs.harnessShowPet(ctx) ? "是" : "否").append('\n');
                    java.util.Iterator<String> it = plugins(ctx).iterator();
                    StringBuilder ids = new StringBuilder();
                    while (it.hasNext()) {
                        if (ids.length() > 0) ids.append(',');
                        ids.append(it.next());
                    }
                    sb.append("启用的插件：").append(ids.length() == 0 ? "（无）" : ids);
                } catch (Throwable t) {
                    sb.append("（状态读取失败：").append(t.getClass().getSimpleName()).append("）");
                }
                return sb.toString();
            }
        };
    }

    // ==================== 当前模式（陪伴 / 工作） ====================

    /**
     * 现在是不是工作模式。
     * <p>★ 这个值只用来**画顶部那张卡片的文案**与决定"冷启动落在哪个界面"，
     * 真正的界面切换由 Activity 承担（{@code MainActivity} ↔ {@code DshWorkActivity}）。
     * 不开总开关时永远返回 false —— 没开功能就不该有"工作模式"这个概念。
     */
    public static boolean isWorkMode(Context ctx) {
        if (!enabled(ctx)) return false;
        return prefs(ctx).getBoolean(DshFeature.KEY_WORK_MODE, false);
    }

    /** 长按菜单请求进工作模式的时刻（elapsedRealtime）。 */
    private static volatile long workAskedAt;
    /** DshHarnessActivity 最后一次真正创建的时刻。 */
    private static volatile long workCreatedAt;

    public static void setWorkLaunchAskedAt(long t) { workAskedAt = t; }
    public static long workLaunchAskedAt() { return workAskedAt; }
    public static void markWorkCreated() { workCreatedAt = android.os.SystemClock.elapsedRealtime(); }
    public static long workCreateAt() { return workCreatedAt; }

    /** 宿主是否以 root 身份跑（默认关，需要用户手动打开）。 */
    public static boolean rootHostEnabled(Context ctx) {
        return prefs(ctx).getBoolean(DshFeature.KEY_ROOT_HOST, false);
    }

    /** 设置宿主 root 开关。 */
    public static void setRootHostEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(DshFeature.KEY_ROOT_HOST, on).apply();
    }

    /** 工作模式总开关（默认开）。关了之后顶部入口也进不去。 */
    public static boolean workEnabled(Context ctx) {
        return prefs(ctx).getBoolean(DshFeature.KEY_WORK_ENABLED, true);
    }

    /** 设置工作模式总开关。 */
    public static void setWorkEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(DshFeature.KEY_WORK_ENABLED, on).apply();
    }

    public static void setWorkMode(Context ctx, boolean work) {
        prefs(ctx).edit().putBoolean(DshFeature.KEY_WORK_MODE, work).apply();
    }

    // ==================== 模式切换动画（设置存在同一个 pref 文件里） ====================

    /** 当前选中的切换动画类型（认不出回默认档）。 */
    public static int transitionKind(Context ctx) {
        return DshTransition.normalize(prefs(ctx).getInt(DshTransition.KEY_KIND, DshTransition.FADE));
    }

    public static void setTransitionKind(Context ctx, int kind) {
        prefs(ctx).edit().putInt(DshTransition.KEY_KIND, DshTransition.normalize(kind)).apply();
    }

    /** 内置动画时长（视频那档用它没用 —— 视频按自己的时长走，受上限约束）。 */
    public static int transitionMs(Context ctx) {
        return DshTransition.clampMs(prefs(ctx).getInt(DshTransition.KEY_MS, DshTransition.BUILTIN_MS));
    }

    public static void setTransitionMs(Context ctx, int ms) {
        prefs(ctx).edit().putInt(DshTransition.KEY_MS, DshTransition.clampMs(ms)).apply();
    }

    /** 自定义视频的落点（files/dsh/transition/transition.mp4）。 */
    public static File transitionVideo(Context ctx) {
        File dir = new File(baseDir(ctx), DshTransition.VIDEO_DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, DshTransition.VIDEO_NAME);
    }

    public static boolean hasTransitionVideo(Context ctx) {
        File f = transitionVideo(ctx);
        return f.isFile() && f.length() > 0;
    }

    /**
     * 导入一段切换动画视频（把 SAF 给的 content:// 流式拷进私有目录）。
     *
     * <p>★ 三条限制都是有意的：**只收视频 mime**（拿别的文件当动画只会黑屏）、
     * **上限 60 MB**（切换动画不需要 4K 片源，拷大文件会卡住主线程）、
     * **先写 .part 再改名**（拷一半拔线不会留下一个坏视频，切换时也不用去猜）。
     *
     * @return 失败原因；成功返回 null
     */
    public static String importTransitionVideo(Context ctx, android.net.Uri uri) {
        if (uri == null) return "没有选到文件";
        String mime = "";
        try {
            mime = ctx.getContentResolver().getType(uri);
        } catch (Throwable ignore) {
            // 有些文件管理器的 provider 不报 mime，靠扩展名兜底
        }
        if (mime != null && mime.length() > 0 && !mime.startsWith("video/")) {
            return "只支持视频文件（当前是 " + mime + "）";
        }
        File target = transitionVideo(ctx);
        File tmp = new File(target.getAbsolutePath() + ".part");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return "打不开这个文件";
            out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 60L * 1024 * 1024) {
                    return "文件太大（超过 60 MB）——切换动画建议 2~5 秒的小片段";
                }
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            out = null;
            if (target.exists() && !target.delete()) return "旧文件删不掉（可能正在播放）";
            if (!tmp.renameTo(target)) return "保存失败";
            return null;
        } catch (Throwable t) {
            return "导入失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
        } finally {
            DshFs.closeQuietly(in);
            if (out != null) DshFs.closeQuietly(out);
            if (tmp.exists() && !target.exists()) tmp.delete();
        }
    }

    /** 删掉自定义视频（回到内置动画时不用，但设置页要能"移除"）。 */
    public static boolean removeTransitionVideo(Context ctx) {
        File f = transitionVideo(ctx);
        return f.isFile() && f.delete();
    }

    // ==================== 停止生成 ====================

    /** 当前正在跑的那一轮（界面用「停止生成」按钮掐掉它）。 */
    private static volatile DshAgentLoop runningLoop;

    /** 请求停止当前这一轮；没有在跑就什么也不做。 */
    public static void cancelCurrent() {
        DshAgentLoop l = runningLoop;
        if (l != null) l.cancel();
    }

    public static boolean isRunning() {
        return runningLoop != null;
    }

    // ==================== 提醒工具的宿主（上游 tool-schedule 的接线） ====================

    /**
     * 让模型用的四个 {@code schedule_*} 工具落到本机的定时任务上。
     *
     * <p>★ 纯类 {@link DshTools} 不认识 Android：落盘 + 挂闹钟都在这一层。
     * 更新时传回来的是 {@code list()} 给出去的那个对象，所以 **id 与投递记录天然保留**
     * （这正是上游 schedule_update 的语义）。
     */
    static final class ScheduleHostImpl implements DshTools.ScheduleHost {
        private final Context ctx;

        ScheduleHostImpl(Context c) {
            this.ctx = c.getApplicationContext();
        }

        @Override public List<DshSchedule> list() {
            return schedules(ctx);
        }

        @Override public DshSchedule create(DshSchedule s) {
            return saveSchedule(ctx, s) ? s : null;
        }

        @Override public DshSchedule update(String id, DshSchedule next) {
            if (next == null) return null;
            return saveSchedule(ctx, next) ? next : null;
        }

        @Override public boolean delete(String id) {
            return deleteSchedule(ctx, id);
        }

        @Override public long now() {
            return System.currentTimeMillis();
        }
    }

    // ==================== 会话列表（左栏工作区树用） ====================

    /**
     * 某个工作区里的最近会话（最多 n 条），每条
     * {@code {id, 标题, 事件数, 最后修改毫秒}}。
     *
     * <p>标题来自 {@code session/title} 事件；老会话没有这条事件，就退回第一条用户消息
     * （再没有就用 id）—— 用户看到的是「继续我们的项目」而不是「20261021-021743-ab12」。
     */
    public static List<String[]> sessionsIn(Context ctx, DshWorkspace ws, int n) {
        List<String[]> out = new ArrayList<String[]>();
        if (ws == null) return out;
        DshSessionLog log = new DshSessionLog(ws.sessions);
        List<String> ids = log.listIds();
        for (int i = 0; i < ids.size() && out.size() < Math.max(1, n); i++) {
            String id = ids.get(i);
            List<DshEvent> ev = log.load(id);
            out.add(new String[]{id, titleOf(id, ev), String.valueOf(ev.size()),
                    String.valueOf(log.fileFor(id).lastModified())});
        }
        return out;
    }

    /** 当前工作区的最近会话。 */
    public static List<String[]> recentSessions(Context ctx, int n) {
        return sessionsIn(ctx, activeWorkspace(ctx), n);
    }

    /** 某个工作区里有几个会话（左栏折叠着的工作区只显示条数，不加载事件）。 */
    public static int sessionCount(Context ctx, DshWorkspace ws) {
        if (ws == null) return 0;
        return new DshSessionLog(ws.sessions).listIds().size();
    }

    // ==================== 任务与定时任务（作业页用） ====================

    /** 任务落盘目录（{@code files/dsh/tasks}）。 */
    public static File tasksDir(Context ctx) {
        File f = new File(baseDir(ctx), "tasks");
        if (!f.isDirectory()) f.mkdirs();
        return f;
    }

    /** 任务存储（作业页列历史任务用；任务本体的生命周期归协调器）。 */
    public static DshTaskStore taskStore(Context ctx) {
        return new DshTaskStore(tasksDir(ctx));
    }

    /** 定时任务目录（{@code files/dsh/schedules}）。 */
    public static File schedulesDir(Context ctx) {
        File f = new File(baseDir(ctx), "schedules");
        if (!f.isDirectory()) f.mkdirs();
        return f;
    }

    public static DshScheduleStore scheduleStore(Context ctx) {
        return new DshScheduleStore(schedulesDir(ctx));
    }

    public static List<DshSchedule> schedules(Context ctx) {
        return scheduleStore(ctx).list();
    }

    /** 保存定时任务并**顺手把闹钟挂上**（关掉的会被撤掉）。 */
    public static boolean saveSchedule(Context ctx, DshSchedule s) {
        if (s == null) return false;
        boolean ok = scheduleStore(ctx).save(s);
        if (ok) DshScheduleRunner.register(ctx, s);
        return ok;
    }

    /** 删除定时任务并撤掉闹钟（定时任务的会话记录不删 —— 那是历史）。 */
    public static boolean deleteSchedule(Context ctx, String id) {
        if (id == null || id.length() == 0) return false;
        DshScheduleRunner.cancel(ctx, id);
        return scheduleStore(ctx).delete(id);
    }

    /** 手动触发一次（「立即跑一次」用；记一条投递，但**不改**下次时点）。 */
    public static boolean runScheduleNow(Context ctx, DshSchedule s) {
        if (s == null) return false;
        long now = System.currentTimeMillis();
        s.recordDelivery(now, now);
        scheduleStore(ctx).save(s);
        return DshTaskCoordinator.get(ctx).submit(s.prompt, s.sessionId());
    }

    private static String titleOf(String id, List<DshEvent> ev) {
        String firstUser = "";
        if (ev != null) {
            for (DshEvent e : ev) {
                if (DshEvent.SESSION_TITLE.equals(e.type)) {
                    Object t = e.data.get("title");
                    if (t != null && String.valueOf(t).trim().length() > 0) {
                        return DshJson.oneLineIfLong(String.valueOf(t).trim(), 40);
                    }
                }
                if (firstUser.length() == 0 && DshEvent.USER_MESSAGE.equals(e.type)) {
                    firstUser = DshJson.oneLineIfLong(textOfContent(e.data.get("content")), 28);
                }
            }
        }
        if (firstUser != null && firstUser.trim().length() > 0) return firstUser.trim();
        return "会话 " + id;
    }

    /** 把 {@code content}（字符串或 parts 数组）拼成一行纯文本。 */
    private static String textOfContent(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (!(content instanceof List)) return String.valueOf(content);
        StringBuilder sb = new StringBuilder();
        for (Object o : (List<?>) content) {
            if (o instanceof java.util.Map) {
                Object t = ((java.util.Map<?, ?>) o).get("text");
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }
}
