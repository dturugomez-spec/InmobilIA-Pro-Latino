package com.inmobilia.controller;

import com.inmobilia.service.CifradoService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;

/**
 * Páginas legales (protección de datos del país de la inmobiliaria): privacidad, términos, cookies, seguridad y contrato de encargado
 * del tratamiento (DPA). Accesibles sin login.
 *
 * Datos que dependen de la configuración real (y que NO se afirman si no están puestos):
 *  - DATOS_REGION   → dónde están alojados los servidores y la BD (p. ej. "Unión Europea (Fráncfort, Alemania)")
 *  - DATOS_COPIAS   → política de copias de seguridad (p. ej. "copia diaria con 7 días de retención")
 *  - TITULAR_NOMBRE / TITULAR_NIF / TITULAR_DOMICILIO → identidad del encargado en el DPA
 */
@Controller
@RequiredArgsConstructor
public class LegalController {

    private final CifradoService cifrado;

    @Value("${DATOS_REGION:}")
    private String regionDatos;
    @Value("${DATOS_COPIAS:}")
    private String copiasDatos;
    @Value("${TITULAR_NOMBRE:}")
    private String titularNombre;
    @Value("${TITULAR_NIF:}")
    private String titularNif;
    @Value("${TITULAR_DOMICILIO:}")
    private String titularDomicilio;

    // La portada enlazaba a /legal/... (daba 404): se sirven ambas rutas
    @GetMapping({"/privacy", "/legal/privacy", "/privacidad"})
    public String privacy(Model model) {
        model.addAttribute("page", "Política de privacidad");
        datosComunes(model);
        return "legal/privacy";
    }

    @GetMapping({"/terms", "/legal/terms", "/terminos"})
    public String terms(Model model) {
        model.addAttribute("page", "Términos de uso");
        return "legal/terms";
    }

    @GetMapping({"/cookies", "/legal/cookies"})
    public String cookies(Model model) {
        model.addAttribute("page", "Política de cookies");
        return "legal/cookies";
    }

    /** Centro de seguridad: qué hacemos para proteger los datos, sin letra pequeña. */
    @GetMapping({"/seguridad-datos", "/legal/seguridad"})
    public String seguridad(Model model) {
        model.addAttribute("page", "Seguridad y privacidad");
        datosComunes(model);
        return "legal/seguridad";
    }

    /** Contrato de encargado del tratamiento (encargado del tratamiento) para que la inmobiliaria lo firme. */
    @GetMapping({"/dpa", "/legal/dpa"})
    public String dpa(Model model) {
        model.addAttribute("page", "Contrato de encargado del tratamiento");
        datosComunes(model);
        model.addAttribute("titularNombre", vacio(titularNombre) ? null : titularNombre.trim());
        model.addAttribute("titularNif", vacio(titularNif) ? null : titularNif.trim());
        model.addAttribute("titularDomicilio", vacio(titularDomicilio) ? null : titularDomicilio.trim());
        return "legal/dpa";
    }

    /** Estándar RFC 9116: dónde avisar si alguien encuentra una vulnerabilidad. */
    @GetMapping(value = {"/.well-known/security.txt", "/security.txt"}, produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String securityTxt() {
        return "Contact: mailto:inmobilia.support@gmail.com\n"
                + "Expires: " + LocalDate.now().plusYears(1) + "T00:00:00.000Z\n"
                + "Preferred-Languages: es, en\n"
                + "Canonical: https://www.inmobiliapro.es/.well-known/security.txt\n"
                + "Policy: https://www.inmobiliapro.es/seguridad-datos\n";
    }

    private void datosComunes(Model model) {
        model.addAttribute("regionDatos", vacio(regionDatos) ? null : regionDatos.trim());
        model.addAttribute("copiasDatos", vacio(copiasDatos) ? null : copiasDatos.trim());
        model.addAttribute("cifradoActivo", cifrado.activo());
    }

    private static boolean vacio(String s) { return s == null || s.isBlank(); }
}
