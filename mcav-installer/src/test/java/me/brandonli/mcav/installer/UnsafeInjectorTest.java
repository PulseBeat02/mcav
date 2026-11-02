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
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import me.brandonli.mcav.installer.testing.LocalMavenRepository;
import me.brandonli.mcav.installer.testing.ResourceUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UnsafeInjectorTest {

  private static final String TEXT_FIELD = "text";
  private static final String URLS_FIELD = "urls";
  private static final String ABSENT_FIELD = "absent";

  private static final String LIBRARY_JAR = "library.jar";
  private static final String LIBRARY_CONTENT = "library";

  private static final String RESOURCE = "library.txt";

  @TempDir
  private Path directory;

  @Test
  void shouldAddJarsThroughTheClassPathOfTheLoader() throws IOException {
    final URL jar = this.writeJar();
    final UnsafeInjector.UnsafeAccess unsafe = this.unsafe();
    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final UnsafeInjector injector = new UnsafeInjector(loader, unsafe);
      injector.addUrl(jar);
      injector.addUrl(jar);

      final URL[] urls = loader.getURLs();
      final URL[] expected = { jar };
      assertArrayEquals(expected, urls, "a jar is only added once");

      final String content = ResourceUtils.readResource(loader, RESOURCE);
      assertEquals(LIBRARY_CONTENT, content);

      final URLClassLoader target = injector.getClassLoader();
      assertSame(loader, target);
    }
  }

  private URL writeJar() throws IOException {
    final Path jar = this.directory.resolve(LIBRARY_JAR);
    LocalMavenRepository.writeJar(jar, RESOURCE, LIBRARY_CONTENT);
    final URI uri = jar.toUri();
    return uri.toURL();
  }

  private UnsafeInjector.UnsafeAccess unsafe() {
    final UnsafeInjector.UnsafeAccess unsafe = UnsafeInjector.UNSAFE;
    assertNotNull(unsafe, "sun.misc.Unsafe is available on the test JVM");

    return unsafe;
  }

  @Test
  void shouldFindTheUnsafeOfTheRuntimeOnly() {
    assertNull(UnsafeWithoutInstance.theUnsafe, "the look-alike declares theUnsafe but has no instance");
    assertNotNull(UnsafeWithoutMethods.theUnsafe, "the look-alike has an instance but no Unsafe methods");

    final String withoutInstanceName = UnsafeWithoutInstance.class.getName();
    final String withoutMethodsName = UnsafeWithoutMethods.class.getName();

    final UnsafeInjector.UnsafeAccess real = UnsafeInjector.UnsafeAccess.find("sun.misc.Unsafe");
    final UnsafeInjector.UnsafeAccess missingClass = UnsafeInjector.UnsafeAccess.find("does.not.Exist");
    final UnsafeInjector.UnsafeAccess missingInstance = UnsafeInjector.UnsafeAccess.find(withoutInstanceName);
    final UnsafeInjector.UnsafeAccess missingMethods = UnsafeInjector.UnsafeAccess.find(withoutMethodsName);
    assertNotNull(real);
    assertNull(missingClass);
    assertNull(missingInstance);
    assertNull(missingMethods);
  }

  @Test
  void shouldReadInstanceFieldsRegardlessOfAccess() {
    final UnsafeInjector.UnsafeAccess unsafe = this.unsafe();
    final Holder holder = new Holder();
    final Object text = unsafe.readField(Holder.class, holder, TEXT_FIELD);
    assertSame(holder.text, text);
  }

  @Test
  void shouldReportFieldsThatAreMissingNullOrStatic() {
    final UnsafeInjector.UnsafeAccess unsafe = this.unsafe();
    final Holder holder = new Holder();
    final String typeName = Holder.class.getName();
    assertNull(holder.absent, "the field exists but holds null");
    assertNotNull(Holder.SHARED, "the static field holds a value that must still not be read");

    final JarInjectorException missing = assertThrows(JarInjectorException.class, () -> unsafe.readField(Holder.class, holder, "missing"));
    final JarInjectorException absent = assertThrows(JarInjectorException.class, () ->
      unsafe.readField(Holder.class, holder, ABSENT_FIELD)
    );
    final JarInjectorException shared = assertThrows(JarInjectorException.class, () -> unsafe.readField(Holder.class, holder, "SHARED"));
    final String missingMessage = missing.getMessage();
    final Throwable missingCause = missing.getCause();
    final String absentMessage = absent.getMessage();
    final String sharedMessage = shared.getMessage();
    final Throwable sharedCause = shared.getCause();

    final String expectedMissing = "Field missing is missing from %s in this Java version!".formatted(typeName);
    assertEquals(expectedMissing, missingMessage);
    assertInstanceOf(NoSuchFieldException.class, missingCause);

    final String expectedAbsent = "Field absent of %s is null!".formatted(typeName);
    assertEquals(expectedAbsent, absentMessage);
    assertEquals("Field SHARED could not be read!", sharedMessage);
    assertNotNull(sharedCause);
  }

  @Test
  void shouldReadOnlyCollectionFieldsAsCollections() {
    final UnsafeInjector.UnsafeAccess unsafe = this.unsafe();
    final Holder holder = new Holder();
    final Collection<?> urls = UnsafeInjector.readCollection(unsafe, Holder.class, holder, URLS_FIELD);
    assertSame(holder.urls, urls);

    final JarInjectorException exception = assertThrows(JarInjectorException.class, () ->
      UnsafeInjector.readCollection(unsafe, Holder.class, holder, TEXT_FIELD)
    );
    final String message = exception.getMessage();
    final String typeName = Holder.class.getName();
    final String expected = "Field text of %s is not a collection!".formatted(typeName);
    assertEquals(expected, message);
  }

  @Test
  void shouldHaveALookAlikeWhoseReadsFail() throws NoSuchFieldException {
    final UnsafeWithFailingReads lookAlike = UnsafeWithFailingReads.theUnsafe;
    final Holder holder = new Holder();
    final Field textField = Holder.class.getDeclaredField(TEXT_FIELD);
    final Field urlsField = Holder.class.getDeclaredField(URLS_FIELD);
    final Field absentField = Holder.class.getDeclaredField(ABSENT_FIELD);
    final long textOffset = lookAlike.objectFieldOffset(textField);
    final long urlsOffset = lookAlike.objectFieldOffset(urlsField);
    final long absentOffset = lookAlike.objectFieldOffset(absentField);
    final Object read = lookAlike.getObject(holder, absentOffset);

    assertSame(holder, read, "only the chosen fields fail");
    assertThrows(InternalError.class, () -> lookAlike.getObject(holder, textOffset));
    assertThrows(LinkageError.class, () -> lookAlike.getObject(holder, urlsOffset));
    assertThrows(NullPointerException.class, () -> lookAlike.getObject(null, urlsOffset));
  }

  @Test
  void shouldPassErrorsOfTheVirtualMachineOnAndWrapEveryOtherReadFailure() {
    final String className = UnsafeWithFailingReads.class.getName();
    final UnsafeInjector.UnsafeAccess failing = UnsafeInjector.UnsafeAccess.find(className);
    assertNotNull(failing, "the look-alike has an instance and both Unsafe methods");

    final Holder holder = new Holder();

    final InternalError fatal = assertThrows(InternalError.class, () -> failing.readField(Holder.class, holder, TEXT_FIELD));
    final JarInjectorException linkage = assertThrows(JarInjectorException.class, () ->
      failing.readField(Holder.class, holder, URLS_FIELD)
    );
    final String fatalMessage = fatal.getMessage();
    final String linkageMessage = linkage.getMessage();
    final Throwable linkageCause = linkage.getCause();

    assertEquals("Out of native memory!", fatalMessage, "an error of the virtual machine is never wrapped");
    assertEquals("Field urls could not be read!", linkageMessage);
    assertInstanceOf(LinkageError.class, linkageCause);
  }

  static final class UnsafeWithoutInstance {

    private static final Object theUnsafe = null;

    private UnsafeWithoutInstance() {}
  }

  static final class UnsafeWithoutMethods {

    private static final Object theUnsafe = new Object();

    private UnsafeWithoutMethods() {}
  }

  static final class UnsafeWithFailingReads {

    private static final UnsafeWithFailingReads theUnsafe = new UnsafeWithFailingReads();
    private static final long READABLE_OFFSET = 0L;
    private static final long FATAL_OFFSET = 1L;
    private static final long LINKAGE_OFFSET = 2L;

    private UnsafeWithFailingReads() {}

    long objectFieldOffset(final Field field) {
      final String name = field.getName();
      return switch (name) {
        case TEXT_FIELD -> FATAL_OFFSET;
        case URLS_FIELD -> LINKAGE_OFFSET;
        default -> READABLE_OFFSET;
      };
    }

    Object getObject(final Object owner, final long offset) {
      Objects.requireNonNull(owner, "Unsafe always reads the field of an owner!");

      if (offset == FATAL_OFFSET) {
        throw new InternalError("Out of native memory!");
      }

      if (offset == LINKAGE_OFFSET) {
        final String message = "Unsafe is gone!";
        throw new LinkageError(message);
      }

      return owner;
    }
  }

  static final class Holder {

    private static final Object SHARED = "shared";

    private final Object text;
    private final Object absent;
    private final List<URL> urls;

    Holder() {
      this.text = "text";
      this.absent = null;
      this.urls = new ArrayList<>();
    }
  }
}
