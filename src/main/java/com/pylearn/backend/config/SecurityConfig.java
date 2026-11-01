package com.pylearn.backend.config;

import com.pylearn.backend.service.AuthUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security Configuration supporting role-based access control:
 * - ROLE_STUDENT: Can view curriculum, run Python IDE via WebSocket, take mock exams, save progress
 * - ROLE_TEACHER / ROLE_ADMIN: Can also create/update/delete CBSE curriculum chapters,
 *   practical programs, mock exam questions, and viva flashcards via /api/admin/**
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final AuthUserService authUserService;

    public SecurityConfig(AuthUserService authUserService) {
        this.authUserService = authUserService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.ico", "/static/**").permitAll()
                        .requestMatchers("/ws/**", "/api/health", "/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/catalog", "/api/curriculum", "/api/navigation/**", "/api/chapters/**", "/api/practicals/**", "/api/exams/**", "/api/ide/**", "/api/cheatsheet/**").permitAll()
                        .requestMatchers("/api/progress/**").permitAll()
                        .requestMatchers("/api/admin/**").hasAnyRole("TEACHER", "ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new BearerTokenAuthFilter(authUserService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    static class BearerTokenAuthFilter extends OncePerRequestFilter {
        private final AuthUserService authUserService;

        BearerTokenAuthFilter(AuthUserService authUserService) {
            this.authUserService = authUserService;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && !authHeader.isBlank()) {
                AuthUserService.AppUser user = authUserService.resolveToken(authHeader);
                if (user != null) {
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role()));
                    var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}
