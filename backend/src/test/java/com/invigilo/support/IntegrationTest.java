package com.invigilo.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the full application and a real PostgreSQL 17.
 *
 * <p>One embedded PostgreSQL server is started per test JVM and shared by every subclass (the Spring
 * context is cached too). Tests must therefore not depend on an empty database: create data with
 * unique codes/emails (see {@link TestData}) instead of truncating tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    private static final EmbeddedPostgres POSTGRES = start();

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected TenantTestSupport tenants;

    protected TestData data() {
        return new TestData(http);
    }

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        TenantTestSupport tenantTestSupport(org.springframework.transaction.PlatformTransactionManager tm) {
            return new TenantTestSupport(tm);
        }
    }

    /** Real time plus an adjustable offset that only moves forward (shared by all tests in the JVM). */
    public static class MutableClock extends Clock {
        private volatile java.time.Duration offset = java.time.Duration.ZERO;

        public void advance(java.time.Duration by) {
            offset = offset.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.now().plus(offset);
        }
    }
}
