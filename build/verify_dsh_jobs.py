# -*- coding: utf-8 -*-
"""第十六版：**提醒/自动化按仓库真源码一比一**（六种规则 + 时区/夏令时 + 四个工具）的断言。

五段：
  [A] 规则模型：after/at/every/daily/weekly/cron 六种；最小间隔 60 秒、标题 120（仓库常量）；
  [B] 时间算术：固定格子、墙上时间、**夏令时缺口跳过 / 重叠取较早**、五段 cron（Vixie 语义）；
  [C] 投递与恢复：投递记录有界、重启补最近错过的一次、一次性投递后停用；
  [D] 接线：模型可用 schedule_create/list/update/delete（插件 core.schedule）；会话路由；
  [E] 界面：六种规则的 chips、at/cron 输入、投递记录展开、忙碌保护。

★ 铁律：只读源码；行为由 build/dshtest 第 [15][16] 节真编真跑覆盖。
"""
import io
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DSH = os.path.join(SRC, 'dsh')

ok = 0
fail = 0


def check(label, cond, detail=None):
    global ok, fail
    if cond:
        ok += 1
        print('  [OK]   %s%s' % (label, (' :: %s' % detail) if detail else ''))
    else:
        fail += 1
        print('  [FAIL] %s%s' % (label, (' :: %s' % detail) if detail else ''))


def read(path):
    try:
        return io.open(path, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


def strip_comments(text):
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(text[i])
            i += 1
    return ''.join(out)


def method_body(text, signature):
    i = text.find(signature)
    if i < 0:
        return ''
    depth = 0
    j = text.find('{', i)
    if j < 0:
        return ''
    k = j
    while k < len(text):
        if text[k] == '{':
            depth += 1
        elif text[k] == '}':
            depth -= 1
            if depth == 0:
                return text[j:k + 1]
        k += 1
    return text[j:]


SCHED = read(os.path.join(DSH, 'DshSchedule.java'))
STORE = read(os.path.join(DSH, 'DshScheduleStore.java'))
RUNNER = read(os.path.join(DSH, 'DshScheduleRunner.java'))
RECV = read(os.path.join(DSH, 'DshScheduleReceiver.java'))
TOOLS = read(os.path.join(DSH, 'DshTools.java'))
PLUGIN = read(os.path.join(DSH, 'DshPlugin.java'))
BRIDGE = read(os.path.join(DSH, 'DshBridge.java'))
LOCAL = read(os.path.join(DSH, 'DshLocalBackend.java'))
WORK = read(os.path.join(SRC, 'DshWorkActivity.java'))
MAN = read(os.path.join(ROOT, 'AndroidManifest.xml'))
WORK_CODE = strip_comments(WORK)

# ==================== [A] 六种规则 ====================

print('=== [A] 规则模型：六种 kind + 仓库常量 ===')
check('DshSchedule.java 存在', len(SCHED) > 5000, '%d 字节' % len(SCHED))
check('★ 纯类（零 Android 依赖）',
      'import android' not in SCHED and 'import android' not in STORE)
check('★ 最小间隔 = 仓库的 60 秒', 'MIN_EVERY_SECONDS = 60L' in SCHED)
check('★ 间隔下限真的被校验（比较用常量）',
      'if (seconds < MIN_EVERY_SECONDS) {' in SCHED)
check('★ 标题上限 = 仓库的 120', 'MAX_TITLE = 120' in SCHED)
for kind in ('after', 'at', 'every', 'daily', 'weekly', 'cron'):
    check('规则 kind：%s' % kind, '"%s"' % kind in SCHED)
check('校验：标题/指令/间隔/钟点/星期/时区 各有一处',
      all(k in SCHED for k in ('validateTitle', 'validatePrompt', 'validateEvery',
                               'normalizeClock', 'normalizeWeekdays', 'validateTimeZone')))
check('时区不认识就拒（不默默用本机时区）',
      'return "不认识的时区：" + tz;' in SCHED)
check('一次性规则到点前=那个时刻、过点=0',
      'return atMillis > now ? atMillis : 0;' in SCHED)

# ==================== [B] 时间算术 ====================

print()
print('=== [B] 时间算术：固定格子 / 墙上时间 / 夏令时 / cron ===')
_na = method_body(SCHED, 'public long nextAfter(long now)')
check('★ every 用创建时间的固定格子（跨格对齐，不追补）',
      'long k = (now - t) / interval + 1;' in _na)
_wall = method_body(SCHED, 'public static long nextWallClock(')
check('每日/每周走 IANA 时区（Calendar + TimeZone）',
      'Calendar.getInstance(zone, Locale.US)' in _wall)
check('★ 不存在的钟点（春季跳表）⇒ 这一天跳过',
      'at.get(Calendar.HOUR_OF_DAY) != hh' in _wall)
check('★ 重叠（秋季回拨）⇒ 取较早那次',
      'long earlier = t - 3600_000L;' in _wall and 'sameWallClock(earlier, zone, hh, mm, ss)' in _wall)
check('每周只看选中的 ISO 星期',
      'hasWeekday(weekdays, isoDow(cand))' in _wall)
check('ISO 星期换算（周日=7）',
      'return dow == Calendar.SUNDAY ? 7 : dow - 1;' in SCHED)
_cron = method_body(SCHED, 'public static long nextCron(')
check('cron 按分钟步进找下一个匹配', 'cal.add(Calendar.MINUTE, 1);' in _cron)
check('★ cron 五段解析（月/日/周/时/分都要合法）',
      all(k in SCHED for k in ('if (!fill(c.min, f[0], 0, 59)) return null;',
                               'if (!fill(c.hour, f[1], 0, 23)) return null;',
                               'if (!fill(c.dom, f[2], 1, 31)) return null;',
                               'if (!fill(c.mon, f[3], 1, 12)) return null;',
                               'if (!fill(c.dow, f[4], 0, 7)) return null;')))
check('★ cron 范围/步长会被校验（from<lo / to>hi / from>to）',
      'if (from < lo || to > hi || from > to) return false;' in SCHED)
check('★ cron 日/周同时受限时取"或"（Vixie 语义）',
      'if (!domStar && !dowStar) return domOk || dowOk;' in SCHED)

# ==================== [C] 投递与恢复 ====================

print()
print('=== [C] 投递记录 / 重启恢复 ===')
check('★ 投递记录有界（超出挤掉最老的）',
      'while (deliveries.size() > MAX_DELIVERY) deliveries.remove(0);' in SCHED)
check('投递同时更新 lastRunAt', 'lastRunAt = deliveredAt;' in SCHED)
_sync = method_body(RUNNER, 'public static void syncAll(')
check('★ 重启后补"最近错过的一次"（不追补全部）',
      'if (s.nextRunAt > 0 && s.nextRunAt <= now) {' in _sync and 's.recordDelivery(now, s.nextRunAt);' in _sync)
check('补投后重算下一次并落盘', 'DshBridge.saveSchedule(ctx, s);' in _sync)
check('一次性投递完就不再排（enabled=false）',
      'if (s.nextRunAt <= 0) s.enabled = false;' in _sync
      and 'if (s.nextRunAt <= 0) {' in method_body(RECV, 'public void onReceive('))
check('★ 关掉的定时任务要撤闹钟',
      'if (!s.enabled) {' in method_body(RUNNER, 'public static void register(')
      and 'am.cancel(pi)' in method_body(RUNNER, 'public static void register('))
check('★ 广播里先落盘投递再执行',
      's.recordDelivery(now, s.nextRunAt > 0 ? s.nextRunAt : now);' in RECV
      and RECV.find('s.recordDelivery(') < RECV.find('store.save(s);') < RECV.find('.submit('))
check('关掉的定时任务被触发也不跑', 'if (s == null || !s.enabled) return;' in RECV)
check('广播异常不外抛', 'catch (Throwable ignore)' in RECV)

# ==================== [D] 模型可用四个工具 ====================

print()
print('=== [D] 模型可用 schedule_create/list/update/delete ===')
for name in ('schedule_create', 'schedule_list', 'schedule_update', 'schedule_delete'):
    check('工具存在：%s' % name, '"%s"' % name in TOOLS and name in PLUGIN)
check('★ 插件 core.schedule 登记了四个工具',
      'P_SCHEDULE = "core.schedule"' in PLUGIN
      and 'new String[]{"schedule_create", "schedule_list", "schedule_update", "schedule_delete"}' in PLUGIN)
check('★ 工具走宿主接口（DshTools 仍是纯类）',
      'public interface ScheduleHost' in TOOLS and 'List<DshTool> scheduleTools(ScheduleHost host)' in TOOLS)
check('forPlugins 有宿主参数（null ⇒ 不提供提醒工具）',
      'ScheduleHost host)' in TOOLS and 'if (on.contains(DshPlugin.P_SCHEDULE) && host != null)' in TOOLS)
check('★ 桥接层实现宿主（落盘 + 挂闹钟）',
      'static final class ScheduleHostImpl implements DshTools.ScheduleHost' in BRIDGE
      and 'new ScheduleHostImpl(ctx)' in BRIDGE)
check('update 保留 id 与投递记录（传回的是同一个对象）',
      'return saveSchedule(ctx, next) ? next : null;' in BRIDGE)
check('工具参数里 kind 六选一',
      'DshJson.list("after", "at", "every", "daily", "weekly", "cron")' in TOOLS)
check('★ 四个提醒工具**真的被注册进集合**',
      'for (DshTool t : scheduleTools(host)) reg.add(t);' in TOOLS)
check('create 前跑同一套校验并算下一次',
      'String why = validate(s);' in TOOLS and 's.nextRunAt = s.nextAfter(host.now());' in TOOLS)
check('会话路由：chatIn 与局部 final 写盘 id',
      'return chatIn(ctx, userText, null, cb);' in BRIDGE and 'final String sid = currentId;' in BRIDGE)
check('本地后端把 request.sessionId 传下去',
      'DshBridge.chatIn(ctx, text, sessionId, new DshBridge.Callback()' in LOCAL)

# ==================== [E] 界面 ====================

print()
print('=== [E] 界面：六种规则 / at 与 cron 输入 / 投递记录 / 忙碌保护 ===')
check('六种 kind 都在新建表单里',
      all(('DshSchedule.' + k) in WORK for k in
          ('KIND_EVERY', 'KIND_DAILY', 'KIND_WEEKLY', 'KIND_AFTER', 'KIND_AT', 'KIND_CRON')))
check('at 用日期时间输入（yyyy-MM-dd HH:mm）', 'yyyy-MM-dd HH:mm' in WORK)
check('cron 用文本输入并当场解析校验',
      'DshSchedule.Cron.parse(raw) == null' in WORK)
check('★ 投递记录可展开（点标题）',
      'schedOpen.contains(s.id)' in WORK and '"· 投递 " + timeOf(d[0])' in WORK)
check('每行显示频率 / 上次 / 下次 / 记录条数',
      's.frequencyLabel()' in WORK and 's.deliveries.size() + " 条"' in WORK)
check('左栏「任务 / 自动化」入口还在',
      '"🗂 任务（" + DshBridge.taskStore(this).recent(200).size()' in WORK
      and '"⏰ 自动化（" + DshBridge.schedules(this).size()' in WORK)
check('忙碌时拒绝切工作区（回到交接纪律）',
      'if (isBusy()) {' in method_body(WORK, 'private void switchWorkspace('))
check('两块面板都不弹 AlertDialog', 'AlertDialog' not in WORK_CODE)
_recv = MAN[MAN.find('.dsh.DshScheduleReceiver'):MAN.find('.dsh.DshScheduleReceiver') + 220]
check('★ 定时广播 exported=false（外部伪造不了）',
      'android:exported="false"' in _recv and 'android:exported="true"' not in _recv)
check('清单里注册了定时广播（exported=false）',
      '.dsh.DshScheduleReceiver' in MAN and 'com.deepseek.pet.dsh.SCHEDULE_RUN' in MAN)

print()
if fail == 0:
    print('提醒/自动化断言通过（%d 项）' % ok)
    sys.exit(0)
print('提醒/自动化断言失败：ok=%d fail=%d' % (ok, fail))
sys.exit(1)
