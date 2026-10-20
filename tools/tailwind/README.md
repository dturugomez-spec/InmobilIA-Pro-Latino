# Estilos compilados de las páginas públicas

Las páginas públicas (portada, funcionalidades, login, legales, blog, calculadoras,
fichas de pisos…) usan `src/main/resources/static/css/tw.css`, generado con Tailwind,
en lugar del script `cdn.tailwindcss.com` (que el navegador descargaba y ejecutaba en
cada visita: ~125 KB de JS frente a ~15 KB de CSS comprimido).

**Railway no compila estilos.** Si añades o cambias clases de Tailwind en una plantilla,
regenera el archivo desde la raíz del proyecto:

```bash
npx tailwindcss@3 -c tools/tailwind/tailwind.config.js -i tools/tailwind/input.css -o src/main/resources/static/css/tw.css --minify
```

Después cambia el `?v=` de `<link href="/css/tw.css?v=...">` en las plantillas (o
ejecuta el mismo script de sustitución) para que los navegadores no usen la copia en caché.

La parte interna de la app (`fragments/layout.html` y las plantillas que la usan)
sigue con el CDN por ahora.
