package ru.configplatform.configserver.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ApiKeyId implements Serializable {
    private UUID serviceId;
    private Short environmentId;

    public ApiKeyId() {
    }

    public ApiKeyId(UUID serviceId, Short environmentId) {
        this.serviceId = serviceId;
        this.environmentId = environmentId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ApiKeyId that = (ApiKeyId) o;
        return Objects.equals(serviceId, that.serviceId) &&
                Objects.equals(environmentId, that.environmentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceId, environmentId);
    }
}
