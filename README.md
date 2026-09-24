# 时间轮定时器

Pair-wise GSB 标注任务仓库（第 6 批 / 62）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

服务里同时挂着十几万个超时任务，用 ScheduledThreadPoolExecutor 实现时每个任务占一个堆节点，内存和取消开销都很大。请从零实现一个时间轮定时器。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 实现分层时间轮，至少两级，每级槽位数量可配置；2) 支持提交延时任务与取消任务，取消后不得再被触发；3) 时间推进抽象成可注入的时钟接口，测试不依赖真实等待；4) 延时超过当前层容量的任务要能正确落到上层轮，测试需覆盖一个超过单层容量的长延时任务；5) 单个任务抛异常不得影响同批其他任务。测试覆盖提交、取消与长延时三类场景，`mvn -q verify` 一条命令跑通，README 说明时间轮结构与精度边界。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

## 实现说明

### 结构

分层时间轮（Kafka 风格），代码位于 `src/main/java/com/example/gsb/timingwheel/`：

```
Timer (接口)
 └── HierarchicalTimingWheelTimer   定时器主体，持有最底层轮 + 到期桶队列 + 工作线程
      └── TimingWheel (第 0 层)      tickMs × wheelSize 个槽位，覆盖 [now, now+tickMs*wheelSize)
           └── TimingWheel (第 1 层)  tickMs' = 下层 interval，按需懒创建
                └── TimingWheel (第 2 层) ... 依此类推，层数无上限
```

- **槽位桶（`TimerTaskList`）**：每层轮是 `wheelSize` 个桶组成的数组，桶内是双向链表。
  任务只是链表节点，提交/取消都是 O(1)，不像 `ScheduledThreadPoolExecutor` 每个任务
  占一个堆节点、取消还要堆调整。整桶到期时一次性弹出，摊销成本低。
- **分层降级**：延时超出本层容量 `[currentTime, currentTime + interval)` 的任务，
  委托给按需创建的上层轮（上层 tick = 本层 interval）。上层桶到期后，桶内任务重新
  插入时间轮，逐级降级，最终落到最底层轮触发。例如 tick=10ms、每层 10 槽：
  第 0 层覆盖 100ms，第 1 层覆盖 1s，第 2 层覆盖 10s，第 3 层覆盖 100s……
- **时钟抽象（`Clock`）**：时间推进完全来自注入的 `Clock.nowMs()`。工作线程至多每
  `pollIntervalMs`（默认 10ms）醒来读一次时钟，把到期桶弹出并推进各级轮指针。
  测试用手动时钟（`ManualClock`）瞬间“流逝”任意时长，不依赖真实等待。
- **取消语义**：`Timeout.cancel()` 与到期触发通过 CAS 竞争同一状态机
  （INIT → CANCELLED | EXPIRED），取消成功后任务保证不再触发。
- **异常隔离**：每个任务在 `try/catch (Throwable)` 中执行，单个任务抛异常不影响
  同批其他任务与工作线程。

### 使用示例

```java
Timer timer = new HierarchicalTimingWheelTimer(10, 512, SystemClock.INSTANCE);
Timeout t = timer.newTimeout(() -> System.out.println("fire"), 5, TimeUnit.SECONDS);
t.cancel();          // 取消后不会再触发
timer.shutdown();
```

### 精度边界

- 任务按 `floor(expiration / tickMs) * tickMs` 向下取整落入槽位，因此**最早可能
  提前不到一个 `tickMs` 触发**；另一方面，工作线程按 `pollIntervalMs` 轮询时钟，
  触发至多再延后一个轮询周期。即实际触发时刻 ∈
  `[截止时间 - tickMs, 截止时间 + pollIntervalMs + 调度抖动]`。
- 精度由最底层 `tickMs` 决定：`tickMs` 越小越精确，但同一延时范围内层级/槽位开销
  越大；`wheelSize` 决定单层容量（`tickMs × wheelSize`），超出部分交给上层轮，
  上层轮的粗粒度不影响最终精度（任务会逐级降级回最底层触发）。
- 延时不足一个 tick 的任务视为已到期，在提交线程上立即执行。

### 测试

`src/test/java/.../HierarchicalTimingWheelTimerTest` 覆盖：

1. **提交**：任务在时钟走过截止时刻后触发，之前不触发；
2. **取消**：取消后时间大幅流逝也不触发，且不影响同槽位其他任务；
3. **长延时**：5000ms 任务超出单层 100ms 容量，正确落到第 3 层轮并按时触发；
4. **异常隔离**：同批任务抛异常不影响其他任务与后续提交。

```bash
mvn -q verify
```
