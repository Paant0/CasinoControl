package com.casinocontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CasinoControlServer {

    static final int PUERTO = 8000;
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path ARCHIVO = Path.of("datos_casinocontrol.json");
    static final Path WEB = Path.of("src/main/resources/static");

    public static void main(String[] args) throws IOException {
        HttpServer servidor = HttpServer.create(
                new InetSocketAddress("127.0.0.1", PUERTO), 0);

        servidor.createContext("/", CasinoControlServer::atender);
        servidor.start();

        System.out.println("CasinoControl funcionando en http://127.0.0.1:8000");
    }

    // Recibe las solicitudes de la página y llama a la función correspondiente.
    static void atender(HttpExchange solicitud) throws IOException {
        String metodo = solicitud.getRequestMethod();
        String ruta = solicitud.getRequestURI().getPath();

        try {
            if (ruta.equals("/api/fallas") && metodo.equals("GET")) {
                consultarFallas(solicitud);
            } else if (ruta.equals("/api/fallas") && metodo.equals("POST")) {
                registrarFallas(solicitud);
            } else if (ruta.matches("/api/fallas/[^/]+/tecnico") && metodo.equals("POST")) {
                asignarTecnico(solicitud, ruta);
            } else if (ruta.matches("/api/fallas/[^/]+") && metodo.equals("PATCH")) {
                actualizarFalla(solicitud, ruta);
            } else if (metodo.equals("GET")) {
                mostrarPagina(solicitud, ruta);
            } else {
                responder(solicitud, 404, mensaje("Opción no encontrada."));
            }
        } catch (Exception e) {
            responder(solicitud, 500, mensaje("Ocurrió un error."));
        } finally {
            solicitud.close();
        }
    }

    // Muestra el HTML y el CSS.
    static void mostrarPagina(HttpExchange solicitud, String ruta) throws IOException {
        String nombre = ruta.equals("/styles.css") ? "styles.css" : "index.html";
        Path archivo = WEB.resolve(nombre);

        if (!Files.exists(archivo)) {
            responder(solicitud, 404, mensaje("Archivo no encontrado."));
            return;
        }

        byte[] contenido = Files.readAllBytes(archivo);
        String tipo = nombre.endsWith(".css")
                ? "text/css; charset=utf-8"
                : "text/html; charset=utf-8";

        solicitud.getResponseHeaders().set("Content-Type", tipo);
        solicitud.sendResponseHeaders(200, contenido.length);
        solicitud.getResponseBody().write(contenido);
    }

    // Muestra los reportes guardados.
    static void consultarFallas(HttpExchange solicitud) throws IOException {
        ArrayNode fallas = leerFallas();

        for (JsonNode dato : fallas) {
            ObjectNode falla = (ObjectNode) dato;
            String fecha = falla.path("fecha_hora").asText();

            try {
                LocalDateTime registrada = LocalDateTime.parse(
                        fecha, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                double horas = Duration.between(registrada, LocalDateTime.now()).toMinutes() / 60.0;
                falla.put("horas_transcurridas", Math.max(0, Math.round(horas * 10.0) / 10.0));
            } catch (Exception e) {
                falla.put("horas_transcurridas", 0);
            }
        }

        ObjectNode respuesta = JSON.createObjectNode();
        respuesta.set("fallas", fallas);
        responder(solicitud, 200, respuesta);
    }

    // Registra un reporte si tiene los tres datos obligatorios.
    static void registrarFallas(HttpExchange solicitud) throws IOException {
        JsonNode datos = JSON.readTree(solicitud.getRequestBody());
        ArrayNode reportes = JSON.createArrayNode();
        reportes.add(datos);

        ArrayNode fallas = leerFallas();
        int registrados = 0;
        int rechazados = 0;

        for (JsonNode reporte : reportes) {
            String maquina = texto(reporte, "codigo_maquina").trim();
            String descripcion = texto(reporte, "descripcion").trim();
            String operador = texto(reporte, "operador").trim();

            if (!maquina.isEmpty() && !descripcion.isEmpty() && !operador.isEmpty()) {
                ObjectNode falla = JSON.createObjectNode();
                falla.put("id", "F-" + (fallas.size() + 1));
                falla.put("codigo_maquina", maquina);
                falla.put("descripcion", descripcion);
                falla.put("fecha_hora", LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                falla.put("operador", operador);
                falla.put("tecnico", "Sin asignar");
                falla.put("estado", "Pendiente");
                falla.put("diagnostico", "Sin registrar");
                falla.put("reparacion", "Sin registrar");

                fallas.add(falla);
                registrados++;
            } else {
                rechazados++;
            }
        }

        guardarFallas(fallas);

        ObjectNode respuesta = JSON.createObjectNode();
        respuesta.put("ok", rechazados == 0);
        respuesta.put("registrados", registrados);
        respuesta.put("rechazados", rechazados);
        respuesta.put("mensaje", rechazados == 0
                ? "Reporte registrado correctamente."
                : "Completa los datos obligatorios.");

        responder(solicitud, rechazados == 0 ? 201 : 400, respuesta);
    }

    // Asigna un técnico a un reporte.
    static void asignarTecnico(HttpExchange solicitud, String ruta) throws IOException {
        String id = ruta.split("/")[3];
        JsonNode datos = JSON.readTree(solicitud.getRequestBody());
        String tecnico = texto(datos, "tecnico").trim();

        ArrayNode fallas = leerFallas();
        ObjectNode falla = buscarFalla(fallas, id);

        if (falla == null || tecnico.isEmpty()) {
            responder(solicitud, 400, mensaje("Revisa el reporte y el nombre del técnico."));
            return;
        }

        falla.put("tecnico", tecnico);
        if (falla.path("estado").asText().equals("Pendiente")) {
            falla.put("estado", "En diagnóstico");
        }

        guardarFallas(fallas);
        responder(solicitud, 200, mensaje("Técnico asignado correctamente."));
    }

    // Actualiza el diagnóstico, la reparación y el estado.
    static void actualizarFalla(HttpExchange solicitud, String ruta) throws IOException {
        String id = ruta.substring("/api/fallas/".length());
        JsonNode datos = JSON.readTree(solicitud.getRequestBody());

        ArrayNode fallas = leerFallas();
        ObjectNode falla = buscarFalla(fallas, id);

        if (falla == null) {
            responder(solicitud, 404, mensaje("Reporte no encontrado."));
            return;
        }

        String estado = texto(datos, "estado");
        String tecnico = falla.path("tecnico").asText();

        if (estado.equals("Solucionada") && tecnico.equals("Sin asignar")) {
            responder(solicitud, 400, mensaje("Primero asigna un técnico."));
            return;
        }

        falla.put("estado", estado);
        falla.put("diagnostico", texto(datos, "diagnostico"));
        falla.put("reparacion", texto(datos, "reparacion"));

        guardarFallas(fallas);
        responder(solicitud, 200, mensaje("Reporte actualizado correctamente."));
    }

    // Lee los reportes del archivo JSON.
    static ArrayNode leerFallas() throws IOException {
        if (!Files.exists(ARCHIVO)) {
            return JSON.createArrayNode();
        }
        JsonNode datos = JSON.readTree(ARCHIVO.toFile());
        return datos != null && datos.isArray()
                ? (ArrayNode) datos
                : JSON.createArrayNode();
    }

    // Guarda los reportes en el archivo JSON.
    static void guardarFallas(ArrayNode fallas) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(ARCHIVO.toFile(), fallas);
    }

    // Busca un reporte por su código.
    static ObjectNode buscarFalla(ArrayNode fallas, String id) {
        for (JsonNode dato : fallas) {
            if (dato.path("id").asText().equalsIgnoreCase(id)) {
                return (ObjectNode) dato;
            }
        }
        return null;
    }

    static String texto(JsonNode datos, String campo) {
        JsonNode valor = datos.get(campo);
        return valor == null ? "" : valor.asText();
    }

    static ObjectNode mensaje(String texto) {
        ObjectNode respuesta = JSON.createObjectNode();
        respuesta.put("ok", true);
        respuesta.put("mensaje", texto);
        return respuesta;
    }

    static void responder(HttpExchange solicitud, int codigo, JsonNode datos) throws IOException {
        byte[] contenido = JSON.writeValueAsBytes(datos);
        solicitud.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        solicitud.sendResponseHeaders(codigo, contenido.length);
        solicitud.getResponseBody().write(contenido);
    }
}
