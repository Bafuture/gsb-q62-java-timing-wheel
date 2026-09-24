package com.example.gsb.timingwheel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HierarchicalTimingWheelTimerTest {

    private static final long TICK_MS = 10;
    private static final int WHEEL_SIZE = 16; // 单层容量 160ms
    private static final long START_MS = 1_000_000L;

    private ManualClock clock;
    private List<Throwable> errors;
    private HierarchicalTimingWheelTimer timer;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(START_MS);
        errors = new CopyOnWriteArrayList<>();
        timer = new HierarchicalTimingWheelTimer(TICK_MS, WHEEL_SIZE, clock, errors::add);
    }

    private void elapse(long ms) {
        clock.advanceBy(ms);
        timer.advance();
    }

    // ---------- 提交 ----------

    @Test
    void firesTaskAtDeadlineButNotBefore() {
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(runs::incrementAndGet, 50, TimeUnit.MILLISECONDS);

        elapse(49);
        assertThat(runs).hasValue(0);
        assertThat(timer.pendingCount()).isEqualTo(1);

        elapse(1);
        assertThat(runs).hasValue(1);
        assertThat(timer.pendingCount()).isZero();
    }

    @Test
    void firesAllTasksOfSameTickInOneBatch() {
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            timer.schedule(runs::incrementAndGet, 30, TimeUnit.MILLISECONDS);
        }

        elapse(30);
        assertThat(runs).hasValue(3);
        assertThat(timer.pendingCount()).isZero();
    }

    @Test
    void firesZeroDelayTaskImmediately() {
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(runs::incrementAndGet, 0, TimeUnit.MILLISECONDS);
        assertThat(runs).hasValue(1);
        assertThat(timer.pendingCount()).isZero();
    }

    // ---------- 取消 ----------

    @Test
    void cancelledTaskNeverFires() {
        AtomicInteger runs = new AtomicInteger();
        Timeout timeout = timer.schedule(runs::incrementAndGet, 100, TimeUnit.MILLISECONDS);

        assertThat(timeout.cancel()).isTrue();
        assertThat(timeout.cancel()).isFalse(); // 重复取消无效
        assertThat(timeout.isCancelled()).isTrue();
        assertThat(timer.pendingCount()).isZero();

        elapse(1_000);
        assertThat(runs).hasValue(0);
    }

    @Test
    void cancellingOneTaskDoesNotAffectSiblingsInSameSlot() {
        AtomicInteger runs = new AtomicInteger();
        Timeout cancelled = timer.schedule(() -> { }, 60, TimeUnit.MILLISECONDS);
        timer.schedule(runs::incrementAndGet, 60, TimeUnit.MILLISECONDS);

        assertThat(cancelled.cancel()).isTrue();
        elapse(60);

        assertThat(runs).hasValue(1);
        assertThat(timer.pendingCount()).isZero();
    }

    @Test
    void cancelAfterExpiryReturnsFalse() {
        Timeout timeout = timer.schedule(() -> { }, 20, TimeUnit.MILLISECONDS);
        elapse(20);

        assertThat(timeout.isExpired()).isTrue();
        assertThat(timeout.cancel()).isFalse();
    }

    // ---------- 长延时（超过单层容量，落到上层轮） ----------

    @Test
    void longDelayTaskLandsInOverflowWheelAndFiresOnTime() {
        // 单层容量 160ms；5000ms 需要第三层（160 -> 2560 -> 40960）
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(runs::incrementAndGet, 5_000, TimeUnit.MILLISECONDS);

        assertThat(timer.levels()).isEqualTo(3);

        // 以 100ms 步长推进，跨越各层边界逐级下沉
        for (int i = 0; i < 49; i++) {
            elapse(100);
        }
        assertThat(runs).hasValue(0);
        assertThat(timer.pendingCount()).isEqualTo(1);

        elapse(100);
        assertThat(runs).hasValue(1);
        assertThat(timer.pendingCount()).isZero();
    }

    @Test
    void taskSlightlyBeyondOneRoundUsesSecondLevel() {
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(runs::incrementAndGet, 200, TimeUnit.MILLISECONDS); // > 160ms 单层容量

        assertThat(timer.levels()).isEqualTo(2);

        elapse(160); // 底层轮转满一圈，任务从上层下沉
        assertThat(runs).hasValue(0);

        elapse(40);
        assertThat(runs).hasValue(1);
    }

    // ---------- 异常隔离 ----------

    @Test
    void failingTaskDoesNotAffectOthersOrTheTimer() {
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(() -> {
            throw new IllegalStateException("boom");
        }, 30, TimeUnit.MILLISECONDS);
        timer.schedule(runs::incrementAndGet, 30, TimeUnit.MILLISECONDS);

        elapse(30);
        assertThat(runs).hasValue(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isInstanceOf(IllegalStateException.class);

        // 时间轮仍可正常工作
        timer.schedule(runs::incrementAndGet, 10, TimeUnit.MILLISECONDS);
        elapse(10);
        assertThat(runs).hasValue(2);
        assertThat(timer.pendingCount()).isZero();
    }
}
