package com.inmobilia;

import com.inmobilia.demo.EsquemaSql;
import com.inmobilia.demo.FakeSupabase;
import com.inmobilia.service.AutopilotComunicacionService;
import com.inmobilia.service.EmailService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Communication AutoPilot, comandos de voz, gestión multi-empresa y Negotiation Co-Pilot dentro de
 * la app entera (Supabase en memoria; el correo, simulado).
 */
@SpringBootTest(properties = {
        "supabase.url=https://demo.supabase.co",
        "supabase.key=clave-de-prueba",
        "spring.main.allow-bean-definition-overriding=true",
        "ADMIN_EMAILS=dueno@inmobiliapro.example.com",
        "LOG_APP=WARN"
})
@AutoConfigureMockMvc
class HerramientasEnLaAppTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final String ORG = "00000000-0000-4000-8000-0000000000b1";

    @TestConfiguration
    static class Config {
        @Bean(name = "httpClient")
        java.net.http.HttpClient httpClient() throws Exception {
            java.net.http.HttpClient c = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
            org.mockito.Mockito.when(c.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new java.io.IOException("sin red en pruebas"));
            org.mockito.Mockito.when(c.sendAsync(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new java.io.IOException("sin red en pruebas")));
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
    @Autowired AutopilotComunicacionService autopilot;
    @MockBean EmailService email;

    @BeforeEach
    void limpiar() throws Exception {
        BD.vaciar();
        BD.esquema = EsquemaSql.delProyecto();
        when(email.sendConRespuesta(anyString(), anyString(), anyString(), any())).thenReturn(new EmailService.SendResult(true, "ok"));
    }

    private static void cuenta(String plan, Object... extra) {
        Map<String, Object> u = new LinkedHashMap<>(Map.of("id", ORG, "email", "gestora@naves.example.com", "name", "Gestora",
                "role", "admin", "active", true, "plan", plan, "plan_status", "active", "stripe_subscription_id", "sub_1"));
        for (int i = 0; i < extra.length; i += 2) u.put((String) extra[i], extra[i + 1]);
        BD.tabla("users").add(u);
    }

    private static MockHttpSession sesion() {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, ORG, ORG, "gestora@naves.example.com", "Gestora", "admin");
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private String html(MvcResult r) throws Exception { return r.getResponse().getContentAsString(StandardCharsets.UTF_8); }

    @Test
    void autopilotDetectaLosAvisosYLosEnviaUnaSolaVez() throws Exception {
        cuenta("pro");
        String inq = id(), req = id();
        BD.tabla("inquilinos").add(new LinkedHashMap<>(Map.of("id", inq, "user_id", ORG, "name", "Marta Ruiz", "email", "marta@correo.es")));
        BD.tabla("contratos").add(new LinkedHashMap<>(Map.of("id", id(), "user_id", ORG, "tenant_name", "Marta Ruiz", "inquilino_id", inq,
                "property_name", "Nave 3 Getafe", "fecha_fin", LocalDate.now().plusDays(30).toString(), "estado", "activo")));
        BD.tabla("receipts").add(new LinkedHashMap<>(Map.of("id", id(), "user_id", ORG, "inquilino_nombre", "Marta Ruiz", "importe", 1850,
                "period_year", 2026, "period_month", 9, "fecha_emision", LocalDate.now().minusDays(12).toString(), "status", "Emitido")));
        BD.tabla("tenant_requests").add(new LinkedHashMap<>(Map.of("id", req, "org_id", ORG, "inquilino_id", inq, "title", "Gotera en el muelle 2",
                "status", "Nueva", "property_name", "Nave 3 Getafe")));

        String pagina = html(mvc.perform(get("/herramientas/autopilot-comunicacion").session(sesion())).andReturn());
        assertTrue(pagina.contains("Renovación") && pagina.contains("Recordatorio de pago") && pagina.contains("Respuesta a incidencia"));
        assertTrue(pagina.contains("septiembre de 2026"), "el recordatorio dice el mes del recibo");

        var aviso = autopilot.avisos(ORG).stream().filter(a -> a.tipo().equals("incidencia")).findFirst().orElseThrow();
        assertEquals("/herramientas/autopilot-comunicacion", mvc.perform(post("/herramientas/autopilot-comunicacion/enviar").session(sesion())
                .param("tipo", "incidencia").param("ref", req).param("asunto", aviso.asunto()).param("cuerpo", "Mañana va el técnico."))
                .andReturn().getResponse().getRedirectedUrl());
        verify(email).sendConRespuesta(eq("marta@correo.es"), eq(aviso.asunto()), contains("Mañana va el técnico."), eq("gestora@naves.example.com"));
        assertEquals("Mañana va el técnico.", BD.tabla("tenant_requests").get(0).get("response"), "la respuesta queda en el portal");

        // Envío automático: renovación y pago (las incidencias nunca), y solo una vez
        autopilot.guardarConfig(ORG, true, false, true);
        autopilot.enviarAutomaticos();
        autopilot.enviarAutomaticos();
        verify(email, times(3)).sendConRespuesta(anyString(), anyString(), anyString(), any());
        assertEquals(3, BD.tabla("comunicaciones_autopilot").size());
    }

    @Test
    void laVozCreaTareasYContestaSinGastarIA() throws Exception {
        cuenta("pro");
        BD.tabla("propiedades").add(new LinkedHashMap<>(Map.of("id", id(), "user_id", ORG, "name", "Nave 1")));
        String r = html(mvc.perform(post("/herramientas/comandos-voz/ejecutar").session(sesion()).param("texto", "Recuérdame llamar al fontanero mañana")).andReturn());
        assertTrue(r.contains("Tarea creada para mañana"));
        Map<String, Object> tarea = BD.tabla("tareas").get(0);
        assertEquals("Llamar al fontanero", tarea.get("titulo"));
        assertEquals(LocalDate.now().plusDays(1).toString(), tarea.get("fecha_limite"));
        String activos = html(mvc.perform(post("/herramientas/comandos-voz/ejecutar").session(sesion()).param("texto", "¿Cuántos activos tengo?")).andReturn());
        assertTrue(activos.contains("Tienes 1 activo y te quedan 14 libres"));
        assertEquals(200, mvc.perform(get("/herramientas/comandos-voz").session(sesion())).andReturn().getResponse().getStatus());
    }

    @Test
    void multiEmpresaConElLimiteDeSociedadesDeBusiness() throws Exception {
        cuenta("business");
        String activo = id();
        BD.tabla("propiedades").add(new LinkedHashMap<>(Map.of("id", activo, "user_id", ORG, "name", "Nave Getafe",
                "current_value", 520000, "rental_income", 3200)));
        for (String n : List.of("Uno S.L.", "Dos S.L.", "Tres S.L.", "Cuatro S.L."))
            mvc.perform(post("/herramientas/multi-empresa/sociedades").session(sesion()).param("nombre", n)).andReturn();
        assertEquals(3, BD.tabla("sociedades").size(), "Business incluye 3");

        String sociedad = String.valueOf(BD.tabla("sociedades").get(0).get("id"));
        mvc.perform(post("/herramientas/multi-empresa/asignar").session(sesion()).param("activo_" + activo, sociedad)).andReturn();
        assertEquals(sociedad, BD.tabla("propiedades").get(0).get("sociedad_id"));
        String pagina = html(mvc.perform(get("/herramientas/multi-empresa").session(sesion())).andReturn());
        assertTrue(pagina.contains("Todo el patrimonio") && pagina.contains("520,000 MXN"));

        mvc.perform(post("/herramientas/multi-empresa/sociedades/" + sociedad + "/borrar").session(sesion())).andReturn();
        assertNull(BD.tabla("propiedades").get(0).get("sociedad_id"), "el activo queda sin sociedad, no se borra");
        assertEquals(1, BD.tabla("propiedades").size());
    }

    @Test
    void multiEmpresaYNegociacionSonDeBusiness() throws Exception {
        cuenta("pro");
        assertEquals("/upgrade?herramienta=Gesti%C3%B3n+multi-empresa",
                mvc.perform(get("/herramientas/multi-empresa").session(sesion())).andReturn().getResponse().getRedirectedUrl());
        assertEquals("/upgrade?herramienta=Negotiation+Co-Pilot",
                mvc.perform(get("/herramientas/negociacion").session(sesion())).andReturn().getResponse().getRedirectedUrl());
    }

    @Test
    void negociacionConLosDatosDelActivo() throws Exception {
        cuenta("business");
        String activo = id();
        BD.tabla("propiedades").add(new LinkedHashMap<>(Map.of("id", activo, "user_id", ORG, "name", "Nave Getafe", "type", "Industrial",
                "area_m2", 400, "city", "Madrid", "current_value", 500000)));
        String r = html(mvc.perform(post("/herramientas/negociacion").session(sesion())
                .param("propiedad_id", activo).param("operacion", "venta").param("precio", "560000")).andReturn());
        assertTrue(r.contains("Vendo · Nave Getafe"));
        assertTrue(r.contains("510,000 MXN"), "objetivo: referencia + 2 %");
        assertTrue(r.contains("el valor actual que tienes apuntado del activo"));
        assertTrue(r.contains("Preparación básica sin IA"), "en las pruebas no hay IA: sale la plantilla");
        assertEquals(1, BD.tabla("negociaciones").size());
        String id = String.valueOf(BD.tabla("negociaciones").get(0).get("id"));
        assertTrue(html(mvc.perform(get("/herramientas/negociacion/" + id).session(sesion())).andReturn()).contains("510,000 MXN"), "se puede volver a abrir");
    }

    /** Dos clientes en la misma app: cada uno solo ve y toca lo suyo, también en los envíos automáticos. */
    @Test
    void cadaCuentaSoloVeYTocaLoSuyo() throws Exception {
        String otra = "00000000-0000-4000-8000-0000000000c2";
        cuenta("business");
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", otra, "email", "otra@locales.example.com", "name", "Otra gestora",
                "role", "admin", "active", true, "plan", "business", "plan_status", "active", "stripe_subscription_id", "sub_2")));
        MockHttpSession deLaOtra = new MockHttpSession();
        SessionUtils.loginFull(deLaOtra, otra, otra, "otra@locales.example.com", "Otra gestora", "admin");
        deLaOtra.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());

        // Datos de la primera cuenta: inquilino, contrato que vence, activo, sociedad y negociación
        String inq = id(), activo = id(), sociedad = id(), negociacion = id();
        BD.tabla("inquilinos").add(new LinkedHashMap<>(Map.of("id", inq, "user_id", ORG, "name", "Marta Ruiz", "email", "marta@correo.es")));
        BD.tabla("contratos").add(new LinkedHashMap<>(Map.of("id", id(), "user_id", ORG, "tenant_name", "Marta Ruiz", "inquilino_id", inq,
                "property_name", "Nave 3 Getafe", "fecha_fin", LocalDate.now().plusDays(30).toString(), "estado", "activo")));
        BD.tabla("propiedades").add(new LinkedHashMap<>(Map.of("id", activo, "user_id", ORG, "name", "Nave 3 Getafe", "current_value", 500000)));
        BD.tabla("sociedades").add(new LinkedHashMap<>(Map.of("id", sociedad, "user_id", ORG, "nombre", "Patrimonial S.L.")));
        BD.tabla("negociaciones").add(new LinkedHashMap<>(Map.of("id", negociacion, "user_id", ORG, "titulo", "Vendo · Nave 3 Getafe",
                "operacion", "venta", "rol", "vendo", "apertura", 560000, "objetivo", 510000, "limite", 475000)));

        // La otra cuenta no ve sus avisos, activos, sociedades ni negociaciones
        assertTrue(autopilot.avisos(otra).isEmpty());
        assertFalse(html(mvc.perform(get("/herramientas/autopilot-comunicacion").session(deLaOtra)).andReturn()).contains("Marta Ruiz"));
        String multi = html(mvc.perform(get("/herramientas/multi-empresa").session(deLaOtra)).andReturn());
        assertFalse(multi.contains("Patrimonial S.L.") || multi.contains("Nave 3 Getafe"));
        assertEquals("/herramientas/negociacion", mvc.perform(get("/herramientas/negociacion/" + negociacion).session(deLaOtra))
                .andReturn().getResponse().getRedirectedUrl(), "no abre la negociación de otra cuenta");
        assertTrue(html(mvc.perform(post("/herramientas/comandos-voz/ejecutar").session(deLaOtra).param("texto", "¿Cuántos activos tengo?"))
                .andReturn()).contains("Tienes 0 activos"));

        // Ni puede tocarlos aunque mande los ids a mano
        mvc.perform(post("/herramientas/multi-empresa/sociedades/" + sociedad + "/borrar").session(deLaOtra)).andReturn();
        assertEquals(1, BD.tabla("sociedades").size(), "no borra la sociedad de otra cuenta");
        mvc.perform(post("/herramientas/multi-empresa/sociedades").session(deLaOtra).param("nombre", "Mía S.L.")).andReturn();
        String mia = BD.tabla("sociedades").stream().filter(x -> otra.equals(x.get("user_id"))).map(x -> String.valueOf(x.get("id"))).findFirst().orElseThrow();
        mvc.perform(post("/herramientas/multi-empresa/asignar").session(deLaOtra).param("activo_" + activo, mia)).andReturn();
        assertNull(BD.tabla("propiedades").get(0).get("sociedad_id"), "no mete un activo ajeno en su sociedad");
        mvc.perform(post("/herramientas/autopilot-comunicacion/enviar").session(deLaOtra).param("tipo", "renovacion")
                .param("ref", String.valueOf(BD.tabla("contratos").get(0).get("id"))).param("asunto", "x").param("cuerpo", "x")).andReturn();
        verify(email, never()).sendConRespuesta(anyString(), anyString(), anyString(), any());

        // Envío automático activado en las dos: a Marta solo le escribe su propia gestora, una vez
        autopilot.guardarConfig(ORG, true, false, false);
        autopilot.guardarConfig(otra, true, false, false);
        autopilot.enviarAutomaticos();
        verify(email, times(1)).sendConRespuesta(eq("marta@correo.es"), anyString(), anyString(), eq("gestora@naves.example.com"));
        assertTrue(BD.tabla("comunicaciones_autopilot").stream().allMatch(c -> ORG.equals(c.get("user_id"))));
    }
}
