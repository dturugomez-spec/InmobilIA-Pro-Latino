# 🚀 InmobilIA Pro · Guía de Despliegue Final

**110 sprints completados** · Listo para producción.

---

## 📋 Checklist visual

- [ ] 1. Crear cuenta Supabase (gratis)
- [ ] 2. Ejecutar `ALL_MIGRATIONS.sql` en Supabase SQL Editor
- [ ] 3. Subir código a GitHub
- [ ] 4. Conectar Render → repositorio GitHub
- [ ] 5. Configurar variables de entorno en Render
- [ ] 6. Primer deploy (5-10 min)
- [ ] 7. Smoke test 7 flujos críticos
- [ ] 8. (Opcional) Integraciones externas
- [ ] 9. ¡A vender!

---

## 1️⃣ Supabase (5 min)

1. Ve a https://supabase.com → **New Project**
2. Apunta:
   - `SUPABASE_URL` (Settings → API)
   - `SUPABASE_SERVICE_KEY` (Settings → API → `service_role` ⚠️ secret)
3. SQL Editor → pega `ALL_MIGRATIONS.sql` completo → **Run**
4. Authentication → Email → desactivar "Confirm email" (gestionamos verificación)

---

## 2️⃣ GitHub (5 min)

```bash
cd "C:\Users\Jose Daniel\Desktop\github-LOTES"
git init
git add .
git commit -m "Initial commit - InmobilIA Pro v1.0 (110 sprints)"

# Crear repo en https://github.com/new (privado)
git remote add origin https://github.com/TU_USUARIO/inmobilia-pro.git
git branch -M main
git push -u origin main
```

**Alternativa sin Git CLI**: ve a https://github.dev/TU_USUARIO/inmobilia-pro y arrastra carpetas.

---

## 3️⃣ Render (10 min)

1. https://render.com → **New +** → **Web Service**
2. Connect repository → selecciona tu repo
3. Configuración:
   - **Name**: `inmobilia-pro`
   - **Region**: Frankfurt
   - **Branch**: `main`
   - **Runtime**: Docker (auto) o **Native** Java 21
   - **Build Command**: `./mvnw clean package -DskipTests`
   - **Start Command**: `java -jar target/inmobilia-*.jar`
   - **Plan**: Free (subir a Starter $7/mes cuando facturas)

4. **Environment** → añade las variables (ver `ENV_VARIABLES.md`)

5. **Create Web Service** → espera build (5-10 min primer deploy)

---

## 4️⃣ Smoke test (10 min)

Una vez deployado, prueba estos 7 flujos:

1. **Registro** → crea cuenta `admin@test.com`
2. **Crear propiedad** → con foto + precio + descripción
3. **Crear lead** → nombre + email + presupuesto
4. **Matching engine** → debería sugerir matches automáticos
5. **Programar visita** → debería llegar email confirmación
6. **Generar contrato** desde plantilla → ver variables rellenadas
7. **Firma electrónica** → enviar a `tu@email.com` y firmar

Si los 7 funcionan → **listo para clientes reales**.

---

## 5️⃣ Integraciones (opcional, según necesites)

### Stripe (suscripciones)
- https://dashboard.stripe.com → API Keys
- Crear webhook → URL: `https://tudominio.com/api/stripe-webhook`
- Eventos: `checkout.session.completed`, `customer.subscription.*`

### WhatsApp Business
- https://business.facebook.com → App → WhatsApp → API Setup
- Obtén: Phone Number ID + Access Token permanente
- Verifica webhook: `https://tudominio.com/whatsapp/webhook`

### Twilio (SMS)
- https://twilio.com → Account SID + Auth Token
- Comprar número español (≈€8/mes)

### Google Calendar OAuth
- https://console.cloud.google.com → APIs → OAuth Client ID
- Redirect URI: `https://tudominio.com/google-calendar/callback`

### OpenAI (chatbot, IA scoring, descripciones)
- https://platform.openai.com → API keys → crea key
- Carga $20 saldo inicial

### Portal feeds (Idealista, Fotocasa)
- Tras tener cuentas → enviarles las URLs:
  - `https://tudominio.com/portal/{token}/idealista.xml`
  - `https://tudominio.com/portal/{token}/fotocasa.xml`

---

## 6️⃣ Dominio propio (opcional)

1. Compra dominio (Namecheap, Gandi, IONOS)
2. Render → tu servicio → **Settings → Custom Domains**
3. Añade `inmobilia.tudominio.com`
4. Sigue instrucciones DNS (CNAME a Render)
5. Render genera SSL gratis automáticamente

---

## 7️⃣ Marketing inicial (ya con producto vivo)

- **Landing comercial**: usa el microsite (`/microsite/admin`) como demo
- **Modo demo**: dale a clientes `https://tudominio.com/demo` para que prueben sin registro
- **LinkedIn**: post explicando ventajas vs Witei/Sooprema
- **Email frío**: a inmobiliarias de tu ciudad
- **Pricing sugerido**:
  - Free: 5 propiedades, 50 leads
  - Pro: €39/mes - 100 propiedades, ilimitado
  - Agency: €79/mes - todo + multi-usuario
  - **Te queda margen vs Witei (€89) y Sooprema (€99)**

---

## 🆘 Troubleshooting

**Build falla en Render**: Verifica versión Java 21 en `pom.xml`
**"Connection refused" Supabase**: revisa que `SUPABASE_URL` no tenga espacios/tabs
**Emails no llegan**: configura SMTP (Brevo gratis 300/día, Resend, SendGrid)
**WhatsApp webhook 403**: revisa `webhook_verify_token` igual en ambos lados

---

## 🎯 Lo que tienes ahora

- **110 sprints** completados
- **42 migraciones SQL** unificadas
- **~110 controllers Java**
- **~190 templates Thymeleaf**
- **~35 servicios** (AI, Email, WhatsApp, SMS, feeds para portales...)
- **3 idiomas** (ES/EN/CA)
- **App móvil** Capacitor
- **API REST pública** v2 con OpenAPI
- **27+ ventajas** sobre Witei/Sooprema/Idealista CRM

**¡A facturar! 💰**
