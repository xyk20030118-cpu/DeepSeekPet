package com.deepseek.pet.dsh.linux;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 引擎自己的界面：看状态、装载荷、起停宿主。
 *
 * <p>只用系统控件手写布局——这个包刻意不引第三方库，保持"小、可独立安装、
 * 出问题一眼看得懂"。
 */
public class EngineActivity extends Activity {

    private TextView state;
    private EditText source;
    private TextView payload;
    private TextView urlView;
    private TextView logView;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        tick();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.postDelayed(poll, 800);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            tick();
            ui.postDelayed(this, 1000);
        }
    };

    private View build() {
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 40, 32, 40);
        root.setBackgroundColor(Color.WHITE);
        sc.addView(root);

        TextView title = new TextView(this);
        title.setText("DSH Linux 引擎");
        title.setTextSize(22f);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("这个包提供「完整 Linux」：proot + Ubuntu + 官方 Node，"
                + "跑上游 DeepSeek Harness。\n"
                + "它是独立 App，targetSdk 28 —— 因为 Android 10+ 只允许 targetSdk≤28 的包"
                + "执行自己目录里的程序（proot 必需）。主 App 不受影响。");
        note.setTextSize(12f);
        note.setTextColor(Color.DKGRAY);
        note.setPadding(0, 12, 0, 12);
        root.addView(note);

        state = new TextView(this);
        state.setTextSize(14f);
        state.setTextColor(Color.BLACK);
        root.addView(state);

        payload = new TextView(this);
        payload.setTextSize(12f);
        payload.setTextColor(Color.DKGRAY);
        payload.setPadding(0, 8, 0, 8);
        root.addView(payload);

        source = new EditText(this);
        source.setTextSize(13f);
        source.setSingleLine(true);
        source.setHint("http://192.168.1.10:8099/");
        source.setText(Engine.lastSource(this));
        root.addView(source, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button download = new Button(this);
        download.setText("从上面的地址下载并安装（带 sha256 校验）");
        download.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String base = source.getText().toString();
                new Thread(new Runnable() {
                    @Override public void run() {
                        EngineService.engine(EngineActivity.this).download(base);
                    }
                }, "engine-download").start();
            }
        });
        root.addView(download, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button clean = new Button(this);
        clean.setText("清理载荷压缩包（装好后可省 ~150 MB）");
        clean.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!EngineProot.available(EngineActivity.this)) {
                    payload.setText("还没装好，不能删压缩包（删了要重下）");
                    return;
                }
                int freed = Engine.cleanupPayloads(EngineActivity.this);
                payload.setText("已清理 " + (freed >> 20) + " MB 压缩包");
            }
        });
        root.addView(clean, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button install = new Button(this);
        install.setText("安装 / 修复（解载荷）");
        install.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        EngineService.engine(EngineActivity.this).install();
                    }
                }, "engine-install").start();
            }
        });
        root.addView(install, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button start = new Button(this);
        start.setText("启动宿主");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startService(new Intent(EngineActivity.this, EngineService.class));
            }
        });
        row.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(EngineActivity.this, EngineService.class);
                i.setAction("stop");
                startService(i);
            }
        });
        row.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        Button self = new Button(this);
        self.setText("自检：rename / mv / node");
        self.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        EngineService.engine(EngineActivity.this).selfTest();
                    }
                }, "engine-selftest").start();
            }
        });
        root.addView(self, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        urlView = new TextView(this);        urlView.setTextSize(12f);
        urlView.setTextColor(Color.rgb(0, 96, 0));
        urlView.setPadding(0, 12, 0, 8);
        urlView.setTextIsSelectable(true);
        root.addView(urlView);

        logView = new TextView(this);
        logView.setTextSize(10f);
        logView.setTextColor(Color.DKGRAY);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setGravity(Gravity.START);
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    private void tick() {
        Engine e = EngineService.engine(this);
        String missing = Engine.missingPayloads(this);
        state.setText("状态：" + e.state()
                + (e.detail().length() > 0 ? "（" + e.detail() + "）" : ""));
        payload.setText("载荷目录：" + Engine.payloadDir(this).getAbsolutePath()
                + (missing.length() > 0 ? "\n还缺：" + missing : "\n载荷齐全"));
        String u = e.url();
        urlView.setText(u.length() > 0
                ? "宿主地址（主 App 填这一行）：\n" + u
                : "宿主尚未启动");
        StringBuilder sb = new StringBuilder();
        for (String line : e.logLines()) sb.append(line).append('\n');
        logView.setText(sb.toString());
    }
}
