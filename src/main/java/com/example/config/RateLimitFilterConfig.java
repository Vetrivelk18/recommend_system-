package com.example.config;

import com.example.filter.RateLimitFilter;
import com.example.service.JwtService;
import com.example.service.RateLimitService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the rate limiter as a plain servlet filter.
 *
 * <p>Two details matter here. The URL pattern confines it to /api/*, so SpaController's
 * forwards, index.html, the JS chunks and the fonts are never counted - only real API
 * calls are. And the order puts it ahead of the DispatcherServlet, which is what makes
 * a 429 cheap: nothing below it runs.
 *
 * <p>The filter is built here rather than annotated @Component on purpose. Boot
 * auto-registers any Filter bean it finds against every URL, which would run this twice
 * per request and double-count every call.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilterConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimitService rateLimitService,
                                                                   JwtService jwtService,
                                                                   RateLimitProperties properties) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(rateLimitService, jwtService, properties));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("rateLimitFilter");
        return registration;
    }
}
