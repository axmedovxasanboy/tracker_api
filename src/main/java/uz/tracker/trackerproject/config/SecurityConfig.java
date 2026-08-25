package uz.tracker.trackerproject.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import uz.tracker.trackerproject.security.JwtAuthFilter;

/**
 * Stateless JWT security for the single-account setup. CSRF is off (no cookies — tokens go
 * in the Authorization header); CORS is wired from {@link CorsConfig}'s CorsConfigurationSource.
 * Everything under /api/v1/** requires a valid access token except the auth endpoints and the
 * health probe. Unauthenticated access returns a plain 401 (no login redirect / Basic prompt).
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // Allow absolutely every request without authentication
                    .anyRequest().permitAll()
            )
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());
            
            // REMOVED: .exceptionHandling(...)
            // REMOVED: .addFilterBefore(jwtAuthFilter, ...)
    
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
