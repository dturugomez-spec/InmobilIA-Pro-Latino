package com.inmobilia.controller;

import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/cee")
public class EnergyCertController {

    private final SupabaseService supabase;
    private final NotificationService notif;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> ceeRows = supabase.selectFilter(
                "certificados_energeticos", "user_id=eq." + uid + "&order=fecha_caducidad.asc");

        // Enriquecer con propiedad
        for (Map<String, Object> c : ceeRows) {
            List<Map<String, Object>> p = supabase.selectFilter("propiedades", "id=eq." + c.get("propiedad_id"));
            if (!p.isEmpty()) c.put("propiedad", p.get(0));
        }

        LocalDate hoy = LocalDate.now();
        LocalDate en90 = hoy.plusDays(90);
        long vigentes = ceeRows.stream().filter(c -> "vigente".equals(c.get("estado"))).count();
        long porVencer = ceeRows.stream().filter(c -> {
            try {
                LocalDate fc = LocalDate.parse(String.valueOf(c.get("fecha_caducidad")));
                return "vigente".equals(c.get("estado")) && !fc.isAfter(en90);
            } catch (Exception e) { return false; }
        }).count();
        long caducados = ceeRows.stream().filter(c -> "caducado".equals(c.get("estado"))).count();

        // Propiedades sin CEE
        List<Map<String, Object>> propsConCee = ceeRows.stream()
                .map(c -> Map.of("id", c.get("propiedad_id"))).distinct().toList();
        List<Map<String, Object>> todasProps = supabase.selectFilter("propiedades", "user_id=eq." + uid);
        Set<String> idsCee = new HashSet<>();
        for (Map<String, Object> c : ceeRows) idsCee.add(String.valueOf(c.get("propiedad_id")));
        long sinCee = todasProps.stream().filter(p -> !idsCee.contains(String.valueOf(p.get("id")))).count();

        model.addAttribute("certificados", ceeRows);
        model.addAttribute("vigentes", vigentes);
        model.addAttribute("porVencer", porVencer);
        model.addAttribute("caducados", caducados);
        model.addAttribute("sinCee", sinCee);
        model.addAttribute("propiedades", todasProps);
        return "energy_certs_list";
    }

    @GetMapping("/nuevo")
    public String form(HttpSession session, Model model, @RequestParam(required = false) String propiedadId) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid));
        model.addAttribute("preseleccion", propiedadId != null ? propiedadId : "");
        return "energy_certs_form";
    }

    @PostMapping("/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("propiedad_id", form.get("propiedad_id"));
        data.put("numero_registro", form.get("numero_registro"));
        data.put("fecha_emision", form.get("fecha_emision"));

        // Calcular fecha caducidad si no se indica
        String fecCad = form.get("fecha_caducidad");
        if (fecCad == null || fecCad.isBlank()) {
            // 10 años para A-D, 5 años para E-G
            String califConsumo = form.get("calificacion_consumo");
            int anios = (califConsumo != null && (califConsumo.equals("E") || califConsumo.equals("F") || califConsumo.equals("G")))
                    ? 5 : 10;
            LocalDate emision = LocalDate.parse(form.get("fecha_emision"));
            fecCad = emision.plusYears(anios).toString();
        }
        data.put("fecha_caducidad", fecCad);

        data.put("calificacion_consumo", form.get("calificacion_consumo"));
        data.put("consumo_kwh_m2_anio", parseDouble(form.get("consumo_kwh_m2_anio")));
        data.put("calificacion_emisiones", form.get("calificacion_emisiones"));
        data.put("emisiones_kg_co2_m2_anio", parseDouble(form.get("emisiones_kg_co2_m2_anio")));
        data.put("tecnico_nombre", form.get("tecnico_nombre"));
        data.put("tecnico_titulacion", form.get("tecnico_titulacion"));
        data.put("tecnico_colegiado", form.get("tecnico_colegiado"));
        data.put("coste", parseDouble(form.get("coste")));
        data.put("medidas_mejora", form.get("medidas_mejora"));
        data.put("estado", "vigente");
        data.put("created_at", OffsetDateTime.now().toString());
        data.put("updated_at", OffsetDateTime.now().toString());
        supabase.insert("certificados_energeticos", data);

        // Actualizar propiedad
        supabase.update("propiedades", form.get("propiedad_id"), Map.of(
                "eficiencia_energetica", form.get("calificacion_consumo")
        ));

        SessionUtils.flash(session, "✅ Certificado registrado");
        return "redirect:/cee";
    }

    /** Cron diario para alertas de caducidad. */
    @Scheduled(cron = "0 0 8 * * *", zone = "Europe/Madrid")
    public void cronAlertas() {
        LocalDate hoy = LocalDate.now();
        List<Map<String, Object>> ceeRows = supabase.selectFilter(
                "certificados_energeticos", "estado=eq.vigente");

        for (Map<String, Object> c : ceeRows) {
            try {
                LocalDate fc = LocalDate.parse(String.valueOf(c.get("fecha_caducidad")));
                long dias = ChronoUnit.DAYS.between(hoy, fc);

                if (dias < 0) {
                    supabase.update("certificados_energeticos", String.valueOf(c.get("id")),
                            Map.of("estado", "caducado"));
                    notif.create(String.valueOf(c.get("user_id")),
                            "🚨 CEE caducado",
                            "El certificado nº " + c.get("numero_registro") + " ha caducado",
                            "/cee", "alta");
                } else if (dias == 90 || dias == 30 || dias == 7) {
                    notif.create(String.valueOf(c.get("user_id")),
                            "⚠️ CEE caduca en " + dias + " días",
                            "Renueva el certificado antes de " + fc,
                            "/cee", dias <= 7 ? "alta" : "media");
                }
            } catch (Exception ignored) {}
        }
    }

    private static Double parseDouble(String s) { try { return s == null || s.isBlank() ? null : Double.parseDouble(s); } catch(Exception e) { return null; } }
}
