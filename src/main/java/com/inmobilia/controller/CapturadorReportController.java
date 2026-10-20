package com.inmobilia.controller;

import com.inmobilia.controller.ValuationPublicController;
import com.inmobilia.service.CurrencyService;
import com.inmobilia.util.PaisActual;
import com.inmobilia.util.PreciosReferencia;
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

    private final CurrencyService divisas;

    @GetMapping("/herramientas/informe-captador")
    public String form(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Informe para captador");
        model.addAttribute("ciudadEjemplo", PreciosReferencia.ciudadPrincipal(PaisActual.get()));
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
        String city = body.getOrDefault("city", PreciosReferencia.ciudadPrincipal(PaisActual.get()));
        String type = body.getOrDefault("type", "Residencial");

        double pricePerM2 = divisas.convertir(PreciosReferencia.usdPorM2(PaisActual.get(), city),
                "USD", PaisActual.moneda());
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
        model.addAttribute("hoy", LocalDate.now().format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", PaisActual.get().locale())));
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

    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
