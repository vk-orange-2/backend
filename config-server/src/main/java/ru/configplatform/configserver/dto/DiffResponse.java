package ru.configplatform.configserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Diff между двумя версиями конфигурации (FR-25)
 *
 * Содержит:
 * - added: ключи, которые есть в новой версии, но не в старой
 * - removed: ключи, которые есть в старой версии, но не в новой
 * - changed: ключи с измененными значениями (old → new)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffResponse {

    private long versionFrom;
    private long versionTo;
    private Map<String, Object> added;
    private Map<String, Object> removed;
    private Map<String, ChangedValue> changed;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangedValue {
        private Object oldValue;
        private Object newValue;
    }
}
