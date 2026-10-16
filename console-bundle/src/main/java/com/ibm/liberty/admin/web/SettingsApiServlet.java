package com.ibm.liberty.admin.web;

import com.ibm.liberty.admin.json.Json;
import com.ibm.liberty.admin.model.UserSettings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * /api/settings - console UI settings (theme, charts), stored in
 * ${server.config.dir}/admin-console-settings.json.
 *
 * <pre>
 * GET /api/settings   current settings
 * PUT /api/settings   save settings, JSON body (Administrator or Reader)
 * </pre>
 */
public class SettingsApiServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!segments(request).isEmpty()) {
            notFound();
        }
        sendJson(response, 200, services().settings().loadSettings());
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!segments(request).isEmpty()) {
            notFound();
        }
        UserSettings settings = Json.read(readBody(request), UserSettings.class);
        try {
            services().settings().saveSettings(settings);
            sendMessage(response, 200, "Settings saved successfully");
        } catch (RuntimeException e) {
            sendError(response, 500, e.getMessage());
        }
    }
}
