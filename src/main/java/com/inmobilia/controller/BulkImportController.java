package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/importar")
@Slf4j
public class BulkImportController {

    private final SupabaseService supabase;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        return "bulk_import";
    }

    @PostMapping("/{tipo}")
    public String importar(@PathVariable String tipo,
                            @RequestParam("archivo") MultipartFile file,
                            HttpSession session) throws Exception {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        int procesados = 0, errores = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            String[] cabecera = null;
            boolean primera = true;
            while ((linea = br.readLine()) != null) {
                String[] cols = parseLine(linea);
                if (primera) { cabecera = cols; primera = false; continue; }
                if (cols.length == 0 || cabecera == null) continue;

                try {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("user_id", uid);
                    for (int i = 0; i < cabecera.length && i < cols.length; i++) {
                        String key = normalizeKey(cabecera[i]);
                        String val = cols[i].trim();
                        if (val.isEmpty()) continue;
                        // Auto-detectar tipo
                        if (key.contains("precio") || key.contains("renta") || key.contains("importe") || key.contains("metros") || key.contains("presupuesto")) {
                            try { data.put(key, Double.parseDouble(val.replace(",", ".").replace(" ", ""))); }
                            catch (Exception e) { data.put(key, val); }
                        } else if (key.contains("habitaciones") || key.contains("banos") || key.equals("ano_construccion")) {
                            try { data.put(key, Integer.parseInt(val)); } catch (Exception e) { data.put(key, val); }
                        } else {
                            data.put(key, val);
                        }
                    }
                    data.put("created_at", OffsetDateTime.now().toString());

                    String tabla = switch (tipo) {
                        case "propiedades" -> "propiedades";
                        case "leads" -> "leads";
                        case "inquilinos" -> "inquilinos";
                        case "propietarios" -> "propietarios";
                        default -> null;
                    };
                    if (tabla == null) continue;

                    supabase.insert(tabla, data);
                    procesados++;
                } catch (Exception e) {
                    errores++;
                    log.warn("Err línea import: {}", e.getMessage());
                }
            }
        }

        SessionUtils.flash(session, "✅ " + procesados + " " + tipo + " importados (" + errores + " errores)");
        return "redirect:/importar";
    }

    /** Parsing simple CSV con soporte para comillas. */
    private String[] parseLine(String linea) {
        List<String> res = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean enComillas = false;
        for (char c : linea.toCharArray()) {
            if (c == '"') enComillas = !enComillas;
            else if ((c == ';' || c == ',') && !enComillas) {
                res.add(current.toString()); current.setLength(0);
            } else current.append(c);
        }
        res.add(current.toString());
        return res.toArray(new String[0]);
    }

    private String normalizeKey(String k) {
        return k.toLowerCase().trim()
                .replaceAll("[áàä]", "a").replaceAll("[éèë]", "e").replaceAll("[íìï]", "i")
                .replaceAll("[óòö]", "o").replaceAll("[úùü]", "u").replaceAll("ñ", "n")
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_|_$", "");
    }
}
