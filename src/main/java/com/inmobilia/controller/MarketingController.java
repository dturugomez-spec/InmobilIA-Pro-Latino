package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class MarketingController {

    private final SupabaseService supabase;

    @org.springframework.beans.factory.annotation.Value("${app.base-url:}")
    private String baseUrl;

    @GetMapping("/changelog")
    public String changelog() { return "marketing/changelog"; }

    @GetMapping("/roadmap")
    public String roadmap() { return "marketing/roadmap"; }

    @GetMapping("/para-agencias")
    public String paraAgencias() { return "marketing/para_agencias"; }

    @GetMapping("/para-asset-managers")
    public String paraAssetManagers() { return "marketing/para_asset_managers"; }

    @GetMapping("/para-particulares")
    public String paraParticulares() { return "marketing/para_particulares"; }

    @GetMapping("/sitemap.xml")
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<String> sitemap() {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
           .append(urlEntry("/"))
           .append(urlEntry("/descubre"))
           .append(urlEntry("/privacy"))
           .append(urlEntry("/terms"))
           .append(urlEntry("/cookies"))
           .append(urlEntry("/changelog"))
           .append(urlEntry("/roadmap"))
           .append(urlEntry("/para-agencias"))
           .append(urlEntry("/para-asset-managers"))
           .append(urlEntry("/para-particulares"))
           .append(urlEntry("/calculadora-impuestos-compra"))
           .append(urlEntry("/tasacion"))
           .append(urlEntry("/status"));

        // Fichas públicas de propiedades: que Google/Bing las descubran e indexen
        // (la plantilla pública ya lleva og:image + Schema.org RealEstateListing).
        try {
            List<Map<String, Object>> props = supabase.selectFilter(
                    "propiedades", "order=created_at.desc&limit=5000");
            for (Map<String, Object> p : props) {
                Object id = p.get("id");
                if (id != null) xml.append(urlEntry("/propiedad/" + id));
            }
        } catch (Exception ignored) { /* si falla la BD, el sitemap sigue con las estáticas */ }

        xml.append("</urlset>");
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "application/xml; charset=UTF-8").body(xml.toString());
    }

    @GetMapping("/robots.txt")
    @org.springframework.web.bind.annotation.ResponseBody
    public String robots() {
        return "User-agent: *\nAllow: /\nDisallow: /api/\nDisallow: /admin/\nDisallow: /portal/\nSitemap: "
                + PublicPropertyPageController.publicBase(baseUrl) + "/sitemap.xml\n";
    }

    private String urlEntry(String path) {
        return "  <url><loc>" + PublicPropertyPageController.publicBase(baseUrl) + path + "</loc></url>\n";
    }
}
