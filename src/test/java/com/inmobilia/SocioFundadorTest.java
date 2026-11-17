package com.inmobilia;

import com.inmobilia.demo.EsquemaSql;
import com.inmobilia.demo.FakeSupabase;
import com.inmobilia.service.StripeService;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Socio fundador y oferta de lanzamiento en las pantallas: el dueño de la plataforma marca hasta 10 socios
 * fundadores, nadie más puede, y la web y la página de planes cuentan la oferta y el precio de fundador.
 * (La fecha de la oferta se fija lejos para que la prueba no caduque.)
 */
@SpringBootTest(properties = {
        "supabase.url=https://demo.supabase.co",
        "supabase.key=clave-de-prueba",
        "spring.main.allow-bean-definition-overriding=true",
        "ADMIN_EMAILS=dueno@inmobiliapro.example.com",
        "app.oferta.primer-cobro=2099-02-01",
        "LOG_APP=WARN"
})
@AutoConfigureMockMvc
class SocioFundadorTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final String DUENO = "00000000-0000-4000-8000-0000000000f1";
    static final StripeService STRIPE = mock(StripeService.class);

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
        when(STRIPE.isConfigured()).thenReturn(true);
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", DUENO, "email", "dueno@inmobiliapro.example.com", "name", "Dueño",
                "role", "admin", "active", true, "plan", "free", "plan_status", "active")));
    }

    private static String id(int n) { return String.format("00000000-0000-4000-8000-%012d", n); }

    private void cliente(int n) {
        BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", id(n), "email", "c" + n + "@cliente.example.com", "name", "Cliente " + n,
                "role", "admin", "active", true, "plan", "business", "plan_status", "trialing",
                "trial_ends_at", OffsetDateTime.now().plusDays(20).toString())));
    }

    private Map<String, Object> usuario(int n) {
        return BD.tabla("users").stream().filter(u -> id(n).equals(u.get("id"))).findFirst().orElseThrow();
    }

    private static MockHttpSession sesion(String uid, String email) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, uid, uid, email, "Persona", "admin");
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    private static String html(MvcResult r) throws Exception { return r.getResponse().getContentAsString(StandardCharsets.UTF_8); }

    private static boolean fundador(Map<String, Object> u) { return Boolean.parseBoolean(String.valueOf(u.get("socio_fundador"))); }

    @Test
    void elDuenoMarcaHasta10SociosFundadoresYNiUnoMas() throws Exception {
        MockHttpSession dueno = sesion(DUENO, "dueno@inmobiliapro.example.com");
        for (int n = 1; n <= 11; n++) cliente(n);
        for (int n = 1; n <= 11; n++)
            mvc.perform(post("/admin/clientes/" + id(n) + "/fundador").param("activar", "true").session(dueno));
        for (int n = 1; n <= 10; n++) assertTrue(fundador(usuario(n)), "plaza " + n);
        assertFalse(fundador(usuario(11)), "la undécima ya no entra");
        assertNotNull(usuario(1).get("socio_fundador_at"));

        String ficha = html(mvc.perform(get("/admin/clientes/" + id(11)).session(dueno)).andReturn());
        assertTrue(ficha.contains("plazas ocupadas: 10 de 10"), "la ficha dice cuántas plazas quedan");
        String lista = html(mvc.perform(get("/admin/clientes").session(dueno)).andReturn());
        assertTrue(lista.contains("Socio fundador") && lista.contains("socios fundadores: 10 de 10"));

        // Quitar a uno libera la plaza
        mvc.perform(post("/admin/clientes/" + id(3) + "/fundador").param("activar", "false").session(dueno));
        assertFalse(fundador(usuario(3)));
        mvc.perform(post("/admin/clientes/" + id(11) + "/fundador").param("activar", "true").session(dueno));
        assertTrue(fundador(usuario(11)));
    }

    @Test
    void unClienteNoSePuedeHacerSocioFundadorASiMismo() throws Exception {
        cliente(1);
        mvc.perform(post("/admin/clientes/" + id(1) + "/fundador").param("activar", "true").session(sesion(id(1), "c1@cliente.example.com")));
        assertFalse(fundador(usuario(1)));
    }

    @Test
    void laWebYLaPaginaDePlanesCuentanLaOfertaYElPrecioDeFundador() throws Exception {
        String web = html(mvc.perform(get("/")).andReturn());
        assertTrue(web.contains("Oferta de lanzamiento") && web.contains("1 de febrero de 2099"), "la oferta en la portada");
        assertTrue(web.contains("10 primeras inmobiliarias") && web.contains("20 % de descuento en sus 4 primeros cobros")
                && web.contains("800 MXN") && web.contains("2,440 MXN"), "precio de fundador en la moneda del país");
        assertTrue(html(mvc.perform(get("/").param("pais", "CO")).andReturn()).contains("143.200 COP"));
        assertFalse(web.contains("de por vida"), "ya no se promete un precio de por vida");

        cliente(1);
        cliente(2);
        usuario(1).put("socio_fundador", true);
        String planesFundador = html(mvc.perform(get("/upgrade").session(sesion(id(1), "c1@cliente.example.com"))).andReturn());
        assertTrue(planesFundador.contains("no pagas nada hasta el 1 de febrero de 2099"));
        assertTrue(planesFundador.contains("Eres socio fundador") && planesFundador.contains("800 MXN"));

        String planesNormal = html(mvc.perform(get("/upgrade").session(sesion(id(2), "c2@cliente.example.com"))).andReturn());
        assertTrue(planesNormal.contains("no pagas nada hasta el 1 de febrero de 2099"));
        assertFalse(planesNormal.contains("Eres socio fundador"));

        // Quien ya tuvo una suscripción no ve la oferta (cuenta aparte: la de arriba ya está en la caché de consultas)
        cliente(3);
        usuario(3).put("stripe_subscription_id", "sub_viejo");
        usuario(3).put("plan_status", "canceled");
        String yaPago = html(mvc.perform(get("/upgrade").session(sesion(id(3), "c3@cliente.example.com"))).andReturn());
        assertFalse(yaPago.contains("no pagas nada hasta"), "sin oferta para quien ya pagó");
    }

    @Test
    void losTerminosExplicanLasPromociones() throws Exception {
        String terminos = html(mvc.perform(get("/terms")).andReturn());
        assertTrue(terminos.contains("Promociones") && terminos.contains("se cobra el precio normal del plan"));
    }
}
