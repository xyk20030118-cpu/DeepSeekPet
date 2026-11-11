package com.deepseek.pet.dsh;

import java.util.Map;

/**
 * 一个可执行工具。声明与执行分离：
 * {@link #spec()} 决定模型看到什么，{@link #run} 决定真的干什么。
 *
 * <p>实现必须<b>自己</b>校验参数（缺参、类型不对都要抛 {@link ToolException}），
 * 由 {@link DshToolRegistry} 统一转成 {@code isError} 的 tool-result 交还给模型 ——
 * 模型看到错误会自我修正，这比直接让整个对话失败有用得多。
 */
public interface DshTool {

    /** 工具声明（名字/说明/参数 schema）。 */
    DshToolSpec spec();

    /**
     * 执行。
     *
     * @param args 已解析的参数表（可能为空 map，但不会是 null）
     * @return 结果正文（写进 tool-result 的 text part）
     */
    String run(Map<String, Object> args) throws ToolException;

    /** 工具自己抛的失败（可读的中文原因，会原样进 tool-result）。 */
    final class ToolException extends Exception {
        public ToolException(String msg) { super(msg); }
    }
}
