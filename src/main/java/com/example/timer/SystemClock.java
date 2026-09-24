package com.example.timer;

/** 基于 {@link System#currentTimeMillis()} 的系统时钟，生产环境使用。 */
public final class SystemClock implements Clock {

    public static final SystemClock INSTANCE = new SystemClock();

    @Override
    public long millis() {
        return System.currentTimeMillis();
    }
}
