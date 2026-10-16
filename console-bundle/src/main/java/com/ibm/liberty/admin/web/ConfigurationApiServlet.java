package com.ibm.liberty.admin.web;

import com.ibm.liberty.admin.service.ConfigurationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/configuration - read and edit server.xml and the other configuration files.
 *
 * <pre>
 * GET /api/configuration/server.xml         server.xml, text/plain
 * PUT /api/configuration/server.xml         replace server.xml, text/plain body (Administrator)
 * GET /api/configuration/info               location of server.xml
 * GET /api/configuration/files              configuration files in the server directory
 * GET /api/configuration/file?path=...      one file, text/plain
 * PUT /api/configuration/file?path=...      replace one file, text/plain body (Administrator)
 * </pre>
 * Every write keeps a timestamped backup of the previous file next to it.
 */
public class ConfigurationApiServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    private static final String RESTART_NOTE = "Server restart may be required for changes to take effect";

    private ConfigurationService configuration() {
        return services().configuration();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<String> path = segments(request);
        if (path.size() != 1) {
            notFound();
        }
        switch (path.get(0)) {
            case "server.xml" -> sendText(response, 200, configuration().readServerXml());
            case "info" -> {
                Map<String, String> info = new LinkedHashMap<>();
                info.put("filePath", configuration().getServerXmlFilePath());
                info.put("note", "Backups are created automatically when updating configuration");
                sendJson(response, 200, info);
            }
            case "files" -> sendJson(response, 200, configuration().listConfigurationFiles());
            case "file" -> {
                try {
                    sendText(response, 200, configuration().readConfigurationFile(request.getParameter("path")));
                } catch (IllegalArgumentException e) {
                    sendError(response, 400, e.getMessage());
                }
            }
            default -> notFound();
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<String> path = segments(request);
        if (path.size() != 1) {
            notFound();
        }
        switch (path.get(0)) {
            case "server.xml" -> {
                requireAdministrator(request);
                String content = readBody(request);
                if (!configuration().validateXml(content)) {
                    sendError(response, 400, "Invalid XML content");
                } else if (configuration().writeServerXml(content)) {
                    sendJson(response, 200, updated("Configuration updated successfully"));
                } else {
                    sendError(response, 500, "Failed to update configuration");
                }
            }
            case "file" -> {
                requireAdministrator(request);
                String content = readBody(request);
                try {
                    if (configuration().writeConfigurationFile(request.getParameter("path"), content)) {
                        sendJson(response, 200, updated("File updated successfully"));
                    } else {
                        sendError(response, 500, "Failed to update file");
                    }
                } catch (IllegalArgumentException e) {
                    sendError(response, 400, e.getMessage());
                }
            }
            default -> notFound();
        }
    }

    private static Map<String, String> updated(String message) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("note", RESTART_NOTE);
        return result;
    }
}
