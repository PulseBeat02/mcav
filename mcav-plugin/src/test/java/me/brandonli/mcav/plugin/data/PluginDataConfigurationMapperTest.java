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
package me.brandonli.mcav.plugin.data;

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
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.command.interaction.VncAllowList;
import me.brandonli.mcav.plugin.locale.Locale;
import me.brandonli.mcav.plugin.utils.IoUtils;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.plugin.utils.Mcv2Hosting;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

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
    confine-chromium: false
    refused-hosts: ["203.0.113.5", "play.example.com"]
  vm:
    allow-network: true
  """;

  private static final String DEFAULT_HTTP_HOST = "localhost";
  private static final int DEFAULT_HTTP_PORT = 3000;
  private static final String DEFAULT_MCV2_NATIVE = "auto";
  private static final int DEFAULT_MCV2_PACK_HTTP_PORT = 25580;

  private static final int CONFIGURED_HTTP_PORT = 8080;
  private static final String REFUSED_HOST_NAME = "play.example.com";

  private static final String KEPT_VNC_HOST = "kept.example";
  private static final int KEPT_VNC_PORT = 5904;

  private static final String CONFIGURATION_FILE_NAME = "config.yml";

  private static final String ALLOWED_HOSTS_WARNING_PATTERN = "of vnc.allowed-hosts, which is not";
  private static final String REFUSED_HOSTS_WARNING_PATTERN = "of browser.refused-hosts, which is not";
  private static final String NATIVE_MODE_WARNING_PATTERN = "using auto";
  private static final String ENCODER_BUDGET_WARNING_PATTERN = "using half the processors";

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

  @Test
  void shouldReadTheAllowedVncServersWithTheirPasswords() throws IOException {
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
      VncAllowList.Entry.of("127.0.0.1", 5901, "secret"),
      VncAllowList.Entry.of("desktop.example", 5902, "1234"),
      VncAllowList.Entry.of("open.example", 5903, null)
    );

    final List<VncAllowList.Entry> entries = list.getEntries();
    assertEquals(expected, entries);
  }

  @Test
  void shouldAcceptTheLowestAndTheHighestVncPort() throws IOException {
    this.writeConfiguration(
      """
      vnc:
        allowed-hosts:
          - host: lowest.example
            port: 1
          - host: highest.example
            port: 65535
      """
    );
    this.mapper.deserialize();
    final VncAllowList list = this.mapper.getVncAllowList();
    final List<VncAllowList.Entry> expected = List.of(
      VncAllowList.Entry.of("lowest.example", 1, null),
      VncAllowList.Entry.of("highest.example", 65535, null)
    );

    final List<VncAllowList.Entry> entries = list.getEntries();
    assertEquals(expected, entries);
  }

  @Test
  void shouldAllowNoVncServerByDefault() {
    this.saveBundledResources();
    this.mapper.deserialize();

    final List<VncAllowList.Entry> entries = this.mapper.getVncAllowList().getEntries();
    assertEquals(List.of(), entries);
  }

  private void saveBundledResources() {
    doAnswer(this::copyResource).when(this.plugin).saveResource(anyString(), anyBoolean());
  }

  private @Nullable Object copyResource(final InvocationOnMock invocation) throws IOException {
    final String name = invocation.getArgument(0);
    final Path target = this.folder.resolve(name);
    try (final InputStream stream = IoUtils.getResourceAsStream(name)) {
      Files.copy(stream, target);
    }
    return null;
  }

  @Test
  void shouldSkipInvalidVncEntriesAndNeverLogsAPassword() throws IOException {
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

    final List<VncAllowList.Entry> expected = List.of(VncAllowList.Entry.of(KEPT_VNC_HOST, KEPT_VNC_PORT, null));
    final List<VncAllowList.Entry> entries = this.mapper.getVncAllowList().getEntries();
    assertEquals(expected, entries);

    final String text = logged.toString(StandardCharsets.UTF_8);

    final int matches = text.split(ALLOWED_HOSTS_WARNING_PATTERN, -1).length - 1;
    assertEquals(5, matches, text);

    final boolean contained = text.contains("hunter2");
    assertFalse(contained, text);
  }

  @ParameterizedTest
  @ValueSource(strings = { "[]", "[ ]" })
  void shouldSkipHostsThatBecomeBlankAfterRemovingIpv6Brackets(final String host) throws IOException {
    final PrintStream original = System.err;
    final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
    try {
      this.writeConfiguration(
        """
        vnc:
          allowed-hosts:
            - host: "%s"
              port: 5901
              password: hidden-secret
            - host: kept.example
              port: 5904
        """.formatted(host)
      );
      this.mapper.deserialize();
    } finally {
      System.setErr(original);
    }

    final List<VncAllowList.Entry> expected = List.of(VncAllowList.Entry.of(KEPT_VNC_HOST, KEPT_VNC_PORT, null));
    final List<VncAllowList.Entry> entries = this.mapper.getVncAllowList().getEntries();
    assertEquals(expected, entries);

    final String text = logged.toString(StandardCharsets.UTF_8);

    final int matches = text.split(ALLOWED_HOSTS_WARNING_PATTERN, -1).length - 1;
    assertEquals(1, matches, text);

    final boolean contained = text.contains("hidden-secret");
    assertFalse(contained, text);
  }

  private void writeConfiguration(final String yaml) throws IOException {
    final Path file = this.folder.resolve(CONFIGURATION_FILE_NAME);
    Files.writeString(file, yaml, StandardCharsets.UTF_8);
  }

  @Test
  void shouldUseDefaultsBeforeReading() {
    final MCAVSandbox owner = this.mapper.getPlugin();
    final Locale locale = this.mapper.getLocale();
    final String token = this.mapper.getDiscordBotToken();
    final String channel = this.mapper.getDiscordBotChannelId();
    final String guild = this.mapper.getDiscordBotGuildId();
    final String host = this.mapper.getHttpHostName();
    final int port = this.mapper.getHttpPort();
    final boolean discordBotEnabled = this.mapper.isDiscordBotEnabled();
    final boolean httpEnabled = this.mapper.isHttpEnabled();
    final boolean voiceChatEnabled = this.mapper.isSimpleVoiceChatEnabled();
    assertSame(this.plugin, owner);
    assertSame(Locale.EN_US, locale);
    assertEquals("", token);
    assertEquals("", channel);
    assertEquals("", guild);
    assertEquals(DEFAULT_HTTP_HOST, host);
    assertEquals(DEFAULT_HTTP_PORT, port);
    assertFalse(discordBotEnabled);
    assertFalse(httpEnabled);
    assertFalse(voiceChatEnabled);

    final int mcv2EncoderThreads = this.mapper.getMcv2EncoderThreads();
    assertEquals(0, mcv2EncoderThreads);

    final String mcv2Native = this.mapper.getMcv2Native();
    assertEquals(DEFAULT_MCV2_NATIVE, mcv2Native);

    final boolean browserPrivateNetworks = this.mapper.isBrowserPrivateNetworks();
    assertFalse(browserPrivateNetworks);

    final boolean browserJavaScriptJit = this.mapper.isBrowserJavaScriptJit();
    assertFalse(browserJavaScriptJit);

    final boolean browserAutoplaySound = this.mapper.isBrowserAutoplaySound();
    assertFalse(browserAutoplaySound);

    final boolean browserConfineChromium = this.mapper.isBrowserConfineChromium();
    assertTrue(browserConfineChromium, "Chromium is confined where the system can");

    final List<String> browserRefusedHosts = this.mapper.getBrowserRefusedHosts();
    assertEquals(List.of(), browserRefusedHosts);

    final boolean vmAllowNetwork = this.mapper.isVmAllowNetwork();
    assertFalse(vmAllowNetwork, "machines get a network that reaches nothing");

    final MapCodec mcv2DefaultCodec = this.mapper.getMcv2DefaultCodec();
    assertSame(MapCodec.DITHER, mcv2DefaultCodec);

    final Mcv2Hosting mcv2PackHosting = this.mapper.getMcv2PackHosting();
    assertSame(Mcv2Hosting.INJECTOR, mcv2PackHosting);

    final String mcv2PackHttpHost = this.mapper.getMcv2PackHttpHost();
    assertEquals("", mcv2PackHttpHost);

    final int mcv2PackHttpPort = this.mapper.getMcv2PackHttpPort();
    assertEquals(DEFAULT_MCV2_PACK_HTTP_PORT, mcv2PackHttpPort);
  }

  @Test
  void shouldCreateTheBundledConfigurationWhenItIsMissing() {
    this.saveBundledResources();
    this.mapper.deserialize();
    verify(this.plugin).saveResource(CONFIGURATION_FILE_NAME, false);

    final Path file = this.folder.resolve(CONFIGURATION_FILE_NAME);
    final boolean exists = Files.isRegularFile(file);
    assertTrue(exists);
  }

  @Test
  void shouldReadTheBundledConfiguration() {
    this.saveBundledResources();
    this.mapper.deserialize();
    final Locale locale = this.mapper.getLocale();
    final String token = this.mapper.getDiscordBotToken();
    final String channel = this.mapper.getDiscordBotChannelId();
    final String guild = this.mapper.getDiscordBotGuildId();
    final String host = this.mapper.getHttpHostName();
    final int port = this.mapper.getHttpPort();
    final boolean discordBotEnabled = this.mapper.isDiscordBotEnabled();
    final boolean httpEnabled = this.mapper.isHttpEnabled();
    final boolean voiceChatEnabled = this.mapper.isSimpleVoiceChatEnabled();
    assertSame(Locale.EN_US, locale);
    assertEquals("none", token);
    assertEquals("none", channel);
    assertEquals("none", guild);
    assertEquals(DEFAULT_HTTP_HOST, host);
    assertEquals(DEFAULT_HTTP_PORT, port);
    assertFalse(discordBotEnabled);
    assertFalse(httpEnabled);
    assertFalse(voiceChatEnabled);

    final int mcv2EncoderThreads = this.mapper.getMcv2EncoderThreads();
    assertEquals(0, mcv2EncoderThreads);

    final String mcv2Native = this.mapper.getMcv2Native();
    assertEquals(DEFAULT_MCV2_NATIVE, mcv2Native);

    final boolean browserPrivateNetworks = this.mapper.isBrowserPrivateNetworks();
    assertFalse(browserPrivateNetworks, "the bundled file keeps the browser on public addresses");

    final boolean browserJavaScriptJit = this.mapper.isBrowserJavaScriptJit();
    assertFalse(browserJavaScriptJit, "and JavaScript without its compiler");

    final boolean browserAutoplaySound = this.mapper.isBrowserAutoplaySound();
    assertFalse(browserAutoplaySound, "and pages silent until a player clicked them");

    final boolean browserConfineChromium = this.mapper.isBrowserConfineChromium();
    assertTrue(browserConfineChromium, "and Chromium confined");

    final List<String> browserRefusedHosts = this.mapper.getBrowserRefusedHosts();
    assertEquals(List.of(), browserRefusedHosts, "and no more hosts refused");

    final MapCodec mcv2DefaultCodec = this.mapper.getMcv2DefaultCodec();
    assertSame(MapCodec.DITHER, mcv2DefaultCodec, "and the maps dithered unless a command asks for MCV2");

    final Mcv2Hosting mcv2PackHosting = this.mapper.getMcv2PackHosting();
    assertSame(Mcv2Hosting.INJECTOR, mcv2PackHosting);

    final String mcv2PackHttpHost = this.mapper.getMcv2PackHttpHost();
    assertEquals("", mcv2PackHttpHost);

    final int mcv2PackHttpPort = this.mapper.getMcv2PackHttpPort();
    assertEquals(DEFAULT_MCV2_PACK_HTTP_PORT, mcv2PackHttpPort);
  }

  @Test
  void shouldReadTheCodecAndThePackHostingInAnyCase() throws IOException {
    this.writeConfiguration(
      "mcv2:\n  default-codec: MCV2\n  pack:\n    hosting: Http\n    http-host: \" mc.example \"\n    http-port: 8443\n"
    );
    this.mapper.deserialize();

    final MapCodec mcv2DefaultCodec = this.mapper.getMcv2DefaultCodec();
    assertSame(MapCodec.MCV2, mcv2DefaultCodec);

    final Mcv2Hosting mcv2PackHosting = this.mapper.getMcv2PackHosting();
    assertSame(Mcv2Hosting.HTTP, mcv2PackHosting);

    final String mcv2PackHttpHost = this.mapper.getMcv2PackHttpHost();
    assertEquals("mc.example", mcv2PackHttpHost);

    final int mcv2PackHttpPort = this.mapper.getMcv2PackHttpPort();
    assertEquals(8443, mcv2PackHttpPort);

    this.writeConfiguration("mcv2:\n  pack:\n    hosting: website\n");
    this.mapper.deserialize();

    final Mcv2Hosting mapperMcv2PackHosting = this.mapper.getMcv2PackHosting();
    assertSame(Mcv2Hosting.WEBSITE, mapperMcv2PackHosting);
  }

  @Test
  void shouldWarnOfAnInvalidCodecHostingOrPortAndKeepTheDefaults() throws IOException {
    final PrintStream original = System.err;
    final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
    try {
      this.writeConfiguration("mcv2:\n  default-codec: vp9\n  pack:\n    hosting: ftp\n    http-port: 70000\n");
      this.mapper.deserialize();
    } finally {
      System.setErr(original);
    }

    final MapCodec mcv2DefaultCodec = this.mapper.getMcv2DefaultCodec();
    assertSame(MapCodec.DITHER, mcv2DefaultCodec);

    final Mcv2Hosting mcv2PackHosting = this.mapper.getMcv2PackHosting();
    assertSame(Mcv2Hosting.INJECTOR, mcv2PackHosting);

    final int mcv2PackHttpPort = this.mapper.getMcv2PackHttpPort();
    assertEquals(DEFAULT_MCV2_PACK_HTTP_PORT, mcv2PackHttpPort);

    final String text = logged.toString(StandardCharsets.UTF_8);

    final boolean contained = text.contains("Invalid mcv2.default-codec vp9, using DITHER");
    assertTrue(contained, text);

    final boolean textContained = text.contains("Invalid mcv2.pack.hosting ftp, using INJECTOR");
    assertTrue(textContained, text);

    final boolean secondContained = text.contains("Invalid mcv2.pack.http-port 70000, using 25580");
    assertTrue(secondContained, text);
  }

  @Test
  void shouldReadEverySettingOfAnExistingFile() throws IOException {
    this.writeConfiguration(FULL_CONFIGURATION);
    this.mapper.deserialize();
    verify(this.plugin, never()).saveResource(anyString(), anyBoolean());

    final String token = this.mapper.getDiscordBotToken();
    final String channel = this.mapper.getDiscordBotChannelId();
    final String guild = this.mapper.getDiscordBotGuildId();
    final String host = this.mapper.getHttpHostName();
    final int port = this.mapper.getHttpPort();
    final boolean discordBotEnabled = this.mapper.isDiscordBotEnabled();
    final boolean httpEnabled = this.mapper.isHttpEnabled();
    final boolean voiceChatEnabled = this.mapper.isSimpleVoiceChatEnabled();
    assertEquals("secret-token", token);
    assertEquals("456", channel);
    assertEquals("123", guild);
    assertEquals("mc.example.com", host);
    assertEquals(CONFIGURED_HTTP_PORT, port);
    assertTrue(discordBotEnabled);
    assertTrue(httpEnabled);
    assertTrue(voiceChatEnabled);

    final int mcv2EncoderThreads = this.mapper.getMcv2EncoderThreads();
    assertEquals(3, mcv2EncoderThreads);

    final String mcv2Native = this.mapper.getMcv2Native();
    assertEquals("off", mcv2Native);

    final boolean browserPrivateNetworks = this.mapper.isBrowserPrivateNetworks();
    assertTrue(browserPrivateNetworks);

    final boolean browserJavaScriptJit = this.mapper.isBrowserJavaScriptJit();
    assertTrue(browserJavaScriptJit);

    final boolean browserAutoplaySound = this.mapper.isBrowserAutoplaySound();
    assertTrue(browserAutoplaySound);

    final boolean browserConfineChromium = this.mapper.isBrowserConfineChromium();
    assertFalse(browserConfineChromium);

    final List<String> browserRefusedHosts = this.mapper.getBrowserRefusedHosts();
    assertEquals(List.of("203.0.113.5", REFUSED_HOST_NAME), browserRefusedHosts);

    final boolean vmAllowNetwork = this.mapper.isVmAllowNetwork();
    assertTrue(vmAllowNetwork);
  }

  @Test
  void shouldFallBackToDefaultsForMissingSettings() throws IOException {
    this.writeConfiguration("language: FR_FR\n");
    this.mapper.deserialize();
    final Locale locale = this.mapper.getLocale();
    final String token = this.mapper.getDiscordBotToken();
    final String host = this.mapper.getHttpHostName();
    final int port = this.mapper.getHttpPort();
    final boolean discordBotEnabled = this.mapper.isDiscordBotEnabled();
    assertSame(Locale.EN_US, locale);
    assertEquals("", token);
    assertEquals(DEFAULT_HTTP_HOST, host);
    assertEquals(DEFAULT_HTTP_PORT, port);
    assertFalse(discordBotEnabled);
  }

  @ParameterizedTest
  @CsvSource({
    "1, 1",
    "65535, 65535",
    "0, 3000",
    "-5, 3000",
    "65536, 3000",
    // a number beyond an int is not cut down to its low bits, which are 80 here
    "4294967376, 3000",
    "18446744073709551696, 3000",
    "8080.9, 3000",
  })
  void shouldAcceptOnlyValidPorts(final String configured, final int expected) throws IOException {
    this.writeConfiguration("http-server:\n  port: " + configured + "\n");
    this.mapper.deserialize();
    final int port = this.mapper.getHttpPort();
    assertEquals(expected, port);
  }

  @ParameterizedTest
  @CsvSource({ "0, 0", "1, 1", "256, 256", "-1, 0", "257, 0", "4294967297, 0", "2.5, 0" })
  void shouldAcceptOnlyValidEncoderThreads(final String configured, final int expected) throws IOException {
    this.writeConfiguration("mcv2:\n  encoder-threads: " + configured + "\n");
    this.mapper.deserialize();

    final int mcv2EncoderThreads = this.mapper.getMcv2EncoderThreads();
    assertEquals(expected, mcv2EncoderThreads);
  }

  @Test
  void shouldSkipRefusedHostsThatAreNoHostAndLogThem() throws IOException {
    final PrintStream original = System.err;
    final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
    try {
      this.writeConfiguration("browser:\n  refused-hosts: [\" play.example.com \", \"two hosts\", \"a,b\", \"\"]\n");
      this.mapper.deserialize();
    } finally {
      System.setErr(original);
    }

    final List<String> browserRefusedHosts = this.mapper.getBrowserRefusedHosts();
    assertEquals(List.of(REFUSED_HOST_NAME), browserRefusedHosts);

    final String text = logged.toString(StandardCharsets.UTF_8);

    final int matches = text.split(REFUSED_HOSTS_WARNING_PATTERN, -1).length - 1;
    assertEquals(3, matches, text);
  }

  @ParameterizedTest
  @CsvSource({ "auto, auto", "off, off", "on, auto", "OFF, auto" })
  void shouldAcceptOnlyTheNativeModes(final String configured, final String expected) throws IOException {
    this.writeConfiguration("mcv2:\n  native: \"" + configured + "\"\n");
    this.mapper.deserialize();

    final String mcv2Native = this.mapper.getMcv2Native();
    assertEquals(expected, mcv2Native);
  }

  @ParameterizedTest
  @CsvSource({ "off, off", "false, off", "on, auto", "true, auto" })
  void shouldReadTheNativeModeWrittenWithoutQuotes(final String configured, final String expected) throws IOException {
    this.writeConfiguration("mcv2:\n  native: " + configured + "\n");
    this.mapper.deserialize();

    final String mcv2Native = this.mapper.getMcv2Native();
    assertEquals(expected, mcv2Native);
  }

  @Test
  void shouldWarnOfAnInvalidNativeModeOnly() throws IOException {
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

    final int matches = text.split(NATIVE_MODE_WARNING_PATTERN, -1).length - 1;
    assertEquals(1, matches, text);
  }

  @Test
  void shouldWarnOfAnInvalidEncoderBudgetOnly() throws IOException {
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

    final int matches = text.split(ENCODER_BUDGET_WARNING_PATTERN, -1).length - 1;
    assertEquals(1, matches, text);
  }

  @Test
  void shouldFailWhenTheFileCannotBeRead() throws IOException {
    final Path file = this.folder.resolve(CONFIGURATION_FILE_NAME);
    final byte[] invalidUtf8 = { (byte) 0xC3, (byte) 0x28 };
    Files.write(file, invalidUtf8);
    final PluginException exception = assertThrows(PluginException.class, this.mapper::deserialize);

    final String message = exception.getMessage();
    final boolean mentionsFile = message.startsWith("File " + file + " could not be read (");
    assertTrue(mentionsFile, message);

    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void shouldFailInsteadOfUsingTheDefaultsWhenTheFileIsNotValidYaml() throws IOException {
    this.writeConfiguration("a: [\n");
    final PluginException exception = assertThrows(PluginException.class, this.mapper::deserialize);

    final Path file = this.folder.resolve(CONFIGURATION_FILE_NAME);
    final String message = exception.getMessage();
    final boolean mentionsFile = message.startsWith("File " + file + " is not valid YAML (");
    assertTrue(mentionsFile, message);

    final Throwable cause = exception.getCause();
    assertInstanceOf(InvalidConfigurationException.class, cause);
  }

  @Test
  void shouldKeepThePreviousSettingsWhenABrokenFileIsRead() throws IOException {
    this.writeConfiguration("http-server:\n  port: 8080\n");
    this.mapper.deserialize();
    this.writeConfiguration("http-server:\n  port: [\n");
    assertThrows(PluginException.class, this.mapper::deserialize);

    final int port = this.mapper.getHttpPort();
    assertEquals(CONFIGURED_HTTP_PORT, port);
  }

  @Test
  void shouldRefuseANullPlugin() {
    assertThrows(NullPointerException.class, () -> new PluginDataConfigurationMapper(null));
  }

  @Test
  void shouldFailWhenTheBundledFileCannotBeSaved() {
    final PluginException exception = assertThrows(PluginException.class, this.mapper::deserialize);
    verify(this.plugin).saveResource(CONFIGURATION_FILE_NAME, false);

    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }
}
