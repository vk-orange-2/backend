package ru.configplatform.configserver.service;

import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import ru.configplatform.configserver.model.ApiKeyEntity;
import ru.configplatform.configserver.model.ApiKeyId;
import ru.configplatform.configserver.repository.ApiKeyRepository;
import ru.configplatform.configserver.repository.EnvironmentRepository;
import ru.configplatform.configserver.repository.ServiceRepository;

@Service
public class ApiKeyService {
    private static final long JWT_EXPIRATION_MILLISECONDS = 30 * 60 * 1000; // 30 minutes

    private final ApiKeyRepository repo;
    private final EnvironmentRepository envRepo;
    private final ServiceRepository serviceRepo;

    private final Argon2PasswordEncoder argonEncoder = new Argon2PasswordEncoder(16, 32, 1, 60000, 10);

    private final String jwtSigningKey;

    @Autowired
    public ApiKeyService(
            final ApiKeyRepository repo,
            final EnvironmentRepository envRepo,
            final ServiceRepository serviceRepo,
            @Value("${config_platform.jwt.signingKey}") final String jwtSigningKey) {
        this.repo = repo;
        this.envRepo = envRepo;
        this.serviceRepo = serviceRepo;
        this.jwtSigningKey = jwtSigningKey;
    }

    public String getJwtByApiKey(String apiKeyValue, UUID serviceId, short environmentId) {
        var apiKeyId = new ApiKeyId(serviceId, environmentId);
        var apiKeyOpt = repo.findById(apiKeyId);

        if (apiKeyOpt.isEmpty()) {
            return null;
        }

        var apiKey = apiKeyOpt.get();

        // Verify the API key using Argon2 matches()
        if (!argonEncoder.matches(apiKeyValue, apiKey.getValue())) {
            return null;
        }

        var service = serviceRepo.findById(apiKey.getServiceId()).orElseThrow();

        var env = envRepo.findById(apiKey.getEnvironmentId()).orElseThrow();

        String channelName = "service:" + service.getName() + ":" + env.getCode();

        SecretKey hmacKey = Keys.hmacShaKeyFor(jwtSigningKey.getBytes());

        var now = new Date();

        return Jwts.builder()
                .subject(channelName)
                .claim("channel", channelName)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + JWT_EXPIRATION_MILLISECONDS))
                .signWith(hmacKey)
                .compact();
    }

    public String createOrResetApiKey(UUID serviceId, short environmentId) {
        var newValue = UUID.randomUUID().toString();

        var apiKeyId = new ApiKeyId(serviceId, environmentId);
        var apiKeyOpt = repo.findById(apiKeyId);

        var encodedValue = argonEncoder.encode(newValue);

        if (apiKeyOpt.isEmpty()) {
            var newApiKey = ApiKeyEntity.builder()
                    .serviceId(serviceId)
                    .environmentId(environmentId)
                    .value(encodedValue)
                    .build();
            repo.saveAndFlush(newApiKey);
            return newValue;
        }

        var apiKey = apiKeyOpt.get();
        apiKey.setValue(encodedValue);
        repo.saveAndFlush(apiKey);

        return newValue;
    }
}
