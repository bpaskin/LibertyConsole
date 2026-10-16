package com.ibm.liberty.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogServiceTest {

    @TempDir
    Path logDir;

    private LogService service() {
        ConsoleRuntime runtime = ConsoleRuntime.create(
            Map.of("server.config.dir", logDir.toString(), "com.ibm.ws.logging.log.directory", logDir.toString())::get,
            k -> null, Map.of());
        return new LogService(() -> runtime);
    }

    @Test
    void returnsTheLastLines() throws Exception {
        Files.write(logDir.resolve("messages.log"), List.of("one", "two", "three", "four"));
        assertEquals("three\nfour", service().readMessagesLog(2));
        assertEquals("one\ntwo\nthree\nfour", service().readMessagesLog(100));
    }

    @Test
    void onlyReadsLogFilesInTheLogDirectory() throws Exception {
        Files.writeString(logDir.resolve("trace.log"), "trace");
        Files.createDirectories(logDir.resolve("ffdc"));
        Files.writeString(logDir.resolve("ffdc/x.txt"), "ffdc");
        LogService service = service();
        assertEquals("trace", service.readLogFile("trace.log", 10));
        assertEquals("Access denied: Invalid file path", service.readLogFile("../trace.log", 10));
        assertEquals("Access denied: Invalid file path", service.readLogFile("ffdc/x.txt", 10));
        assertEquals(List.of("trace.log"), service.listLogFiles());
    }
}
