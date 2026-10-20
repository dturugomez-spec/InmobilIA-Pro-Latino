package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Informe mensual automático al PROPIETARIO de cada inmueble.
 *
 * Es una de las cosas que más fideliza a una inmobiliaria con sus clientes:
 * el propietario ve cada mes qué se está haciendo con su piso (visitas,
 * interesados, visitas al portal, sugerencia de precio) → renueva la exclusiva.
 *
 * Se envía automáticamente el día 1 de cada mes, o manualmente desde el panel
 * del portal del propietario.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OwnerReportService {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final BrandingService branding;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    /** Cron: día 1 de cada mes a las 9:00 (Europe/Madrid). */
    @Scheduled(cron = "0 0 9 1 * *", zone = "Europe/Madrid")
    public void enviarInformesMensuales() {
        List<Map<String, Object>> props;
        try {
            props = supabase.selectFilter("propiedades",
                    "propietario_email=not.is.null&portal_activo=eq.true&limit=1000");
        } catch (Exception e) {
            log.warn("Informe propietarios: no se pudo listar propiedades: {}", e.getMessage());
            return;
        }
        int ok = 0;
        for (Map<String, Object> p : props) {
            try { if (enviarInforme(p, false)) ok++; }
            catch (Exception e) { log.warn("Informe propietario {} falló: {}", p.get("id"), e.getMessage()); }
        }
        log.info("Informes mensuales a propietarios enviados: {}/{}", ok, props.size());
    }

    /** Envía el informe de UNA propiedad a su propietario. Devuelve true si se envió. */
    public boolean enviarInforme(Map<String, Object> p, boolean manual) {
        String email = str(p.get("propietario_email"));
        if (email.isBlank()) return false;

        String propId = str(p.get("id"));
        String uid = str(p.get("user_id"));
        String nombreInmueble = firstNonBlank(str(p.get("name")), str(p.get("address")), "tu inmueble");
        String ownerName = firstNonBlank(str(p.get("propietario_nombre")), "");

        // Actividad
        String desde30 = OffsetDateTime.now().minusDays(30).toString();
        int visitasMes = count("visitas", "propiedad_id=eq." + propId + "&fecha_inicio=gte." + desde30);
        int visitasTotal = count("visitas", "propiedad_id=eq." + propId);
        int interesados = count("leads", "user_id=eq." + uid + "&property_id=eq." + propId);
        long vistasPortal = num(p.get("portal_vistas"));
        long dias = diasEnMercado(p.get("created_at"));
        double precio = num(p.get("current_value")) > 0 ? num(p.get("current_value")) : num(p.get("purchase_price"));
        String token = str(p.get("portal_token"));
        String urlPortal = token.isBlank() ? BASE_URL : BASE_URL + "/mi-inmueble/" + token;

        // Sugerencia (regla simple, sin depender de IA)
        String sugerencia;
        if (visitasMes == 0 && dias > 30) {
            sugerencia = "El inmueble lleva tiempo sin visitas. Puede ser buen momento para revisar el precio o refrescar las fotos y el anuncio.";
        } else if (interesados > 0 || visitasMes > 0) {
            sugerencia = "Hay interés activo. Seguimos trabajando el seguimiento de los contactos para conseguir una oferta.";
        } else {
            sugerencia = "Estamos dando difusión al inmueble en los portales y captando compradores.";
        }

        Map<String, Object> b = branding.branding(uid);
        String agencia = str(b.get("nombre"));
        String color = firstNonBlank(str(b.get("color")), "#1d4ed8");
        String telAg = str(b.get("telefono"));

        String html = buildHtml(agencia, color, telAg, ownerName, nombreInmueble, precio,
                visitasMes, visitasTotal, interesados, vistasPortal, dias, sugerencia, urlPortal);

        var res = emailService.send(null, email,
                "Informe mensual de tu inmueble · " + nombreInmueble, html);
        if (!res.success()) {
            log.warn("Informe propietario {}: email no enviado: {}", propId, res.message());
            return false;
        }
        return true;
    }

    private String buildHtml(String agencia, String color, String telAg, String ownerName, String inmueble,
                             double precio, int visitasMes, int visitasTotal, int interesados,
                             long vistasPortal, long dias, String sugerencia, String urlPortal) {
        String saludo = ownerName.isBlank() ? "Hola," : ("Hola " + ownerName + ",");
        String precioFmt = String.format("%,d", (long) precio).replace(',', '.');
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:600px;margin:auto;color:#0f172a\">"
            + "<div style=\"background:" + color + ";color:#fff;padding:22px 24px;border-radius:12px 12px 0 0\">"
            + "  <div style=\"font-weight:bold;font-size:18px\">" + esc(agencia) + "</div>"
            + "  <div style=\"opacity:.85;font-size:13px;margin-top:2px\">Informe mensual de tu inmueble</div>"
            + "</div>"
            + "<div style=\"border:1px solid #e2e8f0;border-top:none;border-radius:0 0 12px 12px;padding:24px\">"
            + "  <p style=\"margin:0 0 6px\">" + esc(saludo) + "</p>"
            + "  <p style=\"margin:0 0 18px;color:#475569\">Este es el resumen de la actividad de <strong>" + esc(inmueble) + "</strong> este mes:</p>"
            + "  <table style=\"width:100%;border-collapse:collapse;margin-bottom:18px\">"
            + row("Precio de venta actual", precioFmt + (" " + PaisActual.moneda()))
            + row("Días en el mercado", dias + " días")
            + row("Visitas este mes", String.valueOf(visitasMes))
            + row("Visitas totales", String.valueOf(visitasTotal))
            + row("Compradores interesados", String.valueOf(interesados))
            + row("Visitas del propietario al portal", String.valueOf(vistasPortal))
            + "  </table>"
            + "  <div style=\"background:#f1f5f9;border-radius:10px;padding:14px 16px;margin-bottom:20px\">"
            + "    <div style=\"font-size:12px;font-weight:bold;color:" + color + ";text-transform:uppercase;letter-spacing:.5px;margin-bottom:4px\">Nuestra recomendación</div>"
            + "    <div style=\"font-size:14px;color:#334155\">" + esc(sugerencia) + "</div>"
            + "  </div>"
            + "  <div style=\"text-align:center;margin:24px 0\">"
            + "    <a href=\"" + urlPortal + "\" style=\"display:inline-block;background:" + color + ";color:#fff;text-decoration:none;padding:12px 26px;border-radius:8px;font-weight:bold\">Ver la actividad en directo</a>"
            + "  </div>"
            + "  <p style=\"font-size:12px;color:#94a3b8;margin:18px 0 0\">Un saludo del equipo de " + esc(agencia)
            + (telAg.isBlank() ? "" : " · " + esc(telAg)) + "</p>"
            + "</div></div>";
    }

    private static String row(String k, String v) {
        return "<tr>"
            + "<td style=\"padding:9px 0;border-bottom:1px solid #f1f5f9;color:#64748b;font-size:14px\">" + esc(k) + "</td>"
            + "<td style=\"padding:9px 0;border-bottom:1px solid #f1f5f9;text-align:right;font-weight:bold;font-size:14px\">" + esc(v) + "</td>"
            + "</tr>";
    }

    private int count(String table, String filter) {
        try { return supabase.selectFilter(table, filter + "&limit=1000").size(); }
        catch (Exception e) { return 0; }
    }

    private long diasEnMercado(Object createdAt) {
        try { return ChronoUnit.DAYS.between(OffsetDateTime.parse(String.valueOf(createdAt)), OffsetDateTime.now()); }
        catch (Exception e) { return 0; }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long num(Object o) { try { return (long) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; } }
    private static String firstNonBlank(String... vals) { for (String v : vals) if (v != null && !v.isBlank()) return v; return ""; }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
