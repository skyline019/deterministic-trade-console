package com.acme.trade.adapter.ops;

import com.acme.trade.application.service.MaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Explicit maintenance entrypoint. Activated only when {@code trade.maintenance.command} is set.
 * Forbidden pattern: {@code @Scheduled} (AR-006).
 */
public final class MaintenanceRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceRunner.class);

    private final MaintenanceService maintenance;
    private final String command;
    private final boolean exitAfter;
    private final int batchSize;
    private final Duration auditQuietBefore;
    private final int checkpointMinEvents;
    private final ConfigurableApplicationContext context;

    public MaintenanceRunner(MaintenanceService maintenance, String command, boolean exitAfter,
                             int batchSize, Duration auditQuietBefore, int checkpointMinEvents,
                             ConfigurableApplicationContext context) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.command = Objects.requireNonNull(command, "command").trim().toLowerCase(Locale.ROOT);
        this.exitAfter = exitAfter;
        this.batchSize = batchSize;
        this.auditQuietBefore = Objects.requireNonNull(auditQuietBefore, "auditQuietBefore");
        this.checkpointMinEvents = checkpointMinEvents;
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (command.isEmpty()) {
            return;
        }
        int affected = switch (command) {
            case "purge-tokens", "purge-expired-tokens" -> maintenance.purgeExpiredTokens(batchSize);
            case "archive-audit", "archive-quiet-audits" ->
                    maintenance.archiveQuietAudits(auditQuietBefore, batchSize);
            case "checkpoint-audit", "checkpoint-long-chains" ->
                    maintenance.checkpointLongChains(checkpointMinEvents, batchSize);
            case "expire-reservations", "expire-reserved-orders" ->
                    maintenance.expireReservations(batchSize);
            default -> throw new IllegalArgumentException(
                    "unknown trade.maintenance.command='" + command
                            + "' (expected purge-tokens|archive-audit|checkpoint-audit|expire-reservations)");
        };
        log.info("Maintenance command '{}' finished; affected={}", command, affected);
        System.out.println("MAINTENANCE_OK command=" + command + " affected=" + affected);
        if (exitAfter) {
            int code = SpringApplication.exit(context, () -> 0);
            System.exit(code);
        }
    }
}
