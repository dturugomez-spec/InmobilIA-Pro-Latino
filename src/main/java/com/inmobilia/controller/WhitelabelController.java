package com.inmobilia.controller;

import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/**
 * La antigua pantalla de "marca blanca" guardaba subdominio, CSS, favicon y colores que
 * nada usaba. La marca que de verdad ven los clientes (logo, color, teléfono y email en
 * PDFs, correos, fichas y portal del propietario) sale del Perfil de empresa.
 */
@Controller
@RequestMapping("/whitelabel")
public class WhitelabelController {

    @GetMapping
    public String config(HttpSession session) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        SessionUtils.flash(session, "Tu marca (logo, color y datos de contacto) se configura aquí, en el Perfil de empresa.");
        return "redirect:/business";
    }
}
