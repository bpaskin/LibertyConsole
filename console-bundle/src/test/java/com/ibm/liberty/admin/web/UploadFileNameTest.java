package com.ibm.liberty.admin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.http.Part;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class UploadFileNameTest {

    private static Part part(String disposition) {
        return (Part) Proxy.newProxyInstance(UploadFileNameTest.class.getClassLoader(),
            new Class<?>[] { Part.class }, (proxy, method, args) -> {
                if ("getHeader".equals(method.getName()) && "Content-Disposition".equals(args[0])) {
                    return disposition;
                }
                throw new AssertionError("Uploads must only use Servlet 3.0 Part APIs: " + method);
            });
    }

    @Test
    void readsQuotedAndUnquotedFileNames() {
        assertEquals("shop.war", ApplicationsApiServlet.extractSubmittedFileName(
            part("form-data; name=\"file\"; filename=\"shop.war\"")));
        assertEquals("shop.war", ApplicationsApiServlet.extractSubmittedFileName(
            part("form-data; name=file; FILENAME=shop.war")));
        assertEquals("shop;v2.war", ApplicationsApiServlet.extractSubmittedFileName(
            part("form-data; name=\"file\"; filename=\"shop;v2.war\"")));
    }

    @Test
    void stripsClientPaths() {
        assertEquals("shop.war", ApplicationsApiServlet.extractSubmittedFileName(
            part("form-data; filename=\"/tmp/shop.war\"")));
        assertEquals("shop.war", ApplicationsApiServlet.extractSubmittedFileName(
            part("form-data; filename=\"C:\\fakepath\\shop.war\"")));
        assertEquals("shop.war", ApplicationsApiServlet.extractSubmittedFileName(
            part("form-data; filename=\"C:\\\\fakepath\\\\shop.war\"")));
    }

    @Test
    void rejectsMissingEmptyAndInvalidNames() {
        for (String disposition : new String[] { null, "form-data; name=file", "form-data; filename=\"\"",
                "form-data; filename=\" \"", "form-data; filename=\".\"", "form-data; filename=\"..\"",
                "form-data; filename=\"C:\\fakepath\\\"", "form-data; filename=\"unclosed" }) {
            assertThrows(IllegalArgumentException.class,
                () -> ApplicationsApiServlet.extractSubmittedFileName(part(disposition)), disposition);
        }
    }
}
