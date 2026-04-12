# backend

## Запуск БД

```bash
cd infra
docker compose up -d
cd ..
```

## Запуск Config Server

```bash
cd infra
docker compose up -d
cd ..

cd config-server
./gradlew bootRun
```

Если gradlew нет, можно сгенерировать его:

```bash
cd config-server
gradle wrapper --gradle-version 8.12
./gradlew bootRun
```

Запуск тестов:
```bash
cd config-server
./gradlew test
```

