package com.ibm.liberty.admin.model;

import java.util.ArrayList;
import java.util.List;

/**
 * User settings for the admin console
 */
public class UserSettings {
    private String theme;
    private boolean showCpuChart;
    private boolean showThreadChart;
    private boolean showGcChart;
    private List<String> dynamicCharts;
    private boolean autoRefreshMetrics;

    public UserSettings() {
        // Default values
        this.theme = "light";
        this.showCpuChart = true;
        this.showThreadChart = true;
        this.showGcChart = true;
        this.dynamicCharts = new ArrayList<>();
        this.autoRefreshMetrics = false;
    }
    
    public String getTheme() {
        return theme;
    }
    
    public void setTheme(String theme) {
        this.theme = theme;
    }
    
    public boolean isShowCpuChart() {
        return showCpuChart;
    }
    
    public void setShowCpuChart(boolean showCpuChart) {
        this.showCpuChart = showCpuChart;
    }
    
    public boolean isShowThreadChart() {
        return showThreadChart;
    }
    
    public void setShowThreadChart(boolean showThreadChart) {
        this.showThreadChart = showThreadChart;
    }
    
    public boolean isShowGcChart() {
        return showGcChart;
    }
    
    public void setShowGcChart(boolean showGcChart) {
        this.showGcChart = showGcChart;
    }
    
    public List<String> getDynamicCharts() {
        return dynamicCharts;
    }
    
    public void setDynamicCharts(List<String> dynamicCharts) {
        this.dynamicCharts = dynamicCharts;
    }

    public boolean isAutoRefreshMetrics() {
        return autoRefreshMetrics;
    }

    public void setAutoRefreshMetrics(boolean autoRefreshMetrics) {
        this.autoRefreshMetrics = autoRefreshMetrics;
    }
}

// Made with Bob
