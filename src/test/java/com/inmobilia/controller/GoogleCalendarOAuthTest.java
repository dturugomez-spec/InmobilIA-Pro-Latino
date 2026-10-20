package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import com.inmobilia.util.UrlBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Conexión con Google Calendar: vuelta a la web con www y "state" contra la conexión forzada. */
class GoogleCalendarOAuthTest {

    private final SupabaseService supabase = mock(SupabaseService.class);

    private GoogleCalendarController ctrl() throws Exception {
        Constructor<?> c = GoogleCalendarController.class.getDeclaredConstructors()[0];
        Object[] args = new Object[c.getParameterCount()];
        for (int i = 0; i < args.length; i++) args[i] = c.getParameterTypes()[i] == SupabaseService.class ? supabase : mock(c.getParameterTypes()[i]);
        return (GoogleCalendarController) c.newInstance(args);
    }

    @Test
    void direccionDeVuelta_conWwwOLaConfigurada() {
        assertEquals(UrlBase.BASE + "/google-calendar/callback", GoogleCalendarController.redireccion(null));
        assertEquals("https://x.es/cb", GoogleCalendarController.redireccion(" https://x.es/cb "));
        assertEquals(UrlBase.BASE + "/google-calendar/callback", GoogleCalendarController.redireccion("  "));
    }

    @Test
    void sinStateValido_noSeConectaNinguna_cuenta() throws Exception {
        when(supabase.selectFilter(anyString(), anyString())).thenReturn(List.of());
        GoogleCalendarController g = ctrl();
        MockHttpSession sesion = new MockHttpSession();
        SessionUtils.login(sesion, "org1", "a@b.es", "Ana");

        // Enlace preparado por otra web: code de SU cuenta y sin state (o uno inventado)
        assertEquals("redirect:/google-calendar", g.callback("code-del-atacante", null, null, sesion));
        assertEquals("redirect:/google-calendar", g.callback("code-del-atacante", "inventado", null, sesion));
        verify(supabase, never()).insert(eq("integraciones"), anyMap());
        verify(supabase, never()).update(eq("integraciones"), anyString(), anyMap());

        // El panel genera un state de un solo uso que va en el enlace a Google
        ExtendedModelMap model = new ExtendedModelMap();
        g.panel(sesion, model);
        String state = (String) sesion.getAttribute("gcal_oauth_state");
        assertNotNull(state);
        assertTrue(String.valueOf(model.get("authUrl")).contains("&state=" + state));
        assertTrue(String.valueOf(model.get("authUrl")).contains("redirect_uri=https%3A%2F%2F"));
        // Cancelado en Google con state bueno: no se guarda nada y el state ya no vale
        assertEquals("redirect:/google-calendar", g.callback(null, state, "access_denied", sesion));
        assertNull(sesion.getAttribute("gcal_oauth_state"));
        verify(supabase, never()).insert(eq("integraciones"), anyMap());
    }
}
