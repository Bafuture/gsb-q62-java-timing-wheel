package com.example.timer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 取消场景：取消后绝不再触发，覆盖第 0 层与上层轮中的任务。 */
class TimerCancelTest {

    private static HashedWheelTimer newTimer(ManualClock clock) {
        // tick=10ms；第 0 层覆盖 80ms，第 1 层覆盖 640ms
        return new HashedWheelTimer(clock, 10, 8, 8);
    }

    @Test
    void cancelBeforeDeadlineNeverFires() {
        ManualClock clock = new ManualClock();
        HashedWheelTimer timer = newTimer(clock);

        AtomicInteger counter = new AtomicInteger();
        Timeout timeout =
                timer.newTimeout(t -> counter.incrementAndGet(), 50, TimeUnit.MILLISECONDS);
        assertThat(timer.pendingTimeouts()).isEqualTo(1);

        clock.advanceMillis(40);
        timer.advanceClock();
        assertThat(timeout.cancel()).isTrue();
        assertThat(timeout.isCancelled()).isTrue();
        assertThat(timer.pendingTimeouts()).isZero();

        clock.advanceMillis(100);
        timer.advanceClock();
        assertThat(counter).as("取消后的任务不得再被触发").hasValue(0);
    }

    @Test
    void cancelIsIdempotentAndImpossibleAfterExpiry() {
        ManualClock clock = new ManualClock();
        HashedWheelTimer timer = newTimer(clock);

        Timeout cancelled =
                timer.newTimeout(t -> { }, 50, TimeUnit.MILLISECONDS);
        assertThat(cancelled.cancel()).isTrue();
        assertThat(cancelled.cancel()).isFalse();

        Timeout fired = timer.newTimeout(t -> { }, 10, TimeUnit.MILLISECONDS);
        clock.advanceMillis(10);
        timer.advanceClock();
        assertThat(fired.isExpired()).isTrue();
        assertThat(fired.cancel()).isFalse();
    }

    @Test
    void cancelTaskSittingInUpperWheel() {
        ManualClock clock = new ManualClock();
        HashedWheelTimer timer = newTimer(clock);

        AtomicInteger counter = new AtomicInteger();
        // 200ms = 20 个 tick，超过第 0 层容量 8，落在第 1 层
        Timeout longTask =
                timer.newTimeout(t -> counter.incrementAndGet(), 200, TimeUnit.MILLISECONDS);

        clock.advanceMillis(100);
        timer.advanceClock();
        assertThat(longTask.isCancelled()).isFalse();
        assertThat(longTask.cancel()).as("取消仍停留在上层轮的任务").isTrue();

        clock.advanceMillis(300);
        timer.advanceClock();
        assertThat(counter).hasValue(0);
        assertThat(timer.pendingTimeouts()).isZero();
    }
}
