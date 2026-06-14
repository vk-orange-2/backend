package ru.configplatform.configserver.config;

import io.micrometer.observation.ObservationRegistry;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import net.ttddyy.dsproxy.listener.ChainListener;
import net.ttddyy.dsproxy.listener.logging.SLF4JLogLevel;
import net.ttddyy.dsproxy.listener.logging.SLF4JQueryLoggingListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import ru.configplatform.configserver.observation.ObservationQueryListener;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(
        prefix = "observability.jdbc-proxy",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class DataSourceProxyConfig {

    @Autowired
    private ObservationRegistry observationRegistry;

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        DataSource originalDataSource = properties.initializeDataSourceBuilder().build();

        // Создаём слушателя, который создаёт спаны для каждого запроса
        ObservationQueryListener observationListener = new ObservationQueryListener(observationRegistry);

        SLF4JQueryLoggingListener loggingListener = new SLF4JQueryLoggingListener();
        loggingListener.setLogLevel(SLF4JLogLevel.INFO);

        ChainListener chain = new ChainListener();
        chain.addListener(observationListener);
        chain.addListener(loggingListener);

        return ProxyDataSourceBuilder
                .create(originalDataSource)
                .name("ObservationDataSource")
                .listener(chain)
                .build();
    }
}

