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
package me.brandonli.mcav.capability.installer.vlc.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategy.EnvironmentSetter;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategy.SearchProvider;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.co.caprica.vlcj.factory.discovery.provider.AppDirDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.ConfigDirConfigFileDiscoveryDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.JnaLibraryPathDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.LinuxWellKnownDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.MacOsWellKnownDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.SystemPathDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.UserDirConfigFileDiscoveryDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.WindowsInstallDirectoryProvider;

/**
 * Tests {@link DirectoryProviderDiscoveryStrategy}.
 */
final class DirectoryProviderDiscoveryStrategyTest {

  private static final String[] PATTERNS = { "libvlc\\.dll", "libvlccore\\.dll" };
  private static final String[] PLUGIN_FORMATS = { "%s/plugins", "%s/vlc/plugins" };
  private static final EnvironmentSetter IGNORING_SETTER = (_, _) -> true;
  private static final String JNA_LIBRARY_PATH = "jna.library.path";

  @TempDir
  private Path temp;

  @Test
  void searchesTheDirectoriesOfEveryProviderOfVlcj() {
    final DefaultProviderStrategy strategy = new DefaultProviderStrategy();
    final List<SearchProvider> providers = strategy.getSearchProviders();
    final List<Class<?>> providerClasses = new ArrayList<>();
    final List<Integer> depths = new ArrayList<>();
    for (final SearchProvider provider : providers) {
      final DiscoveryDirectoryProvider directoryProvider = provider.getDirectoryProvider();
      final Class<?> providerClass = directoryProvider.getClass();
      final int maxDepth = provider.getMaxDepth();
      providerClasses.add(providerClass);
      depths.add(maxDepth);
    }

    final List<Class<?>> expectedClasses = List.of(
      ConfigDirConfigFileDiscoveryDirectoryProvider.class,
      JnaLibraryPathDirectoryProvider.class,
      LinuxWellKnownDirectoryProvider.class,
      MacOsWellKnownDirectoryProvider.class,
      WindowsInstallDirectoryProvider.class,
      SystemPathDirectoryProvider.class,
      AppDirDirectoryProvider.class,
      UserDirConfigFileDiscoveryDirectoryProvider.class
    );
    // only the well-known system library directories are searched one level deep; every other directory is checked
    // on its own, which is what keeps discovery from walking whole drives
    final List<Integer> expectedDepths = List.of(0, 0, 1, 0, 0, 0, 0, 0);
    assertEquals(expectedClasses, providerClasses, "every directory provider of vlcj is searched");
    assertEquals(expectedDepths, depths);
  }

  @Test
  void neverCountsOneFileForSeveralPatterns() throws IOException {
    final Path directory = createDirectory(this.temp, "second-only");
    createFile(directory, "libvlccore.dll");
    createFile(directory, "readme.txt");
    final TestStrategy strategy = singleSearch(directory, 0);
    final String found = strategy.discover();
    assertNull(found, "a file that matches no pattern never stands in for one that is missing");
  }

  @Test
  void findsTheDirectoryThatContainsEveryLibrary() throws IOException {
    final Path libraries = createLibraries(this.temp, "vlc");
    final TestStrategy strategy = singleSearch(libraries, 0);
    final String found = strategy.discover();
    final String expected = libraries.toString();
    assertEquals(expected, found);
  }

  @Test
  void ignoresDirectoriesWithOnlySomeOfTheLibraries() throws IOException {
    final Path partial = createDirectory(this.temp, "partial");
    createFile(partial, "libvlc.dll");
    final TestStrategy strategy = singleSearch(partial, 0);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void ignoresDirectoriesNamedLikeTheLibraries() throws IOException {
    final Path fake = this.temp.resolve("fake");
    createDirectory(fake, "libvlc.dll");
    createDirectory(fake, "libvlccore.dll");
    final TestStrategy strategy = singleSearch(fake, 0);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void matchesWholeFileNamesOnly() throws IOException {
    final Path similar = createDirectory(this.temp, "similar");
    createFile(similar, "libvlc.dll.bak");
    createFile(similar, "xlibvlccore.dll");
    final TestStrategy strategy = singleSearch(similar, 0);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void needsEveryPatternToMatchADifferentFile() throws IOException {
    final Path directory = createDirectory(this.temp, "single");
    createFile(directory, "libvlc.dll");
    final String[] overlapping = { "libvlc\\.dll", "libvlc.*" };
    final SearchProvider provider = search(1, directory, 0);
    final List<SearchProvider> providers = List.of(provider);
    final TestStrategy strategy = new TestStrategy(overlapping, PLUGIN_FORMATS, providers, IGNORING_SETTER);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void onlyChecksTheProvidedDirectoryAtDepthZero() throws IOException {
    final Path root = this.temp.resolve("root");
    createLibraries(root, "child");
    final TestStrategy strategy = singleSearch(root, 0);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void checksChildDirectoriesAtDepthOne() throws IOException {
    final Path root = this.temp.resolve("root");
    final Path child = createLibraries(root, "child");
    createFile(root, "readme.txt");
    final TestStrategy strategy = singleSearch(root, 1);
    final String found = strategy.discover();
    final String expected = child.toString();
    assertEquals(expected, found);
  }

  @Test
  void neverSearchesDeeperThanTheMaximumDepth() throws IOException {
    final Path root = this.temp.resolve("root");
    createLibraries(root, "child", "grandchild");
    final TestStrategy strategy = singleSearch(root, 1);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void prefersTheShallowestMatch() throws IOException {
    final Path root = this.temp.resolve("root");
    createLibraries(root, "a", "deep");
    final Path shallow = createLibraries(root, "b");
    final TestStrategy strategy = singleSearch(root, 2);
    final String found = strategy.discover();
    final String expected = shallow.toString();
    assertEquals(expected, found);
  }

  @Test
  void prefersProvidersWithAHigherPriority() throws IOException {
    final Path low = createLibraries(this.temp, "low");
    final Path high = createLibraries(this.temp, "high");
    final SearchProvider lowSearch = search(1, low, 0);
    final SearchProvider highSearch = search(5, high, 0);
    final TestStrategy strategy = strategy(lowSearch, highSearch);
    final String found = strategy.discover();
    final String expected = high.toString();
    assertEquals(expected, found);
  }

  @Test
  void skipsUnsupportedProviders() throws IOException {
    final Path unsupported = createLibraries(this.temp, "unsupported");
    final Path supported = createLibraries(this.temp, "supported");
    final String rawUnsupported = unsupported.toString();
    final StaticDirectoryProvider unsupportedProvider = new StaticDirectoryProvider(9, false, rawUnsupported);
    final SearchProvider unsupportedSearch = new SearchProvider(unsupportedProvider, 0);
    final SearchProvider supportedSearch = search(1, supported, 0);
    final TestStrategy strategy = strategy(unsupportedSearch, supportedSearch);
    final String found = strategy.discover();
    final String expected = supported.toString();
    assertEquals(expected, found);
  }

  @Test
  void skipsBlankMissingAndInvalidDirectoryEntries() throws IOException {
    final Path libraries = createLibraries(this.temp, "vlc");
    final Path regularFile = createFile(this.temp, "file.txt");
    final Path missing = this.temp.resolve("missing");
    final String rawMissing = missing.toString();
    final String rawRegularFile = regularFile.toString();
    final String rawLibraries = libraries.toString();
    final String[] entries = { null, "", "   ", rawMissing, rawRegularFile, "bad\0directory", rawLibraries };
    final StaticDirectoryProvider provider = new StaticDirectoryProvider(1, true, entries);
    final SearchProvider providerSearch = new SearchProvider(provider, 0);
    final TestStrategy strategy = strategy(providerSearch);
    final String found = strategy.discover();
    assertEquals(rawLibraries, found);
  }

  @Test
  void searchesADirectoryAgainWhenAProviderAllowsADeeperSearch() throws IOException {
    final Path root = this.temp.resolve("root");
    final Path child = createLibraries(root, "child");
    final SearchProvider shallowFirst = search(5, root, 0);
    final SearchProvider deeperSecond = search(1, root, 1);
    final TestStrategy strategy = strategy(shallowFirst, deeperSecond);
    final String found = strategy.discover();
    final String expected = child.toString();
    assertEquals(expected, found);
  }

  @Test
  void skipsADirectoryAlreadySearchedAtLeastAsDeep() throws IOException {
    // the child is listed while the root is searched one level deep; the second provider adds the missing core
    // library right before it is asked, so the child would only be found if it were listed a second time
    final Path root = this.temp.resolve("root");
    final Path child = createDirectory(root, "child");
    createFile(child, "libvlc.dll");
    final Path missingCore = child.resolve("libvlccore.dll");
    final String rawChild = child.toString();
    final CompletingDirectoryProvider completing = new CompletingDirectoryProvider(missingCore, rawChild);
    final SearchProvider rootSearch = search(5, root, 1);
    final SearchProvider completingSearch = new SearchProvider(completing, 0);
    final TestStrategy strategy = strategy(rootSearch, completingSearch);
    final String found = strategy.discover();
    final boolean completed = Files.exists(missingCore);
    assertTrue(completed, "the second provider was asked");
    assertNull(found, "a directory already searched at least as deep is not listed again");
  }

  @Test
  void findsADirectoryThatIsCompleteWhenItIsFirstSearched() throws IOException {
    // the control for the test above: the same provider alone finds the child
    final Path child = createDirectory(this.temp, "child");
    createFile(child, "libvlc.dll");
    final Path missingCore = child.resolve("libvlccore.dll");
    final String rawChild = child.toString();
    final CompletingDirectoryProvider completing = new CompletingDirectoryProvider(missingCore, rawChild);
    final SearchProvider completingSearch = new SearchProvider(completing, 0);
    final TestStrategy strategy = strategy(completingSearch);
    final String found = strategy.discover();
    assertEquals(rawChild, found);
  }

  @Test
  void survivesSymbolicLinkLoops() throws IOException {
    final Path root = createDirectory(this.temp, "root");
    final Path loop = root.resolve("loop");
    createLinkOrAbort(loop, root);
    final Path libraries = createLibraries(root, "deep", "vlc");
    final Path empty = createDirectory(this.temp, "empty");
    final Path emptyLoop = empty.resolve("loop");
    Files.createSymbolicLink(emptyLoop, empty);

    final TestStrategy finding = singleSearch(root, 5);
    final TestStrategy searchingEmpty = singleSearch(empty, 5);
    final Duration limit = Duration.ofSeconds(10);
    final String found = assertTimeoutPreemptively(limit, finding::discover);
    final String nothing = assertTimeoutPreemptively(limit, searchingEmpty::discover);
    final String expected = libraries.toString();
    assertEquals(expected, found);
    assertNull(nothing);
  }

  @Test
  void givesUpAfterTheMaximumNumberOfDirectories() throws IOException {
    final Path crowded = this.temp.resolve("crowded");
    for (int index = 0; index < DirectoryProviderDiscoveryStrategy.MAX_VISITED_DIRECTORIES; index++) {
      createDirectory(crowded, "empty-" + index);
    }
    final Path libraries = createLibraries(this.temp, "vlc");
    final SearchProvider crowdedSearch = search(5, crowded, 1);
    final SearchProvider librarySearch = search(1, libraries, 0);
    final TestStrategy exhausted = strategy(crowdedSearch, librarySearch);
    final TestStrategy control = strategy(librarySearch);

    final String exhaustedResult = exhausted.discover();
    final String controlResult = control.discover();
    final String expected = libraries.toString();
    assertNull(exhaustedResult);
    assertEquals(expected, controlResult);
  }

  @Test
  void listsTheDirectoriesOfTheSupportedProvidersByPriority() {
    final StaticDirectoryProvider low = new StaticDirectoryProvider(1, true, "low-a", "low-b");
    final StaticDirectoryProvider high = new StaticDirectoryProvider(7, true, "high");
    final StaticDirectoryProvider unsupported = new StaticDirectoryProvider(9, false, "unsupported");
    final SearchProvider lowSearch = new SearchProvider(low, 0);
    final SearchProvider highSearch = new SearchProvider(high, 0);
    final SearchProvider unsupportedSearch = new SearchProvider(unsupported, 0);
    final TestStrategy strategy = strategy(lowSearch, highSearch, unsupportedSearch);
    final List<String> directories = strategy.discoveryDirectories();
    final List<String> expected = List.of("high", "low-a", "low-b");
    assertEquals(expected, directories);
  }

  @Test
  void theDefaultProvidersReadTheJnaLibraryPath() throws IOException {
    final Path libraries = createLibraries(this.temp, "jna");
    final String rawLibraries = libraries.toString();
    final String previous = System.getProperty(JNA_LIBRARY_PATH);
    System.setProperty(JNA_LIBRARY_PATH, rawLibraries);
    try {
      final DefaultProviderStrategy strategy = new DefaultProviderStrategy();
      final List<String> directories = strategy.discoveryDirectories();
      final boolean listed = directories.contains(rawLibraries);
      // only the directories the default providers offer are asserted: which one the strategy then picks depends on
      // the machine, because a private VLC installation in the cache folder of the user is searched as well
      assertTrue(listed, "the providers of the default constructor read jna.library.path");
    } finally {
      restoreProperty(JNA_LIBRARY_PATH, previous);
    }
  }

  private static void restoreProperty(final String name, final @Nullable String previous) {
    if (previous == null) {
      System.clearProperty(name);
      return;
    }
    System.setProperty(name, previous);
  }

  @Test
  void copiesTheArgumentsItIsConstructedWith() throws IOException {
    final Path libraries = createLibraries(this.temp, "vlc");
    final String[] patterns = PATTERNS.clone();
    final SearchProvider librarySearch = search(1, libraries, 0);
    final List<SearchProvider> providers = new ArrayList<>();
    providers.add(librarySearch);
    final TestStrategy strategy = new TestStrategy(patterns, PLUGIN_FORMATS, providers, IGNORING_SETTER);
    patterns[0] = "nothing-matches-this";
    providers.clear();
    final String found = strategy.discover();
    final String expected = libraries.toString();
    assertEquals(expected, found);
  }

  @Test
  void publishesTheFirstExistingPluginDirectory() throws IOException {
    final Path libraries = createLibraries(this.temp, "vlc");
    createDirectory(libraries, "plugins");
    createDirectory(libraries, "vlc", "plugins");
    final List<String> published = new ArrayList<>();
    final TestStrategy strategy = recordingStrategy(published, true);
    final String libraryPath = libraries.toString();
    final boolean result = strategy.onSetPluginPath(libraryPath);
    final List<String> expected = List.of("VLC_PLUGIN_PATH=" + libraryPath + "/plugins");
    assertTrue(result);
    assertEquals(expected, published);
  }

  @Test
  void fallsBackToTheNextPluginDirectoryFormat() throws IOException {
    final Path libraries = createLibraries(this.temp, "vlc");
    createDirectory(libraries, "vlc", "plugins");
    final List<String> published = new ArrayList<>();
    final TestStrategy strategy = recordingStrategy(published, true);
    final String libraryPath = libraries.toString();
    final boolean result = strategy.onSetPluginPath(libraryPath);
    final List<String> expected = List.of("VLC_PLUGIN_PATH=" + libraryPath + "/vlc/plugins");
    assertTrue(result);
    assertEquals(expected, published);
  }

  @Test
  void reportsWhenThePluginPathCouldNotBeSet() throws IOException {
    final Path libraries = createLibraries(this.temp, "vlc");
    createDirectory(libraries, "plugins");
    final List<String> published = new ArrayList<>();
    final TestStrategy strategy = recordingStrategy(published, false);
    final String libraryPath = libraries.toString();
    final boolean result = strategy.onSetPluginPath(libraryPath);
    final int publishCount = published.size();
    assertFalse(result);
    assertEquals(1, publishCount);
  }

  @Test
  void publishesNothingWithoutAPluginDirectory() throws IOException {
    final Path libraries = createLibraries(this.temp, "vlc");
    final List<String> published = new ArrayList<>();
    final TestStrategy strategy = recordingStrategy(published, true);
    final String libraryPath = libraries.toString();
    final boolean result = strategy.onSetPluginPath(libraryPath);
    final boolean nothingPublished = published.isEmpty();
    assertFalse(result);
    assertTrue(nothingPublished);
  }

  @Test
  void letsVlcjAddTheFoundDirectoryToTheSearchPath() {
    final TestStrategy strategy = strategy();
    final boolean addToSearchPath = strategy.onFound("anywhere");
    assertTrue(addToSearchPath);
  }

  @Test
  void rejectsInvalidArguments() {
    final List<SearchProvider> providers = List.of();
    final TestStrategy strategy = strategy();
    assertThrows(NullPointerException.class, () -> new TestStrategy(null, PLUGIN_FORMATS, providers, IGNORING_SETTER));
    assertThrows(NullPointerException.class, () -> new TestStrategy(PATTERNS, null, providers, IGNORING_SETTER));
    assertThrows(NullPointerException.class, () -> new TestStrategy(PATTERNS, PLUGIN_FORMATS, null, IGNORING_SETTER));
    assertThrows(NullPointerException.class, () -> new TestStrategy(PATTERNS, PLUGIN_FORMATS, providers, null));
    assertThrows(IllegalArgumentException.class, () -> new TestStrategy(new String[0], PLUGIN_FORMATS, providers, IGNORING_SETTER));
    assertThrows(NullPointerException.class, () -> strategy.onFound(null));
    assertThrows(NullPointerException.class, () -> strategy.onSetPluginPath(null));
  }

  private static Path createDirectory(final Path parent, final String... names) throws IOException {
    final Path relative = Path.of("", names);
    final Path directory = parent.resolve(relative);
    return Files.createDirectories(directory);
  }

  private static Path createFile(final Path directory, final String name) throws IOException {
    final Path file = directory.resolve(name);
    return Files.createFile(file);
  }

  private static Path createLibraries(final Path parent, final String... names) throws IOException {
    final Path directory = createDirectory(parent, names);
    createFile(directory, "libvlc.dll");
    createFile(directory, "libvlccore.dll");
    return directory;
  }

  private static void createLinkOrAbort(final Path link, final Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (final IOException | UnsupportedOperationException exception) {
      final String reason = exception.getMessage();
      Assumptions.abort("Symbolic links cannot be created here: " + reason);
    }
  }

  private static SearchProvider search(final int priority, final Path directory, final int maxDepth) {
    final String rawDirectory = directory.toString();
    final StaticDirectoryProvider provider = new StaticDirectoryProvider(priority, true, rawDirectory);
    return new SearchProvider(provider, maxDepth);
  }

  private static TestStrategy singleSearch(final Path directory, final int maxDepth) {
    final SearchProvider provider = search(1, directory, maxDepth);
    return strategy(provider);
  }

  private static TestStrategy strategy(final SearchProvider... providers) {
    final List<SearchProvider> providerList = List.of(providers);
    return new TestStrategy(PATTERNS, PLUGIN_FORMATS, providerList, IGNORING_SETTER);
  }

  private static TestStrategy recordingStrategy(final List<String> published, final boolean result) {
    final EnvironmentSetter setter = (name, value) -> {
      published.add(name + "=" + value);
      return result;
    };
    final List<SearchProvider> noProviders = List.of();
    return new TestStrategy(PATTERNS, PLUGIN_FORMATS, noProviders, setter);
  }

  /**
   * A strategy that searches the directories of the providers the library uses by default.
   */
  private static final class DefaultProviderStrategy extends DirectoryProviderDiscoveryStrategy {

    DefaultProviderStrategy() {
      super(PATTERNS, PLUGIN_FORMATS, IGNORING_SETTER);
    }

    @Override
    public boolean supported() {
      return true;
    }
  }

  /**
   * A strategy for any system with the patterns and plugin formats given to it.
   */
  private static final class TestStrategy extends DirectoryProviderDiscoveryStrategy {

    TestStrategy(
      final String[] patterns,
      final String[] pluginFormats,
      final List<SearchProvider> providers,
      final EnvironmentSetter setter
    ) {
      super(patterns, pluginFormats, providers, setter);
    }

    @Override
    public boolean supported() {
      return true;
    }
  }

  /**
   * A directory provider that creates a file just before it names its directories, as if a library appeared between
   * two providers.
   */
  static final class CompletingDirectoryProvider implements DiscoveryDirectoryProvider {

    private final Path fileToCreate;
    private final String[] directories;

    CompletingDirectoryProvider(final Path fileToCreate, final String... directories) {
      this.fileToCreate = fileToCreate;
      this.directories = directories;
    }

    @Override
    public int priority() {
      return 1;
    }

    @Override
    public String[] directories() {
      try {
        Files.createFile(this.fileToCreate);
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
      return this.directories.clone();
    }

    @Override
    public boolean supported() {
      return true;
    }
  }

  /**
   * A directory provider with fixed answers.
   */
  static final class StaticDirectoryProvider implements DiscoveryDirectoryProvider {

    private final int priority;
    private final boolean supported;
    private final String[] directories;

    StaticDirectoryProvider(final int priority, final boolean supported, final String... directories) {
      this.priority = priority;
      this.supported = supported;
      this.directories = directories;
    }

    @Override
    public int priority() {
      return this.priority;
    }

    @Override
    public String[] directories() {
      return this.directories.clone();
    }

    @Override
    public boolean supported() {
      return this.supported;
    }
  }
}
