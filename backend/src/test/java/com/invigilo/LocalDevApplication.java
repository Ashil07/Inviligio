package com.invigilo;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;

/**
 * Runs the backend for local development with an embedded PostgreSQL 17, so no database has to be
 * installed or hosted:
 *
 * <pre>./mvnw spring-boot:test-run      (Windows: .\mvnw.cmd spring-boot:test-run)</pre>
 *
 * Data is kept in {@code backend/.dev-db} between runs (delete the folder to start over). Lives in
 * test sources so the embedded database never ships with the application. Everything else comes
 * from {@code backend/.env} as usual; add {@code SPRING_PROFILES_ACTIVE=demo} to load demo data.
 */
public final class LocalDevApplication {

    private LocalDevApplication() {}

    public static void main(String[] args) throws IOException {
        Path dataDir = Path.of(".dev-db").toAbsolutePath();
        Files.createDirectories(dataDir);
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }));
        // System properties outrank backend/.env, so DB_URL there is ignored in this mode.
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        // A free port is chosen at random: fixed ones can fall in ranges Windows reserves (Hyper-V).
        System.out.println("Embedded PostgreSQL running on port " + postgres.getPort() + ", data in " + dataDir);
        SpringApplication.run(InvigiloApplication.class, args);
    }
}
