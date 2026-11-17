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
