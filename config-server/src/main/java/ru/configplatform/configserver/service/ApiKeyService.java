package ru.configplatform.configserver.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.micrometer.observation.annotation.Observed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private static final int GCM_IV_LENGTH = 12; // 96 bits for GCM IV
    private static final int GCM_TAG_LENGTH = 128; // 128 bits authentication tag
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private final ApiKeyRepository repo;
    private final EnvironmentRepository envRepo;
    private final ServiceRepository serviceRepo;

    private final SecretKey aesEncryptionKey;
    private final String jwtSigningKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public ApiKeyService(
            final ApiKeyRepository repo,
            final EnvironmentRepository envRepo,
            final ServiceRepository serviceRepo,
            @Value("${config_platform.jwt.signingKey}") final String jwtSigningKey,
            @Value("${config_platform.apiKey.encryptionKey}") final String encryptionKey) {
        this.repo = repo;
        this.envRepo = envRepo;
        this.serviceRepo = serviceRepo;
        this.jwtSigningKey = jwtSigningKey;
        this.aesEncryptionKey = new SecretKeySpec(encryptionKey.getBytes(), "AES");
    }

    private String encrypt(String plaintext) {
        try {
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, aesEncryptionKey, parameterSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext and encode as Base64
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt API key", e);
        }
    }

    private String decrypt(String encrypted) {
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);

            // Extract IV and ciphertext
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, aesEncryptionKey, parameterSpec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt API key", e);
        }
    }

    /**
     * Validates API key and returns service/env info if valid.
     * Shared validation logic for both connection and subscription JWT generation.
     */
    @Observed(
            name = "apikey.validate",
            contextualName = "validate-api-key"
    )
    private ValidatedApiKey validateApiKey(String apiKeyValue, UUID serviceId, short environmentId) {
        var apiKeyId = new ApiKeyId(serviceId, environmentId);
        var apiKeyOpt = repo.findById(apiKeyId);

        if (apiKeyOpt.isEmpty()) {
            return null;
        }

        var apiKey = apiKeyOpt.get();

        // Decrypt the stored key and compare
        String decryptedKey = decrypt(apiKey.getValue());
        if (!apiKeyValue.equals(serviceId + ":" + environmentId + ":" + decryptedKey)) {
            return null;
        }

        var service = serviceRepo.findById(apiKey.getServiceId()).orElseThrow();
        var env = envRepo.findById(apiKey.getEnvironmentId()).orElseThrow();

        return new ValidatedApiKey(service.getName(), env.getCode());
    }

    /**
     * Generates a connection JWT token without channel claim.
     * Used for establishing Centrifugo connection.
     */
    @Observed(
            name = "apikey.connection.jwt",
            contextualName = "generate-connection-jwt"
    )
    public String getConnectionJwt(String apiKeyValue, UUID serviceId, short environmentId, String instanceName) {
        ValidatedApiKey validated = validateApiKey(apiKeyValue, serviceId, environmentId);
        if (validated == null) {
            return null;
        }

        SecretKey hmacKey = Keys.hmacShaKeyFor(jwtSigningKey.getBytes());
        var now = new Date();
        String subjectName = instanceName == null ? UUID.randomUUID().toString() : instanceName;

        return Jwts.builder()
                .subject(validated.serviceName() + ":" + validated.envCode() + ":" + subjectName)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + JWT_EXPIRATION_MILLISECONDS))
                .signWith(hmacKey)
                .compact();
    }

    /**
     * Generates a subscription JWT token with channel claim.
     * Used for subscribing to the Centrifugo channel.
     * 
     * @param apiKeyValue   the API key value
     * @param serviceId     the service ID
     * @param environmentId the environment ID
     * @param instanceName  the instance name (can be null)
     * @return JWT token string, or null if validation fails
     */
    @Observed(
            name = "apikey.subscription.jwt",
            contextualName = "generate-subscription-jwt"
    )
    public String getSubscriptionJwt(String apiKeyValue, UUID serviceId, short environmentId, String instanceName) {
        ValidatedApiKey validated = validateApiKey(apiKeyValue, serviceId, environmentId);
        if (validated == null) {
            return null;
        }

        // Channel: service:<service>:<env>
        String channel = "service:" + validated.serviceName() + ":" + validated.envCode();

        SecretKey hmacKey = Keys.hmacShaKeyFor(jwtSigningKey.getBytes());
        var now = new Date();
        String subjectName = instanceName == null ? UUID.randomUUID().toString() : instanceName;

        return Jwts.builder()
                .subject(validated.serviceName() + ":" + validated.envCode() + ":" + subjectName)
                .claim("channel", channel)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + JWT_EXPIRATION_MILLISECONDS))
                .signWith(hmacKey)
                .compact();
    }

    /** Internal record for validated API key data */
    private record ValidatedApiKey(String serviceName, String envCode) {
    }

    @Observed(
            name = "apikey.get",
            contextualName = "get-api-key"
    )
    public String getApiKey(UUID serviceId, short environmentId) {
        var apiKeyId = new ApiKeyId(serviceId, environmentId);
        var apiKeyOpt = repo.findById(apiKeyId);

        if (apiKeyOpt.isEmpty()) {
            return null;
        }

        return buildApiKeyClientValue(serviceId, environmentId, decrypt(apiKeyOpt.get().getValue()));
    }

    @Observed(
            name = "apikey.create.reset",
            contextualName = "create-or-reset-api-key"
    )
    public String createOrResetApiKey(UUID serviceId, short environmentId) {
        var newValue = UUID.randomUUID().toString();

        var apiKeyId = new ApiKeyId(serviceId, environmentId);
        var apiKeyOpt = repo.findById(apiKeyId);

        var encryptedValue = encrypt(newValue);

        if (apiKeyOpt.isEmpty()) {
            var newApiKey = ApiKeyEntity.builder()
                    .serviceId(serviceId)
                    .environmentId(environmentId)
                    .value(encryptedValue)
                    .build();
            repo.saveAndFlush(newApiKey);
            return buildApiKeyClientValue(serviceId, environmentId, newValue);
        }

        var apiKey = apiKeyOpt.get();
        apiKey.setValue(encryptedValue);
        repo.saveAndFlush(apiKey);

        return buildApiKeyClientValue(serviceId, environmentId, newValue);
    }

    private String buildApiKeyClientValue(UUID serviceId, short environmentId, String apiKey) {
        return serviceId.toString() + ":" + environmentId + ":" + apiKey;
    }
}
