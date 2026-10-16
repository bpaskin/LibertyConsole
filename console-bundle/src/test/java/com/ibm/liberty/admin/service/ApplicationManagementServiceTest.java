package com.ibm.liberty.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import com.ibm.liberty.admin.model.ApplicationInfo;
import java.io.ByteArrayInputStream;
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

class ApplicationManagementServiceTest {

    /** Same shape as Liberty's com.ibm.websphere.application.ApplicationMBean. */
    public interface FakeApplicationMBean {
        String getState();

        void start();

        void stop();

        void restart();
    }

    public static class FakeApplication implements FakeApplicationMBean {
        String state = "STARTED";

        @Override
        public String getState() {
            return state;
        }

        @Override
        public void start() {
            state = "STARTED";
        }

        @Override
        public void stop() {
            state = "STOPPED";
        }

        @Override
        public void restart() {
            state = "STARTED";
        }
    }

    @TempDir
    Path serverDir;

    private MBeanServer mbeanServer;
    private ApplicationManagementService service;
    private final FakeApplication shop = new FakeApplication();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(serverDir.resolve("server.xml"), """
            <server>
                <webApplication id="shop" location="shop.war" contextRoot="/store"/>
                <webApplication id="notInstalled" location="gone.war"/>
                <!-- Logging -->
            </server>
            """);
        Files.createDirectories(serverDir.resolve("dropins"));
        Files.write(serverDir.resolve("dropins/hello.war"), new byte[] { 1 });

        mbeanServer = MBeanServerFactory.newMBeanServer();
        register("shop", shop);
        register("hello", new FakeApplication());

        ConsoleRuntime runtime = ConsoleRuntime.create(
            Map.of("server.config.dir", serverDir.toString())::get, k -> null, Map.of());
        ConfigurationService configuration = new ConfigurationService(() -> runtime);
        service = new ApplicationManagementService(configuration, new LibertyMBeans(mbeanServer), () -> runtime);
    }

    private void register(String name, FakeApplication app) throws Exception {
        mbeanServer.registerMBean(app,
            new ObjectName("WebSphere:service=com.ibm.websphere.application.ApplicationMBean,name=" + name));
    }

    private Map<String, ApplicationInfo> apps() {
        return service.getAllApplications().stream()
            .collect(Collectors.toMap(ApplicationInfo::getName, Function.identity()));
    }

    @Test
    void combinesServerXmlDropinsAndLibertyState() {
        Map<String, ApplicationInfo> apps = apps();
        assertEquals("STARTED", apps.get("shop").getState());
        assertEquals("/store", apps.get("shop").getContextRoot());
        assertEquals("STARTED", apps.get("hello").getState());
        assertEquals("hello.war", apps.get("hello").getFileName());
        assertEquals("/hello", apps.get("hello").getContextRoot());
        assertEquals(ApplicationManagementService.STATE_UNKNOWN, apps.get("notInstalled").getState());
    }

    @Test
    void startsAndStopsThroughTheApplicationMBean() {
        assertTrue(service.stopApplication("shop"));
        assertEquals("STOPPED", shop.state);
        assertEquals("STOPPED", service.getApplication("shop").getState());
        assertTrue(service.startApplication("shop"));
        assertEquals("STARTED", shop.state);
        assertTrue(service.restartApplication("shop"));
        assertFalse(service.startApplication("missing"));
    }

    @Test
    void uploadedApplicationsAreInstallingUntilLibertyHasThem() {
        ApplicationInfo deployed = service.deployApplication("demo", "/demo", null, "demo.war",
            new ByteArrayInputStream(new byte[] { 1, 2 }), 2);
        assertEquals(ApplicationManagementService.STATE_INSTALLING, deployed.getState());
        assertEquals(ApplicationManagementService.STATE_INSTALLING, service.getApplication("demo").getState());
        assertTrue(Files.exists(serverDir.resolve("apps/demo.war")));

        assertThrows(IllegalArgumentException.class,
            () -> service.deployApplication("demo2", "/store", null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> service.deployApplication("x", "/console", null, null, null, 0));

        assertTrue(service.undeployApplication("demo"));
        assertFalse(Files.exists(serverDir.resolve("apps/demo.war")));
        assertEquals(null, service.getApplication("demo"));
    }

    @Test
    void undeploysDropinsApplications() {
        assertTrue(service.undeployApplication("hello"));
        assertFalse(Files.exists(serverDir.resolve("dropins/hello.war")));
    }
}
