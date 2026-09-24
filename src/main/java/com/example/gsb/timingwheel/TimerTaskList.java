package com.example.gsb.timingwheel;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 时间轮上的一个槽位桶：一个以哨兵节点为首尾的双向链表，整桶共享一个到期时间。
 * 桶的到期时间变化时会把自身重新放入到期队列，等待工作线程按序取出。
 */
final class TimerTaskList {

    private final Queue<TimerTaskList> expirationQueue;
    private final AtomicLong expiration = new AtomicLong(-1L);
    private final TimerTaskEntry root = TimerTaskEntry.sentinel();

    TimerTaskList(Queue<TimerTaskList> expirationQueue) {
        this.expirationQueue = expirationQueue;
        root.next = root;
        root.prev = root;
    }

    long getExpiration() {
        return expiration.get();
    }

    /** 设置桶到期时间；若发生变化则把桶投入到期队列。 */
    void setExpiration(long expirationMs) {
        if (expiration.getAndSet(expirationMs) != expirationMs) {
            expirationQueue.offer(this);
        }
    }

    synchronized void add(TimerTaskEntry entry) {
        entry.list = this;
        TimerTaskEntry tail = root.prev;
        entry.prev = tail;
        entry.next = root;
        tail.next = entry;
        root.prev = entry;
    }

    synchronized void remove(TimerTaskEntry entry) {
        if (entry.list == this) {
            unlink(entry);
        }
    }

    /**
     * 逐个弹出桶内所有任务交给 consumer。consumer 可能把任务重新插入时间轮
     * （降级到下层轮），因此回调在锁外执行。
     */
    void flush(Consumer<TimerTaskEntry> consumer) {
        while (true) {
            TimerTaskEntry entry;
            synchronized (this) {
                entry = root.next;
                if (entry == root) {
                    return;
                }
                unlink(entry);
            }
            consumer.accept(entry);
        }
    }

    private void unlink(TimerTaskEntry entry) {
        entry.prev.next = entry.next;
        entry.next.prev = entry.prev;
        entry.next = null;
        entry.prev = null;
        entry.list = null;
    }
}
