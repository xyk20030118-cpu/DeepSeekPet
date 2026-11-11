package com.deepseek.pet;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ex-skill 导入器。
 *
 * 背景：perkfly/ex-skill 是跑在 Claude Code / OpenClaw 里的「人格生成器」，
 * 不是运行时组件，无法直接嵌进本 App；但它的产物是纯文本：
 *   exes/{slug}/persona.md  —— 5 层性格结构
 *   exes/{slug}/memories.md —— 共同记忆
 *   exes/{slug}/meta.json   —— 昵称/标签等元信息
 * 而桌宠的人格本身就是一段纯文本字符串（Prefs 的 chat_presets 预设），
 * 两者格式天然兼容。因此不必移植它的 Python 代码，只要把这两个 md 取出来，
 * 拼成一段预设文本即可 —— 这就是本类的全部职责。
 *
 * 支持的输入：
 *   1) 单个 .md 文件（persona.md 或 memories.md）
 *   2) 含上述文件的 .zip（把 exes/{slug}/ 整目录打包，或仓库 zip）
 *
 * 注意：共同记忆刻意并入人格预设文本，而不是写进「记忆点」(facts)——
 * 后者上限 6 行 / 400 字符，会把 memories.md 截掉大半。
 */
public class SkillImporter {

    /** 防御性上限：过大的文本直接拒绝，避免把上下文打爆 */
    private static final int MAX_CHARS = 40000;
    private static final String[] MD_NAMES = {"persona.md", "memories.md", "meta.json"};

    /** 解析结果 */
    public static class Result {
        public String name = "导入的性格";
        public String persona = "";
        public String memories = "";
        public String error = "";

        public boolean ok() {
            return error.length() == 0 && (persona.length() > 0 || memories.length() > 0);
        }

        /** 拼成一条预设文本：人格在前，共同记忆在后 */
        public String toPresetContent() {
            StringBuilder sb = new StringBuilder();
            if (persona.length() > 0) sb.append(persona.trim());
            if (memories.length() > 0) {
                if (sb.length() > 0) sb.append("\n\n---\n\n## PART A：共同记忆\n\n");
                sb.append(memories.trim());
            }
            return sb.toString();
        }
    }

    /** 从 content:// URI 导入 */
    public static Result fromUri(Context ctx, Uri uri) {
        Result r = new Result();
        byte[] data;
        try {
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) {
                r.error = "无法读取所选文件。";
                return r;
            }
            data = readAll(is);
            is.close();
        } catch (Exception e) {
            r.error = "读取失败：" + e.getMessage();
            return r;
        }
        if (data.length == 0) {
            r.error = "文件是空的。";
            return r;
        }
        return fromBytes(data);
    }

    private static Result fromBytes(byte[] data) {
        Result r = new Result();
        if (isZip(data)) {
            parseZip(data, r);
        } else {
            String text = new String(data, StandardCharsets.UTF_8);
            if (text.length() > MAX_CHARS) {
                r.error = "文件过大（" + text.length() + " 字），已拒绝。";
                return r;
            }
            classifyInto(text, r);
        }
        if (!r.ok() && r.error.length() == 0) {
            r.error = "没找到可用内容。\n\n请确认选择的是 ex-skill 生成的 persona.md / "
                    + "memories.md，或包含它们的 .zip 包。";
        }
        return r;
    }

    // ---------------- zip 分支 ----------------

    private static void parseZip(byte[] data, Result r) {
        String persona = null, memories = null, meta = null;
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new ByteArrayInputStream(data));
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String base = baseName(e.getName()).toLowerCase(Locale.US);
                boolean wanted = false;
                for (String n : MD_NAMES) if (n.equals(base)) wanted = true;
                if (!wanted) continue;

                String text = new String(readAll(zis), StandardCharsets.UTF_8);
                zis.closeEntry();
                if (text.length() > MAX_CHARS) continue;

                if ("persona.md".equals(base) && persona == null) persona = text;
                else if ("memories.md".equals(base) && memories == null) memories = text;
                else if ("meta.json".equals(base) && meta == null) meta = text;
            }
        } catch (Exception e) {
            r.error = "解析 zip 失败：" + e.getMessage();
            return;
        } finally {
            try { if (zis != null) zis.close(); } catch (Exception ignore) {}
        }
        fill(persona, memories, meta, r);
    }

    // ---------------- 单文件分支 ----------------

    /**
     * 单个文本文件：先做合理性判断，避免把任意 txt 当成人格吞掉。
     * 认作 skill 产物的信号：5 层结构 / persona / 共同记忆 / markdown 一级标题。
     */
    private static void classifyInto(String text, Result r) {
        String t = text.toLowerCase(Locale.US);
        boolean hasHeading = false;
        for (String line : text.split("\\n")) {
            if (line.trim().startsWith("# ")) { hasHeading = true; break; }
        }
        boolean skillish = hasHeading || t.contains("layer") || t.contains("persona")
                || t.contains("共同记忆") || t.contains("memories");
        if (!skillish) {
            r.error = "这个文件看起来不是 ex-skill 产物。\n\n"
                    + "请选择 persona.md / memories.md，"
                    + "或包含它们的 .zip 包。";
            return;
        }
        boolean looksMemories = (t.contains("共同记忆") || t.contains("memories"))
                && !t.contains("layer 0") && !t.contains("layer 1");
        if (looksMemories) {
            fill(null, text, null, r);
        } else {
            fill(text, null, null, r);
        }
    }

    private static void fill(String persona, String memories, String metaJson, Result r) {
        r.persona = persona == null ? "" : persona;
        r.memories = memories == null ? "" : memories;
        r.name = guessName(r.persona, r.memories, metaJson);
    }

    // ---------------- 取名 ----------------

    /** 名称优先级：meta.json 的 name → 首个一级标题 → 兜底 */
    private static String guessName(String persona, String memories, String metaJson) {
        if (metaJson != null && metaJson.length() > 0) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(metaJson);
                String n = o.optString("name", "").trim();
                if (n.length() > 0) return n;
            } catch (Exception ignore) {}
        }
        String src = (persona != null && persona.length() > 0) ? persona : memories;
        if (src != null) {
            for (String line : src.split("\n")) {
                String t = line.trim();
                if (!t.startsWith("# ")) continue;
                t = t.substring(2).trim();
                int cut = t.indexOf('\u2014');          // 全角破折号 —
                if (cut < 0) cut = t.indexOf(" - ");
                if (cut > 0) t = t.substring(0, cut).trim();
                if (t.length() > 0 && t.length() <= 40) return t;
            }
        }
        return "导入的性格";
    }

    // ---------------- 小工具 ----------------

    private static boolean isZip(byte[] d) {
        return d.length >= 4 && d[0] == 0x50 && d[1] == 0x4B
                && (d[2] == 0x03 || d[2] == 0x05 || d[2] == 0x07);
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static byte[] readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
