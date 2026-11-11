package com.deepseek.pet;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 可拖拽排序的竖列表（轮次㉚）：<b>按住左侧 ≡ 手柄上下拖动</b>，松手落定。
 *
 * <h3>只用在"顺序真的有用"的地方</h3>
 * 这个工程里，动作顺序<b>不是到处都有意义</b>：
 * <ul>
 *   <li><b>场景绑定顺序 → 没有意义</b>：播放时 {@link PetAnimPack.Group#pick} 按**权重随机**抽，
 *       顺序不参与（所以场景改绑页只给搜索+排序，不给拖拽 —— 给了就是假功能）；</li>
 *   <li><b>自定义动作菜单里的顺序 → 真的有用</b>：三击桌宠弹出的菜单按
 *       {@link ActionMenuConfig} 里的顺序排（分类顺序 + 分类内动作顺序），
 *       用户拖一下就能把常用的挪到最前面。</li>
 * </ul>
 *
 * <h3>为什么不用 {@code ListView} + 拖拽</h3>
 * {@code ListView} 会**回收**行视图：拖动中一旦 {@code notifyDataSetChanged()}，
 * 正在接收触摸事件的那一行可能被回收掉，手势当场断掉（本工程在"点空"
 * 那一类问题上吃过亏）。这里用 {@code ScrollView + LinearLayout}，行**不回收**、
 * 拖动中只改 {@code translationY}（不重排视图、不摘挂父容器），手势因此是稳的；
 * 行数（自定义分类的动作，最多一百来个）也撑得住。
 */
final class DragSortPane {

    private final Context ctx;
    private final Tokens.Set T;
    private final List<String> items = new ArrayList<String>();
    private final LinearLayout root;
    private final ScrollView scroll;
    private final LinearLayout column;

    private int dragFrom = -1;
    private int dragTo = -1;
    private float downRawY;
    private View dragRow;
    private View highlightRow;

    /** @param labels 初始顺序（**会被复制**，调用方那份不动） */
    DragSortPane(Context ctx, Tokens.Set T, List<String> labels) {
        this.ctx = ctx;
        this.T = T;
        if (labels != null) for (String s : labels) if (s != null && !s.isEmpty()) items.add(s);

        scroll = new ScrollView(ctx);
        scroll.setFillViewport(false);
        scroll.setClipChildren(false);
        column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rebuildRows();

        // 列表高度同 ClipListPane：对话框里必须给确定高度
        float density = ctx.getResources().getDisplayMetrics().density;
        int h = (int) Math.min(ctx.getResources().getDisplayMetrics().heightPixels * 0.52,
                440 * density);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = UiFactory.dp(ctx, 16);
        root.setPadding(pad, UiFactory.dp(ctx, 4), pad, 0);
        TextView hint = new TextView(ctx);
        hint.setText(ClipList.dragHint());
        hint.setTextSize(12);
        hint.setTextColor(T.text3);
        hint.setPadding(UiFactory.dp(ctx, 2), 0, UiFactory.dp(ctx, 2), UiFactory.dp(ctx, 8));
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);
    }

    View view() { return root; }

    /** 拖完之后的顺序（对话框的「保存」读它） */
    List<String> items() { return new ArrayList<String>(items); }

    /** 行数（断言/设备脚本用） */
    int size() { return items.size(); }

    // ==================== 行 ====================

    private void rebuildRows() {
        column.removeAllViews();
        dragFrom = -1;
        dragTo = -1;
        dragRow = null;
        highlightRow = null;
        for (int i = 0; i < items.size(); i++) column.addView(buildRow(i));
    }

    private View buildRow(final int index) {
        final String label = items.get(index);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiFactory.dp(ctx, 48));
        row.setBackground(UiKit.solidFill(ctx, T, 0x00000000, Tokens.R_CHIP));

        TextView handle = new TextView(ctx);
        handle.setText("\u2261");             // ≡ 拖动排序的把手（也是设备脚本要找的）
        handle.setTextSize(20);
        handle.setTextColor(T.text3);
        handle.setGravity(Gravity.CENTER);
        handle.setTypeface(Typeface.DEFAULT_BOLD);
        handle.setContentDescription("drag_handle");
        row.addView(handle, new LinearLayout.LayoutParams(UiFactory.dp(ctx, 44),
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(T.text1);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // ★ 必须挡住 ScrollView 的拦截：否则它一看到竖向移动就拿走手势，
                        //   我们只会收到一个 ACTION_CANCEL，拖动等于没接上。
                        scroll.requestDisallowInterceptTouchEvent(true);
                        beginDrag(index, e.getRawY());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        updateDrag(e.getRawY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        endDrag(true);
                        scroll.requestDisallowInterceptTouchEvent(false);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        endDrag(false);
                        scroll.requestDisallowInterceptTouchEvent(false);
                        return true;
                    default:
                        return false;
                }
            }
        });
        return row;
    }

    // ==================== 拖拽 ====================

    private void beginDrag(int index, float rawY) {
        dragFrom = index;
        dragTo = index;
        downRawY = rawY;
        dragRow = column.getChildAt(index);
        android.util.Log.i("DSDrag", "开始拖动 index=" + index + " 行数=" + items.size());
        if (dragRow != null) {
            dragRow.setAlpha(0.9f);
            dragRow.setElevation(UiFactory.dp(ctx, 8));
        }
        highlight(index);
    }

    private void updateDrag(float rawY) {
        if (dragRow == null) return;
        dragRow.setTranslationY(rawY - downRawY);
        int[] loc = new int[2];
        column.getLocationOnScreen(loc);
        int y = (int) (rawY - loc[1]);
        int to = ClipList.dropIndex(rowMids(), y);
        if (to >= 0 && to != dragTo) {
            dragTo = to;
            highlight(to);
            android.util.Log.i("DSDrag", "拖到第 " + to + " 行");
        }
        autoScroll(rawY);
    }

    /**
     * 收尾：把拖出来的顺序定下来，然后重建行。
     *
     * <p>★★ 这里有两处**实测踩出来的**坑（轮次㉚，模拟器上 drag 松手当场崩）：
     * <ol>
     *   <li><b>UP 之后还会再来一个 CANCEL</b>（我们把 touch 目标自己摘掉了）——
     *       不加守卫就会连着重排两次，日志里能看到 `松手` 与 `重排完成` 各打两遍；</li>
     *   <li><b>结构改动（{@code removeAllViews}/{@code addView}）不能直接写在触摸回调里</b>：
     *       实测崩在 {@code ViewGroup.dispatchDraw} 的
     *       {@code Attempt to read from field 'int android.view.View.mViewFlags' on a null object}
     *       —— 视图树在输入分发过程中被换掉，绘制期就看到了一个空 child。
     *       所以 {@code post} 到下一帧再重建（顺序数据当场就定了，界面晚一帧刷新）。</li>
     * </ol>
     */
    private void endDrag(boolean commit) {
        if (dragRow == null && dragFrom < 0) return;        // 已经收过尾（UP→CANCEL 的第二次）
        android.util.Log.i("DSDrag", "松手 from=" + dragFrom + " to=" + dragTo + " commit=" + commit);
        if (commit && dragFrom >= 0 && dragTo >= 0 && dragTo != dragFrom) {
            List<String> now = ClipList.move(items, dragFrom, dragTo);
            items.clear();
            items.addAll(now);
        }
        dragFrom = -1;
        dragTo = -1;
        dragRow = null;
        highlightRow = null;
        column.post(new Runnable() {
            @Override public void run() {
                rebuildRows();
                android.util.Log.i("DSDrag", "重排完成 顺序=" + items);
            }
        });
    }

    /** 每行**中线**在 column 坐标系里的 y（升序）—— 命中测试只认这个 */
    private int[] rowMids() {
        int n = column.getChildCount();
        int[] mids = new int[n];
        for (int i = 0; i < n; i++) {
            View v = column.getChildAt(i);
            mids[i] = v.getTop() + v.getHeight() / 2;
        }
        return mids;
    }

    private void highlight(int index) {
        if (highlightRow != null) {
            highlightRow.setBackground(UiKit.solidFill(ctx, T, 0x00000000, Tokens.R_CHIP));
            highlightRow = null;
        }
        View v = column.getChildAt(index);
        if (v != null) {
            v.setBackground(UiKit.solidFill(ctx, T, T.accentSoft, Tokens.R_CHIP));
            highlightRow = v;
        }
    }

    /**
     * 拖到上下边缘时自动滚 —— 否则一百来个动作拖不到最后一行。
     *
     * <p>★ <b>内容还没占满一屏时绝不能滚</b>（实测）：那时本来就没有东西可滚，
     * 而"手指靠近边缘就滚 10px"会让**整列上移**，落点判定（{@code y - columnTop}）
     * 跟着漂 —— 表现成"明明拖到第一行，松手却停在第 2 行"（设备脚本抓到的）。
     */
    private void autoScroll(float rawY) {
        if (column.getHeight() <= scroll.getHeight()) return;
        int[] loc = new int[2];
        scroll.getLocationOnScreen(loc);
        int top = loc[1];
        int bottom = top + scroll.getHeight();
        int edge = UiFactory.dp(ctx, 56);
        int step = UiFactory.dp(ctx, 10);
        if (rawY < top + edge) scroll.scrollBy(0, -step);
        else if (rawY > bottom - edge) scroll.scrollBy(0, step);
    }
}
