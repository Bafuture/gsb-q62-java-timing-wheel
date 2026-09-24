# Hierarchical Timing Wheel Timer

针对"服务里同时挂着十几万个超时任务"的场景：相比 `ScheduledThreadPoolExecutor`
每个任务占一个堆节点（插入 O(log n)、取消需堆调整），本实现中每个任务只是槽位
链表上的一个节点，**插入与取消均为 O(1)**，内存开销与任务数成线性且无常数堆操作。

## 结构

```
level 0:  [tickMs] x N 槽   覆盖 interval0 = tickMs * N
level 1:  [interval0] x N 槽 覆盖 interval1 = interval0 * N
level 2:  [interval1] x N 槽 覆盖 interval2 = interval1 * N
... 上层轮按需创建
```

- 每层是一个环形槽位数组，每格跨度为本层 tick，整圈覆盖 `tick * wheelSize`。
- 提交任务时：延时落在本层覆盖范围内则直接入对应槽位；超出则逐级向上，
  放入按需创建的 overflow 轮（每层槽位数均可配置）。
- 时间推进时：底层轮每走一格，落格任务立即执行；上层轮每走一格，该格任务
  **重新插入**（逐级下沉到下层轮），直到落入底层轮到期。
- 槽位是带哨兵的双向循环链表，任务节点持有所在链表引用，取消即 O(1) 摘除。

## 使用

```java
// 底层 tick = 10ms，每层 512 槽；时钟可注入
HierarchicalTimingWheelTimer timer =
        new HierarchicalTimingWheelTimer(10, 512, Clock.system());

Timeout t = timer.schedule(() -> doSomething(), 5, TimeUnit.SECONDS);
t.cancel(); // 取消后保证不再触发

// 驱动：由单一线程周期性调用（生产可挂在调度线程上）
timer.advance();
```

测试不依赖真实等待：注入手动时钟即可。

```java
ManualClock clock = ...;               // 见 src/test
Timer timer = new HierarchicalTimingWheelTimer(10, 16, clock);
timer.schedule(task, 50, MILLISECONDS);
clock.advanceBy(50);
timer.advance();                        // task 在此触发
```

## 语义保证

- **取消**：`cancel()` 成功（返回 true）后任务绝不再触发；已触发或已取消时返回 false。
- **异常隔离**：单个任务抛异常会被捕获并交给构造时注入的 `exceptionHandler`，
  不影响同批其他任务，也不打断时间轮推进。
- **线程模型**：`schedule` / `cancel` 线程安全；`advance()` 须由单一驱动线程调用。

## 精度边界

- 触发精度由**底层 tickMs** 决定：到期时间向下对齐到 tick 边界，
  任务最多**提前不到一个 tickMs** 触发，绝不会早于 `到期时间 - tickMs`。
- 实际触发时刻还受 `advance()` 驱动频率限制：驱动间隔为 D 时，
  最坏延迟为 D（在上一条提前界之上叠加）。驱动越频繁越准时，代价是空转。
- tickMs 与 wheelSize 的取舍：tick 越小越精准、槽位遍历越频繁；
  wheelSize 越大单层覆盖越广、层级越少。容量按 `tickMs * wheelSize^L` 增长，
  例如 10ms × 512³ ≈ 15 天，三层即可覆盖绝大多数超时场景。
- 延时 ≤ 0 的任务不入轮，提交时立即执行。

## 构建与测试

```bash
mvn -q verify
```

测试覆盖：提交（到期触发 / 同批全触发 / 零延时立即执行）、取消（不再触发 /
同槽兄弟不受影响 / 到期后取消失败）、长延时（5000ms 任务落到第三层并按时触发、
略超单层容量的任务落第二层）、异常隔离（单任务异常不影响其他任务与后续调度）。
