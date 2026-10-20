package com.inmobilia.controller;

import com.inmobilia.service.AvisoCuentaBancariaService;
import com.inmobilia.service.SepaService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/sepa")
public class SepaController {

    private final SupabaseService supabase;
    private final SepaService sepaService;
    private final AvisoCuentaBancariaService avisoCuenta;

    @GetMapping
    public String dashboard(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Acreedor (config)
        List<Map<String, Object>> credRows = supabase.selectFilter("sepa_creditor", "user_id=eq." + uid);
        Map<String, Object> cred = credRows.isEmpty() ? new LinkedHashMap<>() : credRows.get(0);

        List<Map<String, Object>> mandatos = supabase.selectFilter("sepa_mandatos", "user_id=eq." + uid + "&order=fecha_firma.desc");
        List<Map<String, Object>> remesas = supabase.selectFilter("sepa_remesas", "user_id=eq." + uid + "&order=fecha_cobro.desc&limit=20");

        long activos = mandatos.stream().filter(m -> "activo".equals(m.get("estado"))).count();
        double importeMensual = mandatos.stream().filter(m -> "activo".equals(m.get("estado")) && "mensual".equals(m.get("frecuencia")))
                .mapToDouble(m -> nd(m.get("importe"))).sum();

        model.addAttribute("cred", cred);
        model.addAttribute("mandatos", mandatos);
        model.addAttribute("remesas", remesas);
        model.addAttribute("activos", activos);
        model.addAttribute("importeMensual", importeMensual);
        return "sepa_dashboard";
    }

    /** La cuenta del acreedor es donde se ingresan los cobros: se valida y cada cambio avisa al titular. */
    @PostMapping("/acreedor/guardar")
    public String saveAcreedor(@RequestParam Map<String, String> form, HttpServletRequest req, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        String iban = AvisoCuentaBancariaService.normalizar(form.get("iban"));
        if (!sepaService.validarIBAN(iban)) {
            SessionUtils.flash(session, "❌ IBAN no válido: no se ha guardado el acreedor");
            return "redirect:/sepa";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", form.get("nombre"));
        data.put("identificador_acreedor", form.get("identificador_acreedor"));
        data.put("nif", form.get("nif"));
        data.put("iban", iban);
        data.put("bic", form.get("bic"));
        data.put("direccion", form.get("direccion"));
        data.put("cp", form.get("cp"));
        data.put("municipio", form.get("municipio"));
        data.put("provincia", form.get("provincia"));

        List<Map<String, Object>> exist = supabase.selectFilter("sepa_creditor", "user_id=eq." + uid);
        Object ibanAntes = exist.isEmpty() ? null : exist.get(0).get("iban");
        if (!exist.isEmpty()) {
            supabase.update("sepa_creditor", String.valueOf(exist.get(0).get("id")), data, uid);
        } else {
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("sepa_creditor", data);
        }
        boolean cambio = avisoCuenta.siCambia(req, session, "Acreedor SEPA (cuenta donde se cobran los recibos)",
                "sepa_creditor", ibanAntes, iban);
        SessionUtils.flash(session, cambio
                ? "✅ Acreedor SEPA guardado. Hemos avisado por email al titular del cambio de cuenta."
                : "✅ Acreedor SEPA guardado");
        return "redirect:/sepa";
    }

    @PostMapping("/mandato/crear")
    public String crearMandato(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        if (!sepaService.validarIBAN(form.get("deudor_iban"))) {
            SessionUtils.flash(session, "❌ IBAN no válido");
            return "redirect:/sepa";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("referencia_mandato", sepaService.generarReferenciaMandato(uid));
        data.put("deudor_nombre", form.get("deudor_nombre"));
        data.put("deudor_nif", form.get("deudor_nif"));
        data.put("deudor_iban", form.get("deudor_iban").replaceAll("\\s", "").toUpperCase());
        data.put("deudor_email", form.get("deudor_email"));
        if (form.get("contrato_id") != null && !form.get("contrato_id").isBlank())
            data.put("contrato_id", form.get("contrato_id"));
        data.put("importe", form.get("importe") != null && !form.get("importe").isBlank() ? Double.parseDouble(form.get("importe")) : null);
        data.put("frecuencia", form.getOrDefault("frecuencia", "mensual"));
        data.put("dia_cobro", form.get("dia_cobro") != null ? Integer.parseInt(form.get("dia_cobro")) : 5);
        data.put("tipo", "recurrente");
        data.put("secuencia", "FRST");
        data.put("estado", "activo");
        data.put("fecha_firma", LocalDate.now().toString());
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("sepa_mandatos", data);

        SessionUtils.flash(session, "✅ Mandato creado");
        return "redirect:/sepa";
    }

    @PostMapping("/remesa/generar")
    public String generarRemesa(@RequestParam String fechaCobro,
                                @RequestParam(defaultValue = "RCUR") String secuencia,
                                HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Crear remesa con los mandatos activos
        Map<String, Object> remesa = new LinkedHashMap<>();
        remesa.put("user_id", uid);
        remesa.put("numero", "REM-" + LocalDate.now().getYear() + "-" + (System.currentTimeMillis() % 10000));
        remesa.put("fecha_cobro", fechaCobro);
        remesa.put("secuencia", secuencia);
        remesa.put("estado", "pendiente");
        remesa.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> savedRem = supabase.insert("sepa_remesas", remesa);
        String remId = String.valueOf(savedRem.get("id"));

        // Crear adeudos para cada mandato activo
        List<Map<String, Object>> mandatos = supabase.selectFilter("sepa_mandatos",
                "user_id=eq." + uid + "&estado=eq.activo");
        for (Map<String, Object> m : mandatos) {
            if (m.get("importe") == null) continue;
            Map<String, Object> ad = new LinkedHashMap<>();
            ad.put("remesa_id", remId);
            ad.put("mandato_id", m.get("id"));
            ad.put("end_to_end_id", "E2E-" + System.currentTimeMillis() + "-" + new Random().nextInt(1000));
            ad.put("importe", m.get("importe"));
            ad.put("concepto", "Cobro " + LocalDate.now().getMonth() + " " + LocalDate.now().getYear());
            ad.put("deudor_nombre", m.get("deudor_nombre"));
            ad.put("deudor_iban", m.get("deudor_iban"));
            ad.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("sepa_adeudos", ad);
        }

        // Generar XML
        sepaService.generarXmlRemesa(remId);

        SessionUtils.flash(session, "✅ Remesa generada");
        return "redirect:/sepa";
    }

    @GetMapping("/remesa/{id}/descargar-xml")
    public ResponseEntity<String> descargarXml(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return ResponseEntity.status(401).build();
        String xml = sepaService.generarXmlRemesa(id);
        if (xml == null) return ResponseEntity.notFound().build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.setContentDispositionFormData("attachment", "remesa-" + id + ".xml");
        return new ResponseEntity<>(xml, headers, 200);
    }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
