# 🏠 InmobilIA Pro · Latinoamérica

> CRM/SaaS inmobiliario completo en español de Latinoamérica. Versión multipaís derivada de
> InmobilIA Pro (España): cada inmobiliaria elige su país y la app se adapta.

## 🌎 Qué cambia respecto a la versión de España

- **País por inmobiliaria** (`users.pais`, en Configuración y en la solicitud de cuenta): 17 países
  (México, Colombia, Argentina, Chile, Perú, Ecuador, Uruguay, Paraguay, Bolivia, Venezuela, Costa Rica,
  Panamá, Guatemala, Honduras, El Salvador, Nicaragua y República Dominicana). Catálogo en `util/Pais.java`.
- **Moneda**: todos los importes salen con la moneda del país (MXN, COP, ARS, CLP, PEN…) o en USD si la
  inmobiliaria lo elige (`users.moneda`). Separadores de miles y decimales del país.
- **Impuesto y documentos**: IVA/IGV/ITBIS/ITBMS/ISV con su tipo general; RFC, NIT, CUIT, RUT, RUC, cédula…
- **Prefijo telefónico, zona horaria y ley de protección de datos** de cada país.
- **Valoraciones** con precios de referencia por m² de ciudades latinoamericanas (`util/PreciosReferencia.java`,
  en USD y convertidos a la moneda de la inmobiliaria).
- **Fuera** las herramientas que solo servían en España: Verifactu/AEAT, Catastro, subastas BOE, Euríbor,
  comparador de CCAA, IRPF, plusvalía, ITP/AJD, calendario fiscal AEAT, SEPA, Holded, certificado
  energético y el blog de fiscalidad española.
- **Portales**: un feed XML genérico (`/portal/{token}/feed.xml`) con moneda y país, en lugar de los
  formatos de Idealista, Fotocasa, Habitaclia y Pisos.com.
- **Facturación**: control de cobros con el impuesto del país. La factura electrónica con validez fiscal
  (CFDI, DIAN, ARCA, SII, SUNAT…) se timbra en el sistema autorizado de cada país.
- **Planes en USD** (US$78 / US$198). Hay que crear los precios en dólares en Stripe.

Migración de esquema: `src/main/resources/db/migration/V100__pais_latam.sql` (también al final de
`FRESH_INSTALL_SUPABASE.sql`).

---

## 🚀 Inicio rápido

1. Lee `DEPLOY.md` — guía paso a paso
2. Configura `ENV_VARIABLES.md` — variables de entorno
3. Ejecuta `ALL_MIGRATIONS.sql` en Supabase
4. Push a GitHub → Render auto-deploy

---

## ✨ Características destacadas (resumen)

### 🤖 Inteligencia Artificial
- AI Lead Scoring (0-100) + temperatura HOT/Caliente/Tibio/Frío
- Chatbot AI 24/7 embebible
- Generador descripciones SEO
- Resúmenes inteligentes de leads
- Reconocimiento habitaciones por foto
- Notas de voz con transcripción

### 🌎 Adaptado a cada país
- Moneda local o USD, impuesto e identificaciones fiscales del país
- Firma electrónica simple con OTP y registro de evidencias
- Contratos de arrendamiento y encargo orientativos (para revisar con un abogado local)
- Textos legales con la ley de protección de datos de cada país

### 💼 CRM completo
- 7 roles + permisos granulares (25+ flags)
- Pipeline Kanban drag&drop
- Cartera 360° unificada
- Workflow automation tipo Zapier
- AI Lead Scoring

### 💬 Comunicación
- WhatsApp Business Cloud API
- SMS gateway (Twilio)
- Email campaigns con A/B testing
- Email builder visual drag&drop
- Newsletter con doble opt-in
- Inbox IMAP

### 🌐 Web pública SEO
- Microsite inmobiliaria
- Página propiedad con Schema.org
- Landing promociones obra nueva
- Tours virtuales 360°
- Reservas online con Stripe

### 💰 Finanzas
- Facturación / control de cobros con el impuesto del país
- Conciliación bancaria CSV
- Caja chica + gastos agente
- Extractos automáticos propietarios
- Multi-divisa BCE
- Co-broking MLS

### 🔗 Integraciones
- Idealista, Fotocasa, Habitaclia, Pisos.com (feeds XML)
- Google Calendar OAuth
- Holded / A3 / Sage
- Catastro API
- Stripe
- OpenAI

---

## 📦 Stack

| Capa | Tech |
|------|------|
| Backend | Spring Boot 3.3 · Java 21 |
| DB | Supabase (PostgreSQL) |
| Frontend | Thymeleaf · Tailwind CSS · Alpine.js · HTMX |
| Auth | BCrypt + Sessions + 2FA TOTP |
| IA | OpenAI GPT-4o |
| Email | SMTP (Brevo/Resend/SendGrid) |
| Pagos | Stripe |
| Deploy | Render |
| Mobile | Capacitor (Android/iOS) |

---

## 📂 Estructura

```
inmobilia-pro/
├── src/main/java/com/inmobilia/
│   ├── controller/        # ~110 controllers
│   ├── service/           # ~35 servicios
│   ├── config/            # I18n, security
│   └── util/
├── src/main/resources/
│   ├── templates/         # ~190 templates
│   ├── static/            # CSS, JS, PWA
│   ├── db/migration/      # 42 SQLs Supabase
│   └── messages_*.properties  # ES, EN, CA
├── mobile-app/            # Capacitor wrapper
├── ALL_MIGRATIONS.sql     # SQL unificado
├── DEPLOY.md              # 👈 EMPIEZA AQUÍ
├── ENV_VARIABLES.md
└── ROUTES_SIDEBAR.md
```

---

## 🆚 vs competencia

| Feature | InmobilIA Pro | Witei | Sooprema |
|---------|:-:|:-:|:-:|
| Precio sugerido | **€39-79/mes** | €89/mes | €99/mes |
| Verifactu | ✅ | ❌ | ❌ |
| Firma eIDAS | ✅ | ❌ | ❌ |
| AI Lead Scoring | ✅ | ❌ | ❌ |
| Chatbot AI 24/7 | ✅ | ❌ | ⚠️ |
| Workflows Zapier | ✅ | ⚠️ | ⚠️ |
| Co-broking MLS | ✅ | ❌ | ❌ |
| SEPA cobros | ✅ | ❌ | ❌ |
| Multi-divisa | ✅ | ❌ | ❌ |
| Notas de voz | ✅ | ❌ | ❌ |
| Tours 360° + Staging IA | ✅ | ⚠️ | ✅ |

**27+ ventajas competitivas únicas.**

---

## 📞 Soporte

Issues: GitHub Issues
Email: soporte@inmobilia-pro.com

---

## 📜 Licencia

Proprietary - © 2026 InmobilIA Pro
