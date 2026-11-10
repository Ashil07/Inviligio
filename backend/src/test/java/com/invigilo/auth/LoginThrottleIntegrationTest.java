package com.invigilo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** M18 security review: password guessing is slowed down per email. */
@SuppressWarnings("rawtypes")
class LoginThrottleIntegrationTest extends IntegrationTest {

    ResponseEntity<Map> login(String email, String password) {
        return http.postForEntity("/api/v1/auth/login", TestData.json(Map.of("email", email, "password", password)), Map.class);
    }

    @Test
    void fiveWrongPasswordsPauseSignInForThatEmailOnly() {
        TestData.Registered target = data().registerCollege();
        TestData.Registered other = data().registerCollege();

        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertThat(login(target.email(), "wrong-password").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // Even the right password is refused for a while, with a clear reason.
        var paused = login(target.email(), TestData.PASSWORD);
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(paused.getBody()).containsEntry("code", "TOO_MANY_ATTEMPTS");
        assertThat((String) paused.getBody().get("detail")).contains("Try again in");
        // Other accounts are not affected.
        assertThat(login(other.email(), TestData.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // After the window the account can sign in again, and a success clears the count.
        clock.advance(LoginThrottle.WINDOW.plus(Duration.ofMinutes(1)));
        assertThat(login(target.email(), TestData.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(target.email(), "wrong-password").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(target.email(), TestData.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
