package com.inmobilia;

import com.inmobilia.demo.EsquemaSql;
import com.inmobilia.demo.FakeSupabase;
import com.inmobilia.service.StripeService;
import com.inmobilia.util.Pais;
import com.inmobilia.util.PaisActual;
import com.inmobilia.util.PreciosPlataforma;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cada inmobiliaria ve y paga InmobilIA Pro en la moneda de su país (los precios de España pasados
 * al cambio), la web enseña los precios del país del visitante y el cobro con Stripe es mensual.
 */
@SpringBootTest(properties = {
        "supabase.url=https://demo.supabase.co",
        "supabase.key=clave-de-prueba",
        "spring.main.allow-bean-definition-overriding=true",
        "LOG_APP=WARN"
})
@AutoConfigureMockMvc
class PreciosPorPaisTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final StripeService STRIPE = mock(StripeService.class);
    static final String CO = "00000000-0000-4000-8000-0000000000c1";
    static final String AR_USD = "00000000-0000-4000-8000-0000000000c2";

    @TestConfiguration
    static class Config {
        @Bean(name = "httpClient")
        java.net.http.HttpClient httpClient() throws Exception {
            java.net.http.HttpClient c = mock(java.net.http.HttpClient.class);
            when(c.send(any(), any())).thenThrow(new java.io.IOException("sin red en pruebas"));
            return c;
        }

        @Bean
        @Primary
        WebClient webClientDePrueba() {
            return WebClient.builder().exchangeFunction(BD).codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        }

        @Bean
        @Primary
        StripeService stripeDePrueba() { return STRIPE; }
    }

    @Autowired MockMvc mvc;

    @BeforeEach
    void preparar() throws Exception {
        BD.vaciar();
        BD.esquema = EsquemaSql.delProyecto();
        reset(STRIPE);
        when(STRIPE.isConfigured()).thenReturn(true);
        when(STRIPE.createCheckoutSession(any(), anyString(), anyBoolean())).thenReturn("https://checkout.stripe.com/c/pay/prueba");
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", CO, "email", "co@agencia.example.com", "name", "Agencia CO", "role", "admin",
                "active", true, "plan", "business", "plan_status", "trialing", "pais", "CO",
                "trial_ends_at", OffsetDateTime.now().plusDays(20).toString())));
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", AR_USD, "email", "ar@agencia.example.com", "name", "Agencia AR", "role", "admin",
                "active", true, "plan", "free", "plan_status", "active", "pais", "AR", "moneda", "USD")));
    }

    @AfterEach
    void limpiar() { PaisActual.limpiar(); }

    private static MockHttpSession sesion(String org) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, org, org, org + "@example.com", "Persona", "admin");
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    private static String html(MvcResult r) throws Exception { return r.getResponse().getContentAsString(StandardCharsets.UTF_8); }

    @Test
    void cadaMonedaTieneTodosLosPreciosYLasSinDecimalesVanEnUnidades() {
        assertEquals(14, PreciosPlataforma.monedas().size());
        for (String m : PreciosPlataforma.monedas()) {
            for (String c : PreciosPlataforma.CONCEPTOS) assertTrue(PreciosPlataforma.importe(m, c).signum() > 0, m + " " + c);
            // El orden de los planes se mantiene en todas las monedas
            assertTrue(PreciosPlataforma.importe(m, PreciosPlataforma.PLAN_PRO).compareTo(PreciosPlataforma.importe(m, PreciosPlataforma.PLAN_BUSINESS)) < 0, m);
        }
        assertEquals(53900L, PreciosPlataforma.unidadesStripe("CLP", PreciosPlataforma.PLAN_PRO), "CLP sin decimales");
        assertEquals(334000L, PreciosPlataforma.unidadesStripe("PYG", PreciosPlataforma.PLAN_PRO), "PYG sin decimales");
        assertEquals(100000L, PreciosPlataforma.unidadesStripe("MXN", PreciosPlataforma.PLAN_PRO), "MXN en centavos");
        assertEquals(450L, PreciosPlataforma.unidadesStripe("USD", PreciosPlataforma.ACTIVO_EXTRA));
        assertEquals(14, PreciosPlataforma.unidadesStripe(PreciosPlataforma.PLAN_BUSINESS).size(), "un importe por moneda para Stripe");
        assertEquals(new BigDecimal("4.5"), PreciosPlataforma.importe("USD", PreciosPlataforma.ACTIVO_EXTRA));
    }

    @Test
    void cadaPaisPagaEnSuMonedaOEnDolares() {
        assertEquals("MXN", PreciosPlataforma.monedaDe(Pais.de("MX")));
        assertEquals("COP", PreciosPlataforma.monedaDe(Pais.de("CO")));
        assertEquals("USD", PreciosPlataforma.monedaDe(Pais.de("EC")), "Ecuador usa dólares");
        assertEquals("USD", PreciosPlataforma.monedaDe(Pais.de("AR").conMoneda("USD")), "si trabaja en dólares, paga en dólares");
        assertEquals("ARS", PreciosPlataforma.monedaDe(Pais.de("AR")));
        for (Pais p : Pais.todos()) assertTrue(PreciosPlataforma.monedas().contains(PreciosPlataforma.monedaDe(p)), p.codigo());

        PaisActual.set(Pais.de("CO"));
        assertEquals("179.000 COP", PreciosPlataforma.texto(PreciosPlataforma.PLAN_PRO));
        PaisActual.set(Pais.de("MX"));
        assertEquals("1,000 MXN", PreciosPlataforma.texto(PreciosPlataforma.PLAN_PRO));
        assertEquals("246 MXN", PreciosPlataforma.texto(PreciosPlataforma.ACTIVO_EXTRA, 3));
    }

    @Test
    void laWebEnsenaLosPreciosDelPaisDelVisitante() throws Exception {
        String mx = html(mvc.perform(get("/")).andReturn());
        assertTrue(mx.contains("1,000 MXN") && mx.contains("3,050 MXN"), "sin saber el país: el de por defecto");
        assertFalse(mx.contains("US$49") || mx.contains("pagando el año"));

        MvcResult co = mvc.perform(get("/").param("pais", "CO")).andReturn();
        assertTrue(html(co).contains("179.000 COP") && html(co).contains("544.000 COP"));
        Cookie recordado = co.getResponse().getCookie("pais");
        assertNotNull(recordado, "se recuerda el país elegido");
        assertTrue(html(mvc.perform(get("/").cookie(recordado)).andReturn()).contains("179.000 COP"));

        // El idioma del navegador también vale: es-CL → Chile
        assertTrue(html(mvc.perform(get("/").header("Accept-Language", "es-CL,es;q=0.9")).andReturn()).contains("53.900 CLP"));
        // Un país que no existe no cambia nada
        assertTrue(html(mvc.perform(get("/").param("pais", "ZZ")).andReturn()).contains("1,000 MXN"));
    }

    @Test
    void laInmobiliariaVeSuMonedaYElCobroEsMensual() throws Exception {
        String planes = html(mvc.perform(get("/upgrade").session(sesion(CO))).andReturn());
        assertTrue(planes.contains("179.000 COP") && planes.contains("544.000 COP"), "Colombia ve pesos colombianos");
        assertTrue(planes.contains("Elegir Pro") && planes.contains("action=\"/billing/checkout/pro\""));
        assertFalse(planes.contains("Pagando el año") || planes.contains("US$"), "sin selector anual ni dólares fijos");

        assertTrue(html(mvc.perform(get("/upgrade").session(sesion(AR_USD))).andReturn()).contains("54 USD"), "en dólares si trabaja en dólares");

        assertEquals("https://checkout.stripe.com/c/pay/prueba",
                mvc.perform(post("/billing/checkout/pro").param("periodo", "anual").session(sesion(CO))).andReturn().getResponse().getRedirectedUrl());
        verify(STRIPE).createCheckoutSession(any(), eq("pro"), eq(false));
        verify(STRIPE, never()).createCheckoutSession(any(), anyString(), eq(true));
    }
}
