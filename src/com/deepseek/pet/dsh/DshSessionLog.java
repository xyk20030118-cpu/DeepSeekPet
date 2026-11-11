package com.deepseek.pet.dsh;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 会话日志落盘 —— 追加式 JSONL，一行一条事件。
 *
 * <p>文件布局（与上游同构，只是不带 zstd 压缩）：
 * <pre>
 * files/dsh/sessions/&lt;sessionId&gt;.jsonl     ← 事件流（只追加）
 * files/dsh/sessions/&lt;sessionId&gt;.meta.json ← 标题/时间/事件数（给人看）
 * </pre>
 *
 * <p>★ 为什么不压缩：上游用 {@code .zstd}（Node 侧有 zstd 原生模块）；Android 侧要压缩就得
 * 编 native 或者走 {@code GZIPOutputStream}（纯 Java 有）。这里选**不压缩**是有意的：
 * 会话日志本来就是给"人也能看、能 grep、能手动救"的场景准备的，一次几 MB 完全承受得起。
 * 真需要压缩时，{@link #load} 的格式判断已经留好了余地（读不出来的行一律跳过）。
 *
 * <p>★ 坏行容忍：进程被杀可能留下半行；{@link #load} 跳过解析不了的行，
 * 不让"最后一条没写完"变成"整个会话打不开"。
 */
public final class DshSessionLog {

    public final File dir;

    public DshSessionLog(File dir) {
        this.dir = dir;
    }

    public File fileFor(String sessionId) {
        return new File(dir, safeName(sessionId) + ".jsonl");
    }

    public File metaFor(String sessionId) {
        return new File(dir, safeName(sessionId) + ".meta.json");
    }

    /** 会话 id 只允许字母数字与 -_（防路径穿越，模型/用户都可能给奇怪的值）。 */
    public static String safeName(String id) {
        if (id == null || id.trim().length() == 0) return "unnamed";
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length() && sb.length() < 96; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /** 追加一条事件；失败返回 false（不抛 —— 日志写不进去不该炸掉对话）。 */
    public boolean append(String sessionId, DshEvent e) {
        if (e == null) return false;
        try {
            DshFs.appendLine(fileFor(sessionId), e.toJsonLine());
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /** 读回全部事件（坏行跳过）。 */
    public List<DshEvent> load(String sessionId) {
        List<DshEvent> out = new ArrayList<DshEvent>();
        File f = fileFor(sessionId);
        if (!f.isFile()) return out;
        String text = DshFs.readTextQuiet(f, DshFs.MAX_READ_LIMIT * 8);
        if (text == null) return out;
        for (String line : text.split("\n")) {
            if (line.trim().length() == 0) continue;
            DshEvent e = DshEvent.fromJsonLine(line);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** 列出所有会话 id（按文件修改时间倒序，最近的在前）。 */
    public List<String> listIds() {
        List<String> ids = new ArrayList<String>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return ids;
        Arrays.sort(files, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".jsonl")) ids.add(n.substring(0, n.length() - 6));
        }
        return ids;
    }

    /** 写 meta（给人看的摘要；失败不影响会话）。 */
    public void writeMeta(String sessionId, DshSession s) {
        if (s == null) return;
        try {
            DshFs.writeText(metaFor(sessionId), DshJson.write(s.meta()));
        } catch (IOException ignore) {
            // 摘要写不进去无所谓，事件日志才是事实
        }
    }

    public boolean delete(String sessionId) {
        boolean a = fileFor(sessionId).delete();
        metaFor(sessionId).delete();
        return a;
    }

    /**
     * 从磁盘打开会话（不存在就新建一个空会话）。
     *
     * @param sink 事件出口（通常写日志 + 推 UI）；恢复时也会把历史事件推给 UI
     */
    public DshSession open(String sessionId, String cwd, DshSession.Sink sink) {
        DshSession s = new DshSession(sessionId, "", 0, cwd);
        List<DshEvent> loaded = load(sessionId);
        if (!loaded.isEmpty()) {
            s.restore(loaded, sink);
            for (DshEvent e : loaded) {
                if (DshEvent.SESSION_TITLE.equals(e.type)) {
                    Object t = e.data.get("title");
                    if (t != null) s.title = String.valueOf(t);
                }
            }
        } else {
            s.setSink(sink);
        }
        return s;
    }
}
