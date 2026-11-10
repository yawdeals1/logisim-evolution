/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader/writer used by the MCP server.
 *
 * <p>Objects become {@code Map<String, Object>} (insertion ordered), arrays become {@code
 * List<Object>}, numbers become {@code Long} or {@code Double}, and {@code null} becomes Java
 * {@code null}.
 */
public final class Json {

  private Json() {}

  // ---------------------------------------------------------------- parsing

  public static Object parse(String text) {
    final var p = new Parser(text);
    p.skipWs();
    final var value = p.readValue();
    p.skipWs();
    if (p.pos != text.length()) {
      throw new IllegalArgumentException("Trailing characters in JSON at position " + p.pos);
    }
    return value;
  }

  private static final class Parser {
    private final String s;
    private int pos;

    Parser(String s) {
      this.s = s;
    }

    void skipWs() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    IllegalArgumentException error(String msg) {
      return new IllegalArgumentException(msg + " at position " + pos);
    }

    Object readValue() {
      if (pos >= s.length()) throw error("Unexpected end of JSON");
      final var c = s.charAt(pos);
      switch (c) {
        case '{':
          return readObject();
        case '[':
          return readArray();
        case '"':
          return readString();
        case 't':
          expect("true");
          return Boolean.TRUE;
        case 'f':
          expect("false");
          return Boolean.FALSE;
        case 'n':
          expect("null");
          return null;
        default:
          if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
          throw error("Unexpected character '" + c + "'");
      }
    }

    void expect(String word) {
      if (!s.startsWith(word, pos)) throw error("Expected '" + word + "'");
      pos += word.length();
    }

    Map<String, Object> readObject() {
      final var map = new LinkedHashMap<String, Object>();
      pos++; // {
      skipWs();
      if (pos < s.length() && s.charAt(pos) == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWs();
        if (pos >= s.length() || s.charAt(pos) != '"') throw error("Expected object key");
        final var key = readString();
        skipWs();
        if (pos >= s.length() || s.charAt(pos) != ':') throw error("Expected ':'");
        pos++;
        skipWs();
        map.put(key, readValue());
        skipWs();
        if (pos >= s.length()) throw error("Unterminated object");
        final var c = s.charAt(pos++);
        if (c == '}') return map;
        if (c != ',') throw error("Expected ',' or '}'");
      }
    }

    List<Object> readArray() {
      final var list = new ArrayList<Object>();
      pos++; // [
      skipWs();
      if (pos < s.length() && s.charAt(pos) == ']') {
        pos++;
        return list;
      }
      while (true) {
        skipWs();
        list.add(readValue());
        skipWs();
        if (pos >= s.length()) throw error("Unterminated array");
        final var c = s.charAt(pos++);
        if (c == ']') return list;
        if (c != ',') throw error("Expected ',' or ']'");
      }
    }

    String readString() {
      pos++; // opening quote
      final var sb = new StringBuilder();
      while (true) {
        if (pos >= s.length()) throw error("Unterminated string");
        final var c = s.charAt(pos++);
        if (c == '"') return sb.toString();
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (pos >= s.length()) throw error("Unterminated escape");
        final var e = s.charAt(pos++);
        switch (e) {
          case '"', '\\', '/' -> sb.append(e);
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (pos + 4 > s.length()) throw error("Bad unicode escape");
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> throw error("Bad escape '\\" + e + "'");
        }
      }
    }

    Object readNumber() {
      final var start = pos;
      if (s.charAt(pos) == '-') pos++;
      var isDouble = false;
      while (pos < s.length()) {
        final var c = s.charAt(pos);
        if (c >= '0' && c <= '9') {
          pos++;
        } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
          isDouble = true;
          pos++;
        } else {
          break;
        }
      }
      final var num = s.substring(start, pos);
      try {
        if (!isDouble) return Long.parseLong(num);
      } catch (NumberFormatException ignored) {
        // fall through to double for very large integers
      }
      try {
        return Double.parseDouble(num);
      } catch (NumberFormatException ex) {
        throw error("Bad number '" + num + "'");
      }
    }
  }

  // ---------------------------------------------------------------- writing

  public static String write(Object value) {
    final var sb = new StringBuilder();
    write(sb, value);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static void write(StringBuilder sb, Object v) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof String str) {
      quote(sb, str);
    } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long
        || v instanceof Short || v instanceof Byte) {
      sb.append(v);
    } else if (v instanceof Number n) {
      final var d = n.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        sb.append("null");
      } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        sb.append((long) d);
      } else {
        sb.append(d);
      }
    } else if (v instanceof Map<?, ?> map) {
      sb.append('{');
      var first = true;
      for (final var e : ((Map<Object, Object>) map).entrySet()) {
        if (!first) sb.append(',');
        first = false;
        quote(sb, String.valueOf(e.getKey()));
        sb.append(':');
        write(sb, e.getValue());
      }
      sb.append('}');
    } else if (v instanceof Iterable<?> it) {
      sb.append('[');
      var first = true;
      for (final var o : it) {
        if (!first) sb.append(',');
        first = false;
        write(sb, o);
      }
      sb.append(']');
    } else if (v instanceof Object[] arr) {
      write(sb, List.of(arr));
    } else {
      quote(sb, v.toString());
    }
  }

  private static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (var i = 0; i < s.length(); i++) {
      final var c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  // ---------------------------------------------------------------- helpers

  /** Convenience builder for ordered maps: {@code Json.obj("a", 1, "b", "x")}. */
  public static Map<String, Object> obj(Object... kv) {
    final var map = new LinkedHashMap<String, Object>();
    for (var i = 0; i + 1 < kv.length; i += 2) {
      map.put((String) kv[i], kv[i + 1]);
    }
    return map;
  }
}
