package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.PdfService;
import com.inmobilia.service.ScoringService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Herramientas de IA: analizador de operaciones, lector de contratos, valoracion IA,
 * asistente conversacional, scoring, dealfinder, due diligence, memo, marketing,
 * analisis zona, predictor, informes, BI.
 */
@Controller
@RequestMapping("/ia")
@RequiredArgsConstructor
public class AIToolsController {

    private final AIService ai;
    private final PdfService pdf;
    private final ScoringService scoring;
    private final SupabaseService supabase;

    // ─── Analizador de operaciones ───
    @GetMapping("/analyzer")
    public String analyzerForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Analizador de Operaciones IA");
        return "ia/analyzer";
    }

    @PostMapping("/analyzer")
    public String analyzerRun(@RequestParam String contexto,
                              @RequestParam double precio,
                              @RequestParam double rentaMensual,
                              @RequestParam(defaultValue = "") String notas,
                              HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String prompt = """
                Analiza esta operacion inmobiliaria como analista institucional senior.
                Contexto: %s
                Precio: %,.0f EUR
                Renta mensual: %,.0f EUR
                Notas adicionales: %s
                Entrega: yield bruto, yield neto estimado, 3 riesgos clave, 3 puntos fuertes,
                recomendacion GO/NO-GO con justificacion cuantitativa.
                """.formatted(contexto, precio, rentaMensual, notas);
        String res = ai.call(SessionUtils.apiKey(session), prompt,
                "Eres un director de inversiones inmobiliarias.", 1500, SessionUtils.uid(session));
        model.addAttribute("page", "Analizador de Operaciones IA");
        model.addAttribute("result", res != null ? res : "⚠️ " + ai.motivoSinRespuesta(SessionUtils.uid(session)));
        model.addAttribute("contexto", contexto);
        model.addAttribute("precio", precio);
        model.addAttribute("rentaMensual", rentaMensual);
        return "ia/analyzer";
    }

    // ─── Lector de contratos ───
    @GetMapping("/contratos")
    public String contratosForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Lector IA de Contratos");
        return "ia/contratos";
    }

    @PostMapping("/contratos")
    public String contratosRun(@RequestParam(required = false) MultipartFile archivo,
                                @RequestParam(required = false) String texto,
                                HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String contenido = texto != null ? texto : "";
        if (archivo != null && !archivo.isEmpty()) {
            try { contenido += "\n" + pdf.extractText(archivo.getBytes()); }
            catch (Exception e) { model.addAttribute("error", "No se pudo leer el PDF."); }
        }
        if (contenido.isBlank()) {
            model.addAttribute("page", "Lector IA de Contratos");
            model.addAttribute("error", "Adjunta un PDF o pega el texto del contrato.");
            return "ia/contratos";
        }
        String prompt = """
                Analiza este contrato de arrendamiento. Extrae:
                - Datos de las partes.
                - Renta, deposito, duracion, IPC.
                - Clausulas abusivas o inusuales.
                - Riesgos legales prioritarios.
                - Recomendaciones de negociacion.
                Contrato:
                %s
                """.formatted(contenido.length() > 20000 ? contenido.substring(0, 20000) : contenido);
        String res = ai.call(SessionUtils.apiKey(session), prompt,
                "Eres un abogado inmobiliario experto.", 2000, SessionUtils.uid(session));
        model.addAttribute("page", "Lector IA de Contratos");
        model.addAttribute("result", res != null ? res : "⚠️ " + ai.motivoSinRespuesta(SessionUtils.uid(session)));
        return "ia/contratos";
    }

    // ─── Asistente conversacional ───
    @GetMapping("/asistente")
    public String asistenteForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Asistente Inmobiliario IA");
        return "ia/asistente";
    }

    @PostMapping("/asistente")
    public String asistenteRun(@RequestParam String pregunta,
                                HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String res = ai.call(SessionUtils.apiKey(session), pregunta,
                "Eres un asistente especializado en inversion inmobiliaria en " + com.inmobilia.util.PaisActual.get().nombre() + ".",
                1200, SessionUtils.uid(session));
        model.addAttribute("page", "Asistente Inmobiliario IA");
        model.addAttribute("pregunta", pregunta);
        model.addAttribute("result", res != null ? res : "⚠️ " + ai.motivoSinRespuesta(SessionUtils.uid(session)));
        return "ia/asistente";
    }

    // ─── Scoring ───
    @GetMapping("/scoring")
    public String scoringForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Scoring de Inquilinos");
        return "ia/scoring";
    }

    @PostMapping("/scoring")
    public String scoringRun(@RequestParam String nombre,
                             @RequestParam int edad,
                             @RequestParam String trabajo,
                             @RequestParam String contrato,
                             @RequestParam double ingresos,
                             @RequestParam int antiguedad,
                             @RequestParam double renta,
                             @RequestParam(defaultValue = "false") boolean avalista,
                             @RequestParam(defaultValue = "false") boolean seguro,
                             @RequestParam(defaultValue = "0") int desahucios,
                             HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        var s = scoring.scoreCandidate(nombre, edad, trabajo, contrato, ingresos, antiguedad,
                renta, avalista, seguro, desahucios);
        model.addAttribute("page", "Scoring de Inquilinos");
        model.addAttribute("result", s);
        model.addAttribute("nombre", nombre);
        return "ia/scoring";
    }

    // ─── Memo ───
    @GetMapping("/memo")
    public String memoForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Informe de Inversion");
        return "ia/memo";
    }

    @PostMapping("/memo")
    public String memoRun(@RequestParam String propiedad,
                          @RequestParam double precio,
                          @RequestParam double rentaMensual,
                          @RequestParam String zona,
                          @RequestParam String tesis,
                          HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String prompt = """
                Redacta un Investment Memorandum institucional.
                Activo: %s en %s
                Precio: %,.0f EUR - Renta: %,.0f EUR/mes
                Tesis: %s
                Estructura: Resumen ejecutivo, Activo y ubicacion, Mercado,
                Estructura financiera, Analisis financiero (IRR/NPV/Cap Rate),
                Riesgos y mitigantes, Plan de valor, Recomendacion.
                """.formatted(propiedad, zona, precio, rentaMensual, tesis);
        String res = ai.call(SessionUtils.apiKey(session), prompt,
                "Eres un portfolio manager de fondos inmobiliarios.", 2500, SessionUtils.uid(session));
        model.addAttribute("page", "Informe de Inversion");
        model.addAttribute("result", res != null ? res : "⚠️ " + ai.motivoSinRespuesta(SessionUtils.uid(session)));
        model.addAttribute("propiedad", propiedad);
        return "ia/memo";
    }

    // ─── Marketing ───
    @GetMapping("/marketing")
    public String marketingForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Marketing & Captacion IA");
        return "ia/marketing";
    }

    @PostMapping("/marketing")
    public String marketingRun(@RequestParam String activo,
                               @RequestParam String audiencia,
                               @RequestParam String canal,
                               HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String prompt = """
                Disena una campana de marketing inmobiliario.
                Activo: %s
                Audiencia: %s
                Canal: %s
                Entrega: propuesta de valor, copy principal (50 palabras),
                3 variantes de titular, 5 hashtags, CTA, estrategia de difusion.
                """.formatted(activo, audiencia, canal);
        String res = ai.call(SessionUtils.apiKey(session), prompt,
                "Eres un creative director de real estate.", 1200, SessionUtils.uid(session));
        model.addAttribute("page", "Marketing & Captacion IA");
        model.addAttribute("result", res != null ? res : "⚠️ " + ai.motivoSinRespuesta(SessionUtils.uid(session)));
        return "ia/marketing";
    }

    // ─── Zona ───
    @GetMapping("/zona")
    public String zonaForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Analisis de Zona y Mercado");
        return "ia/zona";
    }

    @PostMapping("/zona")
    public String zonaRun(@RequestParam String zona,
                          @RequestParam String tipoActivo,
                          HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String prompt = """
                Analiza esta zona para inversion en %s.
                Zona: %s
                Entrega: Demografia, servicios, transporte, tendencias de precio,
                ratio rent/price, gentrificacion, oportunidades y riesgos,
                recomendacion de perfil inversor.
                """.formatted(tipoActivo, zona);
        String res = ai.call(SessionUtils.apiKey(session), prompt,
                "Eres un analista del mercado inmobiliario de " + com.inmobilia.util.PaisActual.get().nombre() + ".", 1500, SessionUtils.uid(session));
        model.addAttribute("page", "Analisis de Zona y Mercado");
        model.addAttribute("result", res != null ? res : "⚠️ " + ai.motivoSinRespuesta(SessionUtils.uid(session)));
        model.addAttribute("zona", zona);
        return "ia/zona";
    }

    // ─── Pipeline de Deals (dealflow) ───
    @GetMapping("/dealflow")
    public String dealflow(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        List<Map<String, Object>> deals = supabase.query("oportunidades", uid);
        model.addAttribute("page", "Pipeline de Operaciones");
        model.addAttribute("deals", deals);
        return "ia/dealflow";
    }

    // ─── Due Diligence IA ───
    @GetMapping("/due-diligence")
    public String ddForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Due Diligence Asistido");
        return "ia/due_diligence";
    }

    @PostMapping("/due-diligence")
    public String ddRun(@RequestParam String activo,
                        @RequestParam String contexto,
                        HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String prompt = """
                Due diligence de: %s
                Contexto: %s
                Entrega checklist exhaustivo por bloque:
                Legal y urbanistico, Tecnico (estructura/instalaciones), Fiscal,
                Economico (ingresos/gastos), Registral/catastral, Mercado, ESG.
                """.formatted(activo, contexto);
        String res = ai.call(SessionUtils.apiKey(session), prompt,
                "Eres un Director de Due Diligence.", 2200, SessionUtils.uid(session));
        model.addAttribute("page", "Due Diligence Asistido");
        model.addAttribute("result", res != null ? res : "⚠️ " + ai.motivoSinRespuesta(SessionUtils.uid(session)));
        return "ia/due_diligence";
    }
}
