import com.deepseek.pet.dsh.DshAgentLoop;
import com.deepseek.pet.dsh.DshCompaction;
import com.deepseek.pet.dsh.DshEvent;
import com.deepseek.pet.dsh.DshFs;
import com.deepseek.pet.dsh.DshJson;
import com.deepseek.pet.dsh.DshMeter;
import com.deepseek.pet.dsh.DshPolicy;
import com.deepseek.pet.dsh.DshPrompt;
import com.deepseek.pet.dsh.DshSession;
import com.deepseek.pet.dsh.DshSessionLog;
import com.deepseek.pet.dsh.DshSkill;
import com.deepseek.pet.dsh.DshTool;
import com.deepseek.pet.dsh.DshToolRegistry;
import com.deepseek.pet.dsh.DshToolSpec;
import com.deepseek.pet.dsh.DshTools;
import com.deepseek.pet.dsh.DshWire;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * DSH 内核的离线断言（纯 Java，真编真跑，无 Android、无网络）。
 *
 * <p>覆盖的是**行为**，不是"源码里有没有这句话"：
 * 事件日志能不能回放、投影出来的消息对不对、工具越权能不能挡住、
 * loop 的五条分支（无工具/单工具/工具报错/超步数/模型异常）是不是都走对了。
 *
 * <p>跑法：{@code cd build\dshtest && run.bat}（或见 run_all_tests.bat 的第 74 套）。
 */
public final class DshCoreTest {

    private static int ok = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== DSH 内核断言（纯 Java，无 Android）===");
        testJson();
        testPolicy();
        testTools();
        testSkills();
        testSessionAndProjection();
        testWire();
        testMeterAndCompaction();
        testLog();
        testLoop();
        testTransition();
        testPlugins();
        testTask();
        testCommandAndMention();
        testWorkspace();
        testSchedule();
        testScheduleTools();

        System.out.println();
        if (fail == 0) {
            System.out.println("DSH-CORE OK (" + ok + " 项)");
        } else {
            System.out.println("DSH-CORE FAILED (ok=" + ok + ", fail=" + fail + ")");
            System.exit(1);
        }
    }

    // ==================== 断言框架 ====================

    private static void check(String label, boolean cond) {
        if (cond) {
            ok++;
            System.out.println("  [OK]   " + label);
        } else {
            fail++;
            System.out.println("  [FAIL] " + label);
        }
    }

    private static void eq(String label, Object expected, Object actual) {
        boolean same = (expected == null) ? actual == null : expected.equals(actual);
        if (!same) {
            System.out.println("  [FAIL] " + label + " :: 期望=" + expected + " 实际=" + actual);
            fail++;
        } else {
            ok++;
            System.out.println("  [OK]   " + label + " :: " + actual);
        }
    }

    /** 重复字符串（不用 {@code String.repeat}：那是 JDK 11 的 API，本工程的语法基线是 Java 8）。 */
    private static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static File tmpDir(String tag) throws Exception {        File d = java.nio.file.Files.createTempDirectory("dshtest_" + tag).toFile();
        d.deleteOnExit();
        return d;
    }

    // ==================== 12. 任务状态机与恢复（方案 Phase 1） ====================

    private static void testTask() throws Exception {
        System.out.println("\n[12] 任务状态机与进程重启恢复");
        com.deepseek.pet.dsh.DshTask t = new com.deepseek.pet.dsh.DshTask("t1", "s1", "你好", 1000L);
        eq("新建是 CREATED", com.deepseek.pet.dsh.DshTask.CREATED, t.state);
        check("CREATED 是活动态、不是终态", t.active() && !t.terminal());

        t.setState(com.deepseek.pet.dsh.DshTask.RUNNING);
        eq("状态名可往返", "RUNNING", com.deepseek.pet.dsh.DshTask.name(t.state));
        check("SUCCEEDED 是终态", !new com.deepseek.pet.dsh.DshTask("x", "", "", 1L).active()
                || com.deepseek.pet.dsh.DshTask.name(com.deepseek.pet.dsh.DshTask.SUCCEEDED).equals("SUCCEEDED"));
        com.deepseek.pet.dsh.DshTask done = new com.deepseek.pet.dsh.DshTask("t2", "s", "", 1L);
        done.setState(com.deepseek.pet.dsh.DshTask.SUCCEEDED);
        check("SUCCEEDED 确实是终态", done.terminal() && !done.active());
        eq("不认识的状态名 ⇒ CREATED", com.deepseek.pet.dsh.DshTask.CREATED,
                com.deepseek.pet.dsh.DshTask.parse("WHATEVER"));
        com.deepseek.pet.dsh.DshTask weird = new com.deepseek.pet.dsh.DshTask("t3", "", "", 1L);
        weird.setState(99);
        eq("状态归一：超界值 ⇒ CREATED", com.deepseek.pet.dsh.DshTask.CREATED, weird.state);
        eq("有人话标签", "状态待确认",
                labelOf(com.deepseek.pet.dsh.DshTask.UNKNOWN_AFTER_DISCONNECT));

        // ★★ 进程重启：未终结的任务必须变「状态待确认」，且**不自动重跑**
        File dir = tmpDir("tasks");
        com.deepseek.pet.dsh.DshTaskStore store = new com.deepseek.pet.dsh.DshTaskStore(dir);
        com.deepseek.pet.dsh.DshTask running = new com.deepseek.pet.dsh.DshTask("t-run", "s", "跑一个有副作用的任务", 5L);
        running.setState(com.deepseek.pet.dsh.DshTask.RUNNING);
        check("任务能落盘", store.save(running));
        check("latest 指向它", "t-run".equals(store.latestId()));

        com.deepseek.pet.dsh.DshTaskStore store2 = new com.deepseek.pet.dsh.DshTaskStore(dir);
        com.deepseek.pet.dsh.DshTask back = store2.recover();
        check("重启后能读回这个任务", back != null && "t-run".equals(back.id));
        eq("★★ 未终结 ⇒ UNKNOWN_AFTER_DISCONNECT（不装懂）",
                com.deepseek.pet.dsh.DshTask.UNKNOWN_AFTER_DISCONNECT, back.state);
        check("带一句可解释的原因", back.error.length() > 0 && back.error.contains("不会自动重跑"));
        check("★ 恢复不会把它变回 RUNNING（防重跑）",
                back.state != com.deepseek.pet.dsh.DshTask.RUNNING);

        // 已终结的任务不被改
        com.deepseek.pet.dsh.DshTask okTask = new com.deepseek.pet.dsh.DshTask("t-ok", "s", "", 6L);
        okTask.setState(com.deepseek.pet.dsh.DshTask.SUCCEEDED);
        store2.save(okTask);
        eq("已完成的任务叟旧", com.deepseek.pet.dsh.DshTask.SUCCEEDED,
                store2.recover().state);
    }

    /** 便利：取一个状态的标签（不建对象）。 */
    private static String labelOf(int state) {
        com.deepseek.pet.dsh.DshTask t = new com.deepseek.pet.dsh.DshTask("z", "", "", 1L);
        t.state = state;
        return t.label();
    }

    // ==================== 13. `/` 命令与 @引用（方案 Phase 2） ====================

    private static void testCommandAndMention() throws Exception {
        System.out.println("\n[13] / 命令与 @file / @conv");

        com.deepseek.pet.dsh.DshCommand.Parsed help = com.deepseek.pet.dsh.DshCommand.parse("/help");
        check("/help 能解析且可执行", help.isCommand && help.ok());
        eq("命令名", "help", help.name);

        com.deepseek.pet.dsh.DshCommand.Parsed perm = com.deepseek.pet.dsh.DshCommand.parse("/perm  full ");
        check("参数会被归一（trim + 小写）", perm.ok() && "full".equals(perm.args));

        com.deepseek.pet.dsh.DshCommand.Parsed badPerm = com.deepseek.pet.dsh.DshCommand.parse("/perm root");
        check("参数不对要报错，不能当没写", badPerm.error.length() > 0);

        com.deepseek.pet.dsh.DshCommand.Parsed unknown = com.deepseek.pet.dsh.DshCommand.parse("/nope");
        check("未知命令要明说", unknown.error.contains("未知命令"));

        com.deepseek.pet.dsh.DshCommand.Parsed plan = com.deepseek.pet.dsh.DshCommand.parse("/plan");
        check("★ 未支持的命令不能假装执行", !plan.ok() && plan.error.contains("还没实现"));

        check("普通消息不当命令", !com.deepseek.pet.dsh.DshCommand.parse("你好").isCommand);
        check("带空格开头的斜杠不当命令",
                !com.deepseek.pet.dsh.DshCommand.looksLikeCommand(" /help"));
        check("/help 正文列出了未支持项",
                com.deepseek.pet.dsh.DshCommand.helpText().contains("未支持"));

        // @file / @conv
        com.deepseek.pet.dsh.DshMention.Parsed m = com.deepseek.pet.dsh.DshMention.parse(
                "看一下 @file:notes.md 和 @conv:20261023-1");
        eq("文件引用数", 1, m.files.size());
        eq("会话引用数", 1, m.conversations.size());
        check("正文里引用记号被剔掉", !m.clean.contains("@file:"));
        eq("裸写当文件", 1, com.deepseek.pet.dsh.DshMention.parse("@notes.md").files.size());
        eq("邮箱不当引用", 0, com.deepseek.pet.dsh.DshMention.parse("a@b.com").files.size());
        check("@file: 后面空着要报错",
                com.deepseek.pet.dsh.DshMention.parse("@file:").error.length() > 0);

        // 校验：存在 / 目录 / 越界
        File root = tmpDir("mention");
        com.deepseek.pet.dsh.DshPolicy pol = new com.deepseek.pet.dsh.DshPolicy(
                com.deepseek.pet.dsh.DshPolicy.WORKSPACE_WRITE, root.getAbsolutePath());
        com.deepseek.pet.dsh.DshFs.writeText(new File(root, "notes.md"), "一两三");
        String[] err = new String[1];
        java.util.List<com.deepseek.pet.dsh.DshMention.Ref> refs = com.deepseek.pet.dsh.DshMention.resolveFiles(
                pol, java.util.Arrays.asList("notes.md"), err);
        eq("真实文件能读到", 1, refs.size());
        check("内容对得上", refs.get(0).content.contains("一两三"));

        err[0] = "";
        eq("不存在的文件不读", 0, com.deepseek.pet.dsh.DshMention.resolveFiles(
                pol, java.util.Arrays.asList("nope.md"), err).size());
        check("且报“不存在”", err[0] != null && err[0].contains("不存在"));

        err[0] = "";
        eq("★ 越界路径被拒", 0, com.deepseek.pet.dsh.DshMention.resolveFiles(
                pol, java.util.Arrays.asList("../../../etc/passwd"), err).size());
        check("且报越界", err[0] != null && err[0].contains("越界"));

        // 注入块：有标签、有上限
        String block = com.deepseek.pet.dsh.DshMention.contextBlock(refs, null, err);
        check("注入块带 <file path=...>", block.contains("<file path=\"notes.md\">"));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 4000; i++) huge.append("一二三四五六七八九十");
        java.util.List<com.deepseek.pet.dsh.DshMention.Ref> big = new java.util.ArrayList<com.deepseek.pet.dsh.DshMention.Ref>();
        big.add(new com.deepseek.pet.dsh.DshMention.Ref("big.md", huge.toString()));
        err[0] = "";
        eq("★ 超长注入被拒（不静默截断）", "",
                com.deepseek.pet.dsh.DshMention.contextBlock(big, null, err));
        check("且给出原因", err[0] != null && err[0].contains("太长"));
    }

    // ==================== 11. 插件（官方：一切皆插件） ====================

    private static void testPlugins() throws Exception {
        System.out.println("\n[11] 插件系统（官方「一切皆插件」）");
        eq("内置插件数（多了「提醒」）", 8, com.deepseek.pet.dsh.DshPlugin.all().size());
        eq("默认全开", 8, com.deepseek.pet.dsh.DshPlugin.defaults().size());

        // 三种"没设过 / 全关 / 部分开"必须区分得开
        eq("pref 没设过 ⇒ 默认全开", 8, com.deepseek.pet.dsh.DshPlugin.decode(null).size());
        eq("空串 ⇒ 一个都不开", 0, com.deepseek.pet.dsh.DshPlugin.decode("").size());
        eq("认不出的 id 被丢弃", 1,
                com.deepseek.pet.dsh.DshPlugin.decode("core.web,nope").size());
        check("编码按内置顺序且可往返",
                com.deepseek.pet.dsh.DshPlugin.encode(
                        com.deepseek.pet.dsh.DshPlugin.decode("core.web,core.files"))
                        .equals("core.files,core.web"));

        eq("工具归属：web_fetch 属于网页插件",
                com.deepseek.pet.dsh.DshPlugin.P_WEB,
                com.deepseek.pet.dsh.DshPlugin.pluginOfTool("web_fetch"));
        check("未知工具没有归属",
                com.deepseek.pet.dsh.DshPlugin.pluginOfTool("bash") == null);

        // ★ 最关键的语义：关掉的插件，它的工具**不在可见列表里**
        java.util.Set<String> onlyFiles = new java.util.LinkedHashSet<String>();
        onlyFiles.add(com.deepseek.pet.dsh.DshPlugin.P_FILES);
        eq("只开「文件」⇒ 4 个工具（含 stat）",
                4, com.deepseek.pet.dsh.DshPlugin.visibleTools(onlyFiles).size());
        check("关掉网页插件 ⇒ web_fetch 不可见",
                !com.deepseek.pet.dsh.DshPlugin.visibleTools(onlyFiles).contains("web_fetch"));
        check("默认下 pet_status 可见",
                com.deepseek.pet.dsh.DshPlugin.visibleTools(
                        com.deepseek.pet.dsh.DshPlugin.defaults()).contains("pet_status"));

        // 提示块：只列启用的
        String block = com.deepseek.pet.dsh.DshPlugin.promptBlock(onlyFiles);
        check("提示块只含启用插件", block.contains("文件：") && !block.contains("网页："));

        // 真组装：关掉的插件不进注册表
        File root = tmpDir("plugins");
        com.deepseek.pet.dsh.DshPolicy pol = new com.deepseek.pet.dsh.DshPolicy(
                com.deepseek.pet.dsh.DshPolicy.WORKSPACE_WRITE, root.getAbsolutePath());
        java.util.Set<String> clockOnly = new java.util.LinkedHashSet<String>();
        clockOnly.add(com.deepseek.pet.dsh.DshPlugin.P_CLOCK);
        com.deepseek.pet.dsh.DshToolRegistry reg = com.deepseek.pet.dsh.DshTools.forPlugins(
                pol, new ArrayList<com.deepseek.pet.dsh.DshSkill>(), clockOnly, null);
        eq("只开「时间」⇒ 注册表只有 1 个工具", 1, reg.size());
        check("注册表里的那个就是 now", reg.has("now"));

        com.deepseek.pet.dsh.DshToolRegistry all = com.deepseek.pet.dsh.DshTools.forPlugins(
                pol, new ArrayList<com.deepseek.pet.dsh.DshSkill>(),
                com.deepseek.pet.dsh.DshPlugin.defaults(),
                new com.deepseek.pet.dsh.DshTools.PetStatus() {
                    @Override public String snapshot() { return "snapshot-ok"; }
                });
        eq("全开⇒ 10 个工具（新增 search/stat）", 10, all.size());
        // ★ 新增工具的真行为（子 agent 交付的，这里钉住）
        com.deepseek.pet.dsh.DshFs.writeText(new java.io.File(root, "note.md"), "第一行 hello\n第二行 world\n");
        com.deepseek.pet.dsh.DshToolRegistry.Result hit = all.execute("search",
                "{\"pattern\":\"hello\"}", pol);
        check("search 命中且带文件名与行号",
                !hit.isError && hit.text.contains("note.md") && hit.text.contains(":1:"));
        com.deepseek.pet.dsh.DshToolRegistry.Result miss = all.execute("search",
                "{\"pattern\":\"nothing-here-zzz\"}", pol);
        check("search 无命中说清楚", !miss.isError && miss.text.contains("没有匹配"));
        com.deepseek.pet.dsh.DshToolRegistry.Result badre = all.execute("search",
                "{\"pattern\":\"(\"}", pol);
        check("search 正则写错是工具错误（不崩）",
                badre.isError && badre.text.contains("正则"));
        com.deepseek.pet.dsh.DshToolRegistry.Result st = all.execute("stat",
                "{\"path\":\"note.md\"}", pol);
        check("stat 能报文件类型与行数",
                !st.isError && st.text.contains("file") && st.text.contains("行数"));
        com.deepseek.pet.dsh.DshToolRegistry.Result stMiss = all.execute("stat",
                "{\"path\":\"nope.txt\"}", pol);
        check("stat 不存在时明确报错", stMiss.isError && stMiss.text.contains("不存在"));
        // 权限档：默认档下这两个只读工具必须放行
        check("★ 默认档（workspace-write）下 search/stat 不被拦",
                pol.allows("search") && pol.allows("stat"));

        com.deepseek.pet.dsh.DshToolRegistry.Result pet = all.execute("pet_status", "{}", pol);
        check("pet_status 真的能读到岛上状态（注入实现被调到）",
                !pet.isError && pet.text.contains("snapshot-ok"));
    }

    // ==================== 10. 模式切换动画（参数表） ====================

    private static void testTransition() {
        System.out.println("\n[10] 模式切换动画（类型表 / 时长 / 方向）");
        eq("四种类型齐全", 4, com.deepseek.pet.dsh.DshTransition.KINDS.length);
        eq("默认档是淡入淡出", com.deepseek.pet.dsh.DshTransition.FADE,
                com.deepseek.pet.dsh.DshTransition.normalize(99));
        check("认不出的值不算合法", !com.deepseek.pet.dsh.DshTransition.isValid(42));
        check("VIDEO 合法", com.deepseek.pet.dsh.DshTransition.isValid(
                com.deepseek.pet.dsh.DshTransition.VIDEO));
        check("每种都有非空文案", !com.deepseek.pet.dsh.DshTransition.label(
                com.deepseek.pet.dsh.DshTransition.ZOOM).isEmpty()
                && !com.deepseek.pet.dsh.DshTransition.describe(
                com.deepseek.pet.dsh.DshTransition.SLIDE).isEmpty());
        eq("时长下限夹到 120", 120, com.deepseek.pet.dsh.DshTransition.clampMs(5));
        eq("时长上限夹到 800", 800, com.deepseek.pet.dsh.DshTransition.clampMs(99999));
        eq("非法时长回默认", com.deepseek.pet.dsh.DshTransition.BUILTIN_MS,
                com.deepseek.pet.dsh.DshTransition.clampMs(0));
        eq("进工作模式方向 = +1", 1, com.deepseek.pet.dsh.DshTransition.direction(true));
        eq("回陪伴模式方向 = -1", -1, com.deepseek.pet.dsh.DshTransition.direction(false));
        check("announce 说的是人话（含方向与动画名）",
                com.deepseek.pet.dsh.DshTransition.announce(true,
                        com.deepseek.pet.dsh.DshTransition.VIDEO).contains("进入工作模式")
                        && com.deepseek.pet.dsh.DshTransition.announce(false,
                        com.deepseek.pet.dsh.DshTransition.FADE).contains("回到陪伴模式"));
        check("视频上限是有限的正数（不许无限等）",
                com.deepseek.pet.dsh.DshTransition.VIDEO_MAX_MS > 0
                        && com.deepseek.pet.dsh.DshTransition.VIDEO_MAX_MS <= 5000);
    }

    // ==================== 1. JSON ====================

    private static void testJson() {
        System.out.println("\n[1] DshJson 写入与转义");
        eq("字符串转义", "\"a\\\"b\\\\c\\nd\"", DshJson.write("a\"b\\c\nd"));
        eq("控制字符走 \\u00XX", "\"\\u0001\"", DshJson.write("\u0001"));
        eq("整数不带小数点", "42", DshJson.write(Integer.valueOf(42)));
        eq("浮点去尾零", "0.5", DshJson.write(Double.valueOf(0.5)));
        eq("布尔", "true", DshJson.write(Boolean.TRUE));
        eq("null", "null", DshJson.write(null));
        Map<String, Object> m = DshJson.map("b", Integer.valueOf(2), "a", "x");
        eq("map 保持插入顺序", "{\"b\":2,\"a\":\"x\"}", DshJson.write(m));
        eq("list", "[1,\"two\"]", DshJson.write(DshJson.list(Integer.valueOf(1), "two")));
        check("超长截断带省略号", DshJson.oneLineIfLong(rep("x", 200), 50).endsWith("…"));
    }

    // ==================== 2. 权限/沙箱 ====================

    private static void testPolicy() {
        System.out.println("\n[2] DshPolicy 权限档与路径沙箱");
        DshPolicy p = new DshPolicy(DshPolicy.WORKSPACE_WRITE, "/data/user/0/com.deepseek.pet/files/dsh");
        eq("档名往返", "workspace-write", DshPolicy.name(DshPolicy.parse("workspace-write")));
        eq("认不出的档退回可写（保守）", "workspace-write", DshPolicy.name(DshPolicy.parse("whatever")));
        check("只读档不许 write", !new DshPolicy(DshPolicy.READ_ONLY, "/r").allows("write"));
        check("只读档允许 read", new DshPolicy(DshPolicy.READ_ONLY, "/r").allows("read"));
        check("可写档允许 write", p.allows("write"));
        check("未知工具按需最高档（默认拒绝）", !new DshPolicy(DshPolicy.WORKSPACE_WRITE, "/r").allows("bash"));
        try {
            eq("相对路径解析", "/data/user/0/com.deepseek.pet/files/dsh/notes/a.txt",
                    p.resolve("notes/a.txt"));
            eq("点斜杠归一", "/data/user/0/com.deepseek.pet/files/dsh/notes/a.txt",
                    p.resolve("./notes//a.txt"));
            eq("沙箱内 .. 归一", "/data/user/0/com.deepseek.pet/files/dsh/a.txt",
                    p.resolve("notes/../a.txt"));
        } catch (DshTool.ToolException e) {
            check("常规路径不该抛：" + e.getMessage(), false);
        }
        boolean blocked = false;
        try {
            p.resolve("../../databases/prefs.xml");
        } catch (DshTool.ToolException e) {
            blocked = true;
        }
        check("★ 路径穿越被挡住（../../）", blocked);
        boolean blockedAbs = false;
        try {
            p.resolve("/sdcard/DCIM/x.jpg");
        } catch (DshTool.ToolException e) {
            blockedAbs = true;
        }
        check("★ 沙箱外的绝对路径被挡住", blockedAbs);
        boolean emptyBlocked = false;
        try {
            p.resolve("   ");
        } catch (DshTool.ToolException e) {
            emptyBlocked = true;
        }
        check("空路径被挡住", emptyBlocked);
        eq("display 去掉前缀", "notes/a.txt", p.display("/data/user/0/com.deepseek.pet/files/dsh/notes/a.txt"));
    }

    // ==================== 3. 工具与注册表 ====================

    private static void testTools() throws Exception {
        System.out.println("\n[3] 工具注册表 / 权限闸 / 参数解析");
        File root = tmpDir("tools");
        DshPolicy p = new DshPolicy(DshPolicy.WORKSPACE_WRITE, root.getAbsolutePath());
        DshToolRegistry reg = DshTools.standard(p, new ArrayList<DshSkill>());
        check("标准套件注册了 7 个工具", reg.size() == 7);
        check("工具名可查", reg.has("read") && reg.has("write") && reg.has("web_fetch"));

        DshToolRegistry.Result unknown = reg.execute("nope", "{}", p);
        check("未知工具是错误结果（不抛）", unknown.isError && unknown.text.contains("未知工具"));

        DshToolRegistry.Result badArgs = reg.execute("read", "{not json", p);
        check("坏参数是错误结果", badArgs.isError && badArgs.text.contains("JSON"));

        DshToolRegistry.Result missing = reg.execute("read", "{}", p);
        check("缺必填参数说清楚", missing.isError && missing.text.contains("缺少参数 path"));

        DshToolRegistry.Result w = reg.execute("write", "{\"path\":\"notes/a.txt\",\"content\":\"你好\\n世界\"}", p);
        check("写文件成功", !w.isError);
        DshToolRegistry.Result r = reg.execute("read", "{\"path\":\"notes/a.txt\"}", p);
        check("读回来带行号", !r.isError && r.text.contains("1: 你好") && r.text.contains("2: 世界"));
        DshToolRegistry.Result l = reg.execute("list", "{}", p);
        check("列目录能看到 notes", !l.isError && l.text.contains("[dir]  notes"));

        DshPolicy ro = new DshPolicy(DshPolicy.READ_ONLY, root.getAbsolutePath());
        DshToolRegistry.Result denied = reg.execute("write", "{\"path\":\"b.txt\",\"content\":\"x\"}", ro);
        check("★ 只读档拒绝写，且说明当前档", denied.isError
                && denied.text.contains("权限不足") && denied.text.contains("read-only"));

        DshToolRegistry.Result traversal = reg.execute("read", "{\"path\":\"../../../etc/passwd\"}", p);
        check("★ 越界读被挡住", traversal.isError && traversal.text.contains("越界"));

        DshToolRegistry.Result todoAdd = reg.execute("todo", "{\"action\":\"add\",\"text\":\"买牛奶\"}", p);
        DshToolRegistry.Result todoList = reg.execute("todo", "{\"action\":\"list\"}", p);
        check("todo 落盘并读回", !todoAdd.isError && todoList.text.contains("买牛奶"));
        check("todo.json 真的写到了沙箱", new File(root, "todo.json").isFile());

        String htmlText = DshTools.htmlToText("<h1>标题</h1><script>x</script><p>正文</p><b>加粗</b>");
        check("HTML→文本：去标签留正文", !htmlText.contains("<") && htmlText.contains("标题")
                && htmlText.contains("正文") && htmlText.contains("加粗"));
        check("HTML→文本：块级标签换行", htmlText.indexOf("标题") < htmlText.indexOf("正文"));
        check("HTML→文本：script 内容被丢掉", !htmlText.contains("x"));
        eq("HTML 实体解码", "a<b>&c", DshTools.htmlToText("a&lt;b&gt;&amp;c"));
        eq("整数参数容忍字符串", 7, DshToolRegistry.num(DshJson.map("n", "7"), "n", 0));
    }

    // ==================== 4. 技能 ====================

    private static void testSkills() throws Exception {
        System.out.println("\n[4] 技能（SKILL.md 解析与注入块）");
        File root = tmpDir("skills");
        File d = new File(root, "android-dev");
        check("建目录", d.mkdirs());
        String md = "---\n"
                + "name: android-dev\n"
                + "description: Build Android apps. Use when the task mentions APK.\n"
                + "---\n\n"
                + "# Android\n\n第一步：跑 discover.ps1\n";
        DshFs.writeText(new File(d, "SKILL.md"), md);
        List<DshSkill> skills = DshSkill.scan(root);
        eq("扫到 1 个技能", 1, skills.size());
        DshSkill s = skills.get(0);
        eq("name 解析", "android-dev", s.name);
        check("description 解析", s.description.startsWith("Build Android apps"));
        check("正文不含 front-matter", s.loadBody().startsWith("# Android") && !s.loadBody().contains("name:"));
        eq("按名字查找", "android-dev", DshSkill.find(skills, "ANDROID-DEV").name);

        String block = DshSkill.promptBlock(skills);
        check("注入块含 system-reminder", block.startsWith("<system-reminder>"));
        check("注入块含技能名与描述", block.contains("`android-dev`: Build Android apps"));
        check("注入块明确说正文未加载", block.contains("NOT loaded"));
        eq("无技能时注入块为空", "", DshSkill.promptBlock(new ArrayList<DshSkill>()));
    }

    // ==================== 5. 会话与投影 ====================

    private static void testSessionAndProjection() {
        System.out.println("\n[5] 会话事件与投影");
        DshSession s = new DshSession("s1", "", 0, "手机沙箱");
        s.appendSession(0);
        s.appendSandbox("workspace-write");
        s.startTurn();
        s.userMessage("帮我看下 notes/a.txt");
        s.startStep();
        DshWire.Call c = new DshWire.Call("call_1", "read", "{\"path\":\"notes/a.txt\"}");
        s.assistantMessage("我看一下。", "", Arrays.asList(c));
        s.toolCall(c);
        s.toolResult(c, "1: 你好\n2: 世界", false);
        s.endStep();
        s.startStep();
        s.assistantMessage("文件里写着「你好 世界」。", "", new ArrayList<DshWire.Call>());
        s.endStep();
        s.endTurn();

        eq("事件数（session/sandbox/turn/step×2/msg×3/tool×2/end×3）", 13, s.events.size());
        eq("首事件是 session", "session", s.events.get(0).type);
        eq("seq 连续（最后一条 = 条数-1）", 12L, s.events.get(12).seq);

        List<DshWire.Msg> msgs = s.project("SYSTEM");
        eq("投影条数（system+user+assistant+tool+assistant）", 5, msgs.size());
        eq("第一条是 system", "system", msgs.get(0).role);
        eq("system 用传入的提示", "SYSTEM", msgs.get(0).text);
        eq("第二条是 user", "user", msgs.get(1).role);
        eq("assistant 带 1 个 tool-call", 1, msgs.get(2).calls.size());
        eq("tool-call 名字", "read", msgs.get(2).calls.get(0).name);
        eq("tool 结果消息的 callId", "call_1", msgs.get(3).toolCallId);
        check("tool 结果正文还原", msgs.get(3).text.contains("世界"));
        eq("最后一条 assistant 正文", "文件里写着「你好 世界」。", s.lastAssistantText());
    }

    // ==================== 6. 协议编解码 ====================

    private static void testWire() {
        System.out.println("\n[6] DshWire 请求编码 / 响应解码");
        DshWire.Msg sys = DshWire.Msg.system("SYS");
        DshWire.Msg usr = DshWire.Msg.user("你好");
        DshWire.Msg asst = DshWire.Msg.assistant("");
        asst.calls.add(new DshWire.Call("c1", "read", "{\"path\":\"a.txt\"}"));
        DshWire.Msg tool = DshWire.Msg.tool("c1", "read", "内容");

        List<DshToolSpec> tools = Arrays.asList(new DshToolSpec("read", "读文件",
                DshToolSpec.params("path", "string", "路径", "limit?", "integer", "行数")));
        String body = DshWire.requestBody("deepseek-chat", Arrays.asList(sys, usr, asst, tool),
                tools, 1024, 0.3);
        check("请求体带模型名", body.contains("\"model\":\"deepseek-chat\""));
        check("请求体带 tools", body.contains("\"tools\":[{\"type\":\"function\""));
        check("请求体带 tool_choice", body.contains("\"tool_choice\":\"auto\""));
        check("请求体带 max_tokens 与 temperature", body.contains("\"max_tokens\":1024")
                && body.contains("\"temperature\":0.3"));
        check("assistant 的 tool_calls 编码", body.contains("\"tool_calls\":[{\"id\":\"c1\""));
        check("tool 消息带 tool_call_id", body.contains("\"tool_call_id\":\"c1\""));
        check("required 里只有必填项", body.contains("\"required\":[\"path\"]"));
        check("空 tools 时不带 tools 字段",
                !DshWire.requestBody("m", Arrays.asList(usr), new ArrayList<DshToolSpec>(), 0, -1)
                        .contains("\"tools\""));

        DshWire.Reply r = DshWire.parseReply("{\"choices\":[{\"finish_reason\":\"tool_calls\","
                + "\"message\":{\"content\":null,\"reasoning_content\":\"想想\","
                + "\"tool_calls\":[{\"id\":\"c9\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5}}");
        check("正常响应解析成功", r.ok);
        eq("思维链单独取", "想想", r.reasoning);
        eq("正文为空", "", r.text);
        eq("工具调用 1 个", 1, r.calls.size());
        eq("工具名", "read", r.calls.get(0).name);
        eq("调用 id", "c9", r.calls.get(0).id);
        eq("prompt token", 11, r.promptTokens);

        DshWire.Reply err = DshWire.parseReply("{\"error\":{\"message\":\"Insufficient Balance\"}}");
        check("错误体识别", !err.ok && err.error.contains("Insufficient Balance"));
        DshWire.Reply notJson = DshWire.parseReply("<html>502</html>");
        check("非 JSON 响应不抛", !notJson.ok && notJson.error.contains("不是 JSON"));
        DshWire.Reply objArgs = DshWire.parseReply("{\"choices\":[{\"message\":{\"content\":\"\","
                + "\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"t\",\"arguments\":{\"a\":1}}}]}}]}");
        check("★ arguments 给对象时也能收下", objArgs.ok && objArgs.calls.get(0).arguments.contains("\"a\":1"));
        DshWire.Reply unnamed = DshWire.parseReply("{\"choices\":[{\"message\":{\"content\":\"hi\","
                + "\"tool_calls\":[{\"id\":\"c\",\"function\":{\"arguments\":\"{}\"}}]}}]}");
        check("无名工具调用被丢弃（不炸）", unnamed.ok && unnamed.calls.isEmpty());
    }

    // ==================== 7. 计量与压缩 ====================

    private static void testMeterAndCompaction() {
        System.out.println("\n[7] token 计量与上下文压缩");
        eq("中文 1 token/字", 4, DshMeter.estimate("你好世界"));
        eq("英文约 4 字符/token", 2, DshMeter.estimate("abcdefgh"));
        eq("空串 0", 0, DshMeter.estimate(""));
        check("工具声明也算 token", DshMeter.estimateTools(Arrays.asList(
                new DshToolSpec("x", "描述", DshToolSpec.params("a", "string", "说明")))) > 0);
        int budget = DshMeter.budget(32000, 2048);
        check("预算 = 窗口×70% − 预留", budget == (int) Math.floor(32000 * 0.70) - 2048);

        // ① 剪工具结果
        List<DshWire.Msg> msgs = new ArrayList<DshWire.Msg>();
        msgs.add(DshWire.Msg.system("s"));
        msgs.add(DshWire.Msg.user("u"));
        String huge = new String(new char[8000]).replace('\0', 'a');
        msgs.add(DshWire.Msg.tool("c1", "read", huge));
        DshCompaction.Report rep = DshCompaction.compact(msgs, 1200);
        check("超预算时剪工具结果", rep.prunedTools >= 1);
        check("剪后正文显著变短", msgs.get(2).text.length() < 2500);
        check("剪后仍标明省略了多少", msgs.get(2).text.contains("此处省略"));
        check("剪后落在预算内（或已尽力）", rep.afterTokens <= rep.beforeTokens);

        // ② 丢最老回合块，且不拆散 tool_calls 与 tool 结果
        // ★★ 每个 assistant 故意带 **两个** tool-call（两条 tool 结果）——
        //    轮次79 自查时发现：一个 assistant 只带 1 个 call 的话，
        //    "块边界算错"恰好也能保持配对数量相等（丢掉 1 个 call + 1 个结果，看起来还是配对的），
        //    这条断言于是变成空转。多一个 call，边界一错立刻露馅。
        List<DshWire.Msg> m2 = new ArrayList<DshWire.Msg>();
        m2.add(DshWire.Msg.system("s"));
        for (int i = 0; i < 6; i++) {
            DshWire.Msg a = DshWire.Msg.assistant("a" + i);
            a.calls.add(new DshWire.Call("c" + i + "a", "read", "{}"));
            a.calls.add(new DshWire.Call("c" + i + "b", "list", "{}"));
            m2.add(DshWire.Msg.user("u" + i));
            m2.add(a);
            m2.add(DshWire.Msg.tool("c" + i + "a", "read", "r" + i + new String(new char[400]).replace('\0', 'x')));
            m2.add(DshWire.Msg.tool("c" + i + "b", "list", "R" + i + new String(new char[400]).replace('\0', 'y')));
        }
        DshCompaction.Report rep2 = DshCompaction.compact(m2, 700);
        check("丢掉了最老的消息", rep2.droppedMsgs > 0);
        check("插入了压缩说明", m2.get(1).text.contains("上下文压缩"));
        int callsLeft = 0;
        int toolsLeft = 0;
        for (DshWire.Msg m : m2) {
            if ("assistant".equals(m.role)) callsLeft += m.calls.size();
            if ("tool".equals(m.role)) toolsLeft++;
        }
        eq("★ tool_calls 与 tool 结果数量仍成对", callsLeft, toolsLeft);

        // ③ 不超预算时一个字都不动
        List<DshWire.Msg> m3 = new ArrayList<DshWire.Msg>();
        m3.add(DshWire.Msg.user("短"));
        DshCompaction.Report rep3 = DshCompaction.compact(m3, 99999);
        check("预算充足时零改动", !rep3.changed());
    }

    // ==================== 8. 会话日志 ====================

    private static void testLog() throws Exception {
        System.out.println("\n[8] 会话日志（追加 / 回放 / 坏行容忍）");
        File dir = tmpDir("log");
        DshSessionLog log = new DshSessionLog(dir);
        final List<DshEvent> pushed = new ArrayList<DshEvent>();
        DshSession s = log.open("sess-1", "手机沙箱", new DshSession.Sink() {
            @Override public void onEvent(DshEvent e) { pushed.add(e); }
        });
        s.appendSession(0);
        s.startTurn();
        s.userMessage("hi");
        s.startStep();
        s.assistantMessage("hello", "", new ArrayList<DshWire.Call>());
        s.endStep();
        s.endTurn();
        // 手工把事件写盘（Sink 只负责推，不负责写 —— 由 Bridge 决定）
        for (DshEvent e : pushed) log.append("sess-1", e);
        eq("事件都推给了 Sink", 7, pushed.size());
        check("日志文件已生成", log.fileFor("sess-1").isFile());

        List<DshEvent> loaded = log.load("sess-1");
        eq("回放事件数", 7, loaded.size());
        eq("回放第一条类型", "session", loaded.get(0).type);
        eq("回放保留 data", "hi", ((Map<?, ?>) ((List<?>) loaded.get(2).data.get("content")).get(0)).get("text"));

        DshSessionLog log2 = new DshSessionLog(dir);
        DshSession reopened = log2.open("sess-1", "手机沙箱", null);
        eq("重开会话事件数一致", 7, reopened.events.size());
        check("重开后 seq 会接着往上编", reopened.append("x", DshJson.map()).seq > 6);

        // 坏行（进程被杀留下的半行）不该毁掉整条会话
        try {
            DshFs.appendLine(log.fileFor("sess-1"), "{\"type\":\"user/message\",\"seq\":99");
        } catch (java.io.IOException e) {
            check("追加坏行失败：" + e.getMessage(), false);
        }
        eq("★ 坏行被跳过，其余事件仍可读", 7, log.load("sess-1").size());

        eq("会话 id 文件名安全化", "a_b", DshSessionLog.safeName("a/b"));
        eq("空 id 兜底", "unnamed", DshSessionLog.safeName("  "));
        check("列表里能看到会话", log.listIds().contains("sess-1"));
        log.writeMeta("sess-1", s);
        check("meta.json 落盘", log.metaFor("sess-1").isFile());
    }

    // ==================== 9. agent loop ====================

    /** 假模型：按顺序吐预置响应，并记下收到的请求体。 */
    private static final class FakeLlm implements DshAgentLoop.Llm {
        final List<String> replies;
        final List<String> requests = new ArrayList<String>();
        int i;
        boolean throwInstead;

        FakeLlm(String... replies) { this.replies = Arrays.asList(replies); }

        @Override public String complete(String req) throws Exception {
            requests.add(req);
            if (throwInstead) throw new java.io.IOException("网络断了");
            String r = replies.get(Math.min(i, replies.size() - 1));
            i++;
            return r;
        }
    }

    private static String replyText(String text) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + DshJson.write(text) + "}}]}";
    }

    private static String replyCall(String id, String name, String argsJson) {
        return "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":" + DshJson.write(argsJson) + "}}]}}]}";
    }

    private static void testLoop() throws Exception {
        System.out.println("\n[9] agent loop 五条分支");
        File root = tmpDir("loop");
        DshPolicy p = new DshPolicy(DshPolicy.WORKSPACE_WRITE, root.getAbsolutePath());
        DshToolRegistry reg = DshTools.standard(p, new ArrayList<DshSkill>());
        DshAgentLoop.Config cfg = new DshAgentLoop.Config();
        cfg.maxSteps = 4;

        // ① 无工具调用：一句话就收工
        FakeLlm llm1 = new FakeLlm(replyText("你好呀"));
        DshSession s1 = new DshSession("l1", "", 0, root.getAbsolutePath());
        DshAgentLoop.Result r1 = new DshAgentLoop(llm1, reg, p, cfg).run(s1, "SYS", "m");
        check("① 无工具：成功", r1.ok && "你好呀".equals(r1.text));
        eq("① 步数 = 1", 1, r1.steps);
        check("① 事件里有 turn/start 与 turn/end",
                s1.events.get(0).type.equals("turn/start")
                        && s1.events.get(s1.events.size() - 1).type.equals("turn/end"));
        check("① 请求体带 tools", llm1.requests.get(0).contains("\"tools\""));
        check("① 请求体带 system 提示", llm1.requests.get(0).contains("SYS"));

        // ② 一次工具调用 → 再问一次 → 收工
        FakeLlm llm2 = new FakeLlm(
                replyCall("c1", "write", "{\"path\":\"note.txt\",\"content\":\"记住这件事\"}"),
                replyText("记好了"));
        DshSession s2 = new DshSession("l2", "", 0, root.getAbsolutePath());
        DshAgentLoop.Result r2 = new DshAgentLoop(llm2, reg, p, cfg).run(s2, "SYS", "m");
        check("② 两步后成功", r2.ok && "记好了".equals(r2.text));
        eq("② 步数 = 2", 2, r2.steps);
        check("② 文件真的写出来了", new File(root, "note.txt").isFile());
        boolean sawToolResult = false;
        for (DshEvent e : s2.events) if (DshEvent.TOOL_RESULT.equals(e.type)) sawToolResult = true;
        check("② 工具结果落盘成事件", sawToolResult);
        check("② 第二次请求带上了工具结果", llm2.requests.get(1).contains("tool_call_id"));

        // ③ 工具报错 → 模型看到错误后改口（不是整轮失败）
        FakeLlm llm3 = new FakeLlm(
                replyCall("c2", "read", "{\"path\":\"不存在.txt\"}"),
                replyText("那个文件不存在"));
        DshSession s3 = new DshSession("l3", "", 0, root.getAbsolutePath());
        DshAgentLoop.Result r3 = new DshAgentLoop(llm3, reg, p, cfg).run(s3, "SYS", "m");
        check("③ 工具报错不毁掉整轮", r3.ok && r3.text.contains("不存在"));
        check("③ 第二次请求里带的是错误正文", llm3.requests.get(1).contains("工具报错"));

        // ④ 一直调工具 → 撞步数上限
        FakeLlm llm4 = new FakeLlm(replyCall("cx", "list", "{}"));
        DshSession s4 = new DshSession("l4", "", 0, root.getAbsolutePath());
        DshAgentLoop.Result r4 = new DshAgentLoop(llm4, reg, p, cfg).run(s4, "SYS", "m");
        check("④ 撞上限：不算成功", !r4.ok && r4.stoppedByLimit);
        eq("④ 步数 = maxSteps", 4, r4.steps);
        check("④ 上限原因写清楚", r4.error.contains("最大步数"));

        // ⑤ 模型调用直接抛异常
        FakeLlm llm5 = new FakeLlm(replyText("x"));
        llm5.throwInstead = true;
        DshSession s5 = new DshSession("l5", "", 0, root.getAbsolutePath());
        DshAgentLoop.Result r5 = new DshAgentLoop(llm5, reg, p, cfg).run(s5, "SYS", "m");
        check("⑤ 异常被兜住并给出原因", !r5.ok && r5.error.contains("模型调用失败"));
        boolean sawError = false;
        for (DshEvent e : s5.events) if (DshEvent.ERROR.equals(e.type)) sawError = true;
        check("⑤ 失败也落了 error 事件", sawError);
        eq("⑤ 失败路径也补了 turn/end", "turn/end", s5.events.get(s5.events.size() - 1).type);

        // ⑥ 只读档下模型想写 → 权限闸拦住，模型收到"权限不足"
        FakeLlm llm6 = new FakeLlm(
                replyCall("c3", "write", "{\"path\":\"x.txt\",\"content\":\"1\"}"),
                replyText("我没有写权限"));
        DshPolicy ro = new DshPolicy(DshPolicy.READ_ONLY, root.getAbsolutePath());
        DshSession s6 = new DshSession("l6", "", 0, root.getAbsolutePath());
        new DshAgentLoop(llm6, reg, ro, cfg).run(s6, "SYS", "m");
        check("⑥ 只读档下第二次请求里带权限不足", llm6.requests.get(1).contains("权限不足"));
        check("⑥ 文件确实没被写出来", !new File(root, "x.txt").exists());

        // ⑦ system 提示里带上技能清单
        File skillsRoot = tmpDir("loops");
        File sd = new File(skillsRoot, "demo");
        sd.mkdirs();
        DshFs.writeText(new File(sd, "SKILL.md"), "---\nname: demo\ndescription: 演示技能\n---\n正文\n");
        List<DshSkill> sk = DshSkill.scan(skillsRoot);
        String prompt = DshPrompt.system("2026-10-23 上午", p, reg.specs(), sk);
        check("⑦ 提示里有人格铁律", prompt.contains("Never invent"));
        check("⑦ 提示里有工作区与权限档", prompt.contains(p.root) && prompt.contains("workspace-write"));
        check("⑦ 提示里有技能清单", prompt.contains("`demo`: 演示技能"));
        check("⑦ 提示里列了工具名", prompt.contains("`web_fetch`"));
        check("⑦ 只读档的说明措辞不同",
                DshPrompt.system("", new DshPolicy(DshPolicy.READ_ONLY, "/r"), reg.specs(), sk)
                        .contains("cannot write"));
    }

    // ==================== 14. 工作区（多工作区 + 会话标题 + 相对时间） ====================

    private static void testWorkspace() throws Exception {
        System.out.println("\n[14] 工作区（老路径兼容 / 名字校验 / 会话标题 / 相对时间）");
        File base = tmpDir("ws");

        // ① 默认工作区必须还是 v7.6 的老路径 —— 升级后老会话一条不丢
        com.deepseek.pet.dsh.DshWorkspace def = com.deepseek.pet.dsh.DshWorkspace.list(base).get(0);
        eq("默认工作区 id = 空串", "", def.id);
        eq("默认工作区显示名 = DS", "DS", def.name);
        eq("默认目录 = base/workspace", new File(base, "workspace").getAbsolutePath(),
                def.dir.getAbsolutePath());
        eq("默认会话目录 = base/sessions（老路径）", new File(base, "sessions").getAbsolutePath(),
                def.sessions.getAbsolutePath());
        check("默认工作区 isDefault", def.isDefault());

        // ② 名字校验：所有能变成"路径"的输入都要被挡住
        check("空名字被拒绝", com.deepseek.pet.dsh.DshWorkspace.validate(base, "  ") != null);
        check("超长名字被拒绝", com.deepseek.pet.dsh.DshWorkspace.validate(base, rep("名", 25)) != null);
        check("斜杠被拒绝", com.deepseek.pet.dsh.DshWorkspace.validate(base, "a/b") != null);
        check("反斜杠被拒绝", com.deepseek.pet.dsh.DshWorkspace.validate(base, "a\\b") != null);
        check("点点被拒绝（路径穿越）", com.deepseek.pet.dsh.DshWorkspace.validate(base, "..") != null);
        check("点开头被拒绝（隐藏目录）", com.deepseek.pet.dsh.DshWorkspace.validate(base, ".secret") != null);
        check("冒号被拒绝", com.deepseek.pet.dsh.DshWorkspace.validate(base, "C:") != null);

        // ③ 新建：中文名字、独立目录、独立会话目录
        com.deepseek.pet.dsh.DshWorkspace created =
                com.deepseek.pet.dsh.DshWorkspace.create(base, "资料 库");
        check("中文+空格的名字能建出来", created != null);
        check("目录真的建出来了", created != null && created.dir.isDirectory());
        check("显示名存在 name.txt（不是靠目录名）",
                created != null && "资料 库".equals(
                        com.deepseek.pet.dsh.DshWorkspace.readName(created.dir, "")));
        check("目录名是安全 slug（不会带空格/中文）",
                created != null && created.dir.getName().matches("[a-z0-9-]+"));
        check("会话目录与默认分开（多工作区不串会话）",
                created != null
                        && !created.sessions.getAbsolutePath().equals(def.sessions.getAbsolutePath()));
        check("会话目录已建好", created != null && created.sessions.isDirectory());
        eq("现在有两个工作区", 2, com.deepseek.pet.dsh.DshWorkspace.list(base).size());
        check("重名被拒绝", com.deepseek.pet.dsh.DshWorkspace.validate(base, "资料 库") != null);
        check("和默认工作区同名也被拒绝",
                com.deepseek.pet.dsh.DshWorkspace.validate(base, "DS") != null);
        check("按 id 找得回来",
                created != null && com.deepseek.pet.dsh.DshWorkspace.find(base, created.id) != null);
        check("找不到的 id 返回 null（调用方回默认）",
                com.deepseek.pet.dsh.DshWorkspace.find(base, "nope") == null);

        // ④ 会话标题：第一条用户消息（只记一次，不覆盖）
        DshSession s = new DshSession("t", "", 0, "");
        eq("起标题前是空串", "", s.title);
        s.setTitle("继续我们的项目");
        eq("标题 = 第一条用户消息", "继续我们的项目", s.title);
        eq("标题落成 session/title 事件", "session/title", s.events.get(0).type);
        s.setTitle("第二条不该改名");
        eq("第二次不覆盖标题", "继续我们的项目", s.title);
        eq("标题事件只有一条", 1, s.events.size());
        DshSession longTitle = new DshSession("t2", "", 0, "");
        longTitle.setTitle(rep("很长的话", 20));
        check("超长标题被截断", longTitle.title.length() <= 29);

        // ⑤ 相对时间（固定 now，不依赖当前时钟；不用等一天再跑测试）
        long now = 1792121864363L;
        eq("10 秒 ⇒ 刚刚", "刚刚",
                com.deepseek.pet.dsh.DshWorkspace.relativeTime(now, now - 10000L));
        eq("5 分钟", "5分钟",
                com.deepseek.pet.dsh.DshWorkspace.relativeTime(now, now - 5 * 60000L));
        eq("59 分钟", "59分钟",
                com.deepseek.pet.dsh.DshWorkspace.relativeTime(now, now - 59 * 60000L));
        eq("7 小时（参考图里的那档）", "7小时",
                com.deepseek.pet.dsh.DshWorkspace.relativeTime(now, now - 7 * 3600000L));
        eq("1 天", "1天",
                com.deepseek.pet.dsh.DshWorkspace.relativeTime(now, now - 25 * 3600000L));
        eq("3 天", "3天",
                com.deepseek.pet.dsh.DshWorkspace.relativeTime(now, now - 3 * 86400000L));
        eq("2 个月", "2个月",
                com.deepseek.pet.dsh.DshWorkspace.relativeTime(now, now - 61L * 86400000L));
        eq("时钟回拨不显示负数", "刚刚",
                com.deepseek.pet.dsh.DshWorkspace.relativeTime(now, now + 3600000L));
        eq("没时间就不显示", "",
                com.deepseek.pet.dsh.DshWorkspace.relativeTime(now, 0));
    }

    // ==================== 15. 定时任务（六种规则 / 时区 / 夏令时 / 落盘） ====================

    private static void testSchedule() throws Exception {
        System.out.println("\n[15] 定时任务（六种规则 / 校验 / 夏令时 / 投递记录 / 落盘）");

        // ① 常量与校验（与仓库 master 对齐：MIN_EVERY_INTERVAL_SECONDS = 60）
        eq("最小固定间隔 60 秒", 60L, com.deepseek.pet.dsh.DshSchedule.MIN_EVERY_SECONDS);
        eq("标题上限 120", 120, com.deepseek.pet.dsh.DshSchedule.MAX_TITLE);
        check("59 秒被拒", com.deepseek.pet.dsh.DshSchedule.validateEvery(59L) != null);
        check("60 秒可以", com.deepseek.pet.dsh.DshSchedule.validateEvery(60L) == null);
        check("空标题被拒", com.deepseek.pet.dsh.DshSchedule.validateTitle("  ") != null);
        check("超长标题被拒",
                com.deepseek.pet.dsh.DshSchedule.validateTitle(rep("x", 121)) != null);
        check("空指令被拒", com.deepseek.pet.dsh.DshSchedule.validatePrompt(" ") != null);

        // ② 钟点 / 星期 / 时区
        eq("8:5 规范成 08:05:00", "08:05:00",
                com.deepseek.pet.dsh.DshSchedule.normalizeClock("8:5"));
        eq("23:59 原样", "23:59:00", com.deepseek.pet.dsh.DshSchedule.normalizeClock("23:59"));
        check("24:00 非法", com.deepseek.pet.dsh.DshSchedule.normalizeClock("24:00") == null);
        check("乱写非法", com.deepseek.pet.dsh.DshSchedule.normalizeClock("早上八点") == null);
        int[] wd = com.deepseek.pet.dsh.DshSchedule.normalizeWeekdays(new int[]{5, 1, 5, 9, 0});
        eq("星期去重升序且丢掉越界", 2, wd.length);
        eq("第一个是周一", 1, wd[0]);
        eq("第二个是周五", 5, wd[1]);
        check("认得的时区", com.deepseek.pet.dsh.DshSchedule.validateTimeZone("Asia/Shanghai") == null);
        check("空时区被拒", com.deepseek.pet.dsh.DshSchedule.validateTimeZone("") != null);

        // ③ every：与创建时间对齐的固定格子（上游口径）
        long t0 = 1792815747971L;
        com.deepseek.pet.dsh.DshSchedule ev = new com.deepseek.pet.dsh.DshSchedule(
                "s1", "每 5 分钟", "跑一次", "every", t0);
        ev.everySeconds = 300L;
        eq("刚建：下一次 = 创建 + 一个间隔", t0 + 300_000L, ev.nextAfter(t0));
        eq("刚过 1 秒：还是那个格子", t0 + 300_000L, ev.nextAfter(t0 + 1000L));
        eq("正好到点：再加一格（不立刻重跑）", t0 + 600_000L, ev.nextAfter(t0 + 300_000L));
        eq("跨三格：对齐到下一格（不追补）", t0 + 1_500_000L, ev.nextAfter(t0 + 1_200_000L));
        eq("每 5 分钟的说法", "每 5 分钟", ev.frequencyLabel());

        // ④ daily：按 IANA 时区的墙上时间
        long base = 1792815747971L;   // 2026-10-24 04:22:27 UTC
        long daily = com.deepseek.pet.dsh.DshSchedule.nextWallClock(
                base, "09:00", "Asia/Shanghai", null);
        java.util.Calendar dc = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        dc.setTimeInMillis(daily);
        eq("daily 落在 09:00", 9, dc.get(java.util.Calendar.HOUR_OF_DAY));
        eq("daily 落在 0 分", 0, dc.get(java.util.Calendar.MINUTE));
        check("daily 严格晚于 now", daily > base);

        // ⑤ 夏令时：春季不存在的钟点跳过、秋季重叠取较早（America/New_York）
        java.util.TimeZone ny = java.util.TimeZone.getTimeZone("America/New_York");
        java.util.Calendar gapStart = java.util.Calendar.getInstance(ny);
        gapStart.clear();
        gapStart.set(2026, java.util.Calendar.MARCH, 8, 0, 0, 0);   // 2026-03-08 是跳表日
        long gap = com.deepseek.pet.dsh.DshSchedule.nextWallClock(
                gapStart.getTimeInMillis(), "02:30", "America/New_York", null);
        java.util.Calendar gc = java.util.Calendar.getInstance(ny);
        gc.setTimeInMillis(gap);
        check("★ 不存在的 02:30 被跳过（结果不是 3/8）",
                gc.get(java.util.Calendar.DAY_OF_MONTH) != 8
                        && gc.get(java.util.Calendar.HOUR_OF_DAY) == 2);
        eq("第二天仍是 02:30", 30, gc.get(java.util.Calendar.MINUTE));

        java.util.Calendar ovStart = java.util.Calendar.getInstance(ny);
        ovStart.clear();
        ovStart.set(2026, java.util.Calendar.NOVEMBER, 1, 0, 0, 0);  // 2026-11-01 是回拨日
        long ov = com.deepseek.pet.dsh.DshSchedule.nextWallClock(
                ovStart.getTimeInMillis(), "01:30", "America/New_York", null);
        java.util.Calendar oc = java.util.Calendar.getInstance(ny);
        oc.setTimeInMillis(ov);
        eq("重叠日仍是 01:30", 1, oc.get(java.util.Calendar.HOUR_OF_DAY));
        eq("分钟 30", 30, oc.get(java.util.Calendar.MINUTE));
        int off = oc.get(java.util.Calendar.ZONE_OFFSET) + oc.get(java.util.Calendar.DST_OFFSET);
        eq("★ 重叠取较早的那次（-4 小时 = 夏令时仍在）", -4 * 3600 * 1000, off);

        // ⑥ weekly：只落在选中的星期
        long weekly = com.deepseek.pet.dsh.DshSchedule.nextWallClock(
                base, "08:30", "Asia/Shanghai", new int[]{1});
        java.util.Calendar wc = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        wc.setTimeInMillis(weekly);
        eq("weekly 落在周一", java.util.Calendar.MONDAY,
                wc.get(java.util.Calendar.DAY_OF_WEEK));
        eq("weekly 08:30", 8, wc.get(java.util.Calendar.HOUR_OF_DAY));

        // ⑦ cron：五段解析与匹配
        check("全星号能解析",
                com.deepseek.pet.dsh.DshSchedule.Cron.parse("* * * * *") != null);
        check("五段才认", com.deepseek.pet.dsh.DshSchedule.Cron.parse("* * * *") == null);
        check("分钟 61 越界", com.deepseek.pet.dsh.DshSchedule.Cron.parse("61 * * * *") == null);
        check("步长 0 非法", com.deepseek.pet.dsh.DshSchedule.Cron.parse("*/0 * * * *") == null);
        check("范围非法", com.deepseek.pet.dsh.DshSchedule.Cron.parse("30-10 * * * *") == null);
        long cronNext = com.deepseek.pet.dsh.DshSchedule.nextCron(
                base, "0 9 * * 1-5", "Asia/Shanghai");
        java.util.Calendar cc = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        cc.setTimeInMillis(cronNext);
        eq("cron 命中 09:00", 9, cc.get(java.util.Calendar.HOUR_OF_DAY));
        eq("cron 命中 0 秒/0 分", 0, cc.get(java.util.Calendar.MINUTE));
        int dow = cc.get(java.util.Calendar.DAY_OF_WEEK);
        check("cron 命中工作日", dow != java.util.Calendar.SATURDAY
                && dow != java.util.Calendar.SUNDAY);

        // ⑧ 一次性：过去就不再排
        com.deepseek.pet.dsh.DshSchedule once = new com.deepseek.pet.dsh.DshSchedule(
                "s2", "一次", "跑", "after", t0);
        once.atMillis = t0 + 60_000L;
        eq("未到点 ⇒ 就是那个时刻", t0 + 60_000L, once.nextAfter(t0));
        eq("已过点 ⇒ 0（不再排）", 0L, once.nextAfter(t0 + 120_000L));

        // ⑨ 投递记录：有界
        com.deepseek.pet.dsh.DshSchedule hist = new com.deepseek.pet.dsh.DshSchedule(
                "s3", "历史", "跑", "every", t0);
        hist.everySeconds = 60L;
        for (int i = 0; i < 30; i++) hist.recordDelivery(t0 + i * 1000L, t0 + i * 1000L);
        eq("最多留 20 条", com.deepseek.pet.dsh.DshSchedule.MAX_DELIVERY, hist.deliveries.size());
        eq("lastRunAt 跟着投递走", t0 + 29 * 1000L, hist.lastRunAt);

        // ⑩ 落盘往返 + 老数据兼容
        File dir = tmpDir("sched");
        com.deepseek.pet.dsh.DshScheduleStore store =
                new com.deepseek.pet.dsh.DshScheduleStore(dir);
        hist.nextRunAt = hist.nextAfter(t0 + 30_000L);
        check("能存", store.save(hist));
        com.deepseek.pet.dsh.DshSchedule back = store.load("s3");
        check("能读回来", back != null);
        eq("kind 不丢", "every", back.kind);
        eq("标题不丢", "历史", back.title);
        eq("投递记录不丢", 20, back.deliveries.size());
        eq("下次时间不丢", hist.nextRunAt, back.nextRunAt);

        DshFs.writeText(new File(dir, "legacy.json"),
                "{\"id\":\"legacy\",\"prompt\":\"老的每五分钟\",\"everySeconds\":300,"
                        + "\"enabled\":true,\"createdAt\":1792815747971}");
        com.deepseek.pet.dsh.DshSchedule legacy = store.load("legacy");
        check("第十五版的 every 记录还能读", legacy != null);
        eq("老记录缺 kind ⇒ every", "every", legacy.kind);
        eq("老记录标题由指令派生", "老的每五分钟", legacy.title);

        DshFs.writeText(new File(dir, "broken.json"), "{not json");
        eq("坏文件跳过", 2, store.count());
    }

    // ==================== 16. 提醒工具（上游 tool-schedule 的四个动作） ====================

    /** 假宿主：只把对象放在内存里（纯 Java，不碰文件与闹钟）。 */
    static final class FakeScheduleHost implements com.deepseek.pet.dsh.DshTools.ScheduleHost {
        final List<com.deepseek.pet.dsh.DshSchedule> items =
                new java.util.ArrayList<com.deepseek.pet.dsh.DshSchedule>();
        long now = 1792815747971L;

        @Override public List<com.deepseek.pet.dsh.DshSchedule> list() { return items; }

        @Override public com.deepseek.pet.dsh.DshSchedule create(com.deepseek.pet.dsh.DshSchedule s) {
            items.add(s);
            return s;
        }

        @Override public com.deepseek.pet.dsh.DshSchedule update(String id,
                com.deepseek.pet.dsh.DshSchedule next) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(id)) { items.set(i, next); return next; }
            }
            return null;
        }

        @Override public boolean delete(String id) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(id)) { items.remove(i); return true; }
            }
            return false;
        }

        @Override public long now() { return now; }
    }

    private static void testScheduleTools() throws Exception {
        System.out.println("\n[16] 提醒工具（schedule_create / list / update / delete）");
        FakeScheduleHost host = new FakeScheduleHost();
        List<com.deepseek.pet.dsh.DshTool> tools = com.deepseek.pet.dsh.DshTools.scheduleTools(host);
        eq("四个工具", 4, tools.size());
        eq("名字与上游一致", "schedule_create",
                tools.get(0).spec().name);

        Map<String, Object> a = DshJson.map("title", "早上看天气", "prompt", "查一下今天天气",
                "kind", "every", "everySeconds", 300);
        String r = tools.get(0).run(a);
        check("create 能建（every）", r.startsWith("已创建提醒") && host.items.size() == 1);
        eq("落的是 every", "every", host.items.get(0).kind);
        eq("下一次 = now + 300s", host.now + 300_000L, host.items.get(0).nextRunAt);
        eq("标题原样", "早上看天气", host.items.get(0).title);

        Map<String, Object> bad = DshJson.map("title", "x", "prompt", "y", "kind", "every",
                "everySeconds", 30);
        check("every 少于 60 秒被拒绝", tools.get(0).run(bad).startsWith("没有创建"));

        Map<String, Object> daily = DshJson.map("title", "日报", "prompt", "写日报",
                "kind", "daily", "time", "09:00", "timeZone", "Asia/Shanghai");
        check("create 能建（daily）", tools.get(0).run(daily).startsWith("已创建提醒"));
        eq("daily 有钟点", "09:00:00", host.items.get(1).time);
        check("daily 有下一次", host.items.get(1).nextRunAt > host.now);

        Map<String, Object> cron = DshJson.map("title", "工作日九点", "prompt", "提醒我",
                "kind", "cron", "cron", "0 9 * * 1-5", "timeZone", "Asia/Shanghai");
        check("create 能建（cron）", tools.get(0).run(cron).startsWith("已创建提醒"));
        eq("cron 原样保存", "0 9 * * 1-5", host.items.get(2).cron);
        check("坏 cron 被拒", tools.get(0).run(DshJson.map("title", "x", "prompt", "y",
                "kind", "cron", "cron", "0 9 * *", "timeZone", "Asia/Shanghai"))
                .startsWith("没有创建"));

        String list = tools.get(1).run(DshJson.map());
        check("list 列出三个", list.contains("早上看天气") && list.contains("日报")
                && list.contains("工作日九点"));

        com.deepseek.pet.dsh.DshSchedule first = host.items.get(0);
        first.recordDelivery(host.now, host.now);
        Map<String, Object> up = DshJson.map("id", first.id, "title", "改过的标题");
        String ur = tools.get(2).run(up);
        check("update 成功", ur.startsWith("已更新"));
        eq("update 保留 id", first.id, host.items.get(0).id);
        eq("update 改标题", "改过的标题", host.items.get(0).title);
        eq("update 保留投递记录", 1, host.items.get(0).deliveries.size());

        check("delete 成功", tools.get(3).run(DshJson.map("id", first.id)).startsWith("已删除"));
        eq("删完剩两个", 2, host.items.size());
        check("删不存在的说明白", tools.get(3).run(DshJson.map("id", "nope")).contains("找不到"));

        // cron 匹配本身（与上游 cron.spec 的语义一致：分 时 日 月 周）
        com.deepseek.pet.dsh.DshSchedule.Cron c = com.deepseek.pet.dsh.DshSchedule.Cron.parse("* * * * *");
        check("全星号能解析", c != null);
        check("坏字段被拒", com.deepseek.pet.dsh.DshSchedule.Cron.parse("61 * * * *") == null);
        check("步长被拒（0）", com.deepseek.pet.dsh.DshSchedule.Cron.parse("*/0 * * * *") == null);
        check("五段校验", com.deepseek.pet.dsh.DshSchedule.Cron.parse("* * * *") == null);
    }
}
