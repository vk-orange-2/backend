package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.domain.Specification;
import ru.configplatform.configserver.model.AuditLogEntity;

import java.time.Instant;

/**
 * Спецификации для динамической фильтрации аудит-логов
 *
 * Каждый метод возвращает Specification, которые можно комбинировать через .and()
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    /** Фильтр по имени сервиса (FR-63) */
    public static Specification<AuditLogEntity> byService(String serviceName) {
        return (root, query, cb) ->
                cb.equal(root.get("serviceName"), serviceName);
    }

    /** Фильтр по инициатору (FR-64) */
    public static Specification<AuditLogEntity> byActor(String actor) {
        return (root, query, cb) ->
                cb.equal(root.get("actor"), actor);
    }

    /** Фильтр по временному диапазону (FR-65) */
    public static Specification<AuditLogEntity> byTimeRange(Instant from, Instant to) {
        return (root, query, cb) -> {
            if (from != null && to != null) {
                return cb.between(root.get("createdAt"), from, to);
            } else if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
            } else if (to != null) {
                return cb.lessThanOrEqualTo(root.get("createdAt"), to);
            }
            return cb.conjunction(); // no filter
        };
    }

    /** Фильтр по типу операции */
    public static Specification<AuditLogEntity> byOperation(String operation) {
        return (root, query, cb) ->
                cb.equal(root.get("operation"), operation);
    }

    /** Фильтр по ID конфига */
    public static Specification<AuditLogEntity> byConfigId(java.util.UUID configId) {
        return (root, query, cb) ->
                cb.equal(root.get("configId"), configId);
    }
}
