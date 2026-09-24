package com.example.gsb.timingwheel;

import java.util.concurrent.TimeUnit;

/**
 * 分层时间轮定时器。
 */
public interface Timer {

    /**
     * 提交一个延时任务。
     *
     * @param task  到期后执行的动作；抛出的异常会被隔离，不影响同批其他任务
     * @param delay 延时长度（小于等于 0 时尽快执行）
     * @param unit  延时的时间单位
     * @return 可用于取消的句柄
     */
    Timeout schedule(Runnable task, long delay, TimeUnit unit);

    /**
     * 按注入时钟的当前时间推进时间轮，触发所有到期任务。
     * 应由单一驱动线程周期性调用。
     */
    void advance();

    /** 当前尚未触发也未取消的任务数。 */
    long pendingCount();
}
