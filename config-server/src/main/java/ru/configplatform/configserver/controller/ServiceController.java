package ru.configplatform.configserver.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.configplatform.configserver.dto.CreateServiceRequest;
import ru.configplatform.configserver.dto.ServiceResponse;
import ru.configplatform.configserver.service.ConfigService;

import java.util.List;

@RestController
@RequestMapping("/v1/services")
@RequiredArgsConstructor
public class ServiceController {

    private final ConfigService configService;

    /**
     * GET /v1/services — список всех сервисов
     */
    @GetMapping
    public ResponseEntity<List<ServiceResponse>> getServices() {
        return ResponseEntity.ok(configService.getServices());
    }

    /**
     * POST /v1/services — создать новый сервис
     */
    @PostMapping
    public ResponseEntity<ServiceResponse> createService(
            @Valid @RequestBody CreateServiceRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(configService.createService(request));
    }
}
