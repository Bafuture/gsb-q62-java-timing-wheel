package com.example.gsb.timingwheel;

import java.util.concurrent.TimeUnit;

/** 延时任务定时器。 */
public interface Timer {

    /**
     * 提交一个延时任务。
     *
     * @param task  到期后执行的任务；任务抛出异常不会影响其他任务与定时器本身
     * @param delay 延时
     * @param unit  延时单位
     * @return 可用于取消的句柄
     */
    Timeout newTimeout(Runnable task, long delay, TimeUnit unit);

    /** 当前尚未终结（未触发也未取消）的任务数。 */
    long pendingCount();

    /** 停止定时器后台线程，未触发的任务将被丢弃。 */
    void shutdown();
}
