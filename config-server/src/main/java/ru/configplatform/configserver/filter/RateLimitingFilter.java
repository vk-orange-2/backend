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

        // Получаем идентификаторы клиента
        String clientIp = getClientIpAddress(request);
        String author = request.getHeader("X-Author");
        String serviceAccount = request.getHeader("X-Service-Account");

        log.info("IP = {}, author = {}, serviceAccount = {}", clientIp, author, serviceAccount);

        // Обязательное наличие хотя бы одного заголовка
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
            var ipLimit = write ? properties.getIp().getWrite() : properties.getIp().getRead();
            rateLimitService.consume(
                    "ip:" + clientIp + ":" + operation,
                    ipLimit.getRate(),
                    ipLimit.getBurst()
            );

            if (author != null) {
                var userLimit = write ? properties.getUser().getWrite() : properties.getUser().getRead();
                rateLimitService.consume(
                        "user:" + author + ":" + operation,
                        userLimit.getRate(),
                        userLimit.getBurst()
                );
            }

            if (serviceAccount != null) {
                var saLimit = write ? properties.getServiceAccount().getWrite() : properties.getServiceAccount().getRead();
                rateLimitService.consume(
                        "service:" + serviceAccount + ":" + operation,
                        saLimit.getRate(),
                        saLimit.getBurst()
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

    /**
     * Извлекает реальный IP-адрес клиента из запроса,
     * учитывая заголовки прокси (X-Forwarded-For, Proxy-Client-IP и т.п.).
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String ipAddress = request.getHeader("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getHeader("Proxy-Client-IP");
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getRemoteAddr();
        }
        if (ipAddress != null && ipAddress.contains(",")) {
            ipAddress = ipAddress.split(",")[0].trim();
        }
        if (ipAddress == null || ipAddress.isBlank()) {
            ipAddress = "0.0.0.0";
        }
        return ipAddress;
    }
}
