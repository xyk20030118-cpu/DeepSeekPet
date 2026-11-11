package com.deepseek.pet.dsh;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 定时任务落盘 —— 一个任务一个 JSON（人能直接看、能手动救）。
 *
 * <pre>
 * files/dsh/schedules/&lt;id&gt;.json
 * </pre>
 *
 * <p>★ 为什么不用 SharedPreferences：定时任务带"上次跑/下次跑"时间，一旦进程被杀也要
 * 原样读回来；JSON 落盘与 {@link DshTaskStore} 同一套做法，比 pref 好排查。
 *
 * <p>纯类：只认路径。
 */
public final class DshScheduleStore {

    public final File dir;

    public DshScheduleStore(File dir) {
        this.dir = dir;
    }

    private File fileFor(String id) {
        return new File(dir, DshSessionLog.safeName(id) + ".json");
    }

    public boolean save(DshSchedule s) {
        if (s == null) return false;
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            DshFs.writeText(fileFor(s.id), DshJson.write(s.toMap()));
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public DshSchedule load(String id) {
        if (id == null || id.length() == 0) return null;
        Object o = com.deepseek.pet.Json.parseOrNull(DshFs.readTextQuiet(fileFor(id), 64 * 1024));
        if (!(o instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        return DshSchedule.fromMap(m);
    }

    public boolean delete(String id) {
        return id != null && fileFor(id).delete();
    }

    /** 全部定时任务（按创建时间正序：先建的先看到）。 */
    public List<DshSchedule> list() {
        List<DshSchedule> out = new ArrayList<DshSchedule>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : fs) {
            String n = f.getName();
            if (!n.endsWith(".json")) continue;
            DshSchedule s = load(n.substring(0, n.length() - 5));
            if (s != null) out.add(s);
        }
        return out;
    }

    public int count() {
        return list().size();
    }
}
