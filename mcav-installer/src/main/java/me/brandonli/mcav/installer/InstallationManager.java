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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
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

final class InstallationManager implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(InstallationManager.class);

  static final List<RemoteRepository> REPOSITORIES = createDefaultRepositories();

  static final List<String> OBSOLETE_FILE_NAMES = List.of("hash.properties", "hashes.properties");

  private static final String COMPILE_SCOPE = "compile";
  private static final String RUNTIME_SCOPE = "runtime";
  private static final List<String> CLASSPATH_SCOPES = List.of(COMPILE_SCOPE, RUNTIME_SCOPE);
  private static final List<String> EXCLUDED_SCOPES = List.of();

  private static final String JAR_EXTENSION = "jar";
  private static final String PART_SUFFIX = ".part";

  private static final Pattern ARTIFACT_ID = Pattern.compile("(?!\\.{1,2}$)[A-Za-z0-9_.-]+");

  private static final String CENTRAL_ID = "central";
  private static final String CENTRAL_URL = "https://repo.maven.apache.org/maven2/";
  private static final String SNAPSHOTS_ID = "brandonli-snapshots";
  private static final String SNAPSHOTS_URL = "https://repo.brandonli.me/snapshots/";
  private static final String PAPER_ID = "papermc";
  private static final String PAPER_URL = "https://repo.papermc.io/repository/maven-public/";
  private static final String GOOGLE_ID = "google";
  private static final String GOOGLE_URL = "https://maven.google.com/";
  private static final String CODEMC_ID = "codemc-releases";
  private static final String CODEMC_URL = "https://repo.codemc.io/repository/maven-releases/";
  private static final String REPOSITORY_LAYOUT = "default";

  private static final String USER_HOME_PROPERTY = "user.home";
  private static final String MAVEN_FOLDER = ".m2";
  private static final String LOCAL_REPOSITORY_FOLDER = "repository";

  private static final String CONNECT_TIMEOUT_KEY = "aether.connector.connectTimeout";
  private static final String REQUEST_TIMEOUT_KEY = "aether.connector.requestTimeout";
  private static final int CONNECT_TIMEOUT_MILLISECONDS = 30_000;
  private static final int REQUEST_TIMEOUT_MILLISECONDS = 120_000;

  private static final String COPY_THREAD_NAME = "mcav-installer";
  private static final int MINIMUM_COPY_THREADS = 1;
  private static final int MAXIMUM_COPY_THREADS = 8;
  private static final long SHUTDOWN_TIMEOUT_MILLISECONDS = 10_000;

  private final RepositorySystem system;
  private final RepositorySystemSession.CloseableSession session;
  private final Path folder;
  private final List<RemoteRepository> repositories;
  private final ExecutorService executor;

  InstallationManager(final Path folder) {
    final Path localRepository = defaultLocalRepository();
    this(folder, REPOSITORIES, localRepository);
  }

  InstallationManager(final Path folder, final List<RemoteRepository> repositories, final Path localRepository) {
    final RepositorySystemSupplier supplier = new RepositorySystemSupplier();
    this.folder = folder;
    this.repositories = List.copyOf(repositories);
    this.system = supplier.get();
    this.session = this.createSession(this.system, localRepository);

    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final int threads = Math.clamp(processors, MINIMUM_COPY_THREADS, MAXIMUM_COPY_THREADS);
    this.executor = Executors.newFixedThreadPool(threads, InstallationManager::createCopyThread);
  }

  private static Path defaultLocalRepository() {
    final @Nullable String userHome = System.getProperty(USER_HOME_PROPERTY);
    final String home = requireNonNull(userHome); // checker framework
    return Path.of(home, MAVEN_FOLDER, LOCAL_REPOSITORY_FOLDER);
  }

  private RepositorySystemSession.CloseableSession createSession(
    @UnderInitialization InstallationManager this,
    final RepositorySystem system,
    final Path localRepository
  ) {
    final Map<String, String> systemProperties = copySystemProperties();
    final Map<String, String> configurationProperties = new HashMap<>();
    final String connectTimeout = String.valueOf(CONNECT_TIMEOUT_MILLISECONDS);
    final String requestTimeout = String.valueOf(REQUEST_TIMEOUT_MILLISECONDS);
    configurationProperties.put(CONNECT_TIMEOUT_KEY, connectTimeout);
    configurationProperties.put(REQUEST_TIMEOUT_KEY, requestTimeout);

    final SessionBuilderSupplier builderSupplier = new SessionBuilderSupplier(system);
    final RepositorySystemSession.SessionBuilder builder = builderSupplier.get();
    builder.withLocalRepositoryBaseDirectories(localRepository);
    builder.setSystemProperties(systemProperties);
    builder.setConfigProperties(configurationProperties);
    return builder.build();
  }

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

  static Thread createCopyThread(final Runnable runnable) {
    final Thread thread = new Thread(runnable, COPY_THREAD_NAME);
    thread.setDaemon(true);
    return thread;
  }

  private static List<RemoteRepository> createDefaultRepositories() {
    final RemoteRepository central = createRepository(CENTRAL_ID, CENTRAL_URL);
    final RemoteRepository snapshots = createRepository(SNAPSHOTS_ID, SNAPSHOTS_URL);
    final RemoteRepository paper = createRepository(PAPER_ID, PAPER_URL);
    final RemoteRepository google = createRepository(GOOGLE_ID, GOOGLE_URL);
    final RemoteRepository codemc = createRepository(CODEMC_ID, CODEMC_URL);
    return List.of(central, snapshots, paper, google, codemc);
  }

  static RemoteRepository createRepository(final String id, final String url) {
    final RemoteRepository.Builder builder = new RemoteRepository.Builder(id, REPOSITORY_LAYOUT, url);
    final RepositoryPolicy policy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_DAILY, RepositoryPolicy.CHECKSUM_POLICY_FAIL);
    builder.setPolicy(policy);
    return builder.build();
  }

  @Override
  public void close() {
    shutdown(this.executor, SHUTDOWN_TIMEOUT_MILLISECONDS);
    this.session.close();
    this.system.shutdown();
  }

  static void shutdown(final ExecutorService executor, final long timeoutMilliseconds) {
    executor.shutdown();

    try {
      final boolean terminated = executor.awaitTermination(timeoutMilliseconds, TimeUnit.MILLISECONDS);
      if (!terminated) {
        executor.shutdownNow();
      }
    } catch (final InterruptedException exception) {
      executor.shutdownNow();

      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  List<Path> downloadDependencies(final String groupId, final String artifactId, final String version) {
    final Matcher matcher = ARTIFACT_ID.matcher(artifactId);
    final boolean validId = matcher.matches();
    if (!validId) {
      final String message = "Artifact id %s must contain only letters, digits, dots, underscores or hyphens!".formatted(artifactId);
      throw new InstallationException(message);
    }

    final Path target = this.folder.resolve(artifactId);
    try {
      Files.createDirectories(target);

      final List<Artifact> artifacts = this.resolve(groupId, artifactId, version);
      final List<Path> jars = this.copyAll(artifacts, target);
      this.deleteObsoleteFiles(this.folder);

      return jars;
    } catch (final DependencyResolutionException exception) {
      final String message = "Artifact %s:%s:%s could not be resolved!".formatted(groupId, artifactId, version);
      throw new InstallationException(message, exception);
    } catch (final IOException exception) {
      final String message = "Dependencies of %s could not be copied!".formatted(artifactId);
      throw new InstallationException(message, exception);
    }
  }

  private List<Artifact> resolve(final String groupId, final String artifactId, final String version) throws DependencyResolutionException {
    final DependencyRequest request = this.createRequest(groupId, artifactId, version);
    final DependencyResult result = this.system.resolveDependencies(this.session, request);
    final List<ArtifactResult> results = result.getArtifactResults();
    final List<Artifact> artifacts = results.stream().map(this::artifactOf).toList();
    final int count = artifacts.size();
    LOGGER.debug("Resolved {} artifacts for {}, copying them.", count, artifactId);

    return artifacts;
  }

  private DependencyRequest createRequest(final String groupId, final String artifactId, final String version) {
    final DefaultArtifact root = new DefaultArtifact(groupId, artifactId, JAR_EXTENSION, version);
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

  private Artifact artifactOf(final ArtifactResult artifactResult) {
    final Artifact resolved = artifactResult.getArtifact();
    return requireNonNull(resolved, "Resolved artifacts must be present!");
  }

  private List<Path> copyAll(final List<Artifact> artifacts, final Path target) throws IOException {
    final List<Future<Path>> futures = new ArrayList<>();
    for (final Artifact artifact : artifacts) {
      final Future<Path> future = this.executor.submit(() -> this.copy(artifact, target));
      futures.add(future);
    }

    return awaitAll(futures);
  }

  static List<Path> awaitAll(final List<Future<Path>> futures) throws IOException {
    final List<Path> jars = new ArrayList<>();
    for (final Future<Path> future : futures) {
      final Path jar = awaitJar(future);
      jars.add(jar);
    }

    return Collections.unmodifiableList(jars);
  }

  private static Path awaitJar(final Future<Path> future) throws IOException {
    try {
      return future.get();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "Copying the dependencies was interrupted!";
      throw new IOException(message, exception);
    } catch (final ExecutionException exception) {
      final Throwable cause = exception.getCause();
      if (cause instanceof final IOException ioException) {
        throw ioException;
      }

      final String message = "A dependency could not be copied!";
      throw new IOException(message, cause);
    }
  }

  private Path copy(final Artifact artifact, final Path target) throws IOException {
    final Path resolved = artifact.getPath();
    final Path source = requireNonNull(resolved, "Resolved artifacts must have a file!");
    final String groupId = artifact.getGroupId();
    final String artifactId = artifact.getArtifactId();
    final String fileName = IoUtils.getFileName(source);
    final Path destination = destinationOf(target, groupId, artifactId, fileName);

    // a jar that a running server has loaded must not be rewritten
    final boolean upToDate = this.hasSameContent(source, destination);
    if (upToDate) {
      return destination;
    }

    final Path parent = destination.getParent();
    final Path directory = requireNonNull(parent, "Destinations must have a parent folder!");
    Files.createDirectories(directory);

    final Path partial = destination.resolveSibling(fileName + PART_SUFFIX);
    this.copyAtomically(source, partial, destination);
    LOGGER.debug("Copied {} into the installation folder.", artifact);

    return destination;
  }

  static Path destinationOf(final Path target, final String groupId, final String artifactId, final String fileName) throws IOException {
    final Path groupFolder = target.resolve(groupId);
    final Path artifactFolder = groupFolder.resolve(artifactId);
    final Path destination = artifactFolder.resolve(fileName);

    final Path normalizedTarget = target.normalize();
    final Path normalizedDestination = destination.normalize();
    final boolean inside = normalizedDestination.startsWith(normalizedTarget);
    if (!inside) {
      final String message = "Jar %s:%s (%s) would land outside %s when copied!".formatted(groupId, artifactId, fileName, target);
      throw new IOException(message);
    }

    final boolean keepsItsName = keepsFileName(normalizedTarget, normalizedDestination, fileName);
    if (!keepsItsName) {
      final String message = "Jar %s:%s (%s) would not be copied into %s as a file of that name!".formatted(
        groupId,
        artifactId,
        fileName,
        target
      );
      throw new IOException(message);
    }

    return destination;
  }

  // A path starts with itself; empty, dot and parent elements must not resolve the jar to a directory.
  private static boolean keepsFileName(final Path normalizedTarget, final Path normalizedDestination, final String fileName) {
    final Path destinationName = normalizedDestination.getFileName();
    final String name = destinationName == null ? "" : destinationName.toString();
    final boolean isTarget = normalizedDestination.equals(normalizedTarget);
    return !isTarget && name.equals(fileName);
  }

  private boolean hasSameContent(final Path source, final Path destination) throws IOException {
    final boolean exists = Files.isRegularFile(destination);
    if (!exists) {
      return false;
    }

    final long mismatch = Files.mismatch(source, destination);
    return mismatch == -1L;
  }

  private void copyAtomically(final Path source, final Path partial, final Path destination) throws IOException {
    Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
    Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private void deleteObsoleteFiles(final Path folder) {
    for (final String name : OBSOLETE_FILE_NAMES) {
      final Path file = folder.resolve(name);
      try {
        Files.deleteIfExists(file);
      } catch (final IOException exception) {
        LOGGER.warn("Obsolete file {} could not be deleted.", file, exception);
      }
    }
  }

  boolean isStopped() {
    return this.executor.isTerminated();
  }
}
