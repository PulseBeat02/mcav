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
package me.brandonli.mcav.capability.installer.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.utils.IOUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the bundled yt-dlp downloads against the real 2026.08.19 release on GitHub. These tests download files from
 * the internet, so they only run when the {@code mcav.networkTests} system property is true, which
 * {@code ./gradlew test -Pmcav.networkTests=true} sets.
 */
final class YTDLPReleaseTest {

  private static final String RELEASE = "2026.08.19";
  private static final URI CHECKSUMS = URI.create("https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/SHA2-256SUMS");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  @BeforeAll
  static void requireNetworkTests() {
    final boolean enabled = Boolean.getBoolean("mcav.networkTests");
    Assumptions.assumeTrue(enabled, "Network tests run only with -Pmcav.networkTests=true");
  }

  @Test
  void bundledHashesMatchTheChecksumsPublishedWithTheRelease() throws IOException, InterruptedException {
    final Map<String, String> published = downloadChecksums();
    final Download[] downloads = IOUtils.readDownloadsFromJsonResource("yt-dlp.json");
    for (final Download download : downloads) {
      final String url = download.getUrl();
      final String fileName = IOUtils.getFileNameFromUrl(url);
      final String expected = published.get(fileName);
      final String bundled = download.getHash();
      assertEquals(expected, bundled, fileName);
    }
  }

  private static Map<String, String> downloadChecksums() throws IOException, InterruptedException {
    final HttpClient.Builder builder = HttpClient.newBuilder();
    final HttpClient.Builder redirecting = builder.followRedirects(HttpClient.Redirect.NORMAL);
    try (final HttpClient client = redirecting.build()) {
      final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(CHECKSUMS);
      final HttpRequest.Builder timed = requestBuilder.timeout(TIMEOUT);
      final HttpRequest request = timed.build();
      final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
      final HttpResponse<String> response = client.send(request, handler);
      final int status = response.statusCode();
      assertEquals(200, status);
      final String body = response.body();
      return parseChecksums(body);
    }
  }

  /**
   * Parses GNU-style checksum lines, {@code <hash>  <file name>}, into a map from file name to hash.
   */
  private static Map<String, String> parseChecksums(final String body) {
    final Map<String, String> checksums = new HashMap<>();
    final Stream<String> lineStream = body.lines();
    final List<String> lines = lineStream.toList();
    for (final String line : lines) {
      final String trimmed = line.strip();
      final String[] parts = trimmed.split("\\s+", 2);
      if (parts.length == 2) {
        final String fileName = parts[1];
        final String hash = parts[0];
        checksums.put(fileName, hash);
      }
    }
    return checksums;
  }

  @Test
  void installsTheBuildOfThisMachineAndRunsIt(@TempDir final Path folder) throws IOException, InterruptedException {
    final YTDLPInstaller installer = YTDLPInstaller.create(folder);
    final boolean supported = installer.isSupported();
    Assumptions.assumeTrue(supported, "yt-dlp has no build for this platform");
    final Path executable = installer.download(true);
    final String version = runVersion(executable);
    assertEquals(RELEASE, version);
  }

  /**
   * Runs {@code yt-dlp --version} and returns what it printed.
   */
  private static String runVersion(final Path executable) throws IOException, InterruptedException {
    final String command = executable.toString();
    final ProcessBuilder builder = new ProcessBuilder(command, "--version");
    builder.redirectErrorStream(true);
    final Process process = builder.start();
    final byte[] output;
    try (final InputStream stream = process.getInputStream()) {
      output = stream.readAllBytes();
    }
    final long timeoutSeconds = TIMEOUT.toSeconds();
    final boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    assertTrue(finished, "yt-dlp --version did not finish");
    final int exitCode = process.exitValue();
    final String printed = new String(output, StandardCharsets.UTF_8);
    assertEquals(0, exitCode, printed);
    return printed.strip();
  }
}
