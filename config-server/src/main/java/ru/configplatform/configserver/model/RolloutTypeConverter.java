package ru.configplatform.configserver.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RolloutTypeConverter implements AttributeConverter<RolloutType, String> {

    @Override
    public String convertToDatabaseColumn(RolloutType attribute) {
        return attribute == null ? null : attribute.getValue();
    }

    @Override
    public RolloutType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RolloutType.fromValue(dbData);
    }
}
