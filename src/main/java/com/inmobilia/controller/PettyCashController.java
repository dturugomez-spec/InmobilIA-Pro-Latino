package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/caja")
public class PettyCashController {

    private final SupabaseService supabase;

    @GetMapping
    public String dashboard(HttpSession session, Model model,
                            @RequestParam(required = false) String cuenta,
                            @RequestParam(required = false) String categoria) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Crear cuenta caja por defecto si no hay
        List<Map<String, Object>> cuentas = supabase.selectFilter(
                "cuentas_caja", "user_id=eq." + uid + "&activa=eq.true&order=created_at.asc");
        if (cuentas.isEmpty()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("user_id", uid);
            def.put("nombre", "Caja efectivo");
            def.put("tipo", "caja_efectivo");
            def.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("cuentas_caja", def);
            cuentas = supabase.selectFilter("cuentas_caja", "user_id=eq." + uid);
        }

        StringBuilder q = new StringBuilder("user_id=eq.").append(uid).append("&order=fecha.desc,created_at.desc&limit=200");
        if (cuenta != null && !cuenta.isBlank()) q.append("&cuenta_id=eq.").append(cuenta);
        if (categoria != null && !categoria.isBlank()) q.append("&categoria=eq.").append(categoria);
        List<Map<String, Object>> movimientos = supabase.selectFilter("movimientos_caja", q.toString());

        // Cálculos
        double saldoTotal = cuentas.stream().mapToDouble(c -> nd(c.get("saldo_actual"))).sum();

        // Mes actual
        LocalDate hoy = LocalDate.now();
        String mesActual = hoy.toString().substring(0, 7);
        double ingresosMes = 0, gastosMes = 0;
        Map<String, Double> porCategoria = new LinkedHashMap<>();
        for (Map<String, Object> m : movimientos) {
            String fecha = String.valueOf(m.get("fecha"));
            if (fecha == null || !fecha.startsWith(mesActual)) continue;
            double importe = nd(m.get("importe"));
            String tipo = String.valueOf(m.get("tipo"));
            if ("ingreso".equals(tipo)) ingresosMes += importe;
            else if ("gasto".equals(tipo)) {
                gastosMes += importe;
                String cat = String.valueOf(m.getOrDefault("categoria", "otros"));
                porCategoria.merge(cat, importe, Double::sum);
            }
        }

        model.addAttribute("cuentas", cuentas);
        model.addAttribute("movimientos", movimientos);
        model.addAttribute("saldoTotal", saldoTotal);
        model.addAttribute("ingresosMes", ingresosMes);
        model.addAttribute("gastosMes", gastosMes);
        model.addAttribute("balanceMes", ingresosMes - gastosMes);
        model.addAttribute("porCategoria", porCategoria);
        return "petty_cash_dashboard";
    }

    @PostMapping("/cuenta/crear")
    public String crearCuenta(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        double saldoIni = parseD(form.get("saldo_inicial"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", form.get("nombre"));
        data.put("tipo", form.getOrDefault("tipo", "caja_efectivo"));
        data.put("iban", form.get("iban"));
        data.put("saldo_inicial", saldoIni);
        data.put("saldo_actual", saldoIni);
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("cuentas_caja", data);
        return "redirect:/caja";
    }

    @PostMapping("/movimiento/crear")
    public String crearMovimiento(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        String realUid = SessionUtils.realUid(session);
        if (uid == null) return "redirect:/login";

        String tipo = form.get("tipo");
        double importe = parseD(form.get("importe"));
        String cuentaId = form.get("cuenta_id");
        // La cuenta de caja tiene que ser de esta inmobiliaria (antes se podía mover el saldo de otra)
        List<Map<String, Object>> cuentas = supabase.selectOwned("cuentas_caja", cuentaId, uid);
        if (cuentas.isEmpty()) {
            SessionUtils.flash(session, "Elige una cuenta de caja válida.");
            return "redirect:/caja";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("cuenta_id", cuentaId);
        data.put("tipo", tipo);
        data.put("fecha", form.getOrDefault("fecha", LocalDate.now().toString()));
        data.put("concepto", form.get("concepto"));
        data.put("categoria", form.get("categoria"));
        data.put("importe", importe);
        data.put("proveedor", form.get("proveedor"));
        data.put("nif_proveedor", form.get("nif_proveedor"));
        data.put("numero_factura", form.get("numero_factura"));
        if (form.get("iva_pct") != null && !form.get("iva_pct").isBlank()) {
            double iva = parseD(form.get("iva_pct"));
            data.put("iva_pct", iva);
            data.put("iva_importe", importe * iva / (100 + iva));
        }
        data.put("deducible_irpf", "on".equals(form.getOrDefault("deducible_irpf", "on")));
        data.put("notas", form.get("notas"));
        data.put("creado_por", realUid);
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("movimientos_caja", data);

        // Actualizar saldo
        double saldoActual = nd(cuentas.get(0).get("saldo_actual"));
        double nuevo = "ingreso".equals(tipo) ? saldoActual + importe : saldoActual - importe;
        supabase.update("cuentas_caja", cuentaId, Map.of("saldo_actual", nuevo), uid);

        return "redirect:/caja";
    }

    private static double parseD(String s) { try { return Double.parseDouble(s); } catch(Exception e) { return 0; } }
    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
