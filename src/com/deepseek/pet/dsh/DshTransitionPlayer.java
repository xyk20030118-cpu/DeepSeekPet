package com.deepseek.pet.dsh;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;

/**
 * 模式切换动画的播放器 —— **只按 {@link DshTransition#kind} 分派**，自己不定义任何取值。
 *
 * <h3>三种内置 + 一种自定义，形状都统一成"盖一层 → 播完就撤"</h3>
 * 不管内置还是视频，接口都是 {@code play(activity, kind, videoFile, done)}：
 * 在 Activity 的 decorView 上盖一层不可交互的覆盖层 → 播 → 移除 → 回调 done。
 * 这样调用方（{@code MainActivity} / {@code DshWorkActivity}）**不需要 switch**，
 * 以后加动画只改 {@link DshTransition} 与本类。
 *
 * <h3>☆ 视频那一路的三条硬规矩</h3>
 * <ol>
 *   <li><b>静音</b>：切换动画不该抢音频焦点（用户可能在听歌/接电话）；</li>
 *   <li><b>有上限</b>：{@link DshTransition#VIDEO_MAX_MS}，到点强行撤层 ——
 *       VideoView 出错（编码不支持 / 文件被删）时不能把用户卡在"半切"状态；</li>
 *   <li><b>点一下跳过</b>：覆盖层本身 clickable，任何点击立即结束并回调。</li>
 * </ol>
 *
 * <p>★ 兜底原则：**任何异常都必须走 done**。动画失败最多是"没有动画"，
 * 绝不能变成"点了切模式没反应"。
 */
public final class DshTransitionPlayer {

    private DshTransitionPlayer() {}

    /** 播完/跳过/失败都会调用一次（且只一次）。 */
    public interface Done {
        void onFinished();
    }

    /**
     * 播一次切换动画。
     *
     * @param video 自定义视频文件；为 null 或不存在时自动退回淡入淡出
     */
    public static void play(final Activity act, int kind, File video, final Done done) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final Runnable finish = new Runnable() {
            @Override public void run() {
                if (done != null) done.onFinished();
            }
        };
        if (act == null || act.isFinishing()) {
            finish.run();
            return;
        }
        int k = DshTransition.normalize(kind);
        if (k == DshTransition.VIDEO && (video == null || !video.isFile())) {
            k = DshTransition.FADE;   // 视频没了就当没配过，别让用户卡死
        }

        final ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
        final FrameLayout layer = new FrameLayout(act);
        layer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer.setBackgroundColor(Color.BLACK);
        layer.setClickable(true);

        final boolean[] closed = new boolean[1];
        final Runnable close = new Runnable() {
            @Override public void run() {
                if (closed[0]) return;
                closed[0] = true;
                try {
                    decor.removeView(layer);
                } catch (Throwable ignore) {
                    // 已经不在树上了
                }
                // 覆盖层撤掉之后再通知，避免调用方在"还盖着"的时候切换 Activity
                ui.post(finish);
            }
        };

        if (k == DshTransition.VIDEO) {
            playVideo(act, layer, video, close, ui);
        } else {
            playBuiltin(act, layer, k, close, ui);
        }

        // 点一下跳过（视频与内置都吃这一下：内置动画短，但用户手快时也不该被"锁" 320ms）
        layer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { close.run(); }
        });

        try {
            decor.addView(layer);
        } catch (Throwable t) {
            close.run();
        }
    }

    // ==================== 自定义视频 ====================

    private static void playVideo(final Activity act, final FrameLayout layer, final File video,
                                  final Runnable close, final Handler ui) {
        final VideoView vv = new VideoView(act);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        layer.addView(vv, lp);

        // 一句话说明"这是切换动画，可以点掉" —— 第一次见到视频的人不会以为 App 抽了
        TextView hint = new TextView(act);
        hint.setText("切换中…轻点跳过");
        hint.setTextSize(11);
        hint.setTextColor(0x99FFFFFF);
        hint.setPadding(0, 0, dp(act, 16), dp(act, 18));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM | Gravity.END;
        layer.addView(hint, hp);

        // 硬上限：不管视频多长、不管回调有没有来，到点就撤
        ui.postDelayed(close, DshTransition.VIDEO_MAX_MS);

        try {
            vv.setVideoPath(video.getAbsolutePath());
            vv.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(android.media.MediaPlayer mp) {
                    try {
                        mp.setVolume(0f, 0f);      // 静音：不抢音频焦点
                        mp.setLooping(false);
                    } catch (Throwable ignore) {
                        // 某些 ROM 上 setVolume 会抛，静音失败也不该挡住播放
                    }
                    try {
                        vv.start();
                    } catch (Throwable t) {
                        close.run();
                    }
                }
            });
            vv.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer mp) { close.run(); }
            });
            vv.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                @Override public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                    close.run();
                    return true;   // 吃掉错误：切换动画失败 ≠ 功能失败
                }
            });
        } catch (Throwable t) {
            close.run();
        }
    }

    // ==================== 内置动画 ====================

    private static void playBuiltin(Activity act, final FrameLayout layer, int kind,
                                    final Runnable close, Handler ui) {
        int ms = DshTransition.BUILTIN_MS;
        final View glow = new View(act);
        glow.setBackgroundColor(0xFF000000);
        layer.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        switch (kind) {
            case DshTransition.ZOOM: {
                layer.setAlpha(0f);
                layer.setScaleX(0.88f);
                layer.setScaleY(0.88f);
                layer.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(ms).withEndAction(close).start();
                break;
            }
            case DshTransition.SLIDE: {
                layer.setTranslationX(dp(act, 48));
                layer.setAlpha(0f);
                layer.animate().translationX(0f).alpha(1f)
                        .setDuration(ms).withEndAction(close).start();
                break;
            }
            default: {   // FADE
                layer.setAlpha(0f);
                layer.animate().alpha(1f).setDuration(ms).withEndAction(close).start();
            }
        }
        // 保险丝：动画被系统打断（页面重建 / 转到后台）时也要收尾
        ui.postDelayed(close, ms + 260);
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }
}
