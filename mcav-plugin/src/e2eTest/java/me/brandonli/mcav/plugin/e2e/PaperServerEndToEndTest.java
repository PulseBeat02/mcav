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
package me.brandonli.mcav.plugin.e2e;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PaperServerEndToEndTest {

  private static final String PROPERTY_MISSING_MESSAGE = "the build passes -D%s to this test";

  private static final String PAPER_FILE_NAME = requireNonNull(System.getProperty("mcav.e2e.paper_file_name"));
  private static final String VOICE_CHAT_FILE_NAME = requireNonNull(System.getProperty("mcav.e2e.voice_chat_file_name"));

  private static final String PAPER_SHA_256 = "dd64988a011729e6812f2fff1be32ba5c572ecdc8890e6abc7a56aa91b53f77d";

  private static final String SHA_256 = "SHA-256";

  private static final RemoteFile PAPER = new RemoteFile(
    "https://fill-data.papermc.io/v1/objects/%s/%s".formatted(PAPER_SHA_256, PAPER_FILE_NAME),
    PAPER_FILE_NAME,
    SHA_256,
    PAPER_SHA_256
  );
  private static final RemoteFile VOICE_CHAT = new RemoteFile(
    "https://cdn.modrinth.com/data/9eGKb6K1/versions/EJth3OAr/%s".formatted(VOICE_CHAT_FILE_NAME),
    VOICE_CHAT_FILE_NAME,
    "SHA-512",
    "7f1d5765e79cd42616f14f40322d1171a8505e1116dfff71c7bd0e59af9d255c06f70a68a5b622015c0407d40c80e70298c172992007ff339cedcef0116fec42"
  );

  // the first start downloads the libraries of the plugin, VLC and yt-dlp
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(20);
  private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(1);
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofMinutes(3);
  // the first browser on a machine downloads CEF, about 165 MB, and on Linux the libraries it needs
  private static final Duration BROWSER_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration SOUND_TIMEOUT = Duration.ofMinutes(1);
  private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration VNC_POLL_PAUSE = Duration.ofMillis(50);
  private static final Duration PAST_CLIP_END = Duration.ofSeconds(4);
  private static final Duration RELEASE_POLL_PAUSE = Duration.ofMillis(200);
  private static final Duration QEMU_PROBE_TIMEOUT = Duration.ofSeconds(30);

  private static final String TONE_IMAGE = "beep.img";
  private static final String CLIP = "clip.mp4";
  private static final String PICTURE = "picture.png";

  private static final Pattern PACK_SERVED = Pattern.compile("Serving the MCV2 pack ([0-9a-f-]{36}) with .* at (http://\\S+)");
  private static final int PACK_URL_GROUP = 2;
  private static final int PACK_ID_GROUP = 1;

  private static final String FLAT_WORLD_SETTINGS =
    "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\"}";

  private static final int MANIFEST_FIELD_COUNT = 2;

  private static final int MINIMUM_UPDATES = 2;

  private static final String LOOPBACK_ADDRESS = "127.0.0.1";

  private static final int EPHEMERAL_PORT = 0;

  private static final int CHUNK_BYTES = 64 * 1024;

  private static final int MISSING_PORT = 0;

  private static final int SOUND_SECONDS = 2;
  private static final int FREQUENCY_TOLERANCE_HERTZ = 20;

  private static final String BROWSER_STARTED = "Browser started!";
  private static final String BROWSER_RELEASED = "Browser released!";
  private static final String RELEASE_BROWSER_COMMAND = "mcav browser release";

  private static final String FIELD_SEPARATOR = " ";
  private static final String LINE_SEPARATOR = "\n";

  private static final String PLUGINS_DIRECTORY = "plugins";
  private static final String PLUGIN_DATA_DIRECTORY = "MCAV";
  private static final String MEDIA_DIRECTORY = "media";
  private static final String ISO_DIRECTORY = "iso";
  private static final String LIBRARIES_DIRECTORY = "libraries";
  private static final String MCAV_LIBRARIES_DIRECTORY = "libraries/mcav";
  private static final String MODULE_LIBRARIES_DIRECTORY = "libraries/mcav/me/brandonli";
  private static final String VOICE_CHAT_DIRECTORY = "voicechat";

  private static final String EULA_FILE = "eula.txt";
  private static final String PLUGIN_JAR = "mcav-plugin.jar";
  private static final String SERVER_PROPERTIES_FILE = "server.properties";
  private static final String CONFIGURATION_FILE = "config.yml";
  private static final String VOICE_CHAT_PROPERTIES_FILE = "voicechat-server.properties";
  private static final String DEPENDENCIES_ENTRY = "mcav/plugin/dependencies.txt";
  private static final String PACK_METADATA_ENTRY = "pack.mcmeta";
  private static final String ENTITY_OUTLINE_EFFECT = "assets/minecraft/post_effect/entity_outline.json";
  private static final String MEDIA_RESOURCES = "/media/";

  private static final String JAR_SUFFIX = ".jar";
  private static final String PARTIAL_SUFFIX = ".part";
  private static final String UNIX_JAVA_SUFFIX = "/java";
  private static final String WINDOWS_JAVA_SUFFIX = "\\java.exe";

  private static final String QEMU_PROGRAM = "qemu-system-x86_64";
  private static final String VERSION_OPTION = "--version";

  @Test
  void shouldEnableThePluginWithItsAudioOutputsOnAHeadlessServer(@TempDir final Path serverDirectory) throws Exception {
    final boolean eulaAccepted = Boolean.getBoolean("mcav.e2e.accept_eula");
    Assumptions.assumeTrue(
      eulaAccepted,
      "Running a Minecraft server needs the Minecraft EULA; pass -Pmcav.acceptMinecraftEula=true to accept it!"
    );

    final Path cache = this.requirePath("mcav.e2e.cache_directory");
    final Path paperJar = this.download(cache, PAPER);
    final Path voiceChatJar = this.download(cache, VOICE_CHAT);
    final Path pluginJar = this.requirePath("mcav.e2e.plugin_jar");
    final int httpPort = this.findFreePort();
    final FakeVncServer vnc = FakeVncServer.start();
    final int gamePort = this.prepareServer(serverDirectory, pluginJar, voiceChatJar, httpPort, vnc);
    final Path media = serverDirectory.resolve(MEDIA_DIRECTORY);

    final List<String> command = this.createCommand(paperJar);
    final LibraryCache libraryCache = this.lendLibraries(cache, serverDirectory);
    // closed in reverse order: the server stops first, then the repository, then the libraries go back to the cache
    try (
      vnc;
      libraryCache;
      final LocalMavenRepositoryServer repository = this.startRepository();
      final ServerProcess server = ServerProcess.start(serverDirectory, command)
    ) {
      server.awaitLine(0, this::isStartupComplete, STARTUP_TIMEOUT);
      final int servedFileCount = repository.getServedFileCount();
      this.assertCurrentModuleJars(serverDirectory, pluginJar);

      System.out.println("Local repository files served: %s; cached module hashes verified against this plugin".formatted(servedFileCount));
      this.runCommand(server, "mcav help", "mcav dump");
      final String mediaInformation = this.fetchMediaInformation(server, httpPort);
      try (final SoundListener sound = SoundListener.connect(httpPort); final TonePage page = TonePage.start()) {
        this.streamABrowser(server, sound, page);
        this.runAMachineWhenQemuIsAvailable(server, sound);
        this.showAVncDesktop(server, vnc);
        this.playThroughMcv2(server, page, gamePort);
      }
      this.controlAVideo(server, media);
      this.filterAnImage(server, media);
      server.command("stop");
      final int exitCode = server.awaitExit(SHUTDOWN_TIMEOUT);
      final List<String> output = server.getLines();
      PaperServerAssertions.assertRanCleanly(exitCode, mediaInformation, output);

      final boolean passwordLogged = output.stream().anyMatch(line -> line.contains(vnc.getPassword()));
      assertFalse(passwordLogged, "the VNC password is never logged");
    }
  }

  // where QEMU runs, the server must find it, so a broken discovery fails here instead of skipping the machine
  private void runAMachineWhenQemuIsAvailable(final ServerProcess server, final SoundListener sound) throws InterruptedException {
    final boolean qemuAvailable = server
      .getLines()
      .stream()
      .noneMatch(line -> line.contains("QEMU is not installed"));
    if (qemuAvailable) {
      this.runAMachine(server, sound);
      return;
    }

    final boolean qemuRunsHere = this.canRunQemu();
    assertFalse(qemuRunsHere, "the server reports QEMU missing although %s runs on this machine".formatted(QEMU_PROGRAM));

    System.out.println("QEMU is not installed on this machine, so no virtual machine runs");
  }

  private boolean canRunQemu() throws InterruptedException {
    final ProcessBuilder builder = new ProcessBuilder(QEMU_PROGRAM, VERSION_OPTION);
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    final Process probe;
    try {
      probe = builder.start();
    } catch (final IOException exception) {
      // not on the PATH
      return false;
    }

    final long milliseconds = QEMU_PROBE_TIMEOUT.toMillis();
    final boolean exited = probe.waitFor(milliseconds, TimeUnit.MILLISECONDS);
    if (!exited) {
      probe.destroyForcibly();
      return false;
    }

    final int exitCode = probe.exitValue();
    return exitCode == 0;
  }

  // a warm cache skips HTTP when Gremlin's hashes match, so the module bytes are checked here
  private void assertCurrentModuleJars(final Path serverDirectory, final Path pluginJar) throws IOException {
    final String manifest = this.readDependencyManifest(pluginJar);
    final Set<String> expected = this.findRequestedDigests(manifest);
    final boolean modulesRequested = !expected.isEmpty();
    assertTrue(modulesRequested, "the plugin must request the modules of this build");

    final Path modules = serverDirectory.resolve(MODULE_LIBRARIES_DIRECTORY);
    final Set<String> actual = this.hashModuleJars(modules);
    for (final String digest : expected) {
      final boolean contained = actual.contains(digest);
      assertTrue(contained, "the running server's module cache must contain build digest %s".formatted(digest));
    }
  }

  private String readDependencyManifest(final Path pluginJar) throws IOException {
    try (final ZipFile plugin = new ZipFile(pluginJar.toFile())) {
      final ZipEntry entry = plugin.getEntry(DEPENDENCIES_ENTRY);
      assertNotNull(entry, "the plugin must carry the dependency hashes produced by this build");

      try (final InputStream input = plugin.getInputStream(entry)) {
        final byte[] bytes = input.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
      }
    }
  }

  private Set<String> findRequestedDigests(final String manifest) {
    final Set<String> expected = new HashSet<>();
    final List<String> lines = manifest.lines().toList();
    for (final String line : lines) {
      if (line.startsWith("me.brandonli:mcav-")) {
        final String[] fields = line.split(FIELD_SEPARATOR, MANIFEST_FIELD_COUNT);
        assertEquals(MANIFEST_FIELD_COUNT, fields.length, "module coordinates must have a SHA-256 digest");

        expected.add(fields[1]);
      }
    }

    return expected;
  }

  private Set<String> hashModuleJars(final Path modules) throws IOException {
    final List<Path> files;
    try (final Stream<Path> walk = Files.walk(modules)) {
      files = walk.filter(Files::isRegularFile).toList();
    }
    final Set<String> actual = new HashSet<>();
    for (final Path file : files) {
      final Path fileName = file.getFileName();
      final String name = fileName.toString();
      if (name.endsWith(JAR_SUFFIX)) {
        actual.add(this.hash(file, SHA_256));
      }
    }

    return actual;
  }

  private LibraryCache lendLibraries(final Path cache, final Path serverDirectory) throws IOException {
    final Path cachedLibraries = cache.resolve(LIBRARIES_DIRECTORY);
    final Path serverLibraries = serverDirectory.resolve(MCAV_LIBRARIES_DIRECTORY);
    return LibraryCache.lend(cachedLibraries, serverLibraries);
  }

  // the build wrote this port into mcav/plugin/dependencies.txt, so the server downloads this build's modules
  private LocalMavenRepositoryServer startRepository() throws IOException {
    final Path repositoryDirectory = this.requirePath("mcav.e2e.repository_directory");
    final int repositoryPort = this.requirePort("mcav.e2e.repository_port");
    return LocalMavenRepositoryServer.start(repositoryDirectory, repositoryPort);
  }

  private void streamABrowser(final ServerProcess server, final SoundListener sound, final TonePage page) throws InterruptedException {
    final int firstLine = server.getLineCount();
    final String create = "mcav browser create @a 320x240 1 3x2 0 NEAREST_COLOR HTTP_SERVER %s".formatted(page.getUri());
    this.runCommand(server, create, BROWSER_STARTED, BROWSER_TIMEOUT);
    server.awaitLine(firstLine, line -> line.contains("Maps 0 to 5 show their first picture"), COMMAND_TIMEOUT);
    final double frequency = sound.awaitFrequency(SOUND_SECONDS, SOUND_TIMEOUT);
    System.out.printf(Locale.ROOT, "The page of the browser plays %.1f Hz in the audio web page%n", frequency);

    final boolean matches = Math.abs(frequency - TonePage.TONE_HERTZ) < FREQUENCY_TOLERANCE_HERTZ;
    assertTrue(matches, "the tone of the page has %s Hz".formatted(frequency));

    // a helper runs the Java of the server, which starts no other Java; CEF's processes run programs of its folder
    final Predicate<String> browser = command -> this.isJava(command) || command.contains("jcef") || command.contains("Xvfb");
    final List<ProcessHandle> running = server.findDescendants(browser);

    final boolean empty = running.isEmpty();
    assertFalse(empty, "the browser runs in processes of its own");

    this.runCommand(server, RELEASE_BROWSER_COMMAND, BROWSER_RELEASED, COMMAND_TIMEOUT);
    this.awaitNoneLeft(server, "browser", browser, running);
  }

  private void runAMachine(final ServerProcess server, final SoundListener sound) throws InterruptedException {
    sound.clear();
    final int firstLine = server.getLineCount();
    final String create =
      "mcav vm create @a 320x200 10 3x2 6 NEAREST_COLOR X86_64 HTTP_SERVER -m 16 -drive file=%s,format=raw,if=floppy -boot a".formatted(
        TONE_IMAGE
      );
    this.runCommand(server, create, "Created virtual machine!", COMMAND_TIMEOUT);
    server.awaitLine(firstLine, line -> line.contains("Maps 6 to 11 show their first picture"), COMMAND_TIMEOUT);
    final double frequency = sound.awaitFrequency(SOUND_SECONDS, SOUND_TIMEOUT);
    System.out.printf(Locale.ROOT, "The virtual machine plays %.1f Hz in the audio web page%n", frequency);

    final boolean matches = Math.abs(frequency - TonePage.TONE_HERTZ) < FREQUENCY_TOLERANCE_HERTZ;
    assertTrue(matches, "the tone of the machine has %s Hz".formatted(frequency));

    final Predicate<String> machine = command -> command.contains("qemu-system");
    final List<ProcessHandle> running = server.findDescendants(machine);

    final boolean empty = running.isEmpty();
    assertFalse(empty, "the machine runs in a QEMU process");

    this.runCommand(server, "mcav vm release", "Virtual machine released!", COMMAND_TIMEOUT);
    this.awaitNoneLeft(server, "virtual machine", machine, running);
  }

  private void showAVncDesktop(final ServerProcess server, final FakeVncServer vnc) throws InterruptedException {
    final int firstLine = server.getLineCount();
    this.runCommand(
      server,
      "mcav vnc create @a 320x240 10 3x2 12 NEAREST_COLOR %s:%s".formatted(LOOPBACK_ADDRESS, vnc.getPort()),
      "Connected to the VNC desktop!"
    );
    server.awaitLine(firstLine, line -> line.contains("Maps 12 to 17 show their first picture"), COMMAND_TIMEOUT);
    /*
     * the client asks for the desktop's next picture at the screen's rate, ten a second here, and a release sent at
     * once after the first picture comes back before that; a desktop that sends only one picture still fails below
     */
    final long deadline = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
    while (vnc.getUpdates() < MINIMUM_UPDATES && System.nanoTime() < deadline) {
      Thread.sleep(VNC_POLL_PAUSE);
    }
    this.runCommand(server, "mcav vnc release", "VNC desktop released!");
    System.out.printf(Locale.ROOT, "The VNC desktop logged in %d time(s) and sent %d updates%n", vnc.getLogins(), vnc.getUpdates());

    final int logins = vnc.getLogins();
    assertEquals(1, logins, "the plugin logs in with the listed password");

    final int refused = vnc.getRefused();
    assertEquals(0, refused, "and never with another");

    final boolean updatesArrived = vnc.getUpdates() >= MINIMUM_UPDATES;
    assertTrue(updatesArrived, "the desktop's pictures reach the maps");
  }

  // force-loaded chunks: no player keeps them loaded and 26.3 keeps no spawn chunks
  private void playThroughMcv2(final ServerProcess server, final TonePage page, final int gamePort) throws Exception {
    // a wall the console builds faces north, so this one stands at z 2 and 3 within two blocks of x 0
    this.runCommand(server, "forceload add -16 0 15 15", "to be force loaded");
    this.runCommand(server, "mcav screen \"3x2\" 20 STONE 0 -60 3", "Built a new map screen!");
    final int firstLine = server.getLineCount();
    this.runCommand(
      server,
      "mcav browser create @a 320x240 1 3x2 20 NEAREST_COLOR NONE %s --codec mcv2".formatted(page.getUri()),
      BROWSER_STARTED,
      BROWSER_TIMEOUT
    );
    final String served = server.awaitLine(firstLine, line -> PACK_SERVED.matcher(line).find(), COMMAND_TIMEOUT);
    final Matcher matcher = PACK_SERVED.matcher(served);

    final boolean found = matcher.find();
    assertTrue(found, served);

    final URI pack = URI.create(matcher.group(PACK_URL_GROUP));

    final int port = pack.getPort();
    assertEquals(gamePort, port, "the pack is hosted on the game port");

    final byte[] zip = this.fetchBytes(pack);
    final UUID id = this.derivePackId(zip);

    final String expected = matcher.group(PACK_ID_GROUP);
    final String text = id.toString();
    assertEquals(expected, text, "the pack's id is derived from its hash");

    final Set<String> entries = this.readEntryNames(zip);

    final boolean matches = entries.contains(PACK_METADATA_ENTRY) && entries.contains(ENTITY_OUTLINE_EFFECT);
    assertTrue(matches, String.valueOf(entries));

    System.out.println("The MCV2 pack %s of %s bytes is hosted at %s".formatted(id, zip.length, pack));
    this.runCommand(server, RELEASE_BROWSER_COMMAND, BROWSER_RELEASED, COMMAND_TIMEOUT);

    final List<String> lines = server.getLines();
    PaperServerAssertions.assertLogged(lines, "MCV2 uses the ");
  }

  private UUID derivePackId(final byte[] zip) throws NoSuchAlgorithmException {
    final HexFormat hexadecimalFormat = HexFormat.of();
    final MessageDigest digest = MessageDigest.getInstance("SHA-1");
    final byte[] hash = digest.digest(zip);
    final String sha1 = hexadecimalFormat.formatHex(hash);
    final String name = "mcav-mcv2:%s".formatted(sha1);
    final byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
    return UUID.nameUUIDFromBytes(bytes);
  }

  private Set<String> readEntryNames(final byte[] zip) throws IOException {
    final Set<String> entries = new HashSet<>();
    try (final ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
        entries.add(entry.getName());
      }
    }

    return entries;
  }

  private void controlAVideo(final ServerProcess server, final Path media) throws InterruptedException {
    final String clip = media.resolve(CLIP).toString();
    this.runCommand(
      server,
      "mcav video map @a FFMPEG NONE \"64x64\" \"1x1\" 30 NEAREST_COLOR \"\" \"%s\" --filters \"grayscale,invert\"".formatted(clip),
      "Video metadata loaded and playing now!"
    );
    this.runCommand(server, "mcav video loop true", "Videos play again from their start whenever they end.");
    this.runCommand(server, "mcav video seek 0:01", "Jumped to 0:01");
    this.runCommand(server, "mcav video speed 2", "Playing at a speed of 2.0");
    this.runCommand(server, "mcav video volume 50", "Volume in percent: 50");
    // past the end of the clip, which plays again from its start
    Thread.sleep(PAST_CLIP_END);
    this.runCommand(server, "mcav video seek -0:01", "Jumped to ");
    this.runCommand(server, "mcav video loop false", "Videos stop at their end.");
    this.runCommand(server, "mcav video release", "Video player released!");
    final int firstLine = server.getLineCount();
    server.command("mcav video devices");
    server.awaitLine(
      firstLine,
      line -> line.contains("Capture devices of the server") || line.contains("The server has no capture device."),
      COMMAND_TIMEOUT
    );
  }

  private void filterAnImage(final ServerProcess server, final Path media) throws InterruptedException {
    final String picture = media.resolve(PICTURE).toString();
    this.runCommand(
      server,
      "mcav image map @a \"64x64\" \"1x1\" 31 NEAREST_COLOR %s --filters \"blur=2,text=e2e\"".formatted(picture),
      "Image loaded!"
    );
    this.runCommand(server, "mcav image release", "Image released!");
  }

  private boolean isJava(final String command) {
    return command.endsWith(UNIX_JAVA_SUFFIX) || command.endsWith(WINDOWS_JAVA_SUFFIX);
  }

  // before also covers processes that lost their parent and are no longer below the server
  private void awaitNoneLeft(
    final ServerProcess server,
    final String what,
    final Predicate<String> matcher,
    final List<ProcessHandle> before
  ) throws InterruptedException {
    final long deadline = System.nanoTime() + RELEASE_TIMEOUT.toNanos();
    List<String> left = this.findLeft(server, matcher, before);
    while (!left.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(RELEASE_POLL_PAUSE);
      left = this.findLeft(server, matcher, before);
    }
    assertEquals(List.of(), left, "no process of the %s is left after its release".formatted(what));

    System.out.println("After the release of the %s, none of its %s processes is left".formatted(what, before.size()));
  }

  private List<String> findLeft(final ServerProcess server, final Predicate<String> matcher, final List<ProcessHandle> before) {
    return Stream.concat(before.stream(), server.findDescendants(matcher).stream())
      .filter(ProcessHandle::isAlive)
      .distinct()
      .map(handle -> "%s%s%s".formatted(handle.pid(), FIELD_SEPARATOR, server.programOf(handle)))
      .toList();
  }

  private boolean isStartupComplete(final String line) {
    return line.contains("Done (") && line.contains("For help, type");
  }

  private void runCommand(final ServerProcess server, final String command, final String expectedText) throws InterruptedException {
    this.runCommand(server, command, expectedText, COMMAND_TIMEOUT);
  }

  private void runCommand(final ServerProcess server, final String command, final String expectedText, final Duration timeout)
    throws InterruptedException {
    final int firstLine = server.getLineCount();
    server.command(command);
    server.awaitLine(firstLine, line -> line.contains(expectedText), timeout);
  }

  private String fetchMediaInformation(final ServerProcess server, final int port) throws IOException, InterruptedException {
    server.awaitLine(0, line -> line.contains("Audio web page "), HTTP_TIMEOUT);
    final URI uri = URI.create("http://%s:%s/media".formatted(LOOPBACK_ADDRESS, port));
    final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri);
    requestBuilder.timeout(REQUEST_TIMEOUT);
    final HttpRequest request = requestBuilder.build();
    final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
    try (final HttpClient client = HttpClient.newHttpClient()) {
      final HttpResponse<String> response = client.send(request, handler);
      final int status = response.statusCode();
      assertEquals(HttpURLConnection.HTTP_OK, status, "the audio web server answers the media request");

      return response.body();
    }
  }

  private byte[] fetchBytes(final URI uri) throws IOException, InterruptedException {
    final HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).build();
    try (final HttpClient client = HttpClient.newHttpClient()) {
      final HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

      final int statusCode = response.statusCode();
      assertEquals(HttpURLConnection.HTTP_OK, statusCode, "the server hosts %s".formatted(uri));

      return response.body();
    }
  }

  private List<String> createCommand(final Path paperJar) {
    final String java = System.getProperty("mcav.e2e.java", "java");
    final String jar = paperJar.toString();
    return List.of(java, "-Xms1G", "-Xmx3G", "-Djava.awt.headless=true", "-jar", jar, "--nogui");
  }

  private int prepareServer(
    final Path serverDirectory,
    final Path pluginJar,
    final Path voiceChatJar,
    final int httpPort,
    final FakeVncServer vnc
  ) throws IOException {
    final int gamePort = this.writeServerProperties(serverDirectory);
    final Path eula = serverDirectory.resolve(EULA_FILE);
    final String eulaContents = "eula=true%s".formatted(LINE_SEPARATOR);
    Files.writeString(eula, eulaContents, StandardCharsets.UTF_8);

    final Path pluginsDirectory = serverDirectory.resolve(PLUGINS_DIRECTORY);
    Files.createDirectories(pluginsDirectory);
    final Path installedPlugin = pluginsDirectory.resolve(PLUGIN_JAR);
    Files.copy(pluginJar, installedPlugin);
    final Path installedVoiceChat = pluginsDirectory.resolve(VOICE_CHAT.fileName());
    Files.copy(voiceChatJar, installedVoiceChat);

    this.writePluginConfiguration(pluginsDirectory, httpPort, vnc);
    this.writeVoiceChatConfiguration(pluginsDirectory);
    // the boot sector of mcav-vm's sound test (beep.asm there): it plays a 1000 Hz tone on the PC speaker
    final Path images = pluginsDirectory.resolve(PLUGIN_DATA_DIRECTORY).resolve(ISO_DIRECTORY);
    Files.createDirectories(images);
    this.copyResource(TONE_IMAGE, images);
    final Path media = Files.createDirectories(serverDirectory.resolve(MEDIA_DIRECTORY));
    this.copyResource(CLIP, media);
    this.copyResource(PICTURE, media);

    return gamePort;
  }

  private void copyResource(final String name, final Path directory) throws IOException {
    try (final InputStream resource = PaperServerEndToEndTest.class.getResourceAsStream("%s%s".formatted(MEDIA_RESOURCES, name))) {
      final String message = "Resource %s does not exist!".formatted(name);
      Files.copy(requireNonNull(resource, message), directory.resolve(name));
    }
  }

  // Paper logs an error for a flat world without layers
  private int writeServerProperties(final Path serverDirectory) throws IOException {
    final int port = this.findFreePort();
    final String properties = String.join(
      LINE_SEPARATOR,
      "online-mode=false",
      "server-ip=%s".formatted(LOOPBACK_ADDRESS),
      "server-port=%s".formatted(port),
      "level-type=minecraft\\:flat",
      "generator-settings=%s".formatted(FLAT_WORLD_SETTINGS),
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
    final Path file = serverDirectory.resolve(SERVER_PROPERTIES_FILE);
    Files.writeString(file, properties, StandardCharsets.UTF_8);

    return port;
  }

  // the Discord bot needs a real token, so it stays off; no player can click, so sound autoplays
  private void writePluginConfiguration(final Path pluginsDirectory, final int httpPort, final FakeVncServer vnc) throws IOException {
    final Path dataDirectory = pluginsDirectory.resolve(PLUGIN_DATA_DIRECTORY);
    Files.createDirectories(dataDirectory);
    final String configuration = String.join(
      LINE_SEPARATOR,
      "language: EN_US",
      "discord-bot:",
      "  enabled: false",
      "  token: none",
      "  guild-id: none",
      "  channel-id: none",
      "http-server:",
      "  enabled: true",
      "  host-name: localhost",
      "  port: %s".formatted(httpPort),
      "simple-voice-chat:",
      "  enabled: true",
      "browser:",
      "  allow-private-networks: true",
      "  autoplay-sound: true",
      "vnc:",
      "  allowed-hosts:",
      "    - host: %s".formatted(LOOPBACK_ADDRESS),
      "      port: %s".formatted(vnc.getPort()),
      "      password: %s".formatted(vnc.getPassword()),
      ""
    );
    final Path file = dataDirectory.resolve(CONFIGURATION_FILE);
    Files.writeString(file, configuration, StandardCharsets.UTF_8);
  }

  // a free UDP port, so a voice chat server on the machine cannot clash
  private void writeVoiceChatConfiguration(final Path pluginsDirectory) throws IOException {
    final Path voiceChatDirectory = pluginsDirectory.resolve(VOICE_CHAT_DIRECTORY);
    Files.createDirectories(voiceChatDirectory);
    final int voicePort = this.findFreeUdpPort();
    final Path file = voiceChatDirectory.resolve(VOICE_CHAT_PROPERTIES_FILE);
    Files.writeString(file, "port=%s%s".formatted(voicePort, LINE_SEPARATOR), StandardCharsets.UTF_8);
  }

  private int findFreePort() throws IOException {
    try (final ServerSocket socket = new ServerSocket(EPHEMERAL_PORT)) {
      return socket.getLocalPort();
    }
  }

  private int findFreeUdpPort() throws IOException {
    try (final DatagramSocket socket = new DatagramSocket(EPHEMERAL_PORT)) {
      return socket.getLocalPort();
    }
  }

  private Path download(final Path cache, final RemoteFile remoteFile) throws IOException, InterruptedException {
    Files.createDirectories(cache);
    final Path file = cache.resolve(remoteFile.fileName());
    if (Files.isRegularFile(file)) {
      final String cachedHash = this.hash(file, remoteFile.algorithm());
      if (cachedHash.equals(remoteFile.expectedHash())) {
        return file;
      }
    }
    final Path partial = cache.resolve("%s%s".formatted(remoteFile.fileName(), PARTIAL_SUFFIX));
    this.fetch(remoteFile.url(), partial);
    final String downloadedHash = this.hash(partial, remoteFile.algorithm());
    assertEquals(remoteFile.expectedHash(), downloadedHash, "the file downloaded from %s is corrupt".formatted(remoteFile.url()));

    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);

    return file;
  }

  private void fetch(final String url, final Path target) throws IOException, InterruptedException {
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
      assertEquals(HttpURLConnection.HTTP_OK, status, "the file could not be downloaded from %s".formatted(url));
    }
  }

  private String hash(final Path file, final String algorithm) throws IOException {
    final MessageDigest digest = this.createDigest(algorithm);
    try (final InputStream input = Files.newInputStream(file)) {
      final byte[] chunk = new byte[CHUNK_BYTES];
      int readCount = input.read(chunk);
      while (readCount >= 0) {
        digest.update(chunk, 0, readCount);
        readCount = input.read(chunk);
      }
    }
    final byte[] hashBytes = digest.digest();
    final HexFormat hexadecimalFormat = HexFormat.of();
    return hexadecimalFormat.formatHex(hashBytes);
  }

  private MessageDigest createDigest(final String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private Path requirePath(final String property) {
    final String value = System.getProperty(property);
    assertNotNull(value, PROPERTY_MISSING_MESSAGE.formatted(property));

    return Path.of(value);
  }

  private int requirePort(final String property) {
    final int port = Integer.getInteger(property, MISSING_PORT);
    final boolean portPassed = port > 0;
    assertTrue(portPassed, PROPERTY_MISSING_MESSAGE.formatted(property));

    return port;
  }

  private record RemoteFile(String url, String fileName, String algorithm, String expectedHash) {}
}
