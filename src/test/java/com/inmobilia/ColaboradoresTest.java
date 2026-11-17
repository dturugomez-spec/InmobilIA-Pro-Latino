package com.inmobilia;

import com.inmobilia.demo.EsquemaSql;
import com.inmobilia.demo.FakeSupabase;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.StorageService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Colaboradores: la agencia pide un trabajo desde la ficha del inmueble, el colaborador lo acepta
 * y entrega con su enlace (sin cuenta), la agencia lo ve y lo valora; el enlace deja de valer al
 * terminar o cancelar, y otra inmobiliaria no ve nada.
 */
@SpringBootTest(properties = {
        "supabase.url=https://demo.supabase.co",
        "supabase.key=clave-de-prueba",
        "spring.main.allow-bean-definition-overriding=true",
        "LOG_APP=WARN"
})
@AutoConfigureMockMvc
class ColaboradoresTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final String ORG = "00000000-0000-4000-8000-0000000000d7";
    static final String OTRA = "00000000-0000-4000-8000-0000000000d8";
    static final String PROP = "00000000-0000-4000-8000-0000000000d9";
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
        StorageService storageDePrueba() {
            StorageService s = mock(StorageService.class);
            when(s.subirPrivado(any(), anyString(), anyString(), anyString())).thenAnswer(i -> i.getArgument(3) + "/" + UUID.randomUUID() + ".pdf");
            when(s.descargarPrivado(anyString())).thenReturn("%PDF-prueba".getBytes(StandardCharsets.UTF_8));
            return s;
        }

        @Bean
        @Primary
        EmailService correoDePrueba() { return CORREO; }
    }

    @Autowired MockMvc mvc;

    @BeforeEach
    void preparar() throws Exception {
        BD.vaciar();
        BD.esquema = EsquemaSql.delProyecto();
        reset(CORREO);
        when(CORREO.sendConRespuesta(anyString(), anyString(), anyString(), any())).thenReturn(new EmailService.SendResult(true, "ok"));
        for (String id : List.of(ORG, OTRA))
            BD.tabla("users").add(new LinkedHashMap<>(Map.of("id", id, "email", id + "@example.com", "name", "Agencia", "role", "admin",
                    "active", true, "plan", "free", "plan_status", "active")));
        BD.tabla("business_profile").add(new LinkedHashMap<>(Map.of("id", UUID.randomUUID().toString(), "org_id", ORG,
                "legal_name", "Casas del Valle SA de CV", "contact_email", "hola@casasdelvalle.mx")));
        BD.tabla("propiedades").add(new LinkedHashMap<>(Map.of("id", PROP, "user_id", ORG, "name", "Departamento en Roma Norte",
                "address", "Calle Orizaba 101", "city", "Ciudad de México", "postal_code", "06700")));
    }

    private static MockHttpSession sesion(String org) {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, org, org, org + "@example.com", "Persona", "admin");
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    private String html(org.springframework.test.web.servlet.MvcResult r) throws Exception { return r.getResponse().getContentAsString(StandardCharsets.UTF_8); }

    @Test
    void pedirAceptarEntregarYValorar() throws Exception {
        mvc.perform(post("/colaboradores").session(sesion(ORG)).param("nombre", "Ana Fotógrafa").param("servicio", "fotos")
                .param("email", "Ana@Fotos.mx").param("telefono", "55 1234 5678"));
        mvc.perform(post("/colaboradores").session(sesion(ORG)).param("nombre", "Sin servicio válido").param("servicio", "magia"));
        assertEquals(1, BD.tabla("colaboradores").size());
        String col = String.valueOf(BD.tabla("colaboradores").get(0).get("id"));
        assertEquals("ana@fotos.mx", BD.tabla("colaboradores").get(0).get("email"));

        // Desde la ficha del inmueble: el inmueble llega elegido
        assertTrue(html(mvc.perform(get("/entity/propiedad/" + PROP).session(sesion(ORG))).andReturn()).contains("/colaboradores?propiedad=" + PROP));
        assertTrue(html(mvc.perform(get("/colaboradores").param("propiedad", PROP).session(sesion(ORG))).andReturn())
                .matches("(?s).*<option value=\"" + PROP + "\"[^>]*selected.*"));

        String destino = mvc.perform(post("/colaboradores/encargos").session(sesion(ORG)).param("colaborador", col).param("propiedad", PROP)
                .param("descripcion", "Fotos para publicar").param("contacto", "Llaves en la agencia")).andReturn().getResponse().getRedirectedUrl();
        String id = destino.substring("/colaboradores/encargos/".length());
        Map<String, Object> e = BD.tabla("encargos_colaborador").get(0);
        String token = String.valueOf(e.get("token"));
        assertEquals("Calle Orizaba 101, 06700, Ciudad de México", e.get("direccion"));
        verify(CORREO).sendConRespuesta(eq("ana@fotos.mx"), contains("Casas del Valle SA de CV"), contains("/encargo/" + token), eq("hola@casasdelvalle.mx"));
        String ficha = html(mvc.perform(get(destino).session(sesion(ORG))).andReturn());
        assertTrue(ficha.contains("https://wa.me/525512345678"), "enlace para enviarlo por WhatsApp");

        // El colaborador, sin cuenta
        String pub = html(mvc.perform(get("/encargo/" + token)).andReturn());
        assertTrue(pub.contains("Casas del Valle SA de CV") && pub.contains("Calle Orizaba 101") && pub.contains("Fotos para publicar") && pub.contains("Aceptar el encargo"));
        assertTrue(html(mvc.perform(get("/encargo/" + "x".repeat(32))).andReturn()).contains("ya no está activo"));
        mvc.perform(post("/encargo/" + token + "/responder").param("decision", "aceptar").param("precio", "1,200.50").param("fecha_prevista", "2026-12-01"));
        e = BD.tabla("encargos_colaborador").get(0);
        assertEquals("aceptado", e.get("estado"));
        assertEquals(1200.5, ((Number) e.get("precio")).doubleValue(), 0.001);
        assertTrue(BD.tabla("notifications").stream().anyMatch(n -> "Encargo aceptado".equals(n.get("title"))));
        assertTrue(html(mvc.perform(get("/encargo/" + token)).andReturn()).contains("Trabajo terminado"));

        String fin = html(mvc.perform(multipart("/encargo/" + token + "/entregar").file(new MockMultipartFile("archivo", "fotos.pdf", "application/pdf", "%PDF".getBytes()))).andReturn());
        assertTrue(fin.contains("Recibido, gracias"));
        e = BD.tabla("encargos_colaborador").get(0);
        assertEquals("hecho", e.get("estado"));
        assertTrue(String.valueOf(e.get("entrega_ruta")).startsWith(ORG + "/"));
        assertTrue(html(mvc.perform(get("/encargo/" + token)).andReturn()).contains("ya no está activo"), "terminado: el enlace ya no vale");

        // La agencia lo descarga y lo valora
        assertEquals(200, mvc.perform(get("/colaboradores/encargos/" + id + "/entrega").session(sesion(ORG))).andReturn().getResponse().getStatus());
        mvc.perform(post("/colaboradores/encargos/" + id + "/valorar").session(sesion(ORG)).param("valoracion", "9").param("nota", "Rápida"));
        assertEquals(5, ((Number) BD.tabla("encargos_colaborador").get(0).get("valoracion")).intValue(), "de 1 a 5");
        assertTrue(html(mvc.perform(get("/colaboradores").session(sesion(ORG))).andReturn()).contains("1 hechos · 5,0 de 5"));

        // Otra inmobiliaria: ni lo ve, ni lo descarga, ni puede pedir trabajos a ese colaborador
        MockHttpSession otra = sesion(OTRA);
        assertEquals("/colaboradores", mvc.perform(get("/colaboradores/encargos/" + id).session(otra)).andReturn().getResponse().getRedirectedUrl());
        assertEquals(404, mvc.perform(get("/colaboradores/encargos/" + id + "/entrega").session(otra)).andReturn().getResponse().getStatus());
        mvc.perform(post("/colaboradores/encargos").session(otra).param("colaborador", col));
        mvc.perform(post("/colaboradores").session(otra).param("id", col).param("nombre", "Robado").param("servicio", "fotos"));
        mvc.perform(post("/colaboradores/" + col + "/activo").session(otra).param("activo", "false"));
        assertEquals(1, BD.tabla("encargos_colaborador").size());
        assertEquals("Ana Fotógrafa", BD.tabla("colaboradores").stream().filter(c -> col.equals(String.valueOf(c.get("id")))).findFirst().orElseThrow().get("nombre"));
        assertNotEquals(false, BD.tabla("colaboradores").get(0).get("activo"));
        assertFalse(html(mvc.perform(get("/colaboradores").session(otra)).andReturn()).contains("Ana Fotógrafa"));
    }

    @Test
    void rechazarYCancelarCierranElEnlace() throws Exception {
        BD.tabla("colaboradores").add(new LinkedHashMap<>(Map.of("id", "00000000-0000-4000-8000-00000000c0a1", "user_id", ORG, "nombre", "Fotos Luz",
                "servicio", "fotos", "activo", true)));
        when(CORREO.sendConRespuesta(anyString(), anyString(), anyString(), any())).thenReturn(new EmailService.SendResult(false, "sin red"));
        MockHttpSession s = sesion(ORG);
        mvc.perform(post("/colaboradores/encargos").session(s).param("colaborador", "00000000-0000-4000-8000-00000000c0a1"));
        assertTrue(String.valueOf(s.getAttribute(SessionUtils.KEY_FLASH)).contains("comparte tú el enlace"), "sin correo se avisa de que hay que compartir el enlace");
        verify(CORREO, never()).sendConRespuesta(anyString(), anyString(), anyString(), any());
        String token = String.valueOf(BD.tabla("encargos_colaborador").get(0).get("token"));
        assertTrue(html(mvc.perform(post("/encargo/" + token + "/responder").param("decision", "rechazar").param("respuesta", "Estoy de vacaciones")).andReturn())
                .contains("Gracias por avisar"));
        assertEquals("rechazado", BD.tabla("encargos_colaborador").get(0).get("estado"));
        assertTrue(html(mvc.perform(get("/encargo/" + token)).andReturn()).contains("ya no está activo"));

        mvc.perform(post("/colaboradores/encargos").session(sesion(ORG)).param("colaborador", "00000000-0000-4000-8000-00000000c0a1"));
        Map<String, Object> otro = BD.tabla("encargos_colaborador").get(1);
        mvc.perform(post("/colaboradores/encargos/" + otro.get("id") + "/cancelar").session(sesion(ORG)));
        mvc.perform(post("/encargo/" + otro.get("token") + "/responder").param("decision", "aceptar"));
        assertEquals("cancelado", BD.tabla("encargos_colaborador").get(1).get("estado"), "un encargo cancelado no se puede aceptar");
    }
}
