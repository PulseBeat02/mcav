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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.installer.testing.LocalMavenRepository;
import me.brandonli.mcav.installer.testing.UtilityClassAssertions;
import net.fabricmc.loader.impl.launch.knot.KnotClassLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Tests {@link LoaderUtils} and {@link JarLoader#DEFAULT_URL_LOADER}.
 */
final class LoaderUtilsTest {

  private static final String KNOT = "net.fabricmc.loader.impl.launch.knot.KnotClassLoader";

  @TempDir
  private Path directory;

  private Path firstJar;
  private Path secondJar;

  /**
   * A class loader that is neither a URL class loader nor the Fabric one.
   */
  private static final class PlainClassLoader extends ClassLoader {

    PlainClassLoader() {
      super(null);
    }
  }

  /**
   * A class loader that extends the Fabric one, as modded launchers do.
   */
  private static final class DerivedKnotClassLoader extends KnotClassLoader {

    DerivedKnotClassLoader(final Object urlLoader) {
      super(urlLoader);
    }
  }

  /**
   * Defines a Fabric class loader from bytes, isolated from the class path of the tests.
   */
  private static final class KnotDefiningClassLoader extends ClassLoader {

    KnotDefiningClassLoader() {
      super(null);
    }

    Class<?> defineKnot(final byte[] bytes) {
      return this.defineClass(KNOT, bytes, 0, bytes.length);
    }
  }

  @BeforeEach
  void writeTheJars() throws IOException {
    this.firstJar = this.directory.resolve("first.jar");
    this.secondJar = this.directory.resolve("second.jar");
    LocalMavenRepository.writeJar(this.firstJar, "first.txt", "first");
    LocalMavenRepository.writeJar(this.secondJar, "second.txt", "second");
  }

  private static String readResource(final URLClassLoader loader, final String name) throws IOException {
    try (final InputStream stream = loader.getResourceAsStream(name)) {
      assertNotNull(stream, "missing resource " + name);
      final byte[] bytes = stream.readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static URL urlOf(final Path jar) throws MalformedURLException {
    final URI uri = jar.toUri();
    return uri.toURL();
  }

  private void assertBothJarsAreLoaded(final URLClassLoader loader) throws IOException {
    final URL[] urls = loader.getURLs();
    final URL firstUrl = urlOf(this.firstJar);
    final URL secondUrl = urlOf(this.secondJar);
    final URL[] expected = { firstUrl, secondUrl };
    assertArrayEquals(expected, urls);

    final String first = readResource(loader, "first.txt");
    final String second = readResource(loader, "second.txt");
    assertEquals("first", first);
    assertEquals("second", second);
  }

  /**
   * Builds a public class named like the Fabric class loader that extends {@link ClassLoader} but lacks the
   * {@code urlLoader} field, as an unknown version of Fabric would.
   *
   * @return the bytes of the class file
   */
  private static byte[] knotClassWithoutField() {
    final ClassDesc knotDescriptor = ClassDesc.of(KNOT);
    final ClassDesc classLoaderDescriptor = ClassDesc.of("java.lang.ClassLoader");
    final ClassFile classFile = ClassFile.of();
    return classFile.build(knotDescriptor, classBuilder -> {
      classBuilder.withFlags(ClassFile.ACC_PUBLIC);
      classBuilder.withSuperclass(classLoaderDescriptor);
      classBuilder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> {
        code.aload(0);
        code.invokespecial(classLoaderDescriptor, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
        code.return_();
      });
    });
  }

  @Test
  void addsJarsToAUrlClassLoaderWithNormalizedAbsolutePaths() throws IOException {
    final Path emptyPath = Path.of("");
    final Path workingDirectory = emptyPath.toAbsolutePath();
    final Path relativeSecond = workingDirectory.relativize(this.secondJar);
    final List<Path> jars = List.of(this.firstJar, relativeSecond);
    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      LoaderUtils.loadJarPaths(jars, loader);
      this.assertBothJarsAreLoaded(loader);
    }
  }

  @Test
  void theDefaultLoaderAddsJarsToAUrlClassLoader() throws IOException {
    final List<Path> jars = List.of(this.firstJar, this.secondJar);
    final JarLoader defaultLoader = JarLoader.DEFAULT_URL_LOADER;
    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      defaultLoader.loadJars(jars, loader);
      this.assertBothJarsAreLoaded(loader);
    }
  }

  @Test
  void addsJarsToTheUrlLoaderOfTheFabricClassLoader() throws IOException {
    final List<Path> jars = List.of(this.firstJar, this.secondJar);
    try (final URLClassLoader urlLoader = new URLClassLoader(new URL[0], null)) {
      final KnotClassLoader knot = new KnotClassLoader(urlLoader);
      LoaderUtils.loadJarPaths(jars, knot);
      this.assertBothJarsAreLoaded(urlLoader);
    }
  }

  @Test
  void findsTheFabricClassLoaderAmongTheSuperclasses() throws IOException {
    final List<Path> jars = List.of(this.firstJar, this.secondJar);
    try (final URLClassLoader urlLoader = new URLClassLoader(new URL[0], null)) {
      final DerivedKnotClassLoader knot = new DerivedKnotClassLoader(urlLoader);
      LoaderUtils.loadJarPaths(jars, knot);
      this.assertBothJarsAreLoaded(urlLoader);
    }
  }

  @Test
  void rejectsAFabricClassLoaderWithoutUrlLoader() {
    final List<Path> jars = List.of(this.firstJar);
    final KnotClassLoader withoutLoader = new KnotClassLoader(null);
    final KnotClassLoader withOtherValue = new KnotClassLoader("not a class loader");
    final JarInjectorException missing = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, withoutLoader));
    final JarInjectorException other = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, withOtherValue));
    final String missingMessage = missing.getMessage();
    final String otherMessage = other.getMessage();
    assertEquals("The Fabric class loader has no URL class loader", missingMessage);
    assertEquals("The Fabric class loader has no URL class loader", otherMessage);
  }

  @Test
  void reportsAFabricClassLoaderOfAnUnknownVersion() throws ReflectiveOperationException {
    final byte[] bytes = knotClassWithoutField();
    final KnotDefiningClassLoader definer = new KnotDefiningClassLoader();
    final Class<?> knotWithoutField = definer.defineKnot(bytes);
    final Constructor<?> constructor = knotWithoutField.getConstructor();
    final ClassLoader knot = (ClassLoader) constructor.newInstance();

    final List<Path> jars = List.of(this.firstJar);
    final JarInjectorException exception = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, knot));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Cannot access the Fabric class loader: urlLoader", message);
    assertInstanceOf(NoSuchFieldException.class, cause);
  }

  @Test
  void rejectsOtherClassLoaders() {
    final List<Path> jars = List.of(this.firstJar);
    final PlainClassLoader loader = new PlainClassLoader();
    final JarInjectorException exception = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, loader));
    final String message = exception.getMessage();
    final String name = PlainClassLoader.class.getName();
    assertEquals("Cannot add jars to a " + name + "; put the dependencies on the class path or load them from a URLClassLoader", message);
  }

  @Test
  void rejectsPathsThatHaveNoUrl() throws IOException {
    final Path jar = Mockito.mock(Path.class);
    final Path absolute = Mockito.mock(Path.class);
    final URI unknownScheme = URI.create("unknown-scheme:/library.jar");
    Mockito.when(jar.toAbsolutePath()).thenReturn(absolute);
    Mockito.when(absolute.normalize()).thenReturn(absolute);
    Mockito.when(absolute.toUri()).thenReturn(unknownScheme);
    Mockito.when(jar.toString()).thenReturn("library.jar");
    final List<Path> jars = List.of(jar);

    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final JarInjectorException exception = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, loader));
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();
      final boolean describesThePath = message.startsWith("Invalid jar path library.jar: ");
      assertTrue(describesThePath, message);
      assertInstanceOf(MalformedURLException.class, cause);
      final URL[] urls = loader.getURLs();
      assertEquals(0, urls.length);
    }
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(LoaderUtils.class);
  }
}
