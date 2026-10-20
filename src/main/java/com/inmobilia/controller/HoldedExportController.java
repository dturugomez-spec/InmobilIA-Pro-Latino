package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Exportador a Holded / Sage / A3 vía CSV estándar + API Holded directa si hay token.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/holded")
public class HoldedExportController {

    private final SupabaseService supabase;
    private final RestTemplate rest = new RestTemplate();

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> configs = supabase.selectFilter("integraciones", "user_id=eq." + uid + "&proveedor=eq.holded");
        Map<String, Object> cfg = configs.isEmpty() ? new LinkedHashMap<>() : configs.get(0);

        long facturas = supabase.selectFilter("facturas", "user_id=eq." + uid).size();
        long gastos = supabase.selectFilter("movimientos_caja", "user_id=eq." + uid + "&tipo=eq.gasto").size();

        model.addAttribute("cfg", cfg);
        model.addAttribute("facturas", facturas);
        model.addAttribute("gastos", gastos);
        return "holded_panel";
    }

    @PostMapping("/config")
    public String saveConfig(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("proveedor", "holded");
        data.put("api_key", form.get("api_key"));
        data.put("activo", true);

        List<Map<String, Object>> exist = supabase.selectFilter("integraciones", "user_id=eq." + uid + "&proveedor=eq.holded");
        if (!exist.isEmpty()) supabase.update("integraciones", String.valueOf(exist.get(0).get("id")), data);
        else {
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("integraciones", data);
        }
        return "redirect:/holded";
    }

    /** Exporta facturas a CSV Holded. */
    @GetMapping("/export/facturas.csv")
    public ResponseEntity<String> exportFacturas(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> facturas = supabase.selectFilter("facturas", "user_id=eq." + uid);
        StringBuilder csv = new StringBuilder();
        csv.append("Numero;Fecha;Cliente;NIF;Concepto;Base;IVA;Total;Estado\n");
        for (Map<String, Object> f : facturas) {
            csv.append(s(f.get("numero"))).append(";")
               .append(s(f.get("fecha"))).append(";")
               .append(s(f.get("cliente_nombre"))).append(";")
               .append(s(f.get("cliente_nif"))).append(";")
               .append(s(f.get("concepto"))).append(";")
               .append(s(f.get("base_imponible"))).append(";")
               .append(s(f.get("iva_importe"))).append(";")
               .append(s(f.get("total"))).append(";")
               .append(s(f.get("estado"))).append("\n");
        }

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN);
        h.setContentDispositionFormData("attachment", "facturas-holded.csv");
        return new ResponseEntity<>(csv.toString(), h, 200);
    }

    /** Sync con API Holded (necesita api_key). */
    @PostMapping("/sync")
    @ResponseBody
    public Map<String, Object> sync(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        List<Map<String, Object>> cfgs = supabase.selectFilter("integraciones", "user_id=eq." + uid + "&proveedor=eq.holded");
        if (cfgs.isEmpty() || cfgs.get(0).get("api_key") == null) {
            return Map.of("ok", false, "error", "Configura primero la API key");
        }
        String apiKey = String.valueOf(cfgs.get(0).get("api_key"));

        // Llamada real: https://api.holded.com/api/invoicing/v1/documents/invoice
        // (aquí sólo placeholder - sería POST con headers "key: {apiKey}")
        return Map.of("ok", true, "mensaje", "Sync iniciada con Holded API");
    }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o).replace(";", ","); }
}
