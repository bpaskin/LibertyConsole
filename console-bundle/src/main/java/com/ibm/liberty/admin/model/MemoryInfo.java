package com.ibm.liberty.admin.model;

/**
 * Represents memory usage information
 */
public class MemoryInfo {
    private long totalMemory;
    private long freeMemory;
    private long usedMemory;
    private long maxMemory;
    private double usagePercentage;

    public MemoryInfo() {
    }

    public MemoryInfo(long totalMemory, long freeMemory, long usedMemory, long maxMemory) {
        this.totalMemory = totalMemory;
        this.freeMemory = freeMemory;
        this.usedMemory = usedMemory;
        this.maxMemory = maxMemory;
        this.usagePercentage = (double) usedMemory / maxMemory * 100;
    }

    // Getters and Setters
    public long getTotalMemory() {
        return totalMemory;
    }

    public void setTotalMemory(long totalMemory) {
        this.totalMemory = totalMemory;
    }

    public long getFreeMemory() {
        return freeMemory;
    }

    public void setFreeMemory(long freeMemory) {
        this.freeMemory = freeMemory;
    }

    public long getUsedMemory() {
        return usedMemory;
    }

    public void setUsedMemory(long usedMemory) {
        this.usedMemory = usedMemory;
    }

    public long getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(long maxMemory) {
        this.maxMemory = maxMemory;
    }

    public double getUsagePercentage() {
        return usagePercentage;
    }

    public void setUsagePercentage(double usagePercentage) {
        this.usagePercentage = usagePercentage;
    }
}