package com.inmobilia;

import com.inmobilia.demo.EsquemaSql;
import com.inmobilia.demo.FakeSupabase;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.TrialExpiryService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * El recorrido completo de la venta, como lo vive un cliente nuevo y como lo haces tú:
 * web → solicitud → aprobación (un mes gratis) → correo de bienvenida → crea su contraseña → entra →
 * ve precios sin pagos con tarjeta → pide un plan → tú lo activas, apuntas la transferencia y haces
 * la factura → el cliente la ve → y si no paga, vuelve a Free sin perder datos.
 */
@SpringBootTest(properties = {
        "supabase.url=https://demo.supabase.co",
        "supabase.key=clave-de-prueba",
        "spring.main.allow-bean-definition-overriding=true",
        "ADMIN_EMAILS=dueno@inmobiliapro.example.com",
        "app.cobro-online=false",
        "LOG_APP=WARN"
})
@AutoConfigureMockMvc
class RecorridoVentaTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final String DUENO = "00000000-0000-4000-8000-0000000000d1";
    static final EmailService CORREO = mock(EmailService.class);

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
        EmailService correoDePrueba() { return CORREO; }
    }

    @Autowired MockMvc mvc;
    @Autowired TrialExpiryService pruebas;
    @Autowired PlanService planService;

    @BeforeEach
    void preparar() throws Exception {
        BD.vaciar();
        BD.esquema = EsquemaSql.delProyecto();
        reset(CORREO);
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", DUENO, "email", "dueno@inmobiliapro.example.com", "name", "Dueño",
                "role", "admin", "active", true, "plan", "free", "plan_status", "active")));
        BD.tabla("business_profile").add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "org_id", DUENO,
                "legal_name", "Grupo Kivasa SL", "tax_id", "B00000000", "iban", "CLABE 0021 8070 0000 0000 01", "contact_email", "cobros@kivasa.example.com")));
    }

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, DUENO, DUENO, "dueno@inmobiliapro.example.com", "Dueño", "admin");
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    private static String html(MvcResult r) throws Exception { return r.getResponse().getContentAsString(StandardCharsets.UTF_8); }

    private static Map<String, Object> usuario(String email) {
        return BD.tabla("users").stream().filter(u -> email.equals(u.get("email"))).findFirst().orElse(null);
    }

    @Test
    void deLaWebALaFacturaYAlCobro() throws Exception {
        // ── 1. La web: precios, mes gratis, transferencia y dónde pedir acceso; nada de pagar con tarjeta ──
        String web = html(mvc.perform(get("/")).andReturn());
        assertTrue(web.contains("action=\"/solicitar-cuenta\"") && web.contains("3,050 MXN") && web.contains("1,000 MXN") && web.contains("8,160 MXN"));
        assertTrue(web.contains("mes gratis") && !web.contains("14 días"), "un mes gratis, ya no 14 días");
        assertFalse(web.contains("/billing/checkout"));

        // ── 2. El cliente pide acceso ──
        mvc.perform(post("/solicitar-cuenta").param("nombre", "Marta López").param("empresa", "Inmobiliaria del Sur SA de CV")
                .param("email", "Marta@InmobiliariaSur.example.com").param("telefono", "5511122233").param("plan", "business").param("num_propiedades", "21-50"));
        assertEquals(1, BD.tabla("solicitudes_cuenta").size());
        assertEquals("marta@inmobiliariasur.example.com", BD.tabla("solicitudes_cuenta").get(0).get("email"));
        verify(CORREO, atLeastOnce()).send(isNull(), eq("marta@inmobiliariasur.example.com"), anyString(), contains("un mes gratis"));
        verify(CORREO, atLeastOnce()).send(isNull(), eq("dueno@inmobiliapro.example.com"), contains("Nueva solicitud"), anyString());
        String solicitud = String.valueOf(BD.tabla("solicitudes_cuenta").get(0).get("id"));

        // ── 3. Tú la apruebas: un mes de Business sin tarjeta ──
        reset(CORREO);
        mvc.perform(post("/admin/solicitudes/" + solicitud + "/aprobar").session(admin()));
        Map<String, Object> cli = usuario("marta@inmobiliariasur.example.com");
        assertNotNull(cli, "la cuenta se ha creado");
        assertEquals("business", cli.get("plan"));
        assertEquals("trialing", cli.get("plan_status"));
        long dias = java.time.Duration.between(OffsetDateTime.now(), OffsetDateTime.parse(String.valueOf(cli.get("trial_ends_at")))).toDays();
        assertTrue(dias >= 29 && dias <= 30, "30 días de prueba, salen " + dias);
        String cliId = String.valueOf(cli.get("id"));

        // ── 4. Correo de bienvenida: enlace de un solo uso para crear la contraseña ──
        ArgumentCaptor<String> cuerpo = ArgumentCaptor.forClass(String.class);
        verify(CORREO).send(isNull(), eq("marta@inmobiliariasur.example.com"), contains("tu contraseña"), cuerpo.capture());
        assertTrue(cuerpo.getValue().contains("30 días gratis"), cuerpo.getValue());
        assertFalse(cuerpo.getValue().toLowerCase().contains("tarjeta de crédito"));
        Matcher m = Pattern.compile("/reset\\?token=([A-Za-z0-9_-]+)&bienvenida=1").matcher(cuerpo.getValue());
        assertTrue(m.find(), "el correo lleva el enlace para crear la contraseña");
        String token = m.group(1);
        String clave = "Marta-Clave-2026-xyz";
        assertEquals("/login?reset=ok", mvc.perform(post("/reset").param("token", token).param("password", clave).param("bienvenida", "1")).andReturn().getResponse().getRedirectedUrl());
        assertTrue(html(mvc.perform(post("/reset").param("token", token).param("password", clave)).andReturn()).contains("ya se utilizó"), "el enlace sirve una sola vez");

        // ── 5. Entra con su contraseña y ve sus planes: pedir, no pagar ──
        MvcResult login = mvc.perform(post("/login").param("email", "marta@inmobiliariasur.example.com").param("password", clave)).andReturn();
        assertNotNull(login.getResponse().getRedirectedUrl(), html(login).length() > 300 ? html(login).substring(0, 300) : html(login));
        assertFalse(login.getResponse().getRedirectedUrl().contains("login"), login.getResponse().getRedirectedUrl());
        MockHttpSession cliente = (MockHttpSession) login.getRequest().getSession();
        String planes = html(mvc.perform(get("/upgrade").session(cliente)).andReturn());
        assertTrue(planes.contains("Estás probando el plan Business") && planes.contains("Pedir Pro") && planes.contains("Pedir Business"));
        assertFalse(planes.contains("/billing/checkout/") || planes.contains("Pago seguro con"));
        assertEquals("/upgrade", mvc.perform(post("/billing/checkout/pro").session(cliente)).andReturn().getResponse().getRedirectedUrl());

        // ── 6. Pide Pro anual ──
        mvc.perform(post("/billing/solicitar").session(cliente).param("plan", "pro").param("periodo", "anual").param("volver", "/upgrade"));
        assertEquals(1, BD.tabla("peticiones_plan").size());
        verify(CORREO, atLeastOnce()).send(eq("dueno@inmobiliapro.example.com"), contains("Petición de plan"), contains("Plan Pro"));

        // ── 7. Tú: ves la petición, activas el plan, apuntas la transferencia y haces la factura ──
        String clientes = html(mvc.perform(get("/admin/clientes").session(admin())).andReturn());
        assertTrue(clientes.contains("Inmobiliaria del Sur SA de CV") && clientes.contains("Plan Pro anual"));
        mvc.perform(post("/admin/clientes/" + cliId + "/plan").session(admin()).param("plan", "pro").param("periodo", "anual"));
        assertEquals("pro", usuario("marta@inmobiliariasur.example.com").get("plan"));
        assertEquals("active", usuario("marta@inmobiliariasur.example.com").get("plan_status"), "la prueba termina al activar el plan");
        assertTrue(html(mvc.perform(get("/admin/clientes/" + cliId).session(admin())).andReturn()).contains("base: 468.0"), "US$39 × 12 = US$468 sin impuestos");

        MvcResult cobro = mvc.perform(post("/admin/clientes/" + cliId + "/cobros").session(admin()).param("base", "468").param("ivaPct", "16")
                .param("metodo", "transferencia").param("meses", "12").param("referencia", "TRF-2026-001").param("factura", "true")).andReturn();
        assertEquals("/invoices/new", cobro.getResponse().getRedirectedUrl());
        MockHttpSession deTi = (MockHttpSession) cobro.getRequest().getSession();
        assertEquals(542.88, ((Number) BD.tabla("cobros_plataforma").get(0).get("total")).doubleValue(), 0.001);
        assertEquals(LocalDate.now().plusYears(1).minusDays(1).toString(), String.valueOf(usuario("marta@inmobiliariasur.example.com").get("pagado_hasta")).substring(0, 10));
        String formulario = html(mvc.perform(get("/invoices/new").session(deTi)).andReturn());
        assertTrue(formulario.contains("Inmobiliaria del Sur SA de CV"), "cliente en la factura");
        assertTrue(formulario.contains("TRF-2026-001"), "referencia del cobro en la factura");
        assertTrue(formulario.contains("Grupo Kivasa SL"), "emisor en la factura");
        mvc.perform(post("/invoices").session(deTi).param("serie", "A").param("fecha", LocalDate.now().toString()).param("status", "Pagada")
                .param("emisor_nombre", "Grupo Kivasa SL").param("emisor_nif", "B00000000").param("cliente_nombre", "Inmobiliaria del Sur SA de CV")
                .param("concepto", "InmobilIA Pro · plan Pro (anual)").param("base_imponible", "468").param("iva_pct", "16").param("irpf_pct", "0"));
        Map<String, Object> factura = BD.tabla("invoices").stream().filter(f -> DUENO.equals(f.get("user_id"))).findFirst().orElseThrow();
        assertEquals(factura.get("id"), BD.tabla("cobros_plataforma").get(0).get("factura_id"));

        // ── 8. El cliente ve su plan pagado, su pago y su factura ──
        String facturacion = html(mvc.perform(get("/billing").session(cliente)).andReturn());
        assertTrue(facturacion.contains("Pagado hasta el") && facturacion.contains("542,88") && facturacion.contains("CLABE"));
        String idCobro = String.valueOf(BD.tabla("cobros_plataforma").get(0).get("id"));
        assertEquals(200, mvc.perform(get("/billing/facturas/" + idCobro).session(cliente)).andReturn().getResponse().getStatus());

        // ── 9. Si un año después no paga: 10 días de margen y vuelve a Free, con sus datos ──
        BD.tabla("propiedades").add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "user_id", cliId, "name", "Nave 1")));
        Map<String, Object> u = usuario("marta@inmobiliariasur.example.com");
        u.put("pagado_hasta", LocalDate.now().minusDays(PlanService.DIAS_GRACIA + 1).toString());
        assertEquals("free", planService.planEfectivo(u));
        assertEquals(1, BD.tabla("propiedades").size(), "no se borra nada");
        assertTrue(html(mvc.perform(get("/dashboard").session(cliente)).andReturn()).contains("funciona como Free"));
    }

    @Test
    void siNoEligePlanAlAcabarElMesPasaAFreeConSusDatos() throws Exception {
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", "00000000-0000-4000-8000-0000000000e5", "email", "prueba@acabada.example.com", "name", "Cliente",
                "role", "admin", "active", true, "plan", "business", "plan_status", "trialing", "trial_ends_at", OffsetDateTime.now().minusDays(1).toString())));
        BD.tabla("propiedades").add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "user_id", "00000000-0000-4000-8000-0000000000e5", "name", "Local 1")));
        pruebas.pasarAFreeLasPruebasAcabadas();
        Map<String, Object> u = usuario("prueba@acabada.example.com");
        assertEquals("free", u.get("plan"));
        assertEquals("active", u.get("plan_status"));
        assertEquals(true, u.get("active"));
        assertEquals(1, BD.tabla("propiedades").size());
        verify(CORREO).send(isNull(), eq("prueba@acabada.example.com"), contains("sigues en el plan Free"), contains("sin permanencia"));
    }
}
