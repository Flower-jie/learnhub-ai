package com.flower.learnhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessTokenFilter extends OncePerRequestFilter {
    private final byte[] expectedHash;
    private final ObjectMapper mapper;

    public AccessTokenFilter(AppSettings settings, ObjectMapper mapper) {
        this.expectedHash = digest(settings.accessToken());
        this.mapper = mapper;
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        String path;
        try {
            path = org.springframework.web.util.UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
            // Match the container's normalized route, including matrix parameters and duplicate separators.
            path = path.replaceAll(";[^/]*", "").replaceAll("/{2,}", "/");
            path = org.springframework.util.StringUtils.cleanPath(path);
            String context = request.getContextPath();
            if (!context.isEmpty() && path.startsWith(context + "/")) path = path.substring(context.length());
        } catch (IllegalArgumentException ex) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(), new ApiModels.Error("VALIDATION_ERROR", "请求路径无效"));
            return;
        }
        if (path.equals("/api") || path.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            String header = request.getHeader("Authorization");
            boolean bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
            String candidate = bearer ? header.substring(7) : "";
            boolean matches = MessageDigest.isEqual(expectedHash, digest(candidate));
            if (!bearer || !matches) {
                response.setStatus(401);
                response.setHeader("WWW-Authenticate", "Bearer");
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                mapper.writeValue(response.getWriter(), new ApiModels.Error("UNAUTHORIZED", "请提供有效的 Bearer 访问令牌"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
