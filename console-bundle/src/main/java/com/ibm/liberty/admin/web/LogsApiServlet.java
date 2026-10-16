package com.ibm.liberty.admin.web;

import com.ibm.liberty.admin.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/logs - read the server logs.
 *
 * <pre>
 * GET /api/logs/messages?lines=500     end of messages.log, text/plain
 * GET /api/logs/console?lines=500      end of console.log, text/plain
 * GET /api/logs/list                   log files and the log directory
 * GET /api/logs/file/{name}?lines=500  end of any *.log file in the log directory, text/plain
 * GET /api/logs/info                   log directory and available logs
 * </pre>
 */
public class LogsApiServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    private static final int DEFAULT_LINES = 500;

    private LogService logs() {
        return services().logs();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<String> path = segments(request);
        String resource = path.isEmpty() ? "" : path.get(0);

        switch (resource) {
            case "messages" -> {
                if (path.size() != 1) {
                    notFound();
                }
                sendText(response, 200, logs().readMessagesLog(intParameter(request, "lines", DEFAULT_LINES)));
            }
            case "console" -> {
                if (path.size() != 1) {
                    notFound();
                }
                sendText(response, 200, logs().readConsoleLog(intParameter(request, "lines", DEFAULT_LINES)));
            }
            case "list" -> {
                if (path.size() != 1) {
                    notFound();
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("logFiles", logs().listLogFiles());
                result.put("logDirectory", logs().getLogDirectoryPath());
                sendJson(response, 200, result);
            }
            case "file" -> {
                if (path.size() != 2) {
                    notFound();
                }
                sendText(response, 200, logs().readLogFile(path.get(1), intParameter(request, "lines", DEFAULT_LINES)));
            }
            case "info" -> {
                if (path.size() != 1) {
                    notFound();
                }
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("logDirectory", logs().getLogDirectoryPath());
                info.put("availableLogs", logs().listLogFiles());
                info.put("defaultMaxLines", DEFAULT_LINES);
                sendJson(response, 200, info);
            }
            default -> notFound();
        }
    }
}
