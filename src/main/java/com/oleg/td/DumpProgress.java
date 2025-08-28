// ============================================================================
// File: src/main/java/com/oleg/td/DumpProgress.java
// Назначение: DTO прогресса для фронтенда.
// ============================================================================
package com.oleg.td;

/**
 * Простая модель прогресса: проценты и флаг активности.
 */
public class DumpProgress {
    private int percent;
    private boolean running;

    public DumpProgress(int percent, boolean running) { this.percent = percent; this.running = running; }
    public int getPercent() { return percent; }
    public void setPercent(int percent) { this.percent = percent; }
    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }
}