package ru.configplatform.configserver.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RolloutStatusConverter implements AttributeConverter<RolloutStatus, String> {

    @Override
    public String convertToDatabaseColumn(RolloutStatus attribute) {
        return attribute == null ? null : attribute.getValue();
    }

    @Override
    public RolloutStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RolloutStatus.fromValue(dbData);
    }
}
