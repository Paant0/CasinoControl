package com.casinocontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;

public class CasinoControlServer {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8000;
    private static final String[] ESTADOS = {
        "Pendiente", "En diagnóstico", "En reparación", "Solucionada"
    };

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object LOCK = new Object();
    private static final Path DATA_FILE = Path.of("datos_casinocontrol.json");
    private static final Path STATIC_DIR = Path.of("src/main/resources/static");

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
        server.createContext("/", CasinoControlServer::handleRequest);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("CasinoControl está funcionando.");
        System.out.println("Abre en tu navegador: http://" + HOST + ":" + PORT);
        System.out.println("Para detener el servidor, presiona Ctrl + C.");
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            if ("GET".equals(method) && "/api/fallas".equals(path)) {
                synchronized (LOCK) {
                    ArrayNode data = loadData();
                    sendJson(exchange, 200, object("fallas", addElapsedHours(data)));
                }
                return;
            }

            if ("GET".equals(method)) {
                serveStaticFile(exchange, path);
                return;
            }

            if ("POST".equals(method)) {
                handlePost(exchange, path);
                return;
            }

            if ("PATCH".equals(method)) {
                handlePatch(exchange, path);
                return;
            }

            sendJson(exchange, 405, object("ok", false, "mensaje", "Método no permitido."));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, object("ok", false, "mensaje", "Error interno del servidor."));
        } finally {
            exchange.close();
        }
    }

    private static void serveStaticFile(HttpExchange exchange, String requestPath) throws IOException {
        String fileName;
        if ("/".equals(requestPath) || "/index.html".equals(requestPath)) {
            fileName = "index.html";
        } else if ("/styles.css".equals(requestPath)) {
            fileName = "styles.css";
        } else {
            sendJson(exchange, 404, object("ok", false, "mensaje", "Recurso no encontrado."));
            return;
        }

        Path file = STATIC_DIR.resolve(fileName).normalize();
        if (!Files.exists(file)) {
            sendJson(exchange, 404, object("ok", false,
                "mensaje", "No se encontró " + fileName + " en " + STATIC_DIR));
            return;
        }

        byte[] content = Files.readAllBytes(file);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "index.html".equals(fileName)
            ? "text/html; charset=utf-8" : "text/css; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
    }

    private static void handlePost(HttpExchange exchange, String path) throws IOException {
        JsonNode body = readJson(exchange);

        synchronized (LOCK) {
            ArrayNode fallas = loadData();

            if ("/api/fallas".equals(path)) {
                String machine = text(body, "codigo_maquina").trim().toUpperCase();
                String description = text(body, "descripcion").trim();
                String operator = text(body, "operador").trim();

                if (machine.isEmpty() || description.isEmpty() || operator.isEmpty()) {
                    sendJson(exchange, 400, object("ok", false,
                        "mensaje", "Completa el código de máquina, la descripción y el operador.",
                        "falla", null));
                    return;
                }

                for (JsonNode failure : fallas) {
                    boolean sameMachine = text(failure, "codigo_maquina").equalsIgnoreCase(machine);
                    boolean sameDescription = text(failure, "descripcion").equalsIgnoreCase(description);
                    boolean active = !"Solucionada".equals(text(failure, "estado"));
                    if (sameMachine && sameDescription && active) {
                        sendJson(exchange, 400, object("ok", false,
                            "mensaje", "Ya existe una falla igual que todavía no está solucionada.",
                            "falla", null));
                        return;
                    }
                }

                ObjectNode newFailure = JSON.createObjectNode();
                newFailure.put("id", generateId(fallas));
                newFailure.put("codigo_maquina", machine);
                newFailure.put("descripcion", description);
                newFailure.put("fecha_hora", OffsetDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                newFailure.put("operador", operator);
                newFailure.put("tecnico", "Sin asignar");
                newFailure.put("estado", "Pendiente");
                newFailure.put("diagnostico", "Sin registrar");
                newFailure.put("reparacion", "Sin registrar");

                fallas.add(newFailure);
                saveData(fallas);
                sendJson(exchange, 201, object("ok", true,
                    "mensaje", "Reporte " + newFailure.get("id").asText() + " registrado correctamente.",
                    "falla", newFailure));
                return;
            }

            String[] parts = path.split("/");
            if (parts.length == 5 && "api".equals(parts[1])
                    && "fallas".equals(parts[2]) && "tecnico".equals(parts[4])) {
                ObjectNode failure = findFailure(fallas, parts[3]);
                String technician = text(body, "tecnico").trim();

                if (failure == null) {
                    sendJson(exchange, 400, object("ok", false, "mensaje", "No se encontró ese reporte."));
                    return;
                }
                if ("Solucionada".equals(failure.path("estado").asText())) {
                    sendJson(exchange, 400, object("ok", false, "mensaje", "La falla ya está solucionada."));
                    return;
                }
                if (technician.isEmpty()) {
                    sendJson(exchange, 400, object("ok", false, "mensaje", "Escribe el nombre del técnico."));
                    return;
                }

                failure.put("tecnico", technician);
                if ("Pendiente".equals(failure.path("estado").asText())) {
                    failure.put("estado", "En diagnóstico");
                }
                saveData(fallas);
                sendJson(exchange, 200, object("ok", true,
                    "mensaje", "Técnico asignado al reporte " + failure.path("id").asText() + "."));
                return;
            }
        }

        sendJson(exchange, 404, object("ok", false, "mensaje", "Ruta no encontrada."));
    }

    private static void handlePatch(HttpExchange exchange, String path) throws IOException {
        String[] parts = path.split("/");
        if (parts.length != 4 || !"api".equals(parts[1]) || !"fallas".equals(parts[2])) {
            sendJson(exchange, 404, object("ok", false, "mensaje", "Ruta no encontrada."));
            return;
        }

        JsonNode body = readJson(exchange);
        synchronized (LOCK) {
            ArrayNode fallas = loadData();
            ObjectNode failure = findFailure(fallas, parts[3]);

            if (failure == null) {
                sendJson(exchange, 400, object("ok", false, "mensaje", "No se encontró ese reporte."));
                return;
            }

            String state = text(body, "estado");
            boolean valid = false;
            for (String allowed : ESTADOS) {
                if (allowed.equals(state)) valid = true;
            }
            if (!valid) {
                sendJson(exchange, 400, object("ok", false, "mensaje", "Selecciona un estado válido."));
                return;
            }
            if ("Solucionada".equals(state)
                    && "Sin asignar".equals(failure.path("tecnico").asText())) {
                sendJson(exchange, 400, object("ok", false,
                    "mensaje", "Asigna un técnico antes de cerrar la falla como solucionada."));
                return;
            }

            String diagnosis = text(body, "diagnostico").trim();
            String repair = text(body, "reparacion").trim();
            if (!diagnosis.isEmpty()) failure.put("diagnostico", diagnosis);
            if (!repair.isEmpty()) failure.put("reparacion", repair);
            failure.put("estado", state);

            saveData(fallas);
            sendJson(exchange, 200, object("ok", true,
                "mensaje", "Reporte " + failure.path("id").asText() + " actualizado."));
        }
    }

    private static ArrayNode loadData() {
        try {
            if (!Files.exists(DATA_FILE)) return JSON.createArrayNode();
            JsonNode data = JSON.readTree(DATA_FILE.toFile());
            return data != null && data.isArray() ? (ArrayNode) data : JSON.createArrayNode();
        } catch (IOException e) {
            return JSON.createArrayNode();
        }
    }

    private static void saveData(ArrayNode failures) throws IOException {
        Path temp = DATA_FILE.resolveSibling("datos_casinocontrol.tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), failures);
        Files.move(temp, DATA_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String generateId(ArrayNode failures) {
        int largest = 0;
        for (JsonNode failure : failures) {
            String id = text(failure, "id");
            try {
                largest = Math.max(largest, Integer.parseInt(id.split("-")[1]));
            } catch (Exception ignored) {
                // Ignora identificadores con formato inesperado.
            }
        }
        return String.format("F-%03d", largest + 1);
    }

    private static ObjectNode findFailure(ArrayNode failures, String id) {
        for (JsonNode node : failures) {
            if (node instanceof ObjectNode failure
                    && text(failure, "id").equalsIgnoreCase(id)) {
                return failure;
            }
        }
        return null;
    }

    private static ArrayNode addElapsedHours(ArrayNode failures) {
        ArrayNode result = JSON.createArrayNode();
        for (JsonNode node : failures) {
            ObjectNode copy = node.deepCopy();
            try {
                OffsetDateTime date = java.time.LocalDateTime
                    .parse(text(node, "fecha_hora"),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(ZoneId.systemDefault()).toOffsetDateTime();
                double hours = Math.max(0, Duration.between(date, OffsetDateTime.now()).toMinutes() / 60.0);
                copy.put("horas_transcurridas", Math.round(hours * 10.0) / 10.0);
            } catch (Exception e) {
                copy.put("horas_transcurridas", 0);
            }
            result.add(copy);
        }
        return result;
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(100_001);
        if (bytes.length == 0 || bytes.length > 100_000) return JSON.createObjectNode();
        try {
            return JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static ObjectNode object(Object... values) {
        ObjectNode result = JSON.createObjectNode();
        for (int i = 0; i < values.length; i += 2) {
            String key = (String) values[i];
            Object value = values[i + 1];
            if (value == null) result.putNull(key);
            else if (value instanceof String s) result.put(key, s);
            else if (value instanceof Boolean b) result.put(key, b);
            else if (value instanceof JsonNode n) result.set(key, n);
            else result.putPOJO(key, value);
        }
        return result;
    }

    private static void sendJson(HttpExchange exchange, int status, JsonNode data) throws IOException {
        byte[] response = JSON.writeValueAsBytes(data);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
    }
}
