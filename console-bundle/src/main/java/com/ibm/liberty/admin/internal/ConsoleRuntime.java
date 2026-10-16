package com.ibm.liberty.admin.internal;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/**
 * Immutable snapshot of the server locations and console settings, shared by
 * the OSGi side (ConsoleConfiguration) and the web side (servlets and services).
 * <p>
 * Liberty publishes its locations as framework properties (wlp.server.name,
 * server.config.dir, ...). ConsoleConfiguration reads them from its
 * BundleContext and publishes a new snapshot whenever the {@code <usr_console>}
 * configuration changes.
 */
public final class ConsoleRuntime {

    public static final String DEFAULT_CONTEXT_PATH = "/console";

    private static volatile ConsoleRuntime current;

    private final String serverName;
    private final Path installDir;
    private final Path userDir;
    private final Path serverConfigDir;
    private final Path serverOutputDir;
    private final Path logDir;
    private final String contextPath;

    private ConsoleRuntime(String serverName, Path installDir, Path userDir, Path serverConfigDir,
            Path serverOutputDir, Path logDir, String contextPath) {
        this.serverName = serverName;
        this.installDir = installDir;
        this.userDir = userDir;
        this.serverConfigDir = serverConfigDir;
        this.serverOutputDir = serverOutputDir;
        this.logDir = logDir;
        this.contextPath = contextPath;
    }

    /**
     * Builds a snapshot.
     *
     * @param properties lookup for Liberty framework/system properties
     * @param env        lookup for environment variables
     * @param config     the {@code <usr_console>} configuration (may be empty)
     */
    public static ConsoleRuntime create(Function<String, String> properties, Function<String, String> env,
            Map<String, ?> config) {
        Map<String, ?> cfg = config != null ? config : Collections.emptyMap();

        String serverName = firstNonBlank(properties.apply("wlp.server.name"), "defaultServer");
        Path installDir = toPath(properties.apply("wlp.install.dir"));
        Path userDir = toPath(properties.apply("wlp.user.dir"));
        if (userDir == null && installDir != null) {
            userDir = installDir.resolve("usr");
        }

        Path serverConfigDir = toPath(properties.apply("server.config.dir"));
        if (serverConfigDir == null) {
            serverConfigDir = userDir != null
                ? userDir.resolve("servers").resolve(serverName)
                : Paths.get("").toAbsolutePath();
        }

        Path serverOutputDir = toPath(properties.apply("server.output.dir"));
        if (serverOutputDir == null) {
            serverOutputDir = serverConfigDir;
        }

        Path logDir = toPath(properties.apply("com.ibm.ws.logging.log.directory"));
        if (logDir == null) {
            logDir = toPath(env.apply("LOG_DIR"));
        }
        if (logDir == null) {
            logDir = serverOutputDir.resolve("logs");
        }

        String contextPath = normalizeContextPath(stringValue(cfg.get("contextPath")));

        return new ConsoleRuntime(serverName, installDir, userDir, serverConfigDir.normalize(),
            serverOutputDir.normalize(), logDir.normalize(), contextPath);
    }

    /** The current snapshot, or one built from system properties if the OSGi component has not run. */
    public static ConsoleRuntime get() {
        ConsoleRuntime runtime = current;
        if (runtime == null) {
            runtime = create(System::getProperty, System::getenv, Collections.emptyMap());
        }
        return runtime;
    }

    static void set(ConsoleRuntime runtime) {
        current = runtime;
    }

    public String getServerName() {
        return serverName;
    }

    /** Liberty install directory (wlp), or null if unknown. */
    public Path getInstallDir() {
        return installDir;
    }

    /** Liberty user directory (wlp/usr), or null if unknown. */
    public Path getUserDir() {
        return userDir;
    }

    /** Directory that contains server.xml. */
    public Path getServerConfigDir() {
        return serverConfigDir;
    }

    /** Directory that the server writes output to (logs, workarea). */
    public Path getServerOutputDir() {
        return serverOutputDir;
    }

    /** Directory that holds messages.log, console.log, trace.log, ... */
    public Path getLogDir() {
        return logDir;
    }

    public Path getServerXml() {
        return serverConfigDir.resolve("server.xml");
    }

    /** Directory used for uploaded applications (${server.config.dir}/apps). */
    public Path getAppsDir() {
        return serverConfigDir.resolve("apps");
    }

    /** Context path the console is configured with, e.g. "/console". */
    public String getContextPath() {
        return contextPath;
    }

    static String normalizeContextPath(String value) {
        String path = value == null ? "" : value.trim();
        if (path.isEmpty()) {
            return DEFAULT_CONTEXT_PATH;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static Path toPath(String value) {
        return value == null || value.isBlank() ? null : Paths.get(value.trim());
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
