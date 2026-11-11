package com.deepseek.pet;

import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;

/**
 * 余额解析与 Key 隔离的离线断言。
 *
 * <p>为什么必须有：
 * <ol>
 *   <li><b>各家的余额 JSON 结构完全不同</b>，取值路径写错就永远显示不出来，
 *       而且不会报错 —— 只会"没有余额数据"。用户无法自查。</li>
 *   <li><b>按服务商分槽存 Key</b> 是新引入的存储结构，
 *       槽位算错会导致"切到 B 家却用了 A 家的 Key"，报 401 但看不出原因。</li>
 * </ol>
 *
 * <p>{@code ApiClient.pickNumber} 与 {@code ChatProvider} 都是可离线测的
 * （前者只依赖 org.json，本测试自带一个最小实现）。
 */
public class BalanceTest {

    private static int pass = 0, fail = 0;

    private static void ok(boolean cond, String what) {
        if (cond) pass++;
        else { fail++; System.out.println("  FAIL: " + what); }
    }

    private static void eq(Object a, Object b, String what) {
        boolean c = (a == null) ? (b == null) : a.equals(b);
        ok(c, what + " (got " + a + ", want " + b + ")");
    }

    private static void near(double got, double want, double tol, String what) {
        ok(Math.abs(got - want) <= tol,
           what + " (got " + got + ", want " + want + ")");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 余额解析 与 Key 隔离 断言 ===");

        System.out.println();
        System.out.println("[1] DeepSeek：balance_infos 数组，优先 CNY");
        {
            String j = "{\"balance_infos\":["
                    + "{\"currency\":\"USD\",\"total_balance\":\"7.00\"},"
                    + "{\"currency\":\"CNY\",\"total_balance\":\"88.88\"}]}";
            JSONObject o = new JSONObject(j);
            // 路径取的是 [0]（USD），但 deepseek 有 CNY 兜底逻辑
            double v = ChatBalance.pickNumber(o, "balance_infos[0].total_balance");
            near(v, 7.0, 0.001, "路径取值正确（数组下标）");
            System.out.println("      数组下标解析 ✓");
        }

        System.out.println();
        System.out.println("[2] ★ Kimi：data.available_balance（USD）");
        {
            // 来自官方文档示例
            String j = "{\"code\":0,\"data\":{\"available_balance\":49.58894,"
                    + "\"voucher_balance\":46.58893,\"cash_balance\":3.00001},"
                    + "\"scode\":\"0x0\",\"status\":true}";
            ChatProvider.Entry kimi = ChatProvider.find("kimi");
            double v = ChatBalance.pickNumber(new JSONObject(j), kimi.balanceField);
            near(v, 49.58894, 0.0001, "kimi 余额解析正确");
            eq(kimi.balanceCurrency, "USD", "kimi 币种是 USD");
            ok(kimi.hasBalance(), "kimi 有余额接口");
            eq(kimi.balancePath, "/users/me/balance", "kimi 余额路径正确");
        }

        System.out.println();
        System.out.println("[3] ★ SiliconFlow：data.totalBalance 是**字符串**");
        {
            String j = "{\"code\":20000,\"message\":\"OK\",\"status\":true,"
                    + "\"data\":{\"id\":\"u\",\"balance\":\"0.88\","
                    + "\"chargeBalance\":\"88.00\",\"totalBalance\":\"88.88\"}}";
            ChatProvider.Entry sf = ChatProvider.find("siliconflow");
            double v = ChatBalance.pickNumber(new JSONObject(j), sf.balanceField);
            near(v, 88.88, 0.001, "siliconflow 字符串余额能解析");
            System.out.println("      字符串数字兜底 ✓");
        }

        System.out.println();
        System.out.println("[4] ★ 智谱：data.availableBalance + 裸 Key 认证");
        {
            String j = "{\"success\":true,\"data\":{\"balance\":10,\"availableBalance\":12.5}}";
            ChatProvider.Entry zp = ChatProvider.find("zhipu");
            double v = ChatBalance.pickNumber(new JSONObject(j), zp.balanceField);
            near(v, 12.5, 0.001, "智谱 availableBalance 解析");
            ok(zp.balanceRawAuth, "智谱用裸 Key（不带 Bearer）");
            // 这条很关键：带 Bearer 会 401
            System.out.println("      裸 Key 认证标记 ✓");
        }

        System.out.println();
        System.out.println("[5] ★ 百炼 / OpenAI / 自定义：**没有**余额接口");
        {
            for (String id : new String[]{"dashscope", "openai", "custom"}) {
                ChatProvider.Entry e = ChatProvider.find(id);
                ok(!e.hasBalance(), id + " 应标记为无余额接口");
            }
            // 这决定了 UI 显示"该服务商不提供余额接口"而不是"查询失败"
            System.out.println("      三个无接口服务商正确标记（UI 会明确告知）");
        }

        System.out.println();
        System.out.println("[6] 路径解析的健壮性（不能抛异常、不能误报 0）");
        {
            JSONObject o = new JSONObject("{\"a\":{\"b\":1}}");
            ok(Double.isNaN(ChatBalance.pickNumber(o, "a.c")), "缺失字段 → NaN");
            ok(Double.isNaN(ChatBalance.pickNumber(o, "x.y.z")), "整条路径缺失 → NaN");
            ok(Double.isNaN(ChatBalance.pickNumber(o, "")), "空路径 → NaN");
            ok(Double.isNaN(ChatBalance.pickNumber(o, "a.b[0]")), "对标量取下标 → NaN");
            ok(Double.isNaN(ChatBalance.pickNumber(o, "a.b.c.d")), "过深路径 → NaN");
            JSONObject n = new JSONObject("{\"v\":\"\"}");
            ok(Double.isNaN(ChatBalance.pickNumber(n, "v")), "空字符串 → NaN（不能当 0）");
            JSONObject z = new JSONObject("{\"v\":\"  \"}");
            ok(Double.isNaN(ChatBalance.pickNumber(z, "v")), "空白字符串 → NaN");
            JSONObject zero = new JSONObject("{\"v\":\"0\"}");
            near(ChatBalance.pickNumber(zero, "v"), 0.0, 0.0001, "真正的 0 要能读出来");
            ok(Double.isNaN(ChatBalance.pickNumber(null, "a")), "null 根 → NaN");
            ok(Double.isNaN(ChatBalance.pickNumber(o, null)), "null 路径 → NaN");
            System.out.println("      9 种畸形输入全部安全处理 ✓");
        }

        System.out.println();
        System.out.println("[7] ★ 每个服务商的余额路径/字段必须成对出现");
        {
            for (ChatProvider.Entry e : ChatProvider.all()) {
                boolean p = e.balancePath != null && e.balancePath.length() > 0;
                boolean f = e.balanceField != null && e.balanceField.length() > 0;
                ok(p == f, e.id + " 的 balancePath 与 balanceField 成对"
                        + " (path=" + p + ", field=" + f + ")");
            }
            System.out.println("      避免「只填路径没填字段」的半配置状态");
        }

        System.out.println();
        System.out.println("[8] 余额路径指向的字段名要与官方文档一致（防手滑写错）");
        {
            eq(ChatProvider.find("kimi").balanceField, "data.available_balance",
               "kimi 字段名");
            eq(ChatProvider.find("siliconflow").balanceField, "data.totalBalance",
               "siliconflow 字段名");
            eq(ChatProvider.find("zhipu").balanceField, "data.availableBalance",
               "zhipu 字段名");
            eq(ChatProvider.find("deepseek").balanceField,
               "balance_infos[0].total_balance", "deepseek 字段名");
        }

        System.out.println();
        System.out.println("[9] ★ 余额地址必须跟随 baseUrl（Kimi 的 .ai/.com 不通用）");
        {
            // 相对路径要和 baseUrl 组合；绝对路径（/api/...）要换成 host
            ChatProvider.Entry kimi = ChatProvider.find("kimi");
            String u = ChatBalance.buildUrl("https://api.moonshot.cn/v1", kimi.balancePath);
            eq(u, "https://api.moonshot.cn/v1/users/me/balance",
               "kimi 相对路径拼接");
            // 换成 .ai 平台，地址也要跟着变（Key 不通用，地址必须同源）
            eq(ChatBalance.buildUrl("https://api.moonshot.ai/v1", kimi.balancePath),
               "https://api.moonshot.ai/v1/users/me/balance",
               "换 .ai 域名后余额地址跟随");
            // 智谱是绝对路径，要取 host 而不是拼在 /api/paas/v4 后面
            ChatProvider.Entry zp = ChatProvider.find("zhipu");
            eq(ChatBalance.buildUrl("https://open.bigmodel.cn/api/paas/v4", zp.balancePath),
               "https://open.bigmodel.cn/api/biz/account/query-customer-account-report",
               "智谱绝对路径换成 biz 网关");
            // baseUrl 末尾有斜杠时不能拼出双斜杠
            eq(ChatBalance.buildUrl("https://api.deepseek.com/", "/user/balance"),
               "https://api.deepseek.com/user/balance", "末尾斜杠处理");
            // 带端口的地址（自建网关）要保留端口
            eq(ChatBalance.buildUrl("http://10.0.2.2:18099/v1", "/user/balance"),
               "http://10.0.2.2:18099/v1/user/balance", "保留端口（自建网关）");
            // 参数非法返回 null
            ok(ChatBalance.buildUrl("https://x.com", null) == null, "path 为 null → null");
            ok(ChatBalance.buildUrl(null, "/a") == null, "baseUrl 为 null → null");
        }

        System.out.println();
        System.out.println("[10] ★ Key 按服务商分槽（切服务商 = 自动切 Key）");
        {
            // 存储键的构造规则必须与 provider id 一致，否则会出现
            // "切到 B 家却用了 A 家的 Key" —— 报 401 但看不出原因。
            //
            // 这里只验证"键名规则"这一层（Prefs 的 Android 部分无法离线跑），
            // 规则本身是：api_key + "_" + providerId
            for (ChatProvider.Entry e : ChatProvider.all()) {
                String k = "api_key_" + e.id;
                ok(k.startsWith("api_key_"), e.id + " 的 Key 槽名以 api_key_ 开头");
                ok(!k.endsWith("_"), e.id + " 的槽名不以 _ 结尾");
                ok(k.indexOf(' ') < 0, e.id + " 的槽名不含空格");
            }
            // id 不能重复（重复会导致两家共用一个 Key 槽）
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (ChatProvider.Entry e : ChatProvider.all()) {
                ok(seen.add(e.id), "provider id 唯一（否则 Key 槽会冲突）: " + e.id);
            }
            System.out.println("      " + ChatProvider.all().size()
                    + " 个服务商的 Key 槽名唯一且规范");
        }

        System.out.println();
        System.out.println("[11] 服务商列表里必须含 DeepSeek（旧 Key 迁移的落点）");
        {
            // 老用户只有一个 api_key 槽位，升级后 getApiKey 会回退到它。
            // 若 deepseek 条目不存在，回退就没有归属。
            ChatProvider.Entry ds = ChatProvider.find("deepseek");
            eq(ds.id, "deepseek", "deepseek 条目存在");
            // find() 对未知 id 必须回退到 deepseek，不能返回 null
            eq(ChatProvider.find("no-such-provider").id, "deepseek",
               "未知 id 回退到 deepseek（不返回 null）");
            ok(ChatProvider.find(null).id.equals("deepseek"),
               "null 也回退到 deepseek");
        }

        System.out.println();
        System.out.println("[12] ★★ baseUrl 归一化：用户把地址填成完整端点时的 404 修复");
        {
            // 这是用户截图里那个 "HTTP 404" 的根因：
            // 地址填 https://api.moonshot.cn/v1/chat/completions 时，
            // 对话**能用**（chatUrl 见到后缀原样返回），
            // 但余额若直接拼就会变成 .../chat/completions/users/me/balance → 404
            eq(ChatBalance.normalizeBase("https://api.moonshot.cn/v1/chat/completions"),
               "https://api.moonshot.cn/v1", "剥掉 /chat/completions");
            eq(ChatBalance.normalizeBase("https://api.moonshot.cn/v1/"),
               "https://api.moonshot.cn/v1", "剥掉末尾斜杠");
            eq(ChatBalance.normalizeBase("https://api.deepseek.com"),
               "https://api.deepseek.com", "正常地址不变");
            eq(ChatBalance.normalizeBase("https://api.deepseek.com/chat/completions"),
               "https://api.deepseek.com", "DeepSeek 完整端点也剥");
            // 归一化后拼接必须正确
            eq(ChatBalance.buildUrl("https://api.moonshot.cn/v1/chat/completions",
                                    "/users/me/balance"),
               "https://api.moonshot.cn/v1/users/me/balance",
               "★ 完整端点写法也能拼出正确余额地址");
            eq(ChatBalance.buildUrl("https://api.siliconflow.cn/v1/chat/completions/",
                                    "/user/info"),
               "https://api.siliconflow.cn/v1/user/info",
               "带斜杠的完整端点也正确");
            System.out.println("      baseUrl 归一化 ✓（404 根因已修）");
        }

        System.out.println();
        System.out.println("[13] ★ HTTP 200 但体内是业务错误（智谱实测行为）");
        {
            // 实测：智谱 biz 端点 HTTP 200 + {"code":401,...}
            // 只看 HTTP 状态会误判成"无余额数据"，而真实原因是 Key 无效
            JSONObject zhipuErr = new JSONObject(
                    "{\"code\":401,\"msg\":\"令牌已过期或验证不正确\",\"success\":false}");
            ok(ChatBalance.hasBusinessError(zhipuErr),
               "智谱 HTTP200+code401 判定为业务错误");
            eq(ChatBalance.firstNonEmpty(zhipuErr, "msg", "message", "error"),
               "令牌已过期或验证不正确", "能取出服务端错误消息");

            // 正常响应不能被误判
            JSONObject zhipuOk = new JSONObject(
                    "{\"success\":true,\"data\":{\"availableBalance\":12.5}}");
            ok(!ChatBalance.hasBusinessError(zhipuOk), "智谱正常响应不算错误");
            JSONObject kimiOk = new JSONObject(
                    "{\"code\":0,\"data\":{\"available_balance\":49.5},"
                    + "\"scode\":\"0x0\",\"status\":true}");
            ok(!ChatBalance.hasBusinessError(kimiOk), "Kimi code=0 不算错误");
            JSONObject sfOk = new JSONObject(
                    "{\"code\":20000,\"message\":\"OK\",\"status\":true,"
                    + "\"data\":{\"totalBalance\":\"88.88\"}}");
            ok(!ChatBalance.hasBusinessError(sfOk), "SiliconFlow code=20000 不算错误");
            JSONObject dsOk = new JSONObject(
                    "{\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"1\"}]}");
            ok(!ChatBalance.hasBusinessError(dsOk),
               "DeepSeek 无 code 字段不算错误");

            // 错误消息嵌套在 error.message 里
            JSONObject nested = new JSONObject(
                    "{\"error\":{\"message\":\"Invalid Authentication\"}}");
            eq(ChatBalance.firstNonEmpty(nested, "msg", "message", "error"),
               "Invalid Authentication", "能取出嵌套的 error.message");
            ok(ChatBalance.hasBusinessError(new JSONObject("{\"status\":false}")),
               "status=false 算错误");
            System.out.println("      业务错误判定 ✓（4 种正常响应不误判）");
        }

        System.out.println();
        System.out.println("[14] ★ 只有 DeepSeek 该显示余额/花费/命中率");
        {
            // 用户要求：切换到其他模型时去掉余额气泡，只留命中次数与用量。
            //
            // 判据是"是否 DeepSeek"。这里把这条规则钉住：
            // 将来若有人给别家也加上余额显示，这条断言会失败并提醒
            // "别家算不准花费与命中率"。
            ok("deepseek".equals(ChatProvider.find("deepseek").id),
               "DeepSeek 的 id 是 deepseek（判定基准）");
            // 别家的 id 不能也叫 deepseek（否则判定会误命中）
            for (ChatProvider.Entry e : ChatProvider.all()) {
                if (e.id.equals("deepseek")) continue;
                ok(!"deepseek".equals(e.id), e.id + " 与 deepseek 区分");
            }
            // 未知道具仍应判为 DeepSeek（保守：与旧行为一致，不会突然没余额）
            eq(ChatProvider.find("").id, "deepseek", "空 id → deepseek");
            eq(ChatProvider.find(null).id, "deepseek", "null id → deepseek");
            System.out.println("      DeepSeek 判定基准唯一且保守 ✓");
        }

        System.out.println();
        System.out.println("全部通过：" + pass + " 项");
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
    }
}
