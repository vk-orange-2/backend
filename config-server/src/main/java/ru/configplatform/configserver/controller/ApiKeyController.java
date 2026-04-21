package ru.configplatform.configserver.controller;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.configplatform.configserver.service.ApiKeyService;

@RestController
@RequestMapping("/v1/api-keys")
public class ApiKeyController {
    @Autowired
    ApiKeyService service;

    @PutMapping
    String createOrRefreshToken(
            @RequestParam("serviceId") UUID serviceId,
            @RequestParam("environmentId") short environmentId) {
        return service.createOrResetApiKey(serviceId, environmentId);
    }

    @GetMapping("/exchange")
    String exchange(
            @RequestParam("apiKey") String apiKey,
            @RequestParam("serviceId") UUID serviceId,
            @RequestParam("environmentId") short environmentId) {
        String jwt = service.getJwtByApiKey(apiKey, serviceId, environmentId);
        if (jwt == null) {
            return "";
        }
        return jwt;
    }

    @GetMapping
    ResponseEntity<String> getApiKey(
            @RequestParam("serviceId") UUID serviceId,
            @RequestParam("environmentId") short environmentId) {
        String apiKey = service.getApiKey(serviceId, environmentId);
        if (apiKey == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(apiKey);
    }
}
