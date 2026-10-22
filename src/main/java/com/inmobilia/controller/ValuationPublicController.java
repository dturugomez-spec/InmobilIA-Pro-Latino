package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.service.ValoracionService;
import com.inmobilia.service.WorkflowEngine;
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
 *
 * Usa la misma valoración que el resto de la app (precio por provincia + comparables de la
 * cartera de la agencia, con su nivel de confianza). Antes tenía una tabla propia de 15
 * ciudades y para cualquier otro municipio se inventaba un precio por m².
 */
@Controller
@RequiredArgsConstructor
public class ValuationPublicController {

    /** Rentabilidad bruta orientativa del alquiler residencial, para la renta estimada. */
    static final double RENTABILIDAD_BRUTA = 5.0;

    private final SupabaseService supabase;
    private final ValoracionService valoracion;
    private final WorkflowEngine workflows;
    private final com.inmobilia.service.PaisService paises;

    @GetMapping("/tasacion")
    public String form(@RequestParam(required = false) String ref, Model model) {
        if (ref != null && ref.matches("[0-9a-fA-F-]{36}")) paises.usar(ref); // moneda y ejemplos del país de la agencia
        model.addAttribute("ref", ref);
        return "public_valuation";
    }

    @PostMapping("/tasacion")
    @ResponseBody
    public Map<String, Object> calculate(@RequestBody Map<String, Object> body) {
        String city = str(body.get("city"));
        String type = str(body.getOrDefault("type", "Residencial"));
        String name = str(body.get("name"));
        String email = str(body.get("email"));
        String phone = str(body.get("phone"));
        String agencyRef = str(body.get("agencyRef"));

        // Agencia que enlazó la calculadora (sus comparables afinan el precio)
        Map<String, Object> agencia = null;
        if (agencyRef.matches("[0-9a-fA-F-]{36}")) {
            var r = supabase.findBy("users", Map.of("id", agencyRef));
            if (!r.isEmpty()) agencia = r.get(0);
        }

        Map<String, String> in = new LinkedHashMap<>();
        in.put("municipio", city);
        in.put("provincia", city);
        in.put("metros", str(body.get("m2")));
        in.put("habitaciones", str(body.get("bedrooms")));
        in.put("banos", str(body.get("bathrooms")));
        in.put("ano_construccion", str(body.get("year")));
        in.put("tipo", "Comercial".equalsIgnoreCase(type) ? "local" : "piso"); // claves internas del AVM
        Map<String, Object> v = valoracion.valorar(agencia == null ? null : String.valueOf(agencia.get("id")), in);

        double central = num(v.get("precio_estimado"));
        double rentaMensual = central * RENTABILIDAD_BRUTA / 100 / 12;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("ventaMin", Math.round(num(v.get("precio_min"))));
        result.put("ventaMax", Math.round(num(v.get("precio_max"))));
        result.put("ventaCentral", Math.round(central));
        result.put("rentaMensual", Math.round(rentaMensual));
        result.put("rentaMin", Math.round(rentaMensual * 0.92));
        result.put("rentaMax", Math.round(rentaMensual * 1.08));
        result.put("yieldBruto", RENTABILIDAD_BRUTA);
        result.put("confianza", v.get("confianza"));
        result.put("metodo", v.get("metodo"));
        result.put("moneda", com.inmobilia.util.PaisActual.moneda());

        if (agencia != null && email.contains("@")) {
            String orgId = String.valueOf(agencia.get("id"));
            Map<String, Object> lead = new LinkedHashMap<>();
            lead.put("user_id", orgId);
            lead.put("nombre", name);
            lead.put("email", email);
            lead.put("telefono", phone);
            lead.put("interes", String.format(com.inmobilia.util.PaisActual.get().locale(),
                    "Solicita valoración: %s en %s, %s m², %s hab. Estimación: %,.0f %s (confianza %s)",
                    type, city, str(body.get("m2")), str(body.get("bedrooms")), central,
                    com.inmobilia.util.PaisActual.moneda(), v.get("confianza")));
            lead.put("origen", "Calculadora web");
            lead.put("etapa", "Nuevo");
            lead.put("zona", city);
            lead.put("tipo_activo", type);
            lead.put("created_at", OffsetDateTime.now().toString());
            Map<String, Object> guardado = supabase.insert("leads", lead);
            if (guardado != null) workflows.triggerEvent(orgId, "lead_creado", guardado);
        }
        return result;
    }

    private static String str(Object o) { return o == null || "null".equals(String.valueOf(o)) ? "" : String.valueOf(o).trim(); }
    private static double num(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; } }
}
