# -*- coding: utf-8 -*-
"""主页自定义名字（轮次81）判据。

背景：首页名字原来是**硬编码** `name.setText("小深")`，`ChatActivity.petName()` 也硬编码
`return "小深"`，默认人设文本里还写着「你是「小深」，…」⇒ 改名之后 AI 仍自称小深。

判据：
  ① `Prefs` 有 `petName()/setPetName()/cleanPetName()`，默认名常量是「小深」，长度上限 12；
  ② 首页**不再**出现硬编码 `name.setText("小深")`，改读 Prefs，并且名字行可点（有改名对话框）；
  ③ `ChatActivity.petName()` 跟着 Prefs 走；
  ④ 人设取用处全部走 `personaForPet`（默认自称才会跟着改名），且替换只针对默认名；
  ⑤ 改名对话框：留空＝不改、有「恢复默认」；
  ⑥ 改完要 `Prefs.reloadPet` + 刷新首页。

跑法：python build/verify_pet_name.py
"""
import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "com", "deepseek", "pet")
PR = io.open(os.path.join(SRC, "Prefs.java"), encoding="utf-8").read()
MA = io.open(os.path.join(SRC, "MainActivity.java"), encoding="utf-8").read()
CA = io.open(os.path.join(SRC, "ChatActivity.java"), encoding="utf-8").read()
FS = io.open(os.path.join(SRC, "FloatingService.java"), encoding="utf-8").read()

fails, total = [], 0


def ok(name, cond, detail=""):
    global total
    total += 1
    if not cond:
        fails.append(name)
    print(("  [OK]   " if cond else "  [FAIL] ") + name + ((" :: " + detail) if detail else ""))


def main():
    print("[1] Prefs 的名字存储与清洗")
    ok("★★ 默认名常量 = 小深", 'DEFAULT_PET_NAME = "小深"' in PR)
    ok("★★ petName/setPetName 都在", "petName(Context ctx)" in PR and "setPetName(Context ctx" in PR)
    ok("★★ 清洗：空/全空白回落默认", "out.length() == 0 ? DEFAULT_PET_NAME : out" in PR)
    ok("★ 长度上限 12", "PET_NAME_MAX = 12" in PR)
    ok("★★ 人设替换只针对默认名", 'persona.replace(DEFAULT_PET_NAME, me)' in PR)
    ok("★ 名字＝默认时人设原样返回", 'if (me.equals(DEFAULT_PET_NAME)) return persona;' in PR)

    print("[2] 首页：不再硬编码、可点改名")
    ok("★★★ 首页不再硬编码 name.setText(\"小深\")", 'name.setText("小深")' not in MA)
    ok("★★ 首页读 Prefs.petName", "name.setText(Prefs.petName(this)" in MA)
    ok("★★ 名字行可点（OnClickListener → promptPetName）",
       "name.setOnClickListener" in MA and "promptPetName()" in MA)
    ok("★★ 对话框有「恢复默认」", 'setNeutralButton("恢复默认"' in MA)
    ok("★★ 留空＝不改（空输入直接 return）", "if (v.length() == 0) return;" in MA)
    ok("★ 改完 reloadPet + 刷新首页", "Prefs.reloadPet(MainActivity.this);   // 桌宠/气泡/聊天重读" in MA)

    print("[3] 名字的其它出口")
    ok("★★ ChatActivity 用 Prefs.petName", "return Prefs.petName(this);" in CA)
    ok("★★ 聊天窗不再硬编码小深", 'return "小深"' not in CA)
    ai_calls = FS.count("Prefs.personaForPet(") + CA.count("Prefs.personaForPet(") + MA.count("Prefs.personaForPet(")
    ok("★★★ AI 侧人设全部走 personaForPet（默认自称才会跟着改名）", ai_calls >= 10, "调用点=%d" % ai_calls)
    ok("★ 不再有裸 getPersona 喂给模型", "Prefs.getPersona(" not in FS and "Prefs.getPersona(" not in CA)

    print()
    print("=" * 58)
    if fails:
        print("%d / %d 项失败" % (len(fails), total))
        for f in fails:
            print("  - " + f)
        sys.exit(1)
    print("ALL PET-NAME CHECKS PASSED (%d 项)" % total)


if __name__ == "__main__":
    main()
