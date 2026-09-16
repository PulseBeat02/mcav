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
package me.brandonli.mcav.installer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import me.brandonli.mcav.installer.testing.LocalMavenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link URLClassLoaderInjector} and {@link ReflectiveInjector}. The test JVM opens {@code java.net}, as the
 * injector tells users to do, so the reflective strategy is available.
 */
final class URLClassLoaderInjectorTest {

  private static final String RESOURCE = "library.txt";

  @TempDir
  private Path directory;

  /**
   * Creates a stand-in for the {@code addURL} handle that takes a class loader and a URL and throws a failure
   * instead of adding the URL.
   *
   * @param failure the failure to throw
   * @return the handle
   */
  private static MethodHandle failingHandle(final Throwable failure) {
    final MethodHandle thrower = MethodHandles.throwException(void.class, Throwable.class);
    final MethodHandle boundThrower = thrower.bindTo(failure);
    return MethodHandles.dropArguments(boundThrower, 0, URLClassLoader.class, URL.class);
  }

  private URL writeJar() throws IOException {
    final Path jar = this.directory.resolve("library.jar");
    LocalMavenRepository.writeJar(jar, RESOURCE, "library");
    final URI uri = jar.toUri();
    return uri.toURL();
  }

  private static String readResource(final URLClassLoader loader) throws IOException {
    try (final InputStream stream = loader.getResourceAsStream(RESOURCE)) {
      assertNotNull(stream, "missing resource " + RESOURCE);
      final byte[] bytes = stream.readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  @Test
  void prefersTheReflectiveStrategyWhenJavaNetIsOpen() throws IOException {
    final MethodHandle addUrl = ReflectiveInjector.ADD_URL;
    assertNotNull(addUrl);
    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final URLClassLoaderInjector injector = URLClassLoaderInjector.create(loader);
      final URLClassLoader target = injector.getClassLoader();
      assertInstanceOf(ReflectiveInjector.class, injector);
      assertSame(loader, target);
    }
  }

  @Test
  void fallsBackToUnsafeAndFailsWithoutAnyStrategy() throws IOException {
    final UnsafeInjector.UnsafeAccess unsafe = UnsafeInjector.UNSAFE;
    assertNotNull(unsafe);
    final MethodHandle addUrl = ReflectiveInjector.ADD_URL;
    assertNotNull(addUrl);
    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final URLClassLoaderInjector reflective = URLClassLoaderInjector.create(loader, addUrl, unsafe);
      final URLClassLoaderInjector fallback = URLClassLoaderInjector.create(loader, null, unsafe);
      assertInstanceOf(ReflectiveInjector.class, reflective);
      assertInstanceOf(UnsafeInjector.class, fallback);

      final JarInjectorException exception = assertThrows(JarInjectorException.class, () ->
        URLClassLoaderInjector.create(loader, null, null)
      );
      final String message = exception.getMessage();
      assertEquals("No way to add jars to a URLClassLoader on this runtime; add --add-opens java.base/java.net=ALL-UNNAMED", message);
    }
  }

  @Test
  void findsAddUrlOnlyWithPrivateAccess() {
    final MethodHandles.Lookup fullLookup = MethodHandles.lookup();
    final MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
    final MethodHandle found = ReflectiveInjector.findAddUrl(fullLookup);
    final MethodHandle notFound = ReflectiveInjector.findAddUrl(publicLookup);
    assertNotNull(found);
    assertNull(notFound);
  }

  @Test
  void addsJarsReflectively() throws IOException {
    final URL jar = this.writeJar();
    final MethodHandle addUrl = ReflectiveInjector.ADD_URL;
    assertNotNull(addUrl);
    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final ReflectiveInjector injector = new ReflectiveInjector(loader, addUrl);
      injector.addURL(jar);
      final URL[] urls = loader.getURLs();
      final URL[] expected = { jar };
      assertArrayEquals(expected, urls);
      final String content = readResource(loader);
      assertEquals("library", content);
    }
  }

  @Test
  void passesUncheckedFailuresThroughAndWrapsCheckedOnes() throws IOException {
    final URL jar = this.writeJar();
    final IllegalStateException runtimeException = new IllegalStateException("runtime failure");
    final LinkageError linkageError = new LinkageError("linkage failure");
    final Exception checkedException = new Exception("checked failure");
    final MethodHandle runtimeHandle = failingHandle(runtimeException);
    final MethodHandle errorHandle = failingHandle(linkageError);
    final MethodHandle checkedHandle = failingHandle(checkedException);

    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final ReflectiveInjector runtimeInjector = new ReflectiveInjector(loader, runtimeHandle);
      final ReflectiveInjector errorInjector = new ReflectiveInjector(loader, errorHandle);
      final ReflectiveInjector checkedInjector = new ReflectiveInjector(loader, checkedHandle);
      final IllegalStateException runtimeFailure = assertThrows(IllegalStateException.class, () -> runtimeInjector.addURL(jar));
      final LinkageError errorFailure = assertThrows(LinkageError.class, () -> errorInjector.addURL(jar));
      final JarInjectorException checkedFailure = assertThrows(JarInjectorException.class, () -> checkedInjector.addURL(jar));
      final String checkedMessage = checkedFailure.getMessage();
      final Throwable checkedCause = checkedFailure.getCause();
      assertSame(runtimeException, runtimeFailure);
      assertSame(linkageError, errorFailure);
      assertEquals("Failed to add " + jar + ": checked failure", checkedMessage);
      assertSame(checkedException, checkedCause);

      final URL[] urls = loader.getURLs();
      assertEquals(0, urls.length);
    }
  }
}
