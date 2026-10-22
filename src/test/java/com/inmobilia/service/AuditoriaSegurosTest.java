package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Auditoría de cartera con las columnas reales de seguros (las de /seguros). */
class AuditoriaSegurosTest {

    @Test
    void reconocePolizasRealesYAvisaDeLasVencidas() {
        SupabaseService supabase = mock(SupabaseService.class);
        when(supabase.query(anyString(), anyString())).thenReturn(List.of());
        when(supabase.query(eq("propiedades"), anyString())).thenReturn(List.of(
                Map.of("id", "p1", "name", "Piso Gran Vía", "status", "Ocupado", "current_value", 270000),
                Map.of("id", "p2", "name", "Local Atocha", "status", "Ocupado", "current_value", 380000),
                Map.of("id", "p3", "name", "Casa Alcalá", "status", "Ocupado", "current_value", 340000)));
        when(supabase.query(eq("seguros"), anyString())).thenReturn(List.of(
                Map.of("id", "s1", "propiedad_id", "p1", "tipo", "hogar", "estado", "activa",
                        "fecha_vencimiento", LocalDate.now().plusDays(200).toString(), "prima_anual", 285),
                Map.of("id", "s2", "propiedad_id", "p2", "tipo", "impago_alquiler", "estado", "activa",
                        "fecha_vencimiento", LocalDate.now().minusDays(3).toString(), "prima_anual", 540)));

        var informe = new PortfolioAuditService(supabase).audit("org1");
        var sinSeguro = informe.findings().stream().filter(f -> f.code().equals("PROP_SIN_SEGURO")).map(f -> f.entity()).toList();
        assertEquals(List.of("Casa Alcalá"), sinSeguro, "Gran Vía y Atocha tienen póliza: solo Alcalá está sin asegurar");
        var vencida = informe.findings().stream().filter(f -> f.code().equals("SEGURO_VENCIDO")).findFirst().orElseThrow();
        assertEquals("Local Atocha", vencida.entity());
        assertEquals("/seguros/s2", vencida.actionUrl());
    }
}
