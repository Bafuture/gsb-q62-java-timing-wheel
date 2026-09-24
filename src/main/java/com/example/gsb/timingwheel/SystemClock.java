package com.example.gsb.timingwheel;

/** 基于 {@link System#currentTimeMillis()} 的默认时钟。 */
public final class SystemClock implements Clock {

    public static final SystemClock INSTANCE = new SystemClock();

    private SystemClock() {
    }

    @Override
    public long nowMs() {
        return System.currentTimeMillis();
    }
}
