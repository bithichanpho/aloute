package com.aloute.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.RequestDispatcher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    @Value("${app.security.csp:default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'self'; form-action 'self'; script-src 'self' https://www.gstatic.com https://apis.google.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; img-src 'self' data: https:; font-src 'self' data: https://fonts.gstatic.com; connect-src 'self' https: wss:; media-src 'self' https:; frame-src 'self' https://accounts.google.com https://*.google.com https://*.firebaseapp.com https://*.web.app https://www.gstatic.com https://apis.google.com; worker-src 'self';}")
    private String contentSecurityPolicy;

    @Value("${app.security.cookie-secure:false}")
    private boolean cookieSecure;

    public SecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            CorsConfigurationSource corsConfigurationSource) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }


    /**
     * Firebase Auth uses modules from gstatic and an auth helper frame on the Firebase/Google
     * domains. Add these required origins even when a deployment supplies APP_SECURITY_CSP;
     * otherwise the backend advertises Google login while the browser silently blocks its SDK.
     */
    private String firebaseCompatibleCsp() {
        String policy = ensureCspSources(contentSecurityPolicy,
                "script-src",
                "https://www.gstatic.com",
                "https://apis.google.com");
        return ensureCspSources(policy,
                "frame-src",
                "https://accounts.google.com",
                "https://*.google.com",
                "https://*.firebaseapp.com",
                "https://*.web.app",
                "https://www.gstatic.com",
                "https://apis.google.com");
    }

    private static String ensureCspSources(String policy, String directiveName, String... requiredSources) {
        String source = policy == null || policy.isBlank() ? "default-src 'self'" : policy.trim();
        String[] directives = source.split(";");
        StringBuilder result = new StringBuilder();
        boolean found = false;

        for (String rawDirective : directives) {
            String directive = rawDirective.trim();
            if (directive.isEmpty()) continue;

            String[] tokens = directive.split("\\s+");
            if (tokens.length > 0 && tokens[0].equalsIgnoreCase(directiveName)) {
                found = true;
                for (String required : requiredSources) {
                    boolean present = false;
                    for (String token : tokens) {
                        if (token.equalsIgnoreCase(required)) {
                            present = true;
                            break;
                        }
                    }
                    if (!present) directive += " " + required;
                    // Refresh token array after appending a source so a duplicate source is not added.
                    tokens = directive.split("\\s+");
                }
            }

            if (result.length() > 0) result.append("; ");
            result.append(directive);
        }

        if (!found) {
            if (result.length() > 0) result.append("; ");
            result.append(directiveName).append(" 'self'");
            for (String required : requiredSources) result.append(' ').append(required);
        }
        return result.toString();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(cookieSecure));
        return repository;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http, CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // SPA sends the unmasked token read from XSRF-TOKEN cookie.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // SockJS uses POST for its transport; origin validation is enforced by
                        // WebSocketConfig and inbound STOMP authentication remains mandatory.
                        .ignoringRequestMatchers("/ws/**"))
                .sessionManagement(
                        sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        (request, response, authException) -> {
                            String path = request.getServletPath();
                            if (path != null && (path.startsWith("/api/") || path.equals("/api"))) {
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.setCharacterEncoding("UTF-8");
                                response.getWriter().write(
                                        "{\"success\":false,\"message\":\"Vui lòng đăng nhập\",\"data\":null}");
                            } else {
                                response.sendRedirect(request.getContextPath() + "/login");
                            }
                        })
                        .accessDeniedHandler((request, response, denied) -> {
                            String path = request.getServletPath();
                            if (path != null && (path.startsWith("/api/") || path.equals("/api"))) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.setCharacterEncoding("UTF-8");
                                response.getWriter().write(
                                        "{\"success\":false,\"message\":\"Bạn không có quyền truy cập\",\"data\":null}");
                            } else {
                                request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpServletResponse.SC_FORBIDDEN);
                                request.getRequestDispatcher("/error").forward(request, response);
                            }
                        }))
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(csp -> csp.policyDirectives(firebaseCompatibleCsp()))
                                        .frameOptions(frame -> frame.sameOrigin())
                                        .contentTypeOptions(contentType -> {})
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                org.springframework.security.web
                                                                        .header.writers
                                                                        .ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31536000)))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasRole("ADMIN")
                                .requestMatchers("/api/admin/**", "/admin/**").hasRole("ADMIN")
                                .requestMatchers(
                                                "/",
                                                "/login",
                                                "/register",
                                                "/verify-email",
                                                "/forgot-password",
                                                "/reset-password",
                                                "/profile/**",
                                                "/search",
                                                "/css/**",
                                                "/js/**",
                                                "/vendor/**",
                                                "/images/**",
                                                "/favicon.ico",
                                                "/firebase-messaging-sw.js",
                                                "/error")
                                        .permitAll()
                                // All authenticated HTML application areas share the same entry point:
                                // anonymous browser navigation redirects to /login; API requests still get JSON 401.
                                .requestMatchers(
                                                "/feed", "/chat", "/compose", "/activity", "/saved", "/hashtag/**",
                                                "/communities", "/communities/**", "/settings", "/settings/**")
                                        .authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/firebase/config")
                                        .permitAll()
                                        .requestMatchers("/api/auth/**", "/ws/**")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/posts/feed")
                                        .authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/posts/user/**")
                                        .permitAll()
                                // Protect APIs by default; allow unknown browser routes to reach the
                                // custom Thymeleaf 404 view instead of redirecting every typo to login.
                                .requestMatchers("/api/**")
                                        .authenticated()
                                .anyRequest()
                                        .permitAll())
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
