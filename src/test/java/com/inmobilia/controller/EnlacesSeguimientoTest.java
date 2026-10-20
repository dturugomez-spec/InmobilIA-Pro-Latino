package com.inmobilia.controller;

import com.inmobilia.service.CampaignService;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.FirmaEnlaces;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.UrlBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Seguimiento de clics de campañas: sin redirección abierta y sin perder parámetros del destino. */
class EnlacesSeguimientoTest {

    private final FirmaEnlaces firma = new FirmaEnlaces("secreto-de-prueba");
    private final SupabaseService supabase = mock(SupabaseService.class);
    private final CampaignController ctrl = new CampaignController(supabase, mock(CampaignService.class), firma);

    private void envioExiste(String token, String contenidoCampana) {
        when(supabase.selectFilter(eq("campana_envios"), startsWith("tracking_token=eq." + token)))
                .thenReturn(List.of(Map.of("id", "e1", "campana_id", "c1", "clicks_count", 0)));
        when(supabase.selectFilter(eq("campanas"), startsWith("id=eq.c1")))
                .thenReturn(List.of(Map.of("id", "c1", "contenido", contenidoCampana, "clicks", 0)));
    }

    @Test
    void enlaceInventado_vaALaPortada() {
        when(supabase.selectFilter(anyString(), anyString())).thenReturn(List.of());
        assertEquals("redirect:/", ctrl.trackClick("inventado", "https://web-maliciosa.com", null, new MockHttpServletRequest()));
        assertEquals("redirect:/", ctrl.trackClick("x", "javascript:alert(1)", null, new MockHttpServletRequest()));
        assertEquals("redirect:/", ctrl.trackClick("x", "https://a.com/\r\nSet-Cookie: x", null, new MockHttpServletRequest()));
    }

    @Test
    void tokenRealPeroDestinoAjeno_vaALaPortada() {
        envioExiste("t1", "<a href=\"https://www.mi-agencia.es/pisos\">Ver</a>");
        assertEquals("redirect:/", ctrl.trackClick("t1", "https://web-maliciosa.com/login", null, new MockHttpServletRequest()));
        // Emails antiguos sin firma: vale si la web sale en la campaña
        assertEquals("redirect:https://www.mi-agencia.es/pisos?id=3",
                ctrl.trackClick("t1", "https://www.mi-agencia.es/pisos?id=3", null, new MockHttpServletRequest()));
    }

    @Test
    void enlaceFirmado_redirigeASuDestino() {
        envioExiste("t2", "");
        String destino = "https://otra-web.com/oferta?a=1&b=2";
        assertEquals("redirect:" + destino,
                ctrl.trackClick("t2", destino, firma.firmar("t2", destino), new MockHttpServletRequest()));
        assertEquals("redirect:/", ctrl.trackClick("t2", destino, "firma-falsa", new MockHttpServletRequest()));
    }

    @Test
    void alEnviar_elDestinoVaCodificadoYFirmado() {
        CampaignService servicio = new CampaignService(mock(SupabaseService.class), mock(EmailService.class), firma);
        String html = "<p><a href=\"https://www.mi-agencia.es/buscar?zona=centro&amp;precio=200000\">Ver pisos</a></p>";
        String salida = servicio.reescribirEnlaces(html, "tok");
        Matcher m = Pattern.compile("href=\"([^\"]+)\"").matcher(salida);
        assertTrue(m.find());
        String enlace = m.group(1).replace("&amp;", "&");
        assertTrue(enlace.startsWith(UrlBase.BASE + "/track/click/tok?url="));
        String url = URLDecoder.decode(enlace.replaceAll(".*[?&]url=([^&]+).*", "$1"), StandardCharsets.UTF_8);
        String s = enlace.replaceAll(".*[?&]s=([^&]+).*", "$1");
        assertEquals("https://www.mi-agencia.es/buscar?zona=centro&precio=200000", url, "el destino conserva todos sus parámetros");
        assertTrue(firma.valida(s, "tok", url));
    }

    @Test
    void urlBase_conWwwPorDefecto() {
        assertEquals("https://www.inmobiliapro.es", com.inmobilia.util.UrlBase.POR_DEFECTO);
    }
}
