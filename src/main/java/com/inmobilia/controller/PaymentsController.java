package com.inmobilia.controller;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * Control de pagos mensual con semáforo (verde / amarillo / rojo).
 * Cruza inquilinos con transacciones de tipo Ingreso por mes.
 */
@Controller
@RequestMapping("/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentsController {

    private final SupabaseService supabase;
    private final PermissionService permissions;

    @GetMapping
    public String index(@RequestParam(required = false) Integer year, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "transacciones") && !permissions.canRead(role, "inquilinos")) {
            return "redirect:/dashboard";
        }

        String uid = SessionUtils.uid(session);
        int currentYear = year != null ? year : LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();

        var inquilinos = supabase.query("inquilinos", uid).stream()
                .filter(i -> {
                    String s = String.valueOf(i.getOrDefault("status", ""));
                    return !"Inactivo".equalsIgnoreCase(s) && !"Cerrado".equalsIgnoreCase(s);
                })
                .toList();

        var ingresos = supabase.query("transacciones", uid).stream()
                .filter(t -> "Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", ""))))
                .toList();

        // Construir matriz: por inquilino, array de 12 estados (1=pagado, 0=pendiente, -1=atrasado)
        List<Map<String, Object>> matriz = new ArrayList<>();
        double totalEsperadoAnual = 0;
        double totalCobradoAnual  = 0;
        int totalPagados   = 0;
        int totalPendientes = 0;
        int totalAtrasados = 0;

        for (var inq : inquilinos) {
            String inqName = String.valueOf(inq.getOrDefault("name", ""));
            String propName = String.valueOf(inq.getOrDefault("property_name", ""));
            double rentaMensual = parseDouble(inq.get("rent_amount"));

            // Buscar fecha de inicio del contrato (lease_start) para no marcar pagos antes
            String leaseStartStr = String.valueOf(inq.getOrDefault("lease_start", ""));
            LocalDate leaseStart = parseDate(leaseStartStr);

            int[] estados = new int[12];     // 1=pagado verde, 0=pendiente actual amarillo, -1=atrasado rojo, -2=futuro/no aplicable
            double[] cobradoMes = new double[12];

            for (int m = 0; m < 12; m++) {
                int monthNum = m + 1;
                YearMonth ym = YearMonth.of(currentYear, monthNum);

                // Si el contrato no había empezado en ese mes, marcar como N/A
                if (leaseStart != null && leaseStart.isAfter(ym.atEndOfMonth())) {
                    estados[m] = -2;
                    continue;
                }
                // Mes futuro respecto al actual del año en curso
                if (currentYear == LocalDate.now().getYear() && monthNum > currentMonth) {
                    estados[m] = -2;
                    continue;
                }

                // Buscar transacciones de ese mes que coincidan con el inquilino o su propiedad
                double cobrado = 0;
                for (var t : ingresos) {
                    String dateStr = String.valueOf(t.getOrDefault("date", ""));
                    LocalDate d = parseDate(dateStr);
                    if (d == null) continue;
                    if (d.getYear() != currentYear) continue;
                    if (d.getMonthValue() != monthNum) continue;
                    String tProp = String.valueOf(t.getOrDefault("property_name", ""));
                    String tDesc = String.valueOf(t.getOrDefault("description", "")).toLowerCase();
                    boolean match = (!propName.isBlank() && propName.equalsIgnoreCase(tProp))
                                 || (!inqName.isBlank() && tDesc.contains(inqName.toLowerCase()));
                    if (match) cobrado += parseDouble(t.get("amount"));
                }
                cobradoMes[m] = cobrado;

                if (rentaMensual <= 0 || cobrado >= rentaMensual * 0.95) {
                    estados[m] = 1;  // pagado
                    totalPagados++;
                } else if (currentYear == LocalDate.now().getYear() && monthNum == currentMonth) {
                    estados[m] = 0;  // pendiente este mes
                    totalPendientes++;
                } else {
                    estados[m] = -1; // atrasado (mes pasado sin pagar completo)
                    totalAtrasados++;
                }

                if (estados[m] != -2) {
                    totalEsperadoAnual += rentaMensual;
                    totalCobradoAnual  += cobrado;
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", inq.get("id"));
            row.put("name", inqName);
            row.put("property", propName);
            row.put("rent", rentaMensual);
            row.put("estados", estados);
            row.put("cobrado", cobradoMes);
            matriz.add(row);
        }

        model.addAttribute("page", "Control de cobros · " + currentYear);
        model.addAttribute("year", currentYear);
        model.addAttribute("yearAnt", currentYear - 1);
        model.addAttribute("yearSig", currentYear + 1);
        model.addAttribute("matriz", matriz);
        model.addAttribute("totalEsperado", totalEsperadoAnual);
        model.addAttribute("totalCobrado", totalCobradoAnual);
        model.addAttribute("totalPendiente", totalEsperadoAnual - totalCobradoAnual);
        model.addAttribute("totalPagados", totalPagados);
        model.addAttribute("totalAtrasados", totalAtrasados);
        model.addAttribute("totalPendientesMes", totalPendientes);
        model.addAttribute("canWrite", permissions.canWrite(role, "transacciones"));
        return "payments";
    }

    /** Marcar un mes como cobrado: crea transacción de ingreso. */
    @PostMapping("/mark")
    @ResponseBody
    public Map<String, Object> mark(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false, "error", "Sesión expirada");
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return Map.of("ok", false, "error", "Sin permisos");

        String uid = SessionUtils.uid(session);
        try {
            String tenant = String.valueOf(body.getOrDefault("tenant", ""));
            String property = String.valueOf(body.getOrDefault("property", ""));
            int year = Integer.parseInt(String.valueOf(body.getOrDefault("year", LocalDate.now().getYear())));
            int month = Integer.parseInt(String.valueOf(body.getOrDefault("month", LocalDate.now().getMonthValue())));
            double amount = parseDouble(body.get("amount"));

            String date = String.format("%04d-%02d-05", year, month); // día 5 del mes por defecto

            Map<String, Object> tx = new LinkedHashMap<>();
            tx.put("user_id", uid);
            tx.put("type", "Ingreso");
            tx.put("category", "Alquiler");
            tx.put("amount", amount);
            tx.put("date", date);
            tx.put("property_name", property);
            tx.put("description", "Cobro alquiler · " + tenant);
            tx.put("created_at", OffsetDateTime.now().toString());

            Map<String, Object> res = supabase.insert("transacciones", tx);
            if (res == null || res.isEmpty()) {
                return Map.of("ok", false, "error", "No se pudo crear la transacción");
            }
            return Map.of("ok", true);
        } catch (Exception e) {
            log.error("payments mark error: {}", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private static double parseDouble(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return null;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); } catch (Exception e) { return null; }
    }
}
