/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.circuit.WidthIncompatibilityData;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectActions;
import com.cburch.logisim.proj.Projects;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.util.StringUtil;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

/** The MCP tools exposed by Logisim-evolution. All model access happens on the Swing EDT. */
final class McpTools {

  static final String INSTRUCTIONS =
      """
      This server controls a running Logisim-evolution instance (digital logic designer and \
      simulator). Workflow: call list_projects, then get_circuit to see components, their ports \
      (with absolute x/y coordinates) and wires. Use list_libraries / describe_component to find \
      component types and their attributes. Coordinates snap to a 10-pixel grid; wires must \
      start and end exactly on port coordinates (or on other wires) to connect. Prefer \
      'connect' to wire two ports together. Use 'simulate' or 'truth_table' to verify logic \
      deterministically, and render_circuit to see the schematic. Give input/output pins a \
      'label' attribute so they can be addressed by name. All edits are undoable in the GUI.""";

  private interface Handler {
    Object run(Map<?, ?> args) throws Exception;
  }

  private record ToolDef(String name, String description, Map<String, Object> schema,
      boolean readOnly, Handler handler) {}

  private final Map<String, ToolDef> tools = new LinkedHashMap<>();

  McpTools() {
    register();
  }

  // =================================================================== protocol glue

  List<Object> definitions() {
    final var list = new ArrayList<Object>();
    for (final var t : tools.values()) {
      list.add(
          Json.obj(
              "name", t.name,
              "description", t.description,
              "inputSchema", t.schema,
              "annotations", Json.obj("readOnlyHint", t.readOnly)));
    }
    return list;
  }

  Object call(Map<?, ?> params) {
    final var name = params.get("name");
    final var tool = name instanceof String n ? tools.get(n) : null;
    if (tool == null) {
      throw new McpServer.RpcException(-32602, "Unknown tool: " + name);
    }
    final var args = params.get("arguments") instanceof Map<?, ?> a ? a : Map.of();
    try {
      final var result = tool.handler.run(args);
      if (result instanceof ImageResult img) {
        return Json.obj(
            "content",
            List.of(
                Json.obj("type", "image", "data", img.base64, "mimeType", "image/png"),
                Json.obj("type", "text", "text", img.caption)));
      }
      final var text = result instanceof String s ? s : Json.write(result);
      final var out = Json.obj("content", List.of(Json.obj("type", "text", "text", text)));
      if (result instanceof Map<?, ?> || result instanceof List<?>) {
        out.put(
            "structuredContent", result instanceof Map<?, ?> ? result : Json.obj("items", result));
      }
      return out;
    } catch (ToolException e) {
      return toolError(e.getMessage());
    } catch (Exception e) {
      McpServer.logger.warn("MCP tool {} failed", name, e);
      return toolError(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Map<String, Object> toolError(String msg) {
    return Json.obj(
        "content", List.of(Json.obj("type", "text", "text", "Error: " + msg)), "isError", true);
  }

  private record ImageResult(String base64, String caption) {}

  static final class ToolException extends Exception {
    private static final long serialVersionUID = 1L;

    ToolException(String msg) {
      super(msg);
    }
  }

  private static <T> T onEdt(Callable<T> task) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) return task.call();
    final var result = new AtomicReference<T>();
    final var error = new AtomicReference<Exception>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              result.set(task.call());
            } catch (Exception e) {
              error.set(e);
            }
          });
    } catch (InvocationTargetException e) {
      throw new ToolException(String.valueOf(e.getCause()));
    }
    if (error.get() != null) throw error.get();
    return result.get();
  }

  // =================================================================== schema helpers

  private static Map<String, Object> schema(Object... props) {
    final var properties = new LinkedHashMap<String, Object>();
    final var required = new ArrayList<String>();
    for (var i = 0; i + 1 < props.length; i += 2) {
      var key = (String) props[i];
      if (key.endsWith("!")) {
        key = key.substring(0, key.length() - 1);
        required.add(key);
      }
      properties.put(key, props[i + 1]);
    }
    final var s = Json.obj("type", "object", "properties", properties);
    if (!required.isEmpty()) s.put("required", required);
    return s;
  }

  private static Map<String, Object> str(String desc) {
    return Json.obj("type", "string", "description", desc);
  }

  private static Map<String, Object> integer(String desc) {
    return Json.obj("type", "integer", "description", desc);
  }

  private static Map<String, Object> num(String desc) {
    return Json.obj("type", "number", "description", desc);
  }

  private static Map<String, Object> bool(String desc) {
    return Json.obj("type", "boolean", "description", desc);
  }

  private static Map<String, Object> anyObj(String desc) {
    return Json.obj("type", "object", "description", desc, "additionalProperties", true);
  }

  private static final Map<String, Object> PROJECT =
      Json.obj(
          "type", List.of("integer", "string"),
          "description",
          "Project index (from list_projects) or name. Defaults to the active window.");
  private static final Map<String, Object> CIRCUIT =
      str("Circuit name. Defaults to the circuit currently shown in the project window.");

  private static Map<String, Object> port() {
    return Json.obj(
        "type", "object",
        "description", "A component port.",
        "properties",
            Json.obj(
                "component", str("Component id as returned by get_circuit / add_component."),
                "port", integer("Port index from the component's 'ports' list.")),
        "required", List.of("component", "port"));
  }

  // =================================================================== registration

  private void add(String name, String desc, Map<String, Object> schema, boolean ro, Handler h) {
    tools.put(name, new ToolDef(name, desc, schema, ro, h));
  }

  private void register() {
    add("list_projects",
        "List the projects open in Logisim, with their circuits and which one is active.",
        schema(), true, a -> onEdt(this::listProjects));

    add("new_project", "Open a new, empty project window.", schema(), false,
        a -> onEdt(() -> {
          final var proj = ProjectActions.doNew(activeProjectOrNull());
          return Json.obj("project", Projects.getOpenProjects().indexOf(proj),
              "name", proj.getLogisimFile().getName());
        }));

    add("open_project", "Open a .circ file from disk in a new window.",
        schema("path!", str("Absolute path to a .circ file.")), false,
        a -> onEdt(() -> {
          final var file = new File(reqString(a, "path"));
          if (!file.isFile()) throw new ToolException("File not found: " + file);
          final var proj = ProjectActions.doOpen(null, null, file);
          if (proj == null) throw new ToolException("Could not open " + file);
          return Json.obj("project", Projects.getOpenProjects().indexOf(proj),
              "name", proj.getLogisimFile().getName());
        }));

    add("save_project",
        "Save the project. Without 'path' saves to its existing file (fails for unsaved files).",
        schema("project", PROJECT, "path", str("Optional absolute path to save as (.circ).")),
        false,
        a -> onEdt(() -> {
          final var proj = project(a);
          var path = optString(a, "path");
          File file;
          if (path != null) {
            if (!path.toLowerCase(Locale.ROOT).endsWith(".circ")) path += ".circ";
            file = new File(path);
          } else {
            file = proj.getLogisimFile().getLoader().getMainFile();
            if (file == null) {
              throw new ToolException("Project has never been saved; provide a 'path'.");
            }
          }
          if (!ProjectActions.doSave(proj, file)) throw new ToolException("Save failed.");
          return Json.obj("saved", file.getAbsolutePath());
        }));

    add("get_project_xml",
        "Return the project in Logisim's native .circ XML format (what would be saved to disk).",
        schema("project", PROJECT), true,
        a -> onEdt(() -> {
          final var proj = project(a);
          final var out = new ByteArrayOutputStream();
          final var file = proj.getLogisimFile();
          file.write(out, file.getLoader());
          return out.toString(StandardCharsets.UTF_8);
        }));

    add("list_libraries",
        "List component libraries and the component types each provides (use these names in "
            + "add_component). Project circuits appear under the project's own name and can be "
            + "placed as subcircuits.",
        schema("project", PROJECT), true, a -> onEdt(() -> listLibraries(project(a))));

    add("describe_component",
        "Describe a component type: its attributes, their default values and allowed options.",
        schema("project", PROJECT, "type!", str("Component type name, e.g. 'AND Gate', 'Pin'."),
            "library", str("Optional library name to disambiguate.")),
        true,
        a -> onEdt(() -> {
          final var tool = findTool(project(a), reqString(a, "type"), optString(a, "library"));
          final var factory = tool.getFactory();
          final var attrs = factory.createAttributeSet();
          return Json.obj("type", factory.getName(), "displayName", factory.getDisplayName(),
              "attributes", describeAttributes(attrs, true));
        }));

    add("create_circuit", "Add a new, empty circuit to the project and show it.",
        schema("project", PROJECT, "name!", str("Name of the new circuit.")), false,
        a -> onEdt(() -> {
          final var proj = project(a);
          final var name = reqString(a, "name");
          if (proj.getLogisimFile().getCircuit(name) != null) {
            throw new ToolException("A circuit named '" + name + "' already exists.");
          }
          final var circ = new Circuit(name, proj.getLogisimFile(), proj);
          proj.doAction(LogisimFileActions.addCircuit(circ));
          proj.setCurrentCircuit(circ);
          return Json.obj("created", name);
        }));

    add("show_circuit", "Switch the project window to display the given circuit.",
        schema("project", PROJECT, "circuit!", CIRCUIT), false,
        a -> onEdt(() -> {
          final var proj = project(a);
          proj.setCurrentCircuit(circuit(proj, a));
          return Json.obj("shown", proj.getCurrentCircuit().getName());
        }));

    add("set_main_circuit", "Mark a circuit as the project's main (top-level) circuit.",
        schema("project", PROJECT, "circuit!", CIRCUIT), false,
        a -> onEdt(() -> {
          final var proj = project(a);
          final var circ = circuit(proj, a);
          proj.doAction(LogisimFileActions.setMainCircuit(circ));
          return Json.obj("main", circ.getName());
        }));

    add("get_circuit",
        "Describe a circuit: every component (id, type, location, attributes, ports with "
            + "absolute coordinates, bit widths and direction) and every wire segment.",
        schema("project", PROJECT, "circuit", CIRCUIT,
            "include_attributes", bool("Include full attribute lists (default true).")),
        true,
        a -> onEdt(() -> {
          final var proj = project(a);
          return describeCircuit(circuit(proj, a), optBool(a, "include_attributes", true));
        }));

    add("add_component",
        "Place a component. Returns its id and port coordinates. Locations snap to 10px grid.",
        schema("project", PROJECT, "circuit", CIRCUIT,
            "type!", str("Component type name from list_libraries, e.g. 'AND Gate', 'Pin', "
                + "'Clock', or the name of another circuit to use it as a subcircuit."),
            "library", str("Optional library name to disambiguate."),
            "x!", integer("X coordinate of the component's anchor."),
            "y!", integer("Y coordinate of the component's anchor."),
            "attributes", anyObj("Attribute name -> value (as strings or numbers), e.g. "
                + "{\"facing\":\"west\",\"width\":8,\"label\":\"A\",\"type\":\"output\"}.")),
        false, a -> onEdt(() -> addComponent(a)));

    add("add_wires",
        "Add wire segments. Each segment {x1,y1,x2,y2}; non-orthogonal segments are routed as "
            + "an L (horizontal first). Wires connect where endpoints touch ports or wires.",
        schema("project", PROJECT, "circuit", CIRCUIT,
            "wires!", Json.obj("type", "array", "items", Json.obj("type", "object",
                "properties", Json.obj("x1", integer("start x"), "y1", integer("start y"),
                    "x2", integer("end x"), "y2", integer("end y")),
                "required", List.of("x1", "y1", "x2", "y2")))),
        false, a -> onEdt(() -> addWires(a)));

    add("connect",
        "Wire one component port to another with an automatically routed orthogonal path.",
        schema("project", PROJECT, "circuit", CIRCUIT, "from!", port(), "to!", port(),
            "via_x", integer("Optional x of the vertical jog (default: midway).")),
        false, a -> onEdt(() -> connect(a)));

    add("set_attributes", "Change attributes of an existing component.",
        schema("project", PROJECT, "circuit", CIRCUIT,
            "component!", str("Component id."),
            "attributes!", anyObj("Attribute name -> new value.")),
        false, a -> onEdt(() -> setAttributes(a)));

    add("delete",
        "Delete components (by id) and/or wire segments (by exact endpoints).",
        schema("project", PROJECT, "circuit", CIRCUIT,
            "components", Json.obj("type", "array", "items", Json.obj("type", "string")),
            "wires", Json.obj("type", "array", "items", Json.obj("type", "object",
                "properties", Json.obj("x1", integer(""), "y1", integer(""),
                    "x2", integer(""), "y2", integer(""))))),
        false, a -> onEdt(() -> delete(a)));

    add("move_component", "Move a component to a new anchor location (wires are not moved).",
        schema("project", PROJECT, "circuit", CIRCUIT, "component!", str("Component id."),
            "x!", integer("new x"), "y!", integer("new y")),
        false, a -> onEdt(() -> move(a)));

    add("undo", "Undo the last edit in the project.", schema("project", PROJECT), false,
        a -> onEdt(() -> {
          project(a).undoAction();
          return "ok";
        }));

    add("redo", "Redo the last undone edit in the project.", schema("project", PROJECT), false,
        a -> onEdt(() -> {
          project(a).redoAction();
          return "ok";
        }));

    add("simulate",
        "Deterministically evaluate a circuit in a fresh, isolated simulation: drive the given "
            + "input pins, propagate, and return every pin's value. Does not touch the GUI state. "
            + "Values may be numbers, '0b1010', '0x1F', or 'x' for unknown.",
        schema("project", PROJECT, "circuit", CIRCUIT,
            "inputs", anyObj("Input pin label (or component id) -> value. Unset inputs are 0.")),
        true, a -> onEdt(() -> simulateFresh(a)));

    add("truth_table",
        "Compute the full truth table of a combinational circuit over its input pins.",
        schema("project", PROJECT, "circuit", CIRCUIT,
            "max_input_bits", integer("Safety limit on total input bits (default 10, max 16).")),
        true, a -> onEdt(() -> truthTable(a)));

    add("poke_inputs",
        "Set input pins in the LIVE simulation shown in the GUI and wait for propagation. "
            + "Returns all pin values afterwards.",
        schema("project", PROJECT, "circuit", CIRCUIT,
            "inputs!", anyObj("Input pin label (or component id) -> value.")),
        false, this::pokeLive);

    add("read_live_values", "Read every pin's current value in the LIVE GUI simulation.",
        schema("project", PROJECT, "circuit", CIRCUIT), true,
        a -> onEdt(() -> {
          final var proj = project(a);
          final var circ = circuit(proj, a);
          return Json.obj("pins", readPins(proj.getCircuitState(circ), circ));
        }));

    add("tick_clock", "Advance the live simulation's clocks by a number of ticks (half-cycles).",
        schema("project", PROJECT, "count", integer("Number of ticks (default 1).")), false,
        a -> {
          final var proj = onEdt(() -> project(a));
          final var count = (int) optLong(a, "count", 1);
          awaitPropagation(proj.getSimulator(), () -> proj.getSimulator().tick(count));
          return onEdt(() -> {
            final var circ = proj.getCurrentCircuit();
            return Json.obj("circuit", circ.getName(),
                "pins", readPins(proj.getCircuitState(circ), circ));
          });
        });

    add("reset_simulation", "Reset the live simulation to its initial state.",
        schema("project", PROJECT), false,
        a -> {
          final var proj = onEdt(() -> project(a));
          awaitPropagation(proj.getSimulator(), () -> proj.getSimulator().reset());
          return "ok";
        });

    add("render_circuit", "Render a circuit (with live signal colors) as a PNG image.",
        schema("project", PROJECT, "circuit", CIRCUIT,
            "scale", num("Zoom factor, default 1.5 (0.25-4).")),
        true, a -> onEdt(() -> render(a)));
  }

  // =================================================================== project / circuit lookup

  private static Project activeProjectOrNull() {
    final var open = Projects.getOpenProjects();
    if (open.isEmpty()) return null;
    for (final var p : open) {
      final var f = p.getFrame();
      if (f != null && f.isActive()) return p;
    }
    final var top = Projects.getTopFrame();
    for (final var p : open) {
      if (p.getFrame() == top) return p;
    }
    return open.get(open.size() - 1);
  }

  private static Project project(Map<?, ?> a) throws ToolException {
    final var open = Projects.getOpenProjects();
    if (open.isEmpty()) throw new ToolException("No project is open. Use new_project first.");
    final var key = a.get("project");
    if (key == null) return activeProjectOrNull();
    if (key instanceof Number n) {
      final var idx = n.intValue();
      if (idx < 0 || idx >= open.size()) throw new ToolException("No project with index " + idx);
      return open.get(idx);
    }
    final var name = key.toString();
    for (final var p : open) {
      if (p.getLogisimFile().getName().equalsIgnoreCase(name)) return p;
    }
    try {
      return open.get(Integer.parseInt(name));
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      throw new ToolException("No open project named '" + name + "'");
    }
  }

  private static Circuit circuit(Project proj, Map<?, ?> a) throws ToolException {
    final var name = optString(a, "circuit");
    if (name == null) {
      final var cur = proj.getCurrentCircuit();
      if (cur == null) throw new ToolException("No circuit is currently shown; pass 'circuit'.");
      return cur;
    }
    final var circ = proj.getLogisimFile().getCircuit(name);
    if (circ == null) {
      for (final var c : proj.getLogisimFile().getCircuits()) {
        if (c.getName().equalsIgnoreCase(name)) return c;
      }
      throw new ToolException("No circuit named '" + name + "'");
    }
    return circ;
  }

  private Object listProjects() {
    final var active = activeProjectOrNull();
    final var list = new ArrayList<Object>();
    final var open = Projects.getOpenProjects();
    for (var i = 0; i < open.size(); i++) {
      final var p = open.get(i);
      final var file = p.getLogisimFile();
      final var circuits = new ArrayList<Object>();
      for (final var c : file.getCircuits()) circuits.add(c.getName());
      final var main = file.getMainCircuit();
      final var cur = p.getCurrentCircuit();
      final var path = file.getLoader().getMainFile();
      list.add(Json.obj(
          "index", i,
          "name", file.getName(),
          "active", p == active,
          "file", path == null ? null : path.getAbsolutePath(),
          "dirty", p.isFileDirty(),
          "mainCircuit", main == null ? null : main.getName(),
          "currentCircuit", cur == null ? null : cur.getName(),
          "circuits", circuits));
    }
    return Json.obj("projects", list);
  }

  // =================================================================== libraries / factories

  private static void collectLibraries(Library lib, String prefix, List<Object> out) {
    final var name = prefix.isEmpty() ? lib.getName() : prefix + "/" + lib.getName();
    final var comps = new ArrayList<Object>();
    for (final var t : lib.getTools()) {
      if (t instanceof AddTool at) comps.add(at.getName());
    }
    if (!comps.isEmpty()) {
      out.add(Json.obj("library", name, "displayName", lib.getDisplayName(),
          "components", comps));
    }
    for (final var sub : lib.getLibraries()) collectLibraries(sub, name, out);
  }

  private static Object listLibraries(Project proj) {
    final var out = new ArrayList<Object>();
    final var file = proj.getLogisimFile();
    final var own = new ArrayList<Object>();
    for (final var t : file.getTools()) own.add(t.getName());
    out.add(Json.obj("library", file.getName(), "displayName", "(this project's circuits)",
        "components", own));
    for (final var lib : file.getLibraries()) collectLibraries(lib, "", out);
    return Json.obj("libraries", out);
  }

  private static void findTools(Library lib, String libPath, String type, String libFilter,
      List<AddTool> out) {
    final var path = libPath.isEmpty() ? lib.getName() : libPath + "/" + lib.getName();
    final var libMatches = libFilter == null
        || path.equalsIgnoreCase(libFilter) || lib.getName().equalsIgnoreCase(libFilter)
        || lib.getDisplayName().equalsIgnoreCase(libFilter);
    if (libMatches) {
      for (final var t : lib.getTools()) {
        if (t instanceof AddTool at
            && (at.getName().equalsIgnoreCase(type) || at.getDisplayName().equalsIgnoreCase(type))) {
          out.add(at);
        }
      }
    }
    for (final var sub : lib.getLibraries()) findTools(sub, path, type, libFilter, out);
  }

  private static AddTool findTool(Project proj, String type, String libFilter)
      throws ToolException {
    final var found = new ArrayList<AddTool>();
    final var file = proj.getLogisimFile();
    if (libFilter == null || libFilter.equalsIgnoreCase(file.getName())) {
      for (final var t : file.getTools()) {
        if (t.getName().equalsIgnoreCase(type)) found.add(t);
      }
    }
    for (final var lib : file.getLibraries()) findTools(lib, "", type, libFilter, found);
    if (found.isEmpty()) {
      throw new ToolException("Unknown component type '" + type + "'. See list_libraries.");
    }
    return found.get(0);
  }

  // =================================================================== attributes

  @SuppressWarnings("unchecked")
  private static <V> String fmt(Attribute<V> attr, Object value) {
    if (value == null) return null;
    try {
      return attr.toStandardString((V) value);
    } catch (RuntimeException e) {
      return String.valueOf(value);
    }
  }

  private static List<String> optionsOf(Attribute<?> attr, Object value) {
    try {
      @SuppressWarnings({"unchecked", "rawtypes"})
      final var editor = ((Attribute) attr).getCellEditor((java.awt.Window) null, value);
      if (editor instanceof JComboBox<?> combo && combo.getItemCount() <= 256) {
        final var opts = new ArrayList<String>();
        for (var i = 0; i < combo.getItemCount(); i++) opts.add(fmt(attr, combo.getItemAt(i)));
        return opts;
      }
    } catch (RuntimeException ignored) {
      // attribute has no simple option list
    }
    return null;
  }

  private static Map<String, Object> describeAttributes(AttributeSet attrs, boolean withOptions) {
    final var out = new LinkedHashMap<String, Object>();
    for (final var attr : attrs.getAttributes()) {
      if (attr.isHidden()) continue;
      final var value = attrs.getValue(attr);
      if (!withOptions) {
        out.put(attr.getName(), fmt(attr, value));
        continue;
      }
      final var d = Json.obj("value", fmt(attr, value), "displayName", attr.getDisplayName());
      if (attrs.isReadOnly(attr)) d.put("readOnly", true);
      final var opts = optionsOf(attr, value);
      if (opts != null) d.put("options", opts);
      out.put(attr.getName(), d);
    }
    return out;
  }

  private static Attribute<?> findAttribute(AttributeSet attrs, String name) {
    for (final var attr : attrs.getAttributes()) {
      if (attr.getName().equalsIgnoreCase(name)) return attr;
    }
    for (final var attr : attrs.getAttributes()) {
      if (attr.getDisplayName().equalsIgnoreCase(name)) return attr;
    }
    return null;
  }

  private static String attrText(Object v) {
    if (v instanceof Double d && d == Math.rint(d)) return Long.toString(d.longValue());
    return String.valueOf(v);
  }

  /** Parses attribute values; returns attr -> parsed value pairs, validating everything first. */
  private static Map<Attribute<?>, Object> parseAttributes(AttributeSet attrs, Map<?, ?> given)
      throws ToolException {
    final var out = new LinkedHashMap<Attribute<?>, Object>();
    if (given == null) return out;
    for (final var e : given.entrySet()) {
      final var name = String.valueOf(e.getKey());
      final var attr = findAttribute(attrs, name);
      if (attr == null) {
        final var known = new ArrayList<String>();
        for (final var at : attrs.getAttributes()) if (!at.isHidden()) known.add(at.getName());
        throw new ToolException("Unknown attribute '" + name + "'. Known: " + known);
      }
      final var text = attrText(e.getValue());
      try {
        out.put(attr, attr.parse(text));
      } catch (RuntimeException ex) {
        final var opts = optionsOf(attr, attrs.getValue(attr));
        throw new ToolException("Bad value '" + text + "' for attribute '" + attr.getName() + "'"
            + (opts == null ? "" : ". Options: " + opts));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static <V> void applyAttr(AttributeSet attrs, Attribute<V> attr, Object value) {
    attrs.setValue(attr, (V) value);
  }

  // =================================================================== circuit description

  static String componentId(Component c) {
    final var loc = c.getLocation();
    return c.getFactory().getName() + "@" + loc.getX() + "," + loc.getY();
  }

  private static Component findComponent(Circuit circ, String id) throws ToolException {
    final var at = id.lastIndexOf('@');
    if (at > 0) {
      final var type = id.substring(0, at);
      try {
        final var xy = id.substring(at + 1).split(",");
        final var loc = Location.create(
            Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()), false);
        for (final var c : circ.getNonWires(loc)) {
          if (c.getLocation().equals(loc) && c.getFactory().getName().equals(type)) return c;
        }
        for (final var c : circ.getNonWires(loc)) {
          if (c.getLocation().equals(loc)) return c;
        }
      } catch (RuntimeException ignored) {
        // fall through to label lookup
      }
    }
    for (final var c : circ.getNonWires()) {
      final var attrs = c.getAttributeSet();
      if (attrs.containsAttribute(StdAttr.LABEL) && id.equals(attrs.getValue(StdAttr.LABEL))) {
        return c;
      }
    }
    throw new ToolException("No component '" + id + "' in circuit '" + circ.getName()
        + "'. Use get_circuit for ids.");
  }

  private static Map<String, Object> describeComponent(Component c, boolean withAttrs) {
    final var loc = c.getLocation();
    final var bds = c.getBounds();
    final var d = Json.obj(
        "id", componentId(c),
        "type", c.getFactory().getName(),
        "x", loc.getX(),
        "y", loc.getY(),
        "bounds", Json.obj("x", bds.getX(), "y", bds.getY(),
            "width", bds.getWidth(), "height", bds.getHeight()));
    final var attrs = c.getAttributeSet();
    if (attrs.containsAttribute(StdAttr.LABEL)) {
      final var label = attrs.getValue(StdAttr.LABEL);
      if (label != null && !label.isEmpty()) d.put("label", label);
    }
    if (c.getFactory() instanceof Pin) {
      d.put("pinType", Pin.FACTORY.isInputPin(Instance.getInstanceFor(c)) ? "input" : "output");
    }
    final var ports = new ArrayList<Object>();
    final var ends = c.getEnds();
    for (var i = 0; i < ends.size(); i++) {
      final var e = ends.get(i);
      ports.add(Json.obj("index", i, "x", e.getLocation().getX(), "y", e.getLocation().getY(),
          "width", e.getWidth().getWidth(), "direction", direction(e)));
    }
    d.put("ports", ports);
    if (withAttrs) d.put("attributes", describeAttributes(attrs, false));
    return d;
  }

  private static String direction(EndData e) {
    return switch (e.getType()) {
      case EndData.INPUT_ONLY -> "input";
      case EndData.OUTPUT_ONLY -> "output";
      default -> "inout";
    };
  }

  private static Object describeCircuit(Circuit circ, boolean withAttrs) {
    final var comps = new ArrayList<Component>(circ.getNonWires());
    comps.sort(Comparator.comparingInt((Component c) -> c.getLocation().getY())
        .thenComparingInt(c -> c.getLocation().getX()));
    final var compList = new ArrayList<Object>();
    for (final var c : comps) compList.add(describeComponent(c, withAttrs));
    final var wires = new ArrayList<Object>();
    for (final var w : circ.getWires()) {
      wires.add(Json.obj("x1", w.getEnd0().getX(), "y1", w.getEnd0().getY(),
          "x2", w.getEnd1().getX(), "y2", w.getEnd1().getY()));
    }
    final var issues = new ArrayList<Object>();
    final var widthIssues = circ.getWidthIncompatibilityData();
    for (final var wi : widthIssues == null ? List.<WidthIncompatibilityData>of() : widthIssues) {
      final var pts = new ArrayList<Object>();
      for (var i = 0; i < wi.size(); i++) {
        pts.add(Json.obj("x", wi.getPoint(i).getX(), "y", wi.getPoint(i).getY(),
            "width", wi.getBitWidth(i).getWidth()));
      }
      issues.add(Json.obj("widthMismatch", pts));
    }
    final var out = Json.obj("circuit", circ.getName(), "components", compList, "wires", wires);
    if (!issues.isEmpty()) out.put("problems", issues);
    return out;
  }

  // =================================================================== editing

  private static void commit(Project proj, CircuitMutation m, String what) {
    proj.doAction(m.toAction(StringUtil.constantGetter(what)));
  }

  private Object addComponent(Map<?, ?> a) throws Exception {
    final var proj = project(a);
    final var circ = circuit(proj, a);
    final var tool = findTool(proj, reqString(a, "type"), optString(a, "library"));
    final ComponentFactory factory = tool.getFactory();
    if (factory == circ.getSubcircuitFactory()) {
      throw new ToolException("A circuit cannot contain itself.");
    }
    final var attrs = factory.createAttributeSet();
    final var parsed = parseAttributes(attrs,
        a.get("attributes") instanceof Map<?, ?> m ? m : null);
    for (final var e : parsed.entrySet()) applyAttr(attrs, e.getKey(), e.getValue());
    final var loc = Location.create((int) reqLong(a, "x"), (int) reqLong(a, "y"), true);
    final var comp = factory.createComponent(loc, attrs);
    final var bds = comp.getBounds();
    if (bds.getX() < 0 || bds.getY() < 0) {
      throw new ToolException("Component would extend to negative coordinates " + bds
          + "; choose larger x/y.");
    }
    if (circ.hasConflict(comp)) {
      throw new ToolException("Location conflicts with an existing exclusive component.");
    }
    final var m = new CircuitMutation(circ);
    m.add(comp);
    commit(proj, m, "Add " + factory.getDisplayName());
    return describeComponent(comp, true);
  }

  private static void addSegment(List<Wire> out, int x1, int y1, int x2, int y2, Integer viaX) {
    final var a = Location.create(x1, y1, true);
    final var b = Location.create(x2, y2, true);
    if (a.equals(b)) return;
    if (a.getX() == b.getX() || a.getY() == b.getY()) {
      out.add(Wire.create(a, b));
      return;
    }
    if (viaX == null) {
      final var m = Location.create(b.getX(), a.getY(), true);
      out.add(Wire.create(a, m));
      out.add(Wire.create(m, b));
    } else {
      final var vx = Location.create(viaX, 0, true).getX();
      final var m1 = Location.create(vx, a.getY(), true);
      final var m2 = Location.create(vx, b.getY(), true);
      if (!a.equals(m1)) out.add(Wire.create(a, m1));
      out.add(Wire.create(m1, m2));
      if (!m2.equals(b)) out.add(Wire.create(m2, b));
    }
  }

  private static Object commitWires(Project proj, Circuit circ, List<Wire> wires, String what) {
    if (wires.isEmpty()) return Json.obj("added", List.of());
    final var m = new CircuitMutation(circ);
    final var list = new ArrayList<Object>();
    for (final var w : wires) {
      m.add(w);
      list.add(Json.obj("x1", w.getEnd0().getX(), "y1", w.getEnd0().getY(),
          "x2", w.getEnd1().getX(), "y2", w.getEnd1().getY()));
    }
    commit(proj, m, what);
    return Json.obj("added", list);
  }

  private Object addWires(Map<?, ?> a) throws Exception {
    final var proj = project(a);
    final var circ = circuit(proj, a);
    if (!(a.get("wires") instanceof List<?> segs)) throw new ToolException("'wires' is required");
    final var wires = new ArrayList<Wire>();
    for (final var s : segs) {
      if (!(s instanceof Map<?, ?> seg)) throw new ToolException("Each wire must be an object");
      addSegment(wires, (int) reqLong(seg, "x1"), (int) reqLong(seg, "y1"),
          (int) reqLong(seg, "x2"), (int) reqLong(seg, "y2"), null);
    }
    return commitWires(proj, circ, wires, "Add wires");
  }

  private static EndData portOf(Circuit circ, Object spec) throws ToolException {
    if (!(spec instanceof Map<?, ?> p)) throw new ToolException("Port must be {component, port}");
    final var comp = findComponent(circ, reqString(p, "component"));
    final var idx = (int) reqLong(p, "port");
    final var ends = comp.getEnds();
    if (idx < 0 || idx >= ends.size()) {
      throw new ToolException("Component " + componentId(comp) + " has ports 0.."
          + (ends.size() - 1));
    }
    return ends.get(idx);
  }

  private Object connect(Map<?, ?> a) throws Exception {
    final var proj = project(a);
    final var circ = circuit(proj, a);
    final var from = portOf(circ, a.get("from")).getLocation();
    final var to = portOf(circ, a.get("to")).getLocation();
    Integer via = a.get("via_x") instanceof Number n ? n.intValue() : null;
    if (via == null && from.getX() != to.getX() && from.getY() != to.getY()) {
      via = chooseJogX(circ, from, to);
    }
    final var wires = new ArrayList<Wire>();
    addSegment(wires, from.getX(), from.getY(), to.getX(), to.getY(), via);
    return commitWires(proj, circ, wires, "Connect");
  }

  /**
   * Picks the x of the vertical jog for an L/Z route so that none of the three new segments runs
   * along an existing wire or crosses a port/wire endpoint (which would short unrelated nets).
   */
  private static int chooseJogX(Circuit circ, Location from, Location to) {
    final var lo = Math.min(from.getX(), to.getX());
    final var hi = Math.max(from.getX(), to.getX());
    final var mid = Location.create((lo + hi) / 2, 0, true).getX();
    for (var d = 0; d <= 400; d += 10) {
      for (final var x : d == 0 ? new int[] {mid} : new int[] {mid - d, mid + d}) {
        if (x <= lo || x >= hi) continue;
        if (routeIsClear(circ, from, to, x)) return x;
      }
    }
    return mid;
  }

  private static boolean routeIsClear(Circuit circ, Location from, Location to, int x) {
    final var m1 = Location.create(x, from.getY(), false);
    final var m2 = Location.create(x, to.getY(), false);
    return segmentIsClear(circ, from, m1, from, to)
        && segmentIsClear(circ, m1, m2, from, to)
        && segmentIsClear(circ, m2, to, from, to);
  }

  /** True if segment a-b does not overlap existing wires or touch points other than its ends. */
  private static boolean segmentIsClear(Circuit circ, Location a, Location b,
      Location from, Location to) {
    if (a.equals(b)) return true;
    final var vertical = a.getX() == b.getX();
    final var fixed = vertical ? a.getX() : a.getY();
    final var s0 = Math.min(vertical ? a.getY() : a.getX(), vertical ? b.getY() : b.getX());
    final var s1 = Math.max(vertical ? a.getY() : a.getX(), vertical ? b.getY() : b.getX());
    for (final var w : circ.getWires()) {
      final var e0 = w.getEnd0();
      final var e1 = w.getEnd1();
      final var wVertical = e0.getX() == e1.getX();
      if (wVertical != vertical) continue;
      if ((vertical ? e0.getX() : e0.getY()) != fixed) continue;
      final var w0 = Math.min(vertical ? e0.getY() : e0.getX(), vertical ? e1.getY() : e1.getX());
      final var w1 = Math.max(vertical ? e0.getY() : e0.getX(), vertical ? e1.getY() : e1.getX());
      if (w0 < s1 && s0 < w1) return false; // collinear overlap
    }
    for (final var p : circ.getAllLocations()) {
      if (p.equals(from) || p.equals(to)) continue;
      final var onLine = vertical ? p.getX() == fixed : p.getY() == fixed;
      final var along = vertical ? p.getY() : p.getX();
      if (onLine && along >= s0 && along <= s1) return false;
    }
    return true;
  }

  private Object setAttributes(Map<?, ?> a) throws Exception {
    final var proj = project(a);
    final var circ = circuit(proj, a);
    final var comp = findComponent(circ, reqString(a, "component"));
    if (!(a.get("attributes") instanceof Map<?, ?> given)) {
      throw new ToolException("'attributes' must be an object");
    }
    final var parsed = parseAttributes(comp.getAttributeSet(), given);
    final var m = new CircuitMutation(circ);
    for (final var e : parsed.entrySet()) m.set(comp, e.getKey(), e.getValue());
    commit(proj, m, "Set attributes");
    return describeComponent(comp, true);
  }

  private Object delete(Map<?, ?> a) throws Exception {
    final var proj = project(a);
    final var circ = circuit(proj, a);
    final var m = new CircuitMutation(circ);
    final var removed = new ArrayList<Object>();
    if (a.get("components") instanceof List<?> ids) {
      for (final var id : ids) {
        final var c = findComponent(circ, String.valueOf(id));
        m.remove(c);
        removed.add(componentId(c));
      }
    }
    if (a.get("wires") instanceof List<?> segs) {
      for (final var s : segs) {
        if (!(s instanceof Map<?, ?> seg)) continue;
        final var p1 = Location.create((int) reqLong(seg, "x1"), (int) reqLong(seg, "y1"), false);
        final var p2 = Location.create((int) reqLong(seg, "x2"), (int) reqLong(seg, "y2"), false);
        Wire hit = null;
        for (final var w : circ.getWires()) {
          if ((w.getEnd0().equals(p1) && w.getEnd1().equals(p2))
              || (w.getEnd0().equals(p2) && w.getEnd1().equals(p1))) {
            hit = w;
            break;
          }
        }
        if (hit == null) throw new ToolException("No wire from " + p1 + " to " + p2);
        m.remove(hit);
        removed.add(Json.obj("x1", p1.getX(), "y1", p1.getY(), "x2", p2.getX(), "y2", p2.getY()));
      }
    }
    if (removed.isEmpty()) throw new ToolException("Nothing to delete.");
    commit(proj, m, "Delete");
    return Json.obj("deleted", removed);
  }

  private Object move(Map<?, ?> a) throws Exception {
    final var proj = project(a);
    final var circ = circuit(proj, a);
    final var comp = findComponent(circ, reqString(a, "component"));
    final var loc = Location.create((int) reqLong(a, "x"), (int) reqLong(a, "y"), true);
    final var attrs = (AttributeSet) comp.getAttributeSet().clone();
    final var moved = comp.getFactory().createComponent(loc, attrs);
    final var bds = moved.getBounds();
    if (bds.getX() < 0 || bds.getY() < 0) throw new ToolException("Negative coordinates.");
    final var m = new CircuitMutation(circ);
    m.replace(comp, moved);
    commit(proj, m, "Move");
    return describeComponent(moved, false);
  }

  // =================================================================== simulation

  private static List<Component> pins(Circuit circ) {
    final var list = new ArrayList<Component>();
    for (final var c : circ.getNonWires()) {
      if (c.getFactory() instanceof Pin) list.add(c);
    }
    list.sort(Comparator.comparingInt((Component c) -> c.getLocation().getY())
        .thenComparingInt(c -> c.getLocation().getX()));
    return list;
  }

  private static boolean isInput(Component pin) {
    return Pin.FACTORY.isInputPin(Instance.getInstanceFor(pin));
  }

  private static String pinName(Component pin) {
    final var label = pin.getAttributeSet().getValue(StdAttr.LABEL);
    return label == null || label.isEmpty() ? componentId(pin) : label;
  }

  private static BitWidth widthOf(Component pin) {
    return pin.getAttributeSet().getValue(StdAttr.WIDTH);
  }

  static Value parseValue(Object raw, BitWidth width) throws ToolException {
    if (raw instanceof Boolean b) return Value.createKnown(width, b ? 1 : 0);
    if (raw instanceof Number n) return Value.createKnown(width, n.longValue());
    var s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT).replace("_", "");
    if (s.equals("x") || s.equals("u") || s.equals("unknown") || s.equals("z")) {
      return Value.createUnknown(width);
    }
    try {
      final long v;
      if (s.startsWith("0b")) {
        v = Long.parseUnsignedLong(s.substring(2), 2);
      } else if (s.startsWith("0x")) {
        v = Long.parseUnsignedLong(s.substring(2), 16);
      } else if (s.equals("true") || s.equals("high")) {
        v = 1;
      } else if (s.equals("false") || s.equals("low")) {
        v = 0;
      } else {
        v = Long.parseLong(s);
      }
      return Value.createKnown(width, v);
    } catch (NumberFormatException e) {
      throw new ToolException("Cannot parse value '" + raw + "' (use number, 0b.., 0x.. or x)");
    }
  }

  private static Object formatValue(Value v) {
    final var d = Json.obj("bits", v.toBinaryString());
    if (v.isFullyDefined()) {
      d.put("value", v.toLongValue());
      if (v.getWidth() > 4) d.put("hex", "0x" + v.toHexString());
    } else {
      d.put("value", null);
    }
    return d;
  }

  private static Component resolvePin(Circuit circ, List<Component> pins, String key)
      throws ToolException {
    for (final var p : pins) if (pinName(p).equals(key)) return p;
    for (final var p : pins) if (pinName(p).equalsIgnoreCase(key)) return p;
    final var c = findComponent(circ, key);
    if (!(c.getFactory() instanceof Pin)) throw new ToolException(key + " is not a pin");
    return c;
  }

  private static Map<String, Object> readPins(CircuitState state, Circuit circ) {
    final var inputs = new LinkedHashMap<String, Object>();
    final var outputs = new LinkedHashMap<String, Object>();
    for (final var p : pins(circ)) {
      final var v = Pin.FACTORY.getValue(state.getInstanceState(p));
      (isInput(p) ? inputs : outputs).put(pinName(p), formatValue(v));
    }
    return Json.obj("inputs", inputs, "outputs", outputs);
  }

  private Object simulateFresh(Map<?, ?> a) throws Exception {
    final var proj = project(a);
    final var circ = circuit(proj, a);
    final var pins = pins(circ);
    final var state = CircuitState.createRootState(proj, circ, Thread.currentThread());
    for (final var p : pins) {
      if (isInput(p)) {
        Pin.FACTORY.driveInputPin(state.getInstanceState(p), Value.createKnown(widthOf(p), 0));
      }
    }
    if (a.get("inputs") instanceof Map<?, ?> given) {
      for (final var e : given.entrySet()) {
        final var pin = resolvePin(circ, pins, String.valueOf(e.getKey()));
        if (!isInput(pin)) throw new ToolException(pinName(pin) + " is an output pin");
        Pin.FACTORY.driveInputPin(state.getInstanceState(pin),
            parseValue(e.getValue(), widthOf(pin)));
      }
    }
    final var prop = state.getPropagator();
    prop.propagate();
    final var out = readPins(state, circ);
    if (prop.isOscillating()) out.put("warning", "Circuit is oscillating; values are unreliable.");
    return out;
  }

  private Object truthTable(Map<?, ?> a) throws Exception {
    final var proj = project(a);
    final var circ = circuit(proj, a);
    final var pins = pins(circ);
    final var inputs = new ArrayList<Component>();
    final var outputs = new ArrayList<Component>();
    for (final var p : pins) (isInput(p) ? inputs : outputs).add(p);
    var totalBits = 0;
    for (final var p : inputs) totalBits += widthOf(p).getWidth();
    final var limit = Math.min(16, (int) optLong(a, "max_input_bits", 10));
    if (totalBits > limit) {
      throw new ToolException("Circuit has " + totalBits + " input bits; limit is " + limit
          + " (raise max_input_bits up to 16, or use simulate).");
    }
    final var header = new ArrayList<Object>();
    for (final var p : inputs) header.add(pinName(p));
    for (final var p : outputs) header.add(pinName(p));
    final var rows = new ArrayList<Object>();
    for (long row = 0; row < (1L << totalBits); row++) {
      final var state = CircuitState.createRootState(proj, circ, Thread.currentThread());
      final var cells = new ArrayList<Object>();
      var shift = totalBits;
      for (final var p : inputs) {
        final var w = widthOf(p).getWidth();
        shift -= w;
        final var v = (row >> shift) & ((1L << w) - 1);
        Pin.FACTORY.driveInputPin(state.getInstanceState(p), Value.createKnown(widthOf(p), v));
        cells.add(Value.createKnown(widthOf(p), v).toBinaryString());
      }
      final var prop = state.getPropagator();
      prop.propagate();
      for (final var p : outputs) {
        cells.add(prop.isOscillating() ? "E"
            : Pin.FACTORY.getValue(state.getInstanceState(p)).toBinaryString());
      }
      rows.add(cells);
    }
    return Json.obj("columns", header, "inputCount", inputs.size(), "rows", rows);
  }

  /** Runs an action against the live simulator and waits (bounded) for it to settle. */
  private static void awaitPropagation(Simulator sim, Runnable action) throws Exception {
    final var latch = new CountDownLatch(1);
    final Simulator.Listener l = new Simulator.Listener() {
      @Override
      public void simulatorReset(Simulator.Event e) {
        latch.countDown();
      }

      @Override
      public void simulatorStateChanged(Simulator.Event e) {
        // not relevant
      }

      @Override
      public void propagationCompleted(Simulator.Event e) {
        latch.countDown();
      }
    };
    sim.addSimulatorListener(l);
    try {
      onEdt(() -> {
        action.run();
        return null;
      });
      latch.await(2, TimeUnit.SECONDS);
    } finally {
      sim.removeSimulatorListener(l);
    }
  }

  private Object pokeLive(Map<?, ?> a) throws Exception {
    final var proj = onEdt(() -> project(a));
    final var circ = onEdt(() -> circuit(proj, a));
    if (!(a.get("inputs") instanceof Map<?, ?> given)) throw new ToolException("'inputs' needed");
    awaitPropagation(proj.getSimulator(), () -> {
      try {
        final var state = proj.getCircuitState(circ);
        final var pins = pins(circ);
        for (final var e : given.entrySet()) {
          final var pin = resolvePin(circ, pins, String.valueOf(e.getKey()));
          if (!isInput(pin)) throw new ToolException(pinName(pin) + " is an output pin");
          final var ps = state.getInstanceState(pin);
          Pin.FACTORY.driveInputPin(ps, parseValue(e.getValue(), widthOf(pin)));
          ps.fireInvalidated();
        }
      } catch (ToolException e) {
        throw new IllegalArgumentException(e.getMessage());
      }
    });
    return onEdt(() -> {
      final var out = readPins(proj.getCircuitState(circ), circ);
      if (!proj.getSimulator().isAutoPropagating()) {
        out.put("note", "Auto-propagate is off in Simulate menu; outputs may be stale.");
      }
      proj.repaintCanvas();
      return out;
    });
  }

  // =================================================================== rendering

  private Object render(Map<?, ?> a) throws Exception {
    final var proj = project(a);
    final var circ = circuit(proj, a);
    final var scale = Math.max(0.25, Math.min(4.0,
        a.get("scale") instanceof Number n ? n.doubleValue() : 1.5));
    final var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    final var probeG = probe.createGraphics();
    final var bds = circ.getBounds(probeG).expand(10);
    probeG.dispose();
    final var width = Math.max(1, (int) Math.round(bds.getWidth() * scale));
    final var height = Math.max(1, (int) Math.round(bds.getHeight() * scale));
    if ((long) width * height > 40_000_000L) {
      throw new ToolException("Image would be too large; use a smaller scale.");
    }
    final var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final var base = img.createGraphics();
    base.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    base.setColor(Color.WHITE);
    base.fillRect(0, 0, width, height);
    final var g = (Graphics2D) base.create();
    g.scale(scale, scale);
    g.translate(-bds.getX(), -bds.getY());
    g.setColor(Color.BLACK);
    final var frame = proj.getFrame();
    final java.awt.Component dest = frame != null ? frame.getCanvas() : null;
    final var ctx = new ComponentDrawContext(dest, circ, proj.getCircuitState(circ), base, g);
    circ.draw(ctx, null);
    g.dispose();
    base.dispose();
    final var bytes = new ByteArrayOutputStream();
    ImageIO.write(img, "PNG", bytes);
    return new ImageResult(Base64.getEncoder().encodeToString(bytes.toByteArray()),
        "Circuit '" + circ.getName() + "' — image origin is (" + bds.getX() + "," + bds.getY()
            + ") in circuit coordinates, scale " + scale + ".");
  }

  // =================================================================== argument helpers

  private static String optString(Map<?, ?> a, String key) {
    final var v = a.get(key);
    if (v == null) return null;
    final var s = v.toString();
    return s.isBlank() ? null : s;
  }

  private static String reqString(Map<?, ?> a, String key) throws ToolException {
    final var s = optString(a, key);
    if (s == null) throw new ToolException("'" + key + "' is required");
    return s;
  }

  private static long reqLong(Map<?, ?> a, String key) throws ToolException {
    final var v = a.get(key);
    if (v instanceof Number n) return n.longValue();
    if (v instanceof String s) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ignored) {
        // reported below
      }
    }
    throw new ToolException("'" + key + "' must be an integer");
  }

  private static long optLong(Map<?, ?> a, String key, long dflt) throws ToolException {
    return a.get(key) == null ? dflt : reqLong(a, key);
  }

  private static boolean optBool(Map<?, ?> a, String key, boolean dflt) {
    final var v = a.get(key);
    if (v instanceof Boolean b) return b;
    if (v instanceof String s) return Boolean.parseBoolean(s);
    return dflt;
  }
}
