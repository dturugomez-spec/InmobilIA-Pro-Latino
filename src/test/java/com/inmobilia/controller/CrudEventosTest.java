package com.inmobilia.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Eventos de automatización del CRUD: solo cuando el estado CAMBIA a ese valor. */
class CrudEventosTest {

    @Test
    void leadCualificadoEInmuebleVendido() {
        assertEquals("lead_calificado", CrudController.eventoAlActualizar("leads", Map.of("etapa", "Contactado"), Map.of("etapa", "Cualificado")));
        assertNull(CrudController.eventoAlActualizar("leads", Map.of("etapa", "Cualificado"), Map.of("etapa", "Cualificado")), "ya lo era");
        assertNull(CrudController.eventoAlActualizar("leads", Map.of("etapa", "Nuevo"), Map.of("nombre", "Ana")), "no cambia la etapa");
        assertEquals("propiedad_vendida", CrudController.eventoAlActualizar("propiedades", Map.of("status", "Libre"), Map.of("status", "Vendido")));
        assertNull(CrudController.eventoAlActualizar("propiedades", Map.of("status", "Vendida"), Map.of("status", "Vendido")));
        assertNull(CrudController.eventoAlActualizar("inquilinos", Map.of(), Map.of("status", "Vendido")));
    }

    @Test
    void referenciaAutomatica() {
        String r = CrudController.nuevaReferencia();
        assertTrue(r.matches("REF-\\d{4}-[2-9A-HJ-NP-Z]{5}"), r);
        assertNotEquals(r, CrudController.nuevaReferencia());
    }
}
