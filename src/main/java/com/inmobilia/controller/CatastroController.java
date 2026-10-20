package com.inmobilia.controller;

import com.inmobilia.service.CatastroService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/catastro")
public class CatastroController {

    private final CatastroService catastroService;
    private final SupabaseService supabase;

    @GetMapping
    public String form(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        return "catastro_search";
    }

    @PostMapping("/consultar-rc")
    @ResponseBody
    public Map<String, Object> consultarRC(@RequestParam String refCatastral) {
        return catastroService.consultarPorRC(refCatastral);
    }

    @PostMapping("/consultar-coord")
    @ResponseBody
    public Map<String, Object> consultarCoord(@RequestParam double lat, @RequestParam double lng) {
        return catastroService.consultarPorCoordenadas(lat, lng);
    }

    @PostMapping("/consultar-dir")
    @ResponseBody
    public Map<String, Object> consultarDir(@RequestParam Map<String, String> form) {
        return catastroService.consultarPorDireccion(
                form.get("provincia"), form.get("municipio"),
                form.get("tipoVia"), form.get("calle"), form.get("numero"));
    }

    /** Auto-rellena datos de una propiedad consultando catastro. */
    @PostMapping("/auto-rellenar/{propId}")
    @ResponseBody
    public Map<String, Object> autoRellenar(@PathVariable String propId, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        var rows = supabase.selectFilter("propiedades", "id=eq." + propId + "&user_id=eq." + uid);
        if (rows.isEmpty()) return Map.of("ok", false);
        var prop = rows.get(0);

        String rc = String.valueOf(prop.get("referencia_catastral"));
        if (rc == null || rc.isBlank() || "null".equals(rc)) {
            return Map.of("ok", false, "error", "La propiedad no tiene referencia catastral");
        }

        Map<String, Object> data = catastroService.consultarPorRC(rc);
        if (!Boolean.TRUE.equals(data.get("ok"))) return data;

        // Actualizar campos
        Map<String, Object> upd = new java.util.LinkedHashMap<>();
        if (data.get("ano_construccion") != null) upd.put("ano_construccion", data.get("ano_construccion"));
        if (data.get("superficie_construida") != null) upd.put("metros_cuadrados", data.get("superficie_construida"));
        if (data.get("uso") != null) upd.put("uso_catastral", data.get("uso"));

        if (!upd.isEmpty()) {
            supabase.update("propiedades", propId, upd);
        }

        return Map.of("ok", true, "actualizado", upd);
    }
}
