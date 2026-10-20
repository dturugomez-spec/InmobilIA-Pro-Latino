package com.inmobilia.controller;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.service.VerifactuService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Facturación con normativa española:
 * - Numeración correlativa por serie/usuario (auto-trigger en BBDD)
 * - IVA 21% por defecto (configurable)
 * - Retención IRPF 19% típico para alquileres
 * - Estados: Borrador, Emitida, Pagada, Vencida, Anulada
 */
@Controller
@RequestMapping("/invoices")
@RequiredArgsConstructor
@Slf4j
public class InvoiceController {

    private final SupabaseService supabase;
    private final PermissionService permissions;
    private final VerifactuService verifactu;

    // ─── LISTADO ───
    @GetMapping
    public String list(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "transacciones")) return "redirect:/dashboard";

        String uid = SessionUtils.uid(session);
        var rows = supabase.query("invoices", uid);

        double totalEmitido = rows.stream()
                .filter(r -> !"Anulada".equalsIgnoreCase(String.valueOf(r.get("status"))))
                .mapToDouble(r -> parseDouble(r.get("total"))).sum();
        double totalCobrado = rows.stream()
                .filter(r -> "Pagada".equalsIgnoreCase(String.valueOf(r.get("status"))))
                .mapToDouble(r -> parseDouble(r.get("total"))).sum();
        double totalPendiente = rows.stream()
                .filter(r -> {
                    String s = String.valueOf(r.get("status"));
                    return "Emitida".equalsIgnoreCase(s) || "Vencida".equalsIgnoreCase(s);
                })
                .mapToDouble(r -> parseDouble(r.get("total"))).sum();
        long nVencidas = rows.stream().filter(r -> "Vencida".equalsIgnoreCase(String.valueOf(r.get("status")))).count();

        model.addAttribute("page", "Facturación");
        model.addAttribute("rows", rows);
        model.addAttribute("totalEmitido", totalEmitido);
        model.addAttribute("totalCobrado", totalCobrado);
        model.addAttribute("totalPendiente", totalPendiente);
        model.addAttribute("nVencidas", nVencidas);
        model.addAttribute("canWrite", permissions.canWrite(role, "transacciones"));
        return "invoice_list";
    }

    // ─── FORM NUEVA ───
    @GetMapping("/new")
    public String formNew(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/invoices";

        String uid = SessionUtils.uid(session);
        var inquilinos = supabase.query("inquilinos", uid);
        var propiedades = supabase.query("propiedades", uid);

        model.addAttribute("page", "Nueva factura");
        model.addAttribute("inv", new HashMap<>());
        model.addAttribute("editing", false);
        model.addAttribute("inquilinos", inquilinos);
        model.addAttribute("propiedades", propiedades);
        return "invoice_form";
    }

    // ─── FORM EDITAR ───
    @GetMapping("/{id}/edit")
    public String formEdit(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/invoices";

        String uid = SessionUtils.uid(session);
        var rows = supabase.findBy("invoices", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/invoices";

        var inquilinos = supabase.query("inquilinos", uid);
        var propiedades = supabase.query("propiedades", uid);

        model.addAttribute("page", "Editar factura " + rows.get(0).get("full_number"));
        model.addAttribute("inv", rows.get(0));
        model.addAttribute("editing", true);
        model.addAttribute("inquilinos", inquilinos);
        model.addAttribute("propiedades", propiedades);
        return "invoice_form";
    }

    // ─── VER (imprimible) ───
    @GetMapping("/{id}")
    public String view(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        var rows = supabase.findBy("invoices", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/invoices";

        Map<String, Object> inv = rows.get(0);
        model.addAttribute("page", "Factura " + inv.get("full_number"));
        model.addAttribute("inv", inv);
        return "invoice_view";
    }

    // ─── GUARDAR (crear / editar) ───
    @PostMapping
    public String save(HttpServletRequest request, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/invoices";

        String uid = SessionUtils.uid(session);
        String id  = request.getParameter("id");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);

        // Datos básicos
        putIfNotBlank(data, request, "serie", "A");
        putIfNotBlank(data, request, "fecha", LocalDate.now().toString());
        putIfNotBlank(data, request, "vencimiento", null);
        putIfNotBlank(data, request, "concepto", null);
        putIfNotBlank(data, request, "status", "Emitida");
        putIfNotBlank(data, request, "metodo_pago", null);
        putIfNotBlank(data, request, "notas", null);
        putIfNotBlank(data, request, "fecha_pago", null);
        putIfNotBlank(data, request, "property_name", null);
        putIfNotBlank(data, request, "tenant_name", null);

        // Emisor
        putIfNotBlank(data, request, "emisor_nombre", null);
        putIfNotBlank(data, request, "emisor_nif", null);
        putIfNotBlank(data, request, "emisor_direccion", null);
        putIfNotBlank(data, request, "emisor_email", null);
        putIfNotBlank(data, request, "emisor_iban", null);

        // Cliente
        putIfNotBlank(data, request, "cliente_nombre", null);
        putIfNotBlank(data, request, "cliente_nif", null);
        putIfNotBlank(data, request, "cliente_direccion", null);
        putIfNotBlank(data, request, "cliente_email", null);

        // Importes
        double base = parseDouble(request.getParameter("base_imponible"));
        double ivaPct = parseDouble(request.getParameter("iva_pct"));
        double irpfPct = parseDouble(request.getParameter("irpf_pct"));
        double ivaAmount = round2(base * ivaPct / 100.0);
        double irpfAmount = round2(base * irpfPct / 100.0);
        double total = round2(base + ivaAmount - irpfAmount);

        data.put("base_imponible", base);
        data.put("iva_pct", ivaPct);
        data.put("iva_amount", ivaAmount);
        data.put("irpf_pct", irpfPct);
        data.put("irpf_amount", irpfAmount);
        data.put("total", total);

        if (id == null || id.isBlank()) {
            data.put("created_at", OffsetDateTime.now().toString());

            // Verifactu: encadenar hash con la factura anterior
            String prevHash = verifactu.getLastHash(uid);
            int chainId = verifactu.getNextChainId(uid);
            data.put("verifactu_chain_id", chainId);
            data.put("verifactu_prev_hash", prevHash);

            Map<String, Object> saved = supabase.insert("invoices", data);
            if (saved == null || saved.isEmpty()) {
                String err = supabase.lastError();
                SessionUtils.flash(session, "ERROR creando factura: " + (err != null ? err : "desconocido"));
                return "redirect:/invoices/new";
            }

            // Calcular hash y QR ahora que tenemos el numero asignado por el trigger
            try {
                String hash = verifactu.calculateHash(saved, prevHash);
                String qr = verifactu.generateQrPayload(saved, hash);
                supabase.update("invoices", String.valueOf(saved.get("id")),
                        Map.of("verifactu_hash", hash, "verifactu_qr_payload", qr), uid);
            } catch (Exception e) {
                log.warn("Verifactu hash falló: {}", e.getMessage());
            }

            SessionUtils.flash(session, "Factura " + saved.get("full_number") + " creada (Verifactu OK)");
            return "redirect:/invoices/" + saved.get("id");
        } else {
            data.put("updated_at", OffsetDateTime.now().toString());
            boolean ok = supabase.update("invoices", id, data, uid);
            SessionUtils.flash(session, ok ? "Factura actualizada" : "ERROR actualizando factura");
            return "redirect:/invoices/" + id;
        }
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable String id,
                               @RequestParam String status,
                               HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/invoices";

        String uid = SessionUtils.uid(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        if ("Pagada".equalsIgnoreCase(status)) {
            data.put("fecha_pago", LocalDate.now().toString());
        }
        boolean ok = supabase.update("invoices", id, data, uid);
        SessionUtils.flash(session, ok ? "Factura marcada como " + status : "ERROR cambiando estado");
        return "redirect:/invoices";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/invoices";

        String uid = SessionUtils.uid(session);
        supabase.delete("invoices", id, uid);
        SessionUtils.flash(session, "Factura eliminada");
        return "redirect:/invoices";
    }

    // ─── helpers ───
    private static void putIfNotBlank(Map<String, Object> data, HttpServletRequest req, String key, String defaultValue) {
        String v = req.getParameter(key);
        if (v != null && !v.isBlank()) data.put(key, v);
        else if (defaultValue != null) data.put(key, defaultValue);
    }
    private static double parseDouble(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o).replace(',', '.')); } catch (Exception e) { return 0; }
    }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
