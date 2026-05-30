# backend

## Запуск

### Полный стек (с Centrifugo)

Из корня репозитория:

```bash
# 1. (пере)собрать config-server
cd config-server
./gradlew build -x test
cd ..

# 2. поднять всё
docker compose up -d --build

# 3. проверить
curl http://localhost:8081/swagger-ui/index.html
```

Если сборка не работает, попробовать прогнать
```
gradle wrapper --gradle-version 9.1.0
```

### Только config-server (для разработки)

```bash
# 1. Поднять только PostgreSQL
docker compose up -d postgres

# 2. Запустить из IDE или терминала
cd config-server
./gradlew bootRun
```

Config-server будет доступен на `http://localhost:8081`. 
Через nginx (если поднят) — на `http://localhost:8080`

### Тесты

```bash
cd config-server
./gradlew test
```

Тесты используют H2 in-memory, Docker не нужен

## Мониторинг и трассировка

### Backend
- Prometheus endpoint: `http://localhost:8081/actuator/prometheus`
- Health: `http://localhost:8081/actuator/health`

### Centrifugo
- Prometheus metrics: `http://<centrifugo-host>:<metrics-port>/metrics`

### Grafana
- Dashboards: `http://localhost:3000`

### Zipkin

Zipkin используется для distributed tracing и позволяет отслеживать полный путь прохождения запроса через систему

Web UI:
```
http://localhost:9411
```

После выполнения запросов к Config Server в Zipkin будут доступны трейсы с информацией о:
- HTTP-запросах;
- вызовах сервисного слоя;
- обращениях к PostgreSQL;
- времени выполнения отдельных операций.

Для поиска трейсов выберите сервис:
```
config-server
```
и нажмите **Run Query**
