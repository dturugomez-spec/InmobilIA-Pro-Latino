package com.inmobilia.controller;

import com.inmobilia.service.StorageService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Subida masiva de fotos a una propiedad concreta.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class BulkPhotoController {

    private final StorageService storage;
    private final SupabaseService supabase;

    @GetMapping("/propiedades/{id}/fotos")
    public String form(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        var rows = supabase.findBy("propiedades", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/propiedades/grid";
        Map<String, Object> prop = rows.get(0);

        // Fotos existentes en attachments
        var fotos = supabase.findBy("attachments", Map.of(
                "org_id", uid,
                "entity_type", "propiedad",
                "entity_id", id,
                "file_type", "image"));

        model.addAttribute("page", "Galería · " + prop.get("name"));
        model.addAttribute("prop", prop);
        model.addAttribute("fotos", fotos);
        model.addAttribute("storageOk", storage.isConfigured());
        return "bulk_photos";
    }

    @PostMapping("/propiedades/{id}/fotos")
    @ResponseBody
    public Map<String, Object> upload(@PathVariable String id,
                                       @RequestParam("files") MultipartFile[] files,
                                       @RequestParam(defaultValue = "false") boolean setAsFeatured,
                                       HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        String orgId = SessionUtils.uid(session);
        String userId = SessionUtils.realUid(session);
        // El inmueble tiene que ser de esta inmobiliaria (antes se podían colgar fotos en el de otra)
        if (supabase.findBy("propiedades", Map.of("id", id, "user_id", orgId)).isEmpty())
            return Map.of("ok", false, "error", "Inmueble no encontrado");

        List<String> urls = new ArrayList<>();
        int success = 0, fails = 0;

        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            if (f.getSize() > 10 * 1024 * 1024 || !StorageService.esImagen(f)) { fails++; continue; }
            try {
                String url = storage.upload(f, "properties/" + id);
                // Guardar en attachments
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("user_id", userId);
                data.put("org_id", orgId);
                data.put("entity_type", "propiedad");
                data.put("entity_id", id);
                data.put("name", f.getOriginalFilename());
                data.put("url", url);
                data.put("file_type", "image");
                data.put("size_kb", (int)(f.getSize() / 1024));
                data.put("created_at", OffsetDateTime.now().toString());
                supabase.insert("attachments", data);
                urls.add(url);
                success++;
            } catch (Exception e) {
                fails++;
                log.warn("Upload failed: {}", e.getMessage());
            }
        }

        // Establecer la primera como featured si se pidió y no había
        if (setAsFeatured && !urls.isEmpty()) {
            supabase.update("propiedades", id, Map.of("featured_photo", urls.get(0)), orgId);
        }

        return Map.of("ok", true, "uploaded", success, "failed", fails, "urls", urls);
    }

    @PostMapping("/api/photos/{attachId}/set-featured")
    @ResponseBody
    public Map<String, Object> setFeatured(@PathVariable String attachId, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        String orgId = SessionUtils.uid(session);
        var att = supabase.findBy("attachments", Map.of("id", attachId, "org_id", orgId));
        if (att.isEmpty()) return Map.of("ok", false);
        Map<String, Object> a = att.get(0);
        String propId = String.valueOf(a.get("entity_id"));
        boolean ok = supabase.update("propiedades", propId,
                Map.of("featured_photo", a.get("url")), orgId);
        return Map.of("ok", ok);
    }
}
