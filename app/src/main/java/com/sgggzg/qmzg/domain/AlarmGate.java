package com.sgggzg.qmzg.domain;

import android.os.SystemClock;

/** 报警冷却：避免 OCR 每几秒命中一次就重复报警。 */
public final class AlarmGate {

    private long lastAt;
    private int count;

    public synchronized int permit(int cooldownSec) {
        long now = SystemClock.elapsedRealtime();
        if (lastAt != 0 && now - lastAt < cooldownSec * 1000L) {
            return -1;
        }
        lastAt = now;
        count++;
        return count;
    }

    public synchronized int getCount() {
        return count;
    }

    public synchronized void reset() {
        lastAt = 0;
        count = 0;
    }
}
