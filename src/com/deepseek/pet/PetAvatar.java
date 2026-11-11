package com.deepseek.pet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * <b>App 头像</b>（首页那张角色卡左上角的小圆图）—— 和"桌宠形象"是**两件事**。
 *
 * <h3>为什么要分成两套（用户报障后定的口径）</h3>
 * 轮次81 起，点角色卡头像调用的是 `pickImage()`，那条链路改的是 **形象来源**
 * （`Prefs.useCustomImage` + `customImageFile`）—— 也就是**桌宠本体长什么样**。
 * 用户的要求正好相反：**点头像只改"App 里显示的那个头像"，不要动桌宠形象**。
 * 所以这里给它一条**独立的**落盘路径：
 * <pre>
 * files/app_avatar.jpg   ← 只被首页角色卡的圆头像读取
 * </pre>
 * ★ 两条链路互不影响：换 App 头像不会改形象；在设置里换形象也不会改 App 头像。
 * ★ 这一点很重要 —— 上一版把它接错线，用户的反应是"形象被改了"，那是对既有功能的破坏。
 *
 * <h3>三条限制</h3>
 * <ol>
 *   <li><b>只收图片 mime</b>（拿视频/文档当头像只会得到一个坏圆）；</li>
 *   <li><b>上限 8 MB</b>：头像只需要几百像素，超过这个量的多半是原图直出，
 *       解出来还要再缩，没必要先拷一遍几十 MB；</li>
 *   <li><b>先写 .part 再改名</b>：拷一半被打断不会留下一个"打不开的头像"。</li>
 * </ol>
 */
final class PetAvatar {

    private PetAvatar() {}

    /** SAF 请求码（2032 之前未占用：2001~2022 归 MainActivity，2031 归切换动画视频）。 */
    static final int REQ_PICK = 2032;

    /** 上限 8 MB。 */
    static final long MAX_BYTES = 8L * 1024 * 1024;

    static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "app_avatar.jpg");
    }

    static boolean has(Context c) {
        File f = file(c);
        return f.isFile() && f.length() > 0;
    }

    /** 读出圆头像用的位图；没有/读不出来返回 null（调用方回退到形象首帧）。 */
    static Bitmap load(Context c) {
        if (!has(c)) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file(c).getAbsolutePath(), o);
            int scale = 1;
            int big = Math.max(o.outWidth, o.outHeight);
            while (big / scale > 256) scale *= 2;   // 头像只要 56dp，别整张原图解进内存
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = Math.max(1, scale);
            return BitmapFactory.decodeFile(file(c).getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 打开系统文件选择器挑一张图（真正落盘在 {@link #importUri}）。 */
    static void pick(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            a.startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            Toast.makeText(a, "这台机器没有可用的文件选择器：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 把用户挑的图拷成 App 头像（**异步**，拷完回主线程回调）。
     *
     * @param onDone 成功后的回调（调用方用来刷新角色卡）
     */
    static void handleResult(final Activity a, int requestCode, int resultCode, Intent data,
                             final Runnable onDone) {
        if (requestCode != REQ_PICK) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(a, "已取消", Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri uri = data.getData();
        Toast.makeText(a, "正在更换头像…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = importUri(a, uri);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(a, err == null ? "头像已更新（桌宠形象没动）" : err,
                                Toast.LENGTH_LONG).show();
                        if (err == null && onDone != null) onDone.run();
                    }
                });
            }
        }, "pet-avatar").start();
    }

    /**
     * 真正落盘。
     *
     * @return 失败原因；成功返回 null
     */
    static String importUri(Context c, Uri uri) {
        String mime = "";
        try {
            mime = c.getContentResolver().getType(uri);
        } catch (Throwable ignore) {
            // 有些 provider 不报 mime，后面靠解码兜底
        }
        if (mime != null && mime.length() > 0 && !mime.startsWith("image/")) {
            return "只支持图片（当前是 " + mime + "）";
        }
        File target = file(c);
        File tmp = new File(target.getAbsolutePath() + ".part");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) return "打不开这个文件";
            out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[32 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) return "图片太大（超过 8 MB）——头像是小圆图，不需要原图";
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            out = null;
            // 解一下确认是能用的图，别把坏文件存成头像
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                tmp.delete();
                return "这张图读不出来（可能不是图片）";
            }
            if (target.exists() && !target.delete()) return "旧头像删不掉";
            if (!tmp.renameTo(target)) return "保存失败";
            return null;
        } catch (Throwable t) {
            return "导入失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
            try { if (out != null) out.close(); } catch (Throwable ignore) { }
            if (tmp.exists() && !target.exists()) tmp.delete();
        }
    }

    /** 删掉自定义头像（回退到形象首帧）。 */
    static boolean clear(Context c) {
        File f = file(c);
        return f.isFile() && f.delete();
    }
}
