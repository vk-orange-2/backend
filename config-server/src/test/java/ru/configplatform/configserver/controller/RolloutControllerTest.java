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
import ru.configplatform.configserver.dto.UpdateConfigRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RolloutControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCreateInstantRolloutAndComplete() throws Exception {
        String configId = createConfigAndReturnId("rollout-instant-svc", "dev", "instant-key",
                Map.of("a", 1));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("instant")
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.type", is("instant")))
                .andExpect(jsonPath("$.status", is("completed")))
                .andExpect(jsonPath("$.configId", is(configId)))
                .andExpect(jsonPath("$.baselineVersion", is(0)))
                .andExpect(jsonPath("$.targetVersion", is(1)))
                .andExpect(jsonPath("$.currentDeployment", is(1)))
                .andExpect(jsonPath("$.totalDeployments", is(1)));
    }

    @Test
    void shouldCreateGradualRolloutInProgress() throws Exception {
        String configId = createConfigAndReturnId("rollout-gradual-svc", "dev", "gradual-key",
                Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(4)
                .deploymentIntervalSeconds(60)
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("gradual")))
                .andExpect(jsonPath("$.status", is("in_progress")))
                .andExpect(jsonPath("$.totalDeployments", is(4)))
                .andExpect(jsonPath("$.currentDeployment", is(1)))  // первый deployment сразу
                .andExpect(jsonPath("$.baselineVersion", is(1)))
                .andExpect(jsonPath("$.targetVersion", is(2)));
    }

    @Test
    void shouldDeployNextManually() throws Exception {
        String configId = createConfigAndReturnId("rollout-deploy-svc", "dev", "deploy-key",
                Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(3)
                .deploymentIntervalSeconds(0) // без задержки для теста
                .build();

        MvcResult result = mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String rolloutId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        // Deployment 2
        mockMvc.perform(post("/v1/rollouts/" + rolloutId + "/deploy-next")
                        .header("X-Author", "deployer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDeployment", is(2)))
                .andExpect(jsonPath("$.status", is("in_progress")));

        // Deployment 3 (last)
        mockMvc.perform(post("/v1/rollouts/" + rolloutId + "/deploy-next")
                        .header("X-Author", "deployer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDeployment", is(3)))
                .andExpect(jsonPath("$.status", is("completed")));
    }

    @Test
    void shouldStopGradualRollout() throws Exception {
        String configId = createConfigAndReturnId("rollout-stop-svc", "dev", "stop-key",
                Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(5)
                .deploymentIntervalSeconds(60)
                .build();

        MvcResult result = mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String rolloutId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/rollouts/" + rolloutId + "/stop")
                        .header("X-Author", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("stopped")))
                .andExpect(jsonPath("$.currentDeployment", is(1)));  // остановили после 1-го
    }

    @Test
    void shouldReturn409WhenStoppingCompletedRollout() throws Exception {
        String configId = createConfigAndReturnId("rollout-stop-done-svc", "dev", "stop-done-key",
                Map.of("v", 1));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("instant")
                .build();

        MvcResult result = mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String rolloutId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/rollouts/" + rolloutId + "/stop"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("ROLLOUT_NOT_ACTIVE")));
    }

    @Test
    void shouldRollbackGradualRollout() throws Exception {
        String configId = createConfigAndReturnId("rollout-rb-svc", "dev", "rb-key",
                Map.of("original", true));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("modified", true))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(5)
                .deploymentIntervalSeconds(60)
                .build();

        MvcResult result = mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String rolloutId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        // Rollback rollout
        mockMvc.perform(post("/v1/rollouts/" + rolloutId + "/rollback")
                        .header("X-Author", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("rolled_back")));

        // Конфиг должен вернуться к baseline (version 1, original payload)
        mockMvc.perform(get("/v1/configs/" + configId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion", is(3)))  // новая версия после rollback
                .andExpect(jsonPath("$.latestVersion.payload.original", is(true)));

        // Проверяем, что создалась новая версия
        mockMvc.perform(get("/v1/configs/" + configId + "/versions"))
                .andExpect(jsonPath("$.versions", hasSize(3)))
                .andExpect(jsonPath("$.versions[0].changeType", is("rollback")));
    }

    @Test
    void shouldReturn409OnRollbackCompletedRollout() throws Exception {
        String configId = createConfigAndReturnId("rollout-rb-done-svc", "dev", "rb-done-key",
                Map.of("v", 1));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("instant")
                .build();

        MvcResult result = mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String rolloutId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/rollouts/" + rolloutId + "/rollback"))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldReturn409WhenUpdatingConfigWithActiveRollout() throws Exception {
        String configId = createConfigAndReturnId("rollout-block-svc", "dev", "block-key",
                Map.of("v", 1));

        UpdateConfigRequest update1 = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update1)));

        // Создаём gradual rollout
        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(5)
                .deploymentIntervalSeconds(60)
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated());

        // Попытка обновить конфиг — должна быть заблокирована
        UpdateConfigRequest update2 = UpdateConfigRequest.builder()
                .value(Map.of("v", 3))
                .expectedVersion(2L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("ACTIVE_ROLLOUT_EXISTS")));
    }

    @Test
    void shouldReturn409WhenCreatingDuplicateRollout() throws Exception {
        String configId = createConfigAndReturnId("rollout-dup-svc", "dev", "dup-key",
                Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(5)
                .deploymentIntervalSeconds(60)
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated());

        // Второй rollout
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("ACTIVE_ROLLOUT_EXISTS")));
    }

    @Test
    void shouldGetRolloutById() throws Exception {
        String configId = createConfigAndReturnId("rollout-get-svc", "dev", "get-key",
                Map.of("v", 1));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("instant")
                .build();

        MvcResult result = mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String rolloutId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/v1/rollouts/" + rolloutId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(rolloutId)));
    }

    @Test
    void shouldReturn404ForNonexistentRollout() throws Exception {
        mockMvc.perform(get("/v1/rollouts/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("ROLLOUT_NOT_FOUND")));
    }

    @Test
    void shouldGetActiveRolloutForConfig() throws Exception {
        String configId = createConfigAndReturnId("rollout-active-svc", "dev", "active-key",
                Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(5)
                .deploymentIntervalSeconds(60)
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/configs/" + configId + "/active-rollout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("in_progress")))
                .andExpect(jsonPath("$.totalDeployments", is(5)))
                .andExpect(jsonPath("$.currentDeployment", is(1)));
    }

    @Test
    void shouldReturn204WhenNoActiveRollout() throws Exception {
        String configId = createConfigAndReturnId("rollout-noactive-svc", "dev", "noactive-key",
                Map.of("v", 1));

        mockMvc.perform(get("/v1/configs/" + configId + "/active-rollout"))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldAuditRolloutStart() throws Exception {
        String configId = createConfigAndReturnId("rollout-audit-svc", "dev", "audit-key",
                Map.of("v", 1));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("instant")
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "rollout-audit-svc")
                        .param("operation", "ROLLOUT_START"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.entries[0].operation", is("ROLLOUT_START")))
                .andExpect(jsonPath("$.entries[0].actor", is("deployer")));
    }

    @Test
    void shouldAuditRolloutStop() throws Exception {
        String configId = createConfigAndReturnId("rollout-audit-stop-svc", "dev", "audit-stop-key",
                Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(5)
                .deploymentIntervalSeconds(60)
                .build();

        MvcResult result = mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String rolloutId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/rollouts/" + rolloutId + "/stop")
                .header("X-Author", "admin"));

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "rollout-audit-stop-svc")
                        .param("operation", "ROLLOUT_STOP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.entries[0].actor", is("admin")));
    }

    @Test
    void shouldAuditRolloutRollback() throws Exception {
        String configId = createConfigAndReturnId("rollout-audit-rb-svc", "dev", "audit-rb-key",
                Map.of("original", true));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("modified", true))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(5)
                .deploymentIntervalSeconds(60)
                .build();

        MvcResult result = mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String rolloutId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/rollouts/" + rolloutId + "/rollback")
                .header("X-Author", "admin"));

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "rollout-audit-rb-svc")
                        .param("operation", "ROLLOUT_ROLLBACK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(greaterThanOrEqualTo(1))));

        mockMvc.perform(get("/v1/audit")
                        .param("serviceName", "rollout-audit-rb-svc")
                        .param("operation", "ROLLBACK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void shouldReturn400ForInvalidRolloutType() throws Exception {
        String configId = createConfigAndReturnId("rollout-invalid-svc", "dev", "invalid-key",
                Map.of("v", 1));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("unknown")
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldGetActiveRolloutsForServiceAndEnvironment() throws Exception {
        String configId = createConfigAndReturnId("rollout-active-svc", "dev", "active-key",
                Map.of("v", 1));
        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("gradual")
                .totalDeployments(5)
                .deploymentIntervalSeconds(60)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/rollouts/active")
                        .param("serviceName", "rollout-active-svc")
                        .param("environment", "dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status", is("in_progress")))
                .andExpect(jsonPath("$[0].totalDeployments", is(5)))
                .andExpect(jsonPath("$[0].currentDeployment", is(1)))
                .andExpect(jsonPath("$[0].configId", is(configId)));
    }

    @Test
    void shouldReturnEmptyListWhenNoActiveRollouts() throws Exception {
        createConfigAndReturnId("rollout-noactive-svc", "dev", "noactive-key",
                Map.of("v", 1));

        mockMvc.perform(get("/v1/rollouts/active")
                        .param("serviceName", "rollout-noactive-svc")
                        .param("environment", "dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldReturnMultipleActiveRolloutsForServiceEnv() throws Exception {
        // Два разных конфига в одном service+env — оба с активными роллаутами
        String configId1 = createConfigAndReturnId("rollout-multi-svc", "dev", "key-1", Map.of("v", 1));
        String configId2 = createConfigAndReturnId("rollout-multi-svc", "dev", "key-2", Map.of("v", 1));

        // Делаем gradual (он остаётся in_progress)
        mockMvc.perform(put("/v1/configs/" + configId1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        UpdateConfigRequest.builder().value(Map.of("v", 2)).expectedVersion(1L).build()
                )));
        mockMvc.perform(put("/v1/configs/" + configId2)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        UpdateConfigRequest.builder().value(Map.of("v", 2)).expectedVersion(1L).build()
                )));

        for (String cid : List.of(configId1, configId2)) {
            CreateRolloutRequest req = CreateRolloutRequest.builder()
                    .configId(UUID.fromString(cid))
                    .type("gradual")
                    .totalDeployments(5)
                    .deploymentIntervalSeconds(60)
                    .build();
            mockMvc.perform(post("/v1/rollouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }

        // И ещё один rollout в ДРУГОМ окружении того же сервиса — он не должен попасть в выдачу
        String configIdProd = createConfigAndReturnId("rollout-multi-svc", "prod", "key-1", Map.of("v", 1));
        mockMvc.perform(put("/v1/configs/" + configIdProd)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        UpdateConfigRequest.builder().value(Map.of("v", 2)).expectedVersion(1L).build()
                )));
        mockMvc.perform(post("/v1/rollouts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        CreateRolloutRequest.builder()
                                .configId(UUID.fromString(configIdProd))
                                .type("gradual")
                                .totalDeployments(3)
                                .deploymentIntervalSeconds(60)
                                .build()
                )));

        mockMvc.perform(get("/v1/rollouts/active")
                        .param("serviceName", "rollout-multi-svc")
                        .param("environment", "dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void shouldNotReturnCompletedRolloutsInActiveList() throws Exception {
        String configId = createConfigAndReturnId("rollout-completed-svc", "dev", "k", Map.of("v", 1));
        // instant сразу completed
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                CreateRolloutRequest.builder()
                                        .configId(UUID.fromString(configId))
                                        .type("instant")
                                        .build()
                        )))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/rollouts/active")
                        .param("serviceName", "rollout-completed-svc")
                        .param("environment", "dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldCreateCanaryRollout() throws Exception {
        String configId = createConfigAndReturnId("canary-svc", "dev", "canary-key",
                Map.of("v", 1));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("canary")
                .canaryPercentage(5)
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.type", is("canary")))
                .andExpect(jsonPath("$.status", is("completed")))
                .andExpect(jsonPath("$.canaryPercentage", is(5)))
                .andExpect(jsonPath("$.configId", is(configId)))
                .andExpect(jsonPath("$.targetVersion", is(1)));
    }

    @Test
    void shouldReturn400ForCanaryWithoutPercentage() throws Exception {
        String configId = createConfigAndReturnId("canary-nopct-svc", "dev", "canary-nopct-key",
                Map.of("v", 1));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("canary")
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRollbackCanaryRollout() throws Exception {
        String configId = createConfigAndReturnId("canary-rb-svc", "dev", "canary-rb-key",
                Map.of("original", true));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("canary", true))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest rolloutReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("canary")
                .canaryPercentage(10)
                .build();

        MvcResult result = mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Author", "deployer")
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String rolloutId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        // Rollback the canary (it's in completed state)
        mockMvc.perform(post("/v1/rollouts/" + rolloutId + "/rollback")
                        .header("X-Author", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("rolled_back")));

        // Config version should be incremented (rollback creates new version)
        mockMvc.perform(get("/v1/configs/" + configId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion", is(3)))
                .andExpect(jsonPath("$.latestVersion.payload.original", is(true)));
    }

    @Test
    void shouldAllowInstantAfterCanary() throws Exception {
        String configId = createConfigAndReturnId("canary-promote-svc", "dev", "canary-promote-key",
                Map.of("v", 1));

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        // Create canary
        CreateRolloutRequest canaryReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("canary")
                .canaryPercentage(5)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canaryReq)))
                .andExpect(status().isCreated());

        // Now promote via instant (same config) → should work
        // First need a new version
        UpdateConfigRequest update2 = UpdateConfigRequest.builder()
                .value(Map.of("v", 3))
                .expectedVersion(2L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update2)));

        CreateRolloutRequest instantReq = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("instant")
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(instantReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("instant")))
                .andExpect(jsonPath("$.status", is("completed")));
    }

    @Test
    void shouldAllowCanaryOnSamePercentageForDifferentConfig() throws Exception {
        // Config 1 with canary at 10%
        String configId1 = createConfigAndReturnId("canary-multi-svc", "dev", "key-1",
                Map.of("v", 1));
        CreateRolloutRequest canary1 = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId1))
                .type("canary")
                .canaryPercentage(10)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canary1)))
                .andExpect(status().isCreated());

        // Config 2 canary at same 10% → should work
        String configId2 = createConfigAndReturnId("canary-multi-svc", "dev", "key-2",
                Map.of("v", 1));
        CreateRolloutRequest canary2 = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId2))
                .type("canary")
                .canaryPercentage(10)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canary2)))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldAllowCanaryOnDifferentPercentageForDifferentConfig() throws Exception {
        String configId1 = createConfigAndReturnId("canary-block-svc", "dev", "key-1",
                Map.of("v", 1));
        CreateRolloutRequest canary1 = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId1))
                .type("canary")
                .canaryPercentage(10)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canary1)))
                .andExpect(status().isCreated());

        String configId2 = createConfigAndReturnId("canary-block-svc", "dev", "key-2",
                Map.of("v", 1));
        CreateRolloutRequest canary2 = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId2))
                .type("canary")
                .canaryPercentage(20)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canary2)))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldAllowGradualOnDifferentConfigWhileCanaryExists() throws Exception {
        String configId1 = createConfigAndReturnId("canary-gradblock-svc", "dev", "key-1",
                Map.of("v", 1));
        CreateRolloutRequest canary = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId1))
                .type("canary")
                .canaryPercentage(10)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canary)))
                .andExpect(status().isCreated());

        // Different config, gradual → blocked
        String configId2 = createConfigAndReturnId("canary-gradblock-svc", "dev", "key-2",
                Map.of("v", 1));
        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId2)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        CreateRolloutRequest gradual = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId2))
                .type("gradual")
                .totalDeployments(5)
                .deploymentIntervalSeconds(60)
                .build();

        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(gradual)))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldAllowCanaryWithHigherPercentageOnSameConfig() throws Exception {
        String configId = createConfigAndReturnId("canary-increase-svc", "dev", "key-1",
                Map.of("v", 1));

        // Canary at 5%
        CreateRolloutRequest canary1 = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("canary")
                .canaryPercentage(5)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canary1)))
                .andExpect(status().isCreated());

        // Need new version for next canary
        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        // Canary at 15% on same config → OK (higher percentage)
        CreateRolloutRequest canary2 = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("canary")
                .canaryPercentage(15)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canary2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canaryPercentage", is(15)));
    }

    @Test
    void shouldBlockCanaryWithLowerPercentageOnSameConfig() throws Exception {
        String configId = createConfigAndReturnId("canary-decrease-svc", "dev", "key-1",
                Map.of("v", 1));

        CreateRolloutRequest canary1 = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("canary")
                .canaryPercentage(10)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canary1)))
                .andExpect(status().isCreated());

        UpdateConfigRequest update = UpdateConfigRequest.builder()
                .value(Map.of("v", 2))
                .expectedVersion(1L)
                .build();
        mockMvc.perform(put("/v1/configs/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));

        // Canary at 3% → blocked (lower)
        CreateRolloutRequest canary2 = CreateRolloutRequest.builder()
                .configId(UUID.fromString(configId))
                .type("canary")
                .canaryPercentage(3)
                .build();
        mockMvc.perform(post("/v1/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(canary2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("CANARY_POLICY_VIOLATION")));
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
