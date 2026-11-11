package com.deepseek.pet;

import java.io.File;

/**
 * 一个「角色」的元信息（形象 + 动作套组 + 表情 + 性格 的容器）。
 *
 * <h3>★ 轮次㉛ 起：只有一种角色</h3>
 * 用户要求「不留任何角色，只靠导入」+「可以新增角色」⇒ 角色列表里**没有任何预置项**：
 * <ul>
 *   <li>每一个角色都是 {@code filesDir/characters/<id>/} 下的真实目录（新增或导入来的），
 *       人人平等：可改名、可设形象、可导出、可删除；</li>
 *   <li>{@link #BUILTIN_ID} 不再是"内置角色"，它退化成 prefs 里
 *       <b>"当前没有角色"的哨兵</b>（{@code CharacterStore.current()} 会返回一个没有目录的合成壳，
 *       素材层对"没有目录"是**内置素材兜底**，所以桌宠不会变成空白）；</li>
 *   <li>{@link #builtin} 这个字段现在**恒为 false**（保留是为了不改构造签名与测试脚手架）。</li>
 * </ul>
 * 记忆**不随角色**（用户 2026-10-07 决定）：切角色只换 形象/动作/场景/表情/性格。
 */
public class PetCharacter {

    /** ★ 不是"内置角色"，而是 prefs 里"**当前没有角色**"的哨兵（素材层对没有目录的情况走 assets 兜底） */
    public static final String BUILTIN_ID = "builtin:default";

    /** 角色目录标准结构（用户角色） */
    public static final String F_CHARACTER = "character.json";   // 元信息
    public static final String F_ANIM_DIR  = "anim";             // 动作套组
    public static final String F_PACK      = "anim/pack.json";   // 场景 → 动作（2.0）
    public static final String F_LINES     = "anim/lines.json";  // 动作 → 台词
    public static final String F_CLIPS     = "anim/clips";       // 动作素材（*.webp / *.gif）
    public static final String F_MEMES_DIR = "memes";            // 表情包
    public static final String F_MEMES     = "memes/memes.json";
    public static final String F_PERSONA   = "persona.md";       // 性格提示词

    public final String id;
    public final String name;
    public final String author;
    public final String version;
    public final boolean builtin;
    /** 用户角色目录；内置角色为 null（素材在 assets） */
    public final File dir;

    public PetCharacter(String id, String name, String author, String version,
                        boolean builtin, File dir) {
        this.id = id;
        this.name = (name == null || name.isEmpty()) ? id : name;
        this.author = author == null ? "" : author;
        this.version = version == null ? "" : version;
        this.builtin = builtin;
        this.dir = dir;
    }

    public boolean isBuiltin() { return builtin; }

    /** 内置角色的展示名（用户角色用包里的名字） */
    public String displayName() { return name; }

    /** 角色目录（用户角色）；内置角色没有目录，返回 null */
    public File dirOf() { return dir; }

    /** 动作配置文件：内置走 assets，用户角色走包内 */
    public String packRelPath() { return F_PACK; }

    /** 角色素材目录（clips）：内置 = assets/webp，用户 = <dir>/anim/clips */
    public File clipsDir() {
        return dir == null ? null : new File(dir, F_CLIPS);
    }

    @Override public String toString() {
        return "PetCharacter{" + id + ", builtin=" + builtin + "}";
    }
}
