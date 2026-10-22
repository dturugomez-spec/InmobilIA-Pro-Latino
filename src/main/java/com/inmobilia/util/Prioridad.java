package com.inmobilia.util;

/**
 * Prioridad de tareas y mantenimiento. En la base de datos es un número (1 baja, 2 media,
 * 3 alta, 4 urgente), pero hay filas antiguas con texto ("Alta", "Urgente"): se aceptan ambas.
 * Antes el panel hacía (String) priority y con un número se caía entero.
 */
public final class Prioridad {

    private Prioridad() { }

    public static int nivel(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        String s = String.valueOf(v).trim().toLowerCase();
        return switch (s) {
            case "urgente" -> 4;
            case "alta" -> 3;
            case "media" -> 2;
            case "baja" -> 1;
            default -> {
                try { yield (int) Double.parseDouble(s); } catch (NumberFormatException e) { yield 0; }
            }
        };
    }

    public static boolean esAlta(Object v) { return nivel(v) >= 3; }

    public static boolean esUrgente(Object v) { return nivel(v) >= 4; }
}
