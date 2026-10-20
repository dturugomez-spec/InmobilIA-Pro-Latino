package com.inmobilia.controller;

import com.inmobilia.service.EmailService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/cobroking")
public class CobrokingController {

    private final SupabaseService supabase;
    private final EmailService emailService;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping
    public String dashboard(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Mi red
        List<Map<String, Object>> miRed = supabase.selectFilter(
                "cobroking_red", "user_id=eq." + uid + "&estado=eq.activa");

        // Propiedades que comparto
        List<Map<String, Object>> compartidasPorMi = supabase.selectFilter(
                "cobroking_propiedades", "user_propietario_id=eq." + uid + "&activa=eq.true");

        // Propiedades compartidas conmigo (de mi red)
        List<Map<String, Object>> compartidasConmigo = new ArrayList<>();
        for (Map<String, Object> r : miRed) {
            List<Map<String, Object>> props = supabase.selectFilter(
                    "cobroking_propiedades",
                    "user_propietario_id=eq." + r.get("agencia_socia_id") + "&activa=eq.true");
            // Enriquecer con datos propiedad
            for (Map<String, Object> p : props) {
                List<Map<String, Object>> propData = supabase.selectFilter("propiedades", "id=eq." + p.get("propiedad_id"));
                if (!propData.isEmpty()) p.put("propiedad", propData.get(0));
            }
            compartidasConmigo.addAll(props);
        }

        // Operaciones
        List<Map<String, Object>> opsCerradas = supabase.selectFilter(
                "cobroking_operaciones",
                "user_captador_id=eq." + uid + "&estado=eq.cerrada");
        List<Map<String, Object>> opsOferente = supabase.selectFilter(
                "cobroking_operaciones",
                "user_oferente_id=eq." + uid + "&estado=eq.cerrada");

        double ingresoCaptador = opsCerradas.stream().mapToDouble(o -> nd(o.get("comision_captador"))).sum();
        double ingresoOferente = opsOferente.stream().mapToDouble(o -> nd(o.get("comision_oferente"))).sum();

        model.addAttribute("miRed", miRed);
        model.addAttribute("compartidasPorMi", compartidasPorMi);
        model.addAttribute("compartidasConmigo", compartidasConmigo);
        model.addAttribute("ingresoTotal", ingresoCaptador + ingresoOferente);
        model.addAttribute("ingresoCaptador", ingresoCaptador);
        model.addAttribute("ingresoOferente", ingresoOferente);
        model.addAttribute("operaciones", opsCerradas.size() + opsOferente.size());
        return "cobroking_dashboard";
    }

    @PostMapping("/invitar")
    public String invitar(@RequestParam String email, @RequestParam(required = false) String nombreRed,
                          @RequestParam(required = false) Double comisionDefault,
                          HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("invitacion_email", email);
        data.put("invitacion_token", token);
        data.put("nombre_red", nombreRed != null ? nombreRed : "Red colaborativa");
        data.put("comision_default_pct", comisionDefault != null ? comisionDefault : 50);
        data.put("estado", "pendiente");
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("cobroking_red", data);

        String url = BASE_URL + "/cobroking/aceptar/" + token;
        String html = String.format(
            "<h2>🤝 Invitación a colaborar</h2>" +
            "<p>Una agencia te invita a unirte a su red de co-broking en InmobilIA Pro.</p>" +
            "<p><strong>Red:</strong> %s<br><strong>Comisión por defecto:</strong> %.0f%% / %.0f%%</p>" +
            "<p><a href='%s' style='display:inline-block;padding:10px 20px;background:#10b981;color:white;border-radius:8px;text-decoration:none'>Aceptar invitación</a></p>",
            nombreRed != null ? nombreRed : "Red colaborativa",
            comisionDefault != null ? comisionDefault : 50,
            100 - (comisionDefault != null ? comisionDefault : 50),
            url);
        try { emailService.send(email, "🤝 Invitación a red de co-broking", html); } catch(Exception ignored) {}

        SessionUtils.flash(session, "📧 Invitación enviada a " + email);
        return "redirect:/cobroking";
    }

    @GetMapping("/aceptar/{token}")
    public String aceptarInvitacion(@PathVariable String token, HttpSession session, Model model) {
        String realUid = SessionUtils.realUid(session);
        if (realUid == null) {
            model.addAttribute("error", "Inicia sesión para aceptar");
            return "redirect:/login?next=/cobroking/aceptar/" + token;
        }

        List<Map<String, Object>> rows = supabase.selectFilter(
                "cobroking_red", "invitacion_token=eq." + token + "&estado=eq.pendiente");
        if (rows.isEmpty()) {
            model.addAttribute("error", "Invitación no válida o ya aceptada");
            return "redirect:/cobroking";
        }

        Map<String, Object> inv = rows.get(0);
        // Marcar como activa y vincular a la agencia que la acepta
        supabase.update("cobroking_red", String.valueOf(inv.get("id")), Map.of(
                "agencia_socia_id", realUid,
                "estado", "activa",
                "aceptada_at", OffsetDateTime.now().toString()
        ));
        // Crear relación inversa
        Map<String, Object> inversa = new LinkedHashMap<>();
        inversa.put("user_id", realUid);
        inversa.put("agencia_socia_id", inv.get("user_id"));
        inversa.put("nombre_red", inv.get("nombre_red"));
        inversa.put("comision_default_pct", 100 - nd(inv.get("comision_default_pct")));
        inversa.put("estado", "activa");
        inversa.put("aceptada_at", OffsetDateTime.now().toString());
        inversa.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("cobroking_red", inversa);

        SessionUtils.flash(session, "✅ Invitación aceptada. Ya formas parte de la red.");
        return "redirect:/cobroking";
    }

    @PostMapping("/compartir")
    public String compartir(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("propiedad_id", form.get("propiedad_id"));
        data.put("user_propietario_id", uid);
        if (notBlank(form.get("user_compartido_id"))) data.put("user_compartido_id", form.get("user_compartido_id"));
        data.put("comision_oferente_pct", parseDouble(form.get("comision_oferente_pct"), 50));
        data.put("comision_captador_pct", parseDouble(form.get("comision_captador_pct"), 50));
        data.put("condiciones", form.get("condiciones"));
        data.put("activa", true);
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("cobroking_propiedades", data);

        SessionUtils.flash(session, "✅ Propiedad compartida en la red");
        return "redirect:/cobroking";
    }

    @PostMapping("/operacion/registrar")
    public String registrarOperacion(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        double importe = parseDouble(form.get("importe_operacion"), 0);
        double comisionTotal = parseDouble(form.get("comision_total"), importe * 0.03);
        double pctCaptador = parseDouble(form.get("pct_captador"), 50);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("propiedad_id", form.get("propiedad_id"));
        data.put("user_captador_id", form.get("user_captador_id"));
        data.put("user_oferente_id", form.get("user_oferente_id"));
        data.put("importe_operacion", importe);
        data.put("comision_total", comisionTotal);
        data.put("comision_captador", comisionTotal * pctCaptador / 100);
        data.put("comision_oferente", comisionTotal * (100 - pctCaptador) / 100);
        data.put("estado", "cerrada");
        data.put("fecha_cierre", form.get("fecha_cierre"));
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("cobroking_operaciones", data);

        SessionUtils.flash(session, "✅ Operación registrada");
        return "redirect:/cobroking";
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static double parseDouble(String s, double d) { try { return Double.parseDouble(s); } catch(Exception e) { return d; } }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
