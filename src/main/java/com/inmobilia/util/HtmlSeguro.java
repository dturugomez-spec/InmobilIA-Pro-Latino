package com.inmobilia.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * Limpia el HTML que la app muestra tal cual (th:utext / x-html): emails recibidos en la bandeja,
 * documentos a firmar, campañas y plantillas. Deja el formato (negritas, tablas, imágenes, colores)
 * y quita lo que ejecuta código: &lt;script&gt;, onclick/onerror, enlaces javascript:, iframes, formularios.
 */
public final class HtmlSeguro {
    private HtmlSeguro() {}

    private static final Safelist PERMITIDO = Safelist.relaxed()
            .addTags("center", "font", "hr", "small", "strike", "s", "mark")
            .addAttributes(":all", "style", "align", "valign", "width", "height", "bgcolor", "dir")
            .addAttributes("table", "border", "cellpadding", "cellspacing")
            .addAttributes("font", "color", "face", "size")
            .addProtocols("img", "src", "http", "https", "data", "cid")
            .preserveRelativeLinks(true);

    private static final Document.OutputSettings SALIDA = new Document.OutputSettings().prettyPrint(false);

    public static String limpiar(String html) {
        if (html == null || html.isBlank()) return html;
        return Jsoup.clean(html, UrlBase.BASE, PERMITIDO, SALIDA);
    }

    /** Texto plano para meter dentro de HTML (p. ej. datos de un lead en una plantilla). */
    public static String escapar(String texto) {
        if (texto == null) return "";
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
