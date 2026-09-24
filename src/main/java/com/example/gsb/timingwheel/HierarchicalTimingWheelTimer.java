package com.example.gsb.timingwheel;

import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分层时间轮定时器。
 *
 * <p>与 {@code ScheduledThreadPoolExecutor} 每个任务一个堆节点不同，这里任务只作为
 * 链表节点挂在槽位桶上：提交/取消都是 O(1)，整桶任务到期时一次性弹出。
 *
 * <p>时间推进完全依赖注入的 {@link Clock}：工作线程周期性（至多 pollIntervalMs）
 * 醒来读取时钟，把到期的槽位桶弹出、推进各级轮指针并触发任务。因此测试可以用
 * 手动时钟瞬间“流逝”任意时长，无需真实等待。
 */
public final class HierarchicalTimingWheelTimer implements Timer {

    private static final long DEFAULT_POLL_INTERVAL_MS = 10L;

    private final Clock clock;
    private final TimingWheel wheel;
    private final PriorityBlockingQueue<TimerTaskList> expirationQueue;
    private final AtomicLong pendingCount = new AtomicLong();
    private final Object lock = new Object();
    private final long pollIntervalMs;
    private final Thread worker;

    private volatile boolean running = true;

    public HierarchicalTimingWheelTimer(long tickMs, int wheelSize, Clock clock) {
        this(tickMs, wheelSize, clock, DEFAULT_POLL_INTERVAL_MS);
    }

    public HierarchicalTimingWheelTimer(long tickMs, int wheelSize, Clock clock, long pollIntervalMs) {
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pollIntervalMs = pollIntervalMs;
        this.expirationQueue = new PriorityBlockingQueue<>(11,
                Comparator.comparingLong(TimerTaskList::getExpiration));
        this.wheel = new TimingWheel(tickMs, wheelSize, clock.nowMs(), expirationQueue);
        this.worker = new Thread(this::workLoop, "hierarchical-timing-wheel-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public Timeout newTimeout(Runnable task, long delay, TimeUnit unit) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        if (!running) {
            throw new IllegalStateException("timer has been shut down");
        }
        long expirationMs = clock.nowMs() + unit.toMillis(delay);
        TimerTaskEntry entry = new TimerTaskEntry(task, expirationMs, pendingCount);
        pendingCount.incrementAndGet();
        if (wheel.add(entry)) {
            synchronized (lock) {
                lock.notifyAll();
            }
        } else if (entry.markExpired()) {
            // 延时过小（不足一个 tick），直接在提交线程上执行
            runSafely(task);
        }
        return entry;
    }

    @Override
    public long pendingCount() {
        return pendingCount.get();
    }

    /** 当前时间轮层数（含按需创建的上层轮），主要用于测试与观测。 */
    public int wheelLevels() {
        int levels = 1;
        TimingWheel current = wheel;
        while (current.hasOverflowWheel()) {
            current = current.overflowWheel();
            levels++;
        }
        return levels;
    }

    @Override
    public void shutdown() {
        if (!running) {
            return;
        }
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            worker.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void workLoop() {
        while (running) {
            TimerTaskList bucket = null;
            synchronized (lock) {
                long now = clock.nowMs();
                TimerTaskList head = expirationQueue.peek();
                if (head == null) {
                    waitLocked(pollIntervalMs);
                } else if (head.getExpiration() <= now) {
                    expirationQueue.remove(head);
                    bucket = head;
                } else {
                    waitLocked(Math.min(head.getExpiration() - now, pollIntervalMs));
                }
            }
            if (bucket != null) {
                // 先把各级轮指针推进到桶的到期时刻，再弹出桶内任务重新插入/触发
                wheel.advanceClock(bucket.getExpiration());
                bucket.flush(this::transfer);
            }
        }
    }

    private void transfer(TimerTaskEntry entry) {
        if (entry.isCancelled()) {
            return;
        }
        if (!wheel.add(entry) && entry.markExpired()) {
            runSafely(entry.task);
        }
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable ignored) {
            // 单个任务异常不得影响同批其他任务与工作线程
        }
    }

    private void waitLocked(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            lock.wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
