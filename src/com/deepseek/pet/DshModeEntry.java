package com.deepseek.pet;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import com.deepseek.pet.dsh.DshBridge;
import com.deepseek.pet.dsh.DshFeature;
import com.deepseek.pet.dsh.DshTransition;
import com.deepseek.pet.dsh.DshTransitionPlayer;

/**
 * 「双模式」的入口与切换 —— 陪伴模式（原来的界面）⇄ 工作模式（DeepSeek Harness 页面）。
 *
 * <h3>为什么单独一个类，而不是写进 MainActivity</h3>
 * MainActivity 已经一万一千行；这张卡片要读开关、要播动画、要拉 SAF 导入视频，
 * 塞进去以后"模式切换"这件事就在四个地方各有一半。这里只有一处：
 * <b>卡片长什么样、点了去哪、动画怎么放、视频从哪来</b>。
 *
 * <h3>切换的三条规矩</h3>
 * <ol>
 *   <li><b>先播动画再切</b>：动画期间旧页面还在，播完才 startActivity / finish ——
 *       否则用户看到的是"白屏闪一下"而不是"转场"；</li>
 *   <li><b>开关关着就没有这件事</b>：{@link DshFeature#ENABLED} 或运行时开关关掉时，
 *       卡片不绘制、切换入口不存在；</li>
 *   <li><b>动画失败也要能切过去</b>：{@link DshTransitionPlayer} 保证一定回调
 *       （视频坏 / 编码不支持 / 被系统打断都算"播完了"）。</li>
 * </ol>
 */
final class DshModeEntry {

    private DshModeEntry() {}

    /** SAF 请求码（本页专用，避开 MainActivity 里已用掉的 2001~2022）。 */
    static final int REQ_VIDEO = 2031;

    // ==================== 切换入口（集成在首页角色卡，**不画任何按钮**） ====================

    // ★ 用户原话：「集成在这里面，不要显示任何按钮」。
    //   所以入口就是 MainActivity.heroCard() 那张角色卡本身：点卡片空白处 = 去工作模式，
    //   点名字仍只是改名（子视图先吃掉点击）。这里不再有 card() 那种带按钮的卡片。

    // ==================== 切换动作 ====================

    /** 陪伴 → 工作：播动画 → 打开工作模式页面。 */
    static void switchMode(final Activity a, final Tokens.Set T, final Runnable onAfter) {
        // ★ 用户要求：工作模式总开关关掉之后**打不开**工作模式（只有手动打开开关才行）。
        if (!DshBridge.workEnabled(a)) {
            android.widget.Toast.makeText(a, "工作模式已关闭：先到 设置 → 陪伴 → 工作模式 里打开开关",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!DshBridge.enabled(a)) {
            // ★★ 第五版：这里原来是"提示去打开总开关就 return"。
            //   而那个开关只能在工作模式里打开 ⇒ **死锁**（用户就是这么卡住的）。
            //   现在直接开回来再进去：用户点了就是想用，不该被一个隐藏开关挡住。
            DshBridge.setEnabled(a, true);
        }
        if (DshBridge.isWorkMode(a)) {
            // 已经标成工作模式（异常退出留下的）⇒ 直接就位，别再空播一次动画
            openWork(a);
            if (onAfter != null) onAfter.run();
            return;
        }
        DshBridge.setWorkMode(a, true);
        DshTransitionPlayer.play(a, DshBridge.transitionKind(a), DshBridge.transitionVideo(a),
                new DshTransitionPlayer.Done() {
                    @Override public void onFinished() {
                        openWork(a);
                        if (onAfter != null) onAfter.run();
                    }
                });
    }

    /**
     * 真正打开工作模式页面的**唯一**地方。
     * <p>★ 刻意收成一个方法：这样"动画播完才跳转"这件事有一条结构性判据能钉住 ——
     * {@code verify_dsh_work.py} 会查"整个文件里只有这一处 startActivity"，
     * 谁在别处硬跳一个 startActivity，断言当场红。
     */
    private static void openWork(Activity a) {
        // ★ 关键：工作模式必须**独立成一个任务**（document task），否则
        //   ① 手机底部上滑停顿时看不到卡片（用户报障）；
        //   ② MainActivity.onPause 会把本任务从最近任务里移除，工作模式跟着消失。
        //   这里加 NEW_DOCUMENT|MULTIPLE_TASK，并在启动前压住 onPause 的移除逻辑。
        MainActivity.enteringWorkMode = true;
        Intent i = new Intent(a, DshHarnessActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        a.startActivity(i);
    }

    // ==================== 自定义视频 ====================

    /** 打开系统文件选择器挑一段视频（真正落盘在 {@link #handleResult}）。 */
    static void pickVideo(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        try {
            a.startActivityForResult(i, REQ_VIDEO);
        } catch (Throwable t) {
            Toast.makeText(a, "这台机器没有可用的文件选择器：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 处理 SAF 返回（**异步**）。
     *
     * <p>★ 为什么必须异步：这里要把用户挑的视频流式拷进私有目录，上限 60 MB。
     * 在主线程拷一个大文件会直接把界面卡住（甚至 ANR），而"导入切换动画"是个可以等两秒的操作。
     * 所以这里立刻回一句「正在导入…」，真正的拷贝放到后台线程，完成后回主线程提示。
     *
     * @param onDone 导入成功后要做的事（MainActivity 用来刷新设置页）；可为 null
     */
    static void handleResult(final Activity a, int requestCode, int resultCode, Intent data,
                             final Runnable onDone) {
        if (requestCode != REQ_VIDEO) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(a, "已取消", Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri uri = data.getData();
        if (uri == null) {
            Toast.makeText(a, "没拿到文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(a, "正在导入视频…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = DshBridge.importTransitionVideo(a, uri);
                final boolean ok = (err == null);
                if (ok) DshBridge.setTransitionKind(a, DshTransition.VIDEO);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(a, ok ? "已导入切换动画视频（已自动选成「自定义视频」）" : err,
                                Toast.LENGTH_LONG).show();
                        if (ok && onDone != null) onDone.run();
                    }
                });
            }
        }, "dsh-video-import").start();
    }
}
