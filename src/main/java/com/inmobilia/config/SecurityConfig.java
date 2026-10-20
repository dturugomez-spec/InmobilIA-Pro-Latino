package com.inmobilia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * La autenticación y los permisos los hace la propia app (AuthController, SessionGuardInterceptor).
 * Spring Security se usa para las cabeceras de seguridad (HSTS, nosniff, anti-iframe, etc.).
 * La protección CSRF va en OrigenFilter (comprobación de origen) + cookie SameSite=Lax.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .headers(h -> h
                .frameOptions(f -> f.sameOrigin())
                // No filtrar URLs internas (con ids o tokens) a webs de terceros
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // Cámara, micro y ubicación solo para nuestra propia web, nunca para iframes de terceros
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "camera=(self), microphone=(self), geolocation=(self), payment=(self), usb=(), interest-cohort=()")));
        return http.build();
    }
}
