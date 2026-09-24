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
package me.brandonli.mcav.sandbox.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the plugin on a real, headless Paper 26.2 server together with Simple Voice Chat, with the mcav modules of
 * this build, and drives it through the server console and its audio web server. The server downloads the libraries
 * of the plugin, loads the JavaCV natives and installs VLC and yt-dlp exactly as on a production server, so this test
 * needs the internet.
 *
 * <p>Running a Minecraft server means accepting the Minecraft EULA, so the test only runs when the build passes
 * {@code -Pmcav.acceptMinecraftEula=true}.
 */
final class PaperServerEndToEndTest {

  private static final RemoteFile PAPER = new RemoteFile(
    "https://fill-data.papermc.io/v1/objects/7b7b3b43c009103e1971a0576c26f655a7dd9b56a0a2a4438e352c03a7fecd08/paper-26.2-123.jar",
    "paper-26.2-123.jar",
    "SHA-256",
    "7b7b3b43c009103e1971a0576c26f655a7dd9b56a0a2a4438e352c03a7fecd08"
  );
  private static final RemoteFile VOICE_CHAT = new RemoteFile(
    "https://cdn.modrinth.com/data/9eGKb6K1/versions/IhqyykOv/voicechat-bukkit-2.6.23.jar",
    "voicechat-bukkit-2.6.23.jar",
    "SHA-512",
    "3f01340bb29e03c0ba3ebb250b461bea6503a76544c9468fb228738a04fe9c21bfee83b5606e9b403c2b0587da9ad8d08687f6442e0dee392bf6010697641d96"
  );

  // the first start downloads the libraries of the plugin, VLC and yt-dlp
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(20);
  private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(1);
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofMinutes(3);
  private static final int HTTP_OK = 200;
  private static final String FLAT_WORLD_SETTINGS =
    "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2}," +
    "{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\"}";

  @Test
  void enablesThePluginWithItsAudioOutputsOnAHeadlessServer(@TempDir final Path serverDirectory) throws Exception {
    final boolean eulaAccepted = Boolean.getBoolean("mcav.e2e.acceptEula");
    Assumptions.assumeTrue(eulaAccepted, "Running a Minecraft server needs the Minecraft EULA; pass -Pmcav.acceptMinecraftEula=true");
    final Path cache = requirePath("mcav.e2e.cacheDirectory");
    final Path paperJar = download(cache, PAPER);
    final Path voiceChatJar = download(cache, VOICE_CHAT);
    final Path pluginJar = requirePath("mcav.e2e.pluginJar");
    final int httpPort = findFreePort();
    prepareServer(serverDirectory, pluginJar, voiceChatJar, httpPort);

    final List<String> command = createCommand(paperJar);
    final LibraryCache libraryCache = lendLibraries(cache, serverDirectory);
    // closed in reverse order: the server stops first, then the repository, then the libraries go back to the cache
    try (
      libraryCache;
      final LocalMavenRepositoryServer repository = startRepository();
      final ServerProcess server = ServerProcess.start(serverDirectory, command)
    ) {
      server.awaitLine(0, PaperServerEndToEndTest::isStartupComplete, STARTUP_TIMEOUT);
      final int servedFileCount = repository.getServedFileCount();
      assertCurrentModuleJars(serverDirectory, pluginJar);
      System.out.println("Local repository files served: " + servedFileCount + "; cached module hashes verified against this plugin");
      runCommand(server, "plugins", "MCAV");
      runCommand(server, "mcav help", "mcav dump");
      final String mediaInfo = fetchMediaInfo(server, httpPort);
      server.command("stop");
      final int exitCode = server.awaitExit(SHUTDOWN_TIMEOUT);
      final List<String> output = server.getLines();
      assertRanCleanly(exitCode, mediaInfo, output);
    }
  }

  /**
   * Verifies current module bytes even on a warm cache, where valid Gremlin hashes deliberately avoid HTTP.
   */
  private static void assertCurrentModuleJars(final Path serverDirectory, final Path pluginJar) throws IOException {
    final String manifest;
    try (final ZipFile plugin = new ZipFile(pluginJar.toFile())) {
      final ZipEntry entry = plugin.getEntry("dependencies.txt");
      assertNotNull(entry, "the plugin must carry the dependency hashes produced by this build");
      try (final InputStream input = plugin.getInputStream(entry)) {
        final byte[] bytes = input.readAllBytes();
        manifest = new String(bytes, StandardCharsets.UTF_8);
      }
    }
    final Set<String> expected = new HashSet<>();
    final List<String> lines = manifest.lines().toList();
    for (final String line : lines) {
      if (line.startsWith("me.brandonli:mcav-")) {
        final String[] fields = line.split(" ", 2);
        assertEquals(2, fields.length, "module coordinates must have a SHA-256 digest");
        expected.add(fields[1]);
      }
    }
    assertTrue(!expected.isEmpty(), "the plugin must request the modules of this build");
    final Path modules = serverDirectory.resolve("libraries/mcav/me/brandonli");
    final List<Path> files;
    try (final Stream<Path> walk = Files.walk(modules)) {
      files = walk.filter(Files::isRegularFile).toList();
    }
    final Set<String> actual = new HashSet<>();
    for (final Path file : files) {
      final Path fileName = file.getFileName();
      final String name = fileName.toString();
      if (name.endsWith(".jar")) {
        actual.add(hash(file, "SHA-256"));
      }
    }
    for (final String digest : expected) {
      assertTrue(actual.contains(digest), "the running server's module cache must contain build digest " + digest);
    }
  }

  /**
   * Hands the libraries that earlier runs downloaded, kept in the download cache of the build, to the server.
   */
  private static LibraryCache lendLibraries(final Path cache, final Path serverDirectory) throws IOException {
    final Path cachedLibraries = cache.resolve("libraries");
    final Path serverLibraries = serverDirectory.resolve("libraries/mcav");
    return LibraryCache.lend(cachedLibraries, serverLibraries);
  }

  /**
   * Serves the repository the build published the modules into, on the port the build wrote into
   * {@code dependencies.txt} as the first repository, so the server downloads the modules of this build.
   */
  private static LocalMavenRepositoryServer startRepository() throws IOException {
    final Path repositoryDirectory = requirePath("mcav.e2e.repositoryDirectory");
    final int repositoryPort = requirePort("mcav.e2e.repositoryPort");
    return LocalMavenRepositoryServer.start(repositoryDirectory, repositoryPort);
  }

  private static void assertRanCleanly(final int exitCode, final String mediaInfo, final List<String> output) {
    assertEquals(0, exitCode, "the server stops cleanly");
    final boolean json = mediaInfo.startsWith("{");
    assertTrue(json, mediaInfo);
    assertLogged(output, "MCAV loaded in");
    assertLogged(output, "JavaCV natives loaded in");
    assertLogged(output, "Simple Voice Chat audio is ready");
    assertNoErrors(output);
  }

  private static boolean isStartupComplete(final String line) {
    return line.contains("Done (") && line.contains("For help, type");
  }

  /**
   * Sends a console command and waits for its answer; the test fails when the expected text does not appear in time.
   */
  private static void runCommand(final ServerProcess server, final String command, final String expectedText) throws InterruptedException {
    final int firstLine = server.getLineCount();
    server.command(command);
    server.awaitLine(firstLine, line -> line.contains(expectedText), COMMAND_TIMEOUT);
  }

  /**
   * Waits until the plugin reports that its audio web server, which it starts in the background, is listening, and
   * then asks the server for the media information.
   */
  private static String fetchMediaInfo(final ServerProcess server, final int port) throws IOException, InterruptedException {
    server.awaitLine(0, line -> line.contains("The audio web page is available at"), HTTP_TIMEOUT);
    final URI uri = URI.create("http://127.0.0.1:" + port + "/media");
    final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri);
    requestBuilder.timeout(REQUEST_TIMEOUT);
    final HttpRequest request = requestBuilder.build();
    final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
    try (final HttpClient client = HttpClient.newHttpClient()) {
      final HttpResponse<String> response = client.send(request, handler);
      final int status = response.statusCode();
      assertEquals(HTTP_OK, status, "the audio web server answers the media request");
      return response.body();
    }
  }

  private static void assertLogged(final List<String> output, final String text) {
    for (final String line : output) {
      if (line.contains(text)) {
        return;
      }
    }
    fail("the server log must contain \"" + text + "\"");
  }

  private static void assertNoErrors(final List<String> output) {
    final List<String> errors = new ArrayList<>();
    for (final String line : output) {
      final boolean error = line.contains(" ERROR]") || line.contains("Error occurred while enabling");
      if (error) {
        errors.add(line);
      }
    }
    final String separator = System.lineSeparator();
    final String report = String.join(separator, errors);
    final boolean clean = errors.isEmpty();
    assertTrue(clean, "the server logged errors:" + separator + report);
  }

  private static List<String> createCommand(final Path paperJar) {
    final String java = System.getProperty("mcav.e2e.java", "java");
    final String jar = paperJar.toString();
    return List.of(java, "-Xms1G", "-Xmx3G", "-Djava.awt.headless=true", "-jar", jar, "--nogui");
  }

  private static void prepareServer(final Path serverDirectory, final Path pluginJar, final Path voiceChatJar, final int httpPort)
    throws IOException {
    writeServerProperties(serverDirectory);
    final Path eula = serverDirectory.resolve("eula.txt");
    Files.writeString(eula, "eula=true\n", StandardCharsets.UTF_8);

    final Path pluginsDirectory = serverDirectory.resolve("plugins");
    Files.createDirectories(pluginsDirectory);
    final Path installedPlugin = pluginsDirectory.resolve("mcav-sandbox.jar");
    Files.copy(pluginJar, installedPlugin);
    final Path installedVoiceChat = pluginsDirectory.resolve(VOICE_CHAT.fileName);
    Files.copy(voiceChatJar, installedVoiceChat);

    writePluginConfiguration(pluginsDirectory, httpPort);
    writeVoiceChatConfiguration(pluginsDirectory);
  }

  /**
   * Writes the server settings: a small flat world, whose layers must be given, since Paper logs an error for a flat
   * world without them, and no network services beyond the game port.
   */
  private static void writeServerProperties(final Path serverDirectory) throws IOException {
    final int port = findFreePort();
    final String properties = String.join(
      "\n",
      "online-mode=false",
      "server-port=" + port,
      "level-type=minecraft\\:flat",
      "generator-settings=" + FLAT_WORLD_SETTINGS,
      "generate-structures=false",
      "spawn-protection=0",
      "view-distance=3",
      "simulation-distance=3",
      "max-players=2",
      "enable-rcon=false",
      "enable-query=false",
      "motd=mcav end-to-end test",
      ""
    );
    final Path file = serverDirectory.resolve("server.properties");
    Files.writeString(file, properties, StandardCharsets.UTF_8);
  }

  /**
   * Turns on the audio web server and Simple Voice Chat audio; the Discord bot needs a real token, so it stays off.
   */
  private static void writePluginConfiguration(final Path pluginsDirectory, final int httpPort) throws IOException {
    final Path dataFolder = pluginsDirectory.resolve("MCAV");
    Files.createDirectories(dataFolder);
    final String configuration = String.join(
      "\n",
      "language: EN_US",
      "discord-bot:",
      "  enabled: false",
      "  token: none",
      "  guild-id: none",
      "  channel-id: none",
      "http-server:",
      "  enabled: true",
      "  host-name: localhost",
      "  port: " + httpPort,
      "simple-voice-chat:",
      "  enabled: true",
      ""
    );
    final Path file = dataFolder.resolve("config.yml");
    Files.writeString(file, configuration, StandardCharsets.UTF_8);
  }

  /**
   * Moves the voice chat server to a free UDP port, so the test never clashes with a voice chat server on the machine.
   */
  private static void writeVoiceChatConfiguration(final Path pluginsDirectory) throws IOException {
    final Path voiceChatFolder = pluginsDirectory.resolve("voicechat");
    Files.createDirectories(voiceChatFolder);
    final int voicePort = findFreeUdpPort();
    final Path file = voiceChatFolder.resolve("voicechat-server.properties");
    Files.writeString(file, "port=" + voicePort + "\n", StandardCharsets.UTF_8);
  }

  private static int findFreePort() throws IOException {
    try (final ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static int findFreeUdpPort() throws IOException {
    try (final DatagramSocket socket = new DatagramSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /**
   * Gets a file from the download cache of the build, downloading it first when it is missing or damaged.
   */
  private static Path download(final Path cache, final RemoteFile remoteFile) throws IOException, InterruptedException {
    Files.createDirectories(cache);
    final Path file = cache.resolve(remoteFile.fileName);
    if (Files.isRegularFile(file)) {
      final String cachedHash = hash(file, remoteFile.algorithm);
      if (cachedHash.equals(remoteFile.expectedHash)) {
        return file;
      }
    }
    final Path partial = cache.resolve(remoteFile.fileName + ".part");
    fetch(remoteFile.url, partial);
    final String downloadedHash = hash(partial, remoteFile.algorithm);
    assertEquals(remoteFile.expectedHash, downloadedHash, "the file downloaded from " + remoteFile.url + " is corrupt");
    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
    return file;
  }

  private static void fetch(final String url, final Path target) throws IOException, InterruptedException {
    final URI uri = URI.create(url);
    final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri);
    final HttpRequest request = requestBuilder.build();
    final HttpResponse.BodyHandler<Path> handler = HttpResponse.BodyHandlers.ofFile(
      target,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING
    );
    final HttpClient.Builder clientBuilder = HttpClient.newBuilder();
    clientBuilder.connectTimeout(CONNECT_TIMEOUT);
    clientBuilder.followRedirects(HttpClient.Redirect.NORMAL);
    try (final HttpClient client = clientBuilder.build()) {
      final HttpResponse<Path> response = client.send(request, handler);
      final int status = response.statusCode();
      assertEquals(HTTP_OK, status, "the file could not be downloaded from " + url);
    }
  }

  private static String hash(final Path file, final String algorithm) throws IOException {
    final MessageDigest digest = createDigest(algorithm);
    try (final InputStream input = Files.newInputStream(file)) {
      final byte[] chunk = new byte[64 * 1024];
      int readCount = input.read(chunk);
      while (readCount >= 0) {
        digest.update(chunk, 0, readCount);
        readCount = input.read(chunk);
      }
    }
    final byte[] hashBytes = digest.digest();
    final HexFormat hexFormat = HexFormat.of();
    return hexFormat.formatHex(hashBytes);
  }

  private static MessageDigest createDigest(final String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("The Java runtime does not support " + algorithm, exception);
    }
  }

  private static Path requirePath(final String property) {
    final String value = System.getProperty(property);
    assertNotNull(value, "the build passes -D" + property + " to this test");
    return Path.of(value);
  }

  private static int requirePort(final String property) {
    final int port = Integer.getInteger(property, 0);
    assertTrue(port > 0, "the build passes -D" + property + " to this test");
    return port;
  }

  /**
   * A file the test downloads, with the hash it must have.
   */
  private static final class RemoteFile {

    private final String url;
    private final String fileName;
    private final String algorithm;
    private final String expectedHash;

    private RemoteFile(final String url, final String fileName, final String algorithm, final String expectedHash) {
      this.url = url;
      this.fileName = fileName;
      this.algorithm = algorithm;
      this.expectedHash = expectedHash;
    }
  }
}
