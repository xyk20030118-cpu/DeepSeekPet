package com.deepseek.pet.dsh.linux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次性诊断：以 App 自己的 uid 在 proot 访客里跑 node 脚本，
 * 实测「link() 被拒 → rename() 兜底」这条路在安卓上是否可行。
 *
 * <p>用法：{@code adb shell am broadcast -n com.deepseek.pet/.dsh.linux.LinkTestReceiver -a com.deepseek.pet.LINKTEST}
 */
public class LinkTestReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.deepseek.pet.LINKTEST";

    /** 纯 ASCII，避免转义问题。 */
    private static final String SCRIPT =
            "const fs=require('fs'),p=require('path');"
          + "const dir='/home/dsh/.dsh/sessions/--home-dsh--/probe';"
          + "fs.mkdirSync(dir,{recursive:true});"
          + "const tmp=p.join(dir,'a.tmp'),fin=p.join(dir,'a.final');"
          + "try{fs.rmSync(fin,{force:true})}catch(e){}"
          + "fs.writeFileSync(tmp,'x');"
          + "let linkErr='none';"
          + "try{fs.linkSync(tmp,fin);linkErr='LINK-OK'}catch(e){linkErr='LINK-FAIL:'+e.code}"
          + "console.log('link',linkErr);"
          + "if(linkErr!=='LINK-OK'){"
          + "  try{fs.renameSync(tmp,fin);console.log('rename RENAME-OK')}"
          + "  catch(e){console.log('rename RENAME-FAIL:'+e.code)}"
          + "}"
          + "console.log('exists',fs.existsSync(fin));";

    @Override public void onReceive(final Context c, Intent intent) {
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder out = new StringBuilder();
                out.append("uid=").append(android.os.Process.myUid()).append('\n');
                try {
                    List<String> argv = new ArrayList<String>();
                    argv.add("/usr/bin/env");
                    argv.add("PATH=/opt/node/bin:/usr/bin:/bin");
                    argv.add("HOME=/home/dsh");
                    argv.add("DSH_HOME=/home/dsh/.dsh");
                    argv.add("/opt/node/bin/node");
                    argv.add("-e");
                    argv.add(SCRIPT);
                    final StringBuilder one = new StringBuilder();
                    EngineProot.ensureLibs(c);
                    boolean ok = new EngineProot().runArgv(c, argv, new EngineProot.Sink() {
                        @Override public void onLine(String line) {
                            one.append(line).append('\n');
                        }
                    });
                    try { Thread.sleep(8000); } catch (InterruptedException ignore) { }
                    out.append("runArgv=").append(ok).append('\n').append(one);
                } catch (Throwable t) {
                    out.append("EXCEPTION ").append(t.getClass().getSimpleName())
                            .append(": ").append(t.getMessage()).append('\n');
                }
                try {
                    DshFs.writeText(new File(c.getFilesDir(), "linktest.log"), out.toString());
                } catch (Throwable ignore) {
                }
            }
        }, "link-test").start();
    }
}
