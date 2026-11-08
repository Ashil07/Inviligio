package com.invigilo.auth;

import com.invigilo.auth.AuthDtos.AuthResponse;
import com.invigilo.auth.AuthDtos.IssuedSession;
import com.invigilo.auth.AuthDtos.LoginRequest;
import com.invigilo.auth.AuthDtos.LoginResponse;
import com.invigilo.auth.AuthDtos.LoginStatus;
import com.invigilo.auth.AuthDtos.RegisterCollegeRequest;
import com.invigilo.auth.AuthDtos.SessionInfo;
import com.invigilo.common.error.ApiException;
import com.invigilo.config.SecurityProperties;
import com.invigilo.tenant.CurrentUser;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. The access token is returned in the body (the SPA keeps it in memory);
 * the refresh token travels only in an HttpOnly, SameSite=Strict cookie scoped to this path.
 */
@RestController
@RequestMapping(AuthController.BASE_PATH)
public class AuthController {

    static final String BASE_PATH = "/api/v1/auth";
    static final String REFRESH_COOKIE = "invigilo_refresh";

    private final AuthService auth;
    private final SecurityProperties properties;

    public AuthController(AuthService auth, SecurityProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @PostMapping("/register-college")
    public ResponseEntity<AuthResponse> registerCollege(@Valid @RequestBody RegisterCollegeRequest request) {
        IssuedSession issued = auth.registerCollege(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(issued.refreshToken()).toString())
                .body(AuthResponse.from(issued));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return switch (auth.login(request)) {
            case AuthService.LoginResult.Authenticated a -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie(a.session().refreshToken()).toString())
                    .body(new LoginResponse(LoginStatus.AUTHENTICATED, AuthResponse.from(a.session()), null));
            case AuthService.LoginResult.CollegeSelectionRequired s -> ResponseEntity.ok()
                    .body(new LoginResponse(LoginStatus.COLLEGE_SELECTION_REQUIRED, null, s.colleges()));
        };
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        try {
            if (refreshToken == null || refreshToken.isBlank()) {
                throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Not signed in");
            }
            IssuedSession issued = auth.refresh(refreshToken);
            var response = ResponseEntity.ok();
            if (issued.refreshToken() != null) {
                response.header(HttpHeaders.SET_COOKIE, refreshCookie(issued.refreshToken()).toString());
            }
            return response.body(AuthResponse.from(issued));
        } catch (ApiException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
            problem.setProperty("code", ex.code());
            return ResponseEntity.status(ex.status())
                    .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                    .body(problem);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            auth.logout(refreshToken);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearedCookie().toString()).build();
    }

    @GetMapping("/me")
    public SessionInfo me() {
        return auth.currentSession(CurrentUser.require());
    }

    private ResponseCookie refreshCookie(String value) {
        return baseCookie(value).maxAge(properties.refresh().ttl()).build();
    }

    private ResponseCookie clearedCookie() {
        return baseCookie("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.refresh().cookieSecure())
                .sameSite("Strict")
                .path(BASE_PATH);
    }
}
