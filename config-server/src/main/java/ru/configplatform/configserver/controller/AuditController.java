package ru.configplatform.configserver.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.configplatform.configserver.dto.AuditSearchResponse;
import ru.configplatform.configserver.service.AuditService;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<AuditSearchResponse> search(
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) UUID configId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(
                auditService.search(serviceName, actor, from, to, operation, configId, page, size)
        );
    }
}
