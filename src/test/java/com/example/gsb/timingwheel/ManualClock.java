package com.example.gsb.timingwheel;

import java.util.concurrent.atomic.AtomicLong;

/** 测试用手动时钟：时间只在显式调用 {@link #advanceMs(long)} 时流逝。 */
final class ManualClock implements Clock {

    private final AtomicLong nowMs;

    ManualClock(long initialMs) {
        this.nowMs = new AtomicLong(initialMs);
    }

    @Override
    public long nowMs() {
        return nowMs.get();
    }

    void advanceMs(long deltaMs) {
        nowMs.addAndGet(deltaMs);
    }
}
