package com.inmobilia.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;

@Configuration
public class AppConfig {

    /**
     * El arranque lazy (spring.main.lazy-initialization=true) reduce mucho la RAM
     * de inicio, PERO desactivaría los @Scheduled (crons) porque nadie los "usa".
     * Este filtro fuerza el arranque EAGER de cualquier bean que tenga métodos
     * @Scheduled, para que los crons (trials, recordatorios, divisas, etc.) sí corran.
     */
    @Bean
    public LazyInitializationExcludeFilter scheduledBeansEagerFilter() {
        return (beanName, beanDefinition, beanType) ->
                Arrays.stream(beanType.getMethods())
                        .anyMatch(m -> m.isAnnotationPresent(Scheduled.class));
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public WebClient webClient() {
        // Reactor Netty usa por defecto un resolvedor DNS asíncrono propio que FALLA
        // en Railway/algunos contenedores ("Failed to resolve host"). Forzamos el
        // resolvedor del JDK (bloqueante, java.net.InetAddress), que sí resuelve bien.
        reactor.netty.http.client.HttpClient nettyClient = reactor.netty.http.client.HttpClient.create()
                .resolver(io.netty.resolver.DefaultAddressResolverGroup.INSTANCE);
        return WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(nettyClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }
}
