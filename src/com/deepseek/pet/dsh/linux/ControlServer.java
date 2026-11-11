package com.deepseek.pet.dsh.linux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * 回环控制接口：主 App 问"宿主起来了吗、地址是什么"。
 *
 * <p>只绑 {@code 127.0.0.1:43190}，只回答一条 {@code GET /status}：
 * <pre>
 * {"state":"running","url":"http://127.0.0.1:3080/?token=…","token":"…","detail":"…","log":["…"]}
 * </pre>
 * 为什么用回环 HTTP 而不是共享文件：Android 11+ 里别的 App 读不到本包的外部目录，
 * 而回环端口是同一个设备上的既定做法（上游宿主自己也是这么暴露的）。
 * 端口固定、只监听回环、只读一个静态字符串 —— 不提供任何写操作。
 */
final class ControlServer {

    static final int PORT = 43190;

    private final Engine engine;
    private ServerSocket server;
    private Thread thread;

    ControlServer(Engine engine) {
        this.engine = engine;
    }

    void start() {
        if (thread != null) return;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
                    while (!server.isClosed()) {
                        Socket s = server.accept();
                        try {
                            handle(s);
                        } catch (Throwable ignore) {
                        } finally {
                            try { s.close(); } catch (Throwable ignore) { }
                        }
                    }
                } catch (Throwable ignore) {
                    // 端口占用或已关闭：控制接口不可用时引擎本身照跑
                }
            }
        }, "engine-control");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (Throwable ignore) { }
        server = null;
        thread = null;
    }

    private void handle(Socket s) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String request = r.readLine();
        // 头部读完就行（不读 body：本接口没有 POST）
        String line;
        while ((line = r.readLine()) != null && line.length() > 0) {
            // 吃掉请求头
        }
        String body = "{}";
        int code = 400;
        if (request != null && request.startsWith("GET /status")) {
            body = statusJson();
            code = 200;
        }
        byte[] bytes = body.getBytes("UTF-8");
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Bad Request") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private String statusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"state\":\"").append(esc(engine.state())).append('"');
        sb.append(",\"url\":\"").append(esc(engine.url())).append('"');
        sb.append(",\"token\":\"").append(esc(engine.token())).append('"');
        sb.append(",\"detail\":\"").append(esc(engine.detail())).append('"');
        sb.append(",\"log\":[");
        List<String> lines = engine.logLines();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(lines.get(i))).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
