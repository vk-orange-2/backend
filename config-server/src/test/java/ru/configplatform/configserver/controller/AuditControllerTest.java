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
import ru.configplatform.configserver.dto.RollbackRequest;
import ru.configplatform.configserver.dto.UpdateConfigRequest;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void shouldLogCreateOperation() throws Exception {
        createConfig("audit-create-svc", "dev", "key1", "val1", "creator-user");

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "audit-create-svc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.entries[0].operation", is("CREATE")))
                .andExpect(jsonPath("$.entries[0].actor", is("creator-user")))
                .andExpect(jsonPath("$.entries[0].versionAfter", is(1)));
    }

    @Test
    void shouldLogUpdateWithDiff() throws Exception {
        String id = createConfigAndReturnId("audit-update-svc", "dev", "upd-key",
                Map.of("a", 1, "b", 2), "user-a");

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("a", 1, "c", 3))
                .expectedVersion(1L).build();

        mockMvc.perform(put("/v1/configs/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Author", "user-b")
                .content(objectMapper.writeValueAsString(update)));

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "audit-update-svc")
                        .param("operation", "UPDATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].operation", is("UPDATE")))
                .andExpect(jsonPath("$.entries[0].actor", is("user-b")))
                .andExpect(jsonPath("$.entries[0].diff").exists())
                .andExpect(jsonPath("$.entries[0].diff.added.c", is(3)))
                .andExpect(jsonPath("$.entries[0].diff.removed.b", is(2)));
    }

    @Test
    void shouldLogDeleteOperation() throws Exception {
        String id = createConfigAndReturnId("audit-del-svc", "dev", "del-key",
                "val", "user-c");

        DeleteConfigRequest deleteReq = DeleteConfigRequest.builder()
                .expectedVersion(1L).build();

        mockMvc.perform(delete("/v1/configs/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Author", "user-d")
                .content(objectMapper.writeValueAsString(deleteReq)));

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "audit-del-svc")
                        .param("operation", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].operation", is("DELETE")))
                .andExpect(jsonPath("$.entries[0].actor", is("user-d")));
    }

    @Test
    void shouldLogRollbackOperation() throws Exception {
        String id = createConfigAndReturnId("audit-rb-svc", "dev", "rb-key",
                Map.of("v", 1), "user-e");

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L).build();

        mockMvc.perform(put("/v1/configs/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Author", "user-e")
                .content(objectMapper.writeValueAsString(update)));

        RollbackRequest rollback = RollbackRequest.builder()
                .targetVersion(1L)
                .expectedVersion(2L).build();

        mockMvc.perform(post("/v1/configs/" + id + "/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Author", "deployer")
                .content(objectMapper.writeValueAsString(rollback)));

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "audit-rb-svc")
                        .param("operation", "ROLLBACK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].operation", is("ROLLBACK")))
                .andExpect(jsonPath("$.entries[0].actor", is("deployer")))
                .andExpect(jsonPath("$.entries[0].diff").exists());
    }

    @Test
    void shouldFilterByActor() throws Exception {
        createConfig("audit-actor-svc", "dev", "k1", "v1", "alice");
        createConfig("audit-actor-svc", "dev", "k2", "v2", "bob");

        mockMvc.perform(get("/v1/audit")
                        .param("actor", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[*].actor",
                        everyItem(is("alice"))));
    }

    @Test
    void shouldFilterByTimeRange() throws Exception {
        createConfig("audit-time-svc", "dev", "tk", "tv", "user");

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "audit-time-svc")
                        .param("from", "2099-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    void shouldPaginateResults() throws Exception {
        for (int i = 0; i < 5; i++) {
            createConfig("audit-page-svc", "dev", "pk-" + i, "pv-" + i, "user");
        }

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "audit-page-svc")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.totalCount", is(5)));
    }

    private void createConfig(String service, String env, String key,
                              Object value, String author) throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service(service).env(env).key(key).value(value).build();

        mockMvc.perform(post("/v1/configs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Author", author)
                .content(objectMapper.writeValueAsString(request)));
    }

    private String createConfigAndReturnId(String service, String env, String key,
                                           Object value, String author) throws Exception {
        CreateConfigRequest request = CreateConfigRequest.builder()
                .service(service).env(env).key(key).value(value).build();

        MvcResult result = mockMvc.perform(post("/v1/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", author)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }
}
