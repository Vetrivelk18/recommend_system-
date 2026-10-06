package com.example.filter;

import com.example.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Makes the JWT authoritative. Until this existed the token was issued at login and never
 * checked, so every endpoint simply believed the userId in the URL - anyone could read any
 * dashboard, empty any cart, or place an order as any user.
 *
 * <p>The rule is ownership, not roles: a request may only act as the user its token names.
 * The userId stays in the path so no URL or response shape changes; this filter just stops
 * it being taken on trust.
 *
 * <p>Runs after RateLimitFilter on purpose. An unauthenticated flood should be capped
 * before it reaches token verification, not after.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String uri = request.getRequestURI();

        if (isPublic(uri)) {
            chain.doFilter(request, response);
            return;
        }

        Integer target = targetUserId(request, uri);

        // Search is the one endpoint that works signed out, so a missing token is fine
        // there - but only while the caller is not claiming to be somebody.
        if (target == null && isOptionalAuth(uri)) {
            chain.doFilter(request, response);
            return;
        }

        String token = bearerToken(request);
        if (token == null) {
            unauthorized(response, "authentication required");
            return;
        }

        Integer tokenUserId;
        try {
            tokenUserId = jwtService.parseUserId(token);
        } catch (Exception e) {
            // Expired, forged, malformed - all the same answer. Saying which would tell an
            // attacker whether they had guessed a real signing key.
            unauthorized(response, "invalid or expired token");
            return;
        }

        if (target != null && !target.equals(tokenUserId)) {
            // 403, not 401: the credentials are good, the request is not. The client must
            // not react by clearing the session and asking the user to sign in again.
            forbidden(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /** Reachable without an account, or without one yet - login, signup, the onboarding lists. */
    private boolean isPublic(String uri) {
        return uri.startsWith("/api/auth/")
                || uri.startsWith("/api/onboarding/");
    }

    private boolean isOptionalAuth(String uri) {
        return uri.startsWith("/api/search");
    }

    /**
     * The user this request is asking to act as: the first path segment after the
     * collection for the id-in-path endpoints, or ?userId= for search and the publish
     * endpoint.
     *
     * <p>Returns null when the request names nobody, which for a protected path still
     * requires a valid token - it just has nothing to compare against.
     */
    private Integer targetUserId(HttpServletRequest request, String uri) {
        for (String prefix : new String[]{
                "/api/cart/", "/api/orders/", "/api/dashboard/", "/api/recommendations/"}) {
            if (uri.startsWith(prefix)) {
                return parseLeadingInt(uri.substring(prefix.length()));
            }
        }
        if (uri.startsWith("/api/search") || uri.startsWith("/rabbit/send")) {
            return parseLeadingInt(request.getParameter("userId"));
        }
        return null;
    }

    /** "206220/items/24852" -> 206220. Null when the segment is absent or not a number. */
    private Integer parseLeadingInt(String segment) {
        if (segment == null || segment.isBlank()) {
            return null;
        }
        int slash = segment.indexOf('/');
        String head = slash < 0 ? segment : segment.substring(0, slash);
        try {
            return Integer.valueOf(head);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, message);
    }

    private void forbidden(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "this token does not belong to that user");
    }

    /**
     * Written by hand rather than through an ObjectMapper, for the same reason
     * RateLimitFilter does: Spring Boot 4.1 ships Jackson 3 under tools.jackson, while the
     * com.fasterxml 2.x on the classpath is runtime-scoped from jjwt-jackson and invisible
     * at compile time. One field is not worth walking into that.
     */
    private void write(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
