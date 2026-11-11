package com.deepseek.pet;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「动作列表」的内容视图（轮次㉚）：<b>搜索框 + 排序按钮 +（可选）只看已选 + 列表</b>。
 *
 * <h3>它替代什么</h3>
 * 轮次⑳㉑ 起，动作库 / 场景改绑 / 自定义分类三处都是
 * {@code AlertDialog.setItems(...)} 或 {@code setMultiChoiceItems(...)} 的纯滚动列表：
 * 角色包有一百多个动作，找一个只能滚到底 —— 用户报的就是这个（待办 0m ③）。
 *
 * <h3>为什么不用 {@code setItems} 加搜索</h3>
 * {@code setItems} 装的是平台自己的 {@code ListView}，没法在它上面加搜索框；
 * 而 {@code setMessage} 又会把 {@code setItems} **静默吞掉**（本工程踩过，见 STATE 轮次㉑）。
 * 所以这里走 {@code setView(自定义视图)}，列表、搜索、排序全归自己管 ——
 * 对话框的按钮位（保存 / 取消 / 添加动作）仍然用 {@code AlertDialog} 的，
 * 不跟平台抢。
 *
 * <h3>勾选状态存哪</h3>
 * 存在本对象里（{@code LinkedHashSet}），<b>不</b>存在列表行上 ——
 * 列表会随搜索/排序重建，行是会被回收的。保存时用
 * {@link ClipList#pickedOf} 按**原始顺序**取，文件内容才不会因为
 * "当时正好按名称排序"而每次都不同。
 */
final class ClipListPane {

    /** 回调：单选点了某行 / 多选勾了某行 */
    interface Listener {
        void onPick(String id);
        void onToggle(String id, boolean checked);
    }

    private final Context ctx;
    private final Tokens.Set T;
    private final List<String> all = new ArrayList<String>();
    private final List<String> shown = new ArrayList<String>();
    private final java.util.HashMap<String, Integer> boundCount =
            new java.util.HashMap<String, Integer>();
    private final java.util.HashMap<String, Integer> weights =
            new java.util.HashMap<String, Integer>();
    private final Set<String> picked = new LinkedHashSet<String>();
    private final boolean multi;
    private final Listener listener;

    private int sortMode = ClipList.SORT_KEEP;
    private String query = "";
    private boolean onlyPicked = false;

    private final LinearLayout root;
    private final EditText search;
    private final TextView sortBtn;
    private final TextView countTv;
    private final CheckBox onlyPickedCb;
    private final ListView list;
    private final RowAdapter adapter = new RowAdapter();

    /**
     * @param ids         全部动作（**原始顺序**，也是保存时的顺序）
     * @param boundCounts 与 ids 一一对应的"已绑到几个场景"（可 null）
     * @param multi       true = 多选（勾选后由调用方保存）；false = 单选（点一下即回调）
     */
    ClipListPane(Context ctx, Tokens.Set T, List<String> ids, int[] boundCounts,
                 boolean multi, Listener listener) {
        this.ctx = ctx;
        this.T = T;
        this.multi = multi;
        this.listener = listener;
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                if (id == null || id.isEmpty() || all.contains(id)) continue;
                all.add(id);
                boundCount.put(id, (boundCounts != null && i < boundCounts.length)
                        ? Integer.valueOf(boundCounts[i]) : Integer.valueOf(0));
            }
        }

        final int pad = UiFactory.dp(ctx, 16);
        final float density = ctx.getResources().getDisplayMetrics().density;

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, UiFactory.dp(ctx, 4), pad, 0);

        // —— 搜索框 ——
        search = new EditText(ctx);
        search.setSingleLine(true);
        search.setHint("搜索动作名（空格分词，全部命中才显示）");
        search.setTextSize(14);
        search.setTextColor(T.text1);
        search.setHintTextColor(T.text3);
        search.setBackground(UiKit.solidFill(ctx, T, T.surfaceSunken, Tokens.R_INPUT));
        final int ep = UiFactory.dp(ctx, 10);
        search.setPadding(ep, ep, ep, ep);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                query = (e == null) ? "" : e.toString();
                rebuild();
            }
        });
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // —— 排序按钮 +（多选时）只看已选 ——
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, UiFactory.dp(ctx, 8), 0, 0);

        sortBtn = new TextView(ctx);
        sortBtn.setText(ClipList.sortButtonText(sortMode));
        sortBtn.setTextSize(14);
        sortBtn.setTextColor(T.accentInk);
        sortBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sortBtn.setPadding(UiFactory.dp(ctx, 12), UiFactory.dp(ctx, 8),
                UiFactory.dp(ctx, 12), UiFactory.dp(ctx, 8));
        sortBtn.setBackground(UiKit.solidFill(ctx, T, T.accentSoft, Tokens.R_PILL));
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sortMode = ClipList.nextSort(sortMode);
                rebuild();
            }
        });
        bar.addView(sortBtn);

        if (multi) {
            onlyPickedCb = new CheckBox(ctx);
            onlyPickedCb.setText(ClipList.onlyPickedText(0, all.size()));
            onlyPickedCb.setTextSize(13);
            onlyPickedCb.setTextColor(T.text2);
            onlyPickedCb.setPadding(UiFactory.dp(ctx, 8), 0, 0, 0);
            onlyPickedCb.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override public void onCheckedChanged(
                                android.widget.CompoundButton b, boolean on) {
                            onlyPicked = on;
                            rebuild();
                        }
                    });
            bar.addView(onlyPickedCb);
        } else {
            onlyPickedCb = null;
        }
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // —— 计数行 ——
        countTv = new TextView(ctx);
        countTv.setTextSize(12);
        countTv.setTextColor(T.text3);
        countTv.setPadding(UiFactory.dp(ctx, 2), UiFactory.dp(ctx, 8),
                UiFactory.dp(ctx, 2), UiFactory.dp(ctx, 4));
        root.addView(countTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // —— 列表 ——
        list = new ListView(ctx);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(0);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shown.size()) return;
                String cid = shown.get(position);
                if (multi) {
                    if (picked.contains(cid)) picked.remove(cid);
                    else picked.add(cid);
                    if (listener != null) listener.onToggle(cid, picked.contains(cid));
                    rebuild();
                } else if (listener != null) {
                    listener.onPick(cid);
                }
            }
        });

        // 对话框里的列表必须有**确定高度**：ListView 在 wrap_content 下要么 0 高、
        // 要么把对话框撑到屏幕外（这里给屏幕的 62%，上限 520dp）。
        int h = (int) Math.min(ctx.getResources().getDisplayMetrics().heightPixels * 0.62,
                520 * density);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        rebuild();
    }

    View view() { return root; }

    /** 勾选清单（**原始顺序**，可直接落盘） */
    List<String> pickedIds() {
        return ClipList.pickedOf(all, new ArrayList<String>(picked));
    }

    int pickedCount() { return picked.size(); }

    /** 当前显示了几行（设备脚本/断言用） */
    int shownCount() { return shown.size(); }

    String sortButtonText() { return sortBtn.getText().toString(); }

    String countText() { return countTv.getText().toString(); }

    /** 预设勾选（编辑已有绑定/分类时用） */
    void setPicked(List<String> ids) {
        picked.clear();
        if (ids != null) for (String id : ids) if (id != null && all.contains(id)) picked.add(id);
        rebuild();
    }

    /** 权重（改绑时显示 ×N；0 或缺失 = 不显示） */
    void setWeights(List<Integer> w) {
        weights.clear();
        if (w != null) {
            for (int i = 0; i < all.size() && i < w.size(); i++) {
                Integer v = w.get(i);
                if (v != null && v > 0) weights.put(all.get(i), v);
            }
        }
        rebuild();
    }

    // ==================== 内部 ====================

    private void rebuild() {
        List<String> filtered = ClipList.filter(all, query);
        if (onlyPicked && multi) {
            List<String> only = new ArrayList<String>();
            for (String id : filtered) if (picked.contains(id)) only.add(id);
            filtered = only;
        }
        int[] counts = new int[filtered.size()];
        for (int i = 0; i < filtered.size(); i++) {
            Integer c = boundCount.get(filtered.get(i));
            counts[i] = (c == null) ? 0 : c.intValue();
        }
        shown.clear();
        shown.addAll(ClipList.sorted(filtered, counts, sortMode));
        adapter.notifyDataSetChanged();
        countTv.setText(ClipList.countText(all.size(), shown.size(), query));
        sortBtn.setText(ClipList.sortButtonText(sortMode));
        if (onlyPickedCb != null) {
            onlyPickedCb.setText(ClipList.onlyPickedText(picked.size(), all.size()));
        }
    }

    private String labelOf(String id) {
        if (multi) {
            Integer w = weights.get(id);
            return ClipList.bindRowLabel(id, (w == null) ? 0 : w.intValue());
        }
        Integer c = boundCount.get(id);
        return ClipList.rowLabel(id, (c == null) ? 0 : c.intValue());
    }

    /** 行 = [动作名][（多选时）勾选框]；勾选框不自己吃点击，整行点击才算 */
    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView tv;
            CheckBox cb;
            if (convert instanceof LinearLayout && convert.getTag() instanceof String) {
                row = (LinearLayout) convert;
                tv = (TextView) row.getChildAt(0);
                cb = (CheckBox) row.getChildAt(1);
            } else {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(UiFactory.dp(ctx, 48));
                row.setPadding(UiFactory.dp(ctx, 6), 0, UiFactory.dp(ctx, 6), 0);
                row.setBackground(UiFactory.rowPressable(ctx, T, Tokens.R_CHIP));
                tv = new TextView(ctx);
                tv.setTextSize(15);
                tv.setTextColor(T.text1);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(tv, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                cb = new CheckBox(ctx);
                cb.setClickable(false);
                cb.setFocusable(false);
                cb.setDuplicateParentStateEnabled(false);
                row.addView(cb, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setTag("cliprow");
            }
            String id = shown.get(position);
            tv.setText(labelOf(id));
            cb.setVisibility(multi ? View.VISIBLE : View.GONE);
            cb.setChecked(multi && picked.contains(id));
            return row;
        }
    }
}
