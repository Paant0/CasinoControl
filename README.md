# CasinoControl

Prototipo académico para registrar y hacer seguimiento a fallas de máquinas electrónicas.

## Ejecutar la aplicación completa en local

Requisitos:
- Java 21
- Maven

Desde la carpeta raíz del proyecto:

```bash
mvn compile exec:java
```

Abre en el navegador:

```text
http://127.0.0.1:8000
```

El servidor Java entrega la interfaz desde `src/main/resources/static/` y expone estas rutas:

- `GET /api/fallas`
- `POST /api/fallas`
- `POST /api/fallas/{id}/tecnico`
- `PATCH /api/fallas/{id}`

Los reportes se guardan en `datos_casinocontrol.json` en la carpeta desde la que se ejecuta Maven.

## Publicar solo la interfaz con GitHub Pages

GitHub Pages puede publicar los archivos estáticos de `docs/`:

- `docs/index.html`
- `docs/styles.css`

En GitHub: **Settings → Pages → Deploy from a branch → main → /docs**.

Importante: GitHub Pages no ejecuta el servidor Java. Por eso la interfaz se verá, pero para registrar, consultar o actualizar reportes se necesita ejecutar el backend Java en un servidor. La página muestra un aviso claro cuando la API no está disponible.

## Archivos de interfaz

Se mantienen sincronizadas las dos copias:
- `docs/`: publicación estática de GitHub Pages.
- `src/main/resources/static/`: interfaz servida por el backend Java.

Si modificas el diseño, aplica los cambios en ambas ubicaciones.
