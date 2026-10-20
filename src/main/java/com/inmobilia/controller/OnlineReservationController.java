package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/reservas-online")
public class OnlineReservationController {

    private final SupabaseService supabase;
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

    /** Página pública para reservar. */
    @GetMapping("/p/{propId}")
    public String form(@PathVariable String propId, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + propId);
        if (rows.isEmpty()) { model.addAttribute("error", true); return "online_reservation_public"; }
        model.addAttribute("propiedad", rows.get(0));
        paises.usar(String.valueOf(rows.get(0).get("user_id")));  // moneda y formato del país de la inmobiliaria
        // Importe por defecto: 1% del precio o mínimo el equivalente a ~1.000 USD en la moneda de la agencia
        double minimo = divisas.convertir(1000, "USD", com.inmobilia.util.PaisActual.moneda());
        double importe = Math.max(Math.round(minimo), nd(rows.get(0).get("precio")) * 0.01);
        model.addAttribute("importeSugerido", importe);
        return "online_reservation_public";
    }

    @PostMapping("/p/{propId}")
    public String reservar(@PathVariable String propId, @RequestParam Map<String, String> form, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + propId);
        if (rows.isEmpty()) return "redirect:/";
        Map<String, Object> p = rows.get(0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", p.get("user_id"));
        data.put("propiedad_id", propId);
        data.put("nombre", form.get("nombre"));
        data.put("email", form.get("email"));
        data.put("telefono", form.get("telefono"));
        data.put("dni", form.get("dni"));
        data.put("importe", Double.parseDouble(form.get("importe")));
        data.put("estado", "pendiente");
        data.put("fecha_caducidad", OffsetDateTime.now().plusDays(15).toString());
        data.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("reservas_online", data);

        // Generar Stripe Checkout Session aquí (placeholder)
        model.addAttribute("reserva", saved);
        model.addAttribute("propiedad", p);
        model.addAttribute("stripeUrl", "https://checkout.stripe.com/c/pay/" + saved.get("token_acceso"));
        return "online_reservation_pay";
    }

    @PostMapping("/webhook/stripe")
    @ResponseBody
    public Map<String, Object> webhook(@RequestBody Map<String, Object> body) {
        try {
            String paymentIntent = String.valueOf(body.get("payment_intent"));
            String tipo = String.valueOf(body.get("type"));
            if ("payment_intent.succeeded".equals(tipo)) {
                // Marcar reserva como pagada
                List<Map<String, Object>> rows = supabase.selectFilter("reservas_online", "stripe_payment_intent=eq." + paymentIntent);
                if (!rows.isEmpty()) {
                    supabase.update("reservas_online", String.valueOf(rows.get(0).get("id")), Map.of(
                            "estado", "pagada",
                            "fecha_pago", OffsetDateTime.now().toString()
                    ));
                }
            }
        } catch (Exception e) {}
        return Map.of("received", true);
    }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
