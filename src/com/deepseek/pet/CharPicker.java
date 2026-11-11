package com.deepseek.pet;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色的**纯逻辑**（列表行 / 切换 / 删除 / **新增**）—— <b>零 Android 依赖</b>
 * （照 {@link LocalLines} / {@link Appearance} 的手法写，所以能在 {@code build/fpstest} 里做**真断言**）。
 *
 * <h3>★ 轮次㉛ 起：没有"内置角色"这回事了</h3>
 * 用户要求「**不留任何角色，只靠导入**」+「可以新增角色」⇒ 角色列表里<b>一个预置项都没有</b>：
 * <ul>
 *   <li>{@link CharacterStore#list} 只列用户自己新增/导入的角色，**可以为空**；</li>
 *   <li>APK 里的内置素材（待机动作 + 默认形象）退居为<b>素材兜底</b> ——
 *       没有任何角色时桌宠仍用它们显示，但界面上不再有"默认角色（内置）"这一行，
 *       也不能把它当角色来删/导出（它不是角色，只是素材）。</li>
 * </ul>
 *
 * <h3>它负责的四件容易出错的事</h3>
 * <ol>
 *   <li><b>列表行怎么写</b>（{@link #rowLabel}）：● 当前 / ○ 其他 —— 用户得一眼看出在哪个角色上；</li>
 *   <li><b>点了自己算不算切换</b>（{@link #switchNeeded}）：不算，否则白白 reloadPet + 整页重建；</li>
 *   <li><b>删掉之后当前角色指向谁</b>（{@link #currentAfterDelete}）：删的正好是当前角色时
 *       **落到剩下的第一个**；一个都不剩 ⇒ 回哨兵（{@link PetCharacter#BUILTIN_ID}）。
 *       不处理的话 prefs 里会留一个悬空 id，下次导入同名角色时行为变得难以解释；</li>
 *   <li><b>新增角色的 id 怎么生成</b>（{@link #newId}）：角色 id 是**目录名**，
 *       要净化（非法字符 / 路径穿越）、要唯一（同名不能覆盖别人的角色）、
 *       还要对中文名字友好（净化后可能全空 ⇒ 退回 {@code char} 前缀）。</li>
 * </ol>
 */
public final class CharPicker {

    private CharPicker() {}

    /** 新增角色时名字的长度上限（目录名 + 对话框一行都放得下） */
    public static final int NAME_MAX = 24;

    /** 名字兜底（角色目录里没有 character.json 时，目录名可能是个难看的 id） */
    public static String clean(String name) {
        if (name == null) return "未命名角色";
        String n = name.trim();
        return n.isEmpty() ? "未命名角色" : n;
    }

    /**
     * 角色 id 净化 —— <b>全工程唯一实现</b>：只留字母 / 数字 / 下划线 / 横线，
     * 其余（含 {@code : / \ .} 与空格）一律换成下划线；空值给 {@code "default"}。
     *
     * <p>★ 为什么放在这个**纯类**里、而不是 {@code ActionMenuConfig}：
     * 净化是"角色 id → 目录名 / 文件名"的唯一判据，而 {@code ActionMenuConfig} 含
     * Android 依赖（进不了 fpstest）。放在这里，{@code ActionMenuConfig.sanitize} 反过来调它 ——
     * 判据仍然只有一份，而且能离线断言。
     */
    public static String sanitizeId(String s) {
        if (s == null || s.isEmpty()) return "default";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            sb.append((Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') ? ch : '_');
        }
        return sb.toString();
    }

    /**
     * 选择列表里的一行：{@code ● 我的猫（当前）} / {@code ○ 工作搭子}。
     * <p>● = 当前（正在用的那个），○ = 其他。
     */
    public static String rowLabel(String name, boolean current) {
        return (current ? "\u25CF " : "\u25CB ") + clean(name) + (current ? "（当前）" : "");
    }

    /** 设置页那一行的文案（emoji 前缀由调用点加，本类不拼 emoji） */
    public static String currentRowLabel(String name, int total) {
        if (total <= 0) return "角色：还没有（点这里新增或导入）";
        return "角色：" + clean(name) + "（共 " + total + " 个）";
    }

    /** 一个角色都没有时的引导（列表与设置页共用同一句，免得两处说法不一致） */
    public static String emptyHint() {
        return "还没有角色。先「➕ 新增角色」自己做一个，或「📥 添加角色包」导入别人做好的。";
    }

    /** 新增成功后的下一句（告诉用户"接下来干什么"，否则建完一个空角色会一脸茫然） */
    public static String createdHint(String name) {
        return "已创建「" + clean(name) + "」。接下来可以给它「➕ 添加动作」、"
                + "或在「🎨 形象」里换成你自己的图片 / 动图 / Live2D。";
    }

    /**
     * 只有"真实存在的角色 id"能删。
     * <p>哨兵 {@link PetCharacter#BUILTIN_ID}（= "没有角色"）不是角色，空/脏 id 也不是。
     */
    public static boolean deletable(String id) {
        return id != null && !id.trim().isEmpty() && !PetCharacter.BUILTIN_ID.equals(id);
    }

    /**
     * 删掉 {@code deletingId} 之后，「当前角色」应该指向谁。
     *
     * @param remaining 删完之后**还在**的角色 id（顺序 = {@link CharacterStore#list} 的顺序）
     * @return 还在的第一个；一个都不剩 ⇒ 哨兵（= 没有角色，素材层用内置兜底）
     */
    public static String currentAfterDelete(String currentId, String deletingId,
                                            List<String> remaining) {
        boolean deletingCurrent = deletingId != null && deletingId.equals(currentId);
        if (!deletingCurrent && currentId != null && !currentId.trim().isEmpty()
                && !PetCharacter.BUILTIN_ID.equals(currentId)) {
            return currentId;                      // 删的不是当前那个 ⇒ 不动
        }
        if (remaining != null) {
            for (String id : remaining) {
                if (id != null && !id.trim().isEmpty()) return id;
            }
        }
        return PetCharacter.BUILTIN_ID;
    }

    /** 点了某个角色要不要真的切（点自己 = 不用切，省一次 reloadPet + 整页重建） */
    public static boolean switchNeeded(String currentId, String targetId) {
        if (targetId == null || targetId.trim().isEmpty()) return false;
        return !targetId.equals(currentId);
    }

    // ==================== 新增角色（轮次㉛）====================

    /**
     * 新角色的名字有没有问题（没问题返回 null，有问题返回**给用户看的那句话**）。
     * <p>三条：不能空、不能重名（重名会让用户以为"点了没反应"）、不能太长。
     */
    public static String nameError(List<String> existingNames, String input) {
        String n = (input == null) ? "" : input.trim();
        if (n.isEmpty()) return "名字不能为空";
        if (n.length() > NAME_MAX) return "名字太长了（最多 " + NAME_MAX + " 个字）";
        if (existingNames != null) {
            for (String e : existingNames) {
                if (e != null && n.equalsIgnoreCase(e.trim())) return "已经有同名角色了，换一个名字";
            }
        }
        return null;
    }

    /**
     * 由名字生成一个**唯一的角色 id**（= 目录名）。
     *
     * <p>规则：只保留字母 / 数字 / 下划线 / 横线（其余一律换成下划线，路径分隔符因此不可能出现）；
     * 净化后为空（例如名字全是中文）⇒ 退回 {@code char}；再撞上已有 id ⇒ 追加 {@code _2}、{@code _3}…
     * <p>★ 净化走 {@link #sanitizeId}（全工程唯一实现，{@code ActionMenuConfig.sanitize} 也调它）。
     */
    public static String newId(String name, List<String> taken) {
        String base = sanitizeId(name == null ? "" : name.trim());
        if (base.isEmpty() || "default".equals(base)) base = "char";
        if (base.length() > 32) base = base.substring(0, 32);
        if (taken == null || !taken.contains(base)) return base;
        for (int i = 2; i < 1000; i++) {
            String cand = base + "_" + i;
            if (!taken.contains(cand)) return cand;
        }
        return base + "_" + System.currentTimeMillis();
    }

    /** 删除列表里的一行 */
    public static String deleteRowLabel(String name) {
        return "\uD83D\uDDD1 " + clean(name);
    }

    /** 只有"能删的角色"才出现在删除列表里 */
    public static List<String> deletableIds(List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null) return out;
        for (String id : ids) if (deletable(id)) out.add(id);
        return out;
    }

    /**
     * 确认删除时的提示（把"要删掉什么"写清楚：角色包 + 这个角色的绑定/形象/表情/性格）。
     * <p>★ 轮次㉛ 起没有内置角色了，所以不再有"内置不受影响"那半句。
     */
    public static String deleteConfirmMessage(String name) {
        return "「" + clean(name) + "」的角色文件、以及这个角色自己的动作 / 形象 / 表情 / 性格 /"
                + "场景绑定都会被删掉。\n删了就没了（除非你导出过角色包）；全局记忆与 API Key 不受影响。";
    }
}
