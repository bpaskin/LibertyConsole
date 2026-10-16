package com.ibm.liberty.admin.service;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import com.ibm.liberty.admin.model.ApplicationInfo;
import com.ibm.liberty.admin.model.ApplicationMetrics;
import com.ibm.liberty.admin.model.MemoryInfo;
import com.ibm.liberty.admin.model.ServerInfo;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for managing Liberty server operations
 */
public class ServerManagementService {

    private static final Logger LOGGER = Logger.getLogger(ServerManagementService.class.getName());

    private static final Pattern FEATURE_PATTERN = Pattern.compile("<feature>\\s*([^<]+?)\\s*</feature>");

    private final ApplicationManagementService applicationManagementService;
    private final LibertyMBeans mbeans;
    private final Supplier<ConsoleRuntime> runtime;

    public ServerManagementService(ApplicationManagementService applicationManagementService, LibertyMBeans mbeans,
            Supplier<ConsoleRuntime> runtime) {
        this.applicationManagementService = applicationManagementService;
        this.mbeans = mbeans;
        this.runtime = runtime;
    }

    /**
     * Get current server information
     */
    public ServerInfo getServerInfo() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

        ServerInfo serverInfo = new ServerInfo();
        serverInfo.setServerName(getServerName());
        serverInfo.setVersion(getServerVersion());
        serverInfo.setState("STARTED");

        serverInfo.setStartTime(LocalDateTime.ofInstant(
            Instant.ofEpochMilli(runtimeMXBean.getStartTime()), ZoneId.systemDefault()));
        serverInfo.setUptime(runtimeMXBean.getUptime());

        serverInfo.setJavaVersion(System.getProperty("java.version"));
        serverInfo.setJavaVendor(System.getProperty("java.vendor"));
        serverInfo.setOsName(System.getProperty("os.name"));
        serverInfo.setOsVersion(System.getProperty("os.version"));

        serverInfo.setMemoryInfo(getMemoryInfo());
        serverInfo.setInstalledFeatures(getInstalledFeatures());
        serverInfo.setActiveApplications(getActiveApplications());

        return serverInfo;
    }

    /**
     * Get memory usage information
     */
    public MemoryInfo getMemoryInfo() {
        Runtime jvm = Runtime.getRuntime();
        long maxMemory = jvm.maxMemory();
        long totalMemory = jvm.totalMemory();
        long freeMemory = jvm.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        return new MemoryInfo(totalMemory, freeMemory, usedMemory, maxMemory);
    }

    /**
     * Server name, from the ServerInfoMBean or the wlp.server.name property
     */
    private String getServerName() {
        String name = mbeans.serverInfo("Name");
        return name != null && !name.isBlank() ? name : runtime.get().getServerName();
    }

    /**
     * Liberty version, from the ServerInfoMBean or the product properties files
     */
    private String getServerVersion() {
        String version = mbeans.serverInfo("LibertyVersion");
        if (version != null && !version.isBlank()) {
            return version;
        }
        String fromFile = getLibertyVersionFromFile();
        return fromFile != null ? fromFile : "Unknown";
    }

    /**
     * Reads com.ibm.websphere.productVersion from wlp/lib/versions. WebSphere Liberty
     * (WebSphereApplicationServer.properties) is preferred over Open Liberty
     * (openliberty.properties) because WebSphere Liberty ships both files.
     */
    private String getLibertyVersionFromFile() {
        Path installDir = runtime.get().getInstallDir();
        if (installDir == null) {
            return null;
        }
        for (String file : List.of("WebSphereApplicationServer.properties", "openliberty.properties")) {
            Path versionFile = installDir.resolve("lib").resolve("versions").resolve(file);
            if (!Files.exists(versionFile)) {
                continue;
            }
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(versionFile)) {
                props.load(is);
                String version = props.getProperty("com.ibm.websphere.productVersion");
                if (version != null) {
                    return version;
                }
            } catch (IOException e) {
                LOGGER.fine("Could not read version from " + versionFile + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Features listed in the featureManager of server.xml
     */
    private List<String> getInstalledFeatures() {
        Path serverXmlPath = runtime.get().getServerXml();
        if (!Files.exists(serverXmlPath)) {
            LOGGER.warning("Could not locate server.xml at " + serverXmlPath);
            return new ArrayList<>(); // the UI shows an error for an empty list
        }

        List<String> features = new ArrayList<>();
        try {
            String content = Files.readString(serverXmlPath, StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
            Matcher matcher = FEATURE_PATTERN.matcher(content);
            while (matcher.find()) {
                String feature = matcher.group(1).trim();
                if (!feature.isEmpty()) {
                    features.add(feature);
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Error reading installed features: " + e.getMessage());
        }
        return features;
    }

    /**
     * Get list of active applications
     */
    private List<String> getActiveApplications() {
        List<String> apps = new ArrayList<>();
        for (ApplicationInfo app : applicationManagementService.getAllApplications()) {
            if (app.getName() != null && "STARTED".equalsIgnoreCase(app.getState())) {
                apps.add(app.getName());
            }
        }
        Collections.sort(apps);
        return apps;
    }

    /**
     * Perform garbage collection
     */
    public void performGarbageCollection() {
        LOGGER.info("Performing garbage collection");
        System.gc();
    }

    /**
     * Get thread dump
     */
    public String getThreadDump() {
        StringBuilder dump = new StringBuilder();
        Thread.getAllStackTraces().forEach((thread, stackTrace) -> {
            dump.append("Thread: ").append(thread.getName())
                .append(" (").append(thread.getState()).append(")\n");
            for (StackTraceElement element : stackTrace) {
                dump.append("  at ").append(element).append("\n");
            }
            dump.append("\n");
        });
        return dump.toString();
    }

    /**
     * Request metrics for each application, from Liberty's monitoring MBeans
     * (monitor-1.0, which mpMetrics enables). They are read in-process, so the
     * /metrics endpoint can keep its default authentication.
     */
    public List<ApplicationMetrics> getApplicationMetrics() {
        Map<String, ApplicationInfo> applications = new LinkedHashMap<>();
        Map<String, ApplicationMetrics> metricsMap = new LinkedHashMap<>();
        for (ApplicationInfo app : applicationManagementService.getAllApplications()) {
            if (app.getName() == null || app.getName().isBlank()) {
                continue;
            }
            applications.put(app.getName(), app);

            ApplicationMetrics metrics = new ApplicationMetrics(app.getName());
            metrics.setStatus(app.getState() != null ? app.getState() : "STARTED");
            metrics.setActiveRequests(app.getActiveRequests());
            metricsMap.put(app.getName(), metrics);
        }

        // Totals per application: [0] request count, [1] total time in ns
        Map<String, double[]> totals = new LinkedHashMap<>();
        List<LibertyMBeans.HttpRouteStats> routes = mbeans.httpRouteStats();
        if (!routes.isEmpty()) {
            for (LibertyMBeans.HttpRouteStats route : routes) {
                String appName = applicationForRoute(route.route, applications);
                if (appName == null) {
                    continue;
                }
                double[] total = totals.computeIfAbsent(appName, k -> new double[2]);
                total[0] += route.count;
                total[1] += route.durationNanos;
                if (route.status >= 500 || !route.errorType.isBlank()) {
                    ApplicationMetrics metrics = metricsMap.get(appName);
                    metrics.setErrorCount(metrics.getErrorCount() + route.count);
                }
            }
        } else {
            // Older Liberty versions: servlet statistics, grouped by application name
            for (LibertyMBeans.ServletRequestStats servlet : mbeans.servletStats()) {
                if (!applications.containsKey(servlet.appName)) {
                    continue;
                }
                double[] total = totals.computeIfAbsent(servlet.appName, k -> new double[2]);
                total[0] += servlet.count;
                total[1] += servlet.meanNanos * servlet.count;
            }
        }

        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        for (Map.Entry<String, double[]> entry : totals.entrySet()) {
            ApplicationMetrics metrics = metricsMap.get(entry.getKey());
            long count = (long) entry.getValue()[0];
            metrics.setRequestCount(count);
            if (count > 0) {
                metrics.setAverageResponseTime(entry.getValue()[1] / count / 1_000_000d); // ns -> ms
            }
            if (uptimeSeconds > 0) {
                metrics.setRequestRate((double) count / uptimeSeconds);
            }
        }

        return new ArrayList<>(metricsMap.values());
    }

    /**
     * Get thread pool statistics
     */
    public Map<String, Integer> getThreadPoolStats() {
        Map<String, Integer> stats = new HashMap<>();
        try {
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            stats.put("Default Executor", threadMXBean.getThreadCount());
        } catch (Exception e) {
            LOGGER.warning("Error getting thread pool stats: " + e.getMessage());
            stats.put("Default Executor", 0);
        }
        return stats;
    }

    /**
     * The application whose context root contains the route (longest match wins),
     * or null for routes of the console itself and of unknown applications.
     */
    private String applicationForRoute(String route, Map<String, ApplicationInfo> applications) {
        if (route == null || route.isBlank()) {
            return null;
        }
        String consolePath = runtime.get().getContextPath();
        if (route.equals(consolePath) || route.startsWith(consolePath + "/")) {
            return null;
        }

        String match = null;
        int matchLength = -1;
        for (ApplicationInfo app : applications.values()) {
            String contextRoot = app.getContextRoot();
            if (contextRoot == null || contextRoot.isBlank()) {
                contextRoot = "/" + app.getName();
            }
            boolean matches = "/".equals(contextRoot)
                || route.equals(contextRoot) || route.startsWith(contextRoot + "/");
            if (matches && contextRoot.length() > matchLength) {
                match = app.getName();
                matchLength = contextRoot.length();
            }
        }
        return match;
    }
}
