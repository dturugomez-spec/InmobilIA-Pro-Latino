package com.inmobilia.util;

/**
 * Dirección pública de la app para los enlaces que salen en emails y páginas (valoración,
 * portales, firmas, campañas…): APP_BASE_URL, si no BASE_URL y, por defecto, con www.
 * Antes cada clase leía BASE_URL con "https://inmobiliapro.es" (sin www) por defecto, y esa
 * dirección sin www no siempre resuelve ni tiene certificado.
 */
public final class UrlBase {
    private UrlBase() {}

    public static final String POR_DEFECTO = "https://www.inmobiliapro.es";
    public static final String BASE = calcular(System.getenv("APP_BASE_URL"), System.getenv("BASE_URL"));

    static String calcular(String appBaseUrl, String baseUrl) {
        String v = appBaseUrl != null && !appBaseUrl.isBlank() ? appBaseUrl
                : baseUrl != null && !baseUrl.isBlank() ? baseUrl : POR_DEFECTO;
        v = v.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
