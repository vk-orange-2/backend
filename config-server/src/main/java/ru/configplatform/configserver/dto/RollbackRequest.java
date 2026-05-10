package ru.configplatform.configserver.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RollbackRequest {

    /** Версия, к которой нужно откатиться. */
    @NotNull(message = "targetVersion is required")
    @Min(value = 1, message = "targetVersion must be >= 1")
    private Long targetVersion;

    /**
     * Текущая ожидаемая версия для оптимистичной блокировки. Защищает от rollback на основе устаревших данных
     */
    @NotNull(message = "expectedVersion is required")
    @Min(value = 1, message = "expectedVersion must be >= 1")
    private Long expectedVersion;

    private String comment;
}
