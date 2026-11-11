package com.deepseek.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 导入的 Live2D 模型包解析器。
 *
 * 支持两种来源：
 *   A) 标准 Live2D 导出包：*.model3.json + 贴图目录（+ 可选 physics/motions/pose）
 *      本类读取 FileReferences.Textures 的贴图列表，按顺序作为分层叠加，
 *      并生成一个归一化的 meta.json 供 {@link Live2DEngine} 统一加载。
 *   B) 本 App 的简化分层包：目录内直接放 meta.json + 若干 png 分层。
 *
 * 说明（重要）：
 *   .moc3 是 Live2D Cubism 的封闭二进制格式，需要官方 Cubism Core 原生库才能解析/驱动。
 *   本 App 未集成该原生库，因此对 .moc3-only 的模型只能识别、不能驱动——
 *   会通过 {@link #status} 明确告知用户，绝不假装可运行。
 *   若模型包同时提供了分层贴图（Textures），则用分层方式做“近似 Live2D”驱动。
 */
public class Live2DPackage {

    private static final String TAG = "L2DPkg";

    public static final String STATUS_OK = "ok";
    public static final String STATUS_ONLY_MOC3 = "only_moc3";   // 只有 moc3，无法驱动
    public static final String STATUS_INVALID = "invalid";

    private String status = STATUS_INVALID;
    private String message = "";
    private boolean hasMoc3 = false;
    private String modelName = "";
    private int layerCount = 0;
    private final List<String> layerNames = new ArrayList<>();
    private float designW = 0, designH = 0;

    public String status() { return status; }
    public String message() { return message; }
    public boolean hasMoc3() { return hasMoc3; }
    public String modelName() { return modelName; }
    public int layerCount() { return layerCount; }
    public List<String> layerNames() { return layerNames; }
    public float designW() { return designW; }
    public float designH() { return designH; }

    /** 便捷：是否可用（能被 Live2DEngine 驱动）。 */
    public boolean isUsable() { return STATUS_OK.equals(status); }

    /**
     * 解析一个已解压的 Live2D 包目录，并在目录内写出归一化 meta.json。
     * 不修改除 meta.json 之外的文件。
     */
    public static Live2DPackage load(File dir) {
        Live2DPackage p = new Live2DPackage();
        if (dir == null || !dir.isDirectory()) {
            p.message = "目录不存在";
            return p;
        }

        // 1) 找 model3.json / moc3
        File model3 = findFirst(dir, ".model3.json");
        File moc3 = findFirst(dir, ".moc3");
        p.hasMoc3 = moc3 != null;

        // 2) 若有 model3.json：解析贴图列表
        List<File> textures = new ArrayList<>();
        if (model3 != null) {
            try {
                String json = readFile(model3);
                JSONObject root = new JSONObject(json);
                JSONObject refs = root.optJSONObject("FileReferences");
                if (refs != null) {
                    JSONArray tex = refs.optJSONArray("Textures");
                    if (tex != null) {
                        for (int i = 0; i < tex.length(); i++) {
                            String rel = tex.optString(i, "");
                            if (rel.length() == 0) continue;
                            File tf = new File(model3.getParentFile(), rel);
                            if (tf.exists()) textures.add(tf);
                        }
                    }
                }
                // 模型名
                JSONObject meta = root.optJSONObject("FileReferences"); // 保持兼容
                if (root.has("Name")) p.modelName = root.optString("Name", "");
                if (p.modelName.length() == 0) {
                    p.modelName = model3.getName().replace(".model3.json", "");
                }
            } catch (Exception e) {
                Log.w(TAG, "model3 parse fail", e);
            }
        }

        // 3) 若没有 model3.json，则看是否是“本 App 简化分层包”（meta.json）
        File metaJson = new File(dir, "meta.json");
        if (textures.isEmpty() && metaJson.exists()) {
            // 直接复用，验证 png 存在
            List<File> pngs = allFiles(dir, ".png");
            if (!pngs.isEmpty()) {
                p.status = STATUS_OK;
                p.message = "简化分层包";
                p.layerCount = pngs.size();
                for (File f : pngs) p.layerNames.add(f.getName());
                return p;
            }
        }

        // 4) 仍无贴图 → 再看目录里有没有直接放的 png 分层
        if (textures.isEmpty()) {
            List<File> pngs = allFiles(dir, ".png");
            if (!pngs.isEmpty()) {
                textures = pngs;
                p.message = "按目录内 PNG 叠放";
            }
        }

        // 5) 判定结果
        if (textures.isEmpty()) {
            if (p.hasMoc3) {
                p.status = STATUS_ONLY_MOC3;
                p.message = "该模型仅含 .moc3（Live2D 封闭格式），需要 Cubism 原生运行时才能驱动；"
                        + "当前未集成，无法显示。若另有分层 PNG/贴图目录可一并导入。";
            } else {
                p.status = STATUS_INVALID;
                p.message = "未找到可用的贴图或 model3.json";
            }
            return p;
        }

        // 6) 写出归一化 meta.json（供 Live2DEngine 统一加载）
        try {
            buildMeta(dir, textures);
            p.status = STATUS_OK;
            if (p.message.length() == 0) {
                p.message = p.hasMoc3
                        ? "已按贴图分层近似驱动（原模型含 moc3，未用骨骼）"
                        : "已按贴图分层驱动";
            }
            p.layerCount = textures.size();
            for (File f : textures) p.layerNames.add(f.getName());
            // 读取 design
            int[] wh = probeSize(textures.get(0));
            p.designW = wh[0]; p.designH = wh[1];
        } catch (Exception e) {
            Log.e(TAG, "buildMeta fail", e);
            p.status = STATUS_INVALID;
            p.message = "生成分层元数据失败：" + e.getMessage();
        }
        return p;
    }

    /**
     * 生成归一化 meta.json。层顺序 = Textures 数组顺序（Live2D 约定贴图从前到后，
     * 我们按“后画到前”的 z 序输出，即倒序贴图列表；实际叠加按数组顺序即可）。
     */
    private static void buildMeta(File dir, List<File> textures) throws Exception {
        // 取第一张贴图的尺寸作为画布设计尺寸（Live2D 贴图通常同尺寸图集）
        int[] wh = probeSize(textures.get(0));
        int designW = wh[0] > 0 ? wh[0] : 1024;
        int designH = wh[1] > 0 ? wh[1] : 1024;

        JSONArray order = new JSONArray();
        JSONObject trim = new JSONObject();
        JSONObject pivot = new JSONObject();

        // 命名分层：layer_0, layer_1 ...（越靠前越先绘制=越底层）
        // Live2D Textures 顺序：index 越小通常越靠前(上层)。我们反转，让先画底层。
        List<File> drawOrder = new ArrayList<>(textures);
        Collections.reverse(drawOrder);

        for (int i = 0; i < drawOrder.size(); i++) {
            File f = drawOrder.get(i);
            String name = "layer_" + i;
            order.put(name);
            // 相对包根路径（支持 textures/ 子目录）
            String rel = relativize(dir, f);
            trim.put(name, new JSONObject().put("file", rel));
            pivot.put(name, new JSONArray().put(designW / 2.0).put(designH));
        }

        JSONObject meta = new JSONObject();
        meta.put("canvas", new JSONArray().put(designW).put(designH));
        meta.put("order", order);
        meta.put("trim", trim);
        meta.put("pivot", pivot);
        meta.put("imported", true);
        // 眼睛/嘴位置给合理默认（贴图版无法精确得知，取相对位置）
        meta.put("eye_rect", new JSONArray()
                .put((int)(designW * 0.30)).put((int)(designH * 0.28))
                .put((int)(designW * 0.70)).put((int)(designH * 0.42)));
        meta.put("mouth_pos", new JSONArray().put((int)(designW * 0.5)).put((int)(designH * 0.45)));

        File out = new File(dir, "meta.json");
        FileOutputStream fos = new FileOutputStream(out);
        fos.write(meta.toString(2).getBytes("UTF-8"));
        fos.close();
    }

    /** 计算 f 相对 root 的路径（统一用 '/' 分隔）。 */
    private static String relativize(File root, File f) {
        try {
            String rp = root.getCanonicalPath();
            String fp = f.getCanonicalPath();
            if (fp.startsWith(rp)) {
                String rel = fp.substring(rp.length());
                if (rel.startsWith(File.separator)) rel = rel.substring(1);
                return rel.replace(File.separatorChar, '/');
            }
        } catch (Exception ignore) {}
        return f.getName();
    }

    private static int[] probeSize(File img) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(img.getAbsolutePath(), o);
            return new int[]{o.outWidth, o.outHeight};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private static File findFirst(File dir, String suffix) {
        List<File> all = allFiles(dir, null);
        for (File f : all) if (f.getName().toLowerCase().endsWith(suffix)) return f;
        return null;
    }

    private static List<File> allFiles(File dir, String suffix) {
        List<File> out = new ArrayList<>();
        collect(dir, suffix, out);
        return out;
    }

    private static void collect(File dir, String suffix, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (f.getName().startsWith(".")) continue;
                collect(f, suffix, out);
            } else {
                if (suffix == null || f.getName().toLowerCase().endsWith(suffix)) out.add(f);
            }
        }
    }

    private static String readFile(File f) throws Exception {
        InputStream is = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
