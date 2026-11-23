package com.ibm.liberty.admin.web;

import com.ibm.liberty.admin.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Optional ND endpoints. Management roles and same-origin checks also apply here. */
public final class CollectiveApiServlet extends ApiServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<String> path = segments(request);
        if (path.equals(List.of("capabilities"))) {
            sendJson(response, 200, services().collective().capabilities());
        } else if (path.isEmpty()) {
            sendJson(response, 200, services().collective().inventory());
        } else if (path.size() == 2 && "servers".equals(path.get(0))) {
            try {
                sendJson(response, 200, services().collective().monitoringTarget(path.get(1)));
            } catch (IllegalArgumentException e) {
                throw new ApiException(404, e.getMessage());
            }
        } else {
            notFound();
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        requireAdministrator(request);
        if (!segments(request).equals(List.of("operations"))) {
            notFound();
        }
        Map<String, Object> body = Json.parseObject(readBody(request));
        try {
            Map<String, Object> result = services().collective().operate(
                required(body, "kind"), required(body, "target"), required(body, "action"));
            sendJson(response, 200, result);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new ApiException(400, key + " is required");
        }
        return (String) value;
    }
}
