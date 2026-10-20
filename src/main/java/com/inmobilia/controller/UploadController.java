package com.inmobilia.controller;

import com.inmobilia.service.StorageService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Endpoints para subir archivos (fotos, documentos) a Supabase Storage.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class UploadController {

    private final StorageService storage;

    @PostMapping("/api/upload")
    @ResponseBody
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam(defaultValue = "misc") String folder,
                                       HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false, "error", "No autorizado");
        if (!storage.isConfigured()) return Map.of("ok", false, "error", "Storage no configurado");
        try {
            // Validar tamaño (max 10 MB para fotos)
            if (file.getSize() > 10 * 1024 * 1024) {
                return Map.of("ok", false, "error", "Archivo demasiado grande (máx 10 MB)");
            }
            // El almacenamiento es público: solo fotos, nunca documentos
            if (!StorageService.esImagen(file)) {
                return Map.of("ok", false, "error", "Solo se pueden subir fotos (jpg, png, webp o gif)");
            }
            String url = storage.upload(file, SessionUtils.uid(session) + "-" + folder);
            return Map.of("ok", true, "url", url);
        } catch (Exception e) {
            log.error("Error upload: {}", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
