/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.prefs.AppPreferences;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;

/** Settings window for the embedded MCP server ("Agent connection"). */
public final class McpDialog extends JFrame {
  private static final long serialVersionUID = 1L;
  private static McpDialog instance;

  private final JLabel status = new JLabel();
  private final JCheckBox enabled = new JCheckBox(S.get("mcpEnable"));
  private final JCheckBox requireToken = new JCheckBox(S.get("mcpRequireToken"));
  private final JSpinner port = new JSpinner(new SpinnerNumberModel(7531, 1024, 65535, 1));
  private final JTextField token = new JTextField(32);
  private final JTabbedPane snippets = new JTabbedPane();

  public static void showDialog(Component parent) {
    if (instance == null) instance = new McpDialog();
    instance.refresh();
    instance.setLocationRelativeTo(parent);
    instance.setVisible(true);
    instance.toFront();
  }

  private McpDialog() {
    super(S.get("mcpTitle"));
    setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

    enabled.setSelected(AppPreferences.MCP_ENABLED.getBoolean());
    requireToken.setSelected(AppPreferences.MCP_REQUIRE_TOKEN.getBoolean());
    port.setValue(AppPreferences.MCP_PORT.get());
    port.setEditor(new JSpinner.NumberEditor(port, "#"));
    token.setEditable(false);
    token.setText(McpServer.getOrCreateToken());

    final var form = new JPanel(new GridBagLayout());
    final var gc = new GridBagConstraints();
    gc.insets = new Insets(4, 4, 4, 4);
    gc.anchor = GridBagConstraints.WEST;
    gc.fill = GridBagConstraints.HORIZONTAL;

    gc.gridx = 0;
    gc.gridy = 0;
    gc.gridwidth = 3;
    form.add(status, gc);
    gc.gridy++;
    form.add(enabled, gc);

    gc.gridy++;
    gc.gridwidth = 1;
    form.add(new JLabel(S.get("mcpPort")), gc);
    gc.gridx = 1;
    form.add(port, gc);

    gc.gridx = 0;
    gc.gridy++;
    gc.gridwidth = 3;
    form.add(requireToken, gc);

    gc.gridy++;
    gc.gridwidth = 1;
    form.add(new JLabel(S.get("mcpToken")), gc);
    gc.gridx = 1;
    gc.weightx = 1;
    form.add(token, gc);
    gc.weightx = 0;
    gc.gridx = 2;
    final var tokenButtons = new JPanel();
    final var copyToken = new JButton(S.get("mcpCopy"));
    copyToken.addActionListener(e -> copy(token.getText()));
    final var regen = new JButton(S.get("mcpRegenerate"));
    regen.addActionListener(e -> {
      AppPreferences.MCP_TOKEN.set(McpServer.generateToken());
      token.setText(AppPreferences.MCP_TOKEN.get());
      apply();
    });
    tokenButtons.add(copyToken);
    tokenButtons.add(regen);
    form.add(tokenButtons, gc);

    final var buttons = new JPanel();
    final var applyButton = new JButton(S.get("mcpApply"));
    applyButton.addActionListener(e -> apply());
    final var close = new JButton(S.get("mcpClose"));
    close.addActionListener(e -> setVisible(false));
    buttons.add(applyButton);
    buttons.add(close);

    final var content = new JPanel(new BorderLayout(6, 6));
    content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    content.add(form, BorderLayout.NORTH);
    snippets.setBorder(BorderFactory.createTitledBorder(S.get("mcpSetupTitle")));
    content.add(snippets, BorderLayout.CENTER);
    content.add(buttons, BorderLayout.SOUTH);
    setContentPane(content);
    setPreferredSize(new Dimension(760, 620));
    refresh();
    pack();
  }

  private void apply() {
    AppPreferences.MCP_ENABLED.setBoolean(enabled.isSelected());
    AppPreferences.MCP_REQUIRE_TOKEN.setBoolean(requireToken.isSelected());
    AppPreferences.MCP_PORT.set((Integer) port.getValue());
    try {
      if (enabled.isSelected()) {
        McpServer.restart();
      } else {
        McpServer.stop();
      }
    } catch (Exception ex) {
      status.setText(S.get("mcpStartFailed", ex.getMessage()));
      return;
    }
    refresh();
  }

  private void refresh() {
    if (McpServer.isRunning()) {
      status.setText(S.get("mcpRunning", McpServer.endpointUrl(McpServer.getRunningPort())));
    } else {
      status.setText(S.get("mcpStopped"));
    }
    final var selected = Math.max(0, snippets.getSelectedIndex());
    snippets.removeAll();
    final var p = (Integer) port.getValue();
    final var url = McpServer.endpointUrl(p);
    final var tok = requireToken.isSelected() ? token.getText() : null;
    addSnippet("Claude Code", claudeCode(url, tok));
    addSnippet("Claude Desktop", claudeDesktop(url, tok));
    addSnippet("Codex / ChatGPT", codex(url, tok, p));
    addSnippet(S.get("mcpOtherClients"), other(url, tok));
    if (selected < snippets.getTabCount()) snippets.setSelectedIndex(selected);
  }

  private void addSnippet(String title, String text) {
    final var area = new JTextArea(text);
    area.setEditable(false);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    area.setLineWrap(true);
    area.setWrapStyleWord(false);
    final var panel = new JPanel(new BorderLayout());
    panel.add(new JScrollPane(area), BorderLayout.CENTER);
    final var copy = new JButton(S.get("mcpCopy"));
    copy.addActionListener(e -> copy(text));
    final var south = new JPanel(new BorderLayout());
    south.add(copy, BorderLayout.EAST);
    panel.add(south, BorderLayout.SOUTH);
    snippets.addTab(title, panel);
  }

  private static void copy(String text) {
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
  }

  // ------------------------------------------------------------ config snippets

  private static String javaExecutable() {
    return ProcessHandle.current().info().command().orElse("java");
  }

  private static String jarPath() {
    try {
      final var src = McpDialog.class.getProtectionDomain().getCodeSource().getLocation();
      return new File(src.toURI()).getAbsolutePath();
    } catch (Exception e) {
      return "logisim-evolution-all.jar";
    }
  }

  private static String jsonStr(String s) {
    return Json.write(s);
  }

  private static String bridgeArgsJson(String url, String tok) {
    final var sb = new StringBuilder();
    sb.append("[").append(jsonStr("-cp")).append(", ").append(jsonStr(jarPath()))
        .append(", \"com.cburch.logisim.mcp.McpStdioBridge\", \"--url\", ")
        .append(jsonStr(url));
    if (tok != null) sb.append(", \"--token\", ").append(jsonStr(tok));
    return sb.append("]").toString();
  }

  private static String claudeCode(String url, String tok) {
    final var header = tok == null ? "" : " --header \"Authorization: Bearer " + tok + "\"";
    return "# Run in a terminal (add --scope user to make it available in every project):\n"
        + "claude mcp add --transport http logisim " + url + header + "\n\n"
        + "# Then, inside Claude Code, run /mcp to confirm 'logisim' is connected.\n"
        + "# Logisim must be running with the agent connection enabled.\n";
  }

  private static String claudeDesktop(String url, String tok) {
    return "// Add to claude_desktop_config.json (Settings > Developer > Edit Config),\n"
        + "// then restart Claude Desktop. Logisim must be running.\n"
        + "{\n"
        + "  \"mcpServers\": {\n"
        + "    \"logisim\": {\n"
        + "      \"command\": " + jsonStr(javaExecutable()) + ",\n"
        + "      \"args\": " + bridgeArgsJson(url, tok) + "\n"
        + "    }\n"
        + "  }\n"
        + "}\n";
  }

  private static String codex(String url, String tok, int port) {
    final var args = bridgeArgsJson(url, tok);
    final var publicUrl = "https://<your-tunnel-host>/mcp" + (tok == null ? "" : "?token=" + tok);
    return "# --- OpenAI Codex CLI: add to ~/.codex/config.toml ---\n"
        + "[mcp_servers.logisim]\n"
        + "command = " + jsonStr(javaExecutable()) + "\n"
        + "args = " + args + "\n\n"
        + "# --- ChatGPT (web/desktop, Developer mode custom connector) ---\n"
        + "# ChatGPT connects from OpenAI's servers, so it needs a public HTTPS URL.\n"
        + "# 1. Expose the local port with a tunnel, for example:\n"
        + "#      cloudflared tunnel --url http://127.0.0.1:" + port + "\n"
        + "#      (or: ngrok http " + port + ")\n"
        + "# 2. In ChatGPT: Settings > Apps & Connectors > Advanced > Developer mode,\n"
        + "#    then Create connector with URL:\n"
        + "#      " + publicUrl + "\n"
        + "#    Authentication: No authentication (the token is in the URL).\n"
        + "# Anyone with that URL can control Logisim while the tunnel runs; stop the\n"
        + "# tunnel or press Regenerate when done.\n";
  }

  private static String other(String url, String tok) {
    return "Transport : Streamable HTTP (JSON responses)\n"
        + "Endpoint  : " + url + "\n"
        + (tok == null ? "Auth      : none\n"
            : "Auth      : header  Authorization: Bearer " + tok + "\n"
                + "            or query  " + url + "?token=" + tok + "\n")
        + "\nstdio clients can launch the bridge:\n  " + javaExecutable() + " -cp \"" + jarPath()
        + "\" com.cburch.logisim.mcp.McpStdioBridge --url " + url
        + (tok == null ? "" : " --token " + tok) + "\n"
        + "\nThe server only listens on 127.0.0.1 (this computer).\n";
  }
}
