package com.example.timer;

import java.util.concurrent.atomic.AtomicLong;

/** 测试用手工时钟：时间只有在显式推进时才变化，不依赖真实等待。 */
public final class ManualClock implements Clock {

    private final AtomicLong now;

    public ManualClock() {
        this(0L);
    }

    public ManualClock(long initialMillis) {
        this.now = new AtomicLong(initialMillis);
    }

    @Override
    public long millis() {
        return now.get();
    }

    public void setMillis(long millis) {
        now.set(millis);
    }

    public void advanceMillis(long deltaMillis) {
        if (deltaMillis < 0) {
            throw new IllegalArgumentException("delta must be >= 0");
        }
        now.addAndGet(deltaMillis);
    }
}
