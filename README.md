# backend

## Запуск

### Полный стек (с Centrifugo)

Из корня репозитория:

```bash
# 1. Собрать config-server
cd config-server
./gradlew build -x test
cd ..

# 2. Поднять всё
docker compose up -d --build

# 3. Проверить
curl http://localhost:8080/v1/services
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
