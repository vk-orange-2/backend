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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCreateConfigAndReturnVersion1() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("auth-service")
                .env("dev")
                .key("max-retries")
                .value("3")
                .build();

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.service", is("auth-service")))
                .andExpect(jsonPath("$.env", is("dev")))
                .andExpect(jsonPath("$.key", is("max-retries")))
                .andExpect(jsonPath("$.value", is("3")))
                .andExpect(jsonPath("$.version", is(1)));
    }

    @Test
    void shouldIncrementVersionOnUpdate() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("auth-service")
                .env("dev")
                .key("timeout-ms")
                .value("1000")
                .build();

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(1)));

        request.setValue("2000");

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value", is("2000")))
                .andExpect(jsonPath("$.version", is(2)));
    }

    @Test
    void shouldReturnConfigsByServiceAndEnv() throws Exception {
        CreateConfigRequest req1 = CreateConfigRequest.builder()
                .service("payment-service")
                .env("prod")
                .key("api-url")
                .value("https://api.payments.com")
                .build();

        CreateConfigRequest req2 = CreateConfigRequest.builder()
                .service("payment-service")
                .env("prod")
                .key("timeout")
                .value("5000")
                .build();

        mockMvc.perform(post("/v1/configs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)));

        mockMvc.perform(post("/v1/configs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)));

        mockMvc.perform(get("/v1/configs")
                        .param("serviceName", "payment-service")
                        .param("environment", "prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void shouldReturn400WhenServiceMissing() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("")
                .env("dev")
                .key("some-key")
                .value("some-value")
                .build();

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", is("VALIDATION_ERROR")));
    }

    @Test
    void shouldReturn400WhenQueryParamMissing() throws Exception {
        mockMvc.perform(get("/v1/configs")
                        .param("serviceName", "some-service"))
                .andExpect(status().isBadRequest());
    }
}
