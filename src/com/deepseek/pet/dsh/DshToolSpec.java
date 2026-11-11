package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具声明 —— 一个工具"长什么样"，只描述不执行。
 *
 * <p>上游 {@code request/header} 事件里那份 36 KB 的 {@code tools} 数组就是这个东西的成品：
 * 模型只看声明决定要不要调、怎么填参；执行是另一层（{@link DshTool}）。
 *
 * <p>参数表按 JSON Schema 写（{@code type/properties/required/additionalProperties}）。
 * ★ 只用这四种关键字：DeepSeek / Kimi / 各家网关都认；{@code oneOf} / {@code pattern}
 * 这类在部分网关上会被静默丢弃 —— 那样"必填"就变成了"随便"。
 */
public final class DshToolSpec {

    public final String name;
    public final String description;
    /** JSON Schema（object 根）。保持插入顺序，声明顺序即模型看到的顺序。 */
    public final Map<String, Object> parameters;

    public DshToolSpec(String name, String description, Map<String, Object> parameters) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.parameters = parameters == null ? objectSchema() : parameters;
    }

    /** 空 object schema。 */
    public static Map<String, Object> objectSchema() {
        Map<String, Object> s = DshJson.map("type", "object");
        s.put("properties", DshJson.map());
        s.put("additionalProperties", Boolean.FALSE);
        return s;
    }

    /**
     * 便利构造：一组 (名字, 类型, 说明) 三元组 → object schema。
     * <p>可空参数的写法是"名字以 {@code ?} 结尾"（例：{@code "offset?"}），
     * 这样调用方不用自己拼 required 列表。
     */
    public static Map<String, Object> params(String... triples) {
        Map<String, Object> props = DshJson.map();
        List<Object> required = new ArrayList<Object>();
        for (int i = 0; i + 2 < triples.length; i += 3) {
            String raw = triples[i];
            boolean optional = raw.endsWith("?");
            String key = optional ? raw.substring(0, raw.length() - 1) : raw;
            Map<String, Object> p = DshJson.map("type", triples[i + 1]);
            p.put("description", triples[i + 2]);
            props.put(key, p);
            if (!optional) required.add(key);
        }
        Map<String, Object> s = DshJson.map("type", "object");
        s.put("properties", props);
        if (!required.isEmpty()) s.put("required", required);
        s.put("additionalProperties", Boolean.FALSE);
        return s;
    }

    /** 编成 {@code tools} 数组（OpenAI 的 function 形态）。 */
    public static List<Object> toJsonArray(List<DshToolSpec> specs) {
        List<Object> out = new ArrayList<Object>();
        if (specs == null) return out;
        for (DshToolSpec s : specs) {
            if (s == null) continue;
            Map<String, Object> fn = DshJson.map("name", s.name, "description", s.description);
            fn.put("parameters", s.parameters);
            out.add(DshJson.map("type", "function", "function", fn));
        }
        return out;
    }
}
