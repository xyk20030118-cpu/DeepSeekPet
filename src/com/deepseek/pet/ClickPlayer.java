package com.deepseek.pet;

/**
 * 「点击音效」的**播放**那半边（轮次57）—— 波形在 {@link ClickSound}（纯类）。
 *
 * <h3>★ 播放失败一律静默</h3>
 * 音效是"锦上添花"：设备没有音频输出、被别的 App 独占、AudioTrack 建不起来……
 * **都不该让桌宠崩掉或卡一下**。所以 {@link #play} 全程 try/catch，失败只打一行日志。
 *
 * <h3>★ 为什么不做"说话时不响"的互斥</h3>
 * 点击音是**手感反馈**：说话时戳一下，照样该有反馈（而且它只有 ~90ms、音量由用户控）。
 * 引 TtsEngine 进来只会多一条依赖，换不来用户能感知的好处。
 *
 * <h3>缓存：每个音色一条 MODE_STATIC 音轨</h3>
 * 静态音轨可以反复 {@code stop → reloadStaticData → play}，省掉每次点击都新建/释放
 * 原生资源（那会让手感变钝）。三条轨道合计约 30 KB，随进程消失；
 * 服务销毁时另有 {@link #releaseAll}（`FloatingService.onDestroy` 会调）。
 */
public final class ClickPlayer {

    private static final android.media.AudioTrack[] TRACKS =
            new android.media.AudioTrack[ClickSound.STYLE_COUNT];
    private static final float[] TRACK_VOL = new float[ClickSound.STYLE_COUNT];
    private static final Object LOCK = new Object();

    private ClickPlayer() {}

    /** 按偏好播一声（**唯一入口**：先看开关，再按音色与音量播）。 */
    public static boolean playFromPrefs(android.content.Context ctx) {
        if (ctx == null) return false;
        try {
            if (!Prefs.clickSoundOn(ctx)) return false;
            return play(Prefs.clickSoundStyle(ctx), Prefs.clickSoundVolume(ctx));
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 直接播一声（设置页的「试听」走这里）。**失败静默**，返回值只给断言/日志用。 */
    public static boolean play(int style, float volume) {
        final int st = ClickSound.styleIndex(style);
        final float vol = ClickSound.clamp01(volume);
        short[] data = ClickSound.pcm(st, vol);
        if (data.length == 0) return false;
        synchronized (LOCK) {
            android.media.AudioTrack tr = TRACKS[st];
            try {
                if (tr == null || TRACK_VOL[st] != vol) {
                    if (tr != null) {
                        try { tr.release(); } catch (Throwable ignore) {}
                    }
                    tr = build(data);
                    TRACKS[st] = tr;
                    TRACK_VOL[st] = vol;
                } else {
                    tr.stop();
                    tr.reloadStaticData();
                }
                if (tr == null) return false;
                tr.play();
                // ★ 设备侧唯一证据：日志里带音色/音量/样本数（设备脚本按它判"真的走到播放了"）
                android.util.Log.i("DSPClick", "play style=" + ClickSound.styleName(st)
                        + " vol=" + String.format(java.util.Locale.US, "%.2f", vol)
                        + " len=" + data.length + " ms=" + ClickSound.durationMs(st));
                return true;
            } catch (Throwable t) {
                TRACKS[st] = null;
                try { if (tr != null) tr.release(); } catch (Throwable ignore) {}
                android.util.Log.w("DSPClick", "play 失败（音效静默跳过）：" + t);
                return false;
            }
        }
    }

    /** 建一条 MODE_STATIC 音轨并把 PCM 写进去 */
    private static android.media.AudioTrack build(short[] data) {
        android.media.AudioTrack tr = new android.media.AudioTrack.Builder()
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        // 提示音用途：不会被当成"媒体播放"去抢音频焦点（也就不打断用户的音乐）
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ClickSound.SAMPLE_RATE)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.length * 2)
                .build();
        tr.write(data, 0, data.length);
        return tr;
    }

    /** 释放缓存的音轨（服务销毁时调；不调也只是随进程消失） */
    public static void releaseAll() {
        synchronized (LOCK) {
            for (int i = 0; i < ClickSound.STYLE_COUNT; i++) {
                try { if (TRACKS[i] != null) TRACKS[i].release(); } catch (Throwable ignore) {}
                TRACKS[i] = null;
            }
        }
    }
}
