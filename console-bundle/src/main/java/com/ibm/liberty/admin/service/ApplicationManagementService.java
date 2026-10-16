package com.ibm.liberty.admin.service;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import com.ibm.liberty.admin.model.ApplicationInfo;
import com.ibm.liberty.admin.service.ConfigurationService.ConfiguredApplication;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Lists and manages the server's applications.
 * <p>
 * The list combines the applications defined in server.xml, those Liberty has
 * installed (including dropins), and those deployed through the console. State,
 * start, stop and restart go through Liberty's ApplicationMBean.
 */
public class ApplicationManagementService {

    private static final Logger LOGGER = Logger.getLogger(ApplicationManagementService.class.getName());

    /** State reported for an application Liberty has no MBean for (not installed yet, or removed). */
    static final String STATE_UNKNOWN = "UNKNOWN";

    /** State reported for an uploaded application until Liberty has installed it. */
    static final String STATE_INSTALLING = "INSTALLING";

    /** How long undeploy waits for Liberty to uninstall an application before deleting its file. */
    static final long UNINSTALL_TIMEOUT_MS = 15_000;

    /** Metadata (deploy time, size, ...) of applications deployed through the console. */
    private final Map<String, ApplicationInfo> applications = new ConcurrentHashMap<>();

    private final ConfigurationService configurationService;
    private final LibertyMBeans mbeans;
    private final Supplier<ConsoleRuntime> runtime;

    public ApplicationManagementService(ConfigurationService configurationService, LibertyMBeans mbeans,
            Supplier<ConsoleRuntime> runtime) {
        this.configurationService = configurationService;
        this.mbeans = mbeans;
        this.runtime = runtime;
    }

    /**
     * Get all deployed applications
     */
    public List<ApplicationInfo> getAllApplications() {
        Map<String, ApplicationInfo> merged = new LinkedHashMap<>();
        Map<String, String> states = mbeans.applicationStates();

        // 1. Applications defined in server.xml
        for (ConfiguredApplication configured : configurationService.getConfiguredApplications()) {
            ApplicationInfo app = new ApplicationInfo(configured.getName(), configured.getContextRoot(), null);
            app.setType(configured.getType());
            app.setFileName(configured.getFileName());
            app.setVersion("Detected");
            merged.put(app.getName(), app);
        }

        // 2. Applications Liberty has installed that are not in server.xml (dropins)
        for (String name : states.keySet()) {
            merged.computeIfAbsent(name, this::describeDropin);
        }

        // 3. Applications deployed through the console
        for (ApplicationInfo deployed : applications.values()) {
            ApplicationInfo existing = merged.get(deployed.getName());
            if (existing == null) {
                merged.put(deployed.getName(), copy(deployed));
            } else {
                existing.setDeployedTime(deployed.getDeployedTime());
                existing.setSize(deployed.getSize());
                existing.setVersion(deployed.getVersion());
                if (existing.getFileName() == null) {
                    existing.setFileName(deployed.getFileName());
                }
            }
        }

        for (ApplicationInfo app : merged.values()) {
            String state = states.get(app.getName());
            if (state != null) {
                app.setState(state);
            } else if (app.getState() == null) {
                ApplicationInfo deployed = applications.get(app.getName());
                if (deployed == null) {
                    app.setState(STATE_UNKNOWN);
                } else {
                    // Uploaded through the console: Liberty picks up the server.xml change shortly
                    app.setState(deployed.getState() != null ? deployed.getState() : STATE_INSTALLING);
                }
            }
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * Get application by name
     */
    public ApplicationInfo getApplication(String name) {
        if (name == null) {
            return null;
        }
        return getAllApplications().stream()
            .filter(app -> name.equals(app.getName()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Start an application
     */
    public boolean startApplication(String name) {
        if (mbeans.invoke(name, "start")) {
            LOGGER.info("Started application: " + name);
            return true;
        }
        // Not installed in Liberty: only metadata the console knows about
        ApplicationInfo app = applications.get(name);
        if (app != null) {
            app.setState("STARTED");
            return true;
        }
        return false;
    }

    /**
     * Stop an application
     */
    public boolean stopApplication(String name) {
        if (mbeans.invoke(name, "stop")) {
            LOGGER.info("Stopped application: " + name);
            return true;
        }
        ApplicationInfo app = applications.get(name);
        if (app != null && "STARTED".equals(app.getState())) {
            app.setState("STOPPED");
            return true;
        }
        return false;
    }

    /**
     * Restart an application
     */
    public boolean restartApplication(String name) {
        if (mbeans.invoke(name, "restart")) {
            LOGGER.info("Restarted application: " + name);
            return true;
        }
        ApplicationInfo app = applications.get(name);
        if (app != null) {
            app.setState("STARTED");
            return true;
        }
        return false;
    }

    /**
     * Deploy a new application
     */
    public ApplicationInfo deployApplication(String name, String contextRoot, String type) {
        return deployApplication(name, contextRoot, type, null, null, 0L);
    }

    public ApplicationInfo deployApplication(String name, String contextRoot, String type,
            String fileName, InputStream fileContent, long fileSize) {
        String normalizedType = normalizeType(type, fileName);
        String normalizedName = normalizeName(name, fileName);
        String normalizedContextRoot = normalizeContextRoot(contextRoot, normalizedName);

        validateApplicationUniqueness(normalizedName, normalizedContextRoot);

        String storedFileName = fileName;
        if (fileContent != null) {
            Path storedPath = configurationService.saveUploadedApplication(fileName, fileContent);
            storedFileName = storedPath.getFileName().toString();
            configurationService.addApplicationToServerXml(
                normalizedName,
                storedFileName,
                normalizedContextRoot,
                normalizedType
            );
        }

        ApplicationInfo app = new ApplicationInfo(normalizedName, normalizedContextRoot,
            fileContent != null ? null : "STARTED");
        app.setType(normalizedType);
        app.setDeployedTime(LocalDateTime.now());
        app.setVersion("1.0.0");
        app.setActiveRequests(0);
        app.setTotalRequests(0);
        app.setFileName(storedFileName);
        app.setSize(Math.max(fileSize, 0L));
        applications.put(normalizedName, app);
        LOGGER.info("Deployed application: " + normalizedName + (storedFileName != null ? " from file " + storedFileName : ""));

        ApplicationInfo result = copy(app);
        if (result.getState() == null) {
            // Liberty installs the application asynchronously after server.xml changes
            String state = mbeans.applicationState(normalizedName);
            result.setState(state != null ? state : STATE_INSTALLING);
        }
        return result;
    }

    /**
     * Undeploy an application
     */
    public boolean undeployApplication(String name) {
        ApplicationInfo existing = getApplication(name);
        if (existing == null) {
            return false;
        }

        boolean serverXmlUpdated = configurationService.removeApplicationFromServerXml(name);
        if (!serverXmlUpdated) {
            if (applications.remove(name) != null) {
                // Known only to the console (deployed without a file)
                LOGGER.info("Removed application: " + name);
                return true;
            }
            if (deleteDropin(existing)) {
                LOGGER.info("Undeployed application: " + name + " (removed from dropins)");
                return true;
            }
            throw new IllegalArgumentException("Application entry not found in server.xml");
        }

        String fileName = existing.getFileName();
        if (fileName != null && !fileName.isBlank()) {
            // Let Liberty uninstall the application before its file disappears, otherwise it
            // reports CWWKZ0059E (application deleted while still configured)
            awaitUninstall(name);
            configurationService.deleteApplicationFile(fileName);
        }

        applications.remove(name);
        LOGGER.info("Undeployed application: " + name);
        return true;
    }

    /** Waits (up to UNINSTALL_TIMEOUT_MS) until Liberty has removed the application's MBean. */
    private void awaitUninstall(String name) {
        long deadline = System.currentTimeMillis() + UNINSTALL_TIMEOUT_MS;
        while (mbeans.hasApplication(name) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Deletes the dropins file of an application Liberty installed from the dropins directory. */
    private boolean deleteDropin(ApplicationInfo app) {
        String fileName = app.getFileName();
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        Path dropins = runtime.get().getServerConfigDir().resolve("dropins").toAbsolutePath().normalize();
        Path file = dropins.resolve(fileName).normalize();
        if (!dropins.equals(file.getParent()) || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            Files.delete(file);
            return true;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to delete " + fileName + " from dropins: " + e.getMessage());
        }
    }

    /**
     * Update application statistics
     */
    public void updateApplicationStats(String name, int activeRequests, long totalRequests) {
        ApplicationInfo app = applications.get(name);
        if (app != null) {
            app.setActiveRequests(activeRequests);
            app.setTotalRequests(totalRequests);
        }
    }

    /** Describes an application Liberty installed from the dropins directory. */
    private ApplicationInfo describeDropin(String name) {
        ApplicationInfo app = new ApplicationInfo(name, "/" + name, null);
        app.setType("WAR");
        app.setVersion("Detected");
        Path dropins = runtime.get().getServerConfigDir().resolve("dropins");
        if (Files.isDirectory(dropins)) {
            try (Stream<Path> files = Files.list(dropins)) {
                files.map(path -> path.getFileName().toString())
                    .filter(file -> file.equals(name) || stripExtension(file).equals(name))
                    .findFirst()
                    .ifPresent(file -> {
                        app.setFileName(file);
                        String lower = file.toLowerCase();
                        if (lower.endsWith(".ear")) {
                            app.setType("EAR");
                        } else if (lower.endsWith(".jar")) {
                            app.setType("JAR");
                        }
                    });
            } catch (IOException e) {
                LOGGER.fine("Unable to list dropins: " + e.getMessage());
            }
        }
        return app;
    }

    private static ApplicationInfo copy(ApplicationInfo source) {
        ApplicationInfo app = new ApplicationInfo(source.getName(), source.getContextRoot(), source.getState());
        app.setType(source.getType());
        app.setDeployedTime(source.getDeployedTime());
        app.setVersion(source.getVersion());
        app.setActiveRequests(source.getActiveRequests());
        app.setTotalRequests(source.getTotalRequests());
        app.setFileName(source.getFileName());
        app.setSize(source.getSize());
        return app;
    }

    private String normalizeName(String name, String fileName) {
        String normalizedName = name != null ? name.trim() : "";
        if (normalizedName.isEmpty() && fileName != null && !fileName.isBlank()) {
            normalizedName = stripExtension(fileName.trim());
        }
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Application name is required");
        }
        return normalizedName;
    }

    private String normalizeType(String type, String fileName) {
        String normalizedType = type != null ? type.trim().toUpperCase() : "";
        if (normalizedType.isEmpty() && fileName != null && fileName.contains(".")) {
            normalizedType = fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase();
        }
        return normalizedType.isEmpty() ? "WAR" : normalizedType;
    }

    private String normalizeContextRoot(String contextRoot, String normalizedName) {
        String requestedContextRoot = contextRoot != null ? contextRoot.trim() : "";
        if (requestedContextRoot.isEmpty()) {
            return "/" + normalizedName;
        }
        String normalized = requestedContextRoot.startsWith("/") ? requestedContextRoot : "/" + requestedContextRoot;
        if (normalized.equals(runtime.get().getContextPath())) {
            throw new IllegalArgumentException("Context root is already in use by the admin console");
        }
        return normalized;
    }

    private void validateApplicationUniqueness(String normalizedName, String normalizedContextRoot) {
        List<ApplicationInfo> all = getAllApplications();
        if (all.stream().anyMatch(app -> normalizedName.equals(app.getName()))) {
            throw new IllegalArgumentException("Application already exists");
        }
        if (all.stream().anyMatch(app -> normalizedContextRoot.equals(app.getContextRoot()))) {
            throw new IllegalArgumentException("Context root is already in use");
        }
    }

    private static String stripExtension(String fileName) {
        int extensionIndex = fileName.lastIndexOf('.');
        return extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
    }
}
