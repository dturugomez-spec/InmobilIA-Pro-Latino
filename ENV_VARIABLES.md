# 🔑 Variables de entorno · InmobilIA Pro

Configurar en Render → Environment.

## 🔴 OBLIGATORIAS (sin esto no arranca)

```
SUPABASE_URL=https://xxxxx.supabase.co
SUPABASE_SERVICE_KEY=eyJhbGciOiJIUzI1NiIs...
BASE_URL=https://inmobilia-pro.onrender.com
JWT_SECRET=cualquier-string-random-largo-aqui
```

## 🟠 RECOMENDADAS (para funciones IA y emails)

```
# OpenAI (chatbot, lead scoring, descripciones, resúmenes)
OPENAI_API_KEY=sk-proj-...

# Email (Brevo gratis 300/día — alternativa: Resend, SendGrid)
SMTP_HOST=smtp-relay.brevo.com
SMTP_PORT=587
SMTP_USER=tu@email.com
SMTP_PASS=tu-clave-smtp
SMTP_FROM_NAME=InmobilIA Pro
SMTP_FROM_EMAIL=noreply@tudominio.com
```

## 🟡 OPCIONALES (según integraciones que uses)

```
# Stripe (suscripciones de tu SaaS)
STRIPE_PUBLIC_KEY=pk_live_...
STRIPE_SECRET_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...
STRIPE_PRICE_PRO=price_...
STRIPE_PRICE_AGENCY=price_...

# WhatsApp Business Cloud (Meta)
# Se configura por usuario en /whatsapp, no necesita var global

# Twilio SMS
# Se configura por usuario en /sms

# Google Calendar
GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-xxxx

# Catastro (no necesita key, es API pública)

# Storage para PDFs/fotos (alternativa al storage de Supabase)
# Si usas Supabase Storage, no hace falta
```

## ⚙️ TÉCNICAS

```
# Spring profile
SPRING_PROFILES_ACTIVE=production

# Puerto (Render lo asigna automáticamente)
PORT=8080

# Timezone
TZ=America/Mexico_City
APP_ZONA_HORARIA=America/Mexico_City
```

---

## 🔒 SEGURIDAD

- **NUNCA subas estas variables a GitHub** (verifica que `.env` esté en `.gitignore`)
- El `SUPABASE_SERVICE_KEY` tiene permisos de admin sobre tu DB → trátalo como contraseña
- Rota los secretos cada 6 meses

---

## ✅ Checklist final antes de deploy

- [ ] `SUPABASE_URL` y `SUPABASE_SERVICE_KEY` configuradas
- [ ] `BASE_URL` apunta a tu dominio Render real
- [ ] `JWT_SECRET` es un string largo único (no uses el ejemplo)
- [ ] SMTP funcionando (test: envía visita y verifica email)
- [ ] (Si IA) `OPENAI_API_KEY` con saldo

Cuando todo verde → deploy y probar smoke test (ver `DEPLOY.md`).
