package com.inmobilia;

import com.inmobilia.demo.EsquemaSql;
import com.inmobilia.demo.FakeSupabase;
import com.inmobilia.service.CobroManualService;
import com.inmobilia.service.PlanService;
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
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cobro manual por transferencia o efectivo: la web y la app no cobran con tarjeta, el cliente
 * pide su plan, el propietario de la plataforma lo activa, apunta el cobro y emite la factura, y
 * el cliente la ve. Nadie más entra en Clientes y cobros ni ve facturas de otros.
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
class CobroManualTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final String DUENO = "00000000-0000-4000-8000-0000000000d1";
    static final String CLIENTE = "00000000-0000-4000-8000-0000000000c1";
    static final String OTRO = "00000000-0000-4000-8000-0000000000c2";
    static final String COMERCIAL = "00000000-0000-4000-8000-0000000000c3";

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
    @Autowired CobroManualService cobros;

    @BeforeEach
    void preparar() throws Exception {
        BD.vaciar();
        BD.esquema = EsquemaSql.delProyecto();
        usuario(DUENO, null, "dueno@inmobiliapro.example.com", "admin", "free", "active");
        usuario(CLIENTE, null, "cliente@naves.example.com", "admin", "business", "trialing");
        BD.tabla("users").get(1).put("trial_ends_at", java.time.OffsetDateTime.now().plusDays(5).toString());
        usuario(OTRO, null, "otro@locales.example.com", "admin", "pro", "active");
        usuario(COMERCIAL, CLIENTE, "comercial@naves.example.com", "comercial", null, null);
        BD.tabla("business_profile").add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "org_id", DUENO,
                "legal_name", "Grupo Kivasa SL", "tax_id", "B00000000", "iban", "ES91 2100 0418 4502 0005 1332")));
        BD.tabla("business_profile").add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "org_id", CLIENTE,
                "legal_name", "Naves del Sur SL", "tax_id", "B12345678", "address", "Calle Mayor 1", "city", "Sevilla")));
    }

    private static void usuario(String id, String org, String email, String rol, String plan, String estado) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", id);
        u.put("email", email);
        u.put("name", email);
        u.put("role", rol);
        u.put("active", true);
        if (plan != null) u.put("plan", plan);
        if (estado != null) u.put("plan_status", estado);
        if (org != null) u.put("organization_id", org);
        BD.tabla("users").add(u);
    }

    private static Map<String, Object> fila(String tabla, String id) {
        return BD.tabla(tabla).stream().filter(f -> id.equals(String.valueOf(f.get("id")))).findFirst().orElse(null);
    }

    private static MockHttpSession sesion(String yo, String org, String email, String rol) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, yo, org, email, "Persona", rol);
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    private static MockHttpSession dueno() { return sesion(DUENO, DUENO, "dueno@inmobiliapro.example.com", "admin"); }
    private static MockHttpSession cliente() { return sesion(CLIENTE, CLIENTE, "cliente@naves.example.com", "admin"); }

    private String html(MvcResult r) throws Exception { return r.getResponse().getContentAsString(StandardCharsets.UTF_8); }

    @Test
    void laWebYLaAppNoCobranConTarjeta() throws Exception {
        String web = html(mvc.perform(get("/")).andReturn());
        assertTrue(web.contains("action=\"/solicitar-cuenta\""), "la web dice dónde se pide el acceso");
        assertTrue(web.contains("3,050 MXN") && web.contains("1,000 MXN") && web.contains("8,160 MXN"), "con los precios del país por defecto");
        assertFalse(web.contains("/billing/checkout"));

        String planes = html(mvc.perform(get("/upgrade").session(cliente())).andReturn());
        assertTrue(planes.contains("Pedir Pro") && planes.contains("Pedir Business"));
        assertFalse(planes.contains("/billing/checkout/"), "ningún botón de pago con tarjeta");
        assertFalse(planes.contains("Pago seguro con"));

        assertEquals("/upgrade", mvc.perform(post("/billing/checkout/pro").session(cliente())).andReturn().getResponse().getRedirectedUrl());
        assertEquals("/creditos", mvc.perform(post("/billing/credits/100").session(cliente())).andReturn().getResponse().getRedirectedUrl());
        assertEquals("/billing", mvc.perform(post("/billing/extras/activos").param("cantidad", "3").session(cliente())).andReturn().getResponse().getRedirectedUrl());
        assertEquals("business", fila("users", CLIENTE).get("plan"), "nada ha cambiado");
    }

    @Test
    void elClientePideSuPlanYNosLlegaSinPagarNada() throws Exception {
        mvc.perform(post("/billing/solicitar").session(cliente())
                .param("plan", "pro").param("periodo", "anual").param("activosExtra", "2")
                .param("modulos", "futureproof", "inventado").param("cliente_id", OTRO));
        assertEquals(1, BD.tabla("peticiones_plan").size());
        Map<String, Object> p = BD.tabla("peticiones_plan").get(0);
        assertEquals(CLIENTE, p.get("cliente_id"), "la cuenta sale de la sesión, no del formulario");
        assertEquals("pro", p.get("plan"));
        assertEquals("anual", p.get("periodo"));
        assertEquals(List.of("futureproof"), p.get("modulos"), "solo módulos que existen");
        assertEquals("business", fila("users", CLIENTE).get("plan"), "pedirlo no cambia el plan");

        mvc.perform(post("/billing/solicitar").session(cliente()).param("plan", "platino"));
        assertEquals(1, BD.tabla("peticiones_plan").size(), "un plan que no existe no se guarda");

        // Un comercial no entra en la facturación de la cuenta
        mvc.perform(post("/billing/solicitar").session(sesion(COMERCIAL, CLIENTE, "comercial@naves.example.com", "comercial")).param("plan", "free"));
        assertEquals(1, BD.tabla("peticiones_plan").size());

        String billing = html(mvc.perform(get("/billing").session(cliente())).andReturn());
        assertTrue(billing.contains("petición</span> en curso") || billing.contains("en curso"));
        assertTrue(billing.contains("ES9121000418450200051332"), "ve el IBAN para la transferencia");
    }

    @Test
    void soloElPropietarioDeLaPlataformaEntraEnClientesYCobros() throws Exception {
        assertEquals("/dashboard", mvc.perform(get("/admin/clientes").session(cliente())).andReturn().getResponse().getRedirectedUrl());
        assertEquals("/dashboard", mvc.perform(get("/admin/clientes/" + OTRO).session(cliente())).andReturn().getResponse().getRedirectedUrl());
        mvc.perform(post("/admin/clientes/" + CLIENTE + "/plan").param("plan", "enterprise").session(cliente()));
        mvc.perform(post("/admin/clientes/" + CLIENTE + "/cobros").param("base", "0").param("meses", "12").session(cliente()));
        mvc.perform(post("/admin/clientes/" + CLIENTE + "/creditos").param("cantidad", "100000").session(cliente()));
        assertEquals("business", fila("users", CLIENTE).get("plan"), "un cliente no se cambia el plan");
        assertNull(fila("users", CLIENTE).get("pagado_hasta"));
        assertTrue(BD.tabla("cobros_plataforma").isEmpty());

        String lista = html(mvc.perform(get("/admin/clientes").session(dueno())).andReturn());
        assertTrue(lista.contains("cliente@naves.example.com") && lista.contains("otro@locales.example.com"));
        assertFalse(lista.contains("dueno@inmobiliapro.example.com"), "su propia cuenta no es un cliente");
        assertTrue(html(mvc.perform(get("/dashboard").session(dueno())).andReturn()).contains("href=\"/admin/clientes\""));
        assertFalse(html(mvc.perform(get("/dashboard").session(cliente())).andReturn()).contains("href=\"/admin/clientes\""));
        // Un empleado no es un cliente
        assertEquals("/admin/clientes", mvc.perform(get("/admin/clientes/" + COMERCIAL).session(dueno())).andReturn().getResponse().getRedirectedUrl());
    }

    @Test
    void activarPlanApuntarCobroYHacerLaFactura() throws Exception {
        LocalDate hoy = LocalDate.now();
        mvc.perform(post("/admin/clientes/" + CLIENTE + "/plan").session(dueno())
                .param("plan", "pro").param("periodo", "mensual").param("activosExtra", "2").param("modulos", "futureproof"));
        Map<String, Object> u = fila("users", CLIENTE);
        assertEquals("pro", u.get("plan"));
        assertEquals("active", u.get("plan_status"), "la prueba termina");
        assertEquals(2, ((Number) u.get("activos_extra")).intValue());

        String ficha = html(mvc.perform(get("/admin/clientes/" + CLIENTE).session(dueno())).andReturn());
        assertTrue(ficha.contains("Naves del Sur SL") && ficha.contains("B12345678"));
        assertTrue(ficha.contains("base: 86.0"), "sugiere 49 + 2 × 4 + 29 = 86 USD sin impuestos");

        MvcResult r = mvc.perform(post("/admin/clientes/" + CLIENTE + "/cobros").session(dueno())
                .param("base", "86").param("ivaPct", "21").param("metodo", "transferencia").param("meses", "1")
                .param("referencia", "TRF-001").param("factura", "true")).andReturn();
        assertEquals("/invoices/new", r.getResponse().getRedirectedUrl());
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession();
        assertEquals(1, BD.tabla("cobros_plataforma").size());
        Map<String, Object> cobro = BD.tabla("cobros_plataforma").get(0);
        assertEquals(104.06, ((Number) cobro.get("total")).doubleValue(), 0.001);
        assertEquals(hoy.plusMonths(1).minusDays(1).toString(), String.valueOf(fila("users", CLIENTE).get("pagado_hasta")).substring(0, 10));

        // La factura llega rellena con los datos del cliente
        String form = html(mvc.perform(get("/invoices/new").session(s)).andReturn());
        assertTrue(form.contains("Naves del Sur SL") && form.contains("B12345678") && form.contains("TRF-001"));
        assertTrue(form.contains("value=\"Transferencia\" selected") || form.contains("selected=\"selected\" value=\"Transferencia\"")
                || form.matches("(?s).*<option value=\"Transferencia\"[^>]*selected.*"), "método de pago");

        mvc.perform(post("/invoices").session(s)
                .param("serie", "A").param("fecha", hoy.toString()).param("status", "Pagada")
                .param("emisor_nombre", "Grupo Kivasa SL").param("emisor_nif", "B00000000")
                .param("cliente_nombre", "Naves del Sur SL").param("cliente_nif", "B12345678")
                .param("concepto", "InmobilIA Pro · plan Pro").param("base_imponible", "86").param("iva_pct", "21").param("irpf_pct", "0"));
        Map<String, Object> factura = BD.tabla("invoices").stream().filter(f -> DUENO.equals(f.get("user_id"))).findFirst().orElseThrow();
        assertEquals(factura.get("id"), BD.tabla("cobros_plataforma").get(0).get("factura_id"), "la factura queda unida al cobro");

        // El cliente la ve en su facturación; otra cuenta no
        String billing = html(mvc.perform(get("/billing").session(cliente())).andReturn());
        String enlace = "/billing/facturas/" + cobro.get("id");
        assertTrue(billing.contains(enlace) && billing.contains("104,06"));
        MvcResult suya = mvc.perform(get(enlace).session(cliente())).andReturn();
        assertEquals(200, suya.getResponse().getStatus());
        assertTrue(html(suya).contains("Naves del Sur SL") && html(suya).contains("Grupo Kivasa SL"));
        assertEquals("/billing", mvc.perform(get(enlace).session(sesion(OTRO, OTRO, "otro@locales.example.com", "admin")))
                .andReturn().getResponse().getRedirectedUrl());
        assertEquals("/invoices", mvc.perform(get("/invoices/" + factura.get("id")).session(cliente()))
                .andReturn().getResponse().getRedirectedUrl(), "por la ruta normal sigue sin poder abrirla");

        // Un segundo cobro alarga desde donde acababa el anterior
        mvc.perform(post("/admin/clientes/" + CLIENTE + "/cobros").session(dueno())
                .param("base", "86").param("metodo", "efectivo").param("meses", "1"));
        assertEquals(hoy.plusMonths(1).minusDays(1).plusDays(1).plusMonths(1).minusDays(1).toString(),
                String.valueOf(fila("users", CLIENTE).get("pagado_hasta")).substring(0, 10));
        mvc.perform(post("/admin/clientes/" + CLIENTE + "/cobros").session(dueno()).param("base", "10").param("metodo", "tarjeta"));
        assertEquals(2, BD.tabla("cobros_plataforma").size(), "solo transferencia, efectivo, bizum u otro");
    }

    @Test
    void sinPagarPasadoElMargenFuncionaComoFreeSinPerderDatos() throws Exception {
        Map<String, Object> u = fila("users", CLIENTE);
        u.put("plan_status", "active");
        u.put("pagado_hasta", LocalDate.now().minusDays(5).toString());
        assertNotEquals("/upgrade?herramienta=ESG+Score",
                mvc.perform(get("/herramientas/esg").session(cliente())).andReturn().getResponse().getRedirectedUrl(),
                "dentro del margen de 10 días sigue con Business");
        assertTrue(html(mvc.perform(get("/dashboard").session(cliente())).andReturn()).contains("pendiente de pago"));

        u.put("pagado_hasta", LocalDate.now().minusDays(11).toString());
        assertEquals("/upgrade?herramienta=ESG+Score",
                mvc.perform(get("/herramientas/esg").session(cliente())).andReturn().getResponse().getRedirectedUrl());
        assertEquals("business", u.get("plan"), "el plan no se borra: vuelve en cuanto se apunte el cobro");

        mvc.perform(post("/admin/clientes/" + CLIENTE + "/cobros").session(dueno()).param("base", "149").param("meses", "1"));
        assertNotEquals("/upgrade?herramienta=ESG+Score",
                mvc.perform(get("/herramientas/esg").session(cliente())).andReturn().getResponse().getRedirectedUrl());
    }

    @Test
    void avisosDeVencimiento() {
        LocalDate hoy = LocalDate.of(2026, 11, 6);
        fila("users", CLIENTE).putAll(Map.of("plan_status", "active", "pagado_hasta", hoy.plusDays(7).toString()));
        fila("users", OTRO).put("pagado_hasta", hoy.minusDays(1).toString());
        usuario("00000000-0000-4000-8000-0000000000c4", null, "tranquilo@example.com", "admin", "pro", "active");
        fila("users", "00000000-0000-4000-8000-0000000000c4").put("pagado_hasta", hoy.plusDays(30).toString());
        usuario("00000000-0000-4000-8000-0000000000c5", null, "moroso@example.com", "admin", "business", "active");
        fila("users", "00000000-0000-4000-8000-0000000000c5").put("pagado_hasta", hoy.minusDays(PlanService.DIAS_GRACIA + 1).toString());
        List<String> avisos = cobros.avisar(hoy);
        assertEquals(3, avisos.size(), avisos.toString());
        assertTrue(avisos.stream().anyMatch(a -> a.contains("pasa a Free")));
    }

    @Test
    void importeDeUnPeriodo() {
        assertEquals(49, PlanService.importeBase("pro", false, 0, 0, List.of()));
        assertEquals(468, PlanService.importeBase("pro", true, 0, 0, List.of()), "39 × 12");
        assertEquals(86, PlanService.importeBase("pro", false, 2, 5, List.of("futureproof", "futureproof", "x")), "49 + 8 + 29; sociedades solo en Business");
        assertEquals((119 + 15) * 12, PlanService.importeBase("business", true, 0, 1, List.of("holding")), "módulos incluidos en Business");
        assertEquals(0, PlanService.importeBase("enterprise", false, 3, 0, null), "a medida");
        assertEquals(0, PlanService.importeBase("free", false, 3, 0, null));
    }
}
