package com.inmobilia;

import com.inmobilia.demo.EsquemaSql;
import com.inmobilia.demo.FakeSupabase;
import com.inmobilia.service.TrialExpiryService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Planes por activos dentro de la app entera (Supabase en memoria): herramientas por plan,
 * límite de activos, empleados que no caben y fin de la prueba → Free con los datos.
 */
@SpringBootTest(properties = {
        "supabase.url=https://demo.supabase.co",
        "supabase.key=clave-de-prueba",
        "spring.main.allow-bean-definition-overriding=true",
        "ADMIN_EMAILS=dueno@inmobiliapro.example.com",
        "LOG_APP=WARN"
})
@AutoConfigureMockMvc
class PlanesEnLaAppTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final String ORG = "00000000-0000-4000-8000-0000000000a1";
    static final String EMPLEADO = "00000000-0000-4000-8000-0000000000a2";

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
    @Autowired TrialExpiryService pruebas;

    @BeforeEach
    void limpiar() throws Exception {
        BD.vaciar();
        BD.esquema = EsquemaSql.delProyecto();
    }

    private static void cuenta(String plan, String estado, Object... extra) {
        Map<String, Object> u = new LinkedHashMap<>(Map.of("id", ORG, "email", "cliente@naves.example.com", "name", "Cliente",
                "role", "admin", "active", true, "plan", plan, "plan_status", estado));
        for (int i = 0; i < extra.length; i += 2) u.put((String) extra[i], extra[i + 1]);
        BD.tabla("users").add(u);
    }

    private static void activos(int n) {
        for (int i = 0; i < n; i++)
            BD.tabla("propiedades").add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "user_id", ORG, "name", "Nave " + i)));
    }

    private static MockHttpSession sesion(String yo, String rol) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, yo, ORG, yo + "@naves.example.com", "Persona", rol);
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    private String destino(MvcResult r) { return r.getResponse().getRedirectedUrl(); }

    @Test
    void freeNoAbreLoDeBusinessYLeDiceQuePlanLoTrae() throws Exception {
        cuenta("free", "active");
        assertEquals("/upgrade?herramienta=ESG+Score", destino(mvc.perform(get("/herramientas/esg").session(sesion(ORG, "admin"))).andReturn()));
        assertNotEquals("/upgrade", String.valueOf(destino(mvc.perform(get("/herramientas/euribor").session(sesion(ORG, "admin"))).andReturn())).split("\\?")[0],
                "el Euríbor está en Free");

        MvcResult planes = mvc.perform(get("/upgrade").param("herramienta", "ESG Score").session(sesion(ORG, "admin"))).andReturn();
        assertEquals(200, planes.getResponse().getStatus());
        String html = planes.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("está en el plan <span>Business</span>"), "dice qué plan la incluye");
        assertTrue(html.contains("US$149") && html.contains("US$119") && html.contains("US$399"));

        MvcResult facturacion = mvc.perform(get("/billing").session(sesion(ORG, "admin"))).andReturn();
        assertEquals(200, facturacion.getResponse().getStatus());
        assertTrue(facturacion.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("0 de 3"));
    }

    @Test
    void conLosActivosDelPlanLlenosNoSeCreanMas() throws Exception {
        cuenta("free", "active");
        activos(3);
        assertEquals("/upgrade", destino(mvc.perform(get("/crud/propiedades/new").session(sesion(ORG, "admin"))).andReturn()));
        assertEquals("/upgrade", destino(mvc.perform(post("/crud/propiedades").param("name", "Nave 4").session(sesion(ORG, "admin"))).andReturn()),
                "enviando el formulario directamente tampoco");
        assertEquals(3, BD.tabla("propiedades").size());
    }

    @Test
    void conActivosAdicionalesSiCaben() throws Exception {
        cuenta("pro", "active", "stripe_subscription_id", "sub_1", "activos_extra", 2);
        activos(16);
        assertNotEquals("/upgrade", destino(mvc.perform(get("/crud/propiedades/new").session(sesion(ORG, "admin"))).andReturn()),
                "Pro (15) + 2 adicionales = 17");
    }

    @Test
    void alAcabarLaPruebaPasaAFreeYConservaLosDatos() throws Exception {
        cuenta("business", "trialing", "trial_ends_at", OffsetDateTime.now().minusDays(1).toString());
        activos(10);
        pruebas.pasarAFreeLasPruebasAcabadas();
        Map<String, Object> u = BD.tabla("users").get(0);
        assertEquals("free", u.get("plan"));
        assertEquals("active", u.get("plan_status"));
        assertEquals(true, u.get("active"), "la cuenta no se desactiva");
        assertEquals(10, BD.tabla("propiedades").size(), "los datos se mantienen");
        MvcResult cartera = mvc.perform(get("/crud/propiedades").session(sesion(ORG, "admin"))).andReturn();
        assertEquals(200, cartera.getResponse().getStatus(), "sigue viendo sus activos");
        assertEquals("/upgrade", destino(mvc.perform(get("/crud/propiedades/new").session(sesion(ORG, "admin"))).andReturn()), "pero no añade más");
    }

    @Test
    void siElEquipoNoCabeEnElPlanSoloEntraElTitular() throws Exception {
        cuenta("free", "active");
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", EMPLEADO, "email", EMPLEADO + "@naves.example.com", "name", "Empleado",
                "role", "comercial", "active", true, "organization_id", ORG)));
        assertEquals("/upgrade?limite=usuarios", destino(mvc.perform(get("/dashboard").session(sesion(EMPLEADO, "comercial"))).andReturn()));
        assertNotEquals("/upgrade?limite=usuarios", destino(mvc.perform(get("/dashboard").session(sesion(ORG, "admin"))).andReturn()));
        String aviso = mvc.perform(get("/upgrade").param("limite", "usuarios").session(sesion(EMPLEADO, "comercial"))).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(aviso.contains("más usuarios de los que incluye su plan"));
    }

    @Test
    void unModuloCompradoEnProAbreSoloSuHerramienta() throws Exception {
        cuenta("pro", "active", "stripe_subscription_id", "sub_1", "modulos", List.of("futureproof"));
        assertNotEquals("/upgrade", String.valueOf(destino(mvc.perform(get("/herramientas/future-proof").session(sesion(ORG, "admin"))).andReturn())).split("\\?")[0]);
        assertEquals("/upgrade?herramienta=ESG+Score",
                destino(mvc.perform(get("/herramientas/esg").session(sesion(ORG, "admin"))).andReturn()));
    }

    @Test
    void enPruebaTieneBusinessCompleto() throws Exception {
        cuenta("business", "trialing", "trial_ends_at", OffsetDateTime.now().plusDays(10).toString());
        assertNotEquals("/upgrade", String.valueOf(destino(mvc.perform(get("/herramientas/esg").session(sesion(ORG, "admin"))).andReturn())).split("\\?")[0]);
        assertEquals("/upgrade?herramienta=Acceso+por+API", destino(mvc.perform(get("/api-keys").session(sesion(ORG, "admin"))).andReturn()),
                "la API es de Enterprise");
    }
}
