package ru.configplatform.configserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.CreateConfigRequest;
import ru.configplatform.configserver.model.EnvironmentEntity;
import ru.configplatform.configserver.repository.EnvironmentRepository;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @BeforeEach
    void setUp() {
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
    }

    @Test
    void shouldCreateConfigAndReturnVersion1() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("test-auth")
                .env("dev")
                .key("max-retries")
                .value(Map.of("retries", 3))
                .build();

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configKey", is("max-retries")))
                .andExpect(jsonPath("$.currentVersion", is(1)))
                .andExpect(jsonPath("$.latestVersion.payload.retries", is(3)));
    }

    @Test
    void shouldIncrementVersionOnUpdate() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("test-auth")
                .env("dev")
                .key("timeout-ms")
                .value("1000")
                .build();

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersion", is(1)));

        request.setValue("2000");

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion.payload", is("2000")))
                .andExpect(jsonPath("$.currentVersion", is(2)));
    }

    @Test
    void shouldReturnConfigsByServiceAndEnv() throws Exception {
        createConfig("test-payment", "prod", "api-url", "https://api.pay.com");
        createConfig("test-payment", "prod", "timeout", "5000");

        mockMvc.perform(get("/v1/configs")
                        .param("serviceName", "test-payment")
                        .param("environment", "prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs", hasSize(2)));
    }

    @Test
    void shouldReturnEmptyListForUnknownService() throws Exception {
        mockMvc.perform(get("/v1/configs")
                        .param("serviceName", "nonexistent-service")
                        .param("environment", "dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs", hasSize(0)));
    }

    @Test
    void shouldReturn400ForInvalidEnvironment() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("test-svc")
                .env("invalid-env")
                .key("some-key")
                .value("some-value")
                .build();

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", is("BAD_REQUEST")));
    }

    @Test
    void shouldReturn400WhenKeyMissing() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("test-svc")
                .env("dev")
                .key("")
                .value("some-value")
                .build();

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    private void createConfig(String service, String env, String key, Object value)
            throws Exception {

        CreateConfigRequest request = CreateConfigRequest.builder()
                .service(service)
                .env(env)
                .key(key)
                .value(value)
                .build();

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful());
    }
}
