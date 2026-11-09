package com.invigilo.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Loads the demo college at start-up when the {@code demo} profile is active:
 * {@code SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run} with DEMO_ADMIN_EMAIL and
 * DEMO_ADMIN_PASSWORD set (see backend/.env.example). Never active in production.
 */
@Component
@Profile("demo")
class DemoDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataRunner.class);

    private final DemoData demo;
    private final String email;
    private final String password;

    DemoDataRunner(DemoData demo, @Value("${invigilo.demo.admin-email:}") String email,
                   @Value("${invigilo.demo.admin-password:}") String password) {
        this.demo = demo;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.length() < 8) {
            throw new IllegalStateException("Profile 'demo' needs DEMO_ADMIN_EMAIL and DEMO_ADMIN_PASSWORD (8+ characters)");
        }
        var summary = demo.load(email, password);
        if (summary.created()) {
            log.info("Demo college {} created: {} faculty, {} sessions. Sign in as {} and open Examinations to generate duties.",
                    DemoData.CODE, summary.faculty(), summary.sessions(), email);
        } else {
            log.info("Demo college {} already exists; nothing loaded.", DemoData.CODE);
        }
    }
}
