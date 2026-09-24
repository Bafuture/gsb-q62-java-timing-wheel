package com.example.gsb.timingwheel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HierarchicalTimingWheelTimerTest {

    private static final long TICK_MS = 10L;
    private static final int WHEEL_SIZE = 10; // 单层容量 100ms

    private ManualClock clock;
    private HierarchicalTimingWheelTimer timer;

    @AfterEach
    void tearDown() {
        if (timer != null) {
            timer.shutdown();
        }
    }

    private HierarchicalTimingWheelTimer newTimer() {
        clock = new ManualClock(0L);
        timer = new HierarchicalTimingWheelTimer(TICK_MS, WHEEL_SIZE, clock);
        return timer;
    }

    @Test
    void submittedTaskFiresAfterDelay() throws Exception {
        HierarchicalTimingWheelTimer timer = newTimer();
        CountDownLatch fired = new CountDownLatch(1);
        AtomicLong firedAt = new AtomicLong(-1L);

        Timeout timeout = timer.newTimeout(() -> {
            firedAt.set(clock.nowMs());
            fired.countDown();
        }, 50L, TimeUnit.MILLISECONDS);

        // 时钟未走到 50ms，任务不得触发
        clock.advanceMs(40L);
        assertThat(fired.await(150L, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(timer.pendingCount()).isEqualTo(1L);

        // 时钟走过截止时刻，任务触发
        clock.advanceMs(20L);
        assertThat(fired.await(2L, TimeUnit.SECONDS)).isTrue();
        assertThat(firedAt.get()).isGreaterThanOrEqualTo(50L);
        assertThat(timeout.isExpired()).isTrue();
        assertThat(timer.pendingCount()).isZero();
    }

    @Test
    void cancelledTaskNeverFires() throws Exception {
        HierarchicalTimingWheelTimer timer = newTimer();
        AtomicInteger runs = new AtomicInteger();

        Timeout timeout = timer.newTimeout(runs::incrementAndGet, 100L, TimeUnit.MILLISECONDS);
        assertThat(timer.pendingCount()).isEqualTo(1L);

        assertThat(timeout.cancel()).isTrue();
        assertThat(timeout.cancel()).isFalse();
        assertThat(timeout.isCancelled()).isTrue();
        assertThat(timer.pendingCount()).isZero();

        // 时间大幅流逝，已取消任务不得触发
        clock.advanceMs(10_000L);
        Thread.sleep(200L);
        assertThat(runs.get()).isZero();
    }

    @Test
    void cancelledTaskAmongManyDoesNotAffectOthers() throws Exception {
        HierarchicalTimingWheelTimer timer = newTimer();
        CountDownLatch fired = new CountDownLatch(2);

        Timeout cancelled = timer.newTimeout(() -> { }, 60L, TimeUnit.MILLISECONDS);
        timer.newTimeout(fired::countDown, 60L, TimeUnit.MILLISECONDS);
        timer.newTimeout(fired::countDown, 60L, TimeUnit.MILLISECONDS);

        assertThat(cancelled.cancel()).isTrue();
        clock.advanceMs(100L);

        assertThat(fired.await(2L, TimeUnit.SECONDS)).isTrue();
        assertThat(timer.pendingCount()).isZero();
    }

    @Test
    void longDelayBeyondSingleWheelCapacityLandsInOverflowWheel() throws Exception {
        HierarchicalTimingWheelTimer timer = newTimer();
        CountDownLatch fired = new CountDownLatch(1);

        // 单层容量只有 100ms，5000ms 的任务必须落到上层轮
        timer.newTimeout(fired::countDown, 5_000L, TimeUnit.MILLISECONDS);
        assertThat(timer.wheelLevels()).isEqualTo(3); // 100ms / 1s / 10s 三层

        // 逐级推进时钟，到期前不得触发
        clock.advanceMs(500L);   // 越过第一层容量
        clock.advanceMs(4_000L); // 越过第二层容量
        clock.advanceMs(499L);   // 距到期还差 1ms
        assertThat(fired.await(150L, TimeUnit.MILLISECONDS)).isFalse();

        clock.advanceMs(1L); // 到达 5000ms
        assertThat(fired.await(2L, TimeUnit.SECONDS)).isTrue();
        assertThat(timer.pendingCount()).isZero();
    }

    @Test
    void failingTaskDoesNotAffectPeersOrTimer() throws Exception {
        HierarchicalTimingWheelTimer timer = newTimer();
        CountDownLatch batch = new CountDownLatch(2);

        timer.newTimeout(() -> {
            batch.countDown();
            throw new RuntimeException("boom");
        }, 50L, TimeUnit.MILLISECONDS);
        timer.newTimeout(batch::countDown, 50L, TimeUnit.MILLISECONDS);

        clock.advanceMs(100L);
        assertThat(batch.await(2L, TimeUnit.SECONDS)).isTrue();

        // 工作线程仍然存活，后续任务正常触发
        CountDownLatch after = new CountDownLatch(1);
        timer.newTimeout(after::countDown, 20L, TimeUnit.MILLISECONDS);
        clock.advanceMs(50L);
        assertThat(after.await(2L, TimeUnit.SECONDS)).isTrue();
    }
}
