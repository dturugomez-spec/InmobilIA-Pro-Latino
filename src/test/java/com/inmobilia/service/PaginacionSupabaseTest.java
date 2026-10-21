package com.inmobilia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Supabase devuelve como mucho 1.000 filas por consulta: se leen todas las páginas. */
class PaginacionSupabaseTest {

    private final List<String> pedidas = new ArrayList<>();

    /** Simula una tabla de "total" filas que Supabase sirve en trozos de 1.000 como máximo. */
    private SupabaseService supabase(int total) {
        WebClient cliente = WebClient.builder().exchangeFunction(req -> {
            String url = req.url().toString();
            pedidas.add(url);
            Matcher l = Pattern.compile("[?&]limit=(\\d+)").matcher(url);
            Matcher o = Pattern.compile("[?&]offset=(\\d+)").matcher(url);
            int limit = l.find() ? Math.min(1000, Integer.parseInt(l.group(1))) : 1000;
            int offset = o.find() ? Integer.parseInt(o.group(1)) : 0;
            int n = Math.max(0, Math.min(limit, total - offset));
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < n; i++) json.append(i == 0 ? "" : ",").append("{\"id\":").append(offset + i).append('}');
            json.append(']');
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(json.toString()).build());
        }).build();
        return new SupabaseService(cliente, new ObjectMapper(), null, "https://x.supabase.co", "clave");
    }

    @Test
    void conMasDeMilFilas_seLeenTodas() {
        List<?> filas = supabase(2250).selectFilter("transacciones", "user_id=eq.org1");
        assertEquals(2250, filas.size(), "antes se quedaba en 1.000 sin avisar");
        assertEquals(3, pedidas.size());
        assertTrue(pedidas.get(2).contains("offset=2000"));
    }

    @Test
    void conLimiteExplicito_unaSolaConsulta() {
        assertEquals(5, supabase(2250).selectFilter("leads", "user_id=eq.org1&limit=5").size());
        assertEquals(1, pedidas.size());
    }

    @Test
    void query_tambienPagina_yMenosDeMilEsUnaConsulta() {
        assertEquals(1500, supabase(1500).query("leads", "org1").size());
        pedidas.clear();
        assertEquals(40, supabase(40).query("leads", "org1").size());
        assertEquals(1, pedidas.size());
    }
}
