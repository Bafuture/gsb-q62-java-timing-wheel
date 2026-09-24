package com.example.timer;

/** 延时任务。任务逻辑放在 {@link #run(Timeout)} 中实现。 */
@FunctionalInterface
public interface TimerTask {

    /**
     * 时间轮到期时执行。单个任务抛出的运行时异常会被定时器捕获并交给
     * {@link HashedWheelTimer.FailureHandler}，不会影响同槽其他任务。
     */
    void run(Timeout timeout) throws Exception;
}
