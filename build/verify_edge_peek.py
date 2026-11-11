# -*- coding: utf-8 -*-
"""挂边探头（轮次77）离线断言。

三层，缺一层都不算完：
  ① **纯类真跑**：调 build/fpstest/run.bat —— 姿态曲线的不变量由 [35] 段真跑真断；
  ② **接线判据**：源码里"该调的地方真的调了"（贴边落点 2 处 / 触摸 3 个分支 /
     钳制域放宽 / 重建与销毁收口 / 开关默认关）——这一类漏了不会报错，只会静默不生效；
  ③ **反向自检**：把 PEER_KEEP 改成 1.0（= 不探出去）必须让 fpstest 变红，
     恢复后必须变绿。不做这一步的断言等于没写（这是本工程既有纪律）。

跑法：python build/verify_edge_peek.py
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "com", "deepseek", "pet")
FPSTEST = os.path.join(ROOT, "build", "fpstest")

fails = []
total = 0


def ok(name, cond, detail=""):
    global total
    total += 1
    if not cond:
        fails.append(name)
    print(("  [OK]   " if cond else "  [FAIL] ") + name + ((" :: " + detail) if detail else ""))


def read(*parts):
    p = os.path.join(*parts)
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def run_fpstest():
    """跑纯类套件，返回 (exit_code, 输出)"""
    bat = os.path.join(FPSTEST, "run.bat")
    try:
        r = subprocess.run(["cmd", "/c", bat], cwd=FPSTEST,
                           capture_output=True, timeout=600)
    except Exception as e:                      # noqa: BLE001
        return -1, "启动失败: %s" % e
    out = (r.stdout or b"").decode("utf-8", "replace") + \
          (r.stderr or b"").decode("utf-8", "replace")
    return r.returncode, out


def main():
    peek_java = os.path.join(SRC, "PeekPose.java")
    svc = os.path.join(SRC, "FloatingService.java")
    prefs = os.path.join(SRC, "Prefs.java")

    print("[1] 纯类 PeekPose 本身")
    ok("PeekPose.java 存在", os.path.exists(peek_java))
    if os.path.exists(peek_java):
        src = read(peek_java)
        ok("★ 零 Android 依赖（不 import android.*）",
           not re.search(r"^\s*import\s+android", src, re.M))
        ok("★ 八个阶段常量齐全",
           all(k in src for k in ("OFF", "SQUEEZE", "PROBE", "PERCH",
                                  "NUDGE", "LIFT", "WITHDRAW", "SETTLE")))
        ok("★★ 拖动跟手的两个纯函数都在（keepForX / dragPose）",
           "keepForX" in src and "dragPose" in src and "leanForKeep" in src)
        ok("★★ 露头量锚在**身体框**（keepOf 用的就是 keep，不存在 bbox 投影宽度）",
           "PROBE_OVERSHOOT" in src and "PEER_KEEP" in src
           and "projection" not in src.lower())
        ok("★★★ PROBE 角度公式不含二次因子（歪过头再闪回的根因）",
           "* e * (leanMax" not in src and "leanMax / LEAN_PEER_DEG) * e" not in src)
        ok("★ 左右对称只用一个符号函数（leanSigned）",
           "leanSigned" in src and src.count("EDGE_LEFT") >= 2)

    print("[2] 接线：**贴边吸附的一个形态**（不是独立功能）")
    if os.path.exists(svc):
        s = read(svc)
        ok("★★★ 贴边入口按形态分流（dockCollapse 里先判 peekOn 再 return）",
           "if (peekOn()) {\n            peekEnterFromDock();\n            return;\n        }" in s)
        ok("★★ 菜单入口会先滑到边上再挂（peekEnterFromDock + 180ms 位移动画）",
           "private void peekEnterFromDock()" in s and "setDuration(180)" in s)
        ok("★★ 拖动松手 / 漫游撞边落到 peekBegin",
           s.count("peekBegin();") >= 3, "调用点=%d" % s.count("peekBegin();"))
        ok("★★ 触摸 DOWN 分支接探头（点一下 / 想拖走）", "peekDownMoved = false" in s)
        ok("★★★ 触摸 MOVE = 拖动接管（跟手），不再「先缩回再让你拖」",
           "peekBeginDrag();" in s and "peekDragStep(event.getRawX(), event.getRawY());" in s
           and "peekDragStep(float rawX, float rawY)" in s)
        ok("★★ 拖动期间露头量由窗口 x 反推（keepForX）",
           "PeekPose.keepForX(peekBaseX, peekBodyW, x, peekEdge)" in s)
        ok("★★ 拖动期间只写变换、不抢窗口 x（applyPeekVisual）",
           "applyPeekVisual(PeekPose.dragPose(keep, peekTune))" in s and "peekDragStartX" in s)
        ok("★★★ 松手两类去向：拉够了退出／没拉够回挂（SETTLE）",
           "peekEndDrag()" in s and "PeekPose.SETTLE" in s and "0.985f" in s)
        ok("★★★ 双击/三击的分派与普通态共用（挂边也能开问一句/动作菜单）",
           "dispatchPetTap(event, held, true, actCdBlocked)" in s
           and "dispatchPetTap(event, heldPeek, false, cdBlockedP)" in s
           and s.count("private void dispatchPetTap(") == 1)
        ok("★★ 双击→问一句、三击→动作菜单 的判定只此一份",
           "showAskBar();" in s and "showActionBubble();" in s
           and "boolean doubleTap = (nowTs - lastTapTime) < 350;" in s)
        ok("★★★ 挂边「点一下」走和普通态**同一套**点击处理（互动不再丢）",
           "onPetSingleTap(event, allowBalanceToggle, cdBlocked);" in s
           and s.count("private void onPetSingleTap(") == 1)
        ok("★★ 挂边态点击不偷改余额气泡偏好（普通态 true / 挂边态 false）",
           "dispatchPetTap(event, held, true, actCdBlocked)" in s
           and "dispatchPetTap(event, heldPeek, false, cdBlockedP)" in s
           and "allowBalanceToggle" in s)
        ok("★★ 触摸 UP：短按抬头/互动、长按出菜单（挂边时菜单必须够得着）",
           "if (!peekDownMoved) {" in s and "long heldPeek" in s
           and "if (held > 800) {" in s and "showLongPressBubble();" in s
           and "if (held <= 800 && peekPhase != PeekPose.OFF) peekLift();" in s)
        # —— 轮次84：把"有概率挂不上"的两条路堵死 ——
        _snap_start = s.find("private void snapToNearestEdge()")
        _snap_end = s.find("private void ", _snap_start + 10)
        _snap_body = s[_snap_start:_snap_end if _snap_end > 0 else len(s)]
        ok("★★★ 吸附目标用**未放宽**的基础范围（放宽的边会让人挂不上）",
           _snap_body != "" and "edgeRangeX();" not in _snap_body
           and "ContentInset.xRange(screenW(), petWinWidth(), insetOrZero())" in _snap_body)
        ok("★★★ 留白未就绪时重试（异步扫描没跑完不许静默放弃）",
           "挂边等待留白就绪" in s and s.count("peekRetryTask") >= 3)
        ok("★★ 落在贴边位之外（挂边侧）也认，夹回来再挂",
           "beyond > 0 && beyond <= peekBodyW" in s)
        ok("★★ 每条放弃路径都有日志（下次报障能定位）",
           "挂边跳过：离边太远" in s and "挂边放弃：内容留白一直没就绪" in s)
        ok("★★★ 钳制域在探头态放宽（否则一 clamp 就被拽回屏内）",
           "peekPhase != PeekPose.OFF" in s and "rx[0] - out" in s)
        ok("★ 重建视图 / 服务销毁都收口（peekAbort）", s.count("peekAbort();") >= 3,
           "出现 %d 次" % s.count("peekAbort();"))
        ok("★ 每帧驱动（PEEK_FRAME_MS）不靠素材帧率", "PEEK_FRAME_MS" in s)
        ok("★ 姿态写进的是 PetView 的 setRotation/setScale/setTranslationX",
           all(k in s for k in ("setRotation(PeekPose.leanSigned", "setPivotY(",
                                "setTranslationX(dp(pose.breathDp))")))
        ok("★ 与压扁竖条互斥（探头只在 NORMAL 态挂）",
           "dockState != PetDockState.NORMAL" in s)
        ok("★★★ 挂边**不裁身体**（下半身要一直画出来 —— 裁切会让人以为动画坏了）",
           "setClipBounds" not in s)
        _prefs_src = read(prefs) if os.path.exists(prefs) else ""
        _act_src = read(os.path.join(SRC, "MainActivity.java"))
        ok("★★ 挂边角度/深度走设置（edge_peek_lean / edge_peek_keep）",
           "edge_peek_lean" in _prefs_src and "edge_peek_keep" in _prefs_src
           and "peekLeanDeg" in _prefs_src and "peekKeepPercent" in _prefs_src)
        ok("★★ 进挂边时把设置读成 Tuning，并全程喂给纯类",
           "readPeekTuning()" in s and s.count("peekTune") >= 4
           and "PeekPose.at(phase, t, age, peekFromKeep, peekTune)" in s)
        ok("★★ 设置页有「挂边角度 / 挂出深度」两行",
           "挂边角度" in _act_src and "挂出深度" in _act_src
           and "Prefs.setPeekLeanDeg" in _act_src
           and "Prefs.setPeekKeepPercent" in _act_src)
        ok("★★★ 抬头到点后走 SETTLE 平滑回挂（不再一帧跳回 PERCH）",
           "if (peekPhase != PeekPose.LIFT) return;" in s
           and "peekEnterPhase(PeekPose.SETTLE);" in s)
        ok("★★ 挂边期间收起余额气泡，退出后按 isVisible 恢复",
           "peekBalWasVisible" in s and s.count("balRoot.setVisibility") >= 2)
        ok("★ 侧面监测条挂边时先收、收口后重新定位",
           "monitorHideSide()" in s and "positionMonitorSide();" in s)
    else:
        ok("FloatingService.java 存在", False)

    if os.path.exists(prefs):
        p = read(prefs)
        ok("★ 形态键落成 edge_dock_style（不是另开一个开关）", "edge_dock_style" in p)
        ok("★ 两个形态常量齐全",
           "DOCK_STYLE_STRIP" in p and "DOCK_STYLE_PEEK" in p)
        m = re.search(r"KEY_EDGE_DOCK_STYLE,\s*(DOCK_STYLE_\w+)\s*\)", p)
        ok("★★ 默认形态是挂边露头（用户点名要的就是它）",
           bool(m) and m.group(1) == "DOCK_STYLE_PEEK", m.group(0) if m else "没找到默认值")

    act = os.path.join(SRC, "MainActivity.java")
    if os.path.exists(act):
        a = read(act)
        ok("★★ 设置页有「贴边与挂边」一节（有 UI 入口，不是只能发广播）",
           "贴边与挂边" in a)
        ok("★★ 设置页能选形态并即时生效（setEdgeDockStyle + reloadPet）",
           "Prefs.setEdgeDockStyle(MainActivity.this" in a
           and a.count("Prefs.reloadPet(MainActivity.this);") > 5)
    else:
        ok("MainActivity.java 存在", False)

    print("[3] 纯类套件真跑（含 [35] PeekPose 段）")
    code, out = run_fpstest()
    ok("★★ fpstest 全绿", code == 0, "exit=%d" % code)
    ok("★★ [35] PeekPose 段真的存在并跑过", "[35] PeekPose" in out)
    ok("★ [35] 段没有 FAIL 行",
       "FAIL" not in out.split("[35] PeekPose")[-1] if "[35] PeekPose" in out else False)

    print("[4] 反向自检：把 PEER_KEEP 改成 1.0（= 不探出去）必须变红")
    if os.path.exists(peek_java):
        orig = read(peek_java)
        broken = orig.replace("PEER_KEEP = 0.62f;", "PEER_KEEP = 1.0f;")
        if broken == orig:
            ok("★★ 反向：能改到常量", False, "没匹配到 PEER_KEEP = 0.62f;")
        else:
            try:
                with open(peek_java, "w", encoding="utf-8", newline="\n") as f:
                    f.write(broken)
                code2, out2 = run_fpstest()
                ok("★★ 反向：常量被破坏后 fpstest 必须红", code2 != 0,
                   "exit=%d" % code2)
                ok("★★ 反向：红的是 [35] 那几条（不是别的段）",
                   "FAIL" in out2.split("[35] PeekPose")[-1] if "[35] PeekPose" in out2 else False)
            finally:
                with open(peek_java, "w", encoding="utf-8", newline="\n") as f:
                    f.write(orig)
            code3, _ = run_fpstest()
            ok("★★ 反向：恢复后必须回绿", code3 == 0, "exit=%d" % code3)

    print()
    print("=" * 58)
    if fails:
        print("%d / %d 项失败" % (len(fails), total))
        for n in fails:
            print("  - " + n)
        sys.exit(1)
    print("ALL EDGE-PEEK CHECKS PASSED (%d 项)" % total)


if __name__ == "__main__":
    main()
