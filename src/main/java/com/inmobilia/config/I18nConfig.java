package com.inmobilia.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Locale;

@Configuration
public class I18nConfig implements WebMvcConfigurer {

    private final PaywallInterceptor paywallInterceptor;
    private final SubscriptionGateInterceptor subscriptionGate;
    private final SessionGuardInterceptor sessionGuard;

    public I18nConfig(PaywallInterceptor paywallInterceptor, SubscriptionGateInterceptor subscriptionGate,
                      SessionGuardInterceptor sessionGuard) {
        this.paywallInterceptor = paywallInterceptor;
        this.subscriptionGate = subscriptionGate;
        this.sessionGuard = sessionGuard;
    }

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource src = new ResourceBundleMessageSource();
        src.setBasename("messages");
        src.setDefaultEncoding("UTF-8");
        src.setUseCodeAsDefaultMessage(true);
        return src;
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("LANG");
        resolver.setDefaultLocale(new Locale("es", "ES"));
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor i = new LocaleChangeInterceptor();
        i.setParamName("lang");
        return i;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
        // Guardián de sesión (cuenta activa, inquilinos, 2FA obligatorio, permisos por rol).
        // Va antes que el candado de suscripción: primero quién eres, luego si has pagado.
        // Incluye /api/**: sin sesión no hace nada (la API pública va con clave), pero con sesión
        // aplica los permisos (antes /api/search o /api/ai se saltaban el rol y el portal del inquilino).
        registry.addInterceptor(sessionGuard).addPathPatterns("/**").excludePathPatterns(
                "/css/**", "/js/**", "/icons/**", "/brand/**",
                "/favicon.ico", "/favicon.svg", "/manifest.json", "/sw.js",
                "/robots.txt", "/sitemap.xml", "/stripe/webhook");
        // Candado de suscripción: las secciones de la app exigen estar suscrito.
        // NO incluye /upgrade, /billing, /login, /logout, /config, /account, /welcome,
        // páginas públicas, /api ni estáticos → nunca hay bucle ni bloqueo del pago.
        registry.addInterceptor(subscriptionGate).addPathPatterns(
                "/dashboard", "/dashboard/**",
                "/crud/**", "/agente", "/agente/**", "/autopiloto", "/autopiloto/**",
                "/kanban/**", "/pipelines/**", "/leads", "/leads/**", "/oportunidades",
                "/bandeja", "/embudo", "/calendario", "/campanas", "/campanas/**", "/plantillas",
                "/reports/**", "/invoices/**", "/receipts/**", "/commissions/**", "/integrations/**",
                "/analytics", "/business", "/captacion", "/captacion/**", "/coach", "/optimizador",
                "/informes", "/impacto", "/valoraciones", "/equipo", "/equipo/**",
                "/cee/**", "/reviews/**", "/nota-encargo/**", "/newsletter/**",
                "/gastos-agente/**", "/busquedas-guardadas/**", "/creditos",
                "/preferencias", "/preferencias/**", "/herramientas/**",
                "/propiedades/mapa", "/trash", "/team/**");
        // Paywall: solo en las rutas de features premium
        registry.addInterceptor(paywallInterceptor).addPathPatterns(
                "/integrations", "/integrations/**",
                "/invoices", "/invoices/**",
                "/receipts", "/receipts/**",
                "/reports", "/reports/**",
                "/kanban", "/kanban/**",
                "/pipelines", "/pipelines/**");
    }
}
