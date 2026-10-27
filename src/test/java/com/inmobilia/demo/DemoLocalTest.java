package com.inmobilia.demo;

import com.inmobilia.service.StorageService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** La demo local: arranca sin Supabase, con los ejemplos, y se entra sin contraseña. */
@SpringBootTest(properties = {"LOG_APP=WARN", "spring.main.allow-bean-definition-overriding=true"})
@ActiveProfiles("demo-local")
@AutoConfigureMockMvc
class DemoLocalTest {

    @TestConfiguration
    static class SinRed {
        @Bean(name = "httpClient")
        java.net.http.HttpClient httpClient() throws Exception {
            java.net.http.HttpClient c = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
            org.mockito.Mockito.when(c.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new java.io.IOException("sin red en pruebas"));
            org.mockito.Mockito.when(c.sendAsync(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new java.io.IOException("sin red en pruebas")));
            return c;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired FakeSupabase bd;
    @Autowired DemoLocalController demo;
    @Autowired StorageService storage;

    @Test
    void entraSinContrasenaEnLaInmobiliariaDeEjemplo() throws Exception {
        assertTrue(demo.listo(), "los ejemplos se cargan al arrancar");
        assertTrue(bd.tabla("leads").size() > 5, "con leads de ejemplo");
        assertTrue(bd.tabla("propiedades").size() > 5, "con inmuebles de ejemplo");

        String login = mvc.perform(get("/login")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(login.contains("Entrar a la demo"), "la pantalla de acceso no enseña la entrada a la demo");
        assertEquals("/entrar", mvc.perform(get("/demo")).andReturn().getResponse().getRedirectedUrl());

        MockHttpSession s = new MockHttpSession();
        MvcResult entrar = mvc.perform(get("/entrar").session(s)).andReturn();
        assertEquals("/dashboard", entrar.getResponse().getRedirectedUrl());
        assertEquals(DemoLocalController.ORG, SessionUtils.uid(s));

        MvcResult panel = mvc.perform(get("/dashboard").session(s)).andReturn();
        assertEquals(200, panel.getResponse().getStatus());
        String lead = String.valueOf(bd.tabla("leads").get(0).get("nombre"));
        String tablero = mvc.perform(get("/kanban/leads").session(s)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(tablero.contains(lead), "el tablero enseña los leads de ejemplo");
        assertTrue(tablero.contains("MXN") || tablero.contains("$"), "importes en pesos mexicanos");
    }

    @Test
    void lasFotosSubidasSeVenSinSalirDelOrdenador() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff};
        String url = storage.upload(png, "image/png", ".png", "fotos");
        assertTrue(url.startsWith("http://localhost:"), url);
        MvcResult r = mvc.perform(get(url.substring(url.indexOf("/storage/")))).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        assertArrayEquals(png, r.getResponse().getContentAsByteArray());
        assertEquals("image/png", r.getResponse().getContentType());
    }
}
