package com.ibm.liberty.admin.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsoleRuntimeTest {

    @Test
    void usesLibertyLocationProperties() {
        Map<String, String> props = Map.of(
            "wlp.server.name", "prod1",
            "wlp.install.dir", "/opt/wlp/",
            "wlp.user.dir", "/opt/wlp/usr/",
            "server.config.dir", "/opt/wlp/usr/servers/prod1/",
            "server.output.dir", "/var/out/prod1/");
        ConsoleRuntime runtime = ConsoleRuntime.create(props::get, k -> null, Map.of());

        assertEquals("prod1", runtime.getServerName());
        assertEquals(Paths.get("/opt/wlp/usr/servers/prod1/server.xml"), runtime.getServerXml());
        assertEquals(Paths.get("/opt/wlp/usr/servers/prod1/apps"), runtime.getAppsDir());
        assertEquals(Paths.get("/var/out/prod1/logs"), runtime.getLogDir());
        assertEquals("/console", runtime.getContextPath());
    }

    @Test
    void logDirectoryCanComeFromLoggingPropertyOrEnvironment() {
        Map<String, String> props = Map.of("server.config.dir", "/srv/s1");
        assertEquals(Paths.get("/logs/env"),
            ConsoleRuntime.create(props::get, Map.of("LOG_DIR", "/logs/env")::get, Map.of()).getLogDir());

        Map<String, String> withProperty = Map.of("server.config.dir", "/srv/s1",
            "com.ibm.ws.logging.log.directory", "/logs/prop");
        assertEquals(Paths.get("/logs/prop"),
            ConsoleRuntime.create(withProperty::get, Map.of("LOG_DIR", "/logs/env")::get, Map.of()).getLogDir());
    }

    @Test
    void appliesConsoleConfiguration() {
        ConsoleRuntime runtime = ConsoleRuntime.create(k -> null, k -> null,
            Map.of("contextPath", "admin/"));
        assertEquals("/admin", runtime.getContextPath());
    }

    @Test
    void normalizesContextPaths() {
        assertEquals("/console", ConsoleRuntime.normalizeContextPath(null));
        assertEquals("/console", ConsoleRuntime.normalizeContextPath("  "));
        assertEquals("/", ConsoleRuntime.normalizeContextPath("/"));
        assertEquals("/a/b", ConsoleRuntime.normalizeContextPath("/a/b//"));
    }
}
