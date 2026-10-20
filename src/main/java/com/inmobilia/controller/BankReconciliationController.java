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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/conciliacion")
@Slf4j
public class BankReconciliationController {

    private final SupabaseService supabase;

    @GetMapping
    public String dashboard(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> imports = supabase.selectFilter("bank_imports", "user_id=eq." + uid + "&order=created_at.desc&limit=10");
        List<Map<String, Object>> pendientes = supabase.selectFilter("bank_transactions", "user_id=eq." + uid + "&estado=eq.pendiente&order=fecha.desc&limit=100");
        long conciliadas = supabase.selectFilter("bank_transactions", "user_id=eq." + uid + "&estado=eq.conciliada").size();

        model.addAttribute("imports", imports);
        model.addAttribute("pendientes", pendientes);
        model.addAttribute("conciliadas", conciliadas);
        return "bank_reconciliation";
    }

    @PostMapping("/importar")
    public String importar(@RequestParam("archivo") MultipartFile file, HttpSession session) throws Exception {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        String nombre = file.getOriginalFilename();
        String formato = nombre != null && nombre.toLowerCase().endsWith(".csv") ? "csv" : "otros";

        Map<String, Object> imp = new LinkedHashMap<>();
        imp.put("user_id", uid);
        imp.put("nombre_archivo", nombre);
        imp.put("formato", formato);
        imp.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> savedImp = supabase.insert("bank_imports", imp);
        String impId = String.valueOf(savedImp.get("id"));

        // Parsear CSV (formato esperado: fecha;importe;concepto;referencia)
        int procesadas = 0, conciliadas = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            boolean primera = true;
            while ((linea = br.readLine()) != null) {
                if (primera) { primera = false; continue; } // saltar cabecera
                String[] cols = linea.split("[;,]");
                if (cols.length < 3) continue;
                try {
                    Map<String, Object> tx = new LinkedHashMap<>();
                    tx.put("import_id", impId);
                    tx.put("user_id", uid);
                    tx.put("fecha", parseFecha(cols[0]));
                    tx.put("importe", Double.parseDouble(cols[1].replace(",", ".").replace(" ", "")));
                    tx.put("concepto", cols[2].trim());
                    if (cols.length > 3) tx.put("referencia", cols[3].trim());
                    tx.put("estado", "pendiente");
                    tx.put("created_at", OffsetDateTime.now().toString());

                    // Auto-match: factura pendiente con el mismo importe y fecha cercana
                    Map<String, Object> match = autoMatch(uid, (String) tx.get("fecha"), (Double) tx.get("importe"));
                    if (match != null) {
                        tx.put("estado", "conciliada");
                        tx.put("factura_id", match.get("id"));
                        tx.put("score_match", match.get("score"));
                        marcarFacturaPagada(String.valueOf(match.get("id")), (String) tx.get("fecha"), uid);
                        conciliadas++;
                    }

                    supabase.insert("bank_transactions", tx);
                    procesadas++;
                } catch (Exception e) {
                    log.warn("Err línea: {}", e.getMessage());
                }
            }
        }

        supabase.update("bank_imports", impId, Map.of(
                "num_lineas", procesadas,
                "num_conciliadas", conciliadas,
                "num_pendientes", procesadas - conciliadas,
                "estado", "procesado"
        ));

        SessionUtils.flash(session, "✅ " + procesadas + " líneas (" + conciliadas + " auto-conciliadas)");
        return "redirect:/conciliacion";
    }

    /**
     * Factura pendiente (Emitida o Vencida) de esta inmobiliaria con el mismo importe. Si hay
     * varias, la de fecha más cercana al movimiento (como mucho 60 días); si empatan, ninguna
     * (mejor dejarlo pendiente que conciliar con la factura equivocada). Solo cobros (importe > 0).
     * Antes buscaba en una tabla "facturas" que no existe: nunca concilió nada.
     */
    private Map<String, Object> autoMatch(String userId, String fecha, double importe) {
        if (importe <= 0) return null;
        List<Map<String, Object>> candidatas = supabase.selectFilter("invoices",
                "select=id,total,fecha,status&user_id=eq." + userId + "&total=eq." + importe
                        + "&status=in.(Emitida,Vencida)&limit=20");
        Map<String, Object> elegida = elegirFactura(candidatas, fecha);
        if (elegida == null) return null;
        Map<String, Object> r = new LinkedHashMap<>(elegida);
        r.put("score", candidatas.size() == 1 ? 95.0 : 85.0);
        return r;
    }

    /** De las candidatas, la de fecha más cercana (≤ 60 días); empate o ninguna cerca → null. */
    static Map<String, Object> elegirFactura(List<Map<String, Object>> candidatas, String fechaMovimiento) {
        if (candidatas == null || candidatas.isEmpty()) return null;
        java.time.LocalDate mov = com.inmobilia.util.CargoSeguro.leerFecha(fechaMovimiento);
        if (mov == null) return candidatas.size() == 1 ? candidatas.get(0) : null;
        Map<String, Object> mejor = null;
        long mejorDias = Long.MAX_VALUE;
        boolean empate = false;
        for (Map<String, Object> f : candidatas) {
            java.time.LocalDate fecha = com.inmobilia.util.CargoSeguro.leerFecha(f.get("fecha"));
            if (fecha == null) continue;
            long dias = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(fecha, mov));
            if (dias > 60) continue;
            if (dias < mejorDias) { mejor = f; mejorDias = dias; empate = false; }
            else if (dias == mejorDias) empate = true;
        }
        return empate ? null : mejor;
    }

    private void marcarFacturaPagada(String facturaId, String fechaPago, String uid) {
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("status", "Pagada");
        upd.put("fecha_pago", fechaPago != null ? fechaPago : java.time.LocalDate.now().toString());
        upd.put("metodo_pago", "Transferencia");
        supabase.update("invoices", facturaId, upd, uid);
    }

    @PostMapping("/conciliar/{id}")
    @ResponseBody
    public Map<String, Object> conciliarManual(@PathVariable String id, @RequestParam String facturaId, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        // El movimiento y la factura tienen que ser de esta inmobiliaria
        List<Map<String, Object>> tx = supabase.selectOwned("bank_transactions", id, uid);
        if (tx.isEmpty() || supabase.selectOwned("invoices", facturaId, uid).isEmpty()) return Map.of("ok", false);
        supabase.update("bank_transactions", id, Map.of(
                "estado", "conciliada",
                "factura_id", facturaId,
                "score_match", 100
        ), uid);
        marcarFacturaPagada(facturaId, String.valueOf(tx.get(0).get("fecha")), uid);
        return Map.of("ok", true);
    }

    private String parseFecha(String s) {
        String clean = s.replace(" ", "").replaceAll("\"", "");
        try {
            if (clean.matches("\\d{2}/\\d{2}/\\d{4}")) {
                return LocalDate.parse(clean, DateTimeFormatter.ofPattern("dd/MM/yyyy")).toString();
            }
            if (clean.matches("\\d{4}-\\d{2}-\\d{2}")) return clean;
            if (clean.matches("\\d{2}-\\d{2}-\\d{4}")) {
                return LocalDate.parse(clean, DateTimeFormatter.ofPattern("dd-MM-yyyy")).toString();
            }
        } catch (Exception e) { }
        return LocalDate.now().toString();
    }
}
