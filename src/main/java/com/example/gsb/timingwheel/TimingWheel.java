package com.example.gsb.timingwheel;

import java.util.Queue;

/**
 * 单层时间轮。超出本层表示范围 [currentTime, currentTime + interval) 的任务
 * 会被委托给按需创建的上层轮；上层轮的槽到期后，任务会重新插入并逐级降级，
 * 最终落到最底层轮触发。
 */
final class TimingWheel {

    private final long tickMs;
    private final int wheelSize;
    private final long interval;
    private final TimerTaskList[] buckets;
    private final Queue<TimerTaskList> expirationQueue;

    private volatile long currentTime;
    private volatile TimingWheel overflowWheel;

    TimingWheel(long tickMs, int wheelSize, long startMs, Queue<TimerTaskList> expirationQueue) {
        if (tickMs <= 0) {
            throw new IllegalArgumentException("tickMs must be positive");
        }
        if (wheelSize <= 0) {
            throw new IllegalArgumentException("wheelSize must be positive");
        }
        this.tickMs = tickMs;
        this.wheelSize = wheelSize;
        this.interval = tickMs * wheelSize;
        this.currentTime = startMs - (startMs % tickMs);
        this.expirationQueue = expirationQueue;
        this.buckets = new TimerTaskList[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            buckets[i] = new TimerTaskList(expirationQueue);
        }
    }

    /**
     * 尝试把任务放入本层（或按需创建的上层轮）。
     *
     * @return false 表示任务已经到期，应立即执行
     */
    boolean add(TimerTaskEntry entry) {
        long expiration = entry.expirationMs;
        if (expiration < currentTime + tickMs) {
            return false;
        }
        if (expiration < currentTime + interval) {
            long virtualId = expiration / tickMs;
            TimerTaskList bucket = buckets[(int) (virtualId % wheelSize)];
            bucket.setExpiration(virtualId * tickMs);
            bucket.add(entry);
            return true;
        }
        return overflowWheel().add(entry);
    }

    /** 把本层及所有上层轮的指针推进到 timeMs（向下对齐到 tick）。 */
    void advanceClock(long timeMs) {
        if (timeMs >= currentTime + tickMs) {
            currentTime = timeMs - (timeMs % tickMs);
            TimingWheel overflow = overflowWheel;
            if (overflow != null) {
                overflow.advanceClock(currentTime);
            }
        }
    }

    boolean hasOverflowWheel() {
        return overflowWheel != null;
    }

    TimingWheel overflowWheel() {
        TimingWheel overflow = overflowWheel;
        if (overflow == null) {
            synchronized (this) {
                overflow = overflowWheel;
                if (overflow == null) {
                    overflow = new TimingWheel(interval, wheelSize, currentTime, expirationQueue);
                    overflowWheel = overflow;
                }
            }
        }
        return overflow;
    }
}
