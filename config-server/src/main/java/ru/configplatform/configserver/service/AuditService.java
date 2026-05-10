package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.configplatform.configserver.dto.AuditLogResponse;
import ru.configplatform.configserver.dto.AuditSearchResponse;
import ru.configplatform.configserver.dto.RequestContext;
import ru.configplatform.configserver.model.AuditLogEntity;
import ru.configplatform.configserver.model.ConfigEntity;
import ru.configplatform.configserver.repository.AuditLogRepository;
import ru.configplatform.configserver.repository.AuditLogSpecifications;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Записать событие в аудит-лог
     *
     * Вызывается внутри той же транзакции, что и изменение конфига, чтобы гарантировать консистентность
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void log(
            ConfigEntity config,
            String operation,
            Long versionBefore,
            Long versionAfter,
            String diffJson,
            RequestContext ctx
    ) {
        AuditLogEntity entry = AuditLogEntity.builder()
                .configId(config.getId())
                .serviceName(config.getService().getName())
                .environment(config.getEnvironment().getCode())
                .configKey(config.getConfigKey())
                .operation(operation)
                .actor(ctx.getActor())
                .sourceIp(ctx.getSourceIp())
                .userAgent(ctx.getUserAgent())
                .versionBefore(versionBefore)
                .versionAfter(versionAfter)
                .diff(diffJson)
                .build();

        auditLogRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public AuditSearchResponse search(
            String serviceName,
            String actor,
            Instant from,
            Instant to,
            String operation,
            UUID configId,
            int page,
            int size
    ) {
        Specification<AuditLogEntity> spec = Specification.where(null);

        if (serviceName != null) {
            spec = spec.and(AuditLogSpecifications.byService(serviceName));
        }
        if (actor != null) {
            spec = spec.and(AuditLogSpecifications.byActor(actor));
        }
        if (from != null || to != null) {
            spec = spec.and(AuditLogSpecifications.byTimeRange(from, to));
        }
        if (operation != null) {
            spec = spec.and(AuditLogSpecifications.byOperation(operation));
        }
        if (configId != null) {
            spec = spec.and(AuditLogSpecifications.byConfigId(configId));
        }

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<AuditLogEntity> results = auditLogRepository.findAll(spec, pageRequest);

        return AuditSearchResponse.builder()
                .entries(results.getContent().stream()
                        .map(this::toResponse)
                        .toList())
                .totalCount(results.getTotalElements())
                .build();
    }

    private AuditLogResponse toResponse(AuditLogEntity entity) {
        Object diff = null;
        if (entity.getDiff() != null) {
            try {
                diff = objectMapper.readValue(entity.getDiff(), Object.class);
            } catch (JsonProcessingException e) {
                diff = entity.getDiff();
            }
        }

        return AuditLogResponse.builder()
                .id(entity.getId())
                .configId(entity.getConfigId())
                .serviceName(entity.getServiceName())
                .environment(entity.getEnvironment())
                .configKey(entity.getConfigKey())
                .operation(entity.getOperation())
                .actor(entity.getActor())
                .sourceIp(entity.getSourceIp())
                .versionBefore(entity.getVersionBefore())
                .versionAfter(entity.getVersionAfter())
                .diff(diff)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
