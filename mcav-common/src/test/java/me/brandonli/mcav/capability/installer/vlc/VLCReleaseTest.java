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
package me.brandonli.mcav.capability.installer.vlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.utils.IOUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the bundled VLC downloads against the release VideoLAN publishes, then installs the build of this machine
 * from the real download and plays a video through it. These tests download files from the internet, so they only
 * run when the {@code mcav.networkTests} system property is true, which {@code ./gradlew test
 * -Pmcav.networkTests=true} sets.
 */
final class VLCReleaseTest {

  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private static final long PLAYBACK_TIMEOUT_MINUTES = 15L;

  private static final String CHECKSUM_SUFFIX = ".sha256";

  private static final int HTTP_OK = 200;

  @BeforeAll
  static void requireNetworkTests() {
    final boolean enabled = Boolean.getBoolean("mcav.networkTests");
    Assumptions.assumeTrue(enabled, "Network tests run only with -Pmcav.networkTests=true");
  }

  @Test
  void bundledHashesMatchTheChecksumsVideoLanPublishesNextToEveryDownload() throws IOException, InterruptedException {
    final Download[] downloads = IOUtils.readDownloadsFromJsonResource("vlc.json");
    try (final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
      for (final Download download : downloads) {
        final String url = download.getUrl();
        final String published = fetchChecksum(client, url + CHECKSUM_SUFFIX);
        final String bundled = download.getHash();
        assertEquals(published, bundled, url);
      }
    }
  }

  private static String fetchChecksum(final HttpClient client, final String url) throws IOException, InterruptedException {
    final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build();
    final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(HTTP_OK, response.statusCode(), url);
    final String body = response.body().strip();
    return body.split("\\s+", 2)[0];
  }

  @Test
  void installsTheBuildOfThisMachineAndPlaysAVideoThroughIt(@TempDir final Path folder) throws IOException, InterruptedException {
    final VLCInstaller installer = VLCInstaller.create(folder);
    final boolean supported = installer.isSupported();
    Assumptions.assumeTrue(supported, "VLC has no build for this platform");
    final Path libraries = installer.download(true);
    System.out.println("VLC installed from " + installer.getUrl() + " into " + libraries);
    final String output = runProbe(folder, TestMedia.video());
    System.out.println(output);
    assertTrue(output.contains(VLCPlaybackProbe.PASSED), output);
  }

  private static String runProbe(final Path folder, final Path video) throws IOException, InterruptedException {
    final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    final Path java = Path.of(System.getProperty("java.home"), "bin", executable);
    final List<String> command = List.of(
      java.toString(),
      "--enable-native-access=ALL-UNNAMED",
      VLCPlaybackProbe.class.getName(),
      folder.toString(),
      video.toString()
    );
    final ProcessBuilder builder = new ProcessBuilder(command);
    final Map<String, String> environment = builder.environment();
    environment.put("CLASSPATH", System.getProperty("java.class.path"));
    environment.remove("JAVA_TOOL_OPTIONS");
    environment.remove("JDK_JAVA_OPTIONS");
    builder.redirectErrorStream(true);
    final Process process = builder.start();
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final Thread drain = Thread.ofPlatform()
      .name("vlc-probe-output")
      .start(() -> copy(process.getInputStream(), output));
    final boolean exited = process.waitFor(PLAYBACK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    if (!exited) {
      process.destroyForcibly();
    }
    drain.join(TimeUnit.SECONDS.toMillis(10));
    final String text = output.toString(StandardCharsets.UTF_8);
    assertTrue(exited, "the playback JVM finished in time: " + text);
    assertEquals(0, process.exitValue(), text);
    return text;
  }

  private static void copy(final InputStream input, final ByteArrayOutputStream output) {
    try (input) {
      input.transferTo(output);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
