package com.ibm.liberty.admin.model;

/**
 * Model for application-specific metrics
 */
public class ApplicationMetrics {
    private String applicationName;
    private long requestCount;
    private double averageResponseTime;
    private long activeRequests;
    private long errorCount;
    private double requestRate; // requests per second
    private String status;
    
    public ApplicationMetrics() {
    }
    
    public ApplicationMetrics(String applicationName) {
        this.applicationName = applicationName;
    }
    
    // Getters and Setters
    public String getApplicationName() {
        return applicationName;
    }
    
    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }
    
    public long getRequestCount() {
        return requestCount;
    }
    
    public void setRequestCount(long requestCount) {
        this.requestCount = requestCount;
    }
    
    public double getAverageResponseTime() {
        return averageResponseTime;
    }
    
    public void setAverageResponseTime(double averageResponseTime) {
        this.averageResponseTime = averageResponseTime;
    }
    
    public long getActiveRequests() {
        return activeRequests;
    }
    
    public void setActiveRequests(long activeRequests) {
        this.activeRequests = activeRequests;
    }
    
    public long getErrorCount() {
        return errorCount;
    }
    
    public void setErrorCount(long errorCount) {
        this.errorCount = errorCount;
    }
    
    public double getRequestRate() {
        return requestRate;
    }
    
    public void setRequestRate(double requestRate) {
        this.requestRate = requestRate;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
}