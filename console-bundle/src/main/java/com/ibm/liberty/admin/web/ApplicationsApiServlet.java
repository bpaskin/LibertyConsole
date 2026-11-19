package com.ibm.liberty.admin.web;

import com.ibm.liberty.admin.json.Json;
import com.ibm.liberty.admin.model.ApplicationInfo;
import com.ibm.liberty.admin.service.ApplicationManagementService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /api/applications - list, start, stop, restart, deploy and remove applications.
 *
 * <pre>
 * GET    /api/applications                 all applications
 * GET    /api/applications/{name}          one application
 * POST   /api/applications/{name}/start    start (Administrator)
 * POST   /api/applications/{name}/stop     stop (Administrator)
 * POST   /api/applications/{name}/restart  restart (Administrator)
 * POST   /api/applications                 register an application, JSON body (Administrator)
 * POST   /api/applications/upload          upload and deploy, multipart/form-data (Administrator)
 * DELETE /api/applications/{name}          remove from server.xml and delete the file (Administrator)
 * </pre>
 */
public class ApplicationsApiServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    private static final Pattern DISPOSITION_PARAMETER = Pattern.compile(
        "(?:^|;)\\s*([^=;\\s]+)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^;]*)");

    private ApplicationManagementService applications() {
        return services().applications();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<String> path = segments(request);
        if (path.isEmpty()) {
            sendJson(response, 200, applications().getAllApplications());
        } else if (path.size() == 1) {
            ApplicationInfo app = applications().getApplication(path.get(0));
            if (app == null) {
                sendError(response, 404, "Application not found");
            } else {
                sendJson(response, 200, app);
            }
        } else {
            notFound();
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        List<String> path = segments(request);
        requireAdministrator(request);

        if (path.isEmpty()) {
            deploy(request, response);
        } else if (path.size() == 1 && "upload".equals(path.get(0))) {
            upload(request, response);
        } else if (path.size() == 2) {
            String name = path.get(0);
            switch (path.get(1)) {
                case "start" -> respond(response, applications().startApplication(name),
                    "Application started successfully", "Failed to start application");
                case "stop" -> respond(response, applications().stopApplication(name),
                    "Application stopped successfully", "Failed to stop application");
                case "restart" -> respond(response, applications().restartApplication(name),
                    "Application restarted successfully", "Failed to restart application");
                default -> notFound();
            }
        } else {
            notFound();
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<String> path = segments(request);
        if (path.size() != 1) {
            notFound();
        }
        requireAdministrator(request);
        try {
            if (applications().undeployApplication(path.get(0))) {
                sendMessage(response, 200, "Application undeployed successfully");
            } else {
                sendError(response, 404, "Application not found");
            }
        } catch (IllegalArgumentException e) {
            sendError(response, 400, e.getMessage());
        }
    }

    private void respond(HttpServletResponse response, boolean success, String ok, String failed) throws IOException {
        if (success) {
            sendMessage(response, 200, ok);
        } else {
            sendError(response, 400, failed);
        }
    }

    private void deploy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String body = readBody(request);
        if (body.isBlank()) {
            sendError(response, 400, "Application payload is required");
            return;
        }
        ApplicationInfo appInfo = Json.read(body, ApplicationInfo.class);
        try {
            ApplicationInfo deployed = applications().deployApplication(
                appInfo.getName(), appInfo.getContextRoot(), appInfo.getType());
            sendJson(response, 201, deployed);
        } catch (IllegalArgumentException e) {
            sendError(response, 400, e.getMessage());
        }
    }

    private void upload(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")) {
            sendError(response, 415, "Expected multipart/form-data");
            return;
        }
        try {
            Part filePart = request.getPart("file");
            if (filePart == null || filePart.getSize() == 0) {
                sendError(response, 400, "Application file is required");
                return;
            }

            String fileName = extractSubmittedFileName(filePart);
            String appName = getPartValue(request, "name");
            String contextRoot = getPartValue(request, "contextRoot");
            String type = getPartValue(request, "type");

            try (InputStream content = filePart.getInputStream()) {
                ApplicationInfo deployed = applications().deployApplication(
                    appName, contextRoot, type, fileName, content, filePart.getSize());
                sendJson(response, 201, deployed);
            }
        } catch (IllegalArgumentException e) {
            sendError(response, 400, e.getMessage());
        } catch (IllegalStateException e) {
            // Thrown by getPart() when the upload is larger than the multipart-config limits
            sendError(response, 413, "Uploaded application is too large");
        } catch (IOException | ServletException e) {
            sendError(response, 400, "Failed to process uploaded application");
        }
    }

    private String getPartValue(HttpServletRequest request, String partName) throws IOException, ServletException {
        Part part = request.getPart(partName);
        if (part == null) {
            return null;
        }
        try (InputStream in = part.getInputStream()) {
            String value = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        }
    }

    static String extractSubmittedFileName(Part part) {
        // Part.getSubmittedFileName() was added in Servlet 3.1. Read the header
        // instead so uploads also work on Servlet 3.0.
        String disposition = part.getHeader("Content-Disposition");
        String submittedFileName = null;
        if (disposition != null) {
            Matcher parameters = DISPOSITION_PARAMETER.matcher(disposition);
            while (parameters.find()) {
                if (!"filename".equalsIgnoreCase(parameters.group(1))) {
                    continue;
                }
                submittedFileName = parameters.group(2).trim();
                if (submittedFileName.startsWith("\"")) {
                    if (submittedFileName.length() < 2 || !submittedFileName.endsWith("\"")) {
                        throw new IllegalArgumentException("Uploaded file name is invalid");
                    }
                    submittedFileName = submittedFileName.substring(1, submittedFileName.length() - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\");
                }
                break;
            }
        }
        if (submittedFileName == null || submittedFileName.isBlank()) {
            throw new IllegalArgumentException("Uploaded file name is invalid");
        }
        int separatorIndex = Math.max(submittedFileName.lastIndexOf('/'), submittedFileName.lastIndexOf('\\'));
        String fileName = separatorIndex >= 0 ? submittedFileName.substring(separatorIndex + 1) : submittedFileName;
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("Uploaded file name is invalid");
        }
        return fileName;
    }
}
