package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.StagingService;
import com.inmobilia.service.StorageService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/staging-digital")
public class VirtualStagingController {

    private final SupabaseService supabase;
    private final StorageService storage;
    private final StagingService staging;
    private final AIService ai;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> proyectos = supabase.selectFilter(
                "staging_proyectos", "user_id=eq." + uid + "&order=created_at.desc");
        staging.cerrarAtascados(proyectos, uid);
        model.addAttribute("proyectos", proyectos);
        model.addAttribute("enCurso", proyectos.stream().anyMatch(p ->
                "pendiente".equals(p.get("estado")) || "procesando".equals(p.get("estado"))));
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid + "&select=id,direccion"));
        model.addAttribute("creditos", StagingService.CREDITOS);
        return "virtual_staging";
    }

    @PostMapping("/crear")
    public String crear(@RequestParam Map<String, String> form, @RequestParam(value = "foto", required = false) MultipartFile foto,
                        HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        if (foto == null || foto.isEmpty() || !StorageService.esImagen(foto) || foto.getContentType().contains("gif")) {
            SessionUtils.flash(session, "Sube una foto JPG, PNG o WEBP de la estancia vacía.");
            return "redirect:/staging-digital";
        }
        if (foto.getSize() > 15 * 1024 * 1024) {
            SessionUtils.flash(session, "La foto pesa más de 15 MB. Reduce su tamaño e inténtalo de nuevo.");
            return "redirect:/staging-digital";
        }
        if (!ai.gastarCreditos(uid, StagingService.CREDITOS)) {
            SessionUtils.flash(session, "No te quedan créditos de IA suficientes (hacen falta " + StagingService.CREDITOS + ").");
            return "redirect:/staging-digital";
        }

        byte[] bytes;
        String original;
        try {
            bytes = foto.getBytes();
            original = storage.upload(bytes, foto.getContentType(), "." + foto.getContentType().replace("image/", "").replace("jpeg", "jpg"), "staging");
        } catch (Exception e) {
            ai.devolverCreditos(uid, StagingService.CREDITOS);
            SessionUtils.flash(session, "No se pudo guardar la foto. Inténtalo de nuevo.");
            return "redirect:/staging-digital";
        }

        String nombre = Optional.ofNullable(form.get("nombre")).filter(s -> !s.isBlank()).orElse("Staging");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", nombre);
        data.put("imagen_original_url", original);
        data.put("estilo", form.get("estilo"));
        data.put("tipo_habitacion", form.get("tipo_habitacion"));
        if (form.get("propiedad_id") != null && !form.get("propiedad_id").isBlank())
            data.put("propiedad_id", form.get("propiedad_id"));
        data.put("estado", "pendiente");
        data.put("metadata", Map.of("creditos", StagingService.CREDITOS));
        data.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> creado = supabase.insert("staging_proyectos", data);
        if (creado == null || creado.get("id") == null) {
            ai.devolverCreditos(uid, StagingService.CREDITOS);
            SessionUtils.flash(session, "No se pudo crear el proyecto. Inténtalo de nuevo.");
            return "redirect:/staging-digital";
        }

        staging.procesar(String.valueOf(creado.get("id")), uid, nombre, bytes, foto.getContentType(),
                form.get("estilo"), form.get("tipo_habitacion"));
        SessionUtils.flash(session, "Generando la foto amueblada. Tarda alrededor de un minuto; te avisamos al terminar.");
        return "redirect:/staging-digital";
    }

    @PostMapping("/{id}/borrar")
    public String borrar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        supabase.delete("staging_proyectos", id, uid);
        return "redirect:/staging-digital";
    }
}
