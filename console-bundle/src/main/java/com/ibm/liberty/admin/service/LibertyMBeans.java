package com.ibm.liberty.admin.service;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * Access to Liberty's public management MBeans through the platform MBean server:
 * <ul>
 * <li>{@code WebSphere:feature=kernel,name=ServerInfo} (ServerInfoMBean): server name, version, directories</li>
 * <li>{@code WebSphere:service=com.ibm.websphere.application.ApplicationMBean,name=<app>}: state,
 * start, stop, restart for each installed application</li>
 * <li>{@code WebSphere:type=HttpServerStats,*} and {@code WebSphere:type=ServletStats,*} (monitor-1.0):
 * request counts and times, read in-process so the console needs no access to /metrics</li>
 * </ul>
 * Each method returns null/empty rather than throwing when an MBean is not there,
 * so callers can fall back to other information.
 */
public class LibertyMBeans {

    private static final Logger LOGGER = Logger.getLogger(LibertyMBeans.class.getName());

    static final String SERVER_INFO = "WebSphere:feature=kernel,name=ServerInfo";
    static final String APPLICATION_PATTERN = "WebSphere:service=com.ibm.websphere.application.ApplicationMBean,*";
    static final String HTTP_SERVER_STATS_PATTERN = "WebSphere:type=HttpServerStats,*";
    static final String SERVLET_STATS_PATTERN = "WebSphere:type=ServletStats,*";

    private final MBeanServer server;

    public LibertyMBeans() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    LibertyMBeans(MBeanServer server) {
        this.server = server;
    }

    /** A ServerInfoMBean attribute, e.g. "Name" or "LibertyVersion". */
    public String serverInfo(String attribute) {
        try {
            Object value = server.getAttribute(new ObjectName(SERVER_INFO), attribute);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "ServerInfoMBean attribute " + attribute + " is not available", e);
            return null;
        }
    }

    /** Installed applications and their state (STARTING, STARTED, STOPPING, STOPPED, INSTALLED). */
    public Map<String, String> applicationStates() {
        Map<String, String> states = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectName> app : applicationMBeans().entrySet()) {
            states.put(app.getKey(), state(app.getValue()));
        }
        return states;
    }

    /** State of one application, or null when Liberty has no MBean for it. */
    public String applicationState(String appName) {
        ObjectName name = applicationMBeans().get(appName);
        return name != null ? state(name) : null;
    }

    public boolean hasApplication(String appName) {
        return applicationMBeans().containsKey(appName);
    }

    /**
     * Invokes start, stop or restart on an application's MBean.
     *
     * @return false if Liberty has no MBean for the application
     * @throws IllegalStateException if the operation fails
     */
    public boolean invoke(String appName, String operation) {
        ObjectName name = applicationMBeans().get(appName);
        if (name == null) {
            return false;
        }
        try {
            server.invoke(name, operation, new Object[0], new String[0]);
            return true;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Failed to " + operation + " application " + appName + ": "
                + cause.getMessage(), e);
        }
    }

    /** Requests to one HTTP route with one method and status (monitor-1.0 HttpServerStats MXBean). */
    public static final class HttpRouteStats {
        public final String route;
        public final int status;
        public final long count;
        /** Total time spent on these requests, in nanoseconds. */
        public final double durationNanos;
        public final String errorType;

        HttpRouteStats(String route, int status, long count, double durationNanos, String errorType) {
            this.route = route;
            this.status = status;
            this.count = count;
            this.durationNanos = durationNanos;
            this.errorType = errorType;
        }
    }

    /** Requests to one servlet (monitor-1.0 ServletStats MXBean). */
    public static final class ServletRequestStats {
        public final String appName;
        public final long count;
        /** Mean response time, in nanoseconds. */
        public final double meanNanos;

        ServletRequestStats(String appName, long count, double meanNanos) {
            this.appName = appName;
            this.count = count;
            this.meanNanos = meanNanos;
        }
    }

    /**
     * HTTP request statistics per route, from {@code WebSphere:type=HttpServerStats,*}.
     * These MXBeans exist when monitor-1.0 is enabled (mpMetrics enables it) on Liberty
     * versions that collect HTTP metrics; the list is empty otherwise.
     */
    public List<HttpRouteStats> httpRouteStats() {
        List<HttpRouteStats> result = new ArrayList<>();
        for (ObjectName name : query(HTTP_SERVER_STATS_PATTERN)) {
            Map<String, Object> values = attributes(name, "HttpRoute", "ResponseStatus", "Count", "Duration", "ErrorType");
            Object route = values.get("HttpRoute");
            if (route == null) {
                continue;
            }
            result.add(new HttpRouteStats(route.toString(), intValue(values.get("ResponseStatus")),
                longValue(values.get("Count")), doubleValue(values.get("Duration")),
                values.get("ErrorType") != null ? values.get("ErrorType").toString() : ""));
        }
        return result;
    }

    /**
     * Servlet request statistics, from {@code WebSphere:type=ServletStats,*} (monitor-1.0).
     * Used when the server has no HttpServerStats MXBeans.
     */
    public List<ServletRequestStats> servletStats() {
        List<ServletRequestStats> result = new ArrayList<>();
        for (ObjectName name : query(SERVLET_STATS_PATTERN)) {
            Map<String, Object> values = attributes(name, "AppName", "RequestCount", "ResponseTime");
            Object appName = values.get("AppName");
            if (appName == null) {
                continue;
            }
            result.add(new ServletRequestStats(appName.toString(), longValue(values.get("RequestCount")),
                doubleValue(values.get("ResponseTime"))));
        }
        return result;
    }

    private Set<ObjectName> query(String pattern) {
        try {
            return server.queryNames(new ObjectName(pattern), null);
        } catch (MalformedObjectNameException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Unable to query " + pattern, e);
            return Set.of();
        }
    }

    private Map<String, Object> attributes(ObjectName name, String... attributes) {
        Map<String, Object> values = new HashMap<>();
        try {
            for (Attribute attribute : server.getAttributes(name, attributes).asList()) {
                values.put(attribute.getName(), attribute.getValue());
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Unable to read the attributes of " + name, e);
        }
        return values;
    }

    private static long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? d : 0d;
        }
        return 0d;
    }

    private Map<String, ObjectName> applicationMBeans() {
        Map<String, ObjectName> result = new LinkedHashMap<>();
        try {
            Set<ObjectName> names = server.queryNames(new ObjectName(APPLICATION_PATTERN), null);
            for (ObjectName name : names) {
                String appName = name.getKeyProperty("name");
                if (appName != null) {
                    result.put(unquote(appName), name);
                }
            }
        } catch (MalformedObjectNameException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Unable to query application MBeans", e);
        }
        return result;
    }

    private String state(ObjectName name) {
        try {
            Object state = server.getAttribute(name, "State");
            return state != null ? state.toString() : null;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Unable to read the state of " + name, e);
            return null;
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            try {
                return ObjectName.unquote(value);
            } catch (IllegalArgumentException e) {
                return value;
            }
        }
        return value;
    }
}
