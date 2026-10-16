package com.ibm.liberty.admin.service;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads and writes the server configuration (server.xml and the other files in
 * the server configuration directory) and the applications defined in it.
 */
public class ConfigurationService {

    private static final Logger LOGGER = Logger.getLogger(ConfigurationService.class.getName());

    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** File types that are listed and editable in the configuration editor. */
    private static final List<String> CONFIG_FILE_SUFFIXES = List.of(
        ".xml", ".properties", ".cfg", ".conf", ".config", ".options", ".env", ".json");

    private static final Pattern APPLICATION_ELEMENT = Pattern.compile(
        "<(webApplication|enterpriseApplication|springBootApplication|application)\\b(.*?)(?:/>|>)",
        Pattern.DOTALL);

    private final Supplier<ConsoleRuntime> runtime;

    public ConfigurationService(Supplier<ConsoleRuntime> runtime) {
        this.runtime = runtime;
    }

    /** An application that is defined in server.xml. */
    public static final class ConfiguredApplication {
        private final String name;
        private final String contextRoot;
        private final String type;
        private final String fileName;

        ConfiguredApplication(String name, String contextRoot, String type, String fileName) {
            this.name = name;
            this.contextRoot = contextRoot;
            this.type = type;
            this.fileName = fileName;
        }

        public String getName() {
            return name;
        }

        public String getContextRoot() {
            return contextRoot;
        }

        public String getType() {
            return type;
        }

        /** File name from the location attribute, or null. */
        public String getFileName() {
            return fileName;
        }
    }

    private Path getServerXmlPath() {
        return runtime.get().getServerXml();
    }

    private Path getServerDirectoryPath() {
        return runtime.get().getServerConfigDir();
    }

    private Path getAppsDirectoryPath() {
        return runtime.get().getAppsDir().normalize();
    }

    /**
     * Read the server.xml content
     */
    public String readServerXml() {
        Path serverXml = getServerXmlPath();
        try {
            if (Files.exists(serverXml)) {
                return Files.readString(serverXml, StandardCharsets.UTF_8);
            }
            LOGGER.warning("server.xml not found at: " + serverXml);
            return "<!-- server.xml not found at: " + serverXml + " -->";
        } catch (IOException e) {
            LOGGER.severe("Error reading server.xml: " + e.getMessage());
            return "<!-- Error reading server.xml: " + e.getMessage() + " -->";
        }
    }

    /**
     * Write server.xml content (with backup)
     */
    public boolean writeServerXml(String content) {
        try {
            Path serverXml = getServerXmlPath();
            backup(serverXml);
            Files.writeString(serverXml, content, StandardCharsets.UTF_8);
            LOGGER.info("Updated server.xml successfully");
            return true;
        } catch (IOException e) {
            LOGGER.severe("Error writing server.xml: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks that the content is well-formed XML whose root element is {@code <server>}.
     * DTDs and external entities are rejected.
     */
    public boolean validateXml(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // don't print parse errors to stderr
            Document document = builder.parse(new InputSource(new StringReader(content)));
            return "server".equals(document.getDocumentElement().getLocalName())
                || "server".equals(document.getDocumentElement().getNodeName());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "XML validation failed", e);
            return false;
        }
    }

    /**
     * Get server.xml file path as string
     */
    public String getServerXmlFilePath() {
        return getServerXmlPath().toString();
    }

    public Path saveUploadedApplication(String fileName, InputStream fileContent) {
        try {
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("Uploaded file name is invalid");
            }

            Path appsDirectory = getAppsDirectoryPath();
            Files.createDirectories(appsDirectory);

            Path destination = appsDirectory.resolve(fileName).normalize();
            if (!destination.startsWith(appsDirectory) || destination.equals(appsDirectory)) {
                throw new IllegalArgumentException("Invalid uploaded file path");
            }

            Files.copy(fileContent, destination, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Saved uploaded application to: " + destination);
            return destination;
        } catch (IOException e) {
            LOGGER.severe("Error saving uploaded application: " + e.getMessage());
            throw new IllegalArgumentException("Failed to save uploaded application file");
        }
    }

    public void addApplicationToServerXml(String appName, String fileName, String contextRoot, String type) {
        String serverXml = readServerXml();
        String applicationElement = buildApplicationElement(appName, fileName, contextRoot, type);

        if (serverXml.contains("id=\"" + appName + "\"")) {
            throw new IllegalArgumentException("Application already exists in server.xml");
        }

        int insertPosition = serverXml.indexOf("<!-- Logging -->");
        if (insertPosition < 0) {
            insertPosition = serverXml.lastIndexOf("</server>");
        }
        if (insertPosition < 0) {
            throw new IllegalArgumentException("Unable to update server.xml");
        }

        String updatedServerXml = serverXml.substring(0, insertPosition)
            + applicationElement
            + serverXml.substring(insertPosition);

        if (!writeServerXml(updatedServerXml)) {
            throw new IllegalArgumentException("Failed to update server.xml");
        }
    }

    public boolean removeApplicationFromServerXml(String appName) {
        String serverXml = readServerXml();
        String updatedServerXml = serverXml
            .replaceAll("(?s)\\n?\\s*<(webApplication|enterpriseApplication|springBootApplication)\\b[^>]*\\bid=\""
                + Pattern.quote(appName) + "\"[^>]*/>\\s*", "\n\n")
            .replaceAll("\\n{3,}", "\n\n");

        if (serverXml.equals(updatedServerXml)) {
            return false;
        }

        return writeServerXml(updatedServerXml);
    }

    public boolean deleteApplicationFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }

        try {
            Path appsDirectory = getAppsDirectoryPath();
            Path applicationFile = appsDirectory.resolve(fileName).normalize();
            if (!applicationFile.startsWith(appsDirectory) || applicationFile.equals(appsDirectory)) {
                throw new IllegalArgumentException("Invalid application file path");
            }

            boolean deleted = Files.deleteIfExists(applicationFile);
            if (deleted) {
                LOGGER.info("Deleted application file: " + applicationFile);
            }
            return deleted;
        } catch (IOException e) {
            LOGGER.severe("Error deleting application file: " + e.getMessage());
            throw new IllegalArgumentException("Failed to delete application file");
        }
    }

    /** Applications defined directly in server.xml. */
    public List<ConfiguredApplication> getConfiguredApplications() {
        List<ConfiguredApplication> applications = new ArrayList<>();
        String serverXml = stripComments(readServerXml());

        Matcher matcher = APPLICATION_ELEMENT.matcher(serverXml);
        while (matcher.find()) {
            String elementName = matcher.group(1);
            String attributes = matcher.group(2);

            String name = extractAttribute(attributes, "name");
            String id = extractAttribute(attributes, "id");
            String contextRoot = extractAttribute(attributes, "contextRoot");
            String location = extractAttribute(attributes, "location");

            String appName = firstNonBlank(name, id);
            if (appName == null && location != null) {
                appName = stripExtension(extractFileName(location));
            }
            if (appName == null) {
                continue;
            }

            String type = switch (elementName) {
                case "enterpriseApplication" -> "EAR";
                case "springBootApplication" -> "JAR";
                case "application" -> typeFromLocation(location);
                default -> "WAR";
            };

            applications.add(new ConfiguredApplication(
                appName,
                contextRoot != null ? contextRoot : "/" + appName,
                type,
                location != null ? extractFileName(location) : null));
        }

        return applications;
    }

    private static String stripComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    private static String typeFromLocation(String location) {
        if (location == null) {
            return "WAR";
        }
        String lower = location.toLowerCase();
        if (lower.endsWith(".ear")) {
            return "EAR";
        }
        if (lower.endsWith(".jar")) {
            return "JAR";
        }
        return "WAR";
    }

    private String extractAttribute(String attributes, String attributeName) {
        Matcher matcher = Pattern.compile("\\b" + attributeName + "\\s*=\\s*\"([^\"]*)\"").matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String extractFileName(String location) {
        int separatorIndex = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
        return separatorIndex >= 0 ? location.substring(separatorIndex + 1) : location;
    }

    private static String stripExtension(String fileName) {
        int extensionIndex = fileName.lastIndexOf('.');
        return extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
    }

    private String buildApplicationElement(String appName, String fileName, String contextRoot, String type) {
        String elementName = "webApplication";
        if ("EAR".equalsIgnoreCase(type)) {
            elementName = "enterpriseApplication";
        } else if ("JAR".equalsIgnoreCase(type)) {
            elementName = "springBootApplication";
        }

        return String.format(
            "    <%s id=\"%s\" location=\"%s\" name=\"%s\" contextRoot=\"%s\" />%n%n",
            elementName, xmlAttribute(appName), xmlAttribute(fileName), xmlAttribute(appName),
            xmlAttribute(contextRoot));
    }

    private static String xmlAttribute(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * List all configuration files in the server directory
     */
    public List<String> listConfigurationFiles() {
        Path serverDir = getServerDirectoryPath();
        if (!Files.exists(serverDir)) {
            LOGGER.warning("Server directory not found: " + serverDir);
            return List.of();
        }

        // Skip the directories that hold generated or binary content
        Path workarea = serverDir.resolve("workarea");
        Path logs = runtime.get().getLogDir();
        Path apps = serverDir.resolve("apps");
        Path dropins = serverDir.resolve("dropins");

        try (Stream<Path> paths = Files.walk(serverDir)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> !path.startsWith(workarea) && !path.startsWith(logs)
                    && !path.startsWith(apps) && !path.startsWith(dropins))
                .filter(path -> {
                    String fileName = path.getFileName().toString();
                    return CONFIG_FILE_SUFFIXES.stream().anyMatch(fileName::endsWith);
                })
                .map(path -> serverDir.relativize(path).toString().replace('\\', '/'))
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.severe("Error listing configuration files: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Read any file from the server directory
     */
    public String readConfigurationFile(String relativePath) {
        Path filePath = resolveInServerDirectory(relativePath);

        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + relativePath);
        }
        if (!Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Not a regular file: " + relativePath);
        }

        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.severe("Error reading file " + relativePath + ": " + e.getMessage());
            throw new IllegalArgumentException("Failed to read file: " + e.getMessage());
        }
    }

    /**
     * Write content to any file in the server directory (with backup)
     */
    public boolean writeConfigurationFile(String relativePath, String content) {
        Path filePath = resolveInServerDirectory(relativePath);
        String fileName = filePath.getFileName().toString();
        if (CONFIG_FILE_SUFFIXES.stream().noneMatch(fileName::endsWith)) {
            throw new IllegalArgumentException("Only configuration files can be edited: " + relativePath);
        }
        if ("server.xml".equals(fileName) && filePath.equals(getServerXmlPath()) && !validateXml(content)) {
            throw new IllegalArgumentException("Invalid XML content");
        }

        try {
            backup(filePath);
            Files.writeString(filePath, content == null ? "" : content, StandardCharsets.UTF_8);
            LOGGER.info("Updated file successfully: " + relativePath);
            return true;
        } catch (IOException e) {
            LOGGER.severe("Error writing file " + relativePath + ": " + e.getMessage());
            return false;
        }
    }

    /** Resolves a path relative to the server configuration directory, refusing anything outside it. */
    private Path resolveInServerDirectory(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path cannot be empty");
        }

        Path serverDir = getServerDirectoryPath().toAbsolutePath().normalize();
        Path filePath = serverDir.resolve(relativePath).normalize();

        if (!filePath.startsWith(serverDir) || filePath.equals(serverDir)) {
            throw new IllegalArgumentException("Access denied: file is outside server directory");
        }
        // Don't follow symbolic links out of the server directory
        try {
            if (Files.exists(filePath) && !filePath.toRealPath().startsWith(serverDir.toRealPath())) {
                throw new IllegalArgumentException("Access denied: file is outside server directory");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve file: " + e.getMessage());
        }
        return filePath;
    }

    private static void backup(Path file) throws IOException {
        if (Files.exists(file)) {
            String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP);
            Path backup = file.resolveSibling(file.getFileName() + ".backup." + timestamp);
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Created backup: " + backup);
        }
    }
}
