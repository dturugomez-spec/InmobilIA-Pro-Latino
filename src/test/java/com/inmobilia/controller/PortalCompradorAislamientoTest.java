package com.inmobilia.controller;

import com.inmobilia.service.BrandingService;
import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Portal del comprador: nada de leads ni inmuebles de otra inmobiliaria. */
class PortalCompradorAislamientoTest {

    private final SupabaseService supabase = mock(SupabaseService.class);
    private final PortalCompradorController ctrl =
            new PortalCompradorController(supabase, mock(com.inmobilia.service.PaisService.class),
                    mock(NotificationService.class), mock(BrandingService.class));
    private final MockHttpSession sesion = new MockHttpSession();

    PortalCompradorAislamientoTest() {
        SessionUtils.login(sesion, "org1", "a@b.es", "Ana");
        when(supabase.selectOwned(anyString(), anyString(), anyString())).thenReturn(List.of()); // nada es de org1
        when(supabase.insert(anyString(), anyMap())).thenReturn(Map.of("id", "s1"));
    }

    @Test
    void conElLeadDeOtraInmobiliaria_noSeVeSuNombre() {
        ctrl.nueva("Selección", "lead-ajeno", null, sesion);
        verify(supabase).insert(eq("selecciones_comprador"), argThat(m ->
                !m.containsKey("comprador_nombre") && !m.containsKey("lead_id")));
        verify(supabase, never()).selectFilter(eq("leads"), startsWith("id=eq.lead-ajeno"));
    }

    @Test
    void noSeMeteUnInmuebleDeOtraInmobiliaria() {
        when(supabase.selectFilter(eq("selecciones_comprador"), anyString()))
                .thenReturn(List.of(Map.of("id", "s1", "user_id", "org1")));
        ctrl.addItem("s1", "prop-ajena", sesion);
        verify(supabase, never()).insert(eq("seleccion_items"), anyMap());
    }
}
