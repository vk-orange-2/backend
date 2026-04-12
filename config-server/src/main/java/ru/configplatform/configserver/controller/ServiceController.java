package ru.configplatform.configserver.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.configplatform.configserver.service.ConfigService;

import java.util.List;

@RestController
@RequestMapping("/v1/services")
@RequiredArgsConstructor
public class ServiceController {

    private final ConfigService configService;

    /**
     * GET /v1/services — список всех сервисов, у которых есть хотя бы один конфиг.
     */
    @GetMapping
    public ResponseEntity<List<String>> getServices() {
        List<String> services = configService.getServices();
        return ResponseEntity.ok(services);
    }
}
