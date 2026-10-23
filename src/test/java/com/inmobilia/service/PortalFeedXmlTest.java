package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exportación de la cartera a portales: datos reales del inmueble y XML válido. */
class PortalFeedXmlTest {

    private static Map<String, Object> inmueble() {
        Map<String, Object> p = new HashMap<>();
        p.put("id", "11111111-2222-3333-4444-555555555555");
        p.put("name", "Piso luminoso en Ruzafa");
        p.put("address", "C/ Sueca 12");
        p.put("city", "Zapopan");
        p.put("provincia", "Jalisco");
        p.put("postal_code", "45116");
        p.put("type", "Departamento");
        p.put("current_value", 249900.0);
        p.put("area_m2", 85.125);
        p.put("bedrooms", 3);
        p.put("bathrooms", 2);
        p.put("notes", "Reformado, con ascensor y terraza. <Ideal> familias & parejas \u0001");
        p.put("featured_photo", "https://cdn.x.com/1.jpg");
        p.put("photos", List.of("https://cdn.x.com/2.jpg", Map.of("url", "https://cdn.x.com/3.jpg"), "no-es-url"));
        p.put("created_at", "2026-10-01T09:30:00+00:00");
        p.put("status", "Libre");
        return p;
    }

    @Test
    void normaliza_conColumnasRealesYRespaldos() {
        Map<String, Object> n = PortalFeedXml.normalizar(inmueble(), List.of("https://cdn.x.com/1.jpg", "https://cdn.x.com/4.jpg"));
        assertEquals("venta", n.get("operacion"));
        assertEquals(249900.0, n.get("precio"));
        assertEquals("Jalisco", n.get("provincia"), "estado/provincia del inmueble; si falta, la ciudad");
        assertEquals(List.of("https://cdn.x.com/1.jpg", "https://cdn.x.com/2.jpg", "https://cdn.x.com/3.jpg", "https://cdn.x.com/4.jpg"),
                n.get("fotos"), "foto principal + fotos guardadas + galería, sin repetir ni basura");
        assertEquals(true, n.get("ascensor"));
        assertEquals(true, n.get("terraza"));
        assertEquals(false, n.get("piscina"));
        assertTrue(PortalFeedXml.faltan(n).isEmpty());
    }

    @Test
    void alquiler_usaLaRenta_ySinProvinciaUsaLaCiudad() {
        Map<String, Object> p = inmueble();
        p.put("operacion", "Alquiler");
        p.put("rental_income", 1100);
        p.remove("provincia");
        Map<String, Object> n = PortalFeedXml.normalizar(p, List.of());
        assertEquals("alquiler", n.get("operacion"));
        assertEquals(1100.0, n.get("precio"));
        assertEquals("Zapopan", n.get("provincia"));
    }

    @Test
    void noSePublicaSinPrecioNiReservadoOVendido() {
        Map<String, Object> p = inmueble();
        assertTrue(PortalFeedXml.publicable(p, PortalFeedXml.normalizar(p, List.of())));
        p.put("status", "Reservado");
        assertFalse(PortalFeedXml.publicable(p, PortalFeedXml.normalizar(p, List.of())));
        Map<String, Object> sinPrecio = inmueble();
        sinPrecio.remove("current_value");
        Map<String, Object> n = PortalFeedXml.normalizar(sinPrecio, List.of());
        assertFalse(PortalFeedXml.publicable(sinPrecio, n));
        assertTrue(PortalFeedXml.faltan(n).contains("precio"));
    }

    @Test
    void feed_xmlValidoConMonedaYPaisDeLaInmobiliaria() throws Exception {
        Map<String, Object> n = PortalFeedXml.normalizar(inmueble(), List.of());
        String xml = PortalFeedXml.generico(Map.of("company_name", "Inmo & Co", "company_email", "a@b.mx"),
                List.of(n), "https://inmo.example", com.inmobilia.util.Pais.de("MX"));
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))); // XML bien formado
        assertEquals("249900", doc.getElementsByTagName("price").item(0).getTextContent(), "sin decimales");
        assertEquals("MXN", doc.getElementsByTagName("currency").item(0).getTextContent());
        assertEquals("MX", doc.getElementsByTagName("country").item(0).getTextContent());
        assertEquals("sale", doc.getElementsByTagName("price_freq").item(0).getTextContent());
        assertEquals("apartment", doc.getElementsByTagName("property_type").item(0).getTextContent());
        assertEquals("Jalisco", doc.getElementsByTagName("region").item(0).getTextContent());
        assertEquals("85", doc.getElementsByTagName("surface").item(0).getTextContent(), "85,125 m² → 85, no 85125");
        assertEquals("3", doc.getElementsByTagName("rooms").item(0).getTextContent());
        assertEquals(3, doc.getElementsByTagName("image").getLength());
        assertTrue(xml.contains("https://inmo.example/propiedad/11111111-2222-3333-4444-555555555555"));
        assertTrue(doc.getElementsByTagName("description").item(0).getTextContent().contains("<Ideal> familias & parejas"));
        assertFalse(xml.contains(""), "caracteres de control fuera: invalidan el XML");
    }

    @Test
    void alquilerEnOtroPais() throws Exception {
        Map<String, Object> p = inmueble();
        p.put("operacion", "alquiler");
        p.put("rental_income", 950);
        String xml = PortalFeedXml.generico(Map.of("company_name", "Inmo <Sur>", "company_phone", "310 000 0000"),
                List.of(PortalFeedXml.normalizar(p, List.of())), "x", com.inmobilia.util.Pais.de("CO"));
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertFalse(xml.contains("950.0"), "precios sin decimales");
        assertTrue(xml.contains("<operation>rent</operation>") && xml.contains("<price_freq>month</price_freq>"));
        assertTrue(xml.contains("<currency>COP</currency>") && xml.contains("<country>CO</country>"));
    }

    @Test
    void tiposDeInmueble() {
        assertEquals("penthouse", PortalFeedXml.tipo("Penthouse"));
        assertEquals("penthouse", PortalFeedXml.tipo("Ático"));
        assertEquals("house", PortalFeedXml.tipo("Casa en condominio"));
        assertEquals("apartment", PortalFeedXml.tipo("Depto."));
        assertEquals("storage_room", PortalFeedXml.tipo("Bodega"));
        assertEquals("warehouse", PortalFeedXml.tipo("Bodega industrial"));
        assertEquals("land", PortalFeedXml.tipo("Lote"));
        assertEquals("premises", PortalFeedXml.tipo("Local comercial"));
        assertEquals("apartment", PortalFeedXml.tipo(null));
    }
}
