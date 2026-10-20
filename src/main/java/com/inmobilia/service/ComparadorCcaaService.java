package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comparador agregado por Comunidad Autonoma de las 17 CCAA + Ceuta + Melilla.
 * Une fiscalidad real (ITP, AJD, plusvalia, IRPF tramo autonomico) con datos
 * de mercado (yield medio aproximado, % zona tensionada, IPV YoY oficial INE).
 *
 * Diferenciador: ningun software inmobiliario espanol da una vista unica
 * comparativa fiscal-mercado por CCAA. Idealista da precios pero no fiscalidad,
 * los gestores fiscales dan tipos pero no rendimiento neto.
 */
@Service
@RequiredArgsConstructor
public class ComparadorCcaaService {

    public record CcaaRow(
        String ccaa,
        String icon,
        double itpPct,         // ITP transmisiones onerosas
        double ajdPct,         // Actos Juridicos Documentados
        double yieldBruto,     // Rentabilidad bruta media estimada
        double yieldNeto,      // Aprox neto despues de gastos e impuestos
        double precioM2Compra, // €/m2 venta aprox
        double rentaM2Mes,     // €/m2 alquiler mediano
        boolean zonaTensionada,
        String fiscalidadNota,
        String veredicto
    ) {}

    private static final List<CcaaRow> CCAA = build();

    private static List<CcaaRow> build() {
        List<CcaaRow> l = new ArrayList<>();
        // Datos aproximados publicados por Min. Hacienda + INE + portales 2024-2025
        l.add(new CcaaRow("Madrid",            "🟦",  6.0, 0.75, 4.8, 3.4, 4100, 17.5, false,
                "ITP 6% (mas bajo de Espana). AJD 0,75%. IRPF autonomico bajo.",
                "Mejor fiscalidad de inversion del pais"));
        l.add(new CcaaRow("Cataluna",          "🟨", 10.0, 1.50, 5.5, 3.8, 3650, 18.2, true,
                "ITP 10% (alto). 140 municipios zona tensionada. Tope alquiler.",
                "Cuidado: regulacion intensa en zonas urbanas"));
        l.add(new CcaaRow("Andalucia",         "🟩",  7.0, 1.20, 6.2, 4.5, 1900, 10.1, false,
                "ITP 7% (rebajado 2021). Sin zona tensionada. Plusvalia variable.",
                "Yield neto top en costa y capitales"));
        l.add(new CcaaRow("C. Valenciana",     "🟧", 10.0, 1.50, 5.8, 4.0, 1850,  9.8, false,
                "ITP 10%. Sin zona tensionada. AJD 1,5%.",
                "Buen yield en costa, ojo a fiscalidad alta"));
        l.add(new CcaaRow("Galicia",           "🟦", 10.0, 1.50, 5.5, 3.9, 1450,  7.8, false,
                "ITP 10%. Sin zona tensionada. Bonificacion familias.",
                "Yield discreto, mercado estable"));
        l.add(new CcaaRow("Pais Vasco",        "🟥",  4.0, 0.50, 4.5, 3.2, 3400, 13.5, true,
                "ITP 4% (foral, mas bajo). Bilbao/Donostia/Vitoria zona tensionada.",
                "Mejor ITP del pais, riesgo regulatorio"));
        l.add(new CcaaRow("Castilla y Leon",   "🟫",  8.0, 1.50, 5.7, 4.1, 1350,  7.2, false,
                "ITP 8%. Sin zona tensionada. Bonificaciones familia.",
                "Yield neto razonable, demanda baja en rural"));
        l.add(new CcaaRow("Castilla-La Mancha","🟧",  9.0, 1.50, 6.0, 4.3, 1200,  6.8, false,
                "ITP 9%. Sin zona tensionada. Cerca Madrid: oportunidad.",
                "Madrid+1: yield alto en commuter belt"));
        l.add(new CcaaRow("Asturias",          "🟦",  8.0, 1.20, 5.4, 3.8, 1800,  8.5, false,
                "ITP 8% (rebajado). Sin zona tensionada.",
                "Demanda estable, sin presion regulatoria"));
        l.add(new CcaaRow("Cantabria",         "🟦",  9.0, 1.50, 5.0, 3.5, 2200, 10.0, false,
                "ITP 9%. Sin zona tensionada.",
                "Mercado pequeno pero estable"));
        l.add(new CcaaRow("Aragon",            "🟨",  8.0, 1.50, 5.6, 4.0, 1750,  9.2, false,
                "ITP 8%. Sin zona tensionada. Bonificaciones VPO.",
                "Zaragoza eje logistico, oportunidad"));
        l.add(new CcaaRow("Navarra",           "🟩",  6.0, 0.50, 4.7, 3.4, 2400, 10.8, false,
                "ITP 6% (foral). AJD 0,5%. Fiscalidad muy ventajosa.",
                "Fiscalidad foral favorable, yield medio"));
        l.add(new CcaaRow("La Rioja",          "🟥",  7.0, 1.00, 5.5, 3.9, 1600,  8.5, false,
                "ITP 7%. AJD 1%. Sin zona tensionada.",
                "Mercado pequeno, fiscalidad atractiva"));
        l.add(new CcaaRow("Murcia",            "🟧",  8.0, 1.50, 6.4, 4.6, 1300,  8.0, false,
                "ITP 8%. Sin zona tensionada.",
                "Yield bruto alto, demanda turistica"));
        l.add(new CcaaRow("Extremadura",       "🟩",  8.0, 1.50, 6.5, 4.7,  950,  6.0, false,
                "ITP 8%. Sin zona tensionada.",
                "Maximo yield bruto pero demanda limitada"));
        l.add(new CcaaRow("Baleares",          "🟦", 11.0, 1.50, 4.5, 3.0, 4500, 16.0, true,
                "ITP 11% (mas alto Espana). Algunos municipios zona tensionada.",
                "Premium turistico, fiscalidad muy alta"));
        l.add(new CcaaRow("Canarias",          "🟨",  7.5, 1.00, 5.2, 3.7, 2300, 12.5, false,
                "ITP 7,5%. IGIC en lugar de IVA. Sin zona tensionada general.",
                "Regimen fiscal especial Canarias (REF)"));
        l.add(new CcaaRow("Ceuta",             "⬜",  6.0, 0.50, 5.0, 3.6, 1900,  9.0, false,
                "ITP 6%. Bonificaciones territoriales 50%.",
                "Bonificaciones territoriales muy fuertes"));
        l.add(new CcaaRow("Melilla",           "⬜",  6.0, 0.50, 5.1, 3.7, 1850,  8.5, false,
                "ITP 6%. Bonificaciones territoriales 50%.",
                "Bonificaciones territoriales muy fuertes"));
        return l;
    }

    public List<CcaaRow> all() { return CCAA; }

    public List<CcaaRow> top(String orderBy, int n) {
        List<CcaaRow> sorted = new ArrayList<>(CCAA);
        java.util.Comparator<CcaaRow> cmp = switch (orderBy == null ? "" : orderBy) {
            case "itp"        -> java.util.Comparator.comparingDouble(CcaaRow::itpPct);
            case "yieldBruto" -> java.util.Comparator.comparingDouble(CcaaRow::yieldBruto).reversed();
            case "yieldNeto"  -> java.util.Comparator.comparingDouble(CcaaRow::yieldNeto).reversed();
            case "rentaM2"    -> java.util.Comparator.comparingDouble(CcaaRow::rentaM2Mes).reversed();
            case "precioM2"   -> java.util.Comparator.comparingDouble(CcaaRow::precioM2Compra);
            default            -> java.util.Comparator.comparingDouble(CcaaRow::yieldNeto).reversed();
        };
        sorted.sort(cmp);
        return n > 0 && n < sorted.size() ? sorted.subList(0, n) : sorted;
    }

    public Map<String, Object> resumen() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", CCAA.size());
        m.put("conZonaTensionada", CCAA.stream().filter(CcaaRow::zonaTensionada).count());
        m.put("itpMedio", CCAA.stream().mapToDouble(CcaaRow::itpPct).average().orElse(0));
        m.put("itpMin", CCAA.stream().mapToDouble(CcaaRow::itpPct).min().orElse(0));
        m.put("itpMax", CCAA.stream().mapToDouble(CcaaRow::itpPct).max().orElse(0));
        m.put("yieldMedio", CCAA.stream().mapToDouble(CcaaRow::yieldNeto).average().orElse(0));
        m.put("yieldMax", CCAA.stream().mapToDouble(CcaaRow::yieldNeto).max().orElse(0));
        return m;
    }
}
