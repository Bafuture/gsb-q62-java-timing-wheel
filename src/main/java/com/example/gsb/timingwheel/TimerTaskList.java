package com.example.gsb.timingwheel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一个槽位：以哨兵节点为头的双向循环链表。
 * 插入、按节点删除均为 O(1)，这是取消开销低的关键。
 */
final class TimerTaskList {

    private final TimerTaskEntry root = new TimerTaskEntry(null, -1L, () -> { });

    TimerTaskList() {
        root.next = root;
        root.prev = root;
    }

    synchronized void add(TimerTaskEntry entry) {
        TimerTaskEntry tail = root.prev;
        entry.prev = tail;
        entry.next = root;
        tail.next = entry;
        root.prev = entry;
        entry.attachTo(this);
    }

    synchronized void remove(TimerTaskEntry entry) {
        if (entry.next == null) {
            return; // 不在任何链表中
        }
        entry.prev.next = entry.next;
        entry.next.prev = entry.prev;
        entry.next = null;
        entry.prev = null;
        entry.detach();
    }

    /** 取出并清空本槽位的全部任务，逐个交给 consumer（重新插入下层轮或执行）。 */
    void flush(Consumer<TimerTaskEntry> consumer) {
        for (TimerTaskEntry entry : drain()) {
            consumer.accept(entry);
        }
    }

    private synchronized List<TimerTaskEntry> drain() {
        List<TimerTaskEntry> drained = new ArrayList<>();
        TimerTaskEntry head = root.next;
        while (head != root) {
            TimerTaskEntry next = head.next;
            head.next = null;
            head.prev = null;
            head.detach();
            drained.add(head);
            head = next;
        }
        root.next = root;
        root.prev = root;
        return drained;
    }
}
