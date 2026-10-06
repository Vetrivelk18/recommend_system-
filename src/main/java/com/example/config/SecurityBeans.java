package com.example.config;

import com.example.filter.JwtAuthFilter;
import com.example.service.JwtService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The password encoder, and the filter that makes the JWT mean something.
 *
 * <p>Still deliberately not spring-boot-starter-security: that would auto-enable a filter
 * chain and lock every endpoint behind a generated password, and replacing that with a
 * SecurityFilterChain is considerably more machinery than the one filter this needs. What
 * is required here is ownership checking - a request may only act as the user its token
 * names - not roles, scopes or a session concept.
 */
@Configuration
public class SecurityBeans {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Ordered behind RateLimitFilter (HIGHEST_PRECEDENCE + 100) and ahead of the
     * DispatcherServlet. That sequence matters in both directions: an unauthenticated flood
     * is capped before any token is verified, and a rejected request still never reaches a
     * controller.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(JwtService jwtService) {
        FilterRegistrationBean<JwtAuthFilter> registration =
                new FilterRegistrationBean<>(new JwtAuthFilter(jwtService));
        // /rabbit/send publishes a job for a given user, so it is guarded too - it sits
        // outside /api/*, hence the second pattern.
        registration.addUrlPatterns("/api/*", "/rabbit/send");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 200);
        registration.setName("jwtAuthFilter");
        return registration;
    }
}
