package ru.configplatform.configserver.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.configplatform.configserver.config.RateLimitProperties;
import ru.configplatform.configserver.exception.RateLimitExceededException;
import ru.configplatform.configserver.service.RateLimitService;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitingFilter
        extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (path.startsWith("/actuator")
                || path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs")) {

            chain.doFilter(request, response);
            return;
        }

        String author = request.getHeader("X-Author");
        String serviceAccount = request.getHeader("X-Service-Account");

        log.info("author = {}, serviceAccount = {}", author, serviceAccount);

        if (author == null && serviceAccount == null) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {
                      "message": "Missing required headers: X-Author or X-Service-Account"
                    }
                    """);
            return;
        }

        boolean write = !"GET".equals(request.getMethod());
        String operation = write ? "write" : "read";

        try {

            if (author != null) {

                var limit = write
                        ? properties.getUser().getWrite()
                        : properties.getUser().getRead();

                rateLimitService.consume(
                        "user:" + author + ":" + operation,
                        limit.getRate(),
                        limit.getBurst()
                );
            }

            if (serviceAccount != null) {

                var limit = write
                        ? properties.getServiceAccount().getWrite()
                        : properties.getServiceAccount().getRead();

                rateLimitService.consume(
                        "service:" + serviceAccount + ":" + operation,
                        limit.getRate(),
                        limit.getBurst()
                );
            }

            chain.doFilter(request, response);
        } catch (RateLimitExceededException ex) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());

            response.setHeader(
                    HttpHeaders.RETRY_AFTER,
                    String.valueOf(ex.getRetryAfterSeconds())
            );

            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            response.getWriter().write("""
                    {
                      "message": "Rate limit exceeded"
                    }
                    """);
        }
    }
}
