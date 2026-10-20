package com.inmobilia.service;

import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Catalogo curado de enlaces a organismos oficiales espanoles y europeos
 * relevantes para el sector inmobiliario. Cada tool puede renderizar un
 * panel "Enlaces oficiales" filtrando por tags.
 */
@Service
public class OfficialLinksService {

    public record Link(String title, String url, String desc, String icon, List<String> tags) {
        public boolean hasTag(String tag) { return tags.contains(tag); }
    }

    private static final List<Link> ALL = List.of(
        // ═══ Catastro / Registro / Notarias ═══
        new Link("Sede Catastro", "https://www1.sedecatastro.gob.es/",
                "Consulta, certificados y cartografia oficial.", "🗺️",
                List.of("catastro", "propiedad", "due-diligence", "valoracion")),
        new Link("Catastro Cartografia", "https://www.catastro.hacienda.gob.es/esp/cartografia_inicial.asp",
                "Cartografia digital y visor catastral.", "🛰️",
                List.of("catastro", "zona")),
        new Link("Registro Propiedad", "https://www.registradores.org/",
                "Certificaciones registrales, nota simple, indice.", "📜",
                List.of("propiedad", "due-diligence", "contrato")),
        new Link("Consejo Notarial", "https://www.notariado.org/",
                "Notarios, escrituras publicas, indice unico.", "🖋️",
                List.of("contrato", "due-diligence")),

        // ═══ Fiscal / Tributario ═══
        new Link("Agencia Tributaria", "https://sede.agenciatributaria.gob.es/",
                "IRPF, IVA, Modelo 210 no residentes, ITP.", "🏛️",
                List.of("fiscal", "impuestos", "residente")),
        new Link("Simulador ITP (AEAT)", "https://sede.agenciatributaria.gob.es/Sede/impuestos-tasas/transmisiones-patrimoniales-actos-juridicos-documentados.html",
                "Informacion oficial ITP y AJD por operacion.", "📑",
                List.of("fiscal", "itp", "ajd", "compra")),
        new Link("Plusvalia Municipal (MH)", "https://www.hacienda.gob.es/es-ES/Areas%20Tematicas/Impuestos/Paginas/IIVTNU.aspx",
                "Guia oficial Impuesto Incremento Valor Terrenos.", "🌇",
                List.of("fiscal", "plusvalia", "venta")),

        // ═══ Mercado / Precios / Indices ═══
        new Link("INE - IPV", "https://www.ine.es/dynt3/inebase/es/index.htm?padre=4213",
                "Indice Precio de Vivienda oficial, trimestral.", "📈",
                List.of("mercado", "ipv", "valoracion", "zona")),
        new Link("INE - Alquiler", "https://www.ine.es/experimental/ivalquiler/experimental_ind_alquiler.htm",
                "Indice experimental de precios de alquiler.", "🏘️",
                List.of("mercado", "alquiler", "valoracion")),
        new Link("Ministerio Vivienda", "https://www.mivau.gob.es/vivienda",
                "Estadisticas oficiales, boletin vivienda.", "🏠",
                List.of("mercado", "zona", "ipv")),
        new Link("Indice Precio Referencia", "https://www.mivau.gob.es/vivienda/alquila-bien-es-tu-derecho/serviciosdeinformacion",
                "Indice de referencia de alquiler (LAU).", "📊",
                List.of("alquiler", "lau", "zona")),
        new Link("Observatorio Vivienda", "https://apps.fomento.gob.es/BoletinOnline2/",
                "Boletin estadistico del Ministerio.", "🔭",
                List.of("mercado", "zona")),

        // ═══ Financiero / BCE / Banco de Espana ═══
        new Link("Euribor BCE", "https://www.ecb.europa.eu/stats/financial_markets_and_interest_rates/euro_short-term_rate/html/index.en.html",
                "Tipos de referencia oficiales del BCE.", "💶",
                List.of("euribor", "bce", "hipoteca", "tipos")),
        new Link("Banco de Espana TEDR", "https://www.bde.es/webbe/es/estadisticas/compartido/datos/csv/TEDR.csv",
                "Tipos Efectivos Definicion Restringida (hipotecas).", "🏦",
                List.of("bde", "hipoteca", "tipos", "mercado")),
        new Link("IRPH (BdE)", "https://www.bde.es/webbe/es/estadisticas/temas/indices-referencia-prestamos-hipotecarios.html",
                "Indices de referencia de prestamos hipotecarios.", "📉",
                List.of("hipoteca", "tipos", "irph")),
        new Link("Central Riesgos CIRBE", "https://sedeelectronica.bde.es/sede/es/menu/ciudadanos/Consultas___f8aeed3f40d3a51.html",
                "Informe riesgos de clientes bancarios.", "🔐",
                List.of("due-diligence", "credito", "inquilino")),

        // ═══ Urbanismo / Obras ═══
        new Link("Codigo Tecnico CTE", "https://www.codigotecnico.org/",
                "Normativa tecnica de edificacion.", "🏗️",
                List.of("obra", "reforma", "cte")),
        new Link("Certificacion Energetica", "https://energia.gob.es/desarrollo/EficienciaEnergetica/CertificacionEnergetica/",
                "Certificado oficial de eficiencia energetica.", "🔋",
                List.of("esg", "energia", "obra", "venta")),
        new Link("IDAE - Energia", "https://www.idae.es/",
                "Instituto Diversificacion y Ahorro Energia.", "♻️",
                List.of("esg", "energia")),

        // ═══ Legal / BOE ═══
        new Link("BOE - Busqueda", "https://www.boe.es/buscar/",
                "Legislacion, anuncios y subastas oficiales.", "⚖️",
                List.of("legal", "boe", "contrato")),
        new Link("BOE - Subastas", "https://subastas.boe.es/",
                "Subastas judiciales y administrativas.", "🔨",
                List.of("subastas", "oportunidades", "boe")),
        new Link("LAU (Ley Arrendamientos)", "https://www.boe.es/buscar/act.php?id=BOE-A-1994-26003",
                "Ley 29/1994 de Arrendamientos Urbanos.", "📖",
                List.of("contrato", "alquiler", "legal")),
        new Link("Ley Vivienda 12/2023", "https://www.boe.es/buscar/act.php?id=BOE-A-2023-12203",
                "Ley estatal del Derecho a la Vivienda.", "📕",
                List.of("legal", "alquiler", "contrato")),
        new Link("Codigo Civil - Arrendamientos", "https://www.boe.es/buscar/act.php?id=BOE-A-1889-4763",
                "Contratos tipo y obligaciones.", "📘",
                List.of("contrato", "legal")),

        // ═══ Identidad / Validacion ═══
        new Link("DGT Consulta Vehiculos", "https://sede.dgt.gob.es/",
                "Consulta titularidad de vehiculo (trazabilidad).", "🚗",
                List.of("inquilino", "due-diligence")),
        new Link("Seguridad Social TGSS", "https://sede.seg-social.gob.es/",
                "Informes vida laboral, bases cotizacion.", "🧾",
                List.of("inquilino", "scoring", "due-diligence")),
        new Link("ASNEF", "https://www.asnef.com/",
                "Fichero de solvencia patrimonial (Equifax).", "⚠️",
                List.of("inquilino", "scoring", "moroso")),
        new Link("Policia DNI", "https://www.policia.es/miscelanea/consulta_dni/",
                "Validacion oficial DNI/NIE.", "🪪",
                List.of("validador", "dni")),

        // ═══ Estadisticas Macroeconomicas ═══
        new Link("Eurostat Housing", "https://ec.europa.eu/eurostat/web/housing",
                "Estadisticas de vivienda UE.", "🇪🇺",
                List.of("mercado", "zona", "europa")),
        new Link("OCDE Housing Markets", "https://www.oecd.org/housing/data/",
                "Indicadores mundiales de vivienda.", "🌐",
                List.of("mercado", "internacional")),
        new Link("Banco Mundial", "https://data.worldbank.org/indicator",
                "Indicadores macroeconomicos globales.", "🏦",
                List.of("mercado", "internacional")),

        // ═══ Camaras / Empresariales ═══
        new Link("Camara de Espana", "https://www.camara.es/",
                "Informes sectoriales y estadisticas.", "🏢",
                List.of("mercado", "empresa")),
        new Link("Registro Mercantil", "https://www.rmc.es/",
                "Consulta empresas, cuentas anuales, CIF.", "🏛️",
                List.of("empresa", "validador", "cif", "holding")),

        // ═══ Turistico ═══
        new Link("Registro Viviendas Turisticas", "https://registrovut.mitma.gob.es/",
                "Registro unico de viviendas de uso turistico.", "🏖️",
                List.of("turistico", "alquiler", "validador"))
    );

    public List<Link> all() { return ALL; }

    public List<Link> byTags(String... tags) {
        if (tags == null || tags.length == 0) return ALL;
        List<String> set = Arrays.asList(tags);
        List<Link> out = new ArrayList<>();
        for (Link l : ALL) {
            for (String t : set) {
                if (l.hasTag(t)) { out.add(l); break; }
            }
        }
        return out;
    }

    /** URL builder: busqueda BOE filtrada. */
    public String boeSearch(String q) {
        return "https://www.boe.es/buscar/doc.php?coleccion=iberlex&campo%5B0%5D=ID_SRCH&dato%5B0%5D="
                + URLEncoder.encode(q == null ? "" : q, StandardCharsets.UTF_8);
    }

    /** URL builder: subastas BOE por provincia. */
    public String boeSubastas(String provincia) {
        return "https://subastas.boe.es/subastas_ava.php?campo%5B0%5D=PROVINCIA&dato%5B0%5D="
                + URLEncoder.encode(provincia == null ? "" : provincia, StandardCharsets.UTF_8);
    }

    /** URL builder: visor Catastro por ref catastral. */
    public String catastroViewer(String rc) {
        String v = rc == null ? "" : rc.trim();
        return "https://www1.sedecatastro.gob.es/CYCBienInmueble/OVCListaBienes.aspx?rc1="
                + URLEncoder.encode(v.length() >= 14 ? v.substring(0, 14) : v, StandardCharsets.UTF_8)
                + "&rc2=" + URLEncoder.encode(v.length() >= 20 ? v.substring(14, 20) : "", StandardCharsets.UTF_8);
    }

    /** URL builder: nota simple registro propiedad. */
    public String notaSimpleRegistro() {
        return "https://www.registradores.org/propiedad/solicitud-registral-online";
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
        for (Link l : ALL) for (String t : l.tags()) counts.merge(t, 1, Integer::sum);
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(14).map(Map.Entry::getKey).toList();
    }
}
