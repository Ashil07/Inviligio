package com.invigilo.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Security settings bound from {@code invigilo.security.*}. Validated at startup so a
 * missing or weak JWT secret fails fast instead of producing forgeable tokens.
 */
@Validated
@ConfigurationProperties(prefix = "invigilo.security")
public record SecurityProperties(@Valid @NotNull Jwt jwt, @Valid @NotNull Refresh refresh, @Valid @NotNull Cors cors) {

    public record Jwt(
            @NotBlank(message = "JWT_SECRET must be set")
            @Size(min = 32, message = "JWT_SECRET must be at least 32 characters")
            String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl) {}

    /**
     * Refresh-token cookie. {@code cookieSecure} must stay true outside local development
     * (browsers accept Secure cookies on http://localhost).
     */
    public record Refresh(@NotNull Duration ttl, boolean cookieSecure) {}

    public record Cors(@NotEmpty List<String> allowedOrigins) {}
}
