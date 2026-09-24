package com.example.timer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 分层（多层）时间轮定时器。
 *
 * <p>结构：共 N 层（N = {@code wheelSlots.length}，至少 2 层），
 * 第 i 层有 {@code wheelSlots[i]} 个槽，基础 tick 为 {@code tickMs} 毫秒。
 * 第 0 层一槽代表 1 个 tick，第 i 层一槽代表
 * {@code wheelSlots[0] * ... * wheelSlots[i-1]} 个 tick，
 * 整个时间轮覆盖的时间范围为 {@code tickMs * ΠwheelSlots[i]} 毫秒。
 * 超过容量的任务会触发动态扩展的溢出层，溢出层槽位数取最上层配置，
 * 因此支持的延时上限可达 {@code Long.MAX_VALUE} 个 tick。</p>
 *
 * <p>槽内为侵入式双向链表，提交与取消均为 O(1)，不再像
 * {@link java.util.concurrent.ScheduledThreadPoolExecutor} 那样
 * 每个任务占一个堆节点。</p>
 *
 * <p>时间推进只依赖注入的 {@link Clock}：测试时注入手工时钟并直接调用
 * {@link #advanceClock()}，无需真实等待；生产环境可调用 {@link #start()}
 * 启动后台线程按 tick 节奏推进。</p>
 */
public final class HashedWheelTimer implements Timer {

    /** 任务异常处理器。单个任务失败不影响同批其他任务。 */
    @FunctionalInterface
    public interface FailureHandler {
        void handle(Throwable t, TimerTask task);

        FailureHandler SILENT = (t, task) -> { };
    }

    private final Clock clock;
    private final long tickMs;
    private final int[] slotsPerWheel;
    private final FailureHandler failureHandler;
    private final ReentrantLock lock = new ReentrantLock();

    private Slot[][] wheels;
    private long[] tickDuration;
    private long[] cursor;
    private final List<Node> readyTasks = new ArrayList<>();
    private long pendingCount;
    private volatile boolean stopped;
    private volatile Worker worker;

    public HashedWheelTimer(Clock clock, long tickMs, int... wheelSlots) {
        this(clock, tickMs, FailureHandler.SILENT, wheelSlots);
    }

    public HashedWheelTimer(Clock clock, long tickMs, FailureHandler failureHandler, int... wheelSlots) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (tickMs < 1) {
            throw new IllegalArgumentException("tickMs must be >= 1");
        }
        if (wheelSlots == null || wheelSlots.length < 2) {
            throw new IllegalArgumentException("at least two wheels are required");
        }
        for (int slots : wheelSlots) {
            if (slots < 2) {
                throw new IllegalArgumentException("each wheel must have at least 2 slots");
            }
        }
        this.tickMs = tickMs;
        this.slotsPerWheel = wheelSlots.clone();
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");

        int levelCount = wheelSlots.length;
        this.wheels = new Slot[levelCount][];
        this.tickDuration = new long[levelCount];
        this.cursor = new long[levelCount];

        long nowTick = Math.floorDiv(clock.millis(), tickMs);
        long duration = 1L;
        for (int level = 0; level < levelCount; level++) {
            this.tickDuration[level] = duration;
            this.wheels[level] = newSlots(wheelSlots[level]);
            // 游标对齐到该层槽边界，保证 deadline/duration % slots 是稳定的槽下标。
            this.cursor[level] = nowTick - Math.floorMod(nowTick, duration);
            duration = Math.multiplyExact(duration, wheelSlots[level]);
        }
    }

    /** 启动后台推进线程（仅应配合 SystemClock 使用）。 */
    public void start() {
        start(Thread.NORM_PRIORITY, "hierarchical-timing-wheel");
    }

    public void start(int threadPriority, String threadName) {
        if (!(clock instanceof SystemClock)) {
            throw new IllegalStateException(
                    "background worker requires a SystemClock; drive advanceClock() manually");
        }
        if (worker != null) {
            throw new IllegalStateException("timer already started");
        }
        Worker w = new Worker(threadName, threadPriority);
        worker = w;
        w.thread.start();
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        if (delay < 0) {
            throw new IllegalArgumentException("delay must be >= 0");
        }
        long now = clock.millis();
        long deadlineMs = saturatedAdd(now, unit.toMillis(delay));
        long deadlineTick = deadlineMs == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : -Math.floorDiv(-deadlineMs, tickMs); // 向上取整，保证不提前触发
        Node node = new Node(task, deadlineTick, this);
        lock.lock();
        try {
            if (stopped) {
                throw new IllegalStateException("timer is stopped");
            }
            pendingCount++;
            insert(node);
        } finally {
            lock.unlock();
        }
        return node;
    }

    @Override
    public void stop() {
        Worker w = worker;
        stopped = true;
        if (w != null) {
            w.thread.interrupt();
            try {
                w.thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 按时钟当前时刻推进时间轮并触发全部到期任务。
     * 任务在锁外逐个执行，单个任务抛异常只影响自身。
     */
    @Override
    public void advanceClock() {
        long targetTick = Math.floorDiv(clock.millis(), tickMs);
        List<Node> fired = new ArrayList<>();
        lock.lock();
        try {
            // 提交时已过期（如 delay=0）的任务本轮一并触发，
            // 即使时钟尚未跨过下一个 tick 边界。
            fired.addAll(readyTasks);
            readyTasks.clear();

            if (targetTick > cursor[0]) {
                // 逐 tick 推进，保证一次跨多个 tick（手工时钟）时不遗漏中间槽。
                // 每跨过一个 tick：先推进各层游标，再自顶向下搬运边界槽，
                // 最后清空第 0 层当前槽。高层跨边界时低层必然同时跨边界，
                // 因此发生搬运的层恰好是 1..highestMoved 的前缀。
                long current = cursor[0];
                while (current < targetTick) {
                    current++;
                    cursor[0] = current;

                    int levels = wheels.length;
                    int highestMoved = 0;
                    for (int level = 1; level < levels; level++) {
                        long duration = tickDuration[level];
                        if (current - cursor[level] >= duration) {
                            cursor[level] += duration;
                            highestMoved = level;
                        }
                    }
                    for (int level = highestMoved; level >= 1; level--) {
                        long duration = tickDuration[level];
                        int index =
                                (int) Math.floorMod(cursor[level] / duration, wheels[level].length);
                        drain(wheels[level][index]);
                    }
                    drain(wheels[0][(int) Math.floorMod(current, wheels[0].length)]);
                }
            }
            fired.addAll(readyTasks);
            readyTasks.clear();
        } finally {
            lock.unlock();
        }
        for (Node node : fired) {
            fire(node);
        }
    }

    /** 已挂起但尚未触发/取消的任务数，主要用于测试与监控。 */
    public long pendingTimeouts() {
        lock.lock();
        try {
            return pendingCount;
        } finally {
            lock.unlock();
        }
    }

    // ---- 内部实现 -------------------------------------------------------

    /** 把节点放进第一个能容纳它的层；放不下则动态长出溢出层后重试。 */
    private void insert(Node node) {
        long deadline = node.deadlineTick;
        while (true) {
            int levels = wheels.length;
            for (int level = 0; level < levels; level++) {
                long duration = tickDuration[level];
                long slotEnd = cursor[level] + duration * wheels[level].length;
                if (deadline < slotEnd) {
                    if (deadline <= cursor[level]) {
                        node.state = Node.STATE_EXPIRED;
                        pendingCount--;
                        readyTasks.add(node);
                    } else {
                        int index = (int) Math.floorMod(deadline / duration, wheels[level].length);
                        wheels[level][index].add(node, index, level);
                    }
                    return;
                }
            }
            grow(levels);
        }
    }

    private void grow(int currentLevels) {
        long newDuration =
                Math.multiplyExact(tickDuration[currentLevels - 1], wheels[currentLevels - 1].length);
        int newSlotCount = slotsPerWheel[slotsPerWheel.length - 1];

        Slot[][] grownWheels = Arrays.copyOf(wheels, currentLevels + 1);
        long[] grownDuration = Arrays.copyOf(tickDuration, currentLevels + 1);
        long[] grownCursor = Arrays.copyOf(cursor, currentLevels + 1);
        grownWheels[currentLevels] = newSlots(newSlotCount);
        grownDuration[currentLevels] = newDuration;
        grownCursor[currentLevels] =
                cursor[0] - Math.floorMod(cursor[0], newDuration);

        wheels = grownWheels;
        tickDuration = grownDuration;
        cursor = grownCursor;
    }

    private void drain(Slot slot) {
        Node node = slot.head;
        slot.head = null;
        slot.tail = null;
        while (node != null) {
            Node next = node.next;
            node.prev = null;
            node.next = null;
            node.level = -1;
            node.slotIndex = -1;
            if (node.state == Node.STATE_PENDING) {
                insert(node);
            }
            node = next;
        }
    }

    private void fire(Node node) {
        try {
            node.task.run(node);
        } catch (Throwable t) {
            try {
                failureHandler.handle(t, node.task);
            } catch (Throwable handlerFailure) {
                t.addSuppressed(handlerFailure);
                failureHandler.handle(t, node.task);
            }
        }
    }

    private boolean cancelNode(Node node) {
        lock.lock();
        try {
            if (node.state != Node.STATE_PENDING) {
                return false;
            }
            node.state = Node.STATE_CANCELLED;
            if (node.level >= 0) {
                wheels[node.level][node.slotIndex].remove(node);
                node.level = -1;
                node.slotIndex = -1;
            }
            pendingCount--;
            return true;
        } finally {
            lock.unlock();
        }
    }

    private static Slot[] newSlots(int count) {
        Slot[] slots = new Slot[count];
        for (int i = 0; i < count; i++) {
            slots[i] = new Slot();
        }
        return slots;
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /** 侵入式双向链表节点，同时也是对外的任务句柄。 */
    private static final class Node implements Timeout {
        static final int STATE_PENDING = 0;
        static final int STATE_CANCELLED = 1;
        static final int STATE_EXPIRED = 2;

        final TimerTask task;
        final long deadlineTick;
        final HashedWheelTimer timer;

        volatile int state = STATE_PENDING;
        int level = -1;
        int slotIndex = -1;
        Node prev;
        Node next;

        Node(TimerTask task, long deadlineTick, HashedWheelTimer timer) {
            this.task = task;
            this.deadlineTick = deadlineTick;
            this.timer = timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public boolean isExpired() {
            return state == STATE_EXPIRED;
        }

        @Override
        public boolean isCancelled() {
            return state == STATE_CANCELLED;
        }

        @Override
        public boolean cancel() {
            return timer.cancelNode(this);
        }
    }

    /** 侵入式双向链表槽，所有访问都在 timer 锁内进行。 */
    private static final class Slot {
        private Node head;
        private Node tail;

        void add(Node node, int index, int level) {
            node.slotIndex = index;
            node.level = level;
            if (tail == null) {
                head = tail = node;
            } else {
                tail.next = node;
                node.prev = tail;
                tail = node;
            }
        }

        void remove(Node node) {
            if (node.prev != null) {
                node.prev.next = node.next;
            } else if (head == node) {
                head = node.next;
            }
            if (node.next != null) {
                node.next.prev = node.prev;
            } else if (tail == node) {
                tail = node.prev;
            }
            node.prev = null;
            node.next = null;
        }
    }

    /** 后台推进线程：按 tick 节奏睡眠并推进时间轮。 */
    private final class Worker implements Runnable {
        final Thread thread;

        Worker(String name, int priority) {
            this.thread = new Thread(this, name);
            this.thread.setDaemon(true);
            this.thread.setPriority(priority);
        }

        @Override
        public void run() {
            while (!stopped) {
                long start = clock.millis();
                advanceClock();
                long elapsed = clock.millis() - start;
                long sleep = tickMs - Math.floorMod(elapsed, tickMs);
                try {
                    Thread.sleep(Math.max(1, sleep));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
