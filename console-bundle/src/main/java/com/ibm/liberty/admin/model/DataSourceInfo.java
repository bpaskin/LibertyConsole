package com.ibm.liberty.admin.model;

/**
 * Represents JDBC DataSource configuration
 */
public class DataSourceInfo {
    private String jndiName;
    private String driverClass;
    private String url;
    private String username;
    private int minPoolSize;
    private int maxPoolSize;
    private int activeConnections;
    private int idleConnections;
    private String state;
    private boolean testConnection;

    public DataSourceInfo() {
    }

    public DataSourceInfo(String jndiName, String url, String state) {
        this.jndiName = jndiName;
        this.url = url;
        this.state = state;
    }

    // Getters and Setters
    public String getJndiName() {
        return jndiName;
    }

    public void setJndiName(String jndiName) {
        this.jndiName = jndiName;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public void setDriverClass(String driverClass) {
        this.driverClass = driverClass;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getMinPoolSize() {
        return minPoolSize;
    }

    public void setMinPoolSize(int minPoolSize) {
        this.minPoolSize = minPoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getActiveConnections() {
        return activeConnections;
    }

    public void setActiveConnections(int activeConnections) {
        this.activeConnections = activeConnections;
    }

    public int getIdleConnections() {
        return idleConnections;
    }

    public void setIdleConnections(int idleConnections) {
        this.idleConnections = idleConnections;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public boolean isTestConnection() {
        return testConnection;
    }

    public void setTestConnection(boolean testConnection) {
        this.testConnection = testConnection;
    }
}