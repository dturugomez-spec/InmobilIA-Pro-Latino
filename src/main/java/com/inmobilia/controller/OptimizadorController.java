package com.inmobilia.controller;

import com.inmobilia.service.AnuncioScoreService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 📣 Optimizador de anuncios.
 *  - /optimizador: cartera con Anuncio Score (peor primero) + checklist + precio
 *  - /optimizador/{id}/consejo: consejo IA (AJAX)
 */
@Controller
@RequiredArgsConstructor
public class OptimizadorController {

    private final SupabaseService supabase;
    private final AnuncioScoreService anuncioScore;

    @GetMapping("/optimizador")
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> props = supabase.selectFilter(
                "propiedades", "user_id=eq." + uid + "&order=created_at.desc&limit=300");
        double mediana = anuncioScore.medianaEurM2(props);

        List<Map<String, Object>> items = new ArrayList<>();
        long sumaScore = 0;
        for (Map<String, Object> p : props) {
            Map<String, Object> eval = anuncioScore.evaluar(p, mediana);
            sumaScore += (int) eval.get("score");
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("id", p.get("id"));
            it.put("titulo", p.getOrDefault("name", p.getOrDefault("address", "Inmueble")));
            it.put("ciudad", p.get("city"));
            it.put("foto", p.get("featured_photo"));
            it.put("eval", eval);
            items.add(it);
        }
        // Peor score primero (lo que más urge optimizar)
        items.sort(Comparator.comparingInt(a -> (int) ((Map<?, ?>) a.get("eval")).get("score")));

        int media = props.isEmpty() ? 0 : (int) (sumaScore / props.size());
        long buenos = items.stream().filter(i -> (int) ((Map<?, ?>) i.get("eval")).get("score") >= 80).count();
        long flojos = items.stream().filter(i -> (int) ((Map<?, ?>) i.get("eval")).get("score") < 50).count();

        model.addAttribute("items", items);
        model.addAttribute("media", media);
        model.addAttribute("buenos", buenos);
        model.addAttribute("flojos", flojos);
        model.addAttribute("total", props.size());
        return "optimizador";
    }

    @PostMapping("/optimizador/{id}/consejo")
    @ResponseBody
    public Map<String, Object> consejo(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return Map.of("ok", false);

        Map<String, Object> p = rows.get(0);
        List<Map<String, Object>> cartera = supabase.selectFilter("propiedades", "user_id=eq." + uid + "&limit=300");
        double mediana = anuncioScore.medianaEurM2(cartera);
        Map<String, Object> eval = anuncioScore.evaluar(p, mediana);
        String consejo = anuncioScore.consejoIA(p, eval, uid);
        return Map.of("ok", consejo != null, "consejo", consejo == null ? "No se pudo generar el consejo." : consejo);
    }
}
