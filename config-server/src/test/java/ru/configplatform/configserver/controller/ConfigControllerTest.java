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
import ru.configplatform.configserver.dto.DeleteConfigRequest;
import ru.configplatform.configserver.dto.RollbackRolloutRequest;
import ru.configplatform.configserver.dto.UpdateConfigRequest;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String DEFAULT_AUTHOR_HEADER = "test-user";

    @Test
    void shouldCreateConfigAndReturnVersion1() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("test-auth")
                .env("dev")
                .key("max-retries")
                .value(Map.of("retries", 3))
                .isSecret(false)
                .build();

        mockMvc.perform(post("/v1/configs")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.configKey", is("max-retries")))
                .andExpect(jsonPath("$.service", is("test-auth")))
                .andExpect(jsonPath("$.environment", is("dev")))
                .andExpect(jsonPath("$.isSecret", is(false)))
                .andExpect(jsonPath("$.status", is("active")))
                .andExpect(jsonPath("$.currentVersion", is(1)))
                .andExpect(jsonPath("$.latestVersion.payload.retries", is(3)));
    }

    @Test
    void shouldCreateSecretConfig() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("test-secret-svc")
                .env("prod")
                .key("db-password")
                .value("super-secret")
                .isSecret(true)
                .build();

        mockMvc.perform(post("/v1/configs")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSecret", is(true)));
    }

    @Test
    void shouldCreateConfigWithAuthor() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("test-author-svc")
                .env("dev")
                .key("author-key")
                .value(Map.of("a", 1))
                .build();

        mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "test-user")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersion", is(1)));
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
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersion", is(1)));

        request.setValue("2000");
        request.setExpectedVersion(1L);

        mockMvc.perform(post("/v1/configs")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion", is(2)))
                .andExpect(jsonPath("$.latestVersion.payload", is("2000")));
    }

    @Test
    void shouldGetConfigById() throws Exception {
        String id = createConfigAndReturnId("test-getbyid-svc", "dev", "some-key",
                Map.of("a", 1));

        mockMvc.perform(get("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)))
                .andExpect(jsonPath("$.configKey", is("some-key")))
                .andExpect(jsonPath("$.latestVersion.payload.a", is(1)));
    }

    @Test
    void shouldReturn404ForNonexistentConfig() throws Exception {
        mockMvc.perform(get("/v1/configs/" + UUID.randomUUID())
                        .header("X-Author", DEFAULT_AUTHOR_HEADER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("NOT_FOUND")));
    }

    @Test
    void shouldReturnConfigsByServiceAndEnv() throws Exception {
        createConfig("test-payment", "prod", "api-url", "https://api.pay.com");
        createConfig("test-payment", "prod", "timeout", "5000");

        mockMvc.perform(get("/v1/configs")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .param("serviceName", "test-payment")
                        .param("environment", "prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs", hasSize(2)));
    }

    @Test
    void shouldReturnEmptyListForUnknownService() throws Exception {
        mockMvc.perform(get("/v1/configs")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .param("serviceName", "nonexistent-" + UUID.randomUUID())
                        .param("environment", "dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs", hasSize(0)));
    }

    @Test
    void shouldNotReturnDeletedConfigsInList() throws Exception {
        String id = createConfigAndReturnId("test-list-del-svc", "dev", "del-key", "val");

        mockMvc.perform(delete("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(1L)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/v1/configs")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .param("serviceName", "test-list-del-svc")
                        .param("environment", "dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs", hasSize(0)));
    }

    @Test
    void shouldUpdateConfigById() throws Exception {
        String id = createConfigAndReturnId(
                "test-update-svc", "dev", "upd-key", Map.of("old", true)
        );

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("new", true))
                .expectedVersion(1L)
                .build();

        mockMvc.perform(put("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion", is(2)))
                .andExpect(jsonPath("$.latestVersion.payload.new", is(true)));
    }

    @Test
    void shouldReturn404WhenUpdatingDeletedConfig() throws Exception {
        String id = createConfigAndReturnId("test-upd-del-svc", "dev", "key1", "val1");

        mockMvc.perform(delete("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(1L)))
                .andExpect(status().isNoContent());

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value("new-value")
                .expectedVersion(1L)
                .build();

        mockMvc.perform(put("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isNotFound());
    }


    @Test
    void shouldReturnVersionHistory() throws Exception {
        String id = createConfigAndReturnId("test-hist-svc", "dev", "hist-key",
                Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L).build();

        mockMvc.perform(put("/v1/configs/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Author", "editor")
                .content(objectMapper.writeValueAsString(update)));

        mockMvc.perform(get("/v1/configs/" + id + "/versions")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(2)))
                .andExpect(jsonPath("$.versions[0].version", is(2)))
                .andExpect(jsonPath("$.versions[0].changeType", is("update")))
                .andExpect(jsonPath("$.versions[0].author", is("editor")))
                .andExpect(jsonPath("$.versions[1].version", is(1)))
                .andExpect(jsonPath("$.versions[1].changeType", is("create")));
    }

    @Test
    void shouldReturnSpecificVersion() throws Exception {
        String id = createConfigAndReturnId("test-spec-svc", "dev", "spec-key",
                Map.of("original", true));

        mockMvc.perform(get("/v1/configs/" + id + "/versions/1")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.payload.original", is(true)));
    }

    @Test
    void shouldReturn404ForNonexistentVersion() throws Exception {
        String id = createConfigAndReturnId("test-ver404-svc", "dev", "ver404-key", "val");

        mockMvc.perform(get("/v1/configs/" + id + "/versions/999")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("VERSION_NOT_FOUND")));
    }

    @Test
    void shouldSoftDeleteConfig() throws Exception {
        String id = createConfigAndReturnId("test-del-svc", "dev", "delete-me", "value");

        mockMvc.perform(delete("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(1L)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn404WhenDeletingNonexistent() throws Exception {
        mockMvc.perform(delete("/v1/configs/" + UUID.randomUUID())
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(1L)))
                .andExpect(status().isNotFound());
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
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
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
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400WhenMissingQueryParams() throws Exception {
        mockMvc.perform(get("/v1/configs")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .param("serviceName", "some-service"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn409WhenExpectedVersionDoesNotMatch() throws Exception {
        String id = createConfigAndReturnId("test-conflict-svc", "dev",
                "conflict-key", Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(5L)
                .build();

        mockMvc.perform(put("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("VERSION_CONFLICT")))
                .andExpect(jsonPath("$.error.expectedVersion", is(5)))
                .andExpect(jsonPath("$.error.actualVersion", is(1)));
    }

    @Test
    void shouldReturn409OnConcurrentUpdate() throws Exception {
        String id = createConfigAndReturnId("test-concurrent-svc", "dev",
                "race-key", Map.of("v", 1));

        UpdateConfigRequest updateA = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();

        mockMvc.perform(put("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion", is(2)));

        UpdateConfigRequest updateB = UpdateConfigRequest.builder()
                .value(Map.of("v", 3))
                .expectedVersion(1L)
                .build();

        mockMvc.perform(put("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateB)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("VERSION_CONFLICT")))
                .andExpect(jsonPath("$.error.expectedVersion", is(1)))
                .andExpect(jsonPath("$.error.actualVersion", is(2)));
    }

    @Test
    void shouldReturn409OnDeleteWithStaleVersion() throws Exception {
        String id = createConfigAndReturnId("test-del-conflict-svc", "dev",
                "del-ver-key", "value");

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value("new-value")
                .expectedVersion(1L)
                .build();

        mockMvc.perform(put("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk());

        DeleteConfigRequest deleteReq = DeleteConfigRequest.builder()
                .expectedVersion(1L)
                .build();

        mockMvc.perform(delete("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteReq)))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldDeleteSuccessfullyWithCorrectVersion() throws Exception {
        String id = createConfigAndReturnId("test-del-ok-svc", "dev",
                "del-ok-key", "value");

        DeleteConfigRequest deleteReq = DeleteConfigRequest.builder()
                .expectedVersion(1L)
                .build();

        mockMvc.perform(delete("/v1/configs/" + id)
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteReq)))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturnDiffBetweenVersions() throws Exception {
        String id = createConfigAndReturnId("test-diff-svc", "dev", "diff-key",
                Map.of("a", 1, "b", 2));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("a", 1, "c", 3))
                .expectedVersion(1L).build();

        mockMvc.perform(put("/v1/configs/" + id)
                .header("X-Author", DEFAULT_AUTHOR_HEADER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        mockMvc.perform(get("/v1/configs/" + id + "/diff")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .param("from", "1")
                        .param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionFrom", is(1)))
                .andExpect(jsonPath("$.versionTo", is(2)))
                .andExpect(jsonPath("$.added.c", is(3)))
                .andExpect(jsonPath("$.removed.b", is(2)))
                .andExpect(jsonPath("$.changed").isEmpty());
    }

    @Test
    void shouldReturn400ForMissingKey() throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service("svc").env("dev").key("").value("val").build();

        mockMvc.perform(post("/v1/configs")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    private void createConfig(String service, String env, String key, Object value) throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service(service)
                .env(env)
                .key(key)
                .value(value)
                .build();

        mockMvc.perform(post("/v1/configs")
                .header("X-Author", DEFAULT_AUTHOR_HEADER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private String createConfigAndReturnId(String service, String env, String key, Object value) throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service(service)
                .env(env)
                .key(key)
                .value(value)
                .build();

        MvcResult result = mockMvc.perform(post("/v1/configs")
                        .header("X-Author", DEFAULT_AUTHOR_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    @Test
    void shouldReturn429WhenUserLimitExceeded()
            throws Exception {

        var request =
                CreateConfigRequest.builder()
                        .service("svc")
                        .env("dev")
                        .key(UUID.randomUUID().toString())
                        .value(Map.of("a",1));

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(
                    post("/v1/configs")
                            .header("X-Author","ivan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    objectMapper.writeValueAsString(
                                            request
                                                    .expectedVersion((long)i)
                                                    .build()
                                    )
                            )
            );
        }

        mockMvc.perform(
                post("/v1/configs")
                        .header("X-Author","ivan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                objectMapper.writeValueAsString(
                                        request
                                                .expectedVersion(10L)
                                                .build()
                                )
                        )
                )
                .andExpect(status().isTooManyRequests())
                .andExpect(
                        header().exists("Retry-After")
                );
    }
}
