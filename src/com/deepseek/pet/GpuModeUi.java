package com.deepseek.pet;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;
import android.widget.Toast;

/**
 * GPU 开关的**界面动作**（轮次68 建立 · **轮次69 改成两态**）—— 只有两件事：
 * <b>一键切换档位</b>、<b>「开」失败时的"退路"对话框</b>。
 *
 * <h3>★★ 为什么要单独一个类</h3>
 * "一键可切"这件事有**三个调用点**（悬浮窗的气泡 / 聊天窗口 / 设置面板），
 * 而它们各自的对话框 Builder 不一样：
 * <ul>
 *   <li>Activity 用 {@code UiFactory.dialog(ctx)}（会套主题令牌）；</li>
 *   <li>悬浮服务必须用它自己的 **overlay 版** Builder（{@code FloatingService.dlg()}），
 *       否则服务里弹窗会 {@code BadTokenException} —— 而那个异常一旦发生，
 *       用户看到的就是"点了没反应"（正是本轮最不许出现的坏法）。</li>
 * </ul>
 * ⇒ 这里只**装按钮**，Builder 由调用方给。切换与提示文案都只有这一份，
 * 不会出现"设置页改了、气泡那条还是老写法"的漂移。
 */
public final class GpuModeUi {

    private static final String TAG = "DSPLlm";

    private GpuModeUi() {}

    /**
     * 真的把档位改掉：写设置 → 放掉内存里的模型 → 清掉失败提示 → Toast。
     *
     * <p>★★ 为什么必须把模型放掉：GPU 卸载层数是**载入时一次性**给 llama.cpp 的
     * （{@code n_gpu_layers} 只在 {@code llama_model_load_from_file} 那一刻生效），
     * 不放掉的话症状就是"我改了设置但它还是老样子"。
     */
    public static void applyMode(Context ctx, String mode) {
        final String m = GpuMode.normalize(mode);
        if (ctx == null || m == null) return;
        Prefs.setLocalGpuMode(ctx, m);
        try {
            LocalLlm.unload();
            LocalChat.forget();
        } catch (Throwable ignore) {}
        // ★ 用户**自己**做了选择 ⇒ 那条"开 GPU 失败"的提示可以收了。
        //   （这不是静默：他把档位改了，我们还要把新档位告诉他。）
        GpuFailNotice.clear();
        Log.i(TAG, "★★ GPU 加速档位已改成：" + m + "（内存里的模型已放掉，下一次对话重新载入）");
        Toast.makeText(ctx, GpuMode.changedToast(m), Toast.LENGTH_LONG).show();
    }

    /**
     * 需要时弹出"一键退路"。
     *
     * <p>★★ 轮次69 只有**一颗**退路按钮（改成「关」）—— 因为只剩两档。
     * 对话框正文里还点名了另外四条退路（卸载 / 删除 / 换模型 / 切回在线），
     * 而那四条**不经过这个方法**、也不受这里弹不弹得出来影响。
     *
     * @param b 调用方自己的 Builder（Activity 用 {@code UiFactory.dialog(this)}；
     *          悬浮服务用它的 overlay 版）。★ 传 null 就直接不弹（宁可只有气泡文案，
     *          也不能因为弹窗失败把主流程打断）。
     */
    public static void showFallbackPromptIfNeeded(final Context ctx, AlertDialog.Builder b) {
        if (ctx == null || b == null) return;
        if (!GpuFailNotice.popupPending()) return;      // 只弹一次，不骚扰
        GpuFailNotice.popupShown();
        final String reason = GpuFailNotice.last();
        b.setTitle(GpuFailNotice.title())
                .setMessage(GpuFailNotice.message(reason))
                .setPositiveButton(GpuFailNotice.btnOff(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        applyMode(ctx, GpuMode.OFF);
                    }
                })
                .setNegativeButton(GpuFailNotice.btnKeep(), null);
        try {
            b.show();
        } catch (Throwable t) {
            // ★ 弹不出来也要留证据（否则真机上就是"点了没反应"）
            Log.e(TAG, "★ GPU 退路对话框没弹出来（气泡里仍有完整原因）：" + t);
        }
    }
}
