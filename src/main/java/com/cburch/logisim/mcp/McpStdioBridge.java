/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Bridges an MCP client that speaks the stdio transport to the HTTP server embedded in a running
 * Logisim-evolution window.
 *
 * <pre>
 * java -cp logisim-evolution-all.jar com.cburch.logisim.mcp.McpStdioBridge \
 *      [--url http://127.0.0.1:7531/mcp] [--token TOKEN]
 * </pre>
 *
 * <p>The token may also be supplied via the {@code LOGISIM_MCP_TOKEN} environment variable and the
 * URL via {@code LOGISIM_MCP_URL}.
 */
public final class McpStdioBridge {

  private McpStdioBridge() {}

  public static void main(String[] args) throws Exception {
    var url = envOr("LOGISIM_MCP_URL", "http://127.0.0.1:7531/mcp");
    var token = envOr("LOGISIM_MCP_TOKEN", null);
    for (var i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--url" -> url = args[++i];
        case "--token" -> token = args[++i];
        case "--help", "-h" -> {
          System.err.println("Usage: McpStdioBridge [--url URL] [--token TOKEN]");
          return;
        }
        default -> {
          System.err.println("Unknown argument: " + args[i]);
          System.exit(2);
        }
      }
    }

    // stdout is reserved for protocol messages.
    final var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    final var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final var endpoint = URI.create(url);
    String session = null;

    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) continue;
      final var builder =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofMinutes(2))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .POST(HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8));
      if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
      if (session != null) builder.header("Mcp-Session-Id", session);
      try {
        final var resp =
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        final var sid = resp.headers().firstValue("Mcp-Session-Id");
        if (sid.isPresent()) session = sid.get();
        if (resp.statusCode() == 202) continue;
        if (resp.statusCode() == 200 && !resp.body().isBlank()) {
          out.println(resp.body().replace("\r", "").replace("\n", ""));
        } else {
          replyError(out, line, "Logisim MCP server returned HTTP " + resp.statusCode()
              + (resp.statusCode() == 401 ? " (check the token)" : ""));
        }
      } catch (java.io.IOException e) {
        replyError(out, line, "Cannot reach Logisim at " + url
            + ". Start Logisim and enable the agent connection (Help menu). " + e.getMessage());
      }
    }
  }

  private static void replyError(PrintStream out, String request, String message) {
    try {
      final var parsed = Json.parse(request);
      final List<?> msgs = parsed instanceof List<?> l ? l : List.of(parsed);
      for (final var m : msgs) {
        if (m instanceof Map<?, ?> map && map.containsKey("id") && map.containsKey("method")) {
          out.println(Json.write(error(map.get("id"), -32000, message)));
        }
      }
    } catch (IllegalArgumentException e) {
      out.println(Json.write(error(null, -32700, "Parse error")));
    }
  }

  private static Map<String, Object> error(Object id, int code, String message) {
    return Json.obj(
        "jsonrpc", "2.0", "id", id, "error", Json.obj("code", code, "message", message));
  }

  private static String envOr(String name, String dflt) {
    final var v = System.getenv(name);
    return v == null || v.isBlank() ? dflt : v;
  }
}
