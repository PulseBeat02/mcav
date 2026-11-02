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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.PatternSyntaxException;
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

final class DirectoryProviderDiscoveryStrategyTest {

  private static final String LIBRARY_PATTERN = "libvlc\\.dll";
  private static final String CORE_LIBRARY_PATTERN = "libvlccore\\.dll";
  private static final String LIBRARY_PREFIX_PATTERN = "libvlc.*";
  private static final String UNMATCHED_PATTERN = "nothing-matches-this";
  private static final String INVALID_PATTERN = "libvlc[";

  private static final String[] PATTERNS = { LIBRARY_PATTERN, CORE_LIBRARY_PATTERN };
  private static final EnvironmentSetter IGNORING_SETTER = (_, _) -> true;
  private static final String JNA_LIBRARY_PATH = "jna.library.path";

  private static final String LIBRARY_FILE = "libvlc.dll";
  private static final String CORE_LIBRARY_FILE = "libvlccore.dll";
  private static final String BACKUP_LIBRARY_FILE = "libvlc.dll.bak";
  private static final String PREFIXED_CORE_LIBRARY_FILE = "xlibvlccore.dll";
  private static final String README_FILE = "readme.txt";
  private static final String REGULAR_FILE = "file.txt";

  private static final String VLC_DIRECTORY = "vlc";
  private static final String ROOT_DIRECTORY = "root";
  private static final String CHILD_DIRECTORY = "child";
  private static final String GRANDCHILD_DIRECTORY = "grandchild";
  private static final String DEEP_DIRECTORY = "deep";
  private static final String FIRST_BRANCH_DIRECTORY = "a";
  private static final String SECOND_BRANCH_DIRECTORY = "b";
  private static final String LOOP_LINK = "loop";
  private static final String EMPTY_DIRECTORY = "empty";
  private static final String CROWDED_DIRECTORY = "crowded";
  private static final String EMPTY_CHILD_PREFIX = "empty-";
  private static final String SECOND_ONLY_DIRECTORY = "second-only";
  private static final String PARTIAL_DIRECTORY = "partial";
  private static final String FAKE_DIRECTORY = "fake";
  private static final String SIMILAR_DIRECTORY = "similar";
  private static final String SINGLE_DIRECTORY = "single";
  private static final String MISSING_DIRECTORY = "missing";
  private static final String INVALID_DIRECTORY = "bad\0directory";
  private static final String JNA_DIRECTORY = "jna";
  private static final String FOUND_DIRECTORY = "anywhere";

  private static final String HIGH_DIRECTORY = "high";
  private static final String LOW_DIRECTORY = "low";
  private static final String FIRST_LOW_DIRECTORY = "low-a";
  private static final String SECOND_LOW_DIRECTORY = "low-b";
  private static final String SUPPORTED_DIRECTORY = "supported";
  private static final String UNSUPPORTED_DIRECTORY = "unsupported";

  private static final int HIGH_PRIORITY = 5;
  private static final int UNSUPPORTED_PRIORITY = 9;

  private static final String PLUGIN_DIRECTORY = "plugins";
  private static final String PLUGIN_PARENT_DIRECTORY = "vlc";
  private static final String PLUGIN_PATH_ASSIGNMENT = "VLC_PLUGIN_PATH=";
  private static final List<Path> PLUGIN_DIRECTORIES = List.of(
    Path.of(PLUGIN_DIRECTORY),
    Path.of(PLUGIN_PARENT_DIRECTORY, PLUGIN_DIRECTORY)
  );

  @TempDir
  private Path temporaryDirectory;

  @Test
  void shouldSearchTheDirectoriesOfEveryProviderOfVlcj() {
    final DefaultProviderStrategy strategy = new DefaultProviderStrategy();
    final List<SearchProvider> providers = strategy.getSearchProviders();
    final List<Class<?>> providerClasses = new ArrayList<>();
    final List<Integer> depths = new ArrayList<>();
    for (final SearchProvider provider : providers) {
      final DiscoveryDirectoryProvider directoryProvider = provider.getDirectoryProvider();
      final Class<?> providerClass = directoryProvider.getClass();
      final int maximumDepth = provider.getMaximumDepth();
      providerClasses.add(providerClass);
      depths.add(maximumDepth);
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
    final List<Integer> expectedDepths = List.of(0, 0, 1, 0, 0, 0, 0, 0);
    assertEquals(expectedClasses, providerClasses, "every directory provider of vlcj is searched");
    assertEquals(expectedDepths, depths);
  }

  @Test
  void shouldNotCountAFileThatMatchesNoPattern() throws IOException {
    final Path directory = createDirectory(this.temporaryDirectory, SECOND_ONLY_DIRECTORY);
    createFile(directory, CORE_LIBRARY_FILE);
    createFile(directory, README_FILE);
    final TestStrategy strategy = singleSearch(directory, 0);
    final String found = strategy.discover();
    assertNull(found, "a file that matches no pattern never stands in for one that is missing");
  }

  @Test
  void shouldFindTheDirectoryThatContainsEveryLibrary() throws IOException {
    final Path libraries = createLibraries(this.temporaryDirectory, VLC_DIRECTORY);
    final TestStrategy strategy = singleSearch(libraries, 0);
    final String found = strategy.discover();
    final String expected = libraries.toString();
    assertEquals(expected, found);
  }

  @Test
  void shouldIgnoreDirectoriesWithOnlySomeOfTheLibraries() throws IOException {
    final Path partial = createDirectory(this.temporaryDirectory, PARTIAL_DIRECTORY);
    createFile(partial, LIBRARY_FILE);
    final TestStrategy strategy = singleSearch(partial, 0);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void shouldIgnoreDirectoriesNamedLikeTheLibraries() throws IOException {
    final Path fake = this.temporaryDirectory.resolve(FAKE_DIRECTORY);
    createDirectory(fake, LIBRARY_FILE);
    createDirectory(fake, CORE_LIBRARY_FILE);
    final TestStrategy strategy = singleSearch(fake, 0);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void shouldMatchWholeFileNamesOnly() throws IOException {
    final Path similar = createDirectory(this.temporaryDirectory, SIMILAR_DIRECTORY);
    createFile(similar, BACKUP_LIBRARY_FILE);
    createFile(similar, PREFIXED_CORE_LIBRARY_FILE);
    final TestStrategy strategy = singleSearch(similar, 0);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void shouldNeedEveryPatternToMatchADifferentFile() throws IOException {
    final Path directory = createDirectory(this.temporaryDirectory, SINGLE_DIRECTORY);
    createFile(directory, LIBRARY_FILE);
    final String[] overlapping = { LIBRARY_PATTERN, LIBRARY_PREFIX_PATTERN };
    final SearchProvider provider = search(1, directory, 0);
    final List<SearchProvider> providers = List.of(provider);
    final TestStrategy strategy = new TestStrategy(overlapping, PLUGIN_DIRECTORIES, providers, IGNORING_SETTER);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void shouldOnlyCheckTheProvidedDirectoryAtDepthZero() throws IOException {
    final Path root = this.temporaryDirectory.resolve(ROOT_DIRECTORY);
    createLibraries(root, CHILD_DIRECTORY);
    final TestStrategy strategy = singleSearch(root, 0);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void shouldCheckChildDirectoriesAtDepthOne() throws IOException {
    final Path root = this.temporaryDirectory.resolve(ROOT_DIRECTORY);
    final Path child = createLibraries(root, CHILD_DIRECTORY);
    createFile(root, README_FILE);
    final TestStrategy strategy = singleSearch(root, 1);
    final String found = strategy.discover();
    final String expected = child.toString();
    assertEquals(expected, found);
  }

  @Test
  void shouldNeverSearchDeeperThanTheMaximumDepth() throws IOException {
    final Path root = this.temporaryDirectory.resolve(ROOT_DIRECTORY);
    createLibraries(root, CHILD_DIRECTORY, GRANDCHILD_DIRECTORY);
    final TestStrategy strategy = singleSearch(root, 1);
    final String found = strategy.discover();
    assertNull(found);
  }

  @Test
  void shouldPreferTheShallowestMatch() throws IOException {
    final Path root = this.temporaryDirectory.resolve(ROOT_DIRECTORY);
    createLibraries(root, FIRST_BRANCH_DIRECTORY, DEEP_DIRECTORY);
    final Path shallow = createLibraries(root, SECOND_BRANCH_DIRECTORY);
    final TestStrategy strategy = singleSearch(root, 2);
    final String found = strategy.discover();
    final String expected = shallow.toString();
    assertEquals(expected, found);
  }

  @Test
  void shouldPreferProvidersWithAHigherPriority() throws IOException {
    final Path low = createLibraries(this.temporaryDirectory, LOW_DIRECTORY);
    final Path high = createLibraries(this.temporaryDirectory, HIGH_DIRECTORY);
    final SearchProvider lowSearch = search(1, low, 0);
    final SearchProvider highSearch = search(HIGH_PRIORITY, high, 0);
    final TestStrategy strategy = strategy(lowSearch, highSearch);
    final String found = strategy.discover();
    final String expected = high.toString();
    assertEquals(expected, found);
  }

  @Test
  void shouldSkipUnsupportedProviders() throws IOException {
    final Path unsupported = createLibraries(this.temporaryDirectory, UNSUPPORTED_DIRECTORY);
    final Path supported = createLibraries(this.temporaryDirectory, SUPPORTED_DIRECTORY);
    final String rawUnsupported = unsupported.toString();
    final StaticDirectoryProvider unsupportedProvider = new StaticDirectoryProvider(
      UNSUPPORTED_PRIORITY,
      ProviderSupport.UNSUPPORTED,
      rawUnsupported
    );
    final SearchProvider unsupportedSearch = new SearchProvider(unsupportedProvider, 0);
    final SearchProvider supportedSearch = search(1, supported, 0);
    final TestStrategy strategy = strategy(unsupportedSearch, supportedSearch);
    final String found = strategy.discover();
    final String expected = supported.toString();
    assertEquals(expected, found);
  }

  @Test
  void shouldSkipBlankMissingAndInvalidDirectoryEntries() throws IOException {
    final Path libraries = createLibraries(this.temporaryDirectory, VLC_DIRECTORY);
    final Path regularFile = createFile(this.temporaryDirectory, REGULAR_FILE);
    final Path missing = this.temporaryDirectory.resolve(MISSING_DIRECTORY);
    final String rawMissing = missing.toString();
    final String rawRegularFile = regularFile.toString();
    final String rawLibraries = libraries.toString();
    final String[] entries = { null, "", "   ", rawMissing, rawRegularFile, INVALID_DIRECTORY, rawLibraries };
    final StaticDirectoryProvider provider = new StaticDirectoryProvider(1, ProviderSupport.SUPPORTED, entries);
    final SearchProvider providerSearch = new SearchProvider(provider, 0);
    final TestStrategy strategy = strategy(providerSearch);
    final String found = strategy.discover();
    assertEquals(rawLibraries, found);
  }

  @Test
  void shouldSearchADirectoryAgainWhenAProviderAllowsADeeperSearch() throws IOException {
    final Path root = this.temporaryDirectory.resolve(ROOT_DIRECTORY);
    final Path child = createLibraries(root, CHILD_DIRECTORY);
    final SearchProvider shallowFirst = search(HIGH_PRIORITY, root, 0);
    final SearchProvider deeperSecond = search(1, root, 1);
    final TestStrategy strategy = strategy(shallowFirst, deeperSecond);
    final String found = strategy.discover();
    final String expected = child.toString();
    assertEquals(expected, found);
  }

  @Test
  void shouldSkipADirectoryAlreadySearchedAtLeastAsDeep() throws IOException {
    final Path root = this.temporaryDirectory.resolve(ROOT_DIRECTORY);
    final Path child = createDirectory(root, CHILD_DIRECTORY);
    createFile(child, LIBRARY_FILE);
    final Path missingCore = child.resolve(CORE_LIBRARY_FILE);
    final String rawChild = child.toString();
    final CompletingDirectoryProvider completing = new CompletingDirectoryProvider(missingCore, rawChild);
    final SearchProvider rootSearch = search(HIGH_PRIORITY, root, 1);
    final SearchProvider completingSearch = new SearchProvider(completing, 0);
    final TestStrategy strategy = strategy(rootSearch, completingSearch);
    final String found = strategy.discover();
    final boolean completed = Files.exists(missingCore);
    assertTrue(completed, "the second provider was asked");
    assertNull(found, "a directory already searched at least as deep is not listed again");
  }

  @Test
  void shouldFindADirectoryThatIsCompleteWhenItIsFirstSearched() throws IOException {
    final Path child = createDirectory(this.temporaryDirectory, CHILD_DIRECTORY);
    createFile(child, LIBRARY_FILE);
    final Path missingCore = child.resolve(CORE_LIBRARY_FILE);
    final String rawChild = child.toString();
    final CompletingDirectoryProvider completing = new CompletingDirectoryProvider(missingCore, rawChild);
    final SearchProvider completingSearch = new SearchProvider(completing, 0);
    final TestStrategy strategy = strategy(completingSearch);
    final String found = strategy.discover();
    assertEquals(rawChild, found);
  }

  @Test
  void shouldSurviveSymbolicLinkLoops() throws IOException {
    final Path root = createDirectory(this.temporaryDirectory, ROOT_DIRECTORY);
    final Path loop = root.resolve(LOOP_LINK);
    createLinkOrAbort(loop, root);
    final Path libraries = createLibraries(root, DEEP_DIRECTORY, VLC_DIRECTORY);
    final Path empty = createDirectory(this.temporaryDirectory, EMPTY_DIRECTORY);
    final Path emptyLoop = empty.resolve(LOOP_LINK);
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
  void shouldGiveUpAfterTheMaximumNumberOfDirectories() throws IOException {
    final Path crowded = this.temporaryDirectory.resolve(CROWDED_DIRECTORY);
    final int childCount = DirectoryProviderDiscoveryStrategy.MAXIMUM_VISITED_DIRECTORIES - 1;
    for (int index = 0; index < childCount; index++) {
      createDirectory(crowded, EMPTY_CHILD_PREFIX + index);
    }

    final Path libraries = createLibraries(this.temporaryDirectory, VLC_DIRECTORY);
    final SearchProvider crowdedSearch = search(HIGH_PRIORITY, crowded, 1);
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
  void shouldListTheDirectoriesOfTheSupportedProvidersByPriority() {
    final StaticDirectoryProvider low = new StaticDirectoryProvider(
      1,
      ProviderSupport.SUPPORTED,
      FIRST_LOW_DIRECTORY,
      SECOND_LOW_DIRECTORY
    );
    final StaticDirectoryProvider high = new StaticDirectoryProvider(7, ProviderSupport.SUPPORTED, HIGH_DIRECTORY);
    final StaticDirectoryProvider unsupported = new StaticDirectoryProvider(
      UNSUPPORTED_PRIORITY,
      ProviderSupport.UNSUPPORTED,
      UNSUPPORTED_DIRECTORY
    );
    final SearchProvider lowSearch = new SearchProvider(low, 0);
    final SearchProvider highSearch = new SearchProvider(high, 0);
    final SearchProvider unsupportedSearch = new SearchProvider(unsupported, 0);
    final TestStrategy strategy = strategy(lowSearch, highSearch, unsupportedSearch);
    final List<String> directories = strategy.discoveryDirectories();
    final List<String> expected = List.of(HIGH_DIRECTORY, FIRST_LOW_DIRECTORY, SECOND_LOW_DIRECTORY);
    assertEquals(expected, directories);
  }

  @Test
  void shouldReturnANewListOfDirectoriesOnEveryCall() {
    final StaticDirectoryProvider provider = new StaticDirectoryProvider(1, ProviderSupport.SUPPORTED, SUPPORTED_DIRECTORY);
    final SearchProvider search = new SearchProvider(provider, 0);
    final TestStrategy strategy = strategy(search);
    final List<String> first = strategy.discoveryDirectories();
    first.clear();
    final List<String> second = strategy.discoveryDirectories();
    final List<String> expected = List.of(SUPPORTED_DIRECTORY);
    assertEquals(expected, second);
  }

  @Test
  void shouldReadTheJnaLibraryPathInTheDefaultProviders() throws IOException {
    final Path libraries = createLibraries(this.temporaryDirectory, JNA_DIRECTORY);
    final String rawLibraries = libraries.toString();
    final String previous = System.getProperty(JNA_LIBRARY_PATH);
    System.setProperty(JNA_LIBRARY_PATH, rawLibraries);
    try {
      final DefaultProviderStrategy strategy = new DefaultProviderStrategy();
      final List<String> directories = strategy.discoveryDirectories();
      final boolean listed = directories.contains(rawLibraries);
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
  void shouldCopyTheArgumentsItIsConstructedWith() throws IOException {
    final Path libraries = createLibraries(this.temporaryDirectory, VLC_DIRECTORY);
    final String[] patterns = PATTERNS.clone();
    final SearchProvider librarySearch = search(1, libraries, 0);
    final List<SearchProvider> providers = new ArrayList<>();
    providers.add(librarySearch);
    final TestStrategy strategy = new TestStrategy(patterns, PLUGIN_DIRECTORIES, providers, IGNORING_SETTER);
    patterns[0] = UNMATCHED_PATTERN;
    providers.clear();
    final String found = strategy.discover();
    final String expected = libraries.toString();
    assertEquals(expected, found);
  }

  @Test
  void shouldPublishTheFirstExistingPluginDirectory() throws IOException {
    final Path libraries = createLibraries(this.temporaryDirectory, VLC_DIRECTORY);
    createDirectory(libraries, PLUGIN_DIRECTORY);
    createDirectory(libraries, PLUGIN_PARENT_DIRECTORY, PLUGIN_DIRECTORY);
    final List<String> published = new ArrayList<>();
    final TestStrategy strategy = recordingStrategy(published, SetterOutcome.SUCCEEDS);
    final String libraryPath = libraries.toString();
    final boolean pathSet = strategy.onSetPluginPath(libraryPath);
    final Path plugins = libraries.resolve(PLUGIN_DIRECTORY);
    final List<String> expected = List.of(PLUGIN_PATH_ASSIGNMENT + plugins);
    assertTrue(pathSet);
    assertEquals(expected, published);
  }

  @Test
  void shouldFallBackToTheNextPluginDirectoryFormat() throws IOException {
    final Path libraries = createLibraries(this.temporaryDirectory, VLC_DIRECTORY);
    createDirectory(libraries, PLUGIN_PARENT_DIRECTORY, PLUGIN_DIRECTORY);
    final List<String> published = new ArrayList<>();
    final TestStrategy strategy = recordingStrategy(published, SetterOutcome.SUCCEEDS);
    final String libraryPath = libraries.toString();
    final boolean pathSet = strategy.onSetPluginPath(libraryPath);
    final Path pluginParent = libraries.resolve(PLUGIN_PARENT_DIRECTORY);
    final Path plugins = pluginParent.resolve(PLUGIN_DIRECTORY);
    final List<String> expected = List.of(PLUGIN_PATH_ASSIGNMENT + plugins);
    assertTrue(pathSet);
    assertEquals(expected, published);
  }

  @Test
  void shouldReportWhenThePluginPathCouldNotBeSet() throws IOException {
    final Path libraries = createLibraries(this.temporaryDirectory, VLC_DIRECTORY);
    createDirectory(libraries, PLUGIN_DIRECTORY);
    final List<String> published = new ArrayList<>();
    final TestStrategy strategy = recordingStrategy(published, SetterOutcome.FAILS);
    final String libraryPath = libraries.toString();
    final boolean pathSet = strategy.onSetPluginPath(libraryPath);
    final int publishCount = published.size();
    assertFalse(pathSet);
    assertEquals(1, publishCount);
  }

  @Test
  void shouldPublishNothingWithoutAPluginDirectory() throws IOException {
    final Path libraries = createLibraries(this.temporaryDirectory, VLC_DIRECTORY);
    final List<String> published = new ArrayList<>();
    final TestStrategy strategy = recordingStrategy(published, SetterOutcome.SUCCEEDS);
    final String libraryPath = libraries.toString();
    final boolean pathSet = strategy.onSetPluginPath(libraryPath);
    final boolean nothingPublished = published.isEmpty();
    assertFalse(pathSet);
    assertTrue(nothingPublished);
  }

  @Test
  void shouldLetVlcjAddTheFoundDirectoryToTheSearchPath() {
    final TestStrategy strategy = strategy();
    final boolean addsToSearchPath = strategy.onFound(FOUND_DIRECTORY);
    assertTrue(addsToSearchPath);
  }

  @Test
  void shouldRejectInvalidArguments() {
    final List<SearchProvider> providers = List.of();
    final TestStrategy strategy = strategy();
    final String[] patternsWithNull = { LIBRARY_PATTERN, null };
    final List<Path> pluginDirectoriesWithNull = Arrays.asList(Path.of(PLUGIN_DIRECTORY), null);
    final String[] invalidPatterns = { INVALID_PATTERN };
    assertThrows(NullPointerException.class, () -> new TestStrategy(null, PLUGIN_DIRECTORIES, providers, IGNORING_SETTER));
    assertThrows(NullPointerException.class, () -> new TestStrategy(PATTERNS, null, providers, IGNORING_SETTER));
    assertThrows(NullPointerException.class, () -> new TestStrategy(PATTERNS, PLUGIN_DIRECTORIES, null, IGNORING_SETTER));
    assertThrows(NullPointerException.class, () -> new TestStrategy(PATTERNS, PLUGIN_DIRECTORIES, providers, null));
    assertThrows(IllegalArgumentException.class, () -> new TestStrategy(new String[0], PLUGIN_DIRECTORIES, providers, IGNORING_SETTER));
    assertThrows(NullPointerException.class, () -> new TestStrategy(patternsWithNull, PLUGIN_DIRECTORIES, providers, IGNORING_SETTER));
    assertThrows(NullPointerException.class, () -> new TestStrategy(PATTERNS, pluginDirectoriesWithNull, providers, IGNORING_SETTER));
    assertThrows(PatternSyntaxException.class, () -> new TestStrategy(invalidPatterns, PLUGIN_DIRECTORIES, providers, IGNORING_SETTER));
    assertThrows(InvalidPathException.class, () -> strategy.onSetPluginPath(INVALID_DIRECTORY));
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
    createFile(directory, LIBRARY_FILE);
    createFile(directory, CORE_LIBRARY_FILE);
    return directory;
  }

  private static void createLinkOrAbort(final Path link, final Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (final IOException | UnsupportedOperationException exception) {
      final String reason = exception.getMessage();
      final String message = "Creating symbolic links failed with \"%s\" here!".formatted(reason);
      Assumptions.abort(message);
    }
  }

  private static SearchProvider search(final int priority, final Path directory, final int maximumDepth) {
    final String rawDirectory = directory.toString();
    final StaticDirectoryProvider provider = new StaticDirectoryProvider(priority, ProviderSupport.SUPPORTED, rawDirectory);
    return new SearchProvider(provider, maximumDepth);
  }

  private static TestStrategy singleSearch(final Path directory, final int maximumDepth) {
    final SearchProvider provider = search(1, directory, maximumDepth);
    return strategy(provider);
  }

  private static TestStrategy strategy(final SearchProvider... providers) {
    final List<SearchProvider> providerList = List.of(providers);
    return new TestStrategy(PATTERNS, PLUGIN_DIRECTORIES, providerList, IGNORING_SETTER);
  }

  private static TestStrategy recordingStrategy(final List<String> published, final SetterOutcome outcome) {
    final EnvironmentSetter setter = (name, value) -> recordVariable(published, outcome, name, value);
    final List<SearchProvider> noProviders = List.of();
    return new TestStrategy(PATTERNS, PLUGIN_DIRECTORIES, noProviders, setter);
  }

  private static boolean recordVariable(final List<String> published, final SetterOutcome outcome, final String name, final String value) {
    published.add(name + "=" + value);
    return outcome == SetterOutcome.SUCCEEDS;
  }

  private static final class DefaultProviderStrategy extends DirectoryProviderDiscoveryStrategy {

    DefaultProviderStrategy() {
      super(PATTERNS, PLUGIN_DIRECTORIES, IGNORING_SETTER);
    }

    @Override
    public boolean supported() {
      return true;
    }
  }

  private static final class TestStrategy extends DirectoryProviderDiscoveryStrategy {

    TestStrategy(
      final String[] patterns,
      final List<Path> pluginDirectories,
      final List<SearchProvider> providers,
      final EnvironmentSetter setter
    ) {
      super(patterns, pluginDirectories, providers, setter);
    }

    @Override
    public boolean supported() {
      return true;
    }
  }

  private static final class CompletingDirectoryProvider implements DiscoveryDirectoryProvider {

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

  static final class StaticDirectoryProvider implements DiscoveryDirectoryProvider {

    private final int priority;
    private final ProviderSupport support;
    private final String[] directories;

    StaticDirectoryProvider(final int priority, final ProviderSupport support, final String... directories) {
      this.priority = priority;
      this.support = support;
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
      return this.support == ProviderSupport.SUPPORTED;
    }
  }

  enum ProviderSupport {
    SUPPORTED,
    UNSUPPORTED,
  }

  private enum SetterOutcome {
    SUCCEEDS,
    FAILS,
  }
}
