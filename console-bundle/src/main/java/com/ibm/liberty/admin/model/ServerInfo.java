package com.ibm.liberty.admin.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents Liberty server information
 */
public class ServerInfo {
    private String serverName;
    private String version;
    private String state;
    private LocalDateTime startTime;
    private long uptime;
    private String javaVersion;
    private String javaVendor;
    private String osName;
    private String osVersion;
    private List<String> installedFeatures;
    private MemoryInfo memoryInfo;
    private List<String> activeApplications;

    public ServerInfo() {
    }

    public ServerInfo(String serverName, String version, String state) {
        this.serverName = serverName;
        this.version = version;
        this.state = state;
    }

    // Getters and Setters
    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public long getUptime() {
        return uptime;
    }

    public void setUptime(long uptime) {
        this.uptime = uptime;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    public String getJavaVendor() {
        return javaVendor;
    }

    public void setJavaVendor(String javaVendor) {
        this.javaVendor = javaVendor;
    }

    public String getOsName() {
        return osName;
    }

    public void setOsName(String osName) {
        this.osName = osName;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public List<String> getInstalledFeatures() {
        return installedFeatures;
    }

    public void setInstalledFeatures(List<String> installedFeatures) {
        this.installedFeatures = installedFeatures;
    }

    public MemoryInfo getMemoryInfo() {
        return memoryInfo;
    }

    public void setMemoryInfo(MemoryInfo memoryInfo) {
        this.memoryInfo = memoryInfo;
    }

    public List<String> getActiveApplications() {
        return activeApplications;
    }

    public void setActiveApplications(List<String> activeApplications) {
        this.activeApplications = activeApplications;
    }
}