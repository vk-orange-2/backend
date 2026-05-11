package ru.configplatform.configserver.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.model.EnvironmentEntity;
import ru.configplatform.configserver.model.ServiceEntity;
import ru.configplatform.configserver.repository.EnvironmentRepository;
import ru.configplatform.configserver.repository.ServiceRepository;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    private UUID testServiceId;
    private static final short DEV_ENV_ID = 1;

    @BeforeEach
    void setUp() {
        // Create environments
        short id = 1;
        for (String code : List.of("dev", "stage", "prod")) {
            if (environmentRepository.findByCode(code).isEmpty()) {
                EnvironmentEntity env = EnvironmentEntity.builder()
                        .id(id)
                        .code(code)
                        .name(code.toUpperCase())
                        .build();
                environmentRepository.save(env);
                id++;
            }
        }

        // Create a test service
        ServiceEntity service = ServiceEntity.builder()
                .name("test-api-service")
                .build();
        testServiceId = serviceRepository.save(service).getId();
    }

    @Test
    void shouldCreateApiKey() throws Exception {
        mockMvc.perform(put("/v1/api-keys")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(emptyString())));
    }

    @Test
    void shouldReturnExistingApiKey() throws Exception {
        // First create an API key
        String apiKey = mockMvc.perform(put("/v1/api-keys")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Then retrieve it
        mockMvc.perform(get("/v1/api-keys")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk())
                .andExpect(content().string(testServiceId.toString() + ":" + DEV_ENV_ID + ":" + apiKey));
    }

    @Test
    void shouldReturn404WhenApiKeyNotFound() throws Exception {
        UUID randomServiceId = UUID.randomUUID();

        mockMvc.perform(get("/v1/api-keys")
                .param("serviceId", randomServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldResetApiKey() throws Exception {
        // Create initial API key
        String firstKey = mockMvc.perform(put("/v1/api-keys")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Reset (should return a new key)
        String secondKey = mockMvc.perform(put("/v1/api-keys")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Keys should be different
        assertThat(firstKey, not(equalTo(testServiceId.toString() + ":" + DEV_ENV_ID + ":" + secondKey)));
    }

    @Test
    void shouldGetConnectionTokenForValidApiKey() throws Exception {
        // Create API key
        String apiKey = mockMvc.perform(put("/v1/api-keys")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Get connection token
        mockMvc.perform(get("/v1/api-keys/connection-token")
                .param("apiKey", apiKey)
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(emptyString())));
    }

    @Test
    void shouldGetSubscriptionTokenForValidApiKey() throws Exception {
        // Create API key
        String apiKey = mockMvc.perform(put("/v1/api-keys")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Get subscription token
        mockMvc.perform(get("/v1/api-keys/subscription-token")
                .param("apiKey", apiKey)
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(emptyString())));
    }

    @Test
    void shouldReturn401ForInvalidApiKeyConnectionToken() throws Exception {
        // Create a key first (so the record exists)
        mockMvc.perform(put("/v1/api-keys")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk());

        // Try to get connection token with wrong API key
        mockMvc.perform(get("/v1/api-keys/connection-token")
                .param("apiKey", "invalid-api-key")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401ForInvalidApiKeySubscriptionToken() throws Exception {
        // Create a key first (so the record exists)
        mockMvc.perform(put("/v1/api-keys")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isOk());

        // Try to get subscription token with wrong API key
        mockMvc.perform(get("/v1/api-keys/subscription-token")
                .param("apiKey", "invalid-api-key")
                .param("serviceId", testServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401ForNonExistentServiceConnectionToken() throws Exception {
        UUID randomServiceId = UUID.randomUUID();

        mockMvc.perform(get("/v1/api-keys/connection-token")
                .param("apiKey", "any-key")
                .param("serviceId", randomServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401ForNonExistentServiceSubscriptionToken() throws Exception {
        UUID randomServiceId = UUID.randomUUID();

        mockMvc.perform(get("/v1/api-keys/subscription-token")
                .param("apiKey", "any-key")
                .param("serviceId", randomServiceId.toString())
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn400ForMissingServiceId() throws Exception {
        mockMvc.perform(get("/v1/api-keys")
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400ForInvalidServiceId() throws Exception {
        mockMvc.perform(get("/v1/api-keys")
                .param("serviceId", "not-a-uuid")
                .param("environmentId", String.valueOf(DEV_ENV_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400ForMissingEnvironmentId() throws Exception {
        mockMvc.perform(get("/v1/api-keys")
                .param("serviceId", testServiceId.toString()))
                .andExpect(status().isBadRequest());
    }
}
