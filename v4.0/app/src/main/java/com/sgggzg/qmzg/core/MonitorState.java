package com.sgggzg.qmzg.core;

import java.util.concurrent.atomic.AtomicReference;

/** 监控状态机：运行 / 正在暂停 / 已暂停 / 报警暂停。 */
public final class MonitorState {

    public enum Phase {
        STOPPED,
        RUNNING,
        PAUSING,
        PAUSED,
        ALARM
    }

    private static final AtomicReference<Phase> PHASE = new AtomicReference<>(Phase.STOPPED);

    private MonitorState() {
    }

    public static Phase get() {
        return PHASE.get();
    }

    public static void set(Phase phase) {
        PHASE.set(phase == null ? Phase.STOPPED : phase);
    }

    public static boolean isRunning() {
        return PHASE.get() == Phase.RUNNING;
    }

    public static boolean isPausing() {
        return PHASE.get() == Phase.PAUSING;
    }

    public static boolean isPaused() {
        return PHASE.get() == Phase.PAUSED;
    }

    public static boolean isAlarm() {
        return PHASE.get() == Phase.ALARM;
    }

    public static boolean shouldProcess() {
        Phase phase = PHASE.get();
        return phase == Phase.RUNNING || phase == Phase.PAUSING;
    }
}
