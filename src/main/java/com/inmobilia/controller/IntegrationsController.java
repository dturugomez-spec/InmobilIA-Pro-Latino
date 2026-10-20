package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Integraciones con servicios externos:
 * - Banco: importar movimientos CSV y auto-conciliar con cobros esperados
 * - Portales: generar feed XML con propiedades publicadas
 * - WhatsApp Business: plantillas de mensajes con enlaces wa.me
 */
@Controller
@RequestMapping("/integrations")
@RequiredArgsConstructor
@Slf4j
public class IntegrationsController {

    private final SupabaseService supabase;
    private final PermissionService permissions;

    // ─── HUB ───
    @GetMapping
    public String index(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Integraciones");
        return "integrations/index";
    }

    // ─── BANCO ───
    @GetMapping("/bank")
    public String bank(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/integrations";
        model.addAttribute("page", "Importar movimientos bancarios");
        return "integrations/bank";
    }

    @PostMapping("/bank/import")
    public String bankImport(@RequestParam("file") MultipartFile file,
                             @RequestParam(defaultValue = ";") String separator,
                             @RequestParam(defaultValue = "0") int colDate,
                             @RequestParam(defaultValue = "1") int colDesc,
                             @RequestParam(defaultValue = "2") int colAmount,
                             @RequestParam(defaultValue = "1") int skipRows,
                             HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/integrations";

        if (file.isEmpty()) {
            SessionUtils.flash(session, "ERROR: archivo vacío");
            return "redirect:/integrations/bank";
        }

        String uid = SessionUtils.uid(session);
        var inquilinos = supabase.query("inquilinos", uid);
        char sep = separator.isEmpty() ? ';' : separator.charAt(0);

        int imported = 0, skipped = 0, conciliated = 0;
        StringBuilder errors = new StringBuilder();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line; int rowNum = 0;
            while ((line = br.readLine()) != null) {
                rowNum++;
                if (rowNum <= skipRows) continue;
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line, sep);
                if (cols.length <= Math.max(colDate, Math.max(colDesc, colAmount))) { skipped++; continue; }

                String dateStr = cols[colDate].trim();
                String desc    = cols[colDesc].trim().replaceAll("\"", "");
                String amtStr  = cols[colAmount].trim().replace(".", "").replace(",", ".").replaceAll("[^\\d.\\-]", "");

                LocalDate d = parseAnyDate(dateStr);
                if (d == null) { skipped++; continue; }
                double amt;
                try { amt = Double.parseDouble(amtStr); } catch (Exception e) { skipped++; continue; }

                String type = amt > 0 ? "Ingreso" : "Gasto";
                String absAmt = String.valueOf(Math.abs(amt));
                String category = "Importado banco";
                String propertyName = null;

                // Auto-conciliación: si el importe coincide con la renta de un inquilino
                if (amt > 0) {
                    for (var inq : inquilinos) {
                        try {
                            double rent = Double.parseDouble(String.valueOf(inq.getOrDefault("rent_amount", "0")));
                            if (rent > 0 && Math.abs(amt - rent) < rent * 0.05) {  // 5% tolerancia
                                propertyName = String.valueOf(inq.getOrDefault("property_name", ""));
                                category = "Alquiler";
                                desc = "Cobro alquiler · " + inq.get("name") + " (auto-conciliado)";
                                conciliated++;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                Map<String, Object> tx = new LinkedHashMap<>();
                tx.put("user_id", uid);
                tx.put("type", type);
                tx.put("category", category);
                tx.put("amount", Math.abs(amt));
                tx.put("date", d.toString());
                tx.put("description", desc.length() > 200 ? desc.substring(0, 200) : desc);
                if (propertyName != null) tx.put("property_name", propertyName);
                tx.put("created_at", OffsetDateTime.now().toString());

                Map<String, Object> res = supabase.insert("transacciones", tx);
                if (res != null && !res.isEmpty()) imported++;
                else { skipped++; if (errors.length() < 200) errors.append(rowNum).append(", "); }
            }
        } catch (Exception e) {
            log.error("bank import error: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR procesando archivo: " + e.getMessage());
            return "redirect:/integrations/bank";
        }

        SessionUtils.flash(session, String.format(
                "Importación completada · %d transacciones · %d auto-conciliadas con alquileres · %d filas omitidas",
                imported, conciliated, skipped));
        return "redirect:/crud/transacciones";
    }

    // ─── PORTALES ───
    @GetMapping("/portals")
    public String portals(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "propiedades")) return "redirect:/integrations";

        String uid = SessionUtils.uid(session);
        var publicadas = supabase.query("propiedades", uid).stream()
                .filter(p -> Boolean.TRUE.equals(p.get("publicada")) || "true".equalsIgnoreCase(String.valueOf(p.get("publicada"))))
                .toList();

        model.addAttribute("page", "Portales inmobiliarios");
        model.addAttribute("publicadas", publicadas);
        return "integrations/portals";
    }

    @GetMapping(value = "/portals/feed.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public void portalsFeed(HttpSession session, HttpServletResponse response) throws Exception {
        if (!SessionUtils.isLoggedIn(session)) { response.sendError(401); return; }
        String uid = SessionUtils.uid(session);
        var publicadas = supabase.query("propiedades", uid).stream()
                .filter(p -> Boolean.TRUE.equals(p.get("publicada")) || "true".equalsIgnoreCase(String.valueOf(p.get("publicada"))))
                .toList();

        response.setContentType("application/xml; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=feed-propiedades.xml");
        try (PrintWriter w = response.getWriter()) {
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            w.println("<properties generated=\"" + LocalDate.now() + "\" total=\"" + publicadas.size() + "\">");
            for (var p : publicadas) {
                w.println("  <property>");
                w.println("    <reference>" + esc(p.get("reference")) + "</reference>");
                w.println("    <name>" + esc(p.get("name")) + "</name>");
                w.println("    <type>" + esc(p.get("type")) + "</type>");
                w.println("    <status>" + esc(p.get("status")) + "</status>");
                w.println("    <address>" + esc(p.get("address")) + "</address>");
                w.println("    <city>" + esc(p.get("city")) + "</city>");
                w.println("    <postal_code>" + esc(p.get("postal_code")) + "</postal_code>");
                w.println("    <area_m2>" + esc(p.get("area_m2")) + "</area_m2>");
                w.println("    <bedrooms>" + esc(p.get("bedrooms")) + "</bedrooms>");
                w.println("    <bathrooms>" + esc(p.get("bathrooms")) + "</bathrooms>");
                w.println("    <year_built>" + esc(p.get("year_built")) + "</year_built>");
                w.println("    <price>" + esc(p.get("current_value")) + "</price>");
                w.println("    <rental_income>" + esc(p.get("rental_income")) + "</rental_income>");
                w.println("    <photo>" + esc(p.get("featured_photo")) + "</photo>");
                w.println("    <description><![CDATA[" + (p.get("notes") != null ? p.get("notes") : "") + "]]></description>");
                w.println("  </property>");
            }
            w.println("</properties>");
        }
    }

    // ─── WHATSAPP ───
    @GetMapping("/whatsapp")
    public String whatsapp(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        var inquilinos = supabase.query("inquilinos", uid).stream()
                .filter(i -> {
                    String p = String.valueOf(i.getOrDefault("phone", ""));
                    return p != null && !p.isBlank() && !"null".equals(p);
                })
                .toList();
        model.addAttribute("page", "WhatsApp Business");
        model.addAttribute("inquilinos", inquilinos);
        return "integrations/whatsapp";
    }

    // ─── HELPERS ───
    private static String esc(Object o) {
        if (o == null) return "";
        return String.valueOf(o)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
    private static String s(Object o) {
        if (o == null) return "";
        String v = String.valueOf(o);
        if (v.contains(";") || v.contains("\"")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
    private static double parseDouble(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
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
    private static LocalDate parseAnyDate(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.replaceAll("\"", "").trim();
        String[] formats = { "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy/MM/dd" };
        for (String f : formats) {
            try { return LocalDate.parse(s, DateTimeFormatter.ofPattern(f)); } catch (Exception ignored) {}
        }
        return null;
    }
}
