package com.example.timer;

import java.util.concurrent.TimeUnit;

/** 时间轮定时器对外接口。 */
public interface Timer {

    /**
     * 提交一个延时任务。
     *
     * @param task  任务
     * @param delay 延时，向上取整到 tick 的整数倍
     * @param unit  时间单位
     * @return 任务句柄
     */
    Timeout newTimeout(TimerTask task, long delay, TimeUnit unit);

    /** 停止后台推进线程（若有）。 */
    void stop();

    /**
     * 按时钟当前时刻推进时间轮：触发所有到期任务。
     * 手工驱动（测试）时直接调用；后台线程运行时由线程自动调用。
     */
    void advanceClock();
}
