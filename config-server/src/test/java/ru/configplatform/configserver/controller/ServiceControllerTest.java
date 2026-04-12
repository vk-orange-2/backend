package ru.configplatform.configserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.configplatform.configserver.dto.CreateConfigRequest;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldReturnEmptyListWhenNoConfigs() throws Exception {
        mockMvc.perform(get("/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void shouldReturnListOfServices() throws Exception {
        createConfig("order-service", "dev", "timeout", "3000");
        createConfig("notification-service", "dev", "retry-count", "5");

        mockMvc.perform(get("/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$", hasItem("notification-service")))
                .andExpect(jsonPath("$", hasItem("order-service")));
    }

    @Test
    void shouldNotReturnDuplicates() throws Exception {
        createConfig("dedup-service", "dev", "key-a", "value-a");
        createConfig("dedup-service", "dev", "key-b", "value-b");
        createConfig("dedup-service", "prod", "key-a", "value-c");

        mockMvc.perform(get("/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("dedup-service")));
    }

    private void createConfig(String service, String env, String key, String value)
            throws Exception {

        CreateConfigRequest request = CreateConfigRequest.builder()
                .service(service)
                .env(env)
                .key(key)
                .value(value)
                .build();

        mockMvc.perform(post("/v1/configs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }
}
