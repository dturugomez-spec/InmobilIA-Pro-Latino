package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Vaciar un campo al editar: antes el valor antiguo se quedaba porque los null se descartaban. */
class VaciarCamposTest {

    private Map<String, Object> datos() {
        Map<String, Object> d = new HashMap<>();
        d.put("nombre", "Ana");
        d.put("telefono", SupabaseService.VACIAR);   // el usuario lo ha borrado en el formulario
        d.put("notas", null);                        // no venía: no se toca
        d.put("precio", Double.NaN);                 // inválido: no se toca
        return d;
    }

    @Test
    void alActualizar_vaciarGuardaNullYLoDemasNoSeToca() {
        Map<String, Object> limpio = SupabaseService.clean(datos(), true);
        assertTrue(limpio.containsKey("telefono"));
        assertNull(limpio.get("telefono"));
        assertEquals("Ana", limpio.get("nombre"));
        assertFalse(limpio.containsKey("notas"));
        assertFalse(limpio.containsKey("precio"));
    }

    @Test
    void alCrear_vaciarSeIgnoraYValeElValorPorDefecto() {
        Map<String, Object> limpio = SupabaseService.clean(datos(), false);
        assertFalse(limpio.containsKey("telefono"));
        assertEquals(Map.of("nombre", "Ana"), limpio);
    }
}
