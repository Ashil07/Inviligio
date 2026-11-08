package com.invigilo.system;

import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reports whether the API can reach its database and which schema migration is applied. */
@Service
public class SystemStatusService {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);

    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    private final String applicationName;

    public SystemStatusService(JdbcTemplate jdbc, Flyway flyway,
                               @Value("${spring.application.name}") String applicationName) {
        this.jdbc = jdbc;
        this.flyway = flyway;
        this.applicationName = applicationName;
    }

    public SystemStatus status() {
        return new SystemStatus(applicationName, Instant.now(), database());
    }

    private SystemStatus.Database database() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            MigrationInfo current = flyway.info().current();
            String version = current == null ? null : current.getVersion().getVersion();
            return new SystemStatus.Database("UP", version);
        } catch (RuntimeException ex) {
            log.warn("Database status check failed: {}", ex.getMessage());
            return new SystemStatus.Database("DOWN", null);
        }
    }

    public record SystemStatus(String application, Instant serverTime, Database database) {
        public record Database(String status, String schemaVersion) {}
    }
}
