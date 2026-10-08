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
package me.brandonli.mcav.sandbox;

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

/**
 * Tests {@link MCAVLoader}. The libraries are resolved from a prepared cache, so nothing is downloaded.
 */
final class MCAVLoaderTest {

  @TempDir
  private Path cache;

  private static String sha256(final byte[] bytes) throws NoSuchAlgorithmException {
    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
    final byte[] hash = digest.digest(bytes);
    final HexFormat format = HexFormat.of();
    return format.formatHex(hash);
  }

  private static DependencySet dependencies(final List<Dependency> dependencies) {
    final List<String> repositories = List.of();
    final Map<String, ?> extensionData = Map.of();
    return new DependencySet(repositories, dependencies, DependencySet.defaultExtensions(), extensionData);
  }

  @Test
  void cachesTheLibrariesInTheServerLibraryFolder() {
    final MCAVLoader loader = new MCAVLoader();
    final Path libraries = loader.getLibraries();
    final Path expected = Path.of("libraries/mcav");
    assertEquals(expected, libraries);
  }

  @Test
  void readsTheBundledDependencyManifest() {
    final ClassLoader loader = MCAVLoader.class.getClassLoader();
    final DependencySet bundled = DependencySet.readFromClasspathResource(loader, "mcav/plugin/dependencies.txt");
    final Dependency common = bundled
      .dependencies()
      .stream()
      .filter(dependency -> dependency.group().equals("me.brandonli") && dependency.name().equals("mcav-common"))
      .findFirst()
      .orElseThrow();
    assertEquals("jar", common.extension());
    assertTrue(common.sha256().matches("[0-9a-f]{64}"));
  }

  @Test
  void putsTheResolvedLibrariesOnTheClasspath() throws IOException, NoSuchAlgorithmException {
    final Path jar = this.cache.resolve("com/example/library/1.0/library-1.0.jar");
    final Path parent = jar.getParent();
    Files.createDirectories(parent);
    final byte[] content = { 0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    Files.write(jar, content);
    final String hash = sha256(content);
    final Dependency dependency = Dependency.parse("com.example:library:1.0@jar", hash);
    final DependencySet set = dependencies(List.of(dependency));
    final PluginClasspathBuilder builder = mock(PluginClasspathBuilder.class);
    try (final MockedStatic<DependencySet> sets = Mockito.mockStatic(DependencySet.class, Mockito.CALLS_REAL_METHODS)) {
      sets.when(() -> DependencySet.readFromClasspathResource(any(ClassLoader.class), eq("mcav/plugin/dependencies.txt"))).thenReturn(set);
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

  @Test
  void removesCachedLibrariesThatAreNoLongerListed() throws IOException, NoSuchAlgorithmException {
    final Path jar = this.cache.resolve("com/example/library/1.0/library-1.0.jar");
    final Path parent = jar.getParent();
    Files.createDirectories(parent);
    final byte[] content = { 0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    Files.write(jar, content);
    final Path stale = this.cache.resolve("com/example/library/0.9/library-0.9.jar");
    final Path staleParent = stale.getParent();
    Files.createDirectories(staleParent);
    Files.write(stale, content);
    // Gremlin deletes a cached jar only once it was last used longer ago than its retention window of one hour, which
    // it reads from a file next to the jar; a jar without that file is always kept
    final Path staleLastUsed = this.cache.resolve("com/example/library/0.9/library-0.9.jar.last-used.txt");
    final Duration beyondTheWindow = Duration.ofHours(2);
    final long windowMillis = beyondTheWindow.toMillis();
    final long now = System.currentTimeMillis();
    final long lastUsedMillis = now - windowMillis;
    final String lastUsedText = String.valueOf(lastUsedMillis);
    Files.writeString(staleLastUsed, lastUsedText);

    final String hash = sha256(content);
    final Dependency dependency = Dependency.parse("com.example:library:1.0@jar", hash);
    final List<Dependency> listed = List.of(dependency);
    final DependencySet set = dependencies(listed);
    final PluginClasspathBuilder builder = mock(PluginClasspathBuilder.class);
    try (final MockedStatic<DependencySet> sets = Mockito.mockStatic(DependencySet.class, Mockito.CALLS_REAL_METHODS)) {
      sets.when(() -> DependencySet.readFromClasspathResource(any(ClassLoader.class), eq("mcav/plugin/dependencies.txt"))).thenReturn(set);
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
  void addsNothingWithoutLibraries() {
    final DependencySet set = dependencies(List.of());
    final PluginClasspathBuilder builder = mock(PluginClasspathBuilder.class);
    try (final MockedStatic<DependencySet> sets = Mockito.mockStatic(DependencySet.class, Mockito.CALLS_REAL_METHODS)) {
      sets.when(() -> DependencySet.readFromClasspathResource(any(ClassLoader.class), eq("mcav/plugin/dependencies.txt"))).thenReturn(set);
      final MCAVLoader loader = new MCAVLoader(this.cache, List.of());
      loader.classloader(builder);
    }
    verify(builder, never()).addLibrary(any());
  }

  /** A jar holding class files of the given internal names, with no content. */
  private Path jarOf(final String name, final String... classes) throws IOException {
    final Path jar = this.cache.resolve(name);
    try (final OutputStream file = Files.newOutputStream(jar); final JarOutputStream entries = new JarOutputStream(file)) {
      for (final String internalName : classes) {
        entries.putNextEntry(new ZipEntry(internalName + ".class"));
        entries.closeEntry();
      }
    }
    return jar;
  }

  @Test
  void refusesModulesOlderThanThePlugin() throws IOException {
    final Path old = this.jarOf("mcav-bukkit-1.0.0-20260510.193149-206.jar", "me/brandonli/mcav/bukkit/BukkitModule");
    final List<String> required = List.of("me/brandonli/mcav/bukkit/BukkitModule", "me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result");
    final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> MCAVLoader.checkModules(required, List.of(old)));
    assertEquals(
      "The mcav modules the server downloaded are older than this plugin, which uses classes they lack (1), such as " +
        "me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result. Publish the modules of the plugin's own version, or build the plugin " +
        "with -Pmcav.e2e=true",
      refused.getMessage()
    );
  }

  @Test
  void acceptsModulesThatHaveEveryClassThePluginUses() throws IOException {
    final Path bukkit = this.jarOf("mcav-bukkit.jar", "me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result");
    final Path common = this.jarOf("mcav-common.jar", "me/brandonli/mcav/MCAV");
    MCAVLoader.checkModules(List.of("me/brandonli/mcav/MCAV", "me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result"), List.of(bukkit, common));
    assertThrows(UncheckedIOException.class, () -> MCAVLoader.checkModules(List.of(), List.of(this.cache.resolve("missing.jar"))));
  }

  @Test
  void namesAtMostFiveOfTheMissingClasses() throws IOException {
    final Path empty = this.jarOf("empty.jar");
    final List<String> required = List.of("a/A", "b/B", "c/C", "d/D", "e/E", "f/F");
    final IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
      MCAVLoader.checkModules(required, List.of(empty))
    );
    assertTrue(refused.getMessage().contains("(6), such as a.A, b.B, c.C, d.D, e.E. "), refused.getMessage());
  }

  @Test
  void checksTheModulesBeforeTheyAreAddedToTheClasspath() throws IOException, NoSuchAlgorithmException {
    final Path jar = this.cache.resolve("com/example/library/1.0/library-1.0.jar");
    Files.createDirectories(jar.getParent());
    final byte[] content = { 0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    Files.write(jar, content);
    final Dependency dependency = Dependency.parse("com.example:library:1.0@jar", sha256(content));
    final DependencySet set = dependencies(List.of(dependency));
    final PluginClasspathBuilder builder = mock(PluginClasspathBuilder.class);
    try (final MockedStatic<DependencySet> sets = Mockito.mockStatic(DependencySet.class, Mockito.CALLS_REAL_METHODS)) {
      sets.when(() -> DependencySet.readFromClasspathResource(any(ClassLoader.class), eq("mcav/plugin/dependencies.txt"))).thenReturn(set);
      final MCAVLoader loader = new MCAVLoader(this.cache, List.of("me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result"));
      assertThrows(IllegalStateException.class, () -> loader.classloader(builder));
    }
    verify(builder, never()).addLibrary(any());
  }

  @Test
  void readsTheModuleClassesTheBuildListed() {
    final List<String> required = MCAVLoader.readRequiredClasses(MCAVLoaderTest.class.getClassLoader());
    assertTrue(required.contains("me/brandonli/mcav/bukkit/media/mcv2/Mcv2PackServer"), "the plugin starts MCV2 screens");
    assertTrue(required.contains("me/brandonli/mcav/MCAV"));
    assertFalse(required.stream().anyMatch(name -> name.startsWith("me/brandonli/mcav/sandbox/")), "the plugin's own classes");
    assertFalse(required.stream().anyMatch(name -> name.startsWith("me/brandonli/mcav/svc/")), "a module shaded into the plugin");
  }

  @Test
  void readsNoModuleClassesFromAPluginBuiltWithoutTheList() throws IOException {
    try (final URLClassLoader withoutTheList = new URLClassLoader(new URL[0], null)) {
      assertEquals(List.of(), MCAVLoader.readRequiredClasses(withoutTheList));
    }
    final ClassLoader unreadable = new ClassLoader(null) {
      @Override
      public InputStream getResourceAsStream(final String name) {
        return new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("the jar was replaced while the server started");
          }

          @Override
          public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            throw new IOException("the jar was replaced while the server started");
          }
        };
      }
    };
    assertThrows(UncheckedIOException.class, () -> MCAVLoader.readRequiredClasses(unreadable));
  }

  @Test
  void readsTheListWithoutItsBlankLines() {
    final ClassLoader listed = new ClassLoader(null) {
      @Override
      public InputStream getResourceAsStream(final String name) {
        return new ByteArrayInputStream("me/brandonli/mcav/MCAV\n\n  \nme/brandonli/mcav/MCAVApi\n".getBytes(StandardCharsets.UTF_8));
      }
    };
    assertEquals(List.of("me/brandonli/mcav/MCAV", "me/brandonli/mcav/MCAVApi"), MCAVLoader.readRequiredClasses(listed));
  }
}
