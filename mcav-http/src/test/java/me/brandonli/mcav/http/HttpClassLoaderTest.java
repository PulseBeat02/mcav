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
import java.time.Duration;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Tests replacing a plugin's library class loader without restarting the host JVM. */
final class HttpClassLoaderTest {

  private static final String PAGE = "<!doctype html><title>class loader restart</title>";

  @TempDir
  private Path directory;

  @Test
  @Timeout(120)
  void successiveLibraryClassLoadersCanServeTheAudioPage() throws Exception {
    Files.writeString(this.directory.resolve("index.html"), PAGE);
    final URL[] locations = libraryLocations();
    final ClassLoader platform = ClassLoader.getPlatformClassLoader();
    try (final HttpClient client = HttpClient.newHttpClient()) {
      for (int restart = 0; restart < 2; restart++) {
        try (final URLClassLoader loader = new URLClassLoader(locations, platform)) {
          final Class<?> type = loader.loadClass(HttpResultImpl.class.getName());
          assertNotSame(HttpResultImpl.class, type, "each restart uses a new copy of the library");
          this.assertServesAndStops(type, client);
        }
      }
    }
  }

  private void assertServesAndStops(final Class<?> type, final HttpClient client) throws Exception {
    final Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, Path.class, List.class);
    constructor.setAccessible(true);
    final Object server = constructor.newInstance("127.0.0.1", 0, this.directory, List.of("server.address=127.0.0.1"));
    final Method start = type.getMethod("start");
    final Method stop = type.getMethod("stop");
    final Method running = type.getMethod("isRunning");
    final Method localPort = type.getDeclaredMethod("getLocalPort");
    localPort.setAccessible(true);
    try {
      start.invoke(server);
      assertEquals(true, running.invoke(server));
      final int port = (Integer) localPort.invoke(server);
      final URI address = URI.create("http://127.0.0.1:" + port + "/");
      final HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(10)).build();
      final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(PAGE, response.body());
    } finally {
      stop.invoke(server);
    }
    assertEquals(false, running.invoke(server));
  }

  private static URL[] libraryLocations() throws IOException {
    final Map<String, URL> locations = new LinkedHashMap<>();
    final URL httpLocation = HttpResultImpl.class.getProtectionDomain().getCodeSource().getLocation();
    final URL commonLocation = AudioFilter.class.getProtectionDomain().getCodeSource().getLocation();
    locations.put(httpLocation.toExternalForm(), httpLocation);
    locations.put(commonLocation.toExternalForm(), commonLocation);
    // Gradle and PIT use different loader types; manifests locate their dependency jars without a loader cast.
    final ClassLoader loader = HttpClassLoaderTest.class.getClassLoader();
    final Enumeration<URL> manifests = loader.getResources("META-INF/MANIFEST.MF");
    while (manifests.hasMoreElements()) {
      final URL manifest = manifests.nextElement();
      final URLConnection connection = manifest.openConnection();
      if (connection instanceof final JarURLConnection jar) {
        final URL location = jar.getJarFileURL();
        locations.put(location.toExternalForm(), location);
      }
    }
    assertTrue(locations.size() > 2, "the isolated loader includes the real HTTP dependencies");
    return locations.values().toArray(URL[]::new);
  }
}
