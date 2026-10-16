package com.ibm.liberty.admin.model;

import java.time.LocalDateTime;

/**
 * Represents deployed application information
 */
public class ApplicationInfo {
    private String name;
    private String contextRoot;
    private String state;
    private LocalDateTime deployedTime;
    private String type;
    private long size;
    private int activeRequests;
    private long totalRequests;
    private String version;
    private String fileName;

    public ApplicationInfo() {
    }

    public ApplicationInfo(String name, String contextRoot, String state) {
        this.name = name;
        this.contextRoot = contextRoot;
        this.state = state;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContextRoot() {
        return contextRoot;
    }

    public void setContextRoot(String contextRoot) {
        this.contextRoot = contextRoot;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public LocalDateTime getDeployedTime() {
        return deployedTime;
    }

    public void setDeployedTime(LocalDateTime deployedTime) {
        this.deployedTime = deployedTime;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public int getActiveRequests() {
        return activeRequests;
    }

    public void setActiveRequests(int activeRequests) {
        this.activeRequests = activeRequests;
    }

    public long getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(long totalRequests) {
        this.totalRequests = totalRequests;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
}