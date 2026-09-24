package com.example.timer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 提交场景：任务按时触发、不提前触发，同槽任务异常互不影响。 */
class TimerSubmitTest {

    private static HashedWheelTimer newTimer(ManualClock clock) {
        // tick=10ms；第 0 层 8 槽覆盖 80ms，第 1 层 8 槽覆盖 640ms
        return new HashedWheelTimer(clock, 10, 8, 8);
    }

    @Test
    void firesAtDeadlineAndNotBefore() {
        ManualClock clock = new ManualClock(1_000);
        HashedWheelTimer timer = newTimer(clock);

        List<Long> firedAt = new ArrayList<>();
        timer.newTimeout(t -> firedAt.add(clock.millis()), 30, TimeUnit.MILLISECONDS);

        clock.advanceMillis(25);
        timer.advanceClock();
        assertThat(firedAt).as("deadline 向上取整到 30ms，25ms 时不应触发").isEmpty();

        clock.advanceMillis(5);
        timer.advanceClock();
        assertThat(firedAt).containsExactly(1_030L);
        assertThat(timer.pendingTimeouts()).isZero();
    }

    @Test
    void zeroDelayFiresOnFirstAdvance() {
        ManualClock clock = new ManualClock();
        HashedWheelTimer timer = newTimer(clock);

        AtomicInteger counter = new AtomicInteger();
        timer.newTimeout(t -> counter.incrementAndGet(), 0, TimeUnit.MILLISECONDS);

        timer.advanceClock();
        assertThat(counter).hasValue(1);
    }

    @Test
    void multipleTasksAcrossSlotsAllFire() {
        ManualClock clock = new ManualClock();
        HashedWheelTimer timer = newTimer(clock);

        List<Integer> order = new ArrayList<>();
        timer.newTimeout(t -> order.add(1), 10, TimeUnit.MILLISECONDS);
        timer.newTimeout(t -> order.add(2), 20, TimeUnit.MILLISECONDS);
        timer.newTimeout(t -> order.add(3), 70, TimeUnit.MILLISECONDS);

        clock.advanceMillis(70);
        timer.advanceClock();

        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    void oneTaskThrowingDoesNotStopOthersInSameBatch() {
        ManualClock clock = new ManualClock();
        List<Throwable> failures = new ArrayList<>();
        HashedWheelTimer timer =
                new HashedWheelTimer(clock, 10, (t, task) -> failures.add(t), 8, 8);

        AtomicInteger survivors = new AtomicInteger();
        timer.newTimeout(t -> {
            throw new IllegalStateException("boom");
        }, 20, TimeUnit.MILLISECONDS);
        timer.newTimeout(t -> survivors.incrementAndGet(), 20, TimeUnit.MILLISECONDS);
        timer.newTimeout(t -> survivors.incrementAndGet(), 30, TimeUnit.MILLISECONDS);

        clock.advanceMillis(30);
        timer.advanceClock();

        assertThat(survivors).as("同槽及后续槽的任务都必须正常触发").hasValue(2);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(IllegalStateException.class);
    }
}
