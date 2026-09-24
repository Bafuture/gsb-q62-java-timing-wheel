# 时间轮定时器

从零实现的**分层时间轮（Hierarchical / Hashed Timing Wheel）**，用于替代
`ScheduledThreadPoolExecutor` 承载海量超时任务：任务挂在槽位的侵入式链表上，
提交与取消都是 O(1)，不再为每个任务维护堆节点。

| 项目 | 内容 |
|------|------|
| 语言/版本 | Java 17 |
| 构建方式 | Maven（`./mvnw -q verify`，无外部依赖） |
| 测试框架 | JUnit 5 + AssertJ，全部使用可注入的手工时钟，无真实等待 |

## 快速开始

```java
// 生产环境：系统时钟 + 后台线程，tick=10ms，两层各 8 槽
HashedWheelTimer timer = new HashedWheelTimer(SystemClock.INSTANCE, 10, 8, 8);
timer.start();

Timeout timeout = timer.newTimeout(
        t -> doSomething(), 200, TimeUnit.MILLISECONDS);

timeout.cancel();   // O(1) 取消，取消后保证不再触发
timer.stop();
```

```java
// 测试 / 自研驱动：手工时钟，直接推进，不依赖真实时间
ManualClock clock = new ManualClock(0);
Timer timer = new HashedWheelTimer(clock, 10, 8, 8);
timer.newTimeout(t -> ..., 200, MILLISECONDS);
clock.advanceMillis(200);
timer.advanceClock();
```

## 时间轮结构

- **层级（wheel）**：至少两层，层数与每层槽位数都由构造参数 `wheelSlots`
  逐一声明，例如 `new HashedWheelTimer(clock, 10, 8, 8, 4)` 表示三层，
  槽位数分别为 8 / 8 / 4。
- **tick**：基础时间单位 `tickMs`，是时间轮的最小推进粒度。
- **槽（slot）**：第 0 层一槽代表 1 个 tick；第 `i` 层一槽代表
  `slots[0] × … × slots[i-1]` 个 tick。上例（tick=10ms）：
  - 第 0 层：8 槽 × 10ms，覆盖 **80ms**
  - 第 1 层：8 槽 × 80ms，覆盖 **640ms**
  - 第 2 层：4 槽 × 640ms，覆盖 **2560ms**
- **槽内结构**：侵入式双向链表（任务节点自身带 `prev/next` 指针），
  提交入槽、取消摘除均为 O(1)，无堆、无额外包装对象。
- **分层回落**：提交时从第 0 层起找第一层“槽覆盖范围终点 > deadline”的轮；
  deadline 超过低层容量的任务落到上层槽。推进到上层槽边界时，整槽任务被
  摘下并重新入轮，像钟表的分针带动秒针一样逐级回落，直到第 0 层触发。
- **动态溢出层**：延时超过全部已配置层容量时，自动在顶部追加一层
  （槽位数取最上层配置），直到能容纳该任务；因此理论延时上限为
  `Long.MAX_VALUE` 个 tick，不需要预先按最大延时分配内存。
- **游标对齐**：每层游标初始对齐到本层槽边界（Kafka TimingWheel 风格的
  `startMs`），`deadline / slotDuration % slots` 始终是稳定的槽下标。
- **并发模型**：内部用一把 `ReentrantLock` 保护槽链表与游标；任务回调在
  锁外逐个执行。后台线程是可选的（`start()`），也可以用任意驱动方式
  直接调用 `advanceClock()`。

## 精度边界

- **触发精度 = 1 个 tick**。延时按 tick 向上取整：
  `deadlineTick = ceil(now + delay / tickMs)`。任务**绝不会提前**触发，
  实际触发时刻为 deadline 所在 tick 被推进到的时刻，最坏滞后约 1 个 tick
  （`tickMs`），由后台线程调度抖动叠加少量系统调度误差。
- tickMs 越小精度越高，但唤醒与搬运越频繁；槽位数决定单层容量而**不影响
  精度**。例如 tick=10ms、两层 8 槽时，25ms 的延时在 30ms 触发
  （见 `TimerLongDelayTest.precisionIsBoundedByOneTick`）。
- 时间只来自注入的 `Clock.millis()`，最小单位为 1ms；亚毫秒精度不在支持
  范围内。
- 单次 `advanceClock()` 可以一次跨过任意多个 tick（手工时钟大跳），
  内部逐 tick 推进并搬运，不会跳过中间槽的任务。
- 触发时机基于绝对墙钟时间；正常使用中时钟不应回拨，回拨期间时间轮不推进。

## 异常隔离

每个任务回调单独 `try/catch`，异常交给可注入的
`HashedWheelTimer.FailureHandler`（默认静默吞掉）。单个任务抛出
`RuntimeException`/`Error` 不会中断同槽、同批或后续槽位的任何任务。

## 主要类型

| 类型 | 说明 |
|------|------|
| `Clock` / `SystemClock` | 可注入时钟接口与系统时钟实现 |
| `Timer` / `HashedWheelTimer` | 定时器接口与分层时间轮实现（含可选后台线程） |
| `TimerTask` | 任务回调，`run(Timeout)` 形式 |
| `Timeout` | 提交句柄：`cancel()`、`isCancelled()`、`isExpired()` |

## 测试

```bash
mvn -q verify        # 或 ./mvnw -q verify
```

- `TimerSubmitTest`：按时触发、不提前、零延时、跨槽多任务、同批任务异常隔离
- `TimerCancelTest`：到期前取消、重复取消/到期后取消、取消上层轮中的长延时任务
- `TimerLongDelayTest`：超过单层容量的长延时任务经上层轮回落且只触发一次、
  超过全部配置层时动态溢出层、单 tick 精度边界、每层槽位数独立可配置
