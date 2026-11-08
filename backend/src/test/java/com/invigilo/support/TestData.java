package com.invigilo.support;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP helpers that create colleges and sessions through the real API. */
public class TestData {

    public static final String PASSWORD = "correct-horse-battery";
    public static final String REFRESH_COOKIE = "invigilo_refresh";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TestRestTemplate http;

    TestData(TestRestTemplate http) {
        this.http = http;
    }

    public record Registered(UUID collegeId, String collegeCode, UUID membershipId, UUID userId,
                             String email, String accessToken, String refreshToken) {}

    public static String unique() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }

    public static Map<String, Object> registration(String suffix) {
        return Map.of(
                "collegeName", "College " + suffix,
                "collegeCode", "C" + suffix,
                "adminFullName", "Admin " + suffix,
                "adminEmail", "admin." + suffix.toLowerCase() + "@example.test",
                "password", PASSWORD);
    }

    @SuppressWarnings("unchecked")
    public Registered registerCollege() {
        String suffix = unique();
        ResponseEntity<Map> response = http.postForEntity("/api/v1/auth/register-college", json(registration(suffix)), Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Registration failed: " + response);
        }
        Map<String, Object> session = (Map<String, Object>) response.getBody().get("session");
        Map<String, Object> college = (Map<String, Object>) session.get("college");
        Map<String, Object> user = (Map<String, Object>) session.get("user");
        return new Registered(
                UUID.fromString((String) college.get("id")),
                (String) college.get("code"),
                UUID.fromString((String) session.get("membershipId")),
                UUID.fromString((String) user.get("id")),
                (String) user.get("email"),
                (String) response.getBody().get("accessToken"),
                refreshCookie(response));
    }

    public static <T> HttpEntity<T> json(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    public static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    public static HttpEntity<Void> withRefreshCookie(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + refreshToken);
        return new HttpEntity<>(headers);
    }

    public <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        return http.exchange(path, HttpMethod.GET, bearer(token), type);
    }

    /** Raw Set-Cookie header for the refresh cookie, or null. */
    public static String refreshSetCookie(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) return null;
        return cookies.stream().filter(c -> c.startsWith(REFRESH_COOKIE + "=")).findFirst().orElse(null);
    }

    /** Value of the refresh cookie set by the response, or null when none was set. */
    public static String refreshCookie(ResponseEntity<?> response) {
        String header = refreshSetCookie(response);
        if (header == null) return null;
        String value = header.substring(REFRESH_COOKIE.length() + 1, header.indexOf(';'));
        return value.isEmpty() ? null : value;
    }
}
