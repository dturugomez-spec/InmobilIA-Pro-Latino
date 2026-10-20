package com.inmobilia.controller;

import com.inmobilia.controller.TaxCalculatorController;
import com.inmobilia.controller.ValuationPublicController;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Genera un informe PDF profesional para entregar al propietario en visita de captación.
 * Incluye: análisis de zona, comparativa precios, estimación, propuesta de marketing.
 */
@Controller
@RequiredArgsConstructor
public class CapturadorReportController {

    @GetMapping("/herramientas/informe-captador")
    public String form(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Informe para captador");
        model.addAttribute("ccaa", TaxCalculatorController.ITP_CCAA.keySet());
        return "capturador_form";
    }

    @PostMapping("/herramientas/informe-captador")
    public String generate(@RequestParam Map<String, String> body,
                           HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";

        // Cálculos
        double m2 = parseD(body.get("m2"));
        double precioPretendido = parseD(body.get("precioPretendido"));
        int beds = (int) parseD(body.get("bedrooms"));
        int year = (int) parseD(body.get("year"));
        String city = body.getOrDefault("city", "Madrid");
        String type = body.getOrDefault("type", "Residencial");

        double pricePerM2 = priceFor(city);
        double estimacionVenta = m2 * pricePerM2;
        if (beds >= 3) estimacionVenta *= 1.05;
        int currentYear = java.time.Year.now().getValue();
        if (year > 0 && currentYear - year < 10) estimacionVenta *= 1.10;
        else if (year > 0 && currentYear - year > 50) estimacionVenta *= 0.85;

        double rentaMensual = estimacionVenta * 0.05 / 12;
        double yieldBruto = 5.0;

        double diff = precioPretendido > 0 ? ((precioPretendido - estimacionVenta) / estimacionVenta * 100) : 0;
        String veredicto = diff > 15 ? "ALTO" : diff > 5 ? "ALGO ALTO" : diff < -10 ? "BAJO" : "AJUSTADO";

        // Tiempo en mercado estimado
        int diasMercado = diff > 15 ? 180 : diff > 5 ? 120 : diff < -10 ? 30 : 60;

        model.addAttribute("data", body);
        model.addAttribute("hoy", LocalDate.now().format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", new Locale("es","ES"))));
        model.addAttribute("pricePerM2", pricePerM2);
        model.addAttribute("estimacionVenta", estimacionVenta);
        model.addAttribute("estimacionMin", estimacionVenta * 0.92);
        model.addAttribute("estimacionMax", estimacionVenta * 1.08);
        model.addAttribute("rentaMensual", rentaMensual);
        model.addAttribute("yieldBruto", yieldBruto);
        model.addAttribute("precioPretendido", precioPretendido);
        model.addAttribute("diff", diff);
        model.addAttribute("veredicto", veredicto);
        model.addAttribute("diasMercado", diasMercado);

        return "capturador_report";
    }

    private static double priceFor(String city) {
        String c = city == null ? "" : city.toLowerCase().trim();
        return switch (c) {
            case "madrid" -> 4200; case "barcelona" -> 4500;
            case "san sebastián","san sebastian","donostia" -> 5800;
            case "bilbao" -> 3200; case "palma","palma de mallorca" -> 3900;
            case "valencia" -> 2400; case "málaga","malaga" -> 2600;
            case "sevilla" -> 2200; case "zaragoza" -> 1800;
            case "alicante" -> 2100; case "murcia" -> 1500;
            case "vitoria" -> 2700; case "córdoba","cordoba" -> 1700;
            case "valladolid" -> 1700; case "vigo" -> 1900;
            default -> 1800;
        };
    }

    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
