package com.example.timer;

/**
 * 可注入的时钟抽象。定时器的全部时间判断只依赖 {@link #millis()}，
 * 测试中可以注入手工时钟，无需真实等待。
 */
public interface Clock {

    /** 当前时间，毫秒。允许的最小单位为 1ms。 */
    long millis();
}
