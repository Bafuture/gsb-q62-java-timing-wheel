package com.example.gsb.timingwheel;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * 分层时间轮定时器。
 *
 * <p>底层轮以 tickMs 为粒度转动；延时超过本层容量的任务被放入按需创建的上层轮，
 * 上层轮每转一格，就把该格任务重新下放到下层轮（逐级降级），最终落入底层轮到期触发。
 * 每个任务只占一个链表节点，插入与取消均为 O(1)。
 *
 * <p>线程模型：{@link #schedule} 与 {@link Timeout#cancel()} 可被多线程调用；
 * {@link #advance()} 应由单一驱动线程周期性调用。
 */
public final class HierarchicalTimingWheelTimer implements Timer {

    private final TimingWheel wheel;
    private final Clock clock;
    private final LongAdder pendingCount = new LongAdder();
    private final Consumer<Throwable> exceptionHandler;

    public HierarchicalTimingWheelTimer(long tickMs, int wheelSize, Clock clock) {
        this(tickMs, wheelSize, clock, Throwable::printStackTrace);
    }

    public HierarchicalTimingWheelTimer(long tickMs, int wheelSize, Clock clock,
                                        Consumer<Throwable> exceptionHandler) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
        this.wheel = new TimingWheel(tickMs, wheelSize, clock.nowMs());
    }

    @Override
    public Timeout schedule(Runnable task, long delay, TimeUnit unit) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        long delayMs = Math.max(0L, unit.toMillis(delay));
        TimerTaskEntry entry = new TimerTaskEntry(task, clock.nowMs() + delayMs, pendingCount::decrement);
        pendingCount.increment();
        if (!wheel.add(entry)) {
            execute(entry); // 延时为 0 或已过期，立即执行
        }
        return entry;
    }

    @Override
    public void advance() {
        long now = clock.nowMs();
        while (now >= wheel.currentTime() + wheel.tickMs()) {
            advanceOneTick(wheel);
        }
    }

    /** 把某一层推进一格并搬空落格；若上层轮因此跨过自己的一格，则递归推进上层。 */
    private void advanceOneTick(TimingWheel level) {
        level.advanceClock(level.currentTime() + level.tickMs());
        level.bucketAtCurrentTick().flush(this::reinsertOrExecute);
        TimingWheel overflow = level.overflow();
        if (overflow != null && overflow.currentTime() + overflow.tickMs() <= level.currentTime()) {
            advanceOneTick(overflow);
        }
    }

    /** 槽位任务的归宿：已取消则丢弃；仍超出本层则重新入轮（自动下沉或上浮）；否则执行。 */
    private void reinsertOrExecute(TimerTaskEntry entry) {
        if (entry.isCancelled()) {
            return;
        }
        if (!wheel.add(entry)) {
            execute(entry);
        }
    }

    /** 单任务异常隔离：一个任务抛异常不影响同批其他任务，也不打断时间轮推进。 */
    private void execute(TimerTaskEntry entry) {
        if (!entry.markExpired()) {
            return; // 并发下已被取消
        }
        try {
            entry.task().run();
        } catch (Throwable t) {
            try {
                exceptionHandler.accept(t);
            } catch (Throwable ignored) {
                // 异常处理器自身出错也不能影响后续任务
            }
        }
    }

    @Override
    public long pendingCount() {
        return pendingCount.sum();
    }

    /** 当前已创建的时间轮层数（上层轮按需创建），主要用于观测与测试。 */
    public int levels() {
        int count = 0;
        for (TimingWheel level = wheel; level != null; level = level.overflow()) {
            count++;
        }
        return count;
    }
}
