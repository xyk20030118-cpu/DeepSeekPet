package com.deepseek.pet;

import java.nio.charset.Charset;

/** 离线验证 TtsCodec.parse 对各形态响应的处理（不依赖 Android）。 */
public class TtsParseTest {

    static int fails = 0;

    static void check(String name, String json, boolean expectOk, String expectContains) {
        byte[] body = json.getBytes(Charset.forName("UTF-8"));
        TtsCodec.Parsed p = TtsCodec.parse(body, 200);
        boolean ok = p.ok();
        String shown = ok ? ("audio=" + (p.audio == null ? "null" : p.audio.length + "B")
                            + (p.audioUrl != null ? (" url=" + p.audioUrl) : "")
                            + " mime=" + p.mime)
                          : ("ERROR=" + p.error);
        boolean pass = (ok == expectOk)
                && (expectContains == null || (shown != null && shown.contains(expectContains)));
        if (!pass) fails++;
        System.out.println((pass ? "  PASS " : "  FAIL ") + name);
        System.out.println("         -> " + shown);
        if (!pass && expectContains != null) {
            System.out.println("         期望包含: " + expectContains);
        }
    }

    public static void main(String[] args) {
        // 构造一段合法的 ID3 头 + 填充，模拟 MiniMax 的 hex 音频
        StringBuilder hex = new StringBuilder();
        byte[] mp3 = new byte[512];
        mp3[0] = 'I'; mp3[1] = 'D'; mp3[2] = '3'; mp3[3] = 4;
        for (int i = 4; i < mp3.length; i++) mp3[i] = (byte) (i % 251);
        for (byte b : mp3) hex.append(String.format("%02x", b));

        System.out.println("=== 1) MiniMax 成功响应（data.audio 是 HEX）===");
        check("data.audio hex 应能解出音频",
                "{\"data\":{\"audio\":\"" + hex + "\",\"status\":2},"
              + "\"extra_info\":{\"audio_format\":\"mp3\",\"audio_size\":512},"
              + "\"base_resp\":{\"status_code\":0,\"status_msg\":\"success\"}}",
                true, null);

        System.out.println();
        System.out.println("=== 2) MiniMax 错误响应（HTTP 200 + base_resp 非 0）===");
        check("2049 应显示真实原因，而不是通用文案",
                "{\"base_resp\":{\"status_code\":2049,\"status_msg\":\"invalid api key\"}}",
                false, "invalid api key");
        check("2013 参数错误",
                "{\"base_resp\":{\"status_code\":2013,\"status_msg\":\"missing required parameter text\"}}",
                false, "missing required parameter text");
        check("1008 余额不足",
                "{\"base_resp\":{\"status_code\":1008,\"status_msg\":\"insufficient balance\"}}",
                false, "insufficient balance");

        System.out.println();
        System.out.println("=== 3) 确认 hex 解码方向（不能按 base64 解）===");
        byte[] raw = new byte[512];
        raw[0] = 'I'; raw[1] = 'D'; raw[2] = '3'; raw[3] = 4;
        for (int i = 4; i < raw.length; i++) raw[i] = (byte) (i % 251);
        StringBuilder h2 = new StringBuilder();
        for (byte b : raw) h2.append(String.format("%02x", b));
        byte[] dec = TtsCodec.decodeLoose(h2.toString());
        System.out.println("  decodeLoose(hex) -> " + (dec == null ? "null" : dec.length + "B"));
        System.out.println("  sniff            -> " + (dec == null ? "-" : TtsCodec.sniff(dec)));
        if (dec == null || !"mp3".equals(TtsCodec.sniff(dec))) {
            fails++;
            System.out.println("  FAIL hex 未被正确解码");
        } else {
            System.out.println("  PASS hex 解码为合法 mp3");
        }

        System.out.println();
        System.out.println("=== 4) 防误报：无 base_resp 的 status_code 不应被当成业务失败 ===");
        // 某些网关用 status_code 表示别的东西，且同时正常返回了音频 —— 必须仍能取到音频
        check("无 base_resp 时不应误判（并仍取到音频）",
                "{\"status_code\":0,\"data\":{\"audio\":\"" + hex + "\"}}",
                true, null);
        // 裸 hex 响应（无 JSON 外壳）也应正常
        check("裸 hex 文本仍可解码", hex.toString(), true, null);

        System.out.println();
        System.out.println("=== 5) 未知错误不再被压成同一句话 ===");
        check("无任何已知字段时应带出响应原文",
                "{\"weird_field\":\"something odd happened\"}",
                false, "something odd happened");

        System.out.println();
        System.out.println("=== 6) GroupId 不得出现在任何 MiniMax URL 里 ===");
        String up = TtsCodec.minimaxUploadUrl("123456");
        String cl = TtsCodec.minimaxCloneUrl("123456");
        for (String u : new String[]{up, cl}) {
            boolean bad = u.contains("GroupId") || u.contains("group_id") || u.contains("?");
            System.out.println((bad ? "  FAIL " : "  PASS ") + u);
            if (bad) { fails++; System.out.println("         ★ 拼了 GroupId 会触发 1004: token not match group"); }
        }
        System.out.println("  upload 期望无查询参数: " + up);
        System.out.println("  clone  期望无查询参数: " + cl);

        System.out.println();
        System.out.println(fails == 0 ? "全部通过" : (fails + " 项失败"));
    }
}
