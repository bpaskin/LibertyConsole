package com.ibm.liberty.admin.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OriginCheckTest {

    /** A request to serverName:serverPort with the given headers. */
    private static HttpServletRequest request(String serverName, int serverPort, String... headers) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            map.put(headers[i].toLowerCase(), headers[i + 1]);
        }
        return (HttpServletRequest) Proxy.newProxyInstance(OriginCheckTest.class.getClassLoader(),
            new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> switch (method.getName()) {
                case "getHeader" -> map.get(((String) args[0]).toLowerCase());
                case "getServerName" -> serverName;
                case "getServerPort" -> serverPort;
                case "getScheme" -> "https";
                default -> null;
            });
    }

    private static void refused(HttpServletRequest request) {
        ApiServlet.ApiException e = assertThrows(ApiServlet.ApiException.class, () -> ApiServlet.checkSameOrigin(request));
        assertEquals(403, e.status);
    }

    @Test
    void allowsRequestsWithoutOrigin() {
        assertDoesNotThrow(() -> ApiServlet.checkSameOrigin(request("localhost", 9443)));
    }

    @Test
    void allowsSameOrigin() {
        // HTTP/2: no Host header, only the server name and port
        assertDoesNotThrow(() -> ApiServlet.checkSameOrigin(request("localhost", 9443, "Origin", "https://localhost:9443")));
        assertDoesNotThrow(() -> ApiServlet.checkSameOrigin(
            request("internal", 9443, "Origin", "https://console.example.com", "Host", "console.example.com")));
        assertDoesNotThrow(() -> ApiServlet.checkSameOrigin(
            request("internal", 9443, "Origin", "https://console.example.com", "X-Forwarded-Host", "console.example.com, proxy")));
        assertDoesNotThrow(() -> ApiServlet.checkSameOrigin(
            request("internal", 9443, "Origin", "https://[::1]:9443", "Host", "[::1]:9443")));
    }

    @Test
    void refusesOtherOrigins() {
        refused(request("localhost", 9443, "Origin", "https://evil.example"));
        refused(request("localhost", 9443, "Origin", "https://localhost:8443", "Host", "localhost:9443"));
        refused(request("localhost", 9443, "Origin", "http://localhost:9443", "Host", "localhost")); // port 80 != 9443
        refused(request("localhost", 9443, "Origin", "null"));
        refused(request("localhost", 9443, "Origin", "not a uri"));
    }
}
