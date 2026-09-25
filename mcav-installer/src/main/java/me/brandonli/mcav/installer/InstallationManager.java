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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves an artifact and its runtime dependencies from Maven repositories and copies the jars into a folder.
 *
 * <p>Resolution uses the local Maven repository of the user as a cache, so artifacts already downloaded by Maven
 * or a previous run are not downloaded again. A downloaded file whose checksum is missing or does not match the
 * one published by the repository fails the installation instead of being used.
 *
 * <p>The jars of an artifact are copied to {@code <folder>/<artifactId>/<groupId>/<artifactId>/<file name>}, where
 * the file name is the one of the local Maven repository, {@code artifactId-version[-classifier].extension}. The
 * group and artifact folders keep equal artifact ids of different groups apart. The resolver selects a single
 * version of every {@code groupId:artifactId}, so the file names in one folder only differ by classifier, as with
 * {@code javacpp-1.5.14.jar} and its native jar {@code javacpp-1.5.14-linux-x86_64.jar}. A jar that is already
 * present with the content of its source is kept, so jars a running server has loaded are not rewritten; any
 * other jar is copied again.
 */
final class InstallationManager implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(InstallationManager.class);
  /**
   * The repositories artifacts are downloaded from.
   */
  static final List<RemoteRepository> REPOSITORIES = createDefaultRepositories();
  /**
   * The checksum files earlier versions wrote into the folder. Nothing reads them, so they are deleted.
   */
  static final List<String> OBSOLETE_FILE_NAMES = List.of("hash.properties", "hashes.properties");

  private static final String RUNTIME_SCOPE = "runtime";
  private static final List<String> CLASSPATH_SCOPES = List.of("compile", "runtime");
  private static final List<String> EXCLUDED_SCOPES = List.of();
  private static final String PART_SUFFIX = ".part";
  private static final Pattern ARTIFACT_ID = Pattern.compile("(?!\\.{1,2}$)[A-Za-z0-9_.-]+");
  private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
  private static final int REQUEST_TIMEOUT_MILLIS = 120_000;
  private static final int MAX_COPY_THREADS = 8;
  private static final long SHUTDOWN_TIMEOUT_MILLIS = 10_000;

  private final RepositorySystem system;
  private final RepositorySystemSession.CloseableSession session;
  private final Path folder;
  private final List<RemoteRepository> repositories;
  private final ExecutorService executor;

  /**
   * Creates a manager that downloads from the {@link #REPOSITORIES} of the library into the local Maven repository
   * of the user.
   *
   * @param folder the folder the jars are copied into
   */
  InstallationManager(final Path folder) {
    final Path localRepository = defaultLocalRepository();
    this(folder, REPOSITORIES, localRepository);
  }

  /**
   * Creates a manager that downloads from other repositories into another local repository, for example a
   * repository on the file system in tests.
   *
   * @param folder          the folder the jars are copied into
   * @param repositories    the remote repositories
   * @param localRepository the local repository that caches downloads
   */
  InstallationManager(final Path folder, final List<RemoteRepository> repositories, final Path localRepository) {
    final RepositorySystemSupplier supplier = new RepositorySystemSupplier();
    this.folder = folder;
    this.repositories = List.copyOf(repositories);
    this.system = supplier.get();
    this.session = createSession(this.system, localRepository);

    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final int threads = Math.clamp(processors, 1, MAX_COPY_THREADS);
    this.executor = Executors.newFixedThreadPool(threads, InstallationManager::createCopyThread);
  }

  /**
   * Creates a copy thread. Copy threads are daemon threads, so a copy never keeps the JVM alive. Visible for testing.
   *
   * @param runnable the work of the thread
   * @return the unstarted thread, named {@code mcav-installer}
   */
  static Thread createCopyThread(final Runnable runnable) {
    final Thread thread = new Thread(runnable, "mcav-installer");
    thread.setDaemon(true);
    return thread;
  }

  private static List<RemoteRepository> createDefaultRepositories() {
    final RemoteRepository central = createRepository("central", "https://repo.maven.apache.org/maven2/");
    final RemoteRepository snapshots = createRepository("brandonli-snapshots", "https://repo.brandonli.me/snapshots/");
    final RemoteRepository paper = createRepository("papermc", "https://repo.papermc.io/repository/maven-public/");
    final RemoteRepository google = createRepository("google", "https://maven.google.com/");
    final RemoteRepository codemc = createRepository("codemc-releases", "https://repo.codemc.io/repository/maven-releases/");
    return List.of(central, snapshots, paper, google, codemc);
  }

  private static Path defaultLocalRepository() {
    final String home = System.getProperty("user.home");
    return Path.of(home, ".m2", "repository");
  }

  /**
   * Creates a repository with the default Maven layout whose releases and snapshots are checked for updates daily
   * and whose downloads fail when their checksum is missing or wrong.
   *
   * @param id  the repository id
   * @param url the base URL, such as {@code https://repo.maven.apache.org/maven2/} or a {@code file:} URL
   * @return the repository
   */
  static RemoteRepository createRepository(final String id, final String url) {
    final RemoteRepository.Builder builder = new RemoteRepository.Builder(id, "default", url);
    final RepositoryPolicy policy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_DAILY, RepositoryPolicy.CHECKSUM_POLICY_FAIL);
    builder.setPolicy(policy);
    return builder.build();
  }

  private static RepositorySystemSession.CloseableSession createSession(final RepositorySystem system, final Path localRepository) {
    final Map<String, String> systemProperties = copySystemProperties();
    final Map<String, String> configProperties = new HashMap<>();
    final String connectTimeout = String.valueOf(CONNECT_TIMEOUT_MILLIS);
    final String requestTimeout = String.valueOf(REQUEST_TIMEOUT_MILLIS);
    configProperties.put("aether.connector.connectTimeout", connectTimeout);
    configProperties.put("aether.connector.requestTimeout", requestTimeout);

    // the supplier configures the dependency selectors and graph transformers Maven itself uses
    final SessionBuilderSupplier builderSupplier = new SessionBuilderSupplier(system);
    final RepositorySystemSession.SessionBuilder builder = builderSupplier.get();
    builder.withLocalRepositoryBaseDirectories(localRepository);
    builder.setSystemProperties(systemProperties);
    builder.setConfigProperties(configProperties);
    return builder.build();
  }

  /**
   * Copies the system properties of the JVM for the resolver session, which uses them like Maven does, for example to
   * activate profiles that depend on the Java version. Visible for testing.
   *
   * @return the system properties by name
   */
  static Map<String, String> copySystemProperties() {
    final Map<String, String> systemProperties = new HashMap<>();
    final Properties jvmProperties = System.getProperties();
    final Set<String> names = jvmProperties.stringPropertyNames();
    for (final String name : names) {
      final String value = jvmProperties.getProperty(name, "");
      systemProperties.put(name, value);
    }
    return systemProperties;
  }

  /**
   * Resolves an artifact with its compile and runtime dependencies and copies them into a subfolder named after
   * the artifact.
   *
   * @param groupId    the group id
   * @param artifactId the artifact id
   * @param version    the version
   * @return the copied jars
   * @throws InstallationException if the artifact id is not a plain Maven id, or resolution or copying fails
   */
  List<Path> downloadDependencies(final String groupId, final String artifactId, final String version) {
    final Matcher matcher = ARTIFACT_ID.matcher(artifactId);
    final boolean validId = matcher.matches();
    if (!validId) {
      throw new InstallationException("Artifact id must contain only letters, digits, dots, underscores or hyphens: " + artifactId);
    }
    final Path target = this.folder.resolve(artifactId);
    try {
      Files.createDirectories(target);
      final List<Artifact> artifacts = this.resolve(groupId, artifactId, version);
      final List<Path> jars = this.copyAll(artifacts, target);
      deleteObsoleteFiles(this.folder);
      return jars;
    } catch (final DependencyResolutionException exception) {
      final String message = exception.getMessage();
      throw new InstallationException("Failed to resolve " + groupId + ":" + artifactId + ":" + version + ": " + message, exception);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new InstallationException("Failed to copy the dependencies of " + artifactId + ": " + message, exception);
    }
  }

  private List<Artifact> resolve(final String groupId, final String artifactId, final String version) throws DependencyResolutionException {
    final DependencyRequest request = this.createRequest(groupId, artifactId, version);
    final DependencyResult result = this.system.resolveDependencies(this.session, request);
    final List<ArtifactResult> results = result.getArtifactResults();

    final List<Artifact> artifacts = new ArrayList<>();
    // resolveDependencies throws unless every artifact was resolved, so each result has an artifact
    for (final ArtifactResult artifactResult : results) {
      final Artifact resolved = artifactResult.getArtifact();
      final Artifact artifact = Objects.requireNonNull(resolved, "Resolved artifacts are present");
      artifacts.add(artifact);
    }

    final int count = artifacts.size();
    LOGGER.info("Resolved {} artifacts for {}", count, artifactId);
    return artifacts;
  }

  private DependencyRequest createRequest(final String groupId, final String artifactId, final String version) {
    final DefaultArtifact root = new DefaultArtifact(groupId, artifactId, "jar", version);
    final Dependency dependency = new Dependency(root, RUNTIME_SCOPE);
    final CollectRequest collect = new CollectRequest();
    collect.setRoot(dependency);
    collect.setRepositories(this.repositories);

    final DependencyRequest request = new DependencyRequest();
    request.setCollectRequest(collect);
    final ScopeDependencyFilter filter = new ScopeDependencyFilter(CLASSPATH_SCOPES, EXCLUDED_SCOPES);
    request.setFilter(filter);
    return request;
  }

  private List<Path> copyAll(final List<Artifact> artifacts, final Path target) throws IOException {
    final List<Future<Path>> futures = new ArrayList<>();
    for (final Artifact artifact : artifacts) {
      final Future<Path> future = this.executor.submit(() -> copy(artifact, target));
      futures.add(future);
    }
    return awaitAll(futures);
  }

  /**
   * Waits for every copy task in order. Visible for testing.
   *
   * @param futures the copy tasks
   * @return the copied jars in the order of the tasks
   * @throws IOException if a task failed or the thread was interrupted while waiting
   */
  static List<Path> awaitAll(final List<Future<Path>> futures) throws IOException {
    final List<Path> jars = new ArrayList<>();
    for (final Future<Path> future : futures) {
      try {
        final Path jar = future.get();
        jars.add(jar);
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
        throw new IOException("Interrupted while copying dependencies", exception);
      } catch (final ExecutionException exception) {
        final Throwable cause = exception.getCause();
        if (cause instanceof final IOException ioException) {
          throw ioException;
        }
        throw new IOException(cause);
      }
    }
    return Collections.unmodifiableList(jars);
  }

  private static Path copy(final Artifact artifact, final Path target) throws IOException {
    final Path resolved = artifact.getPath();
    // resolved artifacts always have a file
    final Path source = Objects.requireNonNull(resolved, "Resolved artifacts have a file");
    final String groupId = artifact.getGroupId();
    final String artifactId = artifact.getArtifactId();
    final String fileName = IOUtils.getFileName(source);
    final Path destination = destinationOf(target, groupId, artifactId, fileName);

    final boolean upToDate = hasSameContent(source, destination);
    if (upToDate) {
      return destination;
    }

    final Path parent = destination.getParent();
    // the destination lies in the artifact folder, so it always has a parent
    final Path directory = Objects.requireNonNull(parent, "Destinations have a parent folder");
    Files.createDirectories(directory);
    final Path partial = destination.resolveSibling(fileName + PART_SUFFIX);
    copyAtomically(source, partial, destination);
    LOGGER.debug("Copied {}", artifact);
    return destination;
  }

  /**
   * Gets the path a jar is copied to: {@code <target>/<groupId>/<artifactId>/<fileName>}. Visible for testing.
   *
   * @param target     the folder of the installed artifact
   * @param groupId    the group id of the jar
   * @param artifactId the artifact id of the jar
   * @param fileName   the file name of the jar in the local Maven repository
   * @return the destination inside the target folder, whose last element is the file name
   * @throws IOException if the coordinates would place the jar outside the target folder, such as a group id of
   *                     {@code ..} in a malicious POM, or not under its own file name, such as a file name of
   *                     {@code ..}, which would name a folder instead
   */
  static Path destinationOf(final Path target, final String groupId, final String artifactId, final String fileName) throws IOException {
    final Path groupFolder = target.resolve(groupId);
    final Path artifactFolder = groupFolder.resolve(artifactId);
    final Path destination = artifactFolder.resolve(fileName);

    final Path normalizedTarget = target.normalize();
    final Path normalizedDestination = destination.normalize();
    final boolean inside = normalizedDestination.startsWith(normalizedTarget);
    if (!inside) {
      throw new IOException("The jar " + groupId + ":" + artifactId + " (" + fileName + ") would be copied outside " + target);
    }
    // a path starts with itself, and an empty, . or .. element can make the destination the target folder itself or
    // a folder in it, so the jar must also end up as a file of its own name
    final Path destinationName = normalizedDestination.getFileName();
    final String name = destinationName == null ? "" : destinationName.toString();
    final boolean isTarget = normalizedDestination.equals(normalizedTarget);
    final boolean keepsItsName = !isTarget && name.equals(fileName);
    if (!keepsItsName) {
      throw new IOException(
        "The jar " + groupId + ":" + artifactId + " (" + fileName + ") would not be copied as a file of that name into " + target
      );
    }
    return destination;
  }

  private static boolean hasSameContent(final Path source, final Path destination) throws IOException {
    final boolean exists = Files.isRegularFile(destination);
    if (!exists) {
      return false;
    }
    final long mismatch = Files.mismatch(source, destination);
    return mismatch == -1L;
  }

  /**
   * Copies a file next to its destination first and then moves it into place, so a crash never leaves a
   * half-written jar behind.
   */
  private static void copyAtomically(final Path source, final Path partial, final Path destination) throws IOException {
    Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
    Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /**
   * Deletes the checksum files of earlier versions. A file that cannot be deleted is only reported, because it
   * does not affect the installation.
   *
   * @param folder the folder the jars are copied into
   */
  private static void deleteObsoleteFiles(final Path folder) {
    for (final String name : OBSOLETE_FILE_NAMES) {
      final Path file = folder.resolve(name);
      try {
        Files.deleteIfExists(file);
      } catch (final IOException exception) {
        LOGGER.warn("Could not delete the obsolete file {}", file, exception);
      }
    }
  }

  /**
   * Stops the copy threads, waiting up to ten seconds for running copies before interrupting them, and then closes
   * the resolver session and shuts the repository system down. The manager cannot be used afterwards.
   */
  @Override
  public void close() {
    shutdown(this.executor, SHUTDOWN_TIMEOUT_MILLIS);
    this.session.close();
    this.system.shutdown();
  }

  /**
   * Checks whether the copy threads have stopped, which {@link #close()} waits for. Visible for testing.
   *
   * @return true once the copy threads have stopped
   */
  boolean isStopped() {
    return this.executor.isTerminated();
  }

  /**
   * Shuts an executor down, waiting for running tasks and interrupting them after a timeout. Visible for testing.
   *
   * @param executor      the executor
   * @param timeoutMillis how long to wait for running tasks
   */
  static void shutdown(final ExecutorService executor, final long timeoutMillis) {
    executor.shutdown();
    try {
      final boolean terminated = executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
      if (!terminated) {
        executor.shutdownNow();
      }
    } catch (final InterruptedException exception) {
      executor.shutdownNow();
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }
}
