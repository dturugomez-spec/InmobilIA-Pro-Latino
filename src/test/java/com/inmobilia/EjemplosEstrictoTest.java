package com.inmobilia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inmobilia.service.DemoDataService;
import com.inmobilia.service.EjemplosService;
import com.inmobilia.service.SupabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Los ejemplos se cargan aunque la base de datos se haya creado con la versión MÁS estricta de
 * cada tabla de los .sql del proyecto (columnas obligatorias de cualquiera de sus definiciones).
 */
class EjemplosEstrictoTest {

    @Test
    void ningunaFilaDeEjemploLaRechazariaLaBaseReal() throws Exception {
        FakeSupabase bd = new FakeSupabase();
        bd.esquema = EsquemaSql.estricto();
        WebClient cliente = WebClient.builder().exchangeFunction(bd).build();
        SupabaseService supabase = new SupabaseService(cliente, new ObjectMapper(), null, "https://demo.supabase.co", "clave");
        Map<String, Object> r = new EjemplosService(supabase, new DemoDataService(supabase, new com.inmobilia.service.PaisService(supabase), new com.inmobilia.service.CurrencyService())).cargarAhora("00000000-0000-4000-8000-000000000001");
        List<String> rechazos = new ArrayList<>(new LinkedHashSet<>(bd.rechazos));
        Files.writeString(Path.of("target", "ejemplos-estricto.txt"), r.get("mensaje") + "\n" + String.join("\n", rechazos));
        assertTrue(rechazos.isEmpty(), "Filas de ejemplo que la base real rechazaría:\n" + String.join("\n", rechazos));
    }
}
