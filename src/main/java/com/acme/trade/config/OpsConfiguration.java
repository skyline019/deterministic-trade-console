package com.acme.trade.config;

import com.acme.trade.adapter.ops.MaintenanceRunner;
import com.acme.trade.adapter.persistence.MySqlMaintenanceStore;
import com.acme.trade.application.port.MaintenanceStore;
import com.acme.trade.application.port.OrderRepository;
import com.acme.trade.application.service.AuditService;
import com.acme.trade.application.service.MaintenanceService;
import com.acme.trade.application.service.OrderTransitionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class OpsConfiguration {
    @Bean
    MaintenanceStore maintenanceStore(JdbcTemplate jdbc, DataSourceTransactionManager transactionManager) {
        return new MySqlMaintenanceStore(jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    MaintenanceService maintenanceService(MaintenanceStore store, AuditService audits, OrderRepository orders,
                                          OrderTransitionService transitions, Clock clock) {
        return new MaintenanceService(store, audits, orders, transitions, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "trade.maintenance", name = "command")
    MaintenanceRunner maintenanceRunner(MaintenanceService maintenance, Environment environment,
                                        ConfigurableApplicationContext context) {
        Binder binder = Binder.get(environment);
        String command = binder.bind("trade.maintenance.command", String.class).orElse("");
        boolean exitAfter = binder.bind("trade.maintenance.exit-after", Boolean.class).orElse(true);
        int batchSize = binder.bind("trade.maintenance.batch-size", Integer.class).orElse(500);
        Duration quiet = binder.bind("trade.maintenance.audit-quiet-before", Duration.class)
                .orElse(Duration.ofDays(30));
        int checkpointMin = binder.bind("trade.maintenance.checkpoint-min-events", Integer.class)
                .orElse(500);
        return new MaintenanceRunner(maintenance, command, exitAfter, batchSize, quiet, checkpointMin, context);
    }
}
