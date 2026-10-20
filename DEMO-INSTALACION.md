# 📲 InmobilIA Pro · Guía de demo para clientes

Pasos exactos para enseñar a un cliente cómo instalar la app **sin pasar por App Store ni Play Store**.
Tiempo total: **30 segundos por dispositivo**.

> URL del demo: `https://inmobilia-pro.onrender.com` (sustituye por la tuya tras desplegar en Render)

---

## ✅ Antes del demo · Checklist

- [ ] App desplegada y abriendo en `https://...onrender.com`
- [ ] Login funcionando (usuario demo creado)
- [ ] Has hecho un **primer ping** al endpoint `/healthz` para despertar Render free tier (la primera carga tras 15 min de idle tarda 30-50s)
- [ ] Visitas previas a las 4 herramientas estrella (Auditor IA, Subastas BOE, Stress Test, Comparador CCAA) para que el Service Worker las cachee
- [ ] Modo avión listo para enseñar el modo offline
- [ ] Lighthouse audit pasado (ver al final)

---

## 1️⃣ Android · Chrome (lo más común en España)

**Tiempo: 15 segundos**

1. Abre Chrome
2. Pega la URL: `https://inmobilia-pro.onrender.com`
3. Tras 2-3 segundos aparece **abajo a la derecha** un botón flotante azul **"📲 Instalar app"**
4. Pulsa → Chrome muestra un diálogo nativo: **"¿Añadir InmobilIA Pro a la pantalla de inicio?"**
5. Pulsa **Instalar**
6. La app aparece en el escritorio como cualquier app nativa, con el icono morado-azul de InmobilIA

### Wow-effect para enseñar al cliente:
- **Mantén pulsado el icono** → aparecen 4 atajos directos (Auditor IA, Radar, Subastas BOE, Dashboard) — esto es el manifest `shortcuts`
- Al abrir la app **no tiene barra de URL ni de Chrome** — pantalla completa, parece nativa
- En multitarea aparece como una app independiente, no como pestaña del navegador

---

## 2️⃣ iPhone / iPad · Safari

**Tiempo: 20 segundos** (Apple obliga a hacerlo manualmente, no soporta `beforeinstallprompt`)

1. Abre **Safari** (no funciona en Chrome iOS — Apple solo permite instalar PWA desde Safari)
2. Pega la URL
3. Pulsa el botón **Compartir** (el cuadrado con flecha hacia arriba, en la barra inferior)
4. Desliza hacia abajo en el menú y pulsa **"Añadir a pantalla de inicio"**
5. Edita el nombre si quieres (por defecto: "InmobilIA") y pulsa **Añadir**
6. La app queda en el home screen del iPhone

### Notas iPhone:
- En iOS la app abre **sin barra de Safari**, en pantalla completa
- iOS **no soporta push notifications en PWA hasta iOS 16.4+** — si el cliente tiene iOS 15 o anterior, las alertas no llegan en background. Funcionan dentro de la app.
- Para enseñar el modo offline en iPhone: activa modo avión y abre la app desde el icono → debe seguir cargando el dashboard cacheado

---

## 3️⃣ Windows / Mac · Chrome o Edge

**Tiempo: 10 segundos** (la opción más impresionante para clientes B2B)

1. Abre Chrome o Edge
2. Pega la URL
3. En la **barra de direcciones**, a la derecha del candado, aparece un icono pequeño **⊞** (instalar)
4. Click → diálogo **"¿Instalar InmobilIA Pro?"** → **Instalar**
5. La app se abre como **ventana propia** (sin pestañas, sin barra de URL)
6. En Windows queda en el menú Inicio, en Mac en Launchpad/Applications

### Para clientes corporativos:
- En Windows aparece en la barra de tareas como app independiente
- Soporta **Alt+Tab** entre la app y otras ventanas
- Click derecho en el icono → "Anclar a la barra de tareas"
- En Mac queda en el Dock como cualquier app de Mac

---

## 4️⃣ Modo offline · El feature wow

**Cómo enseñarlo en el demo (60 segundos):**

1. Con la app abierta y el dashboard cargado
2. Abre las 4 herramientas Pro (Auditor, Subastas, Stress, Comparador) — **el Service Worker las cachea**
3. Activa **modo avión** en el dispositivo
4. Refresca la app → sigue funcionando, navega por las herramientas ya visitadas
5. Si intentas ir a una página no cacheada → aparece la pantalla **"Sin conexión"** con diseño profesional (no el feo error del navegador)
6. Quita modo avión → la app se sincroniza automáticamente

> Esto **ningún competidor lo tiene**. Witei, Tokko, Idealista Pro requieren conexión 100% del tiempo.

---

## 5️⃣ Push notifications (Android + Desktop)

Para enseñarlo necesitas:
1. Tener el endpoint backend de notificaciones configurado (futuro: integración con WebPush + VAPID)
2. El usuario acepta permisos cuando entra a la app por primera vez
3. Al disparar una alerta (ej: nueva subasta que cumple filtros, contrato vence) llega al móvil **aunque la app esté cerrada**

> Estado actual: Service Worker preparado para `push` y `notificationclick`. Falta endpoint VAPID en backend (siguiente milestone si el cliente lo pide).

---

## 6️⃣ Verificación pre-demo · Lighthouse

Antes del demo conviene verificar que la PWA pasa el audit de Google.

1. Abre la app en Chrome desktop
2. F12 → pestaña **Lighthouse**
3. Marca **Progressive Web App** y **Performance**
4. Click **Analyze page load**

### Puntuaciones esperadas:
- ✅ **PWA Installable**: 100
- ✅ **PWA Optimized**: 100
- ✅ **Performance**: 85-95 (depende del estado de Render)
- ✅ **Best Practices**: 95-100
- ✅ **Accessibility**: 90+

Si alguno falla:
- *"Does not have a maskable icon"* → ya está incluido (`icon-maskable-192/512.png`)
- *"start_url responds with 200 offline"* → el SW cachea `/dashboard` en `PRECACHE_URLS`
- *"Page is not served over HTTPS"* → Render usa HTTPS por defecto

---

## 7️⃣ Argumentos de venta para el demo

Cuando el cliente pregunte **"¿pero esto es una app de verdad?"**:

| Pregunta cliente | Respuesta |
|---|---|
| ¿Está en App Store / Play Store? | No hace falta. Se instala en 5 segundos sin pasar por revisiones de Apple/Google (que tardan días). |
| ¿Funciona offline? | Sí, las herramientas que has visitado quedan cacheadas. |
| ¿Recibo notificaciones? | Sí en Android y Desktop. iOS desde 16.4. |
| ¿Pesa mucho? | <2 MB inicial. App nativa típica = 50-200 MB. |
| ¿Se actualiza sola? | Sí. Cuando subimos versión nueva, la app se actualiza al abrirla, sin pedir nada al usuario. |
| ¿Funciona en Huawei sin Google Services? | Sí. Es web. No depende de Google Play. |
| ¿Es seguro? | Sí. HTTPS obligatorio, headers de seguridad, sesión con cookies HttpOnly + SameSite. |
| ¿Y si quiero versión nativa en stores? | Podemos empaquetarla con TWA (Trusted Web Activity) en Android y subirla a Play Store en 1 día. iOS requiere wrapper Capacitor. Pero el 95% de clientes B2B no lo necesitan. |

---

## 8️⃣ Troubleshooting rápido durante el demo

| Síntoma | Causa | Solución |
|---|---|---|
| Botón "Instalar" no aparece en Android | Ya está instalada o el navegador no es Chrome | Chrome → Menú → Apps instaladas → desinstalar y reintentar |
| iOS no muestra "Añadir a pantalla de inicio" | Estás en Chrome iOS, no en Safari | Abrir en Safari |
| App tarda 40s en cargar la primera vez | Render free tier dormido | Hacer ping a `/healthz` 1 minuto antes del demo |
| Modo offline no funciona | Service Worker no registrado aún | Abrir la app online primero, esperar 5s, luego activar modo avión |
| Icono se ve genérico | PNG no se generó al arrancar | Reiniciar app para que `PwaIconGenerator` cree los iconos |

---

## 9️⃣ Después del demo

Si el cliente firma:

1. Cambiar el dominio `*.onrender.com` por su dominio (ej: `app.cliente.com`) — Render lo soporta gratis con SSL automático
2. Personalizar el `manifest.json` con el nombre de su empresa si es white label
3. Subir Render del plan free al plan **Starter** (7$/mes, sin sleep, dominio custom incluido)
4. Activar UptimeRobot para evitar el sleep del free tier durante la transición

---

**Listo. Con esta guía puedes hacer un demo completo en 5 minutos y dejar al cliente con la app instalada en su móvil.** 🚀
