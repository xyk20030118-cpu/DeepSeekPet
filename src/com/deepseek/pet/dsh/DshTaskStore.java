package com.deepseek.pet.dsh;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务落盘 —— 进程被杀之后，"到底跑没跑完"要有答案（或者诚实地回答"不确定"）。
 *
 * <h3>文件布局（与 `tasks/` 一个目录，纯 JSON，人能直接看）</h3>
 * <pre>
 * files/dsh/tasks/&lt;taskId&gt;.json   每个任务一条
 * files/dsh/tasks/latest.json      最近一个任务的 id（启动时先读它）
 * </pre>
 *
 * <h3>★ 核心规则：**不确定就不要装懂**</h3>
 * {@link #recover()} 读回最近任务时，如果它的状态还是"活的"（RUNNING/QUEUED/CANCELLING…），
 * 说明进程在它跑完之前被杀掉了 —— 这时**不能**当成"还在跑"（会永远挂），**更不能**当成"没跑过"
 * 然后自动重跑（有副作用的工具会执行两次）。正确的做法是标成
 * {@link DshTask#UNKNOWN_AFTER_DISCONNECT}，让 UI 显示「状态待确认」，由用户决定。
 *
 * <p>纯类：只认路径，不认识 Android。
 */
public final class DshTaskStore {

    public final File dir;

    public DshTaskStore(File dir) {
        this.dir = dir;
    }

    private File fileFor(String id) {
        return new File(dir, DshSessionLog.safeName(id) + ".json");
    }

    private File latestFile() {
        return new File(dir, "latest.json");
    }

    /** 保存（失败返回 false，不抛 —— 任务落盘失败不该炸掉对话）。 */
    public boolean save(DshTask t) {
        if (t == null) return false;
        try {
            DshFs.writeText(fileFor(t.id), DshJson.write(t.toMap()));
            DshFs.writeText(latestFile(), DshJson.write(DshJson.map("id", t.id)));
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public DshTask load(String id) {
        if (id == null || id.length() == 0) return null;
        File f = fileFor(id);
        if (!f.isFile()) return null;
        Object o = com.deepseek.pet.Json.parseOrNull(DshFs.readTextQuiet(f, 64 * 1024));
        if (!(o instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        return DshTask.fromMap(m);
    }

    public String latestId() {
        Object o = com.deepseek.pet.Json.parseOrNull(DshFs.readTextQuiet(latestFile(), 4096));
        if (o instanceof Map) {
            Object id = ((Map<?, ?>) o).get("id");
            if (id != null) return String.valueOf(id);
        }
        return "";
    }

    /** 最近 n 个任务 id（按修改时间倒序）。 */
    public List<String> recent(int n) {
        List<String> out = new ArrayList<String>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : fs) {
            String nm = f.getName();
            if (!nm.endsWith(".json") || nm.equals("latest.json")) continue;
            out.add(nm.substring(0, nm.length() - 5));
            if (out.size() >= Math.max(1, n)) break;
        }
        return out;
    }

    /**
     * 启动时恢复最近任务。
     *
     * @return 恢复出来的任务（没有则 null）；若原状态是"活的"，**就地改成**
     *         {@link DshTask#UNKNOWN_AFTER_DISCONNECT} 并落盘
     */
    public DshTask recover() {
        DshTask t = load(latestId());
        if (t == null) return null;
        if (t.active() && t.state != DshTask.CANCELLED) {
            // 进程在它跑完之前就没了 ⇒ 不确定，不重跑
            t.setState(DshTask.UNKNOWN_AFTER_DISCONNECT);
            t.error = "进程中断，无法确认是否执行完成（不会自动重跑）";
            save(t);
        }
        return t;
    }

    /** 删除（清理旧任务；latest 指向的文件不动）。 */
    public boolean delete(String id) {
        return fileFor(id).delete();
    }
}
