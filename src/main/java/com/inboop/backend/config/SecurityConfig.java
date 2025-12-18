package com.inboop.backend.config;

import com.inboop.backend.auth.security.JwtAuthenticationFilter;
import com.inboop.backend.instagram.handler.FacebookOAuth2SuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * Security configuration for the application.
 *
 * OAUTH2 LOGIN FLOW (handled by Spring Security):
 * 1. User visits GET /oauth2/authorization/facebook
 * 2. Spring redirects to Facebook with proper URL encoding, CSRF state, and
 * config_id
 * 3. User authorizes on Facebook
 * 4. Facebook redirects to GET /login/oauth2/code/facebook?code=xxx&state=yyy
 * 5. Spring validates state, exchanges code for token
 * 6. FacebookOAuth2SuccessHandler stores token and redirects to frontend
 */
@Configuration
public class SecurityConfig {

        private final UserDetailsService customUserDetailsService;
        private final JwtAuthenticationFilter jwtAuthenticationFilter;
        private final FacebookOAuth2SuccessHandler facebookOAuth2SuccessHandler;
        private final ClientRegistrationRepository clientRegistrationRepository;

        @Value("${app.cors.allowed-origins:http://localhost:3000}")
        private String allowedOriginsString;

        @Value("${meta.facebook.config-id:}")
        private String facebookConfigId;

        public SecurityConfig(UserDetailsService customUserDetailsService,
                        JwtAuthenticationFilter jwtAuthenticationFilter,
                        FacebookOAuth2SuccessHandler facebookOAuth2SuccessHandler,
                        ClientRegistrationRepository clientRegistrationRepository) {
                this.customUserDetailsService = customUserDetailsService;
                this.jwtAuthenticationFilter = jwtAuthenticationFilter;
                this.facebookOAuth2SuccessHandler = facebookOAuth2SuccessHandler;
                this.clientRegistrationRepository = clientRegistrationRepository;
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        @Bean
        public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
                return config.getAuthenticationManager();
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                http
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .csrf(csrf -> csrf.disable())
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(auth -> auth
                                                // Public endpoints
                                                .requestMatchers("/", "/home", "/register", "/register-submit")
                                                .permitAll()
                                                .requestMatchers("/actuator/**").permitAll()
                                                .requestMatchers("/h2-console/**").permitAll() // H2 Database Console
                                                                                               // (development only)
                                                .requestMatchers("/api/v1/webhooks/**").permitAll()
                                                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                                                "/api/v1/auth/refresh", "/api/v1/auth/google")
                                                .permitAll()
                                                // Meta callback endpoints (no auth - verified via signed_request)
                                                .requestMatchers("/meta/**").permitAll()
                                                // OAuth2 endpoints - Spring Security handles these automatically:
                                                // GET /oauth2/authorization/facebook - initiates OAuth flow
                                                // GET /login/oauth2/code/facebook - callback from Facebook
                                                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                                                // Instagram connection start (uses one-time token, no JWT needed)
                                                .requestMatchers("/instagram/connect/start").permitAll()
                                                // Protected endpoints (including /api/v1/integrations/instagram/*)
                                                .requestMatchers("/api/v1/**").authenticated()
                                                .requestMatchers("/dashboard").authenticated()
                                                .anyRequest().authenticated())
                                // Enable OAuth2 Login for Facebook/Instagram
                                // Uses custom authorization request resolver to add config_id parameter
                                .oauth2Login(oauth2 -> oauth2
                                                .authorizationEndpoint(authorization -> authorization
                                                                .authorizationRequestResolver(
                                                                                new FacebookOAuth2AuthorizationRequestResolver(
                                                                                                clientRegistrationRepository,
                                                                                                "/oauth2/authorization",
                                                                                                facebookConfigId)))
                                                .successHandler(facebookOAuth2SuccessHandler))
                                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();

                // Parse comma-separated origins string
                String[] origins = allowedOriginsString.split(",");
                for (int i = 0; i < origins.length; i++) {
                        origins[i] = origins[i].trim();
                }
                configuration.setAllowedOrigins(Arrays.asList(origins));

                configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
                configuration.setAllowedHeaders(
                                Arrays.asList("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));
                configuration.setExposedHeaders(Arrays.asList("Authorization"));
                configuration.setAllowCredentials(true);
                configuration.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }
}
