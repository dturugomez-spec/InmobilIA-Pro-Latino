package com.inmobilia;

import com.inmobilia.demo.EsquemaSql;
import com.inmobilia.demo.FakeSupabase;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.LeadAutoResponderService;
import com.inmobilia.service.RespuestaLeadsService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Respuesta inmediata a los contactos: encendida por defecto, contesta con huecos de visita reales
 * y con las respuestas del cliente a la agencia, crea una llamada urgente si no hay email y mide
 * el tiempo de respuesta venga de donde venga.
 */
@SpringBootTest(properties = {
        "supabase.url=https://demo.supabase.co",
        "supabase.key=clave-de-prueba",
        "spring.main.allow-bean-definition-overriding=true",
        "LOG_APP=WARN"
})
@AutoConfigureMockMvc
class RespuestaLeadsTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final String ORG = "00000000-0000-4000-8000-0000000000f1";

    @TestConfiguration
    static class Config {
        @Bean(name = "httpClient")
        java.net.http.HttpClient httpClient() throws Exception {
            java.net.http.HttpClient c = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
            org.mockito.Mockito.when(c.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new java.io.IOException("sin red en pruebas"));
            return c;
        }

        @Bean
        @Primary
        WebClient webClientDePrueba() {
            return WebClient.builder().exchangeFunction(BD)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired SupabaseService supabase;
    @Autowired RespuestaLeadsService respuestas;

    @BeforeEach
    void preparar() throws Exception {
        BD.vaciar();
        BD.esquema = EsquemaSql.delProyecto();
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", ORG, "email", "agencia@naves.example.com", "name", "Agencia",
                "role", "admin", "active", true, "plan", "business", "plan_status", "active")));
        BD.tabla("business_profile").add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "org_id", ORG,
                "legal_name", "Naves del Sur SL", "contact_email", "hola@navesdelsur.es", "contact_phone", "954 000 000")));
    }

    private static MockHttpSession sesion(String rol) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, ORG, ORG, "agencia@naves.example.com", "Persona", rol);
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    private static String lead(String nombre, String email, String telefono, Instant creado) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("id", id); l.put("user_id", ORG); l.put("nombre", nombre); l.put("email", email); l.put("telefono", telefono);
        l.put("origen", "idealista"); l.put("etapa", "Nuevo"); l.put("created_at", creado.toString());
        BD.tabla("leads").add(l);
        return id;
    }

    private static Map<String, Object> fila(String tabla, String id) {
        return BD.tabla(tabla).stream().filter(f -> id.equals(String.valueOf(f.get("id")))).findFirst().orElse(null);
    }

    @Test
    void contestaConHuecosRealesYLasRespuestasVanALaAgencia() {
        String id = lead("Lucía", "lucia@gmail.com", null, Instant.now());
        EmailService email = mock(EmailService.class);
        when(email.sendConRespuesta(anyString(), anyString(), anyString(), anyString())).thenReturn(new EmailService.SendResult(true, "ok"));
        LeadAutoResponderService auto = new LeadAutoResponderService(supabase, email, respuestas);
        Map<String, Object> propiedad = Map.of("id", UUID.randomUUID().toString(), "name", "Nave en Dos Hermanas");

        auto.responder(ORG, fila("leads", id), propiedad);   // sin auto_respuesta_leads: encendida por defecto
        verify(email).sendConRespuesta(eq("lucia@gmail.com"), contains("Nave en Dos Hermanas"),
                argThat(h -> h.contains("huecos libres") && h.contains("954 000 000")), eq("hola@navesdelsur.es"));
        assertNotNull(fila("leads", id).get("primera_respuesta_at"));
        assertEquals("auto_email", fila("leads", id).get("primera_respuesta_via"));

        // Apagada por la agencia: no sale nada
        BD.tabla("users").get(0).put("auto_respuesta_leads", false);
        String otro = lead("Pedro", "pedro@gmail.com", null, Instant.now());
        auto.responder(ORG, fila("leads", otro), propiedad);
        verify(email, times(1)).sendConRespuesta(anyString(), anyString(), anyString(), anyString());
        assertNull(fila("leads", otro).get("primera_respuesta_at"));
    }

    @Test
    void sinEmailCreaUnaLlamadaUrgente() {
        String id = lead("Contacto de Fotocasa", null, "612345678", Instant.now());
        EmailService email = mock(EmailService.class);
        new LeadAutoResponderService(supabase, email, respuestas).responder(ORG, fila("leads", id), null);
        verifyNoInteractions(email);
        assertEquals(1, BD.tabla("tareas").size());
        Map<String, Object> t = BD.tabla("tareas").get(0);
        assertEquals("urgente", t.get("prioridad"));
        assertEquals(id, t.get("lead_id"));
        assertTrue(String.valueOf(t.get("titulo")).contains("612345678"));
    }

    @Test
    void cualquierRespuestaCuentaYSeMide() throws Exception {
        String porEtapa = lead("Ana", "ana@gmail.com", null, Instant.now().minus(Duration.ofMinutes(30)));
        mvc.perform(post("/crud/leads").session(sesion("admin")).param("id", porEtapa).param("nombre", "Ana").param("etapa", "Contactado"));
        assertEquals("etapa", fila("leads", porEtapa).get("primera_respuesta_via"));

        String porBandeja = lead("Luis", "luis@gmail.com", null, Instant.now().minus(Duration.ofHours(3)));
        mvc.perform(post("/bandeja/" + porBandeja + "/contactado").session(sesion("admin")));
        assertEquals("agente", fila("leads", porBandeja).get("primera_respuesta_via"));
        Object primera = fila("leads", porBandeja).get("primera_respuesta_at");
        respuestas.marcarRespuesta(ORG, porBandeja, "seguimiento");
        assertEquals(primera, fila("leads", porBandeja).get("primera_respuesta_at"), "solo cuenta la primera");

        lead("Sin contestar", "nadie@gmail.com", null, Instant.now().minus(Duration.ofHours(5)));
        String html = mvc.perform(get("/respuesta-leads").session(sesion("admin"))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("Sin contestar") && html.contains("esperando 5 h"));
        assertTrue(html.contains("Respuesta automática encendida"));

        // Un comercial ve la pantalla pero no apaga la respuesta automática de toda la agencia
        mvc.perform(post("/respuesta-leads/auto").session(sesion("comercial")).param("activar", "false"));
        assertNull(BD.tabla("users").get(0).get("auto_respuesta_leads"));
        mvc.perform(post("/respuesta-leads/auto").session(sesion("admin")).param("activar", "false"));
        assertEquals(false, BD.tabla("users").get(0).get("auto_respuesta_leads"));
    }

    @Test
    void metricasYHuecos() {
        OffsetDateTime ahora = OffsetDateTime.parse("2026-11-06T12:00:00Z");
        List<Map<String, Object>> leads = List.of(
                Map.of("created_at", "2026-11-06T10:00:00Z", "primera_respuesta_at", "2026-11-06T10:02:00Z", "primera_respuesta_via", "auto_email", "origen", "idealista"),
                Map.of("created_at", "2026-11-06T10:00:00Z", "primera_respuesta_at", "2026-11-06T11:00:00Z", "origen", "idealista"),
                Map.of("created_at", "2026-11-06T09:00:00Z", "etapa", "Nuevo", "origen", "fotocasa"),
                Map.of("created_at", "2026-11-06T09:00:00Z", "etapa", "Perdido", "origen", "fotocasa"));
        RespuestaLeadsService.Metricas m = RespuestaLeadsService.calcular(leads, ahora);
        assertEquals(4, m.recibidos());
        assertEquals(2, m.respondidos());
        assertEquals(31L, m.medianaMin(), "mediana de 2 y 60 minutos");
        assertEquals(1, m.enObjetivo());
        assertEquals(1, m.automaticas());
        assertEquals(1, m.sinResponder().size(), "los perdidos no cuentan como pendientes");
        assertEquals("3 h", m.sinResponder().get(0).get("esperando"));

        ZoneId madrid = RespuestaLeadsService.zona();
        ZonedDateTime viernesTarde = ZonedDateTime.of(2026, 11, 6, 16, 0, 0, 0, madrid);
        List<ZonedDateTime> h = RespuestaLeadsService.huecos(viernesTarde,
                List.of(ZonedDateTime.of(2026, 11, 7, 11, 30, 0, 0, madrid)), 3);
        assertEquals(3, h.size());
        assertEquals(LocalDate.of(2026, 11, 9), h.get(0).toLocalDate(), "viernes 19:00 ya pasó; sábado 11:00 está ocupado; domingo no");
        assertEquals(10, h.get(0).getHour());
        assertTrue(h.stream().noneMatch(x -> x.getDayOfWeek() == DayOfWeek.SUNDAY));
    }
}
