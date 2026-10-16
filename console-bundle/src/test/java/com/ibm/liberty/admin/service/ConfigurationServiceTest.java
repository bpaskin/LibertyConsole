package com.ibm.liberty.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import com.ibm.liberty.admin.service.ConfigurationService.ConfiguredApplication;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationServiceTest {

    private static final String SERVER_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <server>
            <featureManager>
                <feature>usr:console-1.0</feature>
            </featureManager>
            <!-- <webApplication id="commented" location="old.war"/> -->
            <webApplication id="shop" location="shop.war" contextRoot="/store"/>
            <enterpriseApplication location="${shared.app.dir}/billing.ear" name="billing">
            </enterpriseApplication>
            <application location="legacy.war"/>
            <!-- Logging -->
        </server>
        """;

    @TempDir
    Path serverDir;

    private ConfigurationService service;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(serverDir.resolve("server.xml"), SERVER_XML);
        ConsoleRuntime runtime = ConsoleRuntime.create(
            Map.of("server.config.dir", serverDir.toString())::get, k -> null, Map.of());
        service = new ConfigurationService(() -> runtime);
    }

    @Test
    void findsApplicationsDefinedInServerXml() {
        List<ConfiguredApplication> apps = service.getConfiguredApplications();
        assertEquals(List.of("shop", "billing", "legacy"), apps.stream().map(ConfiguredApplication::getName).toList());
        assertEquals("/store", apps.get(0).getContextRoot());
        assertEquals("EAR", apps.get(1).getType());
        assertEquals("billing.ear", apps.get(1).getFileName());
        assertEquals("/legacy", apps.get(2).getContextRoot());
    }

    @Test
    void validatesServerXml() {
        assertTrue(service.validateXml(SERVER_XML));
        assertTrue(service.validateXml("<server/>"));
        assertFalse(service.validateXml("<server>"));
        assertFalse(service.validateXml("<client/>"));
        assertFalse(service.validateXml("<?xml version=\"1.0\"?><!DOCTYPE server [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><server>&x;</server>"));
    }

    @Test
    void refusesPathsOutsideTheServerDirectory() {
        assertThrows(IllegalArgumentException.class, () -> service.readConfigurationFile("../outside.xml"));
        assertThrows(IllegalArgumentException.class, () -> service.readConfigurationFile("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> service.writeConfigurationFile("../x.xml", "<a/>"));
        assertThrows(IllegalArgumentException.class, () -> service.writeConfigurationFile("apps/evil.sh", "x"));
    }

    @Test
    void writesConfigurationFilesWithBackup() throws Exception {
        Files.writeString(serverDir.resolve("bootstrap.properties"), "a=1");
        assertTrue(service.writeConfigurationFile("bootstrap.properties", "a=2"));
        assertEquals("a=2", service.readConfigurationFile("bootstrap.properties"));
        try (var files = Files.list(serverDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("bootstrap.properties.backup.")));
        }
        assertThrows(IllegalArgumentException.class, () -> service.writeConfigurationFile("server.xml", "<server>"));
    }

    @Test
    void addsAndRemovesUploadedApplications() throws Exception {
        Path saved = service.saveUploadedApplication("demo.war", new ByteArrayInputStream(new byte[] { 1, 2, 3 }));
        assertEquals(serverDir.resolve("apps").resolve("demo.war"), saved);
        service.addApplicationToServerXml("demo", "demo.war", "/demo", "WAR");
        assertTrue(service.readServerXml().contains("<webApplication id=\"demo\" location=\"demo.war\" name=\"demo\" contextRoot=\"/demo\" />"));

        assertTrue(service.removeApplicationFromServerXml("demo"));
        assertFalse(service.readServerXml().contains("id=\"demo\""));
        assertTrue(service.deleteApplicationFile("demo.war"));
        assertThrows(IllegalArgumentException.class,
            () -> service.saveUploadedApplication("../escape.war", new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void listsConfigurationFilesButNotGeneratedOnes() throws Exception {
        Files.createDirectories(serverDir.resolve("workarea"));
        Files.writeString(serverDir.resolve("workarea/state.xml"), "x");
        Files.createDirectories(serverDir.resolve("configDropins/overrides"));
        Files.writeString(serverDir.resolve("configDropins/overrides/extra.xml"), "<server/>");
        assertEquals(List.of("configDropins/overrides/extra.xml", "server.xml"), service.listConfigurationFiles());
    }
}
