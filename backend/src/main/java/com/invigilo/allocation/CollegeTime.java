package com.invigilo.allocation;

import com.invigilo.college.CollegeService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/** "Now" in the current college's time zone (sessions are stored as local dates and times). */
@Component
class CollegeTime {

    private static final ZoneId FALLBACK = ZoneId.of("Asia/Kolkata");

    private final CollegeService colleges;
    private final Clock clock;

    CollegeTime(CollegeService colleges, Clock clock) {
        this.colleges = colleges;
        this.clock = clock;
    }

    ZoneId zone() {
        String tz = colleges.current().profile().timeZone();
        try {
            return tz == null ? FALLBACK : ZoneId.of(tz);
        } catch (RuntimeException e) {
            return FALLBACK;
        }
    }

    /** Computed from the instant: the test clock's {@code withZone} returns the same clock. */
    LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), zone());
    }
}
