package com.ibm.liberty.admin.service;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for reading and managing Liberty server logs
 */
public class LogService {

    private static final Logger LOGGER = Logger.getLogger(LogService.class.getName());

    /** Upper bound on the number of lines returned in one request. */
    static final int MAX_LINES = 100_000;

    private final Supplier<ConsoleRuntime> runtime;

    public LogService(Supplier<ConsoleRuntime> runtime) {
        this.runtime = runtime;
    }

    /**
     * Get the Liberty server logs directory
     */
    private Path getLogsDirectory() {
        return runtime.get().getLogDir();
    }

    /**
     * Read the messages.log file
     */
    public String readMessagesLog(int maxLines) {
        Path messagesLog = getLogsDirectory().resolve("messages.log");
        if (!Files.exists(messagesLog)) {
            return "Log file not found at: " + messagesLog;
        }
        return tail(messagesLog, maxLines);
    }

    /**
     * Read the console.log file
     */
    public String readConsoleLog(int maxLines) {
        Path consoleLog = getLogsDirectory().resolve("console.log");
        if (!Files.exists(consoleLog)) {
            return "Console log file not found at: " + consoleLog;
        }
        return tail(consoleLog, maxLines);
    }

    /**
     * List all available log files
     */
    public List<String> listLogFiles() {
        Path logsDir = getLogsDirectory();
        if (!Files.exists(logsDir)) {
            return Collections.singletonList("Logs directory not found: " + logsDir);
        }

        try (Stream<Path> files = Files.list(logsDir)) {
            return files
                .filter(Files::isRegularFile)
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(name -> name.endsWith(".log"))
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.severe("Error listing log files: " + e.getMessage());
            return Collections.singletonList("Error listing log files: " + e.getMessage());
        }
    }

    /**
     * Read a specific log file
     */
    public String readLogFile(String fileName, int maxLines) {
        if (fileName == null || fileName.isBlank()) {
            return "Log file not found: " + fileName;
        }
        Path logsDir = getLogsDirectory().toAbsolutePath().normalize();
        Path logFile = logsDir.resolve(fileName).normalize();

        // Only files directly inside the logs directory
        if (!logsDir.equals(logFile.getParent())) {
            return "Access denied: Invalid file path";
        }
        if (!Files.isRegularFile(logFile)) {
            return "Log file not found: " + fileName;
        }
        return tail(logFile, maxLines);
    }

    /**
     * Get log directory path
     */
    public String getLogDirectoryPath() {
        return getLogsDirectory().toString();
    }

    /** Last {@code maxLines} lines of a file, read as a stream so large logs are not loaded into memory. */
    private String tail(Path file, int maxLines) {
        int limit = Math.max(1, Math.min(maxLines, MAX_LINES));
        Deque<String> lines = new ArrayDeque<>(Math.min(limit, 1024));
        // Logs can contain bytes that are not valid UTF-8; replace them instead of failing
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == limit) {
                    lines.removeFirst();
                }
                lines.addLast(line);
            }
            return String.join("\n", lines);
        } catch (IOException e) {
            LOGGER.severe("Error reading log file " + file + ": " + e.getMessage());
            return "Error reading log file: " + e.getMessage();
        }
    }
}
