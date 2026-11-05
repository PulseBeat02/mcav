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

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.utils.IoUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Checks the bundled VLC downloads against the checksums VideoLAN publishes, or against the file itself where VideoLAN
// does not host it, then installs the build of this machine from the real download and plays a video through it. These
// tests download files from the internet, so they only run when the mcav.networkTests system property is true, which
// ./gradlew test -Pmcav.networkTests=true sets.
final class VlcReleaseTest {

  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private static final long PLAYBACK_TIMEOUT_MINUTES = 15L;

  private static final String CHECKSUM_SUFFIX = ".sha256";
  private static final String VIDEO_LAN_HOST = "download.videolan.org";

  private static final int HTTP_OK = 200;

  private static final String VLC_DOWNLOADS_RESOURCE = "mcav/common/installers/vlc.json";

  private static final String BINARY_DIRECTORY = "bin";
  private static final String WINDOWS_JAVA_EXECUTABLE = "java.exe";
  private static final String JAVA_EXECUTABLE = "java";

  private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

  @BeforeAll
  static void requireNetworkTests() {
    final boolean enabled = Boolean.getBoolean("mcav.networkTests");
    Assumptions.assumeTrue(enabled, "Network tests run only with -Pmcav.networkTests=true!");
  }

  @Test
  void shouldBundleHashesThatMatchTheChecksumsVideoLanPublishesNextToItsDownloads() throws IOException, InterruptedException {
    final List<Download> downloads = this.readDownloads().filter(this::isHostedByVideoLan).toList();
    try (final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
      for (final Download download : downloads) {
        final String url = download.getUrl();
        final String published = fetchChecksum(client, url + CHECKSUM_SUFFIX);
        final Optional<String> bundled = download.getHash();
        final Optional<String> expected = Optional.of(published);
        assertEquals(expected, bundled, url);
      }
    }
  }

  private static String fetchChecksum(final HttpClient client, final String url) throws IOException, InterruptedException {
    final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build();
    final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    final int statusCode = response.statusCode();
    assertEquals(HTTP_OK, statusCode, url);

    final String body = response.body().strip();
    return WHITESPACE_PATTERN.split(body, 2)[0];
  }

  @Test
  void shouldBundleHashesThatMatchTheDownloadsVideoLanDoesNotHost() throws IOException, InterruptedException {
    final List<Download> downloads = this.readDownloads().filter(Predicate.not(this::isHostedByVideoLan)).toList();
    try (final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
      for (final Download download : downloads) {
        final String url = download.getUrl();
        final String actual = this.hashDownload(client, url);
        final Optional<String> bundled = download.getHash();
        final Optional<String> expected = Optional.of(actual);
        assertEquals(expected, bundled, url);
      }
    }
  }

  private Stream<Download> readDownloads() {
    final Download[] downloads = IoUtils.readDownloadsFromJsonResource(VLC_DOWNLOADS_RESOURCE);
    return Arrays.stream(downloads);
  }

  private boolean isHostedByVideoLan(final Download download) {
    final String url = download.getUrl();
    final URI uri = URI.create(url);
    final String host = uri.getHost();
    return VIDEO_LAN_HOST.equals(host);
  }

  private String hashDownload(final HttpClient client, final String url) throws IOException, InterruptedException {
    final URI uri = URI.create(url);
    final HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).build();
    final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    final int statusCode = response.statusCode();
    assertEquals(HTTP_OK, statusCode, url);

    final InputStream download = response.body();
    try (final HashingInputStream body = new HashingInputStream(Hashing.sha256(), download)) {
      body.transferTo(OutputStream.nullOutputStream());

      final HashCode hash = body.hash();
      return hash.toString();
    }
  }

  @Test
  void shouldInstallTheBuildOfThisMachineAndPlayAVideoThroughIt(@TempDir final Path folder) throws IOException, InterruptedException {
    final VlcInstaller installer = VlcInstaller.create(folder);
    final boolean supported = installer.isSupported();
    Assumptions.assumeTrue(supported, "VLC has no build for this platform!");
    final Path libraries = installer.download(FileMode.EXECUTABLE);
    final Optional<String> optionalUrl = installer.getUrl();
    final String url = optionalUrl.orElseThrow();
    System.out.println("VLC installed from %s into %s".formatted(url, libraries));
    final String output = runProbe(folder, TestMedia.video());
    System.out.println(output);
    final boolean passed = output.contains(VlcPlaybackProbe.PASSED);
    assertTrue(passed, output);
  }

  private static String runProbe(final Path folder, final Path video) throws IOException, InterruptedException {
    final String executable = System.getProperty("os.name").startsWith("Windows") ? WINDOWS_JAVA_EXECUTABLE : JAVA_EXECUTABLE;
    final Path java = Path.of(System.getProperty("java.home"), BINARY_DIRECTORY, executable);
    final List<String> command = List.of(
      java.toString(),
      "--enable-native-access=ALL-UNNAMED",
      VlcPlaybackProbe.class.getName(),
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
    assertTrue(exited, "the playback JVM finished in time: %s".formatted(text));

    final int exitValue = process.exitValue();
    assertEquals(0, exitValue, text);

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
