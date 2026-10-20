package com.inmobilia.controller;

import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.Numeros;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Reservas online: el comprador pide reservar un inmueble con una señal y la inmobiliaria
 * la cobra y la marca como pagada.
 *
 * Aún NO hay cobro por internet: antes la página mandaba a un enlace de Stripe inventado que
 * no llevaba a ningún pago, y había un webhook sin verificar que podía marcar reservas como
 * pagadas. El cobro real necesita Stripe Connect (el dinero va a cada inmobiliaria).
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/reservas-online")
public class OnlineReservationController {

    static final double SENAL_MINIMA = 500;

    private final SupabaseService supabase;
    private final NotificationService notif;
    private final com.inmobilia.service.PaisService paises;
    private final com.inmobilia.service.CurrencyService divisas;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> reservas = supabase.selectFilter("reservas_online", "user_id=eq." + uid + "&order=created_at.desc");
        long pagadas = reservas.stream().filter(r -> "pagada".equals(r.get("estado"))).count();
        double totalCobrado = reservas.stream().filter(r -> "pagada".equals(r.get("estado")))
                .mapToDouble(r -> nd(r.get("importe"))).sum();
        model.addAttribute("reservas", reservas);
        model.addAttribute("pagadas", pagadas);
        model.addAttribute("totalCobrado", totalCobrado);
        return "online_reservations";
    }

    /** La inmobiliaria marca la señal como cobrada, o cancela la reserva. */
    @PostMapping("/{id}/estado")
    public String cambiarEstado(@PathVariable String id, @RequestParam String estado, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (!Set.of("pagada", "cancelada", "pendiente").contains(estado)) return "redirect:/reservas-online";
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("estado", estado);
        upd.put("fecha_pago", "pagada".equals(estado) ? OffsetDateTime.now().toString() : SupabaseService.VACIAR);
        boolean ok = supabase.update("reservas_online", id, upd, uid);
        SessionUtils.flash(session, !ok ? "⚠️ No se pudo actualizar la reserva"
                : "pagada".equals(estado) ? "✅ Señal marcada como cobrada" : "Reserva actualizada");
        return "redirect:/reservas-online";
    }

    /** Página pública para reservar. */
    @GetMapping("/p/{propId}")
    public String form(@PathVariable String propId, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + propId);
        if (rows.isEmpty()) { model.addAttribute("error", true); return "online_reservation_public"; }
        Map<String, Object> p = rows.get(0);
        paises.usar(String.valueOf(p.get("user_id")));  // moneda y formato del país de la inmobiliaria
        model.addAttribute("propiedad", p);
        model.addAttribute("precio", precio(p));
        model.addAttribute("direccion", direccion(p));
        model.addAttribute("importeSugerido", senalSugerida(precio(p), minimoSenal()));
        return "online_reservation_public";
    }

    @PostMapping("/p/{propId}")
    public String reservar(@PathVariable String propId, @RequestParam Map<String, String> form, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + propId);
        if (rows.isEmpty()) return "redirect:/";
        Map<String, Object> p = rows.get(0);
        paises.usar(String.valueOf(p.get("user_id")));
        double minimo = minimoSenal();
        String nombre = form.getOrDefault("nombre", "").trim();
        String email = form.getOrDefault("email", "").trim();
        if (nombre.isEmpty() || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            model.addAttribute("propiedad", p);
            model.addAttribute("precio", precio(p));
            model.addAttribute("direccion", direccion(p));
            model.addAttribute("importeSugerido", senalSugerida(precio(p), minimo));
            model.addAttribute("aviso", "Escribe tu nombre y un email válido.");
            return "online_reservation_public";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", p.get("user_id"));
        data.put("propiedad_id", propId);
        data.put("nombre", nombre);
        data.put("email", email);
        data.put("telefono", form.get("telefono"));
        data.put("dni", form.get("dni") == null ? null : form.get("dni").trim().toUpperCase());
        // Antes un importe vacío o escrito con coma ("1.500,00") daba error 500
        data.put("importe", senal(Numeros.decimal(form.get("importe")), precio(p), minimo));
        data.put("estado", "pendiente");
        data.put("fecha_caducidad", OffsetDateTime.now().plusDays(15).toString());
        data.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("reservas_online", data);
        if (saved == null) {
            model.addAttribute("error", true);
            return "online_reservation_public";
        }

        notif.create(String.valueOf(p.get("user_id")), "Nueva solicitud de reserva",
                nombre + " quiere reservar " + direccion(p) + " con una señal de "
                        + String.format(com.inmobilia.util.PaisActual.get().locale(), "%,.0f", nd(saved.get("importe")))
                        + " " + com.inmobilia.util.PaisActual.moneda() + ". Contacta para el cobro.",
                "/reservas-online", "alta");
        model.addAttribute("reserva", saved);
        model.addAttribute("propiedad", p);
        model.addAttribute("direccion", direccion(p));
        return "online_reservation_pay";
    }

    static double precio(Map<String, Object> p) {
        double v = nd(p.get("current_value"));
        return v > 0 ? v : nd(p.get("purchase_price"));
    }

    static String direccion(Map<String, Object> p) {
        String dir = Objects.toString(p.get("address"), "").trim();
        String ciudad = Objects.toString(p.get("city"), "").trim();
        if (dir.isEmpty()) dir = Objects.toString(p.get("name"), "el inmueble");
        return ciudad.isEmpty() || dir.contains(ciudad) ? dir : dir + ", " + ciudad;
    }

    /** Mínimo de la señal sugerida: el equivalente a ~1.000 USD en la moneda de la inmobiliaria. */
    private double minimoSenal() {
        return Math.round(divisas.convertir(1000, "USD", com.inmobilia.util.PaisActual.moneda()) / 100) * 100.0;
    }

    /** 1 % del precio, redondeado a 100 unidades, con un mínimo de `minimo`. */
    static double senalSugerida(double precio, double minimo) {
        return Math.max(minimo, Math.round(precio * 0.01 / 100) * 100.0);
    }

    static double senalSugerida(double precio) { return senalSugerida(precio, 1000); }

    /** La señal propuesta, entre la mitad del mínimo y el precio; si no vale, la sugerida. */
    static double senal(Double propuesta, double precio, double minimo) {
        if (propuesta == null || propuesta < minimo * SENAL_MINIMA / 1000) return senalSugerida(precio, minimo);
        return precio > 0 ? Math.min(propuesta, precio) : Math.min(propuesta, minimo * 1000);
    }

    static double senal(Double propuesta, double precio) { return senal(propuesta, precio, 1000); }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
