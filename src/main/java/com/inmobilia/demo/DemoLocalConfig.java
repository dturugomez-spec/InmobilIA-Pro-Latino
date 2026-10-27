package com.inmobilia.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Demo local: la app entera en un ordenador, sin Supabase, con la inmobiliaria de ejemplo cargada
 * para enseñársela a clientes. Se activa con el perfil "demo-local" (nunca en Railway):
 * mvn spring-boot:run -Dspring-boot.run.profiles=demo-local y abrir http://localhost:8091/entrar
 *
 * Todas las llamadas a Supabase las responde {@link FakeSupabase} en memoria: cada vez que se abre
 * la demo, la inmobiliaria de ejemplo está como nueva y nada de lo que se escriba sale del equipo.
 */
@Configuration
@Profile("demo-local")
public class DemoLocalConfig {

    @Bean
    public FakeSupabase supabaseEnMemoria() throws Exception {
        FakeSupabase bd = new FakeSupabase();
        bd.dominio = "localhost";                 // supabase.url=http://localhost:<puerto>
        bd.esquema = EsquemaSql.delClasspath();   // rechaza lo mismo que la base real
        return bd;
    }

    /** Sustituye al WebClient de AppConfig: SupabaseService y StorageService hablan con la memoria. */
    @Bean
    @Primary
    public WebClient webClientDemoLocal(FakeSupabase bd) {
        return WebClient.builder().exchangeFunction(bd)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
    }
}
