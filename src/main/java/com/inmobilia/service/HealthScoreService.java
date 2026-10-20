package com.inmobilia.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Health Score 0-100 con 5 dimensiones de 20 puntos (equivalente a compute_health_score del Python).
 */
@Service
public class HealthScoreService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    public record HealthResult(int score, Map<String, Integer> dimensions, List<String> insights, boolean available) {}

    public HealthResult compute(List<Map<String, Object>> props,
                                 List<Map<String, Object>> tenants,
                                 List<Map<String, Object>> contratos,
                                 List<Map<String, Object>> mantenimiento,
                                 List<Map<String, Object>> tareas) {
        // Sin datos no tiene sentido puntuar: devolvemos N/A
        if (props.isEmpty() && tenants.isEmpty() && contratos.isEmpty()
                && mantenimiento.isEmpty() && tareas.isEmpty()) {
            return new HealthResult(0, Map.of(), List.of(), false);
        }
        List<String> insights = new ArrayList<>();
        int np = props.size();

        // 1. Ocupacion
        long nOcc = props.stream().filter(p -> "Ocupado".equals(p.get("status"))).count();
        double occRate = np > 0 ? nOcc * 100.0 / np : 0;
        int occScore = Math.min(20, (int) (occRate / 5));
        if (occRate < 70) insights.add("Ocupacion baja (" + (int) occRate + "%): considera revisar precios o marketing.");

        // 2. Morosidad
        long nMor = tenants.stream().filter(t -> "Moroso".equals(t.get("status"))).count();
        int nTen = Math.max(1, tenants.size());
        double morRate = nMor * 100.0 / nTen;
        int morScore = Math.max(0, 20 - (int) (morRate * 4));
        if (nMor > 0) insights.add(nMor + " inquilino(s) en morosidad activa.");

        // 3. Contratos
        double contRisk = 0;
        LocalDate today = LocalDate.now();
        for (Map<String, Object> c : contratos) {
            try {
                String s = (String) c.get("end_date");
                if (s == null || s.isBlank()) continue;
                LocalDate ed = LocalDate.parse(s.substring(0, 10), ISO);
                long days = java.time.temporal.ChronoUnit.DAYS.between(today, ed);
                if (days < 0)       contRisk += 2;
                else if (days < 30) contRisk += 1.5;
                else if (days < 60) contRisk += 0.5;
            } catch (Exception ignored) {}
        }
        int contScore = Math.max(0, Math.min(20, 20 - (int) (contRisk * 2)));
        if (contRisk >= 3) insights.add("Varios contratos con vencimiento inminente o ya vencidos.");

        // 4. Mantenimiento
        List<Map<String, Object>> open = mantenimiento.stream()
                .filter(m -> {
                    String st = (String) m.get("status");
                    return !"Completado".equals(st) && !"Cerrado".equals(st);
                }).toList();
        List<Map<String, Object>> highPr = open.stream()
                .filter(m -> {
                    String p = (String) m.get("priority");
                    return "Alta".equals(p) || "Urgente".equals(p);
                }).toList();
        int maintScore = Math.max(0, Math.min(20, 20 - highPr.size() * 4 - open.size()));
        if (!highPr.isEmpty()) insights.add(highPr.size() + " ordenes de mantenimiento de alta prioridad pendientes.");

        // 5. Tareas
        List<Map<String, Object>> tasksOpen = tareas.stream()
                .filter(t -> !"Completada".equals(t.get("status"))).toList();
        int overdue = 0;
        for (Map<String, Object> t : tasksOpen) {
            try {
                String d = (String) t.get("due_date");
                if (d == null || d.isBlank()) continue;
                LocalDate dd = LocalDate.parse(d.substring(0, 10), ISO);
                if (dd.isBefore(today)) overdue++;
            } catch (Exception ignored) {}
        }
        int taskScore = Math.max(0, Math.min(20, 20 - overdue * 3 - Math.max(0, tasksOpen.size() - 5)));
        if (overdue > 0) insights.add(overdue + " tarea(s) vencida(s) sin completar.");

        int total = occScore + morScore + contScore + maintScore + taskScore;
        if (insights.isEmpty()) insights.add("Cartera en buen estado. Sigue asi.");

        return new HealthResult(total, Map.of(
                "ocupacion", occScore,
                "morosidad", morScore,
                "contratos", contScore,
                "mantenimiento", maintScore,
                "tareas", taskScore
        ), insights, true);
    }
}
