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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.installer.testing.LocalMavenRepository;
import me.brandonli.mcav.installer.testing.ResourceUtils;
import me.brandonli.mcav.installer.testing.UtilityClassAssertions;
import net.fabricmc.loader.impl.launch.knot.KnotClassLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

final class LoaderUtilsTest {

  private static final String FIRST_JAR = "first.jar";
  private static final String FIRST_RESOURCE = "first.txt";
  private static final String FIRST_CONTENT = "first";

  private static final String SECOND_JAR = "second.jar";
  private static final String SECOND_RESOURCE = "second.txt";
  private static final String SECOND_CONTENT = "second";

  private static final String LIBRARY_JAR = "library.jar";

  private static final String KNOT = "net.fabricmc.loader.impl.launch.knot.KnotClassLoader";
  private static final ClassDesc CLASS_LOADER_DESCRIPTOR = ClassDesc.of("java.lang.ClassLoader");

  private static final int RECEIVER_SLOT = 0;

  @TempDir
  private Path directory;

  private Path firstJar;
  private Path secondJar;

  @BeforeEach
  void writeTheJars() throws IOException {
    this.firstJar = this.directory.resolve(FIRST_JAR);
    this.secondJar = this.directory.resolve(SECOND_JAR);
    LocalMavenRepository.writeJar(this.firstJar, FIRST_RESOURCE, FIRST_CONTENT);
    LocalMavenRepository.writeJar(this.secondJar, SECOND_RESOURCE, SECOND_CONTENT);
  }

  @Test
  void shouldAddJarsToAUrlClassLoaderWithNormalizedAbsolutePaths() throws IOException {
    final Path emptyPath = Path.of("");
    final Path workingDirectory = emptyPath.toAbsolutePath();
    final Path relativeSecond = workingDirectory.relativize(this.secondJar);
    final List<Path> jars = List.of(this.firstJar, relativeSecond);
    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      LoaderUtils.loadJarPaths(jars, loader);
      this.assertBothJarsAreLoaded(loader);
    }
  }

  private void assertBothJarsAreLoaded(final URLClassLoader loader) throws IOException {
    final URL[] urls = loader.getURLs();
    final URL firstUrl = this.urlOf(this.firstJar);
    final URL secondUrl = this.urlOf(this.secondJar);
    final URL[] expected = { firstUrl, secondUrl };
    assertArrayEquals(expected, urls);

    final String first = ResourceUtils.readResource(loader, FIRST_RESOURCE);
    final String second = ResourceUtils.readResource(loader, SECOND_RESOURCE);
    assertEquals(FIRST_CONTENT, first);
    assertEquals(SECOND_CONTENT, second);
  }

  private URL urlOf(final Path jar) throws MalformedURLException {
    final URI uri = jar.toUri();
    return uri.toURL();
  }

  @Test
  void shouldAddJarsToAUrlClassLoaderWithTheDefaultLoader() throws IOException {
    final List<Path> jars = List.of(this.firstJar, this.secondJar);
    final JarLoader defaultLoader = JarLoader.DEFAULT_URL_LOADER;
    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      defaultLoader.loadJars(jars, loader);
      this.assertBothJarsAreLoaded(loader);
    }
  }

  @Test
  void shouldAddJarsToTheUrlLoaderOfTheFabricClassLoader() throws IOException {
    final List<Path> jars = List.of(this.firstJar, this.secondJar);
    try (final URLClassLoader urlLoader = new URLClassLoader(new URL[0], null)) {
      final KnotClassLoader knot = new KnotClassLoader(urlLoader);
      LoaderUtils.loadJarPaths(jars, knot);
      this.assertBothJarsAreLoaded(urlLoader);
    }
  }

  @Test
  void shouldFindTheFabricClassLoaderAmongTheSuperclasses() throws IOException {
    final List<Path> jars = List.of(this.firstJar, this.secondJar);
    try (final URLClassLoader urlLoader = new URLClassLoader(new URL[0], null)) {
      final DerivedKnotClassLoader knot = new DerivedKnotClassLoader(urlLoader);
      LoaderUtils.loadJarPaths(jars, knot);
      this.assertBothJarsAreLoaded(urlLoader);
    }
  }

  @Test
  void shouldRejectAFabricClassLoaderWithoutUrlLoader() {
    final List<Path> jars = List.of(this.firstJar);
    final KnotClassLoader withoutLoader = new KnotClassLoader(null);
    final KnotClassLoader withOtherValue = new KnotClassLoader("not a class loader");
    final JarInjectorException missing = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, withoutLoader));
    final JarInjectorException other = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, withOtherValue));
    final String missingMessage = missing.getMessage();
    final String otherMessage = other.getMessage();
    assertEquals("The Fabric class loader has no URL class loader!", missingMessage);
    assertEquals("The Fabric class loader has no URL class loader!", otherMessage);
  }

  @Test
  void shouldReportAFabricClassLoaderOfAnUnknownVersion() throws ReflectiveOperationException {
    final byte[] bytes = this.knotClassWithoutField();
    final KnotDefiningClassLoader definer = new KnotDefiningClassLoader();
    final Class<?> knotWithoutField = definer.defineKnot(bytes);
    final Constructor<?> constructor = knotWithoutField.getConstructor();
    final ClassLoader knot = (ClassLoader) constructor.newInstance();

    final List<Path> jars = List.of(this.firstJar);
    final JarInjectorException exception = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, knot));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("The Fabric class loader cannot be accessed!", message);
    assertInstanceOf(NoSuchFieldException.class, cause);
  }

  private byte[] knotClassWithoutField() {
    final ClassDesc knotDescriptor = ClassDesc.of(KNOT);
    final ClassFile classFile = ClassFile.of();
    return classFile.build(knotDescriptor, this::buildKnotClass);
  }

  private void buildKnotClass(final ClassBuilder classBuilder) {
    classBuilder.withFlags(ClassFile.ACC_PUBLIC);
    classBuilder.withSuperclass(CLASS_LOADER_DESCRIPTOR);
    classBuilder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, this::callTheSuperConstructor);
  }

  private void callTheSuperConstructor(final CodeBuilder code) {
    code.aload(RECEIVER_SLOT);
    code.invokespecial(CLASS_LOADER_DESCRIPTOR, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
    code.return_();
  }

  @Test
  void shouldRejectOtherClassLoaders() {
    final List<Path> jars = List.of(this.firstJar);
    final PlainClassLoader loader = new PlainClassLoader();
    final JarInjectorException exception = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, loader));
    final String message = exception.getMessage();
    final String name = PlainClassLoader.class.getName();
    final String expected =
      "Jars cannot be added to a %s; put the dependencies on the class path or load them from a URLClassLoader!".formatted(name);
    assertEquals(expected, message);
  }

  @Test
  void shouldRejectPathsThatHaveNoUrl() throws IOException {
    final Path jar = Mockito.mock(Path.class);
    final Path absolute = Mockito.mock(Path.class);
    final URI unknownScheme = URI.create("unknown-scheme:/library.jar");
    Mockito.when(jar.toAbsolutePath()).thenReturn(absolute);
    Mockito.when(absolute.normalize()).thenReturn(absolute);
    Mockito.when(absolute.toUri()).thenReturn(unknownScheme);
    Mockito.when(jar.toString()).thenReturn(LIBRARY_JAR);
    final List<Path> jars = List.of(jar);

    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final JarInjectorException exception = assertThrows(JarInjectorException.class, () -> LoaderUtils.loadJarPaths(jars, loader));
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();
      assertEquals("Jar path library.jar is not a valid URL!", message);
      assertInstanceOf(MalformedURLException.class, cause);

      final URL[] urls = loader.getURLs();
      final int urlCount = urls.length;
      assertEquals(0, urlCount);
    }
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(LoaderUtils.class);
  }

  private static final class PlainClassLoader extends ClassLoader {

    PlainClassLoader() {
      super(null);
    }
  }

  private static final class DerivedKnotClassLoader extends KnotClassLoader {

    DerivedKnotClassLoader(final Object urlLoader) {
      super(urlLoader);
    }
  }

  private static final class KnotDefiningClassLoader extends ClassLoader {

    KnotDefiningClassLoader() {
      super(null);
    }

    private Class<?> defineKnot(final byte[] bytes) {
      return this.defineClass(KNOT, bytes, 0, bytes.length);
    }
  }
}
