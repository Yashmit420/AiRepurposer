package com.ai.repurposer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

@Component
public class AuthTokenInterceptor implements HandlerInterceptor {
    private static final Set<String> PROTECTED_PREFIXES = Set.of(
        "/generate",
        "/plan",
        "/upgrade",
        "/account",
        "/logout"
    );

    private final UserStore userStore;

    public AuthTokenInterceptor(UserStore userStore) {
        this.userStore = userStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (!isProtected(path)) {
            return true;
        }

        String token = extractToken(request);
        if (token.isBlank()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing auth token");
            return false;
        }

        String requestedEmail = request.getParameter("email");
        if (requestedEmail != null && !requestedEmail.isBlank() && !token.equalsIgnoreCase(requestedEmail.trim())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Token does not match requested email");
            return false;
        }

        if (!userStore.existsByEmail(token)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid auth token");
            return false;
        }

        return true;
    }

    private static boolean isProtected(String path) {
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static String extractToken(HttpServletRequest request) {
        String customHeader = request.getHeader("X-Auth-Token");
        if (customHeader != null && !customHeader.isBlank()) {
            return customHeader.trim().toLowerCase();
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.toLowerCase().startsWith("bearer ")) {
            return authorization.substring(7).trim().toLowerCase();
        }
        return "";
    }

    private static void writeError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("text/plain");
        response.getWriter().write(message);
    }
}
