package com.example.gsb.timingwheel;

/**
 * 时间源抽象。生产环境使用 {@link #system()}，测试可注入手动推进的实现，
 * 使定时器测试不依赖真实等待。
 */
@FunctionalInterface
public interface Clock {

    /** 当前时间，Unix 毫秒。 */
    long nowMs();

    /** 基于系统时间的时钟。 */
    static Clock system() {
        return System::currentTimeMillis;
    }
}
