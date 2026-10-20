package com.inmobilia.controller;

import com.inmobilia.service.OfficialDataService;
import com.inmobilia.service.OfficialDataService.*;
import com.inmobilia.service.TaxService;
import com.inmobilia.service.TaxService.*;
import com.inmobilia.service.ValidationService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Herramientas basadas en datos oficiales:
 * - Catastro (consulta por ref. catastral)
 * - Euribor live + historico (BCE)
 * - Geocodificacion (Nominatim)
 * - INE IPV
 * - BOE busqueda
 * - Validadores espanoles (DNI, NIE, CIF, IBAN, RC)
 * - Calculadoras fiscales (ITP, AJD, Plusvalia)
 */
@Controller
@RequestMapping("/herramientas")
@RequiredArgsConstructor
public class DatosOficialesController {

    private final OfficialDataService official;
    private final TaxService tax;
    private final ValidationService validator;

    private void baseModel(Model model) {
        model.addAttribute("page", "Datos Oficiales");
        model.addAttribute("ccaaList", TaxService.ITP_CCAA_2026.keySet().stream().sorted().toList());
    }

    @GetMapping("/datos-oficiales")
    public String hub(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Datos Oficiales");
        model.addAttribute("ccaaList", TaxService.ITP_CCAA_2026.keySet().stream().sorted().toList());
        return "herramientas/datos_oficiales";
    }

    @PostMapping("/datos-oficiales/catastro")
    public String catastro(@RequestParam String rc, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        Map<String, Object> data = official.catastroPorRc(rc);
        model.addAttribute("page", "Datos Oficiales");
        model.addAttribute("ccaaList", TaxService.ITP_CCAA_2026.keySet().stream().sorted().toList());
        model.addAttribute("catastroResult", data);
        model.addAttribute("activeTab", "catastro");
        return "herramientas/datos_oficiales";
    }

    @GetMapping("/euribor")
    public String euribor(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        EuriborLive live = official.euriborLive();
        List<EuriborHistoryRow> hist = official.euriborHistory(24);
        model.addAttribute("page", "Euribor en Vivo");
        model.addAttribute("euribor", live);
        model.addAttribute("historico", hist);
        model.addAttribute("delta12m", official.euriborDelta12m());
        model.addAttribute("tedr", official.bancoDeEspanaTedr());
        double[] spark = official.euriborSpark(24);
        model.addAttribute("sparkValues", spark);
        model.addAttribute("sparkSvg", buildSparkline(spark));
        return "herramientas/euribor";
    }

    private String buildSparkline(double[] v) {
        if (v == null || v.length < 2) return "";
        double min = v[0], max = v[0];
        for (double d : v) { if (d < min) min = d; if (d > max) max = d; }
        double span = (max - min) == 0 ? 1 : (max - min);
        int w = 520, h = 90, pad = 6;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            double x = pad + i * (double)(w - 2*pad) / (v.length - 1);
            double y = h - pad - ((v[i] - min) / span) * (h - 2*pad);
            path.append(i == 0 ? "M" : " L").append(String.format(java.util.Locale.US, "%.1f,%.1f", x, y));
        }
        double lastX = pad + (v.length - 1) * (double)(w - 2*pad) / (v.length - 1);
        double lastY = h - pad - ((v[v.length-1] - min) / span) * (h - 2*pad);
        return "<svg viewBox=\"0 0 " + w + " " + h + "\" class=\"sparkline\">" +
               "<path d=\"" + path + "\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"/>" +
               "<circle cx=\"" + String.format(java.util.Locale.US, "%.1f", lastX) +
               "\" cy=\"" + String.format(java.util.Locale.US, "%.1f", lastY) +
               "\" r=\"3\" fill=\"currentColor\"/></svg>";
    }

    @PostMapping("/datos-oficiales/direccion")
    public String catastroDireccion(@RequestParam String provincia,
                                     @RequestParam String municipio,
                                     @RequestParam(defaultValue = "") String tipoVia,
                                     @RequestParam String nombreVia,
                                     @RequestParam(defaultValue = "") String numero,
                                     HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        var hits = official.catastroPorDireccion(provincia, municipio, tipoVia, nombreVia, numero);
        baseModel(model);
        model.addAttribute("direccionHits", hits);
        model.addAttribute("activeTab", "direccion");
        return "herramientas/datos_oficiales";
    }

    @GetMapping("/datos-oficiales/boe")
    public String boeSearch(@RequestParam(required = false) String q,
                            HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        baseModel(model);
        if (q != null && !q.isBlank()) {
            model.addAttribute("boeUrl", official.boeSearchUrl(q));
            model.addAttribute("boeQuery", q);
        }
        model.addAttribute("activeTab", "boe");
        return "herramientas/datos_oficiales";
    }

    @PostMapping("/datos-oficiales/geocode")
    public String geocode(@RequestParam String direccion, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        Geocode geo = official.geocode(direccion);
        model.addAttribute("page", "Datos Oficiales");
        model.addAttribute("ccaaList", TaxService.ITP_CCAA_2026.keySet().stream().sorted().toList());
        model.addAttribute("geocodeResult", geo);
        model.addAttribute("activeTab", "geocode");
        return "herramientas/datos_oficiales";
    }

    @GetMapping("/datos-oficiales/ine")
    public String ine(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        List<InePoint> ipv = official.ineIpv();
        model.addAttribute("page", "Datos Oficiales");
        model.addAttribute("ccaaList", TaxService.ITP_CCAA_2026.keySet().stream().sorted().toList());
        model.addAttribute("ipv", ipv);
        model.addAttribute("activeTab", "ine");
        return "herramientas/datos_oficiales";
    }

    @PostMapping("/datos-oficiales/validar")
    public String validar(@RequestParam String tipo, @RequestParam String valor,
                          HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        boolean ok = switch (tipo) {
            case "dni"  -> validator.validarDni(valor);
            case "nie"  -> validator.validarNie(valor);
            case "cif"  -> validator.validarCif(valor);
            case "iban" -> validator.validarIban(valor);
            case "rc"   -> validator.validarRefCatastral(valor);
            default     -> false;
        };
        model.addAttribute("page", "Datos Oficiales");
        model.addAttribute("ccaaList", TaxService.ITP_CCAA_2026.keySet().stream().sorted().toList());
        model.addAttribute("validResult", Map.of("tipo", tipo, "valor", valor, "ok", ok));
        model.addAttribute("activeTab", "validar");
        return "herramientas/datos_oficiales";
    }

    // ─── Fiscal ───

    @GetMapping("/fiscal")
    public String fiscal(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Planificacion Fiscal");
        model.addAttribute("ccaaList", TaxService.ITP_CCAA_2026.keySet().stream().sorted().toList());
        return "herramientas/fiscal";
    }

    @PostMapping("/fiscal")
    public String fiscalCalc(@RequestParam String ccaa,
                              @RequestParam double precio,
                              @RequestParam(defaultValue = "false") boolean viviendaHabitual,
                              @RequestParam(defaultValue = "false") boolean joven,
                              @RequestParam(defaultValue = "0") double importeHipoteca,
                              @RequestParam(defaultValue = "0") double valorCatastralSuelo,
                              @RequestParam(defaultValue = "5") int aniosTenencia,
                              @RequestParam(defaultValue = "0.30") double tipoMunicipal,
                              HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        ItpResult itp = tax.calcItp(ccaa, precio, viviendaHabitual, joven);
        AjdResult ajd = tax.calcAjd(ccaa, importeHipoteca);
        PlusvaliaResult plusv = tax.calcPlusvaliaMunicipal(valorCatastralSuelo, aniosTenencia, tipoMunicipal, null);
        model.addAttribute("page", "Planificacion Fiscal");
        model.addAttribute("ccaaList", TaxService.ITP_CCAA_2026.keySet().stream().sorted().toList());
        model.addAttribute("itp", itp);
        model.addAttribute("ajd", ajd);
        model.addAttribute("plusvalia", plusv);
        model.addAttribute("ccaa", ccaa);
        return "herramientas/fiscal";
    }
}
