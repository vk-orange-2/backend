package ru.configplatform.configserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.configplatform.configserver.dto.DiffResponse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Вычисляет diff между двумя версиями payload
 *
 * Поддерживает flat JSON-объекты (первый уровень). Для вложенных структур сравнивает значения целиком
 */
@Service
@RequiredArgsConstructor
public class DiffService {

    private final ObjectMapper objectMapper;

    @Observed(
            name = "diff.compute",
            contextualName = "compute-diff"
    )
    public DiffResponse computeDiff(String oldJson, String newJson, long versionFrom, long versionTo) {
        Map<String, Object> oldMap = toFlatMap(oldJson);
        Map<String, Object> newMap = toFlatMap(newJson);

        Map<String, Object> added = new LinkedHashMap<>();
        Map<String, Object> removed = new LinkedHashMap<>();
        Map<String, DiffResponse.ChangedValue> changed = new LinkedHashMap<>();

        // Ключи в новой версии
        for (Map.Entry<String, Object> entry : newMap.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();

            if (!oldMap.containsKey(key)) {
                added.put(key, newValue);
            } else {
                Object oldValue = oldMap.get(key);
                if (!Objects.equals(oldValue, newValue)) {
                    changed.put(key, DiffResponse.ChangedValue.builder()
                            .oldValue(oldValue)
                            .newValue(newValue)
                            .build());
                }
            }
        }

        // Ключи, удаленные в новой версии
        for (String key : oldMap.keySet()) {
            if (!newMap.containsKey(key)) {
                removed.put(key, oldMap.get(key));
            }
        }

        return DiffResponse.builder()
                .versionFrom(versionFrom)
                .versionTo(versionTo)
                .added(added)
                .removed(removed)
                .changed(changed)
                .build();
    }

    @Observed(
            name = "diff.serialize",
            contextualName = "serialize-diff"
    )
    public String serializeDiff(DiffResponse diff) {
        try {
            return objectMapper.writeValueAsString(diff);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, Object> toFlatMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Collections.emptyMap();
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map) {
                return objectMapper.convertValue(parsed, new TypeReference<Map<String, Object>>() {});
            }

            return Map.of("_value", parsed); // Для скалярных значений (строка, число) — оборачиваем
        } catch (JsonProcessingException e) {
            return Map.of("_raw", json);
        }
    }
}
