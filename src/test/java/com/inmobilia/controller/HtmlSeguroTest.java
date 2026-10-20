package com.inmobilia.controller;

import com.inmobilia.service.DocumentTemplateService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.HtmlSeguro;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** HTML que se muestra tal cual (wiki, emails, documentos, campañas): sin código ejecutable. */
class HtmlSeguroTest {

    @Test
    void wiki_escapaElTextoYSoloEnlazaWebsSeguras() {
        String html = WikiController.markdownToHtml("# Hola\n\n<script>alert(1)</script> **negrita**\n\n"
                + "[web](https://www.mi-agencia.es) [malo](javascript:alert(1)) [app](/leads) <img src=x onerror=alert(1)>");
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("<img"), html);
        assertFalse(html.contains("href=\"javascript"), html);
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("<h1>Hola</h1>"));
        assertTrue(html.contains("<strong>negrita</strong>"));
        assertTrue(html.contains("<a href=\"https://www.mi-agencia.es\""));
        assertTrue(html.contains("<a href=\"/leads\""));
        assertTrue(html.contains("malo"), "el texto del enlace peligroso se queda, sin enlace");
    }

    @Test
    void wiki_noSePuedeSalirDelEnlaceConComillas() {
        String html = WikiController.markdownToHtml("[x](https://a.com\"onmouseover=\"alert(1))");
        assertFalse(html.contains("\"onmouseover"), html);
    }

    @Test
    void limpiar_quitaCodigoYDejaElFormato() {
        String sucio = "<p style=\"color:red\">Hola <strong>Ana</strong></p><script>robar()</script>"
                + "<img src=\"https://cdn.x.com/a.jpg\" onerror=\"robar()\"><a href=\"javascript:robar()\">clic</a>"
                + "<table border=\"0\"><tr><td bgcolor=\"#eee\">1</td></tr></table><iframe src=\"https://x\"></iframe>";
        String limpio = HtmlSeguro.limpiar(sucio);
        assertFalse(limpio.contains("script"), limpio);
        assertFalse(limpio.contains("onerror"), limpio);
        assertFalse(limpio.contains("javascript"), limpio);
        assertFalse(limpio.contains("iframe"), limpio);
        assertTrue(limpio.contains("<strong>Ana</strong>"));
        assertTrue(limpio.contains("style=\"color:red\""));
        assertTrue(limpio.contains("src=\"https://cdn.x.com/a.jpg\""));
        assertTrue(limpio.contains("bgcolor=\"#eee\""));
    }

    @Test
    void plantillas_losDatosDelLeadVanComoTexto() {
        DocumentTemplateService s = new DocumentTemplateService(mock(SupabaseService.class));
        String html = s.rellenar("<p>Comprador: <b>{lead.nombre}</b></p>",
                Map.of("lead", Map.of("nombre", "<img src=x onerror=alert(1)> O'Brien")));
        assertEquals("<p>Comprador: <b>&lt;img src=x onerror=alert(1)&gt; O&#39;Brien</b></p>", html);
    }
}
