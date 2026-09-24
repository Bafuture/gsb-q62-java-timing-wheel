package com.example.timer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 长延时场景：超过单层容量的任务经上层轮逐级回落，恰好触发一次。 */
class TimerLongDelayTest {

    private static HashedWheelTimer newTimer(ManualClock clock) {
        // tick=10ms；第 0 层 8 槽覆盖 80ms，第 1 层 8 槽覆盖 640ms
        return new HashedWheelTimer(clock, 10, 8, 8);
    }

    @Test
    void delayBeyondSingleLayerCascadesDownAndFiresOnce() {
        ManualClock clock = new ManualClock();
        HashedWheelTimer timer = newTimer(clock);

        AtomicInteger counter = new AtomicInteger();
        // 200ms = 20 tick > 第 0 层 8 槽容量，必须落到第 1 层
        timer.newTimeout(t -> counter.incrementAndGet(), 200, TimeUnit.MILLISECONDS);
        assertThat(timer.pendingTimeouts()).isEqualTo(1);

        clock.advanceMillis(190);
        timer.advanceClock();
        assertThat(counter).as("未到取整后的 deadline（200ms）不得触发").hasValue(0);

        clock.advanceMillis(10);
        timer.advanceClock();
        assertThat(counter).as("超过单层容量的长延时任务应在 200ms 触发").hasValue(1);

        // 再转一整圈确认不会重复触发
        clock.advanceMillis(640);
        timer.advanceClock();
        assertThat(counter).hasValue(1);
    }

    @Test
    void delayBeyondAllConfiguredLayersGrowsOverflowWheel() {
        ManualClock clock = new ManualClock();
        HashedWheelTimer timer = newTimer(clock);

        AtomicInteger counter = new AtomicInteger();
        // 2000ms = 200 tick > 两层总容量 64 tick，应动态长出第 3 层（覆盖 512 tick）
        timer.newTimeout(t -> counter.incrementAndGet(), 2_000, TimeUnit.MILLISECONDS);

        clock.advanceMillis(1_990);
        timer.advanceClock();
        assertThat(counter).hasValue(0);

        clock.advanceMillis(10);
        timer.advanceClock();
        assertThat(counter).hasValue(1);
        assertThat(timer.pendingTimeouts()).isZero();
    }

    @Test
    void precisionIsBoundedByOneTick() {
        ManualClock clock = new ManualClock();
        HashedWheelTimer timer = new HashedWheelTimer(clock, 10, 4, 4);

        AtomicInteger counter = new AtomicInteger();
        // 25ms 向上取整到 3 个 tick = 30ms，触发时刻落在 [25ms, 35ms) 预期内
        timer.newTimeout(t -> counter.incrementAndGet(), 25, TimeUnit.MILLISECONDS);

        clock.advanceMillis(20);
        timer.advanceClock();
        assertThat(counter).hasValue(0);

        clock.advanceMillis(5); // 25ms：目标 tick=2，仍未到 tick 3
        timer.advanceClock();
        assertThat(counter).hasValue(0);

        clock.advanceMillis(5); // 30ms
        timer.advanceClock();
        assertThat(counter).hasValue(1);
    }

    @Test
    void configurableSlotCountsPerWheel() {
        ManualClock clock = new ManualClock();
        // 非均等分层：第 0 层 2 槽（20ms），第 1 层 3 槽（60ms），第 2 层 5 槽（300ms）
        HashedWheelTimer timer = new HashedWheelTimer(clock, 10, 2, 3, 5);

        AtomicInteger counter = new AtomicInteger();
        timer.newTimeout(t -> counter.incrementAndGet(), 250, TimeUnit.MILLISECONDS);

        clock.advanceMillis(240);
        timer.advanceClock();
        assertThat(counter).hasValue(0);

        clock.advanceMillis(10);
        timer.advanceClock();
        assertThat(counter).hasValue(1);
    }
}
