package com.ibm.liberty.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import com.ibm.liberty.admin.model.ApplicationMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerManagementServiceTest {

    /** Same attributes as Liberty's HttpServerStats MXBean (monitor-1.0). */
    public interface FakeHttpStatsMBean {
        String getHttpRoute();

        int getResponseStatus();

        long getCount();

        double getDuration();

        String getErrorType();
    }

    public static class FakeHttpStats implements FakeHttpStatsMBean {
        final String route;
        final int status;
        final long count;
        final double duration;

        FakeHttpStats(String route, int status, long count, double duration) {
            this.route = route;
            this.status = status;
            this.count = count;
            this.duration = duration;
        }

        @Override
        public String getHttpRoute() {
            return route;
        }

        @Override
        public int getResponseStatus() {
            return status;
        }

        @Override
        public long getCount() {
            return count;
        }

        @Override
        public double getDuration() {
            return duration;
        }

        @Override
        public String getErrorType() {
            return status >= 500 ? String.valueOf(status) : "";
        }
    }

    /** Same attributes as Liberty's ServletStats MXBean (monitor-1.0). */
    public interface FakeServletStatsMBean {
        String getAppName();

        long getRequestCount();

        double getResponseTime();
    }

    public static class FakeServletStats implements FakeServletStatsMBean {
        final String app;
        final long count;
        final double mean;

        FakeServletStats(String app, long count, double mean) {
            this.app = app;
            this.count = count;
            this.mean = mean;
        }

        @Override
        public String getAppName() {
            return app;
        }

        @Override
        public long getRequestCount() {
            return count;
        }

        @Override
        public double getResponseTime() {
            return mean;
        }
    }

    @TempDir
    Path serverDir;

    private MBeanServer mbeanServer;
    private ServerManagementService service;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(serverDir.resolve("server.xml"), """
            <server>
                <webApplication id="shop" location="shop.war" contextRoot="/store"/>
                <webApplication id="hello" location="hello.war"/>
            </server>
            """);
        mbeanServer = MBeanServerFactory.newMBeanServer();
        ConsoleRuntime runtime = ConsoleRuntime.create(
            Map.of("server.config.dir", serverDir.toString())::get, k -> null, Map.of());
        LibertyMBeans mbeans = new LibertyMBeans(mbeanServer);
        ApplicationManagementService applications =
            new ApplicationManagementService(new ConfigurationService(() -> runtime), mbeans, () -> runtime);
        service = new ServerManagementService(applications, mbeans, () -> runtime);
    }

    private void http(String route, int status, long count, double durationNanos) throws Exception {
        mbeanServer.registerMBean(new FakeHttpStats(route, status, count, durationNanos), new ObjectName(
            "WebSphere:type=HttpServerStats,name=" + ObjectName.quote("method:GET;status:" + status + ";httpRoute:" + route)));
    }

    private void servlet(String app, String servlet, long count, double meanNanos) throws Exception {
        mbeanServer.registerMBean(new FakeServletStats(app, count, meanNanos),
            new ObjectName("WebSphere:type=ServletStats,name=" + app + "." + servlet));
    }

    private Map<String, ApplicationMetrics> metrics() {
        return service.getApplicationMetrics().stream()
            .collect(Collectors.toMap(ApplicationMetrics::getApplicationName, Function.identity()));
    }

    @Test
    void addsUpHttpRouteStatisticsPerApplication() throws Exception {
        http("/store/", 200, 10, 50_000_000d);
        http("/store/api/orders", 500, 2, 10_000_000d);
        http("/hello/", 200, 4, 8_000_000d);
        http("/console/api/server/info", 200, 5, 5_000_000d); // the console itself
        http("/unknown/", 200, 7, 7_000_000d);

        Map<String, ApplicationMetrics> metrics = metrics();
        assertEquals(2, metrics.size());
        assertEquals(12, metrics.get("shop").getRequestCount());
        assertEquals(5.0, metrics.get("shop").getAverageResponseTime(), 1e-9);
        assertEquals(2, metrics.get("shop").getErrorCount());
        assertEquals(4, metrics.get("hello").getRequestCount());
        assertEquals(2.0, metrics.get("hello").getAverageResponseTime(), 1e-9);
        assertEquals(0, metrics.get("hello").getErrorCount());
    }

    @Test
    void fallsBackToServletStatistics() throws Exception {
        servlet("shop", "Orders", 3, 2_000_000d);
        servlet("shop", "Cart", 1, 6_000_000d);
        servlet("com.ibm.liberty.admin.console", "ServerApi", 9, 1_000_000d);

        Map<String, ApplicationMetrics> metrics = metrics();
        assertEquals(4, metrics.get("shop").getRequestCount());
        assertEquals(3.0, metrics.get("shop").getAverageResponseTime(), 1e-9);
        assertEquals(0, metrics.get("hello").getRequestCount());
    }

    @Test
    void reportsZeroWithoutMonitoring() {
        Map<String, ApplicationMetrics> metrics = metrics();
        assertEquals(0, metrics.get("shop").getRequestCount());
        assertEquals(0.0, metrics.get("shop").getAverageResponseTime());
    }
}
