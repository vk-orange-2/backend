package ru.configplatform.configserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.configplatform.configserver.model.CentrifugoOutboxEntity;

@Repository
public interface CentrifugoOutboxRepository extends JpaRepository<CentrifugoOutboxEntity, Long> {
}
