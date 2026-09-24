package com.example.gsb.timingwheel;

/**
 * 时间轮中的任务节点。同时挂在槽位的双向链表中，支持 O(1) 摘除（取消）。
 * 状态迁移：PENDING -> CANCELLED | EXPIRED，终态迁移时恰好回调一次 onTerminal。
 */
final class TimerTaskEntry implements Timeout {

    private enum State { PENDING, CANCELLED, EXPIRED }

    private final Runnable task;
    private final long expirationMs;
    private final Runnable onTerminal;

    private State state = State.PENDING;
    private TimerTaskList list;

    TimerTaskEntry next;
    TimerTaskEntry prev;

    TimerTaskEntry(Runnable task, long expirationMs, Runnable onTerminal) {
        this.task = task;
        this.expirationMs = expirationMs;
        this.onTerminal = onTerminal;
    }

    Runnable task() {
        return task;
    }

    long expirationMs() {
        return expirationMs;
    }

    @Override
    public boolean cancel() {
        TimerTaskList owner;
        synchronized (this) {
            if (state != State.PENDING) {
                return false;
            }
            state = State.CANCELLED;
            owner = list;
            list = null;
        }
        // 锁外摘除，避免与槽位 flush 的加锁顺序相反而死锁
        if (owner != null) {
            owner.remove(this);
        }
        onTerminal.run();
        return true;
    }

    /** 仅当任务仍处于 PENDING 时将其标记为已到期，返回是否由本次调用接管执行。 */
    boolean markExpired() {
        synchronized (this) {
            if (state != State.PENDING) {
                return false;
            }
            state = State.EXPIRED;
        }
        onTerminal.run();
        return true;
    }

    @Override
    public synchronized boolean isCancelled() {
        return state == State.CANCELLED;
    }

    @Override
    public synchronized boolean isExpired() {
        return state == State.EXPIRED;
    }

    synchronized void attachTo(TimerTaskList owner) {
        this.list = owner;
    }

    /** 槽位 flush 时由 {@link TimerTaskList} 调用，清除反向引用。 */
    synchronized void detach() {
        this.list = null;
    }
}
