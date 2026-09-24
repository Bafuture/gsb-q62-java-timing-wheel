package com.example.gsb.timingwheel;

/**
 * 可注入的时间源。生产环境使用 {@link SystemClock}，测试使用手动推进的实现，
 * 使时间轮的行为验证不依赖真实等待。
 */
public interface Clock {

    /** 当前时间，单位毫秒。 */
    long nowMs();
}
