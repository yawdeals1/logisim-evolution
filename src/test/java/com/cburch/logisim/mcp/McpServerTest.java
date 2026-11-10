/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.data.BitWidth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class McpServerTest {

  @Test
  public void jsonRoundTrip() {
    final var text = "{\"a\":1,\"b\":[true,false,null,2.5,\"x\\n\\u00e9\"],\"c\":{}}";
    final var parsed = Json.parse(text);
    assertEquals(text.replace("\\u00e9", "é"), Json.write(parsed));
    final var map = (Map<?, ?>) parsed;
    assertEquals(1L, map.get("a"));
    assertEquals(2.5, ((List<?>) map.get("b")).get(3));
  }

  @Test
  public void originCheck() {
    assertTrue(McpServer.originAllowed(null));
    assertTrue(McpServer.originAllowed("http://localhost:3000"));
    assertTrue(McpServer.originAllowed("http://127.0.0.1"));
    assertFalse(McpServer.originAllowed("https://evil.example.com"));
  }

  @Test
  public void valueParsing() throws Exception {
    final var w8 = BitWidth.create(8);
    assertEquals(10L, McpTools.parseValue("0b1010", w8).toLongValue());
    assertEquals(31L, McpTools.parseValue("0x1f", w8).toLongValue());
    assertEquals(7L, McpTools.parseValue(7L, w8).toLongValue());
    assertFalse(McpTools.parseValue("x", w8).isFullyDefined());
  }

  @Test
  public void toolDefinitionsAreWellFormed() {
    final var names = new HashSet<String>();
    for (final var def : new McpTools().definitions()) {
      final var m = (Map<?, ?>) def;
      final var name = (String) m.get("name");
      assertTrue(name.matches("[a-z_]+"), name);
      assertTrue(names.add(name), "duplicate " + name);
      assertEquals("object", ((Map<?, ?>) m.get("inputSchema")).get("type"));
      // Must survive serialisation.
      Json.parse(Json.write(m));
    }
    assertTrue(names.contains("get_circuit"));
    assertTrue(names.contains("simulate"));
  }

  @Test
  public void errorShape() {
    final var err = McpServer.error(5L, -32601, "nope");
    assertEquals("2.0", err.get("jsonrpc"));
    assertEquals(5L, err.get("id"));
    assertNull(err.get("result"));
  }
}
