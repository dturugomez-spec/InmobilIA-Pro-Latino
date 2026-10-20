package com.inmobilia.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Catálogo curado de enlaces a organismos oficiales de cada país de Latinoamérica
 * (fisco, banco central, estadística, registro de la propiedad, supervisor financiero),
 * más referencias internacionales. Cada herramienta renderiza un panel "Enlaces oficiales"
 * filtrando por tags; se muestran los del país de la inmobiliaria.
 */
@Service
public class OfficialLinksService {

    public record Link(String title, String url, String desc, String icon, List<String> tags) {
        public boolean hasTag(String tag) { return tags.contains(tag); }
    }

    private static Link l(String title, String url, String desc, String icon, String... tags) {
        return new Link(title, url, desc, icon, List.of(tags));
    }

    private static final Map<String, List<Link>> POR_PAIS = Map.of(
        "MX", List.of(
            l("SAT", "https://www.sat.gob.mx/", "Servicio de Administración Tributaria: RFC, CFDI, ISR e IVA.", "🧾",
                    "fiscal", "impuestos", "validador", "empresa", "cif", "holding"),
            l("Banxico", "https://www.banxico.org.mx/", "Banco de México: tasa objetivo, TIIE y tipo de cambio.", "🏦",
                    "tipos", "hipoteca", "bce", "euribor", "mercado"),
            l("INEGI", "https://www.inegi.org.mx/", "Estadísticas de vivienda, población y precios.", "📊",
                    "mercado", "zona", "ipv", "vivienda"),
            l("SHF", "https://www.gob.mx/shf", "Sociedad Hipotecaria Federal: índice de precios de la vivienda.", "🏠",
                    "ipv", "mercado", "vivienda", "hipoteca"),
            l("Infonavit", "https://portalmx.infonavit.org.mx/", "Créditos de vivienda para trabajadores.", "🏗️",
                    "hipoteca", "vivienda"),
            l("Condusef", "https://www.condusef.gob.mx/", "Comparación de créditos hipotecarios y protección al usuario.", "⚖️",
                    "hipoteca", "tipos", "legal")),
        "CO", List.of(
            l("DIAN", "https://www.dian.gov.co/", "Impuestos, NIT, RUT y facturación electrónica.", "🧾",
                    "fiscal", "impuestos", "validador", "empresa", "cif", "holding"),
            l("Banco de la República", "https://www.banrep.gov.co/", "Tasa de intervención, UVR e IPC.", "🏦",
                    "tipos", "hipoteca", "bce", "euribor", "mercado"),
            l("DANE", "https://www.dane.gov.co/", "Estadísticas de vivienda, IPC y construcción.", "📊",
                    "mercado", "zona", "ipv", "vivienda"),
            l("Superintendencia de Notariado y Registro", "https://www.supernotariado.gov.co/",
                    "Certificados de tradición y libertad.", "🏛️", "propiedad", "registro", "due-diligence", "legal"),
            l("IGAC", "https://www.igac.gov.co/", "Instituto Geográfico Agustín Codazzi: catastro.", "🗺️",
                    "catastro", "propiedad"),
            l("Superfinanciera", "https://www.superfinanciera.gov.co/", "Tasas de crédito y entidades vigiladas.", "⚖️",
                    "hipoteca", "tipos", "legal")),
        "AR", List.of(
            l("ARCA (ex AFIP)", "https://www.arca.gob.ar/", "Agencia de Recaudación y Control Aduanero: CUIT, IVA, Ganancias.", "🧾",
                    "fiscal", "impuestos", "validador", "empresa", "cif", "holding"),
            l("BCRA", "https://www.bcra.gob.ar/", "Banco Central: tasas, UVA y tipo de cambio.", "🏦",
                    "tipos", "hipoteca", "bce", "euribor", "mercado"),
            l("INDEC", "https://www.indec.gob.ar/", "Estadísticas de precios, construcción y vivienda.", "📊",
                    "mercado", "zona", "ipv", "vivienda")),
        "CL", List.of(
            l("SII", "https://www.sii.cl/", "Servicio de Impuestos Internos: RUT, IVA y avalúo fiscal.", "🧾",
                    "fiscal", "impuestos", "validador", "empresa", "cif", "holding", "catastro"),
            l("Banco Central de Chile", "https://www.bcentral.cl/", "Tasa de política monetaria, UF e índice de precios de vivienda.", "🏦",
                    "tipos", "hipoteca", "bce", "euribor", "mercado", "ipv"),
            l("INE Chile", "https://www.ine.gob.cl/", "Estadísticas de vivienda y precios.", "📊",
                    "mercado", "zona", "vivienda"),
            l("Conservador de Bienes Raíces de Santiago", "https://www.conservador.cl/", "Certificados de dominio vigente.", "🏛️",
                    "propiedad", "registro", "due-diligence"),
            l("CMF", "https://www.cmfchile.cl/", "Comisión para el Mercado Financiero: créditos hipotecarios.", "⚖️",
                    "hipoteca", "tipos", "legal")),
        "PE", List.of(
            l("SUNAT", "https://www.sunat.gob.pe/", "RUC, IGV, renta y comprobantes electrónicos.", "🧾",
                    "fiscal", "impuestos", "validador", "empresa", "cif", "holding"),
            l("BCRP", "https://www.bcrp.gob.pe/", "Banco Central de Reserva: tasas y precios de inmuebles.", "🏦",
                    "tipos", "hipoteca", "bce", "euribor", "mercado", "ipv"),
            l("INEI", "https://www.inei.gob.pe/", "Estadísticas de vivienda y población.", "📊",
                    "mercado", "zona", "vivienda"),
            l("SUNARP", "https://www.sunarp.gob.pe/", "Registros públicos: partidas y búsqueda de propiedades.", "🏛️",
                    "propiedad", "registro", "due-diligence", "catastro"),
            l("SBS", "https://www.sbs.gob.pe/", "Superintendencia de Banca: tasas de crédito hipotecario.", "⚖️",
                    "hipoteca", "tipos", "legal"))
    );

    /** Referencias internacionales: valen para todos los países. */
    private static final List<Link> GLOBALES = List.of(
        l("CEPAL - Estadísticas", "https://statistics.cepal.org/", "Indicadores económicos de América Latina y el Caribe.", "🌎",
                "mercado", "internacional", "europa", "zona"),
        l("BID", "https://www.iadb.org/es", "Banco Interamericano de Desarrollo: estudios de vivienda en la región.", "🏘️",
                "vivienda", "mercado", "internacional"),
        l("Banco Mundial", "https://datos.bancomundial.org/", "Indicadores macroeconómicos globales.", "🏦",
                "mercado", "internacional"),
        l("OCDE Housing Markets", "https://www.oecd.org/housing/data/", "Indicadores mundiales de vivienda.", "🌐",
                "mercado", "internacional")
    );

    private static List<Link> delPais() {
        List<Link> out = new ArrayList<>(POR_PAIS.getOrDefault(com.inmobilia.util.PaisActual.get().codigo(), List.of()));
        out.addAll(GLOBALES);
        return out;
    }

    public List<Link> all() { return delPais(); }

    public List<Link> byTags(String... tags) {
        List<Link> todos = delPais();
        if (tags == null || tags.length == 0) return todos;
        List<String> set = Arrays.asList(tags);
        List<Link> out = new ArrayList<>();
        for (Link l : todos) {
            for (String t : set) {
                if (l.hasTag(t)) { out.add(l); break; }
            }
        }
        return out;
    }

    /** URL builder: Google Maps StreetView por lat/lon. */
    public String streetView(double lat, double lon) {
        return "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=" + lat + "," + lon;
    }

    /** URL builder: OSM maps. */
    public String osmMap(double lat, double lon, int zoom) {
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=" + zoom + "/" + lat + "/" + lon;
    }

    /** Devuelve los tags mas comunes del catalogo (para sugerencias). */
    public List<String> tagCloud() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Link l : delPais()) for (String t : l.tags()) counts.merge(t, 1, Integer::sum);
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(14).map(Map.Entry::getKey).toList();
    }
}
