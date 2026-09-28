/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.sandbox.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.interaction.VncAllowList;
import me.brandonli.mcav.sandbox.locale.Locale;
import me.brandonli.mcav.sandbox.utils.IOUtils;
import me.brandonli.mcav.sandbox.utils.MapCodec;
import me.brandonli.mcav.sandbox.utils.Mcv2Hosting;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link PluginDataConfigurationMapper}.
 */
final class PluginDataConfigurationMapperTest {

  private static final String FULL_CONFIGURATION = """
  language: en_us
  discord-bot:
    enabled: true
    token: secret-token
    guild-id: "123"
    channel-id: "456"
  http-server:
    enabled: true
    host-name: mc.example.com
    port: 8080
  simple-voice-chat:
    enabled: true
  mcv2:
    encoder-threads: 3
    native: "off"
  browser:
    allow-private-networks: true
    javascript-jit: true
    autoplay-sound: true
  """;

  @TempDir
  private Path folder;

  private MCAVSandbox plugin;

  private MockedStatic<JavaPlugin> javaPlugin;

  private PluginDataConfigurationMapper mapper;

  @BeforeEach
  void createMapper() {
    this.plugin = mock(MCAVSandbox.class);
    final File folderFile = this.folder.toFile();
    when(this.plugin.getDataFolder()).thenReturn(folderFile);
    this.javaPlugin = Mockito.mockStatic(JavaPlugin.class);
    this.javaPlugin.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(this.plugin);
    this.mapper = new PluginDataConfigurationMapper(this.plugin);
  }

  @AfterEach
  void closeStaticMock() {
    this.javaPlugin.close();
  }

  // copies the bundled resource like JavaPlugin#saveResource does
  private void saveBundledResources() {
    doAnswer(invocation -> {
      final String name = invocation.getArgument(0);
      final Path target = this.folder.resolve(name);
      try (final InputStream stream = IOUtils.getResourceAsStream(name)) {
        Files.copy(stream, target);
      }
      return null;
    })
      .when(this.plugin)
      .saveResource(anyString(), anyBoolean());
  }

  @Test
  void readsTheAllowedVncServersWithTheirPasswords() throws IOException {
    this.writeConfiguration(
      """
      vnc:
        allowed-hosts:
          - host: 127.0.0.1
            port: 5901
            password: secret
          - host: desktop.example
            port: 5902
            password: 1234
          - host: open.example
            port: 5903
      """
    );
    this.mapper.deserialize();
    final VncAllowList list = this.mapper.getVncAllowList();
    final List<VncAllowList.Entry> expected = List.of(
      new VncAllowList.Entry("127.0.0.1", 5901, "secret"),
      new VncAllowList.Entry("desktop.example", 5902, "1234"),
      new VncAllowList.Entry("open.example", 5903, null)
    );
    assertEquals(expected, list.entries());
  }

  @Test
  void allowsNoVncServerByDefault() {
    this.saveBundledResources();
    this.mapper.deserialize();
    assertEquals(List.of(), this.mapper.getVncAllowList().entries());
  }

  @Test
  void skipsInvalidVncEntriesAndNeverLogsAPassword() throws IOException {
    final PrintStream original = System.err;
    final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
    try {
      this.writeConfiguration(
        """
        vnc:
          allowed-hosts:
            - port: 5901
              password: hunter2
            - host: " "
              port: 5901
              password: hunter2
            - host: desktop.example
              port: 70000
              password: hunter2
            - host: desktop.example
              port: 0
              password: hunter2
            - host: desktop.example
              port: "5901"
              password: hunter2
            - host: kept.example
              port: 5904
              password: ""
        """
      );
      this.mapper.deserialize();
    } finally {
      System.setErr(original);
    }
    assertEquals(List.of(new VncAllowList.Entry("kept.example", 5904, null)), this.mapper.getVncAllowList().entries());
    final String text = logged.toString(StandardCharsets.UTF_8);
    assertEquals(5, text.split("Ignoring an entry of vnc.allowed-hosts", -1).length - 1, text);
    assertFalse(text.contains("hunter2"), text);
  }

  private void writeConfiguration(final String yaml) throws IOException {
    final Path file = this.folder.resolve("config.yml");
    Files.writeString(file, yaml, StandardCharsets.UTF_8);
  }

  @Test
  void usesDefaultsBeforeReading() {
    final MCAVSandbox owner = this.mapper.getPlugin();
    final Locale locale = this.mapper.getLocale();
    final String token = this.mapper.getDiscordBotToken();
    final String channel = this.mapper.getDiscordBotChannelId();
    final String guild = this.mapper.getDiscordBotGuildId();
    final String host = this.mapper.getHttpHostName();
    final int port = this.mapper.getHttpPort();
    final boolean discord = this.mapper.isDiscordBotEnabled();
    final boolean http = this.mapper.isHttpEnabled();
    final boolean voiceChat = this.mapper.isSimpleVoiceChatEnabled();
    assertSame(this.plugin, owner);
    assertSame(Locale.EN_US, locale);
    assertEquals("", token);
    assertEquals("", channel);
    assertEquals("", guild);
    assertEquals("localhost", host);
    assertEquals(3000, port);
    assertFalse(discord);
    assertFalse(http);
    assertFalse(voiceChat);
    assertEquals(0, this.mapper.getMcv2EncoderThreads());
    assertEquals("auto", this.mapper.getMcv2Native());
    assertFalse(this.mapper.isBrowserPrivateNetworks());
    assertFalse(this.mapper.isBrowserJavaScriptJit());
    assertFalse(this.mapper.isBrowserAutoplaySound());
    assertSame(MapCodec.DITHER, this.mapper.getMcv2DefaultCodec());
    assertSame(Mcv2Hosting.INJECTOR, this.mapper.getMcv2PackHosting());
    assertEquals("", this.mapper.getMcv2PackHttpHost());
    assertEquals(25580, this.mapper.getMcv2PackHttpPort());
  }

  @Test
  void createsTheBundledConfigurationWhenItIsMissing() {
    this.saveBundledResources();
    this.mapper.deserialize();
    verify(this.plugin).saveResource("config.yml", false);
    final Path file = this.folder.resolve("config.yml");
    final boolean exists = Files.isRegularFile(file);
    assertTrue(exists);
  }

  @Test
  void readsTheBundledConfiguration() {
    this.saveBundledResources();
    this.mapper.deserialize();
    final Locale locale = this.mapper.getLocale();
    final String token = this.mapper.getDiscordBotToken();
    final String channel = this.mapper.getDiscordBotChannelId();
    final String guild = this.mapper.getDiscordBotGuildId();
    final String host = this.mapper.getHttpHostName();
    final int port = this.mapper.getHttpPort();
    final boolean discord = this.mapper.isDiscordBotEnabled();
    final boolean http = this.mapper.isHttpEnabled();
    final boolean voiceChat = this.mapper.isSimpleVoiceChatEnabled();
    assertSame(Locale.EN_US, locale);
    assertEquals("none", token);
    assertEquals("none", channel);
    assertEquals("none", guild);
    assertEquals("localhost", host);
    assertEquals(3000, port);
    assertFalse(discord);
    assertFalse(http);
    assertFalse(voiceChat);
    assertEquals(0, this.mapper.getMcv2EncoderThreads());
    assertEquals("auto", this.mapper.getMcv2Native());
    assertFalse(this.mapper.isBrowserPrivateNetworks(), "the bundled file keeps the browser on public addresses");
    assertFalse(this.mapper.isBrowserJavaScriptJit(), "and JavaScript without its compiler");
    assertFalse(this.mapper.isBrowserAutoplaySound(), "and pages silent until a player clicked them");
    assertSame(MapCodec.DITHER, this.mapper.getMcv2DefaultCodec(), "and the maps dithered unless a command asks for MCV2");
    assertSame(Mcv2Hosting.INJECTOR, this.mapper.getMcv2PackHosting());
    assertEquals("", this.mapper.getMcv2PackHttpHost());
    assertEquals(25580, this.mapper.getMcv2PackHttpPort());
  }

  @Test
  void readsTheCodecAndThePackHostingInAnyCase() throws IOException {
    this.writeConfiguration(
      "mcv2:\n  default-codec: MCV2\n  pack:\n    hosting: Http\n    http-host: \" mc.example \"\n    http-port: 8443\n"
    );
    this.mapper.deserialize();
    assertSame(MapCodec.MCV2, this.mapper.getMcv2DefaultCodec());
    assertSame(Mcv2Hosting.HTTP, this.mapper.getMcv2PackHosting());
    assertEquals("mc.example", this.mapper.getMcv2PackHttpHost());
    assertEquals(8443, this.mapper.getMcv2PackHttpPort());
    this.writeConfiguration("mcv2:\n  pack:\n    hosting: website\n");
    this.mapper.deserialize();
    assertSame(Mcv2Hosting.WEBSITE, this.mapper.getMcv2PackHosting());
  }

  @Test
  void warnsOfAnInvalidCodecHostingOrPortAndKeepsTheDefaults() throws IOException {
    final PrintStream original = System.err;
    final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
    try {
      this.writeConfiguration("mcv2:\n  default-codec: vp9\n  pack:\n    hosting: ftp\n    http-port: 70000\n");
      this.mapper.deserialize();
    } finally {
      System.setErr(original);
    }
    assertSame(MapCodec.DITHER, this.mapper.getMcv2DefaultCodec());
    assertSame(Mcv2Hosting.INJECTOR, this.mapper.getMcv2PackHosting());
    assertEquals(25580, this.mapper.getMcv2PackHttpPort());
    final String text = logged.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("Invalid mcv2.default-codec vp9, using DITHER"), text);
    assertTrue(text.contains("Invalid mcv2.pack.hosting ftp, using INJECTOR"), text);
    assertTrue(text.contains("Invalid mcv2.pack.http-port 70000, using 25580"), text);
  }

  @Test
  void readsEverySettingOfAnExistingFile() throws IOException {
    this.writeConfiguration(FULL_CONFIGURATION);
    this.mapper.deserialize();
    verify(this.plugin, never()).saveResource(anyString(), anyBoolean());
    final String token = this.mapper.getDiscordBotToken();
    final String channel = this.mapper.getDiscordBotChannelId();
    final String guild = this.mapper.getDiscordBotGuildId();
    final String host = this.mapper.getHttpHostName();
    final int port = this.mapper.getHttpPort();
    final boolean discord = this.mapper.isDiscordBotEnabled();
    final boolean http = this.mapper.isHttpEnabled();
    final boolean voiceChat = this.mapper.isSimpleVoiceChatEnabled();
    assertEquals("secret-token", token);
    assertEquals("456", channel);
    assertEquals("123", guild);
    assertEquals("mc.example.com", host);
    assertEquals(8080, port);
    assertTrue(discord);
    assertTrue(http);
    assertTrue(voiceChat);
    assertEquals(3, this.mapper.getMcv2EncoderThreads());
    assertEquals("off", this.mapper.getMcv2Native());
    assertTrue(this.mapper.isBrowserPrivateNetworks());
    assertTrue(this.mapper.isBrowserJavaScriptJit());
    assertTrue(this.mapper.isBrowserAutoplaySound());
  }

  @Test
  void fallsBackToDefaultsForMissingSettings() throws IOException {
    this.writeConfiguration("language: FR_FR\n");
    this.mapper.deserialize();
    final Locale locale = this.mapper.getLocale();
    final String token = this.mapper.getDiscordBotToken();
    final String host = this.mapper.getHttpHostName();
    final int port = this.mapper.getHttpPort();
    final boolean discord = this.mapper.isDiscordBotEnabled();
    assertSame(Locale.EN_US, locale);
    assertEquals("", token);
    assertEquals("localhost", host);
    assertEquals(3000, port);
    assertFalse(discord);
  }

  @ParameterizedTest
  @CsvSource({ "1, 1", "65535, 65535", "0, 3000", "-5, 3000", "65536, 3000" })
  void acceptsOnlyValidPorts(final int configured, final int expected) throws IOException {
    this.writeConfiguration("http-server:\n  port: " + configured + "\n");
    this.mapper.deserialize();
    final int port = this.mapper.getHttpPort();
    assertEquals(expected, port);
  }

  @ParameterizedTest
  @CsvSource({ "0, 0", "1, 1", "256, 256", "-1, 0", "257, 0" })
  void acceptsOnlyValidEncoderThreads(final int configured, final int expected) throws IOException {
    this.writeConfiguration("mcv2:\n  encoder-threads: " + configured + "\n");
    this.mapper.deserialize();
    assertEquals(expected, this.mapper.getMcv2EncoderThreads());
  }

  @ParameterizedTest
  @CsvSource({ "auto, auto", "off, off", "on, auto", "OFF, auto" })
  void acceptsOnlyTheNativeModes(final String configured, final String expected) throws IOException {
    this.writeConfiguration("mcv2:\n  native: \"" + configured + "\"\n");
    this.mapper.deserialize();
    assertEquals(expected, this.mapper.getMcv2Native());
  }

  @ParameterizedTest
  @CsvSource({ "off, off", "false, off", "on, auto", "true, auto" })
  void readsTheNativeModeWrittenWithoutQuotes(final String configured, final String expected) throws IOException {
    this.writeConfiguration("mcv2:\n  native: " + configured + "\n");
    this.mapper.deserialize();
    assertEquals(expected, this.mapper.getMcv2Native());
  }

  @Test
  void warnsOfAnInvalidNativeModeOnly() throws IOException {
    final PrintStream original = System.err;
    final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
    try {
      this.writeConfiguration("mcv2:\n  native: \"off\"\n");
      this.mapper.deserialize();
      this.writeConfiguration("mcv2:\n  native: \"sometimes\"\n");
      this.mapper.deserialize();
    } finally {
      System.setErr(original);
    }
    final String text = logged.toString(StandardCharsets.UTF_8);
    assertEquals(1, text.split("using auto", -1).length - 1, text);
  }

  @Test
  void warnsOfAnInvalidEncoderBudgetOnly() throws IOException {
    // the tests log through slf4j-simple, which writes to the standard error stream of the moment
    final PrintStream original = System.err;
    final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
    try {
      this.writeConfiguration("mcv2:\n  encoder-threads: 0\n");
      this.mapper.deserialize();
      this.writeConfiguration("mcv2:\n  encoder-threads: -1\n");
      this.mapper.deserialize();
    } finally {
      System.setErr(original);
    }
    // the default, 0, is no mistake; -1 is
    final String text = logged.toString(StandardCharsets.UTF_8);
    assertEquals(1, text.split("using half the processors", -1).length - 1, text);
  }

  @Test
  void failsWhenTheFileCannotBeRead() throws IOException {
    final Path file = this.folder.resolve("config.yml");
    final byte[] invalidUtf8 = { (byte) 0xC3, (byte) 0x28 };
    Files.write(file, invalidUtf8);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, this.mapper::deserialize);
    final String message = exception.getMessage();
    final boolean mentionsFile = message.startsWith("Failed to read " + file + ": ");
    assertTrue(mentionsFile, message);
    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void failsInsteadOfUsingTheDefaultsWhenTheFileIsNotValidYaml() throws IOException {
    this.writeConfiguration("a: [\n");
    final IllegalStateException exception = assertThrows(IllegalStateException.class, this.mapper::deserialize);
    final Path file = this.folder.resolve("config.yml");
    final String message = exception.getMessage();
    final boolean mentionsFile = message.startsWith(file + " is not valid YAML: ");
    assertTrue(mentionsFile, message);
    final Throwable cause = exception.getCause();
    assertInstanceOf(InvalidConfigurationException.class, cause);
  }

  @Test
  void keepsThePreviousSettingsWhenABrokenFileIsRead() throws IOException {
    this.writeConfiguration("http-server:\n  port: 8080\n");
    this.mapper.deserialize();
    this.writeConfiguration("http-server:\n  port: [\n");
    assertThrows(IllegalStateException.class, this.mapper::deserialize);
    final int port = this.mapper.getHttpPort();
    assertEquals(8080, port);
  }

  @Test
  void refusesANullPlugin() {
    assertThrows(NullPointerException.class, () -> new PluginDataConfigurationMapper(null));
  }

  @Test
  void failsWhenTheBundledFileCannotBeSaved() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, this.mapper::deserialize);
    verify(this.plugin).saveResource("config.yml", false);
    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }
}
