package com.example.timer;

/** 一次成功提交后返回的任务句柄，可用于查询状态与取消任务。 */
public interface Timeout {

    TimerTask task();

    Timer timer();

    /** 是否已到期执行。 */
    boolean isExpired();

    /** 是否已被取消。取消后的任务保证不会再被触发。 */
    boolean isCancelled();

    /**
     * 取消任务，O(1) 时间内把任务从所在槽位摘除。
     *
     * @return 取消成功返回 true；任务已执行或已取消返回 false
     */
    boolean cancel();
}
