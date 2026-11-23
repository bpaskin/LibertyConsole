package com.ibm.liberty.admin.web;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class CollectiveAuthorizationTest {
    @Test
    void readerIsRejectedBeforeParsingOrInvokingAnOperation() {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> {
                if (method.getName().equals("isUserInRole")) return false;
                throw new AssertionError("Reader requests must be refused before accessing " + method.getName());
            });
        ApiServlet.ApiException error = assertThrows(ApiServlet.ApiException.class,
            () -> new CollectiveApiServlet().doPost(request, null));
        assertEquals(403, error.status);
    }
}
