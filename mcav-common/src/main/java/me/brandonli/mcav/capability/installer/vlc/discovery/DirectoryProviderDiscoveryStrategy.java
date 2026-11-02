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

import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.factory.discovery.provider.AppDirDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.ConfigDirConfigFileDiscoveryDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.JnaLibraryPathDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.LinuxWellKnownDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.MacOsWellKnownDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.SystemPathDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.UserDirConfigFileDiscoveryDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.provider.WindowsInstallDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;

/**
 * A native discovery strategy that looks for the VLC libraries in the directories named by vlcj's directory
 * providers without ever walking a large directory tree.
 *
 * <p>vlcj's own strategies search every provided directory recursively. That includes every entry of {@code PATH}
 * and the directory of the application, so on a machine whose {@code PATH} contains a mounted drive (WSL mounts the
 * whole Windows drive under {@code /mnt/c}) or whose server folder holds large worlds, discovery can take many
 * minutes. This strategy gives each provider a maximum search depth instead: directories that hold the libraries
 * directly, such as the configured native directory, {@code jna.library.path}, {@code PATH} and the VLC installation
 * directory on Windows, are only checked themselves, and the well-known system library directories are searched one
 * level deep. A directory is searched again only when a provider allows a deeper search of it than before, which
 * also ends symbolic link loops, and one discovery looks into at most {@value #MAXIMUM_VISITED_DIRECTORIES} directories.
 *
 * <p>Subclasses describe the platform: the file name patterns of the libraries, where the plugins live relative to
 * them, and how the plugin path is published to libvlc.
 *
 * <p>Instances hold no mutable state, but discovery sets environment variables of the process, so run one discovery at
 * a time.
 *
 * @since 1.0.0
 */
public abstract class DirectoryProviderDiscoveryStrategy implements NativeDiscoveryStrategy {

  /**
   * The largest number of directories one discovery looks into before it gives up.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_VISITED_DIRECTORIES = 1_024;

  /**
   * The environment variable libvlc reads its plugin directory from.
   *
   * @since 1.0.0
   */
  protected static final String PLUGIN_ENVIRONMENT_NAME = "VLC_PLUGIN_PATH";

  private static final String PATH_NULL_MESSAGE = "Path must not be null!";

  private static final int DIRECT_DEPTH = 0;
  private static final int WELL_KNOWN_DEPTH = 1;

  private static final Comparator<SearchProvider> BY_PRIORITY = Comparator.comparingInt(SearchProvider::getPriority);
  private static final Comparator<SearchProvider> HIGHEST_PRIORITY_FIRST = BY_PRIORITY.reversed();

  private final Pattern[] filenamePatterns;
  private final List<Path> pluginDirectories;
  private final List<SearchProvider> searchProviders;
  private final EnvironmentSetter environmentSetter;

  /**
   * Creates a new strategy that searches the directories of vlcj's providers.
   *
   * @param filenamePatterns  a non-null, nonempty array of non-null regular expressions; each must match a
   *                          library file name; expressions are compiled during construction
   * @param pluginDirectories the plugin directories relative to the library directory, such as {@code plugins},
   *                          copied and tried in order; an empty list publishes no plugin path
   * @param environmentSetter sets the environment variable that tells libvlc where its plugins are
   * @throws NullPointerException                   if an argument, a filename expression or a plugin directory is null
   * @throws IllegalArgumentException               if no filename pattern is supplied
   * @throws java.util.regex.PatternSyntaxException if a filename expression is not a valid regular expression
   * @since 1.0.0
   */
  protected DirectoryProviderDiscoveryStrategy(
    final String[] filenamePatterns,
    final List<Path> pluginDirectories,
    final EnvironmentSetter environmentSetter
  ) {
    final List<SearchProvider> searchProviders = createSearchProviders();
    this(filenamePatterns, pluginDirectories, searchProviders, environmentSetter);
  }

  DirectoryProviderDiscoveryStrategy(
    final String[] filenamePatterns,
    final List<Path> pluginDirectories,
    final List<SearchProvider> searchProviders,
    final EnvironmentSetter environmentSetter
  ) {
    Preconditions.checkNotNull(filenamePatterns, "Filename patterns must not be null!");
    Preconditions.checkNotNull(pluginDirectories, "Plugin directories must not be null!");
    Preconditions.checkNotNull(searchProviders, "Search providers must not be null!");
    Preconditions.checkNotNull(environmentSetter, "Environment setter must not be null!");
    Preconditions.checkArgument(filenamePatterns.length > 0, "At least one filename pattern is required!");

    this.filenamePatterns = compilePatterns(filenamePatterns);
    this.pluginDirectories = List.copyOf(pluginDirectories);
    this.searchProviders = List.copyOf(searchProviders);
    this.environmentSetter = environmentSetter;
  }

  private static Pattern[] compilePatterns(final String[] expressions) {
    final Pattern[] patterns = new Pattern[expressions.length];
    for (int patternIndex = 0; patternIndex < expressions.length; patternIndex++) {
      final String expression = expressions[patternIndex];
      patterns[patternIndex] = Pattern.compile(expression);
    }

    return patterns;
  }

  private static List<SearchProvider> createSearchProviders() {
    final List<SearchProvider> providers = new ArrayList<>();
    final DiscoveryDirectoryProvider configurationDirectory = new ConfigDirConfigFileDiscoveryDirectoryProvider();
    addSearchProvider(providers, configurationDirectory, DIRECT_DEPTH);
    final DiscoveryDirectoryProvider jnaLibraryPath = new JnaLibraryPathDirectoryProvider();
    addSearchProvider(providers, jnaLibraryPath, DIRECT_DEPTH);
    final DiscoveryDirectoryProvider linuxWellKnown = new LinuxWellKnownDirectoryProvider();
    addSearchProvider(providers, linuxWellKnown, WELL_KNOWN_DEPTH);
    final DiscoveryDirectoryProvider macOsWellKnown = new MacOsWellKnownDirectoryProvider();
    addSearchProvider(providers, macOsWellKnown, DIRECT_DEPTH);
    final DiscoveryDirectoryProvider windowsInstall = new WindowsInstallDirectoryProvider();
    addSearchProvider(providers, windowsInstall, DIRECT_DEPTH);
    final DiscoveryDirectoryProvider systemPath = new SystemPathDirectoryProvider();
    addSearchProvider(providers, systemPath, DIRECT_DEPTH);
    final DiscoveryDirectoryProvider applicationDirectory = new AppDirDirectoryProvider();
    addSearchProvider(providers, applicationDirectory, DIRECT_DEPTH);
    final DiscoveryDirectoryProvider userDirectory = new UserDirConfigFileDiscoveryDirectoryProvider();
    addSearchProvider(providers, userDirectory, DIRECT_DEPTH);
    return List.copyOf(providers);
  }

  private static void addSearchProvider(
    final List<SearchProvider> providers,
    final DiscoveryDirectoryProvider directoryProvider,
    final int maximumDepth
  ) {
    final SearchProvider searchProvider = new SearchProvider(directoryProvider, maximumDepth);
    providers.add(searchProvider);
  }

  /**
   * Searches the provided directories, highest priority first, for a directory that contains all the libraries.
   *
   * @return the absolute path of the first directory that contains all the libraries, or {@code null} if none does
   * @since 1.0.0
   */
  @Override
  public final @Nullable String discover() {
    final List<SearchProvider> providers = this.getSupportedProviders();
    final Map<Path, Integer> searchedDepths = new HashMap<>();
    for (final SearchProvider provider : providers) {
      final File found = this.searchProvider(provider, searchedDepths);
      if (found != null) {
        return found.getAbsolutePath();
      }
    }

    return null;
  }

  /**
   * Returns the directories that are searched, in order, without searching them.
   *
   * @return a new mutable list of directory names from supported providers, highest priority first;
   *         modifying it does not change the strategy
   * @since 1.0.0
   */
  public final List<String> getDiscoveryDirectories() {
    final List<SearchProvider> providers = this.getSupportedProviders();
    final List<String> directories = new ArrayList<>();
    for (final SearchProvider provider : providers) {
      final DiscoveryDirectoryProvider directoryProvider = provider.getDirectoryProvider();
      final String[] provided = directoryProvider.directories();
      final List<String> providedList = List.of(provided);
      directories.addAll(providedList);
    }

    return directories;
  }

  private @Nullable File searchProvider(final SearchProvider provider, final Map<Path, Integer> searchedDepths) {
    final DiscoveryDirectoryProvider directoryProvider = provider.getDirectoryProvider();
    final String[] directories = directoryProvider.directories();
    final int maximumDepth = provider.getMaximumDepth();
    for (final String directory : directories) {
      if (directory == null || directory.isBlank()) {
        continue;
      }
      final File root = new File(directory);
      final File found = this.searchTree(root, maximumDepth, searchedDepths);
      if (found != null) {
        return found;
      }
    }

    return null;
  }

  private @Nullable File searchTree(final File root, final int maximumDepth, final Map<Path, Integer> searchedDepths) {
    final Deque<SearchEntry> pending = new ArrayDeque<>();
    final SearchEntry rootEntry = new SearchEntry(root, 0);
    pending.add(rootEntry);
    while (!pending.isEmpty() && searchedDepths.size() < MAXIMUM_VISITED_DIRECTORIES) {
      final SearchEntry entry = pending.removeFirst();
      final File directory = entry.getDirectory();
      final int depth = entry.getDepth();
      final int remainingDepth = maximumDepth - depth;
      final File[] children = listUnsearched(directory, remainingDepth, searchedDepths);
      if (children == null) {
        continue;
      }
      if (this.containsLibraries(children)) {
        return directory;
      }
      if (remainingDepth > 0) {
        enqueueSubdirectories(children, depth + 1, pending);
      }
    }

    return null;
  }

  private static File @Nullable [] listUnsearched(final File directory, final int remainingDepth, final Map<Path, Integer> searchedDepths) {
    final Path realPath = toRealDirectory(directory);
    if (realPath == null) {
      return null;
    }

    final Integer searchedDepth = searchedDepths.get(realPath);
    if (searchedDepth != null && searchedDepth >= remainingDepth) {
      return null;
    }

    searchedDepths.put(realPath, remainingDepth);
    return directory.listFiles();
  }

  private static @Nullable Path toRealDirectory(final File directory) {
    try {
      final Path directoryPath = directory.toPath();
      final Path realPath = directoryPath.toRealPath();
      final boolean isDirectory = Files.isDirectory(realPath);
      return isDirectory ? realPath : null;
    } catch (final IOException | InvalidPathException exception) {
      return null;
    }
  }

  private static void enqueueSubdirectories(final File[] children, final int depth, final Deque<SearchEntry> pending) {
    for (final File child : children) {
      final boolean isDirectory = child.isDirectory();
      if (isDirectory) {
        final SearchEntry childEntry = new SearchEntry(child, depth);
        pending.addLast(childEntry);
      }
    }
  }

  private boolean containsLibraries(final File[] files) {
    final int patternCount = this.filenamePatterns.length;
    final boolean[] matched = new boolean[patternCount];
    int matchCount = 0;
    for (final File file : files) {
      final int patternIndex = this.findUnmatchedPattern(file, matched);
      if (patternIndex >= 0) {
        matched[patternIndex] = true;
        matchCount++;
      }
    }

    return matchCount == patternCount;
  }

  private int findUnmatchedPattern(final File file, final boolean[] matched) {
    final String fileName = file.getName();
    for (int patternIndex = 0; patternIndex < this.filenamePatterns.length; patternIndex++) {
      if (matched[patternIndex]) {
        continue;
      }
      final Pattern pattern = this.filenamePatterns[patternIndex];
      final Matcher matcher = pattern.matcher(fileName);
      if (matcher.matches() && file.isFile()) {
        return patternIndex;
      }
    }

    return -1;
  }

  private List<SearchProvider> getSupportedProviders() {
    final List<SearchProvider> result = new ArrayList<>();
    for (final SearchProvider provider : this.searchProviders) {
      final DiscoveryDirectoryProvider directoryProvider = provider.getDirectoryProvider();
      final boolean supported = directoryProvider.supported();
      if (supported) {
        result.add(provider);
      }
    }

    result.sort(HIGHEST_PRIORITY_FIRST);
    return result;
  }

  /**
   * Called with the directory the libraries were found in, before vlcj adds it to the JNA search path.
   *
   * @param path the directory that contains the libraries
   * @return {@code true} to let vlcj add the directory to the JNA search path
   * @throws NullPointerException if {@code path} is null
   * @since 1.0.0
   */
  @Override
  public boolean onFound(final String path) {
    Preconditions.checkNotNull(path, PATH_NULL_MESSAGE);

    return true;
  }

  /**
   * Publishes the plugin directory that belongs to the found library directory, using the first configured plugin
   * directory that exists below it.
   *
   * @param path the directory that contains the libraries
   * @return {@code true} if a plugin directory exists and was published
   * @throws InvalidPathException if {@code path} is not a valid path on this file system
   * @throws NullPointerException if {@code path} is null
   * @since 1.0.0
   */
  @Override
  public final boolean onSetPluginPath(final String path) {
    Preconditions.checkNotNull(path, PATH_NULL_MESSAGE);

    final Path libraryDirectory = Path.of(path);
    for (final Path relativeDirectory : this.pluginDirectories) {
      final Path pluginDirectory = libraryDirectory.resolve(relativeDirectory);
      final boolean exists = Files.isDirectory(pluginDirectory);
      if (exists) {
        final String pluginPath = pluginDirectory.toString();
        return this.environmentSetter.set(PLUGIN_ENVIRONMENT_NAME, pluginPath);
      }
    }

    return false;
  }

  // Returns the providers this strategy searches, in the order they were created, before any of them is filtered out
  // for being unsupported on this machine. Visible for testing.
  List<SearchProvider> getSearchProviders() {
    return this.searchProviders;
  }

  /**
   * Sets an environment variable of the running process, so native libraries such as libvlc can read it.
   *
   * <p>Implementations are called from the discovering thread only and need not be thread-safe.
   *
   * @since 1.0.0
   */
  @FunctionalInterface
  public interface EnvironmentSetter {
    /**
     * Sets the variable.
     *
     * @param name  the name of the variable
     * @param value the value of the variable
     * @return true if the variable was set
     * @since 1.0.0
     */
    boolean set(String name, String value);
  }

  static final class SearchProvider {

    private final DiscoveryDirectoryProvider directoryProvider;
    private final int maximumDepth;

    SearchProvider(final DiscoveryDirectoryProvider directoryProvider, final int maximumDepth) {
      this.directoryProvider = directoryProvider;
      this.maximumDepth = maximumDepth;
    }

    DiscoveryDirectoryProvider getDirectoryProvider() {
      return this.directoryProvider;
    }

    int getMaximumDepth() {
      return this.maximumDepth;
    }

    private int getPriority() {
      return this.directoryProvider.priority();
    }
  }

  private static final class SearchEntry {

    private final File directory;
    private final int depth;

    SearchEntry(final File directory, final int depth) {
      this.directory = directory;
      this.depth = depth;
    }

    private File getDirectory() {
      return this.directory;
    }

    private int getDepth() {
      return this.depth;
    }
  }
}
