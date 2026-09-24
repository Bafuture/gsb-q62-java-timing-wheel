package com.example.gsb.timingwheel;

/**
 * 单层时间轮：wheelSize 个槽位围成一圈，每格跨度 tickMs，
 * 整圈覆盖 interval = tickMs * wheelSize。
 * 超出本层覆盖范围的任务交给按需创建的 overflowWheel（上一层，tick 粒度为本层 interval）。
 */
final class TimingWheel {

    private final long tickMs;
    private final int wheelSize;
    private final long interval;
    private final TimerTaskList[] buckets;

    /** 已推进到的时间，始终向下对齐到 tickMs 的整数倍。 */
    private long currentTime;

    private volatile TimingWheel overflowWheel;

    TimingWheel(long tickMs, int wheelSize, long startMs) {
        if (tickMs <= 0) {
            throw new IllegalArgumentException("tickMs must be positive: " + tickMs);
        }
        if (wheelSize <= 1) {
            throw new IllegalArgumentException("wheelSize must be > 1: " + wheelSize);
        }
        this.tickMs = tickMs;
        this.wheelSize = wheelSize;
        this.interval = Math.multiplyExact(tickMs, wheelSize);
        this.currentTime = startMs - Math.floorMod(startMs, tickMs);
        this.buckets = new TimerTaskList[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            buckets[i] = new TimerTaskList();
        }
    }

    /**
     * 尝试把任务放入本层（或逐层向上）。
     *
     * @return true 表示已入桶；false 表示任务已到期，调用方应立即执行
     */
    boolean add(TimerTaskEntry entry) {
        long expiration = entry.expirationMs();
        if (expiration < currentTime + tickMs) {
            return false; // 已落入当前 tick，视为到期
        }
        if (expiration < currentTime + interval) {
            int index = (int) ((expiration / tickMs) % wheelSize);
            buckets[index].add(entry);
            return true;
        }
        return overflowWheel().add(entry);
    }

    /** 把本层指针推进到 timeMs（向下对齐 tick）。槽位搬空由外层驱动负责。 */
    void advanceClock(long timeMs) {
        if (timeMs >= currentTime + tickMs) {
            currentTime = timeMs - Math.floorMod(timeMs, tickMs);
        }
    }

    private TimingWheel overflowWheel() {
        TimingWheel overflow = overflowWheel;
        if (overflow == null) {
            synchronized (this) {
                overflow = overflowWheel;
                if (overflow == null) {
                    overflow = new TimingWheel(interval, wheelSize, currentTime);
                    overflowWheel = overflow;
                }
            }
        }
        return overflow;
    }

    long tickMs() {
        return tickMs;
    }

    long currentTime() {
        return currentTime;
    }

    /** 当前指针所指的槽位，覆盖 [currentTime, currentTime + tickMs)。 */
    TimerTaskList bucketAtCurrentTick() {
        return buckets[(int) ((currentTime / tickMs) % wheelSize)];
    }

    TimingWheel overflow() {
        return overflowWheel;
    }
}
