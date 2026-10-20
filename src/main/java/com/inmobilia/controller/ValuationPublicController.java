package com.inmobilia.controller;

import com.inmobilia.service.CurrencyService;
import com.inmobilia.service.PaisService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.Pais;
import com.inmobilia.util.PreciosReferencia;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calculadora pública de valoración estimada de un inmueble.
 * Cuando el dueño rellena → genera lead automático para la agencia que la enlazó.
 *
 * URL ejemplo: /tasacion?ref=AGENCY123 (con ref opcional para tracking)
 */
@Controller
@RequiredArgsConstructor
public class ValuationPublicController {

    private final SupabaseService supabase;
    private final PaisService paises;
    private final CurrencyService divisas;

    @GetMapping("/tasacion")
    public String form(@RequestParam(required = false) String ref, Model model) {
        if (ref != null && !ref.isBlank()) paises.usar(ref); // moneda y ejemplos del país de la agencia
        model.addAttribute("ref", ref);
        return "public_valuation";
    }

    @PostMapping("/tasacion")
    @ResponseBody
    public Map<String, Object> calculate(@RequestBody Map<String, Object> body) {
        // Extraer datos
        String city = String.valueOf(body.getOrDefault("city", ""));
        double m2 = parseD(body.get("m2"));
        int beds = (int) parseD(body.get("bedrooms"));
        int baths = (int) parseD(body.get("bathrooms"));
        int year = (int) parseD(body.get("year"));
        String type = String.valueOf(body.getOrDefault("type", "Residencial"));
        String name = String.valueOf(body.getOrDefault("name", ""));
        String email = String.valueOf(body.getOrDefault("email", ""));
        String phone = String.valueOf(body.getOrDefault("phone", ""));
        String agencyRef = String.valueOf(body.getOrDefault("agencyRef", ""));

        // País de la agencia que enlazó la calculadora (moneda y precios de referencia)
        Pais pais = agencyRef.isBlank() ? com.inmobilia.util.PaisActual.get() : paises.usar(agencyRef);

        // Cálculo simplificado por zona y características (datos orientativos)
        double basePrice = divisas.convertir(PreciosReferencia.usdPorM2(pais, city), "USD", pais.moneda());
        double estVenta = basePrice * m2;
        // Ajuste por habitaciones
        if (beds >= 3) estVenta *= 1.05;
        // Ajuste por antigüedad
        int currentYear = java.time.Year.now().getValue();
        if (year > 0 && currentYear - year < 10) estVenta *= 1.10;
        else if (year > 0 && currentYear - year > 50) estVenta *= 0.85;
        // Ajuste por baños
        if (baths >= 2) estVenta *= 1.03;

        // Renta estimada (yield bruto típico 5-6%)
        double rentaMensual = estVenta * 0.05 / 12;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("ventaMin", Math.round(estVenta * 0.92));
        result.put("ventaMax", Math.round(estVenta * 1.08));
        result.put("ventaCentral", Math.round(estVenta));
        result.put("rentaMensual", Math.round(rentaMensual));
        result.put("rentaMin", Math.round(rentaMensual * 0.92));
        result.put("rentaMax", Math.round(rentaMensual * 1.08));
        result.put("yieldBruto", 5.0);
        result.put("moneda", pais.moneda());

        // Si nos dieron datos de contacto + agencyRef → guardar como lead
        if (!email.isBlank() && !agencyRef.isBlank()) {
            try {
                // Buscar agencia por id o por algún identificador
                var agencyUsers = supabase.findBy("users", Map.of("id", agencyRef));
                if (!agencyUsers.isEmpty()) {
                    String orgId = String.valueOf(agencyUsers.get(0).get("id"));
                    Map<String, Object> lead = new LinkedHashMap<>();
                    lead.put("user_id", orgId);
                    lead.put("nombre", name);
                    lead.put("email", email);
                    lead.put("telefono", phone);
                    lead.put("interes", String.format("Solicita valoración: %s en %s, %.0fm², %dh, %db. Estimación: %d %s",
                            type, city, m2, beds, baths, Math.round(estVenta), pais.moneda()));
                    lead.put("origen", "Calculadora web");
                    lead.put("etapa", "Nuevo");
                    lead.put("zona", city);
                    lead.put("tipo_activo", type);
                    lead.put("created_at", OffsetDateTime.now().toString());
                    supabase.insert("leads", lead);
                }
            } catch (Exception ignored) {}
        }

        return result;
    }

    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
