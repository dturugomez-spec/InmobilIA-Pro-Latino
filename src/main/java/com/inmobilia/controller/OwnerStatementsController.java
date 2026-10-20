package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

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
@RequestMapping("/extractos")
public class OwnerStatementsController {

    private final SupabaseService supabase;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> extractos = supabase.selectFilter(
                "extractos_propietario", "user_id=eq." + uid + "&order=periodo_inicio.desc");

        supabase.enriquecer(extractos, "propietario_id", "propietarios", "propietario", uid);

        double totalPendiente = extractos.stream().filter(e -> "pendiente".equals(e.get("estado")))
                .mapToDouble(e -> nd(e.get("importe_liquidar"))).sum();

        model.addAttribute("extractos", extractos);
        model.addAttribute("totalPendiente", totalPendiente);
        model.addAttribute("propietarios", supabase.selectFilter("propietarios", "user_id=eq." + uid));
        return "owner_statements";
    }

    @PostMapping("/generar")
    public String generar(@RequestParam String propietarioId,
                          @RequestParam String periodoInicio,
                          @RequestParam String periodoFin,
                          @RequestParam(defaultValue = "10") double comisionPct,
                          @RequestParam(defaultValue = "19") double retencionIrpfPct,
                          HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Propiedades del propietario
        List<Map<String, Object>> props = supabase.selectFilter("propiedades", "propietario_id=eq." + propietarioId);
        List<String> propIds = props.stream().map(p -> String.valueOf(p.get("id"))).toList();

        // Sumar rentas de los contratos
        double totalRentas = 0, totalGastos = 0;
        for (String propId : propIds) {
            List<Map<String, Object>> contratos = supabase.selectFilter("contratos", "propiedad_id=eq." + propId + "&estado=eq.activo");
            for (Map<String, Object> c : contratos) {
                totalRentas += nd(c.get("renta_mensual"));
            }
        }

        // Gastos del periodo
        List<Map<String, Object>> gastos = supabase.selectFilter(
                "movimientos_caja",
                "user_id=eq." + uid + "&tipo=eq.gasto&fecha=gte." + periodoInicio + "&fecha=lte." + periodoFin);
        totalGastos = gastos.stream().mapToDouble(g -> nd(g.get("importe"))).sum();

        double comision = totalRentas * comisionPct / 100;
        double iva = comision * com.inmobilia.util.PaisActual.get().tipoImpuesto() / 100.0; // impuesto del país
        double retencion = totalRentas * retencionIrpfPct / 100;
        double liquidar = totalRentas - comision - iva - retencion - totalGastos;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("propietario_id", propietarioId);
        data.put("periodo_inicio", periodoInicio);
        data.put("periodo_fin", periodoFin);
        data.put("propiedades_ids", propIds);
        data.put("total_rentas", totalRentas);
        data.put("total_comision_inmobiliaria", comision);
        data.put("total_iva", iva);
        data.put("total_irpf_retenido", retencion);
        data.put("total_otros_gastos", totalGastos);
        data.put("total_gastos", comision + iva + retencion + totalGastos);
        data.put("total_ingresos", totalRentas);
        data.put("importe_liquidar", liquidar);
        data.put("estado", "pendiente");
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("extractos_propietario", data);

        SessionUtils.flash(session, "✅ Extracto generado: " + liquidar + (" " + PaisActual.moneda() + " a liquidar"));
        return "redirect:/extractos";
    }

    @PostMapping("/{id}/marcar-pagado")
    public String marcarPagado(@PathVariable String id, @RequestParam(required = false) String referencia, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("estado", "pagado");
        upd.put("fecha_pago", LocalDate.now().toString());
        if (referencia != null) upd.put("referencia_transferencia", referencia);
        supabase.update("extractos_propietario", id, upd, uid);
        return "redirect:/extractos";
    }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
