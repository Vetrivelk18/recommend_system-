package com.example.filter;

import com.example.config.RateLimitProperties;
import com.example.dto.RateLimitDecision;
import com.example.service.JwtService;
import com.example.service.RateLimitService;
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
 * The entry-layer gate. Runs ahead of the DispatcherServlet, so a rejected request
 * never reaches a controller and therefore never reaches the expensive work behind
 * one: the embed hop, RRF, pgvector, or a 14B Ollama call.
 *
 * <p>There is no Spring Security filter chain in this project - SecurityBeans
 * deliberately omits the starter - so this registers as a plain servlet filter, scoped
 * to /api/* by RateLimitFilterConfig. If spring-boot-starter-security is ever added,
 * this should move to addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class).
 *
 * <p>This filter reads the JWT but does not enforce it. A request with no token, or a
 * bad one, is still served - it is simply limited by IP instead of by user. Turning the
 * token into real authentication is a separate change.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final RateLimitService rateLimitService;
    private final JwtService jwtService;
    private final RateLimitProperties properties;

    public RateLimitFilter(RateLimitService rateLimitService,
                           JwtService jwtService,
                           RateLimitProperties properties) {
        this.rateLimitService = rateLimitService;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String bucket = bucketFor(request.getRequestURI());
        if (!properties.isEnabled() || bucket == null) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = rateLimitService.check(bucket, identity(request));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        reject(response, bucket, decision);
    }

    /**
     * Unlisted paths return null and are not limited. /api/onboarding/* is five static
     * dropdown reads and /api/dashboard/* is three indexed queries, so neither is worth
     * a bucket; both can be added to this method and the properties file if that changes.
     */
    private String bucketFor(String uri) {
        if (uri.startsWith("/api/search")) {
            return "search";
        }
        if (uri.startsWith("/api/recommendations")) {
            return "recommendations";
        }
        if (uri.startsWith("/api/auth")) {
            return "auth";
        }
        return null;
    }

    /**
     * The signed user id when the caller presents a usable token, the client address
     * otherwise.
     *
     * <p>Deliberately NOT the userId path segment or query parameter the controllers
     * take today: that value is caller-supplied, so anyone could slip the limit by
     * changing the number. A signed subject cannot be forged; an IP at least costs
     * something to change.
     */
    private String identity(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            try {
                return String.valueOf(jwtService.parseUserId(header.substring(BEARER.length())));
            } catch (Exception e) {
                // Expired, forged or malformed. Not this filter's job to reject it - the
                // caller simply loses their per-user bucket and shares the IP one.
                logger.debug("unusable bearer token, falling back to IP: " + e);
            }
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        if (properties.isTrustForwardedHeader()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Right-most entry, not left-most. A proxy appends the address it actually
                // saw to whatever the caller sent, so the last entry is the only one the
                // caller cannot write. Taking the first would let anyone slip the limit
                // by sending a different X-Forwarded-For with each request.
                String[] hops = forwarded.split(",");
                return hops[hops.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * Written by hand rather than through an ObjectMapper. Spring Boot 4.1 ships
     * Jackson 3 (tools.jackson.*), while the com.fasterxml 2.x on the classpath is
     * runtime-scoped from jjwt-jackson and invisible at compile time - four fields are
     * not worth walking into that.
     */
    private void reject(HttpServletResponse response, String bucket, RateLimitDecision decision)
            throws IOException {

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Without this Tomcat appends its own default (ISO-8859-1) to the Content-Type,
        // which is wrong for JSON even while the body happens to be pure ASCII.
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", "0");

        response.getWriter().write(
                "{\"error\":\"rate limit exceeded\","
                        + "\"endpoint\":\"" + bucket + "\","
                        + "\"limit\":" + decision.limit() + ","
                        + "\"retryAfterSeconds\":" + decision.retryAfterSeconds() + "}");
    }
}
