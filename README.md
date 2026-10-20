# 🏠 InmobilIA Pro

> CRM/SaaS inmobiliario completo en español. **110 sprints** completados.
> Superior a Witei (€89/mes) y Sooprema (€99/mes) en funcionalidades.

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

### ⚖️ Cumplimiento legal español
- **Verifactu** (RD 1007/2023) con cadena hash AEAT
- **Firma electrónica eIDAS** (Ley 6/2020) con OTP + auditoría blockchain
- 10 modelos AEAT: 100, 130, 180, 184, 190, 200, 303, 347, 390, 111
- LAU, RGPD, calendario fiscal con alertas

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
- Newsletter RGPD
- Inbox IMAP

### 🌐 Web pública SEO
- Microsite inmobiliaria
- Página propiedad con Schema.org
- Landing promociones obra nueva
- Tours virtuales 360°
- Reservas online con Stripe

### 💰 Finanzas
- Verifactu facturas
- SEPA Direct Debit (pain.008)
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
