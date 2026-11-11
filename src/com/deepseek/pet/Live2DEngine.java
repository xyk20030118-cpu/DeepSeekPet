package com.deepseek.pet;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.*;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 伪 Live2D 渲染引擎（分层骨骼 + 参数驱动）。
 * 由 PetView 在 live2d / custom_l2d 模式下调用。
 *
 * 支持两种素材来源：
 *   - 内置资源：assets/live2d/*.png + meta.json
 *   - 导入包：外部目录（见 {@link Live2DPackage} 生成的 meta.json + 分层 PNG）
 *
 * 参数（Cubism 风格）：
 *   ParamAngleX/Y/Z   头部旋转
 *   ParamBodyAngleX   身体摇摆
 *   ParamBreath       呼吸
 *   ParamEyeOpen      眨眼
 *   ParamMouthOpenY   口型
 *   ParamAhoge/Hair   呆毛/后发物理摆动
 */
public class Live2DEngine {

    private static final String TAG = "L2D";

    private boolean loaded, failed;
    private final List<Layer> layers = new ArrayList<>();
    private float designW = 1672, designH = 2508;
    private float eyeRectTop = 850, eyeRectBot = 1040;
    private float mouthX = 849, mouthY = 1130;

    /** 头/身体/腿/发 “角色层名” 语义映射（内置包固定名；导入包用 layer_i + 启发式） */
    private String headName = "head", bodyName = "body", legsName = "legs",
            hairName = "backhair", ahogeName = "ahoge";

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    // 参数
    private float angleX, angleY, angleZ;
    private float curAngleX, curAngleY, curAngleZ;
    private float bodyAngleX, curBodyAngleX;
    private float breath;
    private float eyeOpen = 1f;
    private float mouthOpen = 0f;
    private boolean talking = false;
    private float ahogeSway, ahogeVel;
    private float hairSway, hairVel;

    private float targetX, targetY;
    private long nextWanderT;
    private long nextBlinkT, blinkStartT;
    private long pokeStart, happyStart;
    private int pokeCount;
    private boolean dragging;
    private float dragImpulse;

    /** 导入模式的“整体呼吸/摆动”更强，因为导入层没有语义区分 */
    private boolean imported = false;

    private static class Layer {
        String name;
        Bitmap bmp;
        float offX, offY, pivotX, pivotY;
        int meshW = 1;
        int meshH = 1;
        int z;                       // 绘制顺序
        boolean isHead, isBody, isLegs, isHair, isAhoge;
    }

    public boolean isReady() { return loaded && !failed; }
    public boolean isFailed() { return failed; }

    /** 内置资源加载 */
    public void load(Context ctx) {
        if (loaded || failed) return;
        try {
            String meta = readAsset(ctx.getAssets(), "live2d/meta.json");
            JSONObject j = new JSONObject(meta);
            parseMeta(j, ctx.getAssets(), null);
            finishLoad("assets");
        } catch (Exception e) {
            Log.e(TAG, "load(assets) fail", e);
            failed = true;
        }
    }

    /** 从导入目录加载（目录内应有 meta.json 与分层 PNG） */
    public void loadFromDir(Context ctx, File dir) {
        if (loaded || failed) return;
        try {
            if (dir == null || !new File(dir, "meta.json").exists()) {
                Log.w(TAG, "loadFromDir: no meta.json in " + dir);
                failed = true;
                return;
            }
            JSONObject j = new JSONObject(readFile(new File(dir, "meta.json")));
            parseMeta(j, null, dir);
            finishLoad("dir:" + dir.getName());
        } catch (Exception e) {
            Log.e(TAG, "loadFromDir fail", e);
            failed = true;
        }
    }

    private void finishLoad(String src) {
        if (layers.isEmpty()) { failed = true; return; }
        loaded = true;
        nextBlinkT = SystemClock.uptimeMillis() + 2000;
        nextWanderT = SystemClock.uptimeMillis() + 2500;
        Log.i(TAG, "loaded " + layers.size() + " layers from " + src
                + ", design " + designW + "x" + designH);
    }

    /** 解析 meta.json，按 order 读取各层 PNG（来源：assets 或目录） */
    private void parseMeta(JSONObject j, AssetManager am, File dir) throws Exception {
        designW = (float) j.getJSONArray("canvas").getDouble(0);
        designH = (float) j.getJSONArray("canvas").getDouble(1);
        if (j.has("eye_rect")) {
            eyeRectTop = (float) j.getJSONArray("eye_rect").getDouble(1);
            eyeRectBot = (float) j.getJSONArray("eye_rect").getDouble(3);
        }
        if (j.has("mouth_pos")) {
            mouthX = (float) j.getJSONArray("mouth_pos").getDouble(0);
            mouthY = (float) j.getJSONArray("mouth_pos").getDouble(1);
        }
        imported = j.optBoolean("imported", false);

        JSONObject piv = j.optJSONObject("pivot");
        JSONObject trim = j.optJSONObject("trim");

        JSONArray order = j.optJSONArray("order");
        List<String> names = new ArrayList<>();
        if (order != null && order.length() > 0) {
            for (int i = 0; i < order.length(); i++) names.add(order.getString(i));
        } else {
            // 没有 order：用内置默认 5 层
            names.add("backhair"); names.add("legs");
            names.add("body"); names.add("head"); names.add("ahoge");
        }

        for (int zi = 0; zi < names.size(); zi++) {
            String name = names.get(zi);
            Layer L = new Layer();
            L.name = name;
            L.z = zi;

            // 文件名：内置用 name.png；导入包用 trim[name].file 或 name.png，也支持 layer_i.png
            String file = name + ".png";
            if (trim != null && trim.has(name)) {
                JSONObject t = trim.getJSONObject(name);
                if (t.has("file")) file = t.getString("file");
                if (t.has("offset")) {
                    L.offX = (float) t.getJSONArray("offset").getDouble(0);
                    L.offY = (float) t.getJSONArray("offset").getDouble(1);
                }
            }

            L.bmp = decode(am, dir, file);
            if (L.bmp == null) {
                // 尝试直接按 name 找 PNG
                L.bmp = decode(am, dir, name + ".png");
            }
            if (L.bmp == null) {
                Log.w(TAG, "layer bitmap missing: " + name + " (" + file + ")");
                continue;
            }

            if (piv != null && piv.has(name)) {
                L.pivotX = (float) piv.getJSONArray(name).getDouble(0);
                L.pivotY = (float) piv.getJSONArray(name).getDouble(1);
            } else {
                L.pivotX = designW / 2f; L.pivotY = designH;
            }

            // 语义判定
            String low = name.toLowerCase();
            L.isHead = low.contains("head") || low.contains("face") || low.contains("front") && low.contains("hair");
            L.isBody = low.contains("body") || low.contains("torso") || low.contains("neck");
            L.isLegs = low.contains("leg") || low.contains("skirt") && low.contains("back");
            L.isHair = low.contains("backhair") || low.contains("back_hair");
            L.isAhoge = low.contains("ahoge");
            if ("head".equals(name)) L.isHead = true;
            if ("body".equals(name)) L.isBody = true;
            if ("legs".equals(name)) L.isLegs = true;
            if ("backhair".equals(name)) L.isHair = true;
            if ("ahoge".equals(name)) L.isAhoge = true;

            if (L.isHead) L.meshW = 4;
            layers.add(L);
        }

        // 导入包没有语义层：启发式分配（从图上到下推断）
        if (imported) assignSemantics();
    }

    /** 给无语义的分层按“从上到下”启发式分配角色，让整体仍能转头/摆身/呼吸 */
    private void assignSemantics() {
        // 依据各层内容的重心 y 排序：越靠上的越可能是头/发
        List<Layer> byTop = new ArrayList<>(layers);
        java.util.Collections.sort(byTop, new java.util.Comparator<Layer>() {
            @Override public int compare(Layer a, Layer b) {
                return Float.compare(contentMidY(a), contentMidY(b));
            }
        });
        int n = byTop.size();
        // 最上面的 1~2 层当作头部（可转头）
        if (n >= 1) byTop.get(0).isHead = true;
        if (n >= 4) byTop.get(1).isHead = true;
        // 中间层当作身体
        if (n >= 3) byTop.get(n / 2).isBody = true;
        // 最底层当作腿
        byTop.get(n - 1).isLegs = true;
        for (Layer L : layers) if (L.isHead) L.meshW = 4;
    }

    private float contentMidY(Layer L) {
        try {
            int w = L.bmp.getWidth(), h = L.bmp.getHeight();
            if (w <= 0 || h <= 0) return designH;
            int top = -1, bot = -1;
            // 近似：扫描若干列找非透明区域上下边界
            for (int y = 0; y < h; y += Math.max(1, h / 60)) {
                boolean rowHas = false;
                for (int x = 0; x < w; x += Math.max(1, w / 40)) {
                    if (Color.alpha(L.bmp.getPixel(x, y)) > 12) { rowHas = true; break; }
                }
                if (rowHas) { if (top < 0) top = y; bot = y; }
            }
            if (top < 0) return designH;
            return L.offY + (top + bot) / 2f;
        } catch (Exception e) {
            return designH;
        }
    }

    private Bitmap decode(AssetManager am, File dir, String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            if (dir != null) {
                File f = new File(dir, path);
                if (!f.exists()) return null;
                return BitmapFactory.decodeStream(new FileInputStream(f), null, o);
            } else if (am != null) {
                InputStream is;
                try { is = am.open("live2d/" + path); }
                catch (Exception e) { return null; }
                Bitmap bm = BitmapFactory.decodeStream(is, null, o);
                is.close();
                return bm;
            }
        } catch (Exception e) {
            Log.w(TAG, "decode fail " + path, e);
        }
        return null;
    }

    private static String readAsset(AssetManager am, String path) throws Exception {
        InputStream is = am.open(path);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096]; int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String readFile(File f) throws Exception {
        InputStream is = new FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ---- 外部驱动 ----
    public void setTalking(boolean t) { talking = t; if (!t) mouthOpen = 0f; }
    public void poke() {
        pokeStart = SystemClock.uptimeMillis();
        pokeCount++;
        if (pokeCount % 3 == 0) happyStart = SystemClock.uptimeMillis();
    }
    public void setDragging(boolean d, float vx) {
        dragging = d;
        if (d) dragImpulse = vx * 8f;
        else dragImpulse = 0f;
    }
    public void setAngleTarget(float ax, float ay) { targetX = ax; targetY = ay; }

    /** 每帧调用：推进动画 */
    public void update() {
        long now = SystemClock.uptimeMillis();
        breath = (float) ((Math.sin(now / 1000.0 * 1.8) + 1) / 2.0);

        if (!dragging && now > nextWanderT) {
            targetX = (float) (Math.random() * 22 - 11);
            targetY = (float) (Math.random() * 14 - 7);
            nextWanderT = now + 1800 + (long) (Math.random() * 3200);
        }
        if (dragging) { targetX += (dragImpulse - targetX) * 0.3f; }

        curAngleX += (targetX - curAngleX) * 0.09f;
        curAngleY += (targetY - curAngleY) * 0.09f;
        curAngleZ = (float) Math.sin(now / 1000.0 * 0.9) * 2.2f;
        curBodyAngleX += (curAngleX * 0.28f - curBodyAngleX) * 0.07f;

        // 眨眼
        if (now > nextBlinkT && blinkStartT == 0) {
            blinkStartT = now;
            nextBlinkT = now + 2000 + (long) (Math.random() * 3500);
        }
        long dtB = now - blinkStartT;
        if (blinkStartT > 0 && dtB < 160) {
            eyeOpen = (float) Math.abs(Math.cos(dtB / 160.0 * Math.PI));
        } else {
            eyeOpen += (1f - eyeOpen) * 0.4f;
            if (dtB >= 160) blinkStartT = 0;
        }

        // 呆毛 / 后发物理（弹簧）
        float aForce = -curAngleX * 0.9f - ahogeSway * 0.22f;
        ahogeVel = ahogeVel * 0.80f + aForce * 0.6f + dragImpulse * 0.2f;
        ahogeSway += ahogeVel;
        float hForce = -curAngleX * 1.1f - hairSway * 0.18f;
        hairVel = hairVel * 0.82f + hForce * 0.5f + dragImpulse * 0.3f;
        hairSway += hairVel;

        // 说话口型
        if (talking) mouthOpen = (float) Math.abs(Math.sin(now / 1000.0 * 9));

        dragImpulse *= 0.9f;
    }

    /** 绘制到指定画布，控件尺寸 w×h */
    public void draw(Canvas cv, int W, int H) {
        if (!isReady() || W <= 0 || H <= 0) return;
        float scale = Math.min(W / designW, H / designH) * 0.94f;
        float dx = (W - designW * scale) / 2f;
        float dy = (H - designH * scale) / 2f;
        float breathS = 1f + (breath - 0.5f) * (imported ? 0.02f : 0.025f);

        for (Layer L : layers) {
            cv.save();
            cv.translate(dx, dy);
            cv.scale(scale, scale);
            // 呼吸：以脚底为基点做纵向缩放
            cv.translate(0, designH);
            cv.scale(1f, breathS);
            cv.translate(0, -designH);

            float pvX = L.pivotX, pvY = L.pivotY;
            if (L.isHead) {
                cv.rotate(curAngleZ, pvX, pvY);
                float amp = imported ? 0.7f : 1.4f;
                cv.translate(curAngleX * amp, curAngleY * (imported ? 0.6f : 1.2f));
                float sq = curAngleX / 30f * 0.07f;
                cv.translate(pvX, pvY);
                cv.scale(1f + sq, 1f - Math.abs(sq) * 0.35f);
                cv.translate(-pvX, -pvY);
            } else if (L.isBody) {
                cv.rotate(curBodyAngleX * 0.5f, pvX, pvY);
                cv.translate(curBodyAngleX * 0.4f, 0);
            } else if (L.isLegs) {
                cv.translate(curBodyAngleX * 0.2f, 0);
            } else if (L.isHair) {
                cv.rotate(hairSway * 0.35f, pvX, pvY);
                cv.translate(hairSway * 0.5f, 0);
            } else if (L.isAhoge) {
                cv.rotate(ahogeSway * 1.2f, pvX, pvY);
            }

            RectF dst = new RectF(L.offX, L.offY,
                    L.offX + L.bmp.getWidth(), L.offY + L.bmp.getHeight());
            p.setAlpha(255);
            cv.drawBitmap(L.bmp, null, dst, p);
            cv.restore();
        }
    }

    public void recycle() {
        for (Layer L : layers) if (L.bmp != null) L.bmp.recycle();
        layers.clear();
        loaded = false;
    }
}
