package com.example.gsb.timingwheel;

/** 测试用手动推进的时钟，不依赖真实等待。 */
final class ManualClock implements Clock {

    private long nowMs;

    ManualClock(long startMs) {
        this.nowMs = startMs;
    }

    @Override
    public long nowMs() {
        return nowMs;
    }

    void advanceBy(long ms) {
        nowMs += ms;
    }
}
