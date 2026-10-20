package com.inmobilia.controller;

import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Listados sin una consulta por fila, y chat de equipo sin acceso a canales ajenos. */
class ConsultasEnLoteTest {

    @Test
    void enriquecer_haceUnaConsultaCadaCienYFiltraPorInmobiliaria() {
        SupabaseService s = mock(SupabaseService.class, CALLS_REAL_METHODS);
        List<String> filtros = new ArrayList<>();
        doAnswer(inv -> {
            String f = inv.getArgument(1, String.class);
            filtros.add(f);
            String ids = f.replaceAll(".*id=in\\.\\(([^)]*)\\).*", "$1");
            List<Map<String, Object>> r = new ArrayList<>();
            for (String id : ids.split(",")) r.add(new HashMap<>(Map.of("id", id, "name", "Prop " + id)));
            return r;
        }).when(s).selectFilter(eq("propiedades"), anyString());

        List<Map<String, Object>> filas = new ArrayList<>();
        for (int i = 0; i < 250; i++) filas.add(new HashMap<>(Map.of("propiedad_id", String.valueOf(1000 + i))));
        filas.add(new HashMap<>(Map.of("propiedad_id", "1;drop")));   // id raro: ni se consulta
        Map<String, Object> sinId = new HashMap<>();
        sinId.put("propiedad_id", null);
        filas.add(sinId);

        s.enriquecer(filas, "propiedad_id", "propiedades", "propiedad", "org1");

        assertEquals(3, filtros.size(), "250 ids → 3 consultas, no 250");
        assertTrue(filtros.stream().allMatch(f -> f.contains("&user_id=eq.org1")), "solo las de la inmobiliaria");
        assertFalse(String.join(" ", filtros).contains("drop"));
        assertEquals("Prop 1000", ((Map<?, ?>) filas.get(0).get("propiedad")).get("name"));
        assertEquals("Prop 1249", ((Map<?, ?>) filas.get(249).get("propiedad")).get("name"));
        assertNull(filas.get(250).get("propiedad"));
        assertNull(filas.get(251).get("propiedad"));
    }

    @Test
    void chatDeEquipo_noSeLeeNiSeEscribeEnCanalesAjenos() {
        SupabaseService supabase = mock(SupabaseService.class);
        when(supabase.selectFilter(eq("chat_canales"), anyString())).thenAnswer(inv -> {
            String f = inv.getArgument(1, String.class);
            if (f.equals("id=eq.11&org_id=eq.org1")) return List.of(Map.of("id", 11, "org_id", "org1", "tipo", "publico"));
            if (f.equals("id=eq.12&org_id=eq.org1"))
                return List.of(Map.of("id", 12, "org_id", "org1", "tipo", "privado", "miembros_ids", List.of("u-otro")));
            return List.of(); // el 99 es de otra inmobiliaria
        });
        TeamChatV2Controller chat = new TeamChatV2Controller(supabase, mock(NotificationService.class));
        assertNotNull(chat.canalPermitido("11", "org1", "u1"));
        assertNull(chat.canalPermitido("12", "org1", "u1"), "privado y no soy miembro");
        assertNull(chat.canalPermitido("99", "org1", "u1"), "canal de otra inmobiliaria");
        assertNull(chat.canalPermitido("1 or 1=1", "org1", "u1"));

        MockHttpSession sesion = new MockHttpSession();
        SessionUtils.loginFull(sesion, "u1", "org1", "a@b.es", "Ana", "admin");
        assertEquals(List.of(), chat.nuevos("99", 0, sesion));
        assertEquals(false, chat.enviarMensaje(new HashMap<>(Map.of("canal_id", "99", "contenido", "hola")), sesion).get("ok"));
        verify(supabase, never()).insert(eq("chat_mensajes"), anyMap());
        verify(supabase, never()).selectFilter(eq("chat_mensajes"), contains("canal_id=eq.99"));
    }
}
