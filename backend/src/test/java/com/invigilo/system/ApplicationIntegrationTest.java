package com.invigilo.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.support.IntegrationTest;
import com.invigilo.system.SystemStatusService.SystemStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** Boots the full application against real PostgreSQL, runs Flyway and checks the public status endpoint. */
class ApplicationIntegrationTest extends IntegrationTest {

    @Autowired
    Flyway flyway;

    @Test
    void bootsMigratesAndReportsDatabaseUp() {
        var response = http.getForEntity("/api/v1/system/status", SystemStatus.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().database().status()).isEqualTo("UP");
        assertThat(response.getBody().database().schemaVersion())
                .isEqualTo(flyway.info().current().getVersion().getVersion());
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void protectedEndpointsRequireAuthentication() {
        assertThat(http.getForEntity("/api/v1/auth/me", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
