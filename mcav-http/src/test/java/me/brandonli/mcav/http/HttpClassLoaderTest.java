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
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.time.Duration;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

final class HttpClassLoaderTest {

  private static final String PAGE = "<!doctype html><title>class loader restart</title>";

  private static final String INDEX_FILE = "index.html";

  private static final String LOOPBACK_HOST = "127.0.0.1";
  private static final int EPHEMERAL_PORT = 0;

  private static final int LIBRARY_LOADER_COUNT = 2;

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  @TempDir
  private Path directory;

  @Test
  @Timeout(120)
  void shouldServeTheAudioPageFromSuccessiveLibraryClassLoaders() throws Exception {
    final Path index = this.directory.resolve(INDEX_FILE);
    Files.writeString(index, PAGE);

    final URL[] locations = this.findLibraryLocations();
    try (final HttpClient client = HttpClient.newHttpClient()) {
      for (int restart = 0; restart < LIBRARY_LOADER_COUNT; restart++) {
        this.assertServesFromANewLoader(locations, client);
      }
    }
  }

  private URL[] findLibraryLocations() throws IOException {
    final Map<String, URL> locations = new LinkedHashMap<>();
    this.addCodeLocation(locations, SpringHttpResult.class);
    this.addCodeLocation(locations, AudioFilter.class);
    final int ownLocationCount = locations.size();

    this.addDependencyLocations(locations);
    final int locationCount = locations.size();
    assertTrue(locationCount > ownLocationCount, "the isolated loader includes the real HTTP dependencies");

    final Collection<URL> values = locations.values();
    return values.toArray(URL[]::new);
  }

  private void addCodeLocation(final Map<String, URL> locations, final Class<?> type) {
    final ProtectionDomain domain = type.getProtectionDomain();
    final CodeSource source = domain.getCodeSource();
    final URL location = source.getLocation();
    this.addLocation(locations, location);
  }

  private void addLocation(final Map<String, URL> locations, final URL location) {
    final String key = location.toExternalForm();
    locations.put(key, location);
  }

  private void addDependencyLocations(final Map<String, URL> locations) throws IOException {
    // Gradle and PIT use different loader types; manifests locate their dependency jars without a loader cast.
    final ClassLoader loader = HttpClassLoaderTest.class.getClassLoader();
    final Enumeration<URL> manifests = loader.getResources(JarFile.MANIFEST_NAME);
    while (manifests.hasMoreElements()) {
      final URL manifest = manifests.nextElement();
      final URLConnection connection = manifest.openConnection();
      if (connection instanceof final JarURLConnection jar) {
        final URL location = jar.getJarFileURL();
        this.addLocation(locations, location);
      }
    }
  }

  private void assertServesFromANewLoader(final URL[] locations, final HttpClient client) throws Exception {
    final ClassLoader platform = ClassLoader.getPlatformClassLoader();
    try (final URLClassLoader loader = new URLClassLoader(locations, platform)) {
      final String name = SpringHttpResult.class.getName();
      final Class<?> type = loader.loadClass(name);
      assertNotSame(SpringHttpResult.class, type, "each restart uses a new copy of the library");

      this.assertServesAndStops(type, client);
    }
  }

  private void assertServesAndStops(final Class<?> type, final HttpClient client) throws Exception {
    final Object server = this.createServer(type);
    final Method start = type.getMethod("start");
    final Method stop = type.getMethod("stop");
    final Method running = type.getMethod("isRunning");
    try {
      start.invoke(server);
      final Object runningAfterStart = running.invoke(server);
      assertEquals(true, runningAfterStart);

      this.assertServesThePage(type, server, client);
    } finally {
      stop.invoke(server);
    }

    final Object runningAfterStop = running.invoke(server);
    assertEquals(false, runningAfterStop);
  }

  private Object createServer(final Class<?> type) throws Exception {
    final Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, Path.class, List.class);
    constructor.setAccessible(true);

    final String addressProperty = "server.address=%s".formatted(LOOPBACK_HOST);
    final List<String> properties = List.of(addressProperty);
    return constructor.newInstance(LOOPBACK_HOST, EPHEMERAL_PORT, this.directory, properties);
  }

  private void assertServesThePage(final Class<?> type, final Object server, final HttpClient client) throws Exception {
    final Method localPort = type.getDeclaredMethod("getLocalPort");
    localPort.setAccessible(true);

    final int port = (Integer) localPort.invoke(server);
    final String url = "http://%s:%s/".formatted(LOOPBACK_HOST, port);
    final URI address = URI.create(url);
    final HttpRequest request = HttpRequest.newBuilder(address).timeout(REQUEST_TIMEOUT).build();
    final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    final int status = response.statusCode();
    assertEquals(HttpURLConnection.HTTP_OK, status);

    final String body = response.body();
    assertEquals(PAGE, body);
  }
}
