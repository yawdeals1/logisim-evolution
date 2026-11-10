/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.prefs.AppPreferences;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded Model Context Protocol server (Streamable HTTP transport, JSON responses).
 *
 * <p>Listens on the loopback interface only. Clients POST JSON-RPC 2.0 messages to {@code
 * http://127.0.0.1:<port>/mcp}. When token protection is enabled, requests must carry {@code
 * Authorization: Bearer <token>}. Browser-originated requests from non-local origins are refused
 * to prevent DNS-rebinding attacks.
 */
public final class McpServer {

  static final Logger logger = LoggerFactory.getLogger(McpServer.class);

  public static final String PATH = "/mcp";
  static final String SERVER_NAME = "logisim-evolution";
  private static final List<String> SUPPORTED_PROTOCOLS =
      List.of("2025-06-18", "2025-03-26", "2024-11-05");
  private static final int MAX_BODY = 16 * 1024 * 1024;

  private static McpServer instance;

  private final int port;
  private final String token;
  private final boolean requireToken;
  private final McpTools tools = new McpTools();
  private HttpServer http;
  private ExecutorService executor;

  private McpServer(int port, String token, boolean requireToken) {
    this.port = port;
    this.token = token;
    this.requireToken = requireToken;
  }

  // ------------------------------------------------------------ lifecycle (static facade)

  /** Starts the server using saved preferences, if the user enabled it. Never throws. */
  public static void startIfEnabled() {
    if (!AppPreferences.MCP_ENABLED.getBoolean()) return;
    try {
      restart();
    } catch (Exception e) {
      logger.warn("Could not start MCP server: {}", e.getMessage());
    }
  }

  /** (Re)starts the server with the currently saved preferences. */
  public static synchronized void restart() throws IOException {
    stop();
    final var server =
        new McpServer(
            AppPreferences.MCP_PORT.get(),
            getOrCreateToken(),
            AppPreferences.MCP_REQUIRE_TOKEN.getBoolean());
    server.start();
    instance = server;
  }

  public static synchronized void stop() {
    if (instance != null) {
      instance.shutdown();
      instance = null;
    }
  }

  public static synchronized boolean isRunning() {
    return instance != null;
  }

  public static synchronized int getRunningPort() {
    return instance == null ? -1 : instance.port;
  }

  public static String getOrCreateToken() {
    var t = AppPreferences.MCP_TOKEN.get();
    if (t == null || t.isBlank()) {
      t = generateToken();
      AppPreferences.MCP_TOKEN.set(t);
    }
    return t;
  }

  public static String generateToken() {
    final var bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String endpointUrl(int port) {
    return "http://127.0.0.1:" + port + PATH;
  }

  // ------------------------------------------------------------ HTTP handling

  private void start() throws IOException {
    http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    executor =
        Executors.newFixedThreadPool(
            4,
            r -> {
              final var t = new Thread(r, "mcp-server");
              t.setDaemon(true);
              return t;
            });
    http.setExecutor(executor);
    http.createContext(PATH, this::handle);
    http.start();
    logger.info("MCP server listening on {}", endpointUrl(port));
  }

  private void shutdown() {
    if (http != null) http.stop(0);
    if (executor != null) executor.shutdownNow();
  }

  private void handle(HttpExchange ex) throws IOException {
    try {
      if (!originAllowed(ex.getRequestHeaders().getFirst("Origin"))) {
        send(ex, 403, "text/plain", "Forbidden origin");
        return;
      }
      if (requireToken
          && !authorized(ex.getRequestHeaders().getFirst("Authorization"),
              ex.getRequestURI().getRawQuery())) {
        ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"logisim-mcp\"");
        send(ex, 401, "text/plain", "Missing or invalid bearer token");
        return;
      }
      switch (ex.getRequestMethod()) {
        case "POST" -> handlePost(ex);
        case "DELETE" -> send(ex, 200, "text/plain", "");
        case "OPTIONS" -> {
          ex.getResponseHeaders().add("Allow", "POST, DELETE, OPTIONS");
          send(ex, 204, null, null);
        }
        default -> {
          // We do not offer a server-initiated SSE stream; spec allows 405 here.
          ex.getResponseHeaders().add("Allow", "POST, DELETE, OPTIONS");
          send(ex, 405, "text/plain", "Method not allowed");
        }
      }
    } catch (Exception e) {
      logger.warn("MCP request failed", e);
      try {
        send(ex, 500, "text/plain", "Internal error");
      } catch (IOException ignored) {
        // connection already gone
      }
    } finally {
      ex.close();
    }
  }

  private void handlePost(HttpExchange ex) throws IOException {
    final var bodyBytes = ex.getRequestBody().readNBytes(MAX_BODY + 1);
    if (bodyBytes.length > MAX_BODY) {
      send(ex, 413, "text/plain", "Request too large");
      return;
    }
    final Object message;
    try {
      message = Json.parse(new String(bodyBytes, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      sendJson(ex, error(null, -32700, "Parse error: " + e.getMessage()));
      return;
    }

    final var sessionHeader = new String[] {null};
    Object reply;
    if (message instanceof List<?> batch) {
      final var replies = new ArrayList<Object>();
      for (final var m : batch) {
        final var r = dispatch(m, sessionHeader);
        if (r != null) replies.add(r);
      }
      reply = replies.isEmpty() ? null : replies;
    } else {
      reply = dispatch(message, sessionHeader);
    }
    if (sessionHeader[0] != null) {
      ex.getResponseHeaders().add("Mcp-Session-Id", sessionHeader[0]);
    }
    if (reply == null) {
      send(ex, 202, null, null);
    } else {
      sendJson(ex, reply);
    }
  }

  /** Handles one JSON-RPC message; returns the response object, or null for notifications. */
  Object dispatch(Object msg, String[] sessionHeader) {
    if (!(msg instanceof Map<?, ?> m)) return error(null, -32600, "Invalid request");
    final var id = m.get("id");
    final var method = m.get("method");
    if (!(method instanceof String name)) {
      // A response from the client (we never send requests) -- ignore.
      return null;
    }
    final var isNotification = !m.containsKey("id");
    final var params = m.get("params") instanceof Map<?, ?> p ? p : Map.of();
    try {
      final Object result =
          switch (name) {
            case "initialize" -> {
              sessionHeader[0] = UUID.randomUUID().toString();
              yield initialize(params);
            }
            case "ping" -> Map.of();
            case "tools/list" -> Json.obj("tools", tools.definitions());
            case "tools/call" -> tools.call(params);
            case "resources/list" -> Json.obj("resources", List.of());
            case "resources/templates/list" -> Json.obj("resourceTemplates", List.of());
            case "prompts/list" -> Json.obj("prompts", List.of());
            case "logging/setLevel" -> Map.of();
            default -> {
              if (name.startsWith("notifications/")) yield null;
              throw new RpcException(-32601, "Method not found: " + name);
            }
          };
      if (isNotification) return null;
      return Json.obj("jsonrpc", "2.0", "id", id, "result", result);
    } catch (RpcException e) {
      return isNotification ? null : error(id, e.code, e.getMessage());
    } catch (Exception e) {
      logger.warn("MCP method {} failed", name, e);
      return isNotification ? null : error(id, -32603, "Internal error: " + e);
    }
  }

  private Map<String, Object> initialize(Map<?, ?> params) {
    final var requested = params.get("protocolVersion");
    final var version =
        requested instanceof String r && SUPPORTED_PROTOCOLS.contains(r)
            ? r
            : SUPPORTED_PROTOCOLS.get(0);
    return Json.obj(
        "protocolVersion", version,
        "capabilities", Json.obj("tools", Json.obj("listChanged", false)),
        "serverInfo", Json.obj("name", SERVER_NAME, "version", BuildInfo.version.toString()),
        "instructions", McpTools.INSTRUCTIONS);
  }

  static Map<String, Object> error(Object id, int code, String message) {
    return Json.obj(
        "jsonrpc", "2.0", "id", id, "error", Json.obj("code", code, "message", message));
  }

  static final class RpcException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int code;

    RpcException(int code, String message) {
      super(message);
      this.code = code;
    }
  }

  // ------------------------------------------------------------ security helpers

  static boolean originAllowed(String origin) {
    if (origin == null || origin.isBlank() || "null".equals(origin)) return true;
    try {
      final var host = URI.create(origin).getHost();
      return host != null
          && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
              || host.equals("::1"));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private boolean authorized(String header, String query) {
    String given = null;
    if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      given = header.substring(7).trim();
    } else if (query != null) {
      // For clients that cannot send headers (e.g. ChatGPT connectors): /mcp?token=...
      for (final var part : query.split("&")) {
        if (part.startsWith("token=")) {
          given = URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8);
        }
      }
    }
    if (given == null) return false;
    return MessageDigest.isEqual(
        given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------ IO helpers

  private static void sendJson(HttpExchange ex, Object body) throws IOException {
    send(ex, 200, "application/json", Json.write(body));
  }

  private static void send(HttpExchange ex, int status, String type, String body)
      throws IOException {
    if (body == null || status == 204) {
      ex.sendResponseHeaders(status, -1);
      return;
    }
    final var bytes = body.getBytes(StandardCharsets.UTF_8);
    if (type != null) ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
    ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (var os = ex.getResponseBody()) {
        os.write(bytes);
      }
    }
  }
}
