package com.example.gsb.timingwheel;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 时间轮中的任务节点。同时充当双向链表节点（挂在某个 {@link TimerTaskList} 桶上）
 * 和对外的 {@link Timeout} 句柄。
 *
 * <p>状态机：INIT -> CANCELLED | EXPIRED，取消与触发竞争时只有一个能成功，
 * 因此“取消后不再触发”由 CAS 保证。
 */
final class TimerTaskEntry implements Timeout {

    private enum State {INIT, CANCELLED, EXPIRED}

    private static final AtomicLong IGNORED_COUNTER = new AtomicLong();

    final Runnable task;
    final long expirationMs;

    private final AtomicLong pendingCount;
    private final AtomicReference<State> state = new AtomicReference<>(State.INIT);

    /** 以下字段只在所属桶的监视器锁内访问。 */
    volatile TimerTaskList list;
    TimerTaskEntry next;
    TimerTaskEntry prev;

    TimerTaskEntry(Runnable task, long expirationMs, AtomicLong pendingCount) {
        this.task = task;
        this.expirationMs = expirationMs;
        this.pendingCount = pendingCount;
    }

    static TimerTaskEntry sentinel() {
        return new TimerTaskEntry(null, -1L, IGNORED_COUNTER);
    }

    @Override
    public boolean cancel() {
        if (!state.compareAndSet(State.INIT, State.CANCELLED)) {
            return false;
        }
        TimerTaskList owner = list;
        if (owner != null) {
            owner.remove(this);
        }
        pendingCount.decrementAndGet();
        return true;
    }

    /** 标记为到期触发；与 cancel 互斥，只有一个能成功。 */
    boolean markExpired() {
        if (!state.compareAndSet(State.INIT, State.EXPIRED)) {
            return false;
        }
        pendingCount.decrementAndGet();
        return true;
    }

    @Override
    public boolean isCancelled() {
        return state.get() == State.CANCELLED;
    }

    @Override
    public boolean isExpired() {
        return state.get() == State.EXPIRED;
    }
}
