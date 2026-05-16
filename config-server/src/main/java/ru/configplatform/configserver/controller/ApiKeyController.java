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
    String createOrRefreshApiKey(
            @RequestParam("serviceId") UUID serviceId,
            @RequestParam("environmentId") short environmentId) {
        return service.createOrResetApiKey(serviceId, environmentId);
    }

    @GetMapping("/connection-token")
    ResponseEntity<String> getConnectionToken(
            @RequestParam("apiKey") String apiKey,
            @RequestParam("serviceId") UUID serviceId,
            @RequestParam("environmentId") short environmentId,
            @RequestParam(value = "instanceName", required = false) String instanceName) {
        String jwt = service.getConnectionJwt(apiKey, serviceId, environmentId, instanceName);
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(jwt);
    }

    @GetMapping("/subscription-token")
    ResponseEntity<String> getSubscriptionToken(
            @RequestParam("apiKey") String apiKey,
            @RequestParam("serviceId") UUID serviceId,
            @RequestParam("environmentId") short environmentId,
            @RequestParam(value = "instanceName", required = false) String instanceName,
            @RequestParam(value = "configKey", required = false) String configKey,
            @RequestParam(value = "deployment", required = false) Integer deployment) {
        
        String jwt;
        if (configKey != null && deployment != null) {
            // Gradual rollout channel subscription
            jwt = service.getSubscriptionJwtForGradualChannel(apiKey, serviceId, environmentId, instanceName, configKey, deployment);
        } else {
            // Base channel subscription
            jwt = service.getSubscriptionJwt(apiKey, serviceId, environmentId, instanceName);
        }
        
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(jwt);
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
