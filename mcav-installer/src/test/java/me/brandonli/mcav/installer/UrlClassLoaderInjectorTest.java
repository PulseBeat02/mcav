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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import me.brandonli.mcav.installer.testing.LocalMavenRepository;
import me.brandonli.mcav.installer.testing.ResourceUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UrlClassLoaderInjectorTest {

  private static final String LIBRARY_JAR = "library.jar";
  private static final String LIBRARY_CONTENT = "library";

  private static final String RESOURCE = "library.txt";

  @TempDir
  private Path directory;

  @Test
  void shouldPreferTheReflectiveStrategyWhenJavaNetIsOpen() throws IOException {
    final MethodHandle addUrlHandle = ReflectiveInjector.ADD_URL;
    assertNotNull(addUrlHandle);

    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final UrlClassLoaderInjector injector = UrlClassLoaderInjector.create(loader);
      final URLClassLoader target = injector.getClassLoader();
      assertInstanceOf(ReflectiveInjector.class, injector);
      assertSame(loader, target);
    }
  }

  @Test
  void shouldFallBackToUnsafeAndFailWithoutAnyStrategy() throws IOException {
    final UnsafeInjector.UnsafeAccess unsafe = UnsafeInjector.UNSAFE;
    assertNotNull(unsafe);

    final MethodHandle addUrlHandle = ReflectiveInjector.ADD_URL;
    assertNotNull(addUrlHandle);

    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final UrlClassLoaderInjector reflective = UrlClassLoaderInjector.create(loader, addUrlHandle, unsafe);
      final UrlClassLoaderInjector fallback = UrlClassLoaderInjector.create(loader, null, unsafe);
      assertInstanceOf(ReflectiveInjector.class, reflective);
      assertInstanceOf(UnsafeInjector.class, fallback);

      final JarInjectorException exception = assertThrows(JarInjectorException.class, () ->
        UrlClassLoaderInjector.create(loader, null, null)
      );
      final String message = exception.getMessage();
      assertEquals(
        "Jars cannot be added to a URLClassLoader on this runtime; add --add-opens java.base/java.net=ALL-UNNAMED to allow it!",
        message
      );
    }
  }

  @Test
  void shouldFindAddUrlOnlyWithPrivateAccess() {
    final MethodHandles.Lookup fullLookup = MethodHandles.lookup();
    final MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
    final MethodHandle found = ReflectiveInjector.findAddUrl(fullLookup);
    final MethodHandle notFound = ReflectiveInjector.findAddUrl(publicLookup);
    assertNotNull(found);
    assertNull(notFound);
  }

  @Test
  void shouldAddJarsReflectively() throws IOException {
    final URL jar = this.writeJar();
    final MethodHandle addUrlHandle = ReflectiveInjector.ADD_URL;
    assertNotNull(addUrlHandle);

    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final ReflectiveInjector injector = new ReflectiveInjector(loader, addUrlHandle);
      injector.addUrl(jar);
      final URL[] urls = loader.getURLs();
      final URL[] expected = { jar };
      assertArrayEquals(expected, urls);

      final String content = ResourceUtils.readResource(loader, RESOURCE);
      assertEquals(LIBRARY_CONTENT, content);
    }
  }

  private URL writeJar() throws IOException {
    final Path jar = this.directory.resolve(LIBRARY_JAR);
    LocalMavenRepository.writeJar(jar, RESOURCE, LIBRARY_CONTENT);
    final URI uri = jar.toUri();
    return uri.toURL();
  }

  @Test
  void shouldPassUncheckedFailuresThroughAndWrapCheckedOnes() throws IOException {
    final URL jar = this.writeJar();
    final IllegalStateException runtimeException = new IllegalStateException("Runtime failure!");
    final LinkageError linkageError = new LinkageError("Linkage failure!");
    final Exception checkedException = new Exception("Checked failure!");
    final MethodHandle runtimeHandle = this.failingHandle(runtimeException);
    final MethodHandle errorHandle = this.failingHandle(linkageError);
    final MethodHandle checkedHandle = this.failingHandle(checkedException);

    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final ReflectiveInjector runtimeInjector = new ReflectiveInjector(loader, runtimeHandle);
      final ReflectiveInjector errorInjector = new ReflectiveInjector(loader, errorHandle);
      final ReflectiveInjector checkedInjector = new ReflectiveInjector(loader, checkedHandle);
      final IllegalStateException runtimeFailure = assertThrows(IllegalStateException.class, () -> runtimeInjector.addUrl(jar));
      final LinkageError errorFailure = assertThrows(LinkageError.class, () -> errorInjector.addUrl(jar));
      final JarInjectorException checkedFailure = assertThrows(JarInjectorException.class, () -> checkedInjector.addUrl(jar));
      final String checkedMessage = checkedFailure.getMessage();
      final Throwable checkedCause = checkedFailure.getCause();
      assertSame(runtimeException, runtimeFailure);
      assertSame(linkageError, errorFailure);

      final String expectedMessage = "Jar %s could not be added to the class loader!".formatted(jar);
      assertEquals(expectedMessage, checkedMessage);
      assertSame(checkedException, checkedCause);

      final URL[] urls = loader.getURLs();
      final int urlCount = urls.length;
      assertEquals(0, urlCount);
    }
  }

  private MethodHandle failingHandle(final Throwable failure) {
    final MethodHandle thrower = MethodHandles.throwException(void.class, Throwable.class);
    final MethodHandle boundThrower = thrower.bindTo(failure);
    return MethodHandles.dropArguments(boundThrower, 0, URLClassLoader.class, URL.class);
  }
}
