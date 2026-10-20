package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Benchmark de renta vs Indice de Referencia del Sistema Estatal de Indices de
 * Precios de Alquiler (MITMA / Min. Vivienda) y deteccion de zona tensionada
 * conforme a la Ley 12/2023 (Ley por el Derecho a la Vivienda).
 *
 * Datos baked-in de fuentes oficiales publicas:
 *  - €/m2/mes mediano por provincia (Indice MITMA 2024-2025).
 *  - Lista de municipios con declaracion vigente de zona de mercado tensionado
 *    (Cataluna 140 municipios, Pais Vasco selectivo, etc.).
 *
 * Esta es la unica herramienta del mercado que cruza la renta del usuario con
 * el indice oficial y le dice si se le aplica el cap de la Ley de Vivienda.
 */
@Service
@RequiredArgsConstructor
public class BenchmarkRentaService {

    /** Resultado del benchmark. */
    public record Benchmark(
        double rentaUsuarioMes,
        double rentaUsuarioM2,
        double indiceM2,
        double indiceMin,
        double indiceMax,
        double rentaIndiceMes,
        double diffPct,
        String posicion,        // "BAJO", "EN_LINEA", "ALTO", "MUY_ALTO"
        String posicionLabel,
        String posicionColor,
        boolean zonaTensionada,
        String zonaDetalle,
        double topeLeyVivienda,  // tope si zona tensionada (= indice * 1.10 nuevo contrato)
        String veredicto,
        List<String> recomendaciones
    ) {}

    /** Provincia -> [€/m2/mes mediano, min razonable, max razonable] aprox MITMA 2024. */
    private static final Map<String, double[]> INDICE_PROV = buildIndice();

    /** Set de municipios declarados zona tensionada (lower-case sin tildes). */
    private static final java.util.Set<String> ZONAS_TENSIONADAS = buildZonas();

    private static Map<String, double[]> buildIndice() {
        Map<String, double[]> m = new LinkedHashMap<>();
        // Capitales / provincias top
        m.put("madrid",      new double[]{17.5, 13.0, 22.0});
        m.put("barcelona",   new double[]{18.2, 13.5, 23.5});
        m.put("baleares",    new double[]{16.0, 12.0, 21.0});
        m.put("malaga",      new double[]{14.5, 10.5, 19.0});
        m.put("valencia",    new double[]{12.8,  9.5, 16.5});
        m.put("alicante",    new double[]{11.2,  8.5, 14.5});
        m.put("bizkaia",     new double[]{12.5,  9.5, 16.0});
        m.put("gipuzkoa",    new double[]{13.8, 10.5, 17.5});
        m.put("alava",       new double[]{10.5,  8.0, 13.5});
        m.put("navarra",     new double[]{10.8,  8.0, 14.0});
        m.put("la coruna",   new double[]{ 9.5,  7.5, 12.5});
        m.put("pontevedra",  new double[]{ 8.8,  6.5, 11.5});
        m.put("santa cruz de tenerife", new double[]{12.8, 9.5, 16.5});
        m.put("las palmas",  new double[]{12.5,  9.5, 16.0});
        m.put("sevilla",     new double[]{10.5,  7.8, 13.5});
        m.put("cadiz",       new double[]{ 9.8,  7.2, 13.0});
        m.put("granada",     new double[]{ 8.8,  6.5, 11.5});
        m.put("cordoba",     new double[]{ 7.5,  5.5,  9.8});
        m.put("almeria",     new double[]{ 8.2,  6.0, 10.8});
        m.put("huelva",      new double[]{ 7.0,  5.0,  9.2});
        m.put("jaen",        new double[]{ 6.0,  4.5,  8.0});
        m.put("zaragoza",    new double[]{ 9.2,  7.0, 12.0});
        m.put("huesca",      new double[]{ 7.5,  5.5, 10.0});
        m.put("teruel",      new double[]{ 6.0,  4.5,  8.0});
        m.put("la rioja",    new double[]{ 8.5,  6.5, 11.0});
        m.put("cantabria",   new double[]{10.0,  7.5, 13.0});
        m.put("asturias",    new double[]{ 8.5,  6.5, 11.0});
        m.put("leon",        new double[]{ 6.5,  5.0,  8.5});
        m.put("burgos",      new double[]{ 7.5,  5.5, 10.0});
        m.put("valladolid",  new double[]{ 8.0,  6.0, 10.5});
        m.put("salamanca",   new double[]{ 7.8,  6.0, 10.2});
        m.put("zamora",      new double[]{ 5.5,  4.0,  7.5});
        m.put("palencia",    new double[]{ 6.5,  5.0,  8.5});
        m.put("avila",       new double[]{ 6.8,  5.0,  9.0});
        m.put("segovia",     new double[]{ 7.5,  5.5, 10.0});
        m.put("soria",       new double[]{ 6.0,  4.5,  8.0});
        m.put("toledo",      new double[]{ 7.2,  5.5,  9.5});
        m.put("guadalajara", new double[]{ 8.5,  6.5, 11.0});
        m.put("ciudad real", new double[]{ 6.5,  5.0,  8.5});
        m.put("cuenca",      new double[]{ 5.8,  4.5,  7.8});
        m.put("albacete",    new double[]{ 6.5,  5.0,  8.5});
        m.put("badajoz",     new double[]{ 6.0,  4.5,  8.0});
        m.put("caceres",     new double[]{ 5.8,  4.5,  7.8});
        m.put("murcia",      new double[]{ 8.0,  6.0, 10.5});
        m.put("castellon",   new double[]{ 8.5,  6.5, 11.0});
        m.put("tarragona",   new double[]{10.5,  8.0, 13.5});
        m.put("lleida",      new double[]{ 9.0,  7.0, 12.0});
        m.put("girona",      new double[]{12.0,  9.0, 15.5});
        m.put("lugo",        new double[]{ 6.5,  5.0,  8.5});
        m.put("ourense",     new double[]{ 6.0,  4.5,  8.0});
        m.put("ceuta",       new double[]{ 9.0,  7.0, 12.0});
        m.put("melilla",     new double[]{ 8.5,  6.5, 11.0});
        return m;
    }

    private static java.util.Set<String> buildZonas() {
        // Cataluna (declaracion 14-mar-2024 + ampliaciones): 140 municipios.
        // Solo listamos los principales para deteccion rapida; la Ley aplica
        // tope si municipio en lista oficial (publicada por la Generalitat / MITMA).
        return java.util.Set.of(
            "barcelona", "lhospitalet de llobregat", "badalona", "terrassa", "sabadell",
            "mataro", "santa coloma de gramenet", "cornella de llobregat", "sant cugat del valles",
            "rubi", "manresa", "vilanova i la geltru", "viladecans", "el prat de llobregat",
            "castelldefels", "granollers", "cerdanyola del valles", "mollet del valles",
            "esplugues de llobregat", "sant boi de llobregat", "girona", "lleida", "tarragona",
            "reus", "salou", "cambrils", "olot", "vic", "figueres", "blanes", "lloret de mar",
            // Pais Vasco
            "bilbao", "san sebastian", "vitoria-gasteiz", "donostia",
            // Otros previstos
            "palma", "ibiza"
        );
    }

    public Benchmark evaluar(String provincia, String municipio,
                              double m2, double rentaMes, boolean nuevoContrato) {
        String pkey = norm(provincia);
        String mkey = norm(municipio);

        double[] idx = INDICE_PROV.getOrDefault(pkey, new double[]{10.0, 7.5, 13.0});
        double indMed = idx[0], indMin = idx[1], indMax = idx[2];

        double rentaM2 = m2 > 0 ? rentaMes / m2 : 0;
        double rentaIndice = m2 > 0 ? indMed * m2 : 0;
        double diffPct = rentaIndice > 0 ? (rentaMes - rentaIndice) / rentaIndice * 100.0 : 0;

        String posicion, label, color;
        if (rentaM2 == 0) {
            posicion = "DESCONOCIDO"; label = "Sin datos"; color = "#A3AED0";
        } else if (rentaM2 < indMin) {
            posicion = "BAJO";    label = "Por debajo de mercado"; color = "#0EA5E9";
        } else if (rentaM2 <= indMed) {
            posicion = "EN_LINEA"; label = "En linea con el indice oficial"; color = "#05CD99";
        } else if (rentaM2 <= indMax) {
            posicion = "ALTO";    label = "Por encima de la mediana provincial"; color = "#FFB547";
        } else {
            posicion = "MUY_ALTO"; label = "Muy por encima del indice"; color = "#EE5D50";
        }

        boolean zona = mkey != null && !mkey.isBlank() && ZONAS_TENSIONADAS.contains(mkey);
        String zonaDet = zona
                ? "Municipio declarado ZONA DE MERCADO TENSIONADO (Ley 12/2023). Aplica tope de actualizacion y nueva regulacion del precio."
                : "Municipio NO declarado zona tensionada (a fecha actual). No aplica tope automatico, pero si IRAV anual.";

        double tope = indMed * 1.10 * Math.max(m2, 1);

        String veredicto;
        java.util.List<String> recom = new java.util.ArrayList<>();
        if (zona && nuevoContrato && rentaMes > tope) {
            veredicto = "INCUMPLIMIENTO POTENCIAL: nuevo contrato en zona tensionada por encima del tope (indice +10%).";
            recom.add("Reducir renta a un maximo de " + euro(tope) + "/mes para nuevo contrato.");
            recom.add("Documentar mejoras realizadas para aplicar incrementos permitidos.");
        } else if (zona && rentaM2 > indMax) {
            veredicto = "ALERTA: renta superior al rango oficial. Riesgo de revision por inquilino o SEPE.";
            recom.add("Considerar ajuste a " + euro(indMed * m2) + "/mes (mediana provincial).");
        } else if ("MUY_ALTO".equals(posicion)) {
            veredicto = "Renta muy por encima de mercado. Riesgo de vacancia y morosidad si no esta justificada.";
            recom.add("Contrastar con comparables de la zona.");
            recom.add("Revisar amenities y reformas que justifiquen el premium.");
        } else if ("BAJO".equals(posicion)) {
            veredicto = "Renta por debajo de mercado. Oportunidad de revaloracion legal en proxima renovacion.";
            recom.add("Aplicar IRAV en proxima actualizacion (consultar BOE).");
            recom.add("Revisar si la propiedad podria competir en tramo medio.");
        } else if ("EN_LINEA".equals(posicion)) {
            veredicto = "Renta optima. Equilibrio entre ingresos y riesgo de mercado.";
            recom.add("Mantener actualizacion anual via IRAV.");
        } else {
            veredicto = "Renta razonable. Vigilar evolucion del indice provincial.";
        }
        if (zona) recom.add("Actualizacion limitada por LAU + indice referencia (NO IPC) en zona tensionada.");

        return new Benchmark(
            rentaMes, rentaM2, indMed, indMin, indMax, rentaIndice, diffPct,
            posicion, label, color, zona, zonaDet, tope, veredicto, recom
        );
    }

    public java.util.Set<String> provinciasDisponibles() {
        return INDICE_PROV.keySet();
    }

    public List<Map<String, Object>> rankingProvincias() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        INDICE_PROV.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
            .forEach(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("provincia", capitalize(e.getKey()));
                row.put("medianaM2", e.getValue()[0]);
                row.put("min", e.getValue()[1]);
                row.put("max", e.getValue()[2]);
                out.add(row);
            });
        return out;
    }

    private String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u").replace("ñ", "n")
                .replace("'", "").trim();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c)) { sb.append(c); cap = true; }
            else if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
            else sb.append(c);
        }
        return sb.toString();
    }

    private String euro(double v) {
        return String.format(Locale.GERMAN, "%,.0f €", v);
    }
}
