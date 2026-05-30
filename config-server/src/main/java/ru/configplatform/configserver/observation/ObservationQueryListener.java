package ru.configplatform.configserver.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class ObservationQueryListener implements QueryExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ObservationQueryListener.class);
    private final ObservationRegistry registry;
    private final ThreadLocal<Observation> currentObservation = new ThreadLocal<>();

    public ObservationQueryListener(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void beforeQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        if (queryInfoList == null || queryInfoList.isEmpty()) {
            return;
        }

        QueryInfo queryInfo = queryInfoList.get(0);
        String sql = queryInfo.getQuery();

        // Очищаем SQL от лишних пробелов и переносов строк для читаемости
        String normalizedSql = normalizeSql(sql);

        // Создаём Observation
        Observation observation = Observation.createNotStarted("jdbc.query", registry)
                .contextualName("db-query")
                .lowCardinalityKeyValue("db.type", "sql")
                .lowCardinalityKeyValue("db.statement.type", extractStatementType(sql))
                .highCardinalityKeyValue("db.statement", normalizedSql);

        observation.start();
        currentObservation.set(observation);
    }

    @Override
    public void afterQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        Observation observation = currentObservation.get();
        if (observation == null) {
            return;
        }

        try {
            // Добавляем информацию о выполненном запросе
            if (executionInfo != null) {
                observation.lowCardinalityKeyValue("db.success", String.valueOf(executionInfo.isSuccess()));
                observation.lowCardinalityKeyValue("db.elapsed.time.ms",
                        String.valueOf(TimeUnit.NANOSECONDS.toMillis(executionInfo.getElapsedTime())));

                if (executionInfo.getResult() instanceof Integer) {
                    observation.lowCardinalityKeyValue("db.affected.rows", String.valueOf(executionInfo.getResult()));
                }

                if (executionInfo.isBatch()) {
                    observation.lowCardinalityKeyValue("db.batch.size", String.valueOf(executionInfo.getBatchSize()));
                }

                if (executionInfo.getThrowable() != null) {
                    observation.error(executionInfo.getThrowable());
                }
            }
        } finally {
            // Останавливаем Observation и очищаем ThreadLocal
            observation.stop();
            currentObservation.remove();
        }
    }

    /**
     * Нормализует SQL для более читаемого отображения в трейсах
     */
    private String normalizeSql(String sql) {
        if (sql == null) return "";
        // Убираем лишние пробелы и переносы строк
        return sql.replaceAll("\\s+", " ").trim();
    }

    /**
     * Определяет тип SQL запроса (SELECT, INSERT, UPDATE, DELETE и т.д.)
     */
    private String extractStatementType(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "unknown";
        }

        String upperSql = sql.trim().toUpperCase();
        if (upperSql.startsWith("SELECT")) return "SELECT";
        if (upperSql.startsWith("INSERT")) return "INSERT";
        if (upperSql.startsWith("UPDATE")) return "UPDATE";
        if (upperSql.startsWith("DELETE")) return "DELETE";
        if (upperSql.startsWith("CREATE")) return "CREATE";
        if (upperSql.startsWith("ALTER")) return "ALTER";
        if (upperSql.startsWith("DROP")) return "DROP";
        if (upperSql.startsWith("TRUNCATE")) return "TRUNCATE";
        if (upperSql.startsWith("BEGIN") || upperSql.startsWith("START")) return "TRANSACTION";
        if (upperSql.startsWith("COMMIT")) return "COMMIT";
        if (upperSql.startsWith("ROLLBACK")) return "ROLLBACK";

        return "other";
    }
}
