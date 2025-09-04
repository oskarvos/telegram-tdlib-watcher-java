package com.oleg.td;

class MonitorStatus {
    private boolean monitoring;
    private MonitorConfig currentConfig;

    // Геттеры и сеттеры
    public boolean isMonitoring() {
        return monitoring;
    }

    public void setMonitoring(boolean monitoring) {
        this.monitoring = monitoring;
    }

    public MonitorConfig getCurrentConfig() {
        return currentConfig;
    }

    public void setCurrentConfig(MonitorConfig currentConfig) {
        this.currentConfig = currentConfig;
    }
}
