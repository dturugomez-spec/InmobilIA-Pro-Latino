package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Importar registros masivamente desde CSV.
 * Formato esperado: cabecera con nombres de columnas que coincidan con campos de la BBDD.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ImportController {

    private final SupabaseService supabase;

    private static final Set<String> SUPPORTED = Set.of("propiedades", "inquilinos", "leads", "transacciones");

    @GetMapping("/import")
    public String form(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Importar datos masivos");
        return "import";
    }

    @PostMapping("/import")
    public String importFile(@RequestParam("file") MultipartFile file,
                             @RequestParam String table,
                             @RequestParam(defaultValue = ";") String separator,
                             HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!SUPPORTED.contains(table)) {
            SessionUtils.flash(session, "Tabla no soportada: " + table);
            return "redirect:/import";
        }
        if (file.isEmpty()) {
            SessionUtils.flash(session, "Archivo vacío");
            return "redirect:/import";
        }

        String uid = SessionUtils.uid(session);
        char sep = separator.isEmpty() ? ';' : separator.charAt(0);
        int imported = 0, skipped = 0;
        List<String> headers = null;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line, sep);

                if (headers == null) {
                    headers = new ArrayList<>();
                    for (String h : cols) headers.add(normalizeHeader(h));
                    continue;
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("user_id", uid);
                for (int i = 0; i < cols.length && i < headers.size(); i++) {
                    String h = headers.get(i);
                    String v = cols[i].trim().replaceAll("\"", "");
                    if (v.isBlank()) continue;
                    row.put(h, parseValue(h, v));
                }
                row.put("created_at", OffsetDateTime.now().toString());

                if (supabase.insert(table, row) != null) imported++;
                else skipped++;
            }
        } catch (Exception e) {
            log.error("Error importing: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
            return "redirect:/import";
        }

        SessionUtils.flash(session, String.format("Importación completada: %d registros · %d omitidos", imported, skipped));
        return "redirect:/crud/" + table;
    }

    private static String normalizeHeader(String h) {
        return h.trim().toLowerCase()
                .replaceAll("\\s+", "_")
                .replaceAll("[áàä]", "a").replaceAll("[éèë]", "e").replaceAll("[íìï]", "i")
                .replaceAll("[óòö]", "o").replaceAll("[úùü]", "u").replaceAll("ñ", "n");
    }

    private static Object parseValue(String key, String v) {
        // Si parece número, convertir
        if (v.matches("-?\\d+(\\.\\d+)?")) {
            try { return Double.parseDouble(v); } catch (Exception ignored) {}
        }
        if (v.matches("-?\\d{1,3}([.,]\\d{3})*([.,]\\d+)?")) {
            try { return Double.parseDouble(v.replace(".", "").replace(",", ".")); } catch (Exception ignored) {}
        }
        if ("true".equalsIgnoreCase(v) || "sí".equalsIgnoreCase(v) || "si".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v)) return false;
        return v;
    }

    private static String[] splitCsv(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') inQuotes = !inQuotes;
            else if (c == sep && !inQuotes) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
