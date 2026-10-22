package com.inmobilia;

import com.inmobilia.service.EjemplosService;
import com.inmobilia.util.SessionUtils;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * La app entera (controladores, plantillas e interceptores reales) sobre una Supabase en memoria:
 * se abren todas las pantallas del menú con la cuenta vacía y otra vez con los ejemplos cargados.
 *  - Ninguna pantalla puede fallar al pintarse.
 *  - Las herramientas que lista una demo tienen que enseñar los ejemplos (si el texto de la
 *    pantalla es el mismo con y sin ejemplos, los ejemplos no llegan a esa pantalla).
 */
@SpringBootTest(properties = {
        "supabase.url=https://demo.supabase.co",
        "supabase.key=clave-de-prueba",
        "spring.main.allow-bean-definition-overriding=true",
        "ADMIN_EMAILS=ana@inmobiliaria-demo.example.com",
        "LOG_APP=WARN"
})
@AutoConfigureMockMvc
class PantallasConEjemplosTest {

    static final FakeSupabase BD = new FakeSupabase();
    static final String ORG = "00000000-0000-4000-8000-000000000001";

    @TestConfiguration
    static class Config {
        /** Sin red: las consultas a datos oficiales (INE, BOE…) fallan al momento. */
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
    @Autowired EjemplosService ejemplos;

    private static void cuenta() {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", ORG);
        u.put("email", "ana@inmobiliaria-demo.example.com");
        u.put("name", "Ana Demo");
        u.put("role", "admin");
        u.put("active", true);
        u.put("plan", "agency");
        u.put("plan_status", "active");
        u.put("stripe_subscription_id", "sub_prueba");
        u.put("stripe_customer_id", "cus_prueba");
        u.put("ai_credits", 100);
        BD.tabla("users").add(u);
    }

    private static MockHttpSession sesion() {
        MockHttpSession s = new MockHttpSession();
        SessionUtils.loginFull(s, ORG, ORG, "ana@inmobiliaria-demo.example.com", "Ana Demo", "admin");
        s.setAttribute(SessionUtils.KEY_REVISADO, System.currentTimeMillis());
        return s;
    }

    /** Rutas del menú lateral. */
    static List<String> rutasDelMenu() throws Exception {
        String layout = Files.readString(Path.of("src/main/resources/templates/fragments/layout.html"));
        Set<String> rutas = new TreeSet<>();
        Matcher m = Pattern.compile("href=\"(/[a-z0-9][^\"#?{}$]*)\"").matcher(layout);
        while (m.find()) {
            String r = m.group(1);
            if (r.startsWith("/logout") || r.startsWith("/css") || r.startsWith("/js") || r.startsWith("/img")
                    || r.startsWith("/manifest") || r.startsWith("/favicon") || r.endsWith(".png") || r.endsWith(".ico")) continue;
            rutas.add(r);
        }
        return new ArrayList<>(rutas);
    }

    record Visita(int status, String destino, String error, String texto, String html) {}

    private Visita visitar(String ruta) {
        try {
            MvcResult r = mvc.perform(get(ruta).session(sesion())).andReturn();
            String html = r.getResponse().getContentType() != null && !r.getResponse().getContentType().contains("html")
                    ? "" : r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            String error = r.getResolvedException() == null ? null : r.getResolvedException() + donde(r.getResolvedException());
            if (error == null && r.getResponse().getStatus() >= 500) error = "HTTP " + r.getResponse().getStatus();
            return new Visita(r.getResponse().getStatus(), r.getResponse().getRedirectedUrl(), error, contenido(html), html);
        } catch (Exception e) {
            Throwable c = e;
            while (c.getCause() != null) c = c.getCause();
            return new Visita(500, null, c + donde(c), "", "");
        }
    }

    /** Primera línea de código propio en la traza: dónde arreglarlo. */
    private static String donde(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        for (StackTraceElement e : t.getStackTrace())
            if (e.getClassName().startsWith("com.inmobilia")) return " @ " + e.getClassName().replace("com.inmobilia.", "") + ":" + e.getLineNumber();
        return "";
    }

    private static final Pattern ENLACE = Pattern.compile("(?:href|action)=\"(/[a-z][^\"#{}$]*)\"");
    private static final Pattern CON_ID = Pattern.compile(".*/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*|.*/(pdf|imprimir|print|ver|preview)(/.*)?");

    /** Enlaces a fichas, PDFs y vistas de impresión dentro del contenido de una pantalla. */
    static Set<String> enlacesDeDetalle(String html) {
        Set<String> out = new TreeSet<>();
        int i = html.indexOf("<main"), j = html.lastIndexOf("</main>");
        String main = i >= 0 && j > i ? html.substring(i, j) : html;
        // Los formularios POST no se siguen: solo enlaces
        Matcher m = ENLACE.matcher(main.replaceAll("(?is)<form[^>]*method=\"post\"[^>]*>", ""));
        while (m.find()) {
            String r = m.group(1).replace("&amp;", "&");
            if (CON_ID.matcher(r).matches() && !r.contains("/delete") && !r.contains("/borrar") && !r.contains("/eliminar")
                    && !r.contains("/quitar") && !r.contains("/toggle")) out.add(r);
        }
        return out;
    }

    /** Texto visible del contenido principal (sin el menú), sin números ni fechas. */
    static String contenido(String html) {
        int i = html.indexOf("<main");
        int j = html.lastIndexOf("</main>");
        String main = i >= 0 && j > i ? html.substring(i, j) : html;
        return main.replaceAll("(?s)<script.*?</script>", " ")
                .replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("&[a-z#0-9]+;", " ")
                .replaceAll("[0-9]", "")
                .replaceAll("\\s+", " ").trim();
    }

    @Test
    void todasLasPantallasSePintanYEnsenanLosEjemplos() throws Exception {
        BD.vaciar();
        BD.esquema = EsquemaSql.delProyecto();
        cuenta();
        List<String> rutas = rutasDelMenu();
        Map<String, Visita> vacia = new LinkedHashMap<>();
        for (String r : rutas) vacia.put(r, visitar(r));

        Map<String, Object> informe = ejemplos.cargarAhora(ORG);
        Map<String, Visita> llena = new LinkedHashMap<>();
        for (String r : rutas) llena.put(r, visitar(r));

        StringBuilder rep = new StringBuilder();
        rep.append("Carga de ejemplos: ").append(informe.get("mensaje")).append('\n');
        informe.forEach((k, v) -> { if (k.endsWith("_error")) rep.append("  ERROR SIEMBRA ").append(k).append(": ").append(v).append('\n'); });
        new LinkedHashSet<>(BD.rechazos).forEach(x -> rep.append("  RECHAZO ").append(x).append('\n'));
        List<String> fallos = new ArrayList<>(), sinEjemplos = new ArrayList<>();
        for (String r : rutas) {
            Visita a = vacia.get(r), b = llena.get(r);
            String linea = String.format("%-40s %3d %s", r, b.status(), b.destino() != null ? "→ " + b.destino() : "");
            if (b.status() >= 500 || b.error() != null) {
                fallos.add(r + " : " + b.error());
                linea += "  FALLO " + b.error();
            } else if (b.status() == 200 && a.status() == 200 && a.texto().equals(b.texto())) {
                sinEjemplos.add(r);
                linea += "  (igual con y sin ejemplos)";
            }
            rep.append(linea).append('\n');
        }
        // Un nivel más: fichas, PDFs e impresiones enlazadas desde cada pantalla
        Set<String> detalles = new TreeSet<>();
        for (Visita v : llena.values()) detalles.addAll(enlacesDeDetalle(v.html()));
        detalles.removeAll(rutas);
        rep.append("\nFichas y documentos enlazados: ").append(detalles.size()).append('\n');
        for (String d : detalles) {
            Visita v = visitar(d);
            if (v.status() >= 500 || v.error() != null) {
                fallos.add(d + " : " + v.error());
                rep.append(String.format("%-60s %3d FALLO %s%n", d, v.status(), v.error()));
            }
        }
        // Botones que el usuario dijo que "no hacían nada": seguimiento automático
        MockHttpSession s = sesion();
        MvcResult ej = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/cadencias/ejecutar").session(s)).andReturn();
        assertEquals("/cadencias", ej.getResponse().getRedirectedUrl());
        assertNotNull(s.getAttribute(SessionUtils.KEY_FLASH), "tiene que explicar qué ha hecho");
        String lead = BD.tabla("leads").stream()
                .filter(l -> "Contactado".equals(l.get("etapa")) || "Nuevo".equals(l.get("etapa")))
                .filter(l -> BD.tabla("cadencia_inscripciones").stream().noneMatch(i -> String.valueOf(l.get("id")).equals(String.valueOf(i.get("lead_id")))))
                .map(l -> String.valueOf(l.get("id"))).findFirst().orElse(null);
        if (lead != null) {
            int antes = BD.tabla("cadencia_inscripciones").size();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/cadencias/inscribir")
                    .param("leadId", lead).session(sesion())).andReturn();
            assertEquals(antes + 1, BD.tabla("cadencia_inscripciones").size(), "añadir un lead al seguimiento");
        }

        Path informeTxt = Path.of("target", "pantallas-con-ejemplos.txt");
        Files.createDirectories(informeTxt.getParent());
        Files.writeString(informeTxt, rep.toString() + "\nSIN EJEMPLOS: " + sinEjemplos + "\nFALLOS: " + fallos + "\n");
        System.out.println(rep);
        assertTrue(fallos.isEmpty(), "Pantallas que fallan: " + fallos);
    }
}
