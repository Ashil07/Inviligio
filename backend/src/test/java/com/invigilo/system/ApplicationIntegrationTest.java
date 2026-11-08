package com.invigilo.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.system.SystemStatusService.SystemStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full application against a real PostgreSQL (same major version as Supabase),
 * runs Flyway and checks the public status endpoint. Skipped when Docker is not running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ApplicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    TestRestTemplate http;

    @Test
    void bootsMigratesAndReportsDatabaseUp() {
        var response = http.getForEntity("/api/v1/system/status", SystemStatus.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().database().status()).isEqualTo("UP");
        assertThat(response.getBody().database().schemaVersion()).isEqualTo("1");
    }

    @Test
    void protectedEndpointsRequireAuthentication() {
        assertThat(http.getForEntity("/api/v1/faculty", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
