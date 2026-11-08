package com.invigilo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The application must refuse to start with a missing or weak JWT signing secret. */
class SecurityPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(SecurityProperties.class)
    static class Props {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Props.class)
            .withPropertyValues(
                    "invigilo.security.jwt.issuer=invigilo",
                    "invigilo.security.jwt.access-token-ttl=PT15M",
                    "invigilo.security.cors.allowed-origins=http://localhost:5173");

    @Test
    void missingSecretFailsStartup() {
        runner.withPropertyValues("invigilo.security.jwt.secret=")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void shortSecretFailsStartup() {
        runner.withPropertyValues("invigilo.security.jwt.secret=too-short")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void strongSecretStarts() {
        runner.withPropertyValues("invigilo.security.jwt.secret=0123456789abcdef0123456789abcdef")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(SecurityProperties.class));
    }
}
