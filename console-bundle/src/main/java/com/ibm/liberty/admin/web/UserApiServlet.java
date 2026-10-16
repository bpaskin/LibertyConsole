package com.ibm.liberty.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/user - the signed-in user and their management roles, so the UI can
 * show the user name and know whether the user is read-only.
 */
public class UserApiServlet extends ApiServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!segments(request).isEmpty()) {
            notFound();
        }
        Principal principal = request.getUserPrincipal();
        boolean administrator = request.isUserInRole(ADMINISTRATOR);
        boolean reader = request.isUserInRole(READER);

        List<String> roles = new ArrayList<>();
        if (administrator) {
            roles.add(ADMINISTRATOR);
        }
        if (reader) {
            roles.add(READER);
        }

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("name", principal != null ? principal.getName() : request.getRemoteUser());
        user.put("roles", roles);
        user.put("administrator", administrator);
        user.put("readOnly", !administrator);
        sendJson(response, 200, user);
    }
}
