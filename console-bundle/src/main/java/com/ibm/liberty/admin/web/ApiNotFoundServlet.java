package com.ibm.liberty.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Answers any /api/* path that no other endpoint handles with a JSON 404. */
public class ApiNotFoundServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        sendError(response, HttpServletResponse.SC_NOT_FOUND, "Not found");
    }
}
