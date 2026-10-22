package com.inmobilia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Claves de Supabase: las nuevas (sb_secret_…) van solo en apikey; las antiguas service_role
 * (JWT) también como Bearer. Permite cambiar a una clave nueva y desactivar la antigua.
 */
class ClaveSupabaseTest {

    private final List<HttpHeaders> enviadas = new ArrayList<>();

    private SupabaseService con(String clave) {
        WebClient cliente = WebClient.builder().exchangeFunction(req -> {
            enviadas.add(req.headers());
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("[]").build());
        }).build();
        return new SupabaseService(cliente, new ObjectMapper(), null, "https://x.supabase.co", clave);
    }

    @Test
    void claveNueva_soloEnApikey() {
        con("sb_secret_abc123").selectFilter("leads", "user_id=eq.org1&limit=1");
        assertEquals("sb_secret_abc123", enviadas.get(0).getFirst("apikey"));
        assertNull(enviadas.get(0).getFirst(HttpHeaders.AUTHORIZATION), "una sb_secret_ como Bearer la rechaza Supabase");
    }

    @Test
    void claveAntigua_tambienComoBearer() {
        con("eyJhbGciOiJIUzI1NiJ9.e30.firma").selectFilter("leads", "user_id=eq.org1&limit=1");
        assertEquals("Bearer eyJhbGciOiJIUzI1NiJ9.e30.firma", enviadas.get(0).getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void deteccion() {
        assertTrue(SupabaseService.esClaveNueva("sb_secret_x"));
        assertTrue(SupabaseService.esClaveNueva(" sb_publishable_x"));
        assertFalse(SupabaseService.esClaveNueva("eyJxxx"));
        assertFalse(SupabaseService.esClaveNueva(null));
    }
}
