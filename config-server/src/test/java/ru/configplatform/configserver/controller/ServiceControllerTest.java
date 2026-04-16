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
class ServiceControllerTest {

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
    void shouldReturnServicesAfterCreatingConfigs() throws Exception {
        // Создаем конфиги для двух разных сервисов в окружении dev
        createConfig("svc-order", "dev", "key1", "val1");
        createConfig("svc-notification", "dev", "key2", "val2");

        mockMvc.perform(get("/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name", is("svc-order")))
                .andExpect(jsonPath("$[1].name", is("svc-notification")));
    }

    @Test
    void shouldNotReturnDuplicates() throws Exception {
        // Один сервис, но разные ключи в одном окружении
        createConfig("dedup-service", "dev", "key-a", "value-a");
        createConfig("dedup-service", "dev", "key-b", "value-b");
        // Тот же сервис в другом окружении — не должен дублироваться
        createConfig("dedup-service", "prod", "key-a", "value-c");

        mockMvc.perform(get("/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("dedup-service")));
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
