package com.deepseek.pet;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 独立验证：从 assets/webp 用 PetAnimPack 解码 animated WebP 并播放，
 * 放在彩色棋盘背景上以肉眼确认 alpha 透明。
 */
public class Vp9ProbeActivity extends Activity {
    static final String TAG = "PACKPROBE";
    static void L(String s) { Log.i(TAG, s); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // ★ 用户 2026-10-11：「所有三级菜单都要跟随主题切换」——系统自绘的东西
        //   （对话框确认框 / 输入法选择菜单 / 长按文本菜单）只认 Activity 主题，
        //   不认我们的令牌 ⇒ 必须先换成与明暗一致的系统主题。
        //   必须在 setContentView 之前（setTheme 的硬性要求）。
        Tokens.applyPlatformTheme(this);
        FrameLayout root = new FrameLayout(this);
        // 彩色棋盘背景（半透明网格），用来确认 alpha
        final Paint[] holder = new Paint[1];
        View bg = new View(this) {
            private final Paint p = new Paint();
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight(), cell = 40;
                for (int y = 0; y < h; y += cell)
                    for (int x = 0; x < w; x += cell) {
                        p.setColor(((x / cell + y / cell) & 1) == 0 ? 0xFFFF00FF : 0xFF00FFFF);
                        c.drawRect(x, y, x + cell, y + cell, p);
                    }
            }
        };
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        final PetAnimPack pack = new PetAnimPack();
        pack.load(getApplicationContext());
        L("isReady=" + pack.isReady() + " failed=" + pack.isFailed());
        String[] tryIds = {"东张西望", "三球抛接", "下五子棋"};
        Drawable d = null; String used = null;
        for (String id : tryIds) {
            d = pack.getDrawable(id);
            if (d != null) { used = id; break; }
        }
        L("decoded drawable=" + (d != null) + " id=" + used
          + (d != null ? (" intrinsic=" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight()
             + " class=" + d.getClass().getSimpleName()) : ""));

        final ImageView iv = new ImageView(this);
        if (d != null) {
            iv.setImageDrawable(d);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pack.startDrawable(d);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(720, 720);
        lp.gravity = Gravity.CENTER;
        root.addView(iv, lp);

        TextView tv = new TextView(this);
        tv.setText("pack ready=" + pack.isReady() + "  clip=" + used);
        tv.setTextColor(0xFF000000);
        tv.setBackgroundColor(0x88FFFFFF);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
        tlp.gravity = Gravity.TOP | Gravity.START;
        root.addView(tv, tlp);

        setContentView(root);
        L("=== LAUNCHED ===");
    }
}
