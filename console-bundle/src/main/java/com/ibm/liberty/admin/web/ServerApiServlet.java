package com.ibm.liberty.admin.web;

import com.ibm.liberty.admin.service.ServerManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * /api/server - server status, memory, threads, garbage collection and metrics.
 *
 * <pre>
 * GET  /api/server/info                  server information
 * GET  /api/server/memory                memory usage
 * POST /api/server/gc                    run garbage collection (Administrator)
 * GET  /api/server/threads               thread dump, text/plain (Administrator)
 * GET  /api/server/health                health check
 * GET  /api/server/metrics/applications  per-application metrics
 * GET  /api/server/threadpools           thread pool statistics
 * </pre>
 */
public class ServerApiServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    private ServerManagementService server() {
        return services().server();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<String> path = segments(request);
        String resource = path.isEmpty() ? "" : path.get(0);

        switch (resource) {
            case "info" -> {
                if (path.size() != 1) {
                    notFound();
                }
                sendJson(response, 200, server().getServerInfo());
            }
            case "memory" -> {
                if (path.size() != 1) {
                    notFound();
                }
                sendJson(response, 200, server().getMemoryInfo());
            }
            case "threads" -> {
                if (path.size() != 1) {
                    notFound();
                }
                requireAdministrator(request);
                sendText(response, 200, server().getThreadDump());
            }
            case "health" -> {
                if (path.size() != 1) {
                    notFound();
                }
                sendJson(response, 200, Map.of("status", "UP", "message", "Server is running"));
            }
            case "metrics" -> {
                if (path.size() != 2 || !"applications".equals(path.get(1))) {
                    notFound();
                }
                sendJson(response, 200, server().getApplicationMetrics());
            }
            case "threadpools" -> {
                if (path.size() != 1) {
                    notFound();
                }
                sendJson(response, 200, server().getThreadPoolStats());
            }
            default -> notFound();
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<String> path = segments(request);
        if (path.size() == 1 && "gc".equals(path.get(0))) {
            requireAdministrator(request);
            server().performGarbageCollection();
            sendMessage(response, 200, "Garbage collection triggered");
            return;
        }
        notFound();
    }
}
