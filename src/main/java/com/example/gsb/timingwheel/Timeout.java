package com.example.gsb.timingwheel;

/**
 * 一次已提交延时任务的句柄，用于取消与状态查询。
 */
public interface Timeout {

    /**
     * 尝试取消任务。取消成功后任务保证不会再被触发。
     *
     * @return 本次调用是否真正完成了取消（任务已触发或已取消时返回 false）
     */
    boolean cancel();

    boolean isCancelled();

    boolean isExpired();
}
