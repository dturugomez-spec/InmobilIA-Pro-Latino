package com.inmobilia.controller;

import com.inmobilia.service.AvisoCuentaBancariaService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.CargoSeguro;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Gestión de seguros: hogar, vida, impago alquiler, comunidad, responsabilidad civil.
 * Vinculados a propiedad, contrato o inquilino. Gestión de siniestros y alertas de vencimiento.
 */
@Controller
@RequestMapping("/seguros")
@RequiredArgsConstructor
public class InsuranceController {

    private final SupabaseService supabase;

    @GetMapping
    public String list(HttpSession session, Model model,
                       @RequestParam(required = false) String tipo,
                       @RequestParam(required = false) String estado) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        StringBuilder q = new StringBuilder("user_id=eq.").append(uid);
        if (tipo != null && !tipo.isBlank()) q.append("&tipo=eq.").append(tipo);
        if (estado != null && !estado.isBlank()) q.append("&estado=eq.").append(estado);
        q.append("&order=fecha_vencimiento.asc");

        List<Map<String, Object>> polizas = supabase.selectFilter("seguros", q.toString());

        // Stats
        long activas = polizas.stream().filter(p -> "activa".equals(p.get("estado"))).count();
        double primaTotal = polizas.stream()
                .filter(p -> "activa".equals(p.get("estado")))
                .mapToDouble(p -> parseD(p.get("prima_anual")))
                .sum();
        double comisionesTotal = polizas.stream()
                .filter(p -> "activa".equals(p.get("estado")))
                .mapToDouble(p -> parseD(p.get("comision_agente")))
                .sum();

        // Próximos vencimientos (30 días)
        LocalDate ahora = LocalDate.now();
        LocalDate limite = ahora.plusDays(30);
        long porVencer = polizas.stream().filter(p -> {
            String fv = String.valueOf(p.get("fecha_vencimiento"));
            try {
                LocalDate fecha = LocalDate.parse(fv);
                return "activa".equals(p.get("estado")) && !fecha.isBefore(ahora) && !fecha.isAfter(limite);
            } catch (Exception e) { return false; }
        }).count();

        model.addAttribute("polizas", polizas);
        model.addAttribute("activas", activas);
        model.addAttribute("primaTotal", primaTotal);
        model.addAttribute("comisionesTotal", comisionesTotal);
        model.addAttribute("porVencer", porVencer);
        return "insurance_list";
    }

    @GetMapping("/nuevo")
    public String formNew(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("poliza", new LinkedHashMap<>());
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid + "&order=direccion.asc"));
        model.addAttribute("contratos", supabase.selectFilter("contratos", "user_id=eq." + uid + "&order=fecha_inicio.desc"));
        // Para el botón "usar la cuenta de la empresa" (la de Mi negocio)
        var perfil = supabase.findBy("business_profile", Map.of("org_id", uid));
        model.addAttribute("ibanEmpresa", perfil.isEmpty() ? "" : AvisoCuentaBancariaService.normalizar(perfil.get(0).get("iban")));
        return "insurance_form";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("seguros", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/seguros";
        Map<String, Object> poliza = rows.get(0);
        model.addAttribute("poliza", poliza);
        model.addAttribute("importePorCargo", CargoSeguro.importePorCargo(poliza.get("prima_anual"), String.valueOf(poliza.get("forma_pago"))));
        model.addAttribute("cuentaCargo", AvisoCuentaBancariaService.textoCuenta(poliza.get("cuenta_cargo")));
        model.addAttribute("siniestros", supabase.selectFilter("siniestros", "seguro_id=eq." + id + "&order=fecha_siniestro.desc"));
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid));
        model.addAttribute("contratos", supabase.selectFilter("contratos", "user_id=eq." + uid));
        return "insurance_detail";
    }

    @PostMapping("/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("tipo", form.get("tipo"));
        data.put("aseguradora", form.get("aseguradora"));
        data.put("numero_poliza", form.get("numero_poliza"));
        data.put("tomador", form.get("tomador"));
        data.put("asegurado", form.get("asegurado"));
        data.put("beneficiario", form.get("beneficiario"));
        data.put("capital_asegurado", parseDouble(form.get("capital_asegurado")));
        data.put("franquicia", parseDouble(form.get("franquicia")));
        data.put("coberturas", form.get("coberturas"));
        data.put("exclusiones", form.get("exclusiones"));
        data.put("prima_anual", parseDouble(form.get("prima_anual")));
        data.put("forma_pago", form.getOrDefault("forma_pago", "anual"));
        data.put("comision_agente", parseDouble(form.get("comision_agente")));
        data.put("fecha_emision", form.get("fecha_emision"));
        data.put("fecha_inicio", form.get("fecha_inicio"));
        data.put("fecha_vencimiento", form.get("fecha_vencimiento"));
        data.put("renovacion_automatica", "on".equals(form.get("renovacion_automatica")));
        data.put("propiedad_id", emptyToNull(form.get("propiedad_id")));
        data.put("contrato_id", emptyToNull(form.get("contrato_id")));
        data.put("inquilino_id", emptyToNull(form.get("inquilino_id")));
        data.put("estado", form.getOrDefault("estado", "activa"));
        data.put("notas", form.get("notas"));
        // Pago: con qué cuenta se carga, si está domiciliado y cuándo es el próximo cargo
        data.put("cuenta_cargo", normalizarCuenta(form.get("cuenta_cargo")));
        data.put("domiciliado", "on".equals(form.get("domiciliado")));
        LocalDate proximo = CargoSeguro.leerFecha(form.get("proximo_cargo"));
        if (proximo == null) {
            proximo = CargoSeguro.proximoCargo(CargoSeguro.leerFecha(form.get("fecha_inicio")),
                    form.getOrDefault("forma_pago", "anual"), LocalDate.now());
        }
        data.put("proximo_cargo", proximo == null ? null : proximo.toString());
        data.put("updated_at", OffsetDateTime.now().toString());

        String id = form.get("id");
        boolean nueva = id == null || id.isBlank();
        if (nueva) data.put("created_at", OffsetDateTime.now().toString());
        boolean ok = guardar(nueva ? null : id, data, uid);
        if (!ok) {
            // ¿Falta ejecutar la migración V84 (columnas de cargo)? Guardar al menos el resto
            CAMPOS_CARGO.forEach(data::remove);
            if (guardar(nueva ? null : id, data, uid)) {
                SessionUtils.flash(session, "Póliza guardada, pero sin los datos de cargo: falta ejecutar la migración V84 en Supabase.");
                return "redirect:/seguros";
            }
            SessionUtils.flash(session, "No se pudo guardar la póliza. Inténtalo de nuevo.");
            return "redirect:/seguros";
        }
        SessionUtils.flash(session, "✅ Póliza guardada");
        return "redirect:/seguros";
    }

    private static final List<String> CAMPOS_CARGO = List.of("cuenta_cargo", "domiciliado", "proximo_cargo");

    /** id null = nueva. Las actualizaciones van filtradas por la inmobiliaria. */
    private boolean guardar(String id, Map<String, Object> data, String uid) {
        if (id == null) {
            Map<String, Object> creada = supabase.insert("seguros", data);
            return creada != null && !creada.isEmpty();
        }
        return supabase.update("seguros", id, data, uid);
    }

    @PostMapping("/{id}/cancelar")
    public String cancel(@PathVariable String id, @RequestParam String motivo, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = Map.of(
                "estado", "cancelada",
                "fecha_cancelacion", LocalDate.now().toString(),
                "motivo_cancelacion", motivo,
                "updated_at", OffsetDateTime.now().toString()
        );
        supabase.update("seguros", id, data, uid); // solo pólizas de esta inmobiliaria
        SessionUtils.flash(session, "Póliza cancelada");
        return "redirect:/seguros";
    }

    // ===== SINIESTROS =====

    @PostMapping("/{id}/siniestros/crear")
    public String createClaim(@PathVariable String id, @RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seguro_id", id);
        data.put("user_id", uid);
        data.put("fecha_siniestro", form.get("fecha_siniestro"));
        data.put("fecha_declaracion", form.getOrDefault("fecha_declaracion", LocalDate.now().toString()));
        data.put("numero_expediente", form.get("numero_expediente"));
        data.put("tipo_dano", form.get("tipo_dano"));
        data.put("descripcion", form.get("descripcion"));
        data.put("importe_reclamado", parseDouble(form.get("importe_reclamado")));
        data.put("estado", "abierto");
        data.put("created_at", OffsetDateTime.now().toString());

        supabase.insert("siniestros", data);

        // Incrementar contador
        List<Map<String, Object>> rows = supabase.selectOwned("seguros", id, uid);
        if (!rows.isEmpty()) {
            int count = parseInt(rows.get(0).get("siniestros_count")) + 1;
            supabase.update("seguros", id, Map.of("siniestros_count", count), uid);
        }

        SessionUtils.flash(session, "Siniestro registrado");
        return "redirect:/seguros/" + id;
    }

    @PostMapping("/siniestros/{sid}/actualizar")
    public String updateClaim(@PathVariable String sid, @RequestParam Map<String, String> form, HttpSession session, @RequestParam String seguroId) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (supabase.selectOwned("siniestros", sid, uid).isEmpty()) return "redirect:/seguros"; // de otra inmobiliaria
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("estado", form.get("estado"));
        data.put("importe_indemnizado", parseDouble(form.get("importe_indemnizado")));
        data.put("perito_asignado", form.get("perito_asignado"));
        data.put("fecha_resolucion", form.get("fecha_resolucion"));
        data.put("notas", form.get("notas"));
        supabase.update("siniestros", sid, data, uid);
        SessionUtils.flash(session, "Siniestro actualizado");
        return "redirect:/seguros/" + seguroId;
    }

    /** Un IBAN se guarda sin espacios y en mayúsculas; cualquier otro texto ("Tarjeta de empresa") tal cual. */
    static String normalizarCuenta(String cuenta) {
        if (cuenta == null || cuenta.isBlank()) return null;
        String sinEspacios = AvisoCuentaBancariaService.normalizar(cuenta);
        return AvisoCuentaBancariaService.pareceIban(sinEspacios) ? sinEspacios : cuenta.trim();
    }

    private static Object emptyToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
    private static Double parseDouble(String s) { try { return s == null || s.isBlank() ? null : Double.parseDouble(s); } catch(Exception e) { return null; } }
    private static int parseInt(Object o) { try { return Integer.parseInt(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static double parseD(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
