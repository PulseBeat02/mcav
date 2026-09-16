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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import me.brandonli.mcav.installer.testing.LocalMavenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link UnsafeInjector} and its {@link UnsafeInjector.UnsafeAccess}.
 */
final class UnsafeInjectorTest {

  private static final String RESOURCE = "library.txt";

  @TempDir
  private Path directory;

  /**
   * An Unsafe look-alike whose instance is missing.
   */
  static final class UnsafeWithoutInstance {

    private static final Object theUnsafe = null;

    private UnsafeWithoutInstance() {}
  }

  /**
   * An Unsafe look-alike without the needed methods.
   */
  static final class UnsafeWithoutMethods {

    private static final Object theUnsafe = new Object();

    private UnsafeWithoutMethods() {}
  }

  /**
   * An Unsafe look-alike whose reads fail: the field {@code text} with an error of the virtual machine, the field
   * {@code urls} with a {@link LinkageError}, and every other field is read as the owner itself.
   */
  static final class UnsafeWithFailingReads {

    private static final UnsafeWithFailingReads theUnsafe = new UnsafeWithFailingReads();
    private static final long READABLE_OFFSET = 0L;
    private static final long FATAL_OFFSET = 1L;
    private static final long LINKAGE_OFFSET = 2L;

    private UnsafeWithFailingReads() {}

    long objectFieldOffset(final Field field) {
      final String name = field.getName();
      return switch (name) {
        case "text" -> FATAL_OFFSET;
        case "urls" -> LINKAGE_OFFSET;
        default -> READABLE_OFFSET;
      };
    }

    Object getObject(final Object owner, final long offset) {
      Objects.requireNonNull(owner, "Unsafe always reads the field of an owner");
      if (offset == FATAL_OFFSET) {
        throw new InternalError("out of native memory");
      }
      if (offset == LINKAGE_OFFSET) {
        throw new LinkageError("unsafe is gone");
      }
      return owner;
    }
  }

  /**
   * Holds the fields read in the tests.
   */
  static final class Holder {

    private static final Object SHARED = "shared";

    private final Object text = "text";
    private final Object absent = null;
    private final List<URL> urls = new ArrayList<>();
  }

  private static UnsafeInjector.UnsafeAccess unsafe() {
    final UnsafeInjector.UnsafeAccess unsafe = UnsafeInjector.UNSAFE;
    assertNotNull(unsafe, "sun.misc.Unsafe is available on the test JVM");
    return unsafe;
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
  void addsJarsThroughTheClassPathOfTheLoader() throws IOException {
    final URL jar = this.writeJar();
    final UnsafeInjector.UnsafeAccess unsafe = unsafe();
    try (final URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      final UnsafeInjector injector = new UnsafeInjector(loader, unsafe);
      injector.addURL(jar);
      injector.addURL(jar);

      final URL[] urls = loader.getURLs();
      final URL[] expected = { jar };
      assertArrayEquals(expected, urls, "a jar is only added once");
      final String content = readResource(loader);
      assertEquals("library", content);
      final URLClassLoader target = injector.getClassLoader();
      assertSame(loader, target);
    }
  }

  @Test
  void findsTheUnsafeOfTheRuntimeOnly() {
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
  void readsInstanceFieldsRegardlessOfAccess() {
    final UnsafeInjector.UnsafeAccess unsafe = unsafe();
    final Holder holder = new Holder();
    final Object text = unsafe.readField(Holder.class, holder, "text");
    assertSame(holder.text, text);
  }

  @Test
  void reportsFieldsThatAreMissingNullOrStatic() {
    final UnsafeInjector.UnsafeAccess unsafe = unsafe();
    final Holder holder = new Holder();
    final String typeName = Holder.class.getName();
    assertNull(holder.absent, "the field exists but holds null");
    assertNotNull(Holder.SHARED, "the static field holds a value that must still not be read");

    final JarInjectorException missing = assertThrows(JarInjectorException.class, () -> unsafe.readField(Holder.class, holder, "missing"));
    final JarInjectorException absent = assertThrows(JarInjectorException.class, () -> unsafe.readField(Holder.class, holder, "absent"));
    final JarInjectorException shared = assertThrows(JarInjectorException.class, () -> unsafe.readField(Holder.class, holder, "SHARED"));
    final String missingMessage = missing.getMessage();
    final Throwable missingCause = missing.getCause();
    final String absentMessage = absent.getMessage();
    final String sharedMessage = shared.getMessage();
    final Throwable sharedCause = shared.getCause();
    final boolean sharedReadFailed = sharedMessage.startsWith("Failed to read SHARED: ");

    assertEquals("This Java version has no field missing in " + typeName, missingMessage);
    assertInstanceOf(NoSuchFieldException.class, missingCause);
    assertEquals("The field absent of " + typeName + " is null", absentMessage);
    assertTrue(sharedReadFailed, sharedMessage);
    assertNotNull(sharedCause);
  }

  @Test
  void readsOnlyCollectionFieldsAsCollections() {
    final UnsafeInjector.UnsafeAccess unsafe = unsafe();
    final Holder holder = new Holder();
    final Collection<?> urls = UnsafeInjector.readCollection(unsafe, Holder.class, holder, "urls");
    assertSame(holder.urls, urls);

    final JarInjectorException exception = assertThrows(JarInjectorException.class, () ->
      UnsafeInjector.readCollection(unsafe, Holder.class, holder, "text")
    );
    final String message = exception.getMessage();
    final String typeName = Holder.class.getName();
    assertEquals("The field text of " + typeName + " is not a collection", message);
  }

  @Test
  void hasALookAlikeWhoseReadsFail() throws NoSuchFieldException {
    final UnsafeWithFailingReads lookAlike = UnsafeWithFailingReads.theUnsafe;
    final Holder holder = new Holder();
    final Field textField = Holder.class.getDeclaredField("text");
    final Field urlsField = Holder.class.getDeclaredField("urls");
    final Field absentField = Holder.class.getDeclaredField("absent");
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
  void passesErrorsOfTheVirtualMachineOnAndWrapsEveryOtherReadFailure() {
    final String className = UnsafeWithFailingReads.class.getName();
    final UnsafeInjector.UnsafeAccess failing = UnsafeInjector.UnsafeAccess.find(className);
    assertNotNull(failing, "the look-alike has an instance and both Unsafe methods");
    final Holder holder = new Holder();

    final InternalError fatal = assertThrows(InternalError.class, () -> failing.readField(Holder.class, holder, "text"));
    final JarInjectorException linkage = assertThrows(JarInjectorException.class, () -> failing.readField(Holder.class, holder, "urls"));
    final String fatalMessage = fatal.getMessage();
    final String linkageMessage = linkage.getMessage();
    final Throwable linkageCause = linkage.getCause();

    assertEquals("out of native memory", fatalMessage, "an error of the virtual machine is never wrapped");
    assertEquals("Failed to read urls: unsafe is gone", linkageMessage);
    assertInstanceOf(LinkageError.class, linkageCause);
  }
}
