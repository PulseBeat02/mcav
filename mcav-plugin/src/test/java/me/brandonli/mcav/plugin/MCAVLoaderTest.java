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
package me.brandonli.mcav.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import io.papermc.paper.plugin.loader.library.LibraryStore;
import io.papermc.paper.plugin.loader.library.impl.JarLibrary;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.jpenilla.gremlin.runtime.Dependency;
import xyz.jpenilla.gremlin.runtime.DependencySet;

// libraries come from a prepared cache, so nothing is downloaded
final class MCAVLoaderTest {

  private static final String JAR_REPLACED_MESSAGE = "the jar was replaced while the server started!";

  private static final String LIBRARY_COORDINATE = "com.example:library:1.0@jar";
  private static final String LIBRARY_JAR_PATH = "com/example/library/1.0/library-1.0.jar";
  private static final String STALE_LIBRARY_JAR_PATH = "com/example/library/0.9/library-0.9.jar";
  private static final String STALE_LIBRARY_LAST_USED_PATH = "com/example/library/0.9/library-0.9.jar.last-used.txt";

  private static final String DEPENDENCIES_RESOURCE_PATH = "mcav/plugin/dependencies.txt";

  private static final String MCAV_CLASS_NAME = "me/brandonli/mcav/MCAV";
  private static final String MCV2_RESULT_CLASS_NAME = "me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result";
  private static final String MCAV_API_CLASS_NAME = "me/brandonli/mcav/MCAVApi";
  private static final String BUKKIT_MODULE_CLASS_NAME = "me/brandonli/mcav/bukkit/BukkitModule";
  private static final String MCV2_PACK_SERVER_CLASS_NAME = "me/brandonli/mcav/bukkit/media/mcv2/Mcv2PackServer";

  private static final String PLUGIN_PACKAGE_PATH = "me/brandonli/mcav/plugin/";
  private static final String VOICECHAT_PACKAGE_PATH = "me/brandonli/mcav/voicechat/";

  private static final String CLASS_FILE_EXTENSION = ".class";

  private static final String CACHE_FOLDER_PATH = "libraries/mcav";

  private static final String SHA256_PATTERN = "[0-9a-f]{64}";

  private static final String OLD_BUKKIT_JAR_FILE_NAME = "mcav-bukkit-1.0.0-20260510.193149-206.jar";
  private static final String BUKKIT_JAR_FILE_NAME = "mcav-bukkit.jar";
  private static final String COMMON_JAR_FILE_NAME = "mcav-common.jar";
  private static final String MISSING_JAR_FILE_NAME = "missing.jar";
  private static final String EMPTY_JAR_FILE_NAME = "empty.jar";

  private static final byte[] EMPTY_ZIP_ARCHIVE = { 0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

  @TempDir
  private Path cache;

  @Test
  void shouldCacheTheLibrariesInTheServerLibraryFolder() {
    final MCAVLoader loader = new MCAVLoader();
    final Path libraries = loader.getLibraries();
    final Path expected = Path.of(CACHE_FOLDER_PATH);
    assertEquals(expected, libraries);
  }

  @Test
  void shouldReadTheBundledDependencyManifest() {
    final ClassLoader loader = MCAVLoader.class.getClassLoader();
    final DependencySet bundled = DependencySet.readFromClasspathResource(loader, DEPENDENCIES_RESOURCE_PATH);
    final Dependency common = bundled
      .dependencies()
      .stream()
      .filter(dependency -> dependency.group().equals("me.brandonli") && dependency.name().equals("mcav-common"))
      .findFirst()
      .orElseThrow();

    final String extension = common.extension();
    assertEquals("jar", extension);

    final boolean matches = common.sha256().matches(SHA256_PATTERN);
    assertTrue(matches);
  }

  @Test
  void shouldPutTheResolvedLibrariesOnTheClasspath() throws IOException {
    final Path jar = this.cache.resolve(LIBRARY_JAR_PATH);
    final Path parent = jar.getParent();
    Files.createDirectories(parent);
    Files.write(jar, EMPTY_ZIP_ARCHIVE);
    final String hash = this.sha256(EMPTY_ZIP_ARCHIVE);
    final Dependency dependency = Dependency.parse(LIBRARY_COORDINATE, hash);
    final DependencySet set = this.dependencies(List.of(dependency));
    final PluginClasspathBuilder builder = mock(PluginClasspathBuilder.class);
    try (final MockedStatic<DependencySet> sets = Mockito.mockStatic(DependencySet.class, Mockito.CALLS_REAL_METHODS)) {
      sets.when(() -> DependencySet.readFromClasspathResource(any(ClassLoader.class), eq(DEPENDENCIES_RESOURCE_PATH))).thenReturn(set);
      final MCAVLoader loader = new MCAVLoader(this.cache, List.of());
      loader.classloader(builder);
    }
    final ArgumentCaptor<ClassPathLibrary> captor = ArgumentCaptor.forClass(ClassPathLibrary.class);
    verify(builder).addLibrary(captor.capture());

    final ClassPathLibrary library = captor.getValue();
    assertInstanceOf(JarLibrary.class, library);

    final LibraryStore store = mock(LibraryStore.class);
    library.register(store);
    verify(store).addLibrary(jar);
  }

  private String sha256(final byte[] bytes) {
    final MessageDigest digest = this.createDigest();
    final byte[] hash = digest.digest(bytes);
    final HexFormat format = HexFormat.of();
    return format.formatHex(hash);
  }

  private MessageDigest createDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private DependencySet dependencies(final List<Dependency> dependencies) {
    final List<String> repositories = List.of();
    final Map<String, ?> extensionData = Map.of();
    return new DependencySet(repositories, dependencies, DependencySet.defaultExtensions(), extensionData);
  }

  @Test
  void shouldRemoveCachedLibrariesThatAreNoLongerListed() throws IOException {
    final Path jar = this.cache.resolve(LIBRARY_JAR_PATH);
    final Path parent = jar.getParent();
    Files.createDirectories(parent);
    Files.write(jar, EMPTY_ZIP_ARCHIVE);
    final Path stale = this.cache.resolve(STALE_LIBRARY_JAR_PATH);
    final Path staleParent = stale.getParent();
    Files.createDirectories(staleParent);
    Files.write(stale, EMPTY_ZIP_ARCHIVE);
    // Gremlin deletes a cached jar only when the last-used file beside it is older than an hour; without the file it is kept
    final Path staleLastUsed = this.cache.resolve(STALE_LIBRARY_LAST_USED_PATH);
    final Duration beyondTheWindow = Duration.ofHours(2);
    final long windowMilliseconds = beyondTheWindow.toMillis();
    final long now = System.currentTimeMillis();
    final long lastUsedMilliseconds = now - windowMilliseconds;
    final String lastUsedText = String.valueOf(lastUsedMilliseconds);
    Files.writeString(staleLastUsed, lastUsedText);

    final String hash = this.sha256(EMPTY_ZIP_ARCHIVE);
    final Dependency dependency = Dependency.parse(LIBRARY_COORDINATE, hash);
    final List<Dependency> listed = List.of(dependency);
    final DependencySet set = this.dependencies(listed);
    final PluginClasspathBuilder builder = mock(PluginClasspathBuilder.class);
    try (final MockedStatic<DependencySet> sets = Mockito.mockStatic(DependencySet.class, Mockito.CALLS_REAL_METHODS)) {
      sets.when(() -> DependencySet.readFromClasspathResource(any(ClassLoader.class), eq(DEPENDENCIES_RESOURCE_PATH))).thenReturn(set);
      final MCAVLoader loader = new MCAVLoader(this.cache, List.of());
      loader.classloader(builder);
    }

    final boolean staleLeft = Files.exists(stale);
    final boolean staleLastUsedLeft = Files.exists(staleLastUsed);
    final boolean listedKept = Files.exists(jar);
    assertFalse(staleLeft, "a cached library that has not been used within the retention window is deleted");
    assertFalse(staleLastUsedLeft, "the file that records when it was last used goes with it");
    assertTrue(listedKept, "the library that is listed stays");
  }

  @Test
  void shouldAddNothingWithoutLibraries() {
    final DependencySet set = this.dependencies(List.of());
    final PluginClasspathBuilder builder = mock(PluginClasspathBuilder.class);
    try (final MockedStatic<DependencySet> sets = Mockito.mockStatic(DependencySet.class, Mockito.CALLS_REAL_METHODS)) {
      sets.when(() -> DependencySet.readFromClasspathResource(any(ClassLoader.class), eq(DEPENDENCIES_RESOURCE_PATH))).thenReturn(set);
      final MCAVLoader loader = new MCAVLoader(this.cache, List.of());
      loader.classloader(builder);
    }
    verify(builder, never()).addLibrary(any());
  }

  @Test
  void shouldRefuseANullClasspathBuilder() {
    final DependencySet set = this.dependencies(List.of());
    final PluginClasspathBuilder missing = null;
    // the empty set keeps a run without the check offline
    try (final MockedStatic<DependencySet> sets = Mockito.mockStatic(DependencySet.class, Mockito.CALLS_REAL_METHODS)) {
      sets.when(() -> DependencySet.readFromClasspathResource(any(ClassLoader.class), eq(DEPENDENCIES_RESOURCE_PATH))).thenReturn(set);
      final MCAVLoader loader = new MCAVLoader(this.cache, List.of());
      final NullPointerException refused = assertThrows(NullPointerException.class, () -> loader.classloader(missing));

      final String message = refused.getMessage();
      assertEquals("Classpath builder must not be null!", message);
    }
  }

  @Test
  void shouldRefuseModulesOlderThanThePlugin() throws IOException {
    final Path old = this.jarOf(OLD_BUKKIT_JAR_FILE_NAME, BUKKIT_MODULE_CLASS_NAME);
    final List<String> required = List.of(BUKKIT_MODULE_CLASS_NAME, MCV2_RESULT_CLASS_NAME);
    final MCAVLoader loader = new MCAVLoader(this.cache, required);
    final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> loader.checkModules(List.of(old)));

    final String message = refused.getMessage();
    final String expected =
      "The MCAV modules the server downloaded are older than this plugin, which uses classes they lack (1), such as me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result. Publish the modules of the plugin's own version, or set -Pmcav.e2e=true when you build the plugin!";
    assertEquals(expected, message);
  }

  private Path jarOf(final String name, final String... classes) throws IOException {
    final Path jar = this.cache.resolve(name);
    try (final OutputStream file = Files.newOutputStream(jar); final JarOutputStream entries = new JarOutputStream(file)) {
      for (final String internalName : classes) {
        entries.putNextEntry(new ZipEntry(internalName + CLASS_FILE_EXTENSION));
        entries.closeEntry();
      }
    }
    return jar;
  }

  @Test
  void shouldAcceptModulesThatHaveEveryClassThePluginUses() throws IOException {
    final Path bukkit = this.jarOf(BUKKIT_JAR_FILE_NAME, MCV2_RESULT_CLASS_NAME);
    final Path common = this.jarOf(COMMON_JAR_FILE_NAME, MCAV_CLASS_NAME);
    final MCAVLoader loader = new MCAVLoader(this.cache, List.of(MCAV_CLASS_NAME, MCV2_RESULT_CLASS_NAME));
    loader.checkModules(List.of(bukkit, common));
  }

  @Test
  void shouldReportADownloadedLibraryThatCannotBeRead() {
    final Path missing = this.cache.resolve(MISSING_JAR_FILE_NAME);
    final MCAVLoader loader = new MCAVLoader(this.cache, List.of());
    final UncheckedIOException refused = assertThrows(UncheckedIOException.class, () -> loader.checkModules(List.of(missing)));

    final String message = refused.getMessage();
    final String expected = "Downloaded library %s could not be read!".formatted(missing);
    assertEquals(expected, message);
  }

  @Test
  void shouldNameAtMostFiveOfTheMissingClasses() throws IOException {
    final Path empty = this.jarOf(EMPTY_JAR_FILE_NAME);
    final List<String> required = List.of("a/A", "b/B", "c/C", "d/D", "e/E", "f/F");
    final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> this.checkModulesAgainst(required, empty));

    final boolean contained = refused.getMessage().contains("(6), such as a.A, b.B, c.C, d.D, e.E. ");
    assertTrue(contained, refused.getMessage());
  }

  private void checkModulesAgainst(final List<String> required, final Path empty) {
    final MCAVLoader loader = new MCAVLoader(this.cache, required);
    loader.checkModules(List.of(empty));
  }

  @Test
  void shouldCheckTheModulesBeforeTheyAreAddedToTheClasspath() throws IOException {
    final Path jar = this.cache.resolve(LIBRARY_JAR_PATH);
    Files.createDirectories(jar.getParent());
    Files.write(jar, EMPTY_ZIP_ARCHIVE);
    final Dependency dependency = Dependency.parse(LIBRARY_COORDINATE, this.sha256(EMPTY_ZIP_ARCHIVE));
    final DependencySet set = this.dependencies(List.of(dependency));
    final PluginClasspathBuilder builder = mock(PluginClasspathBuilder.class);
    try (final MockedStatic<DependencySet> sets = Mockito.mockStatic(DependencySet.class, Mockito.CALLS_REAL_METHODS)) {
      sets.when(() -> DependencySet.readFromClasspathResource(any(ClassLoader.class), eq(DEPENDENCIES_RESOURCE_PATH))).thenReturn(set);
      final MCAVLoader loader = new MCAVLoader(this.cache, List.of(MCV2_RESULT_CLASS_NAME));
      assertThrows(IllegalStateException.class, () -> loader.classloader(builder));
    }
    verify(builder, never()).addLibrary(any());
  }

  @Test
  void shouldReadTheModuleClassesTheBuildListed() {
    final List<String> required = MCAVLoader.readRequiredClasses(MCAVLoaderTest.class.getClassLoader());

    final boolean contained = required.contains(MCV2_PACK_SERVER_CLASS_NAME);
    assertTrue(contained, "the plugin starts MCV2 screens");

    final boolean requiredContained = required.contains(MCAV_CLASS_NAME);
    assertTrue(requiredContained);

    final boolean matched = required.stream().anyMatch(name -> name.startsWith(PLUGIN_PACKAGE_PATH));
    assertFalse(matched, "the plugin's own classes");

    final boolean requiredMatched = required.stream().anyMatch(name -> name.startsWith(VOICECHAT_PACKAGE_PATH));
    assertFalse(requiredMatched, "a module shaded into the plugin");
  }

  @Test
  void shouldReadNoModuleClassesFromAPluginBuiltWithoutTheList() throws IOException {
    try (final URLClassLoader withoutTheList = new URLClassLoader(new URL[0], null)) {
      final List<String> requiredClasses = MCAVLoader.readRequiredClasses(withoutTheList);
      assertEquals(List.of(), requiredClasses);
    }
  }

  @Test
  void shouldReportAListOfClassesThatCannotBeRead() {
    final ClassLoader unreadable = new UnreadableClassLoader();
    final UncheckedIOException refused = assertThrows(UncheckedIOException.class, () -> MCAVLoader.readRequiredClasses(unreadable));

    final String message = refused.getMessage();
    assertEquals("Cannot read the classes the plugin needs from its modules!", message);

    final IOException cause = refused.getCause();
    final String causeMessage = cause.getMessage();
    assertEquals(JAR_REPLACED_MESSAGE, causeMessage);
  }

  @Test
  void shouldReadTheListWithoutItsBlankLines() {
    final ClassLoader listed = new ClassLoader(null) {
      @Override
      public InputStream getResourceAsStream(final String name) {
        return new ByteArrayInputStream("me/brandonli/mcav/MCAV\n\n  \nme/brandonli/mcav/MCAVApi\n".getBytes(StandardCharsets.UTF_8));
      }
    };

    final List<String> requiredClasses = MCAVLoader.readRequiredClasses(listed);
    final List<String> expected = List.of(MCAV_CLASS_NAME, MCAV_API_CLASS_NAME);
    assertEquals(expected, requiredClasses);
  }

  // a list that the class loader finds but cannot read, as when the jar is replaced while the server starts
  private static final class UnreadableClassLoader extends ClassLoader {

    UnreadableClassLoader() {
      super(null);
    }

    @Override
    public InputStream getResourceAsStream(final String name) {
      return new FailingInputStream();
    }
  }

  private static final class FailingInputStream extends InputStream {

    @Override
    public int read() throws IOException {
      throw new IOException(JAR_REPLACED_MESSAGE);
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
      throw new IOException(JAR_REPLACED_MESSAGE);
    }
  }
}
