package com.invigilo.demo;

import static com.invigilo.support.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The demo college loads through the real services and the engine allocates it completely:
 * an end-to-end check of M1-M8 on realistic data.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class DemoDataIntegrationTest extends IntegrationTest {

    @Autowired DemoData demo;

    @Test
    void loadsOnceAndAllocatesEveryPosition() {
        String email = "examcell." + TestData.unique().toLowerCase() + "@demo.invigilo.test";

        var summary = demo.load(email, TestData.PASSWORD);
        var again = demo.load(email, TestData.PASSWORD);

        assertThat(summary.created()).isTrue();
        assertThat(summary.faculty()).isEqualTo(50);
        assertThat(summary.sessions()).isEqualTo(10);
        assertThat(again.created()).isFalse();

        var login = http.postForEntity("/api/v1/auth/login", json(Map.of("email", email, "password", TestData.PASSWORD)), Map.class);
        String token = (String) ((Map<String, Object>) login.getBody().get("auth")).get("accessToken");
        var college = data().get("/api/v1/college", token, Map.class).getBody();
        assertThat(college).containsEntry("status", "ACTIVE");

        var chart = data().send(HttpMethod.POST, "/api/v1/exam-periods/" + summary.periodId() + "/duty-chart/generate", token, null, Map.class);

        assertThat(chart.getStatusCode()).as(String.valueOf(chart.getBody())).isEqualTo(HttpStatus.OK);
        var stats = (Map<String, Object>) chart.getBody().get("stats");
        assertThat((Integer) stats.get("positions")).isGreaterThan(50);
        assertThat(stats.get("filled")).isEqualTo(stats.get("positions"));
        assertThat((List<?>) chart.getBody().get("unfilled")).isEmpty();
        assertThat((Integer) stats.get("maxDuties") - (Integer) stats.get("minDuties")).isLessThanOrEqualTo(3);
    }
}
