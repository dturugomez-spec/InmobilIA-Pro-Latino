package com.inmobilia.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calculadora pública de impuestos ITP/AJD/IVA por compra de inmueble en España.
 * Datos por CCAA actualizados a 2026.
 */
@Controller
@RequiredArgsConstructor
public class TaxCalculatorController {

    /** ITP general por CCAA · % aplicado al precio de venta (vivienda usada). */
    public static final Map<String, Double> ITP_CCAA = new LinkedHashMap<>();
    /** AJD general por CCAA · % (vivienda nueva con IVA). */
    public static final Map<String, Double> AJD_CCAA = new LinkedHashMap<>();

    static {
        ITP_CCAA.put("Andalucía", 7.0);
        ITP_CCAA.put("Aragón", 8.0);
        ITP_CCAA.put("Asturias", 8.0);
        ITP_CCAA.put("Baleares", 8.0);
        ITP_CCAA.put("Canarias", 6.5);
        ITP_CCAA.put("Cantabria", 9.0);
        ITP_CCAA.put("Castilla-La Mancha", 9.0);
        ITP_CCAA.put("Castilla y León", 8.0);
        ITP_CCAA.put("Cataluña", 10.0);
        ITP_CCAA.put("Comunidad Valenciana", 10.0);
        ITP_CCAA.put("Extremadura", 8.0);
        ITP_CCAA.put("Galicia", 8.0);
        ITP_CCAA.put("La Rioja", 7.0);
        ITP_CCAA.put("Madrid", 6.0);
        ITP_CCAA.put("Murcia", 8.0);
        ITP_CCAA.put("Navarra", 6.0);
        ITP_CCAA.put("País Vasco", 4.0);

        AJD_CCAA.put("Andalucía", 1.2);
        AJD_CCAA.put("Aragón", 1.5);
        AJD_CCAA.put("Asturias", 1.2);
        AJD_CCAA.put("Baleares", 1.5);
        AJD_CCAA.put("Canarias", 1.0);
        AJD_CCAA.put("Cantabria", 1.5);
        AJD_CCAA.put("Castilla-La Mancha", 1.5);
        AJD_CCAA.put("Castilla y León", 1.5);
        AJD_CCAA.put("Cataluña", 1.5);
        AJD_CCAA.put("Comunidad Valenciana", 1.5);
        AJD_CCAA.put("Extremadura", 1.5);
        AJD_CCAA.put("Galicia", 1.5);
        AJD_CCAA.put("La Rioja", 1.0);
        AJD_CCAA.put("Madrid", 0.75);
        AJD_CCAA.put("Murcia", 2.0);
        AJD_CCAA.put("Navarra", 0.5);
        AJD_CCAA.put("País Vasco", 0.0);
    }

    @GetMapping("/calculadora-impuestos-compra")
    public String form(Model model) {
        model.addAttribute("ccaa", ITP_CCAA.keySet());
        return "tax_calculator";
    }

    @PostMapping("/calculadora-impuestos-compra")
    @ResponseBody
    public Map<String, Object> calculate(@RequestBody Map<String, Object> body) {
        double precio = parseD(body.get("precio"));
        String ccaa = String.valueOf(body.getOrDefault("ccaa", "Madrid"));
        boolean nueva = Boolean.TRUE.equals(body.get("nueva")) || "true".equals(String.valueOf(body.get("nueva")));
        boolean primeraVivienda = Boolean.TRUE.equals(body.get("primeraVivienda")) || "true".equals(String.valueOf(body.get("primeraVivienda")));
        boolean joven = Boolean.TRUE.equals(body.get("joven")) || "true".equals(String.valueOf(body.get("joven")));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("precio", precio);
        r.put("ccaa", ccaa);
        r.put("nueva", nueva);

        if (nueva) {
            // Vivienda nueva: IVA 10% + AJD por CCAA
            double iva = precio * 0.10;
            double ajdPct = AJD_CCAA.getOrDefault(ccaa, 1.5);
            // Reducciones jóvenes
            if (joven && (ccaa.equals("Madrid") || ccaa.equals("Andalucía"))) ajdPct *= 0.5;
            double ajd = precio * ajdPct / 100;
            r.put("iva", round(iva));
            r.put("ivaPct", 10.0);
            r.put("ajd", round(ajd));
            r.put("ajdPct", ajdPct);
            r.put("notaria", round(precio * 0.005));
            r.put("registro", round(precio * 0.003));
            r.put("gestoria", 400.0);
            r.put("totalImpuestos", round(iva + ajd));
            r.put("totalGastos", round(iva + ajd + (precio * 0.008) + 400));
        } else {
            // Vivienda usada: ITP por CCAA
            double itpPct = ITP_CCAA.getOrDefault(ccaa, 8.0);
            // Tipos reducidos
            if (primeraVivienda && joven) {
                if (ccaa.equals("Madrid")) itpPct = 4.0;
                else if (ccaa.equals("Andalucía")) itpPct = 3.5;
                else if (ccaa.equals("Cataluña")) itpPct = 5.0;
                else if (ccaa.equals("Comunidad Valenciana")) itpPct = 8.0;
            }
            double itp = precio * itpPct / 100;
            r.put("itp", round(itp));
            r.put("itpPct", itpPct);
            r.put("notaria", round(precio * 0.005));
            r.put("registro", round(precio * 0.003));
            r.put("gestoria", 400.0);
            r.put("totalImpuestos", round(itp));
            r.put("totalGastos", round(itp + (precio * 0.008) + 400));
        }
        r.put("totalConPiso", round(precio + (double) r.get("totalGastos")));

        return r;
    }

    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
