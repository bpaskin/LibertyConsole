package com.ibm.liberty.admin.web;

import com.ibm.liberty.admin.json.Json;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for the console's REST endpoints.
 * <p>
 * Who may call the API is decided in two places:
 * <ul>
 * <li>web.xml lets only users in the server's {@code <administrator-role>} or
 * {@code <reader-role>} reach /api/*, because the bundle declares
 * {@code IBM-Authorization-Roles: com.ibm.ws.management}</li>
 * <li>operations that change the server call {@link #requireAdministrator}</li>
 * </ul>
 * Requests that change state are also refused when their Origin header names
 * another site (cross-site request forgery protection).
 */
abstract class ApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    static final String ADMINISTRATOR = "Administrator";
    static final String READER = "Reader";

    static final String JSON = "application/json";
    static final String TEXT = "text/plain";

    private static final Logger LOGGER = Logger.getLogger(ApiServlet.class.getName());

    /** Thrown to end a request with an error status and message (sent as JSON). */
    static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        ApiException(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    protected ConsoleServices services() {
        return ConsoleServices.from(getServletContext());
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            String method = request.getMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method)) {
                checkSameOrigin(request);
            }
            super.service(request, response);
        } catch (ApiException e) {
            sendError(response, e.status, e.getMessage());
        } catch (Json.JsonException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON: " + e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Request " + request.getMethod() + " " + request.getRequestURI() + " failed", e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** Path segments after the servlet path, e.g. "/myapp/start" gives [myapp, start]. */
    protected static List<String> segments(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            return Collections.emptyList();
        }
        List<String> segments = new ArrayList<>();
        for (String segment : pathInfo.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    protected static void requireAdministrator(HttpServletRequest request) {
        if (!request.isUserInRole(ADMINISTRATOR)) {
            throw new ApiException(HttpServletResponse.SC_FORBIDDEN,
                "This operation requires the Administrator role");
        }
    }

    protected static int intParameter(HttpServletRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Parameter " + name + " must be a number");
        }
    }

    protected static String readBody(HttpServletRequest request) throws IOException {
        return new String(request.getInputStream().readAllBytes(), charset(request));
    }

    protected static void sendJson(HttpServletResponse response, int status, Object body) throws IOException {
        write(response, status, JSON, Json.write(body));
    }

    protected static void sendText(HttpServletResponse response, int status, String body) throws IOException {
        write(response, status, TEXT, body != null ? body : "");
    }

    /** A JSON body with a single "message" (success) property. */
    protected static void sendMessage(HttpServletResponse response, int status, String message) throws IOException {
        sendJson(response, status, Map.of("message", message));
    }

    protected static void sendError(HttpServletResponse response, int status, String error) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        sendJson(response, status, Map.of("error", error != null ? error : "Error"));
    }

    protected static void notFound() {
        throw new ApiException(HttpServletResponse.SC_NOT_FOUND, "Not found");
    }

    private static void write(HttpServletResponse response, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType(contentType + ";charset=UTF-8");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private static Charset charset(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        if (encoding != null) {
            try {
                return Charset.forName(encoding);
            } catch (RuntimeException e) {
                // fall through to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Refuses a state-changing request whose Origin header names another site. The origin
     * is compared with the host the request was sent to: the server name and port (which
     * also covers HTTP/2, where there is no Host header), the Host header, and
     * X-Forwarded-Host when the console is behind a proxy.
     */
    static void checkSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return; // not a cross-site browser request (curl, scripts, older browsers)
        }
        URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpServletResponse.SC_FORBIDDEN, "Cross-origin request refused");
        }
        String originHost = uri.getHost();
        int originPort = uri.getPort() != -1 ? uri.getPort() : defaultPort(uri.getScheme());
        if (originHost != null) {
            if (originHost.equalsIgnoreCase(request.getServerName()) && originPort == request.getServerPort()
                    && uri.getScheme() != null && uri.getScheme().equalsIgnoreCase(request.getScheme())) {
                return;
            }
            String scheme = uri.getScheme();
            for (String header : new String[] { "Host", "X-Forwarded-Host" }) {
                String value = request.getHeader(header);
                if (value != null) {
                    // X-Forwarded-Host may hold a list; the first entry is the client-facing host
                    String first = value.split(",")[0].trim();
                    if (matches(first, originHost, originPort, scheme)) {
                        return;
                    }
                }
            }
        }
        throw new ApiException(HttpServletResponse.SC_FORBIDDEN, "Cross-origin request refused");
    }

    /** Whether a "host[:port]" value names the same host and port as the origin. */
    private static boolean matches(String hostAndPort, String originHost, int originPort, String scheme) {
        String host = hostAndPort;
        int port = defaultPort(scheme);
        int colon = hostAndPort.lastIndexOf(':');
        if (colon > 0 && hostAndPort.indexOf(']') < colon) { // ignore the colons of an IPv6 literal
            host = hostAndPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostAndPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        String origin = originHost.startsWith("[") && originHost.endsWith("]")
            ? originHost.substring(1, originHost.length() - 1) : originHost;
        return host.equalsIgnoreCase(origin) && port == originPort;
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
