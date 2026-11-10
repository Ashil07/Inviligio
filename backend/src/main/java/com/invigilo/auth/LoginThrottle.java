package com.invigilo.auth;

import com.invigilo.common.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: after {@link #MAX_FAILURES} wrong passwords for one email within
 * {@link #WINDOW}, sign-in for that email is refused (429) until the oldest failure is out of the
 * window. Kept in memory, so it is per instance and resets on restart; good enough for one instance
 * (see docs/DEPLOYMENT.md for several).
 */
@Component
class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Refuses the attempt while the email has too many recent failures. */
    void check(String email) {
        Deque<Instant> recent = failures.get(email);
        if (recent == null) return;
        synchronized (recent) {
            Instant now = clock.instant();
            prune(recent, now);
            if (recent.size() >= MAX_FAILURES) {
                long minutes = Math.max(1, Duration.between(now, recent.peekFirst().plus(WINDOW)).toMinutes() + 1);
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                        "Too many failed sign-ins for this email. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".");
            }
        }
    }

    void failed(String email) {
        Deque<Instant> recent = failures.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (recent) {
            Instant now = clock.instant();
            prune(recent, now);
            recent.addLast(now);
        }
        if (failures.size() > 10_000) failures.entrySet().removeIf(e -> e.getValue().isEmpty()); // bound memory
    }

    void succeeded(String email) {
        failures.remove(email);
    }

    private static void prune(Deque<Instant> recent, Instant now) {
        while (!recent.isEmpty() && !recent.peekFirst().plus(WINDOW).isAfter(now)) recent.removeFirst();
    }
}
