package com.inmobilia.config;

import com.inmobilia.service.RateLimiter;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;

/** Formularios públicos: límite de envíos por IP para que no se puedan llenar de spam. */
class LimitePublicoFilterTest {

    private final LimitePublicoFilter filtro = new LimitePublicoFilter(new RateLimiter());

    private MockHttpServletResponse enviar(String metodo, String uri, String ip, MockHttpSession sesion) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, uri);
        req.setRemoteAddr(ip);
        req.addHeader("Accept", "application/json");
        if (sesion != null) req.setSession(sesion);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filtro.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    @Test
    void cadaRutaTieneSuLimite() {
        assertEquals("formulario", LimitePublicoFilter.limiteDe("/m/mi-agencia/contacto").grupo());
        assertEquals("formulario", LimitePublicoFilter.limiteDe("/tasacion").grupo());
        assertEquals("formulario", LimitePublicoFilter.limiteDe("/newsletter/suscribir").grupo());
        assertEquals("alta", LimitePublicoFilter.limiteDe("/solicitar-cuenta").grupo());
        assertEquals("valoracion", LimitePublicoFilter.limiteDe("/v/abc123/calcular").grupo(), "gasta créditos de IA");
        assertEquals("calculo", LimitePublicoFilter.limiteDe("/h/abc123/calcular").grupo());
        assertEquals("calculo", LimitePublicoFilter.limiteDe("/calculadora-hipoteca").grupo());
        assertEquals("open-house", LimitePublicoFilter.limiteDe("/open-house/publico/tok/registrar").grupo());
        assertEquals("interaccion", LimitePublicoFilter.limiteDe("/descubre/swipe").grupo(), "un envío por favorito");
        assertEquals("interaccion", LimitePublicoFilter.limiteDe("/c/tok/reaccion").grupo());
        // Fuera: webhooks, API con clave y lo que ya tiene su límite propio
        assertNull(LimitePublicoFilter.limiteDe("/stripe/webhook"));
        assertNull(LimitePublicoFilter.limiteDe("/whatsapp/webhook"));
        assertNull(LimitePublicoFilter.limiteDe("/api/v2/leads"));
        assertNull(LimitePublicoFilter.limiteDe("/login"));
        assertNull(LimitePublicoFilter.limiteDe("/login/2fa"));
        assertNull(LimitePublicoFilter.limiteDe("/chatbot/api/mensaje"));
        assertEquals("formulario", LimitePublicoFilter.limiteDe("/chatbot/api/datos-visitante").grupo());
    }

    @Test
    void elFormularioPublicoSeCortaTrasVeinteEnviosPorHora() throws Exception {
        for (int i = 0; i < 20; i++) assertEquals(200, enviar("POST", "/propiedad/p1/contactar", "8.8.8.8", null).getStatus());
        MockHttpServletResponse bloqueada = enviar("POST", "/m/agencia/contacto", "8.8.8.8", null);
        assertEquals(429, bloqueada.getStatus());
        assertTrue(bloqueada.getContentAsString().contains("Demasiados envíos"));
        // Otra IP sigue pudiendo
        assertEquals(200, enviar("POST", "/propiedad/p1/contactar", "9.9.9.9", null).getStatus());
    }

    @Test
    void altasDeCuenta_cincoPorHora() throws Exception {
        for (int i = 0; i < 5; i++) assertEquals(200, enviar("POST", "/register", "7.7.7.7", null).getStatus());
        assertEquals(429, enviar("POST", "/register", "7.7.7.7", null).getStatus());
    }

    @Test
    void sinLimite_conSesionWebhooksYLecturas() throws Exception {
        MockHttpSession sesion = new MockHttpSession();
        SessionUtils.login(sesion, "org1", "a@b.es", "A");
        for (int i = 0; i < 50; i++) {
            assertEquals(200, enviar("POST", "/calculadora-hipoteca", "6.6.6.6", sesion).getStatus());
            assertEquals(200, enviar("POST", "/stripe/webhook", "6.6.6.6", null).getStatus());
            assertEquals(200, enviar("GET", "/m/agencia", "6.6.6.6", null).getStatus());
        }
    }
}
