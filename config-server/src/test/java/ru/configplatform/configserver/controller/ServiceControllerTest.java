package ru.configplatform.configserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.CreateConfigRequest;
import ru.configplatform.configserver.dto.CreateRolloutRequest;
import ru.configplatform.configserver.dto.CreateServiceRequest;
import ru.configplatform.configserver.dto.UpdateConfigRequest;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldReturnServicesAfterCreatingConfigs() throws Exception {
        // Создаем конфиги для двух разных сервисов в окружении dev
        createConfigAndReturnId("svc-order", "dev", "key1", "val1");
        createConfigAndReturnId("svc-notification", "dev", "key2", "val2");

        mockMvc.perform(get("/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name", is("svc-order")))
                .andExpect(jsonPath("$[1].name", is("svc-notification")));
    }

    @Test
    void shouldNotReturnDuplicates() throws Exception {
        // Один сервис, но разные ключи в одном окружении
        createConfigAndReturnId("dedup-service", "dev", "key-a", "value-a");
        createConfigAndReturnId("dedup-service", "dev", "key-b", "value-b");
        // Тот же сервис в другом окружении — не должен дублироваться
        createConfigAndReturnId("dedup-service", "prod", "key-a", "value-c");

        mockMvc.perform(get("/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("dedup-service")));
    }

    @Test
    void shouldCreateService() throws Exception {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .name("new-service-" + UUID.randomUUID())
                .description("Test service")
                .build();

        mockMvc.perform(post("/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is(request.getName())))
                .andExpect(jsonPath("$.description", is("Test service")))
                .andExpect(jsonPath("$.id", notNullValue()));
    }

    @Test
    void shouldReturn409WhenServiceAlreadyExists() throws Exception {
        String name = "duplicate-svc-" + UUID.randomUUID();
        CreateServiceRequest request = CreateServiceRequest.builder()
                .name(name)
                .build();

        mockMvc.perform(post("/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("SERVICE_ALREADY_EXISTS")));
    }

    @Test
    void shouldReturn400WhenServiceNameBlank() throws Exception {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .name("")
                .build();

        mockMvc.perform(post("/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnServiceEnvState() throws Exception {
        String configId = createConfigAndReturnId("state-svc", "dev", "state-key",
                Map.of("v", 1));

        mockMvc.perform(get("/v1/services/state-svc/envs/dev/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceName", is("state-svc")))
                .andExpect(jsonPath("$.environment", is("dev")))
                .andExpect(jsonPath("$.configs", hasSize(1)))
                .andExpect(jsonPath("$.configs[0].configKey", is("state-key")))
                .andExpect(jsonPath("$.configs[0].globalVersion", is(1)))
                .andExpect(jsonPath("$.configs[0].globalPayload.v", is(1)));
    }

    @Test
    void shouldReturnServiceEnvStateWithCanary() throws Exception {
        String configId = createConfigAndReturnId("state-canary-svc", "dev", "state-canary-key",
                Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest canary = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("canary")
                .canaryPercentage(10)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(canary)));

        mockMvc.perform(get("/v1/services/state-canary-svc/envs/dev/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs[0].canary.percentage", is(10)))
                .andExpect(jsonPath("$.configs[0].canary.canaryVersion", is(2)));
    }

    @Test
    void shouldReturnEmptyStateForUnknownService() throws Exception {
        mockMvc.perform(get("/v1/services/unknown-svc-" + UUID.randomUUID() + "/envs/dev/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs", hasSize(0)));
    }

    private String createConfigAndReturnId(String service, String env, String key, Object value) throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service(service)
                .env(env)
                .key(key)
                .value(value)
                .build();

        MvcResult result = mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }
}
