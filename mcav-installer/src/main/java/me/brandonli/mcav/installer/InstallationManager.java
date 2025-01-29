/*
 * This file is part of mcav, a media playback library for Minecraft
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InstallationManager implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(InstallationManager.class);

  private static final List<RemoteRepository> DEFAULT_REPOSITORIES = Stream.of(
    "https://repo.maven.apache.org/maven2/",
    "https://repo.brandonli.me/snapshots",
    "https://maven.google.com/",
    "https://repo.papermc.io/repository/maven-public/",
    "https://oss.sonatype.org/content/repositories/snapshots",
    "https://s01.oss.sonatype.org/content/repositories/snapshots/",
    "https://hub.spigotmc.org/nexus/content/repositories/snapshots/",
    "https://repo.codemc.io/repository/maven-releases/"
  )
    .map(InstallationManager::newDefaultRepository)
    .collect(Collectors.toUnmodifiableList());

  private static final String MCAV_COMMON_GROUP_ID = "me.brandonli";
  private static final String MCAV_VERSION = "1.0.0-SNAPSHOT";

  private static final String HASH_FILE_NAME = "hash.properties";
  private static final int CONNECTION_TIMEOUT_MS = 30_000;
  private static final int REQUEST_TIMEOUT_MS = 60_000;
  private static final int MAX_RETRIES = 3;
  private static final long RETRY_DELAY_MS = 2_000;

  private static RemoteRepository newDefaultRepository(final String url) {
    final UUID random = UUID.randomUUID();
    final String raw = random.toString();
    final String name = String.format("repo-%s", raw);
    return new RemoteRepository.Builder(name, "default", url).build();
  }

  private final RepositorySystem repositorySystem;
  private final RepositorySystemSession repositorySystemSession;
  private final Path downloadPath;
  private final ExecutorService downloadExecutor;
  private final Properties artifactHashes;

  InstallationManager(final Path downloadPath) {
    final RepositorySystemSupplier repositorySystemSupplier = new RepositorySystemSupplier();
    this.downloadPath = downloadPath;
    this.repositorySystem = repositorySystemSupplier.get();
    this.repositorySystemSession = this.createSession(this.repositorySystem);
    this.downloadExecutor = this.createDownloadExecutor();
    this.artifactHashes = this.loadArtifactHashes(downloadPath);
  }

  private Collection<Path> getAllJars() {
    try (final Stream<Path> paths = Files.walk(this.downloadPath)) {
      return paths.filter(this::isJarFile).collect(Collectors.toUnmodifiableSet());
    } catch (final IOException e) {
      throw new InstallationError(e.getMessage(), e);
    }
  }

  private boolean isJarFile(final Path path) {
    final String name = IOUtils.getFileName(path);
    return Files.isRegularFile(path) && name.endsWith(".jar");
  }

  private ExecutorService createDownloadExecutor(@UnderInitialization InstallationManager this) {
    final Runtime runtime = Runtime.getRuntime();
    final int availableProcessors = runtime.availableProcessors();
    return Executors.newFixedThreadPool(availableProcessors);
  }

  public Collection<Path> downloadDependencies(final me.brandonli.mcav.installer.Artifact artifact) {
    try {
      final String artifactId = artifact.getArtifactId();
      final Collection<Artifact> artifacts = this.findTransitiveDependencies(artifactId);
      final CompletableFuture<Void> future = this.saveArtifactsAsync(artifacts);
      future.get();
    } catch (final DependencyResolutionException | IOException | ExecutionException e) {
      throw new InstallationError(e.getMessage(), e);
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new InstallationError(e.getMessage(), e);
    }
    return this.getAllJars();
  }

  private Collection<Artifact> findTransitiveDependencies(final String artifactId) throws DependencyResolutionException {
    final CollectRequest collectRequest = this.createCollectRequest(artifactId);
    final DependencyRequest dependencyRequest = this.createDependencyRequest(collectRequest);
    final DependencyResult dependencyResult = this.repositorySystem.resolveDependencies(this.repositorySystemSession, dependencyRequest);
    final List<ArtifactResult> artifactResults = dependencyResult.getArtifactResults();
    final int size = artifactResults.size();
    final String msg = String.format("Resolved %d dependencies for %s", size, artifactId);
    LOGGER.info(msg);
    return dependencyResult
      .getArtifactResults()
      .stream()
      .filter(ArtifactResult::isResolved)
      .map(ArtifactResult::getArtifact)
      .collect(Collectors.toList());
  }

  private DependencyRequest createDependencyRequest(final CollectRequest collectRequest) {
    final List<String> scopes = List.of("compile", "runtime");
    final ScopeDependencyFilter filter = new ScopeDependencyFilter(scopes, Collections.emptyList());
    final DependencyRequest dependencyRequest = new DependencyRequest();
    dependencyRequest.setCollectRequest(collectRequest);
    dependencyRequest.setFilter(filter);
    return dependencyRequest;
  }

  private CollectRequest createCollectRequest(final String artifactId) {
    final Artifact artifact = new DefaultArtifact(
      InstallationManager.MCAV_COMMON_GROUP_ID,
      artifactId,
      "",
      "jar",
      InstallationManager.MCAV_VERSION
    );
    final CollectRequest collectRequest = new CollectRequest();
    final Dependency dependency = new Dependency(artifact, "compile");
    collectRequest.setRoot(dependency);
    collectRequest.setRepositories(InstallationManager.DEFAULT_REPOSITORIES);
    return collectRequest;
  }

  @Override
  public void close() {
    if (this.downloadExecutor == null || this.downloadExecutor.isShutdown()) {
      return;
    }
    this.downloadExecutor.shutdown();
    try {
      final boolean await = this.downloadExecutor.awaitTermination(10, TimeUnit.SECONDS);
      if (!await) {
        this.downloadExecutor.shutdownNow();
      }
    } catch (final InterruptedException e) {
      this.downloadExecutor.shutdownNow();
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  private DefaultRepositorySystemSession createSession(@UnderInitialization InstallationManager this, final RepositorySystem system) {
    final Properties properties = this.getProperties();
    @SuppressWarnings("deprecation")
    final DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
    final LocalRepository localRepo = new LocalRepository(System.getProperty("user.home") + "/.m2/repository");
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
    session.setSystemProperties(properties);
    session.setConfigProperties(properties);
    session.setReadOnly();
    return session;
  }

  private Properties getProperties(@UnderInitialization InstallationManager this) {
    final Properties properties = new Properties();
    properties.putAll(System.getProperties());
    properties.setProperty("aether.connector.connectTimeout", String.valueOf(CONNECTION_TIMEOUT_MS));
    properties.setProperty("aether.connector.requestTimeout", String.valueOf(REQUEST_TIMEOUT_MS));
    return properties;
  }

  private Properties loadArtifactHashes(@UnderInitialization InstallationManager this, final Path downloadPath) {
    final Properties props = new Properties();
    final Path hashFile = downloadPath.resolve(HASH_FILE_NAME);
    if (Files.notExists(hashFile)) {
      return props;
    }
    try (final InputStream in = Files.newInputStream(hashFile)) {
      props.load(in);
      return props;
    } catch (final IOException e) {
      throw new InstallationError(e.getMessage(), e);
    }
  }

  private void saveArtifactHashes() {
    final Path hashFile = this.downloadPath.resolve(HASH_FILE_NAME);
    try (final OutputStream out = Files.newOutputStream(hashFile)) {
      this.artifactHashes.store(out, "Artifact SHA-256 Hashes");
    } catch (final IOException e) {
      throw new InstallationError(e.getMessage(), e);
    }
  }

  private CompletableFuture<Void> saveArtifactsAsync(final Collection<Artifact> artifacts) throws IOException {
    final int size = artifacts.size();
    final String msg = String.format("Preparing to download %s artifacts", size);
    LOGGER.info(msg);
    Files.createDirectories(this.downloadPath);
    final List<CompletableFuture<Void>> downloadFutures = new ArrayList<>();
    for (final Artifact artifact : artifacts) {
      final String id = artifact.getArtifactId();
      final String completion = String.format("Downloaded artifact %s", id);
      final CompletableFuture<Void> future = CompletableFuture.runAsync(
        () -> this.tryArtifactDownload(artifact),
        this.downloadExecutor
      ).thenRun(() -> LOGGER.info(completion));
      downloadFutures.add(future);
    }
    return CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture[0])).thenRun(this::saveArtifactHashes);
  }

  private void tryArtifactDownload(final Artifact artifact) {
    final Path sourcePath = artifact.getPath();
    final String name = IOUtils.getFileName(sourcePath);
    final Path targetPath = this.downloadPath.resolve(name);
    final String groupId = artifact.getGroupId();
    final String artifactId = artifact.getArtifactId();
    final String version = artifact.getVersion();
    final String artifactKey = String.format("%s:%s:%s", groupId, artifactId, version);
    try {
      for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
        try {
          if (this.jarWithHashExists(targetPath, artifactKey, sourcePath)) {
            return;
          }
          this.downloadJar(sourcePath, targetPath, artifactKey);
          break;
        } catch (final IOException e) {
          this.retryArtifactDownload(e, attempt, sourcePath);
        }
      }
    } catch (final IOException e) {
      throw new InstallationError(e.getMessage(), e);
    }
  }

  private void retryArtifactDownload(final IOException e, final int attempt, final Path sourceFile) throws IOException {
    if (attempt == MAX_RETRIES) {
      throw e;
    }
    final String name = IOUtils.getFileName(sourceFile);
    final String msg = e.getMessage();
    final String message = String.format("Download attempt %d failed for %s: %s", attempt, name, msg);
    LOGGER.error(message);
    try {
      Thread.sleep(RETRY_DELAY_MS);
    } catch (final InterruptedException ie) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new IOException("Download interrupted", ie);
    }
  }

  private void downloadJar(final Path sourceFile, final Path targetPath, final String artifactKey) throws IOException {
    Files.copy(sourceFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
    final String hash = IOUtils.calculateSha256Hash(targetPath);
    this.artifactHashes.setProperty(artifactKey, hash);
  }

  private boolean jarWithHashExists(final Path targetPath, final String artifactKey, final Path sourcePath) throws IOException {
    if (Files.exists(targetPath)) {
      final String existingHash = IOUtils.calculateSha256Hash(targetPath);
      final String storedHash = this.artifactHashes.getProperty(artifactKey);
      return storedHash != null && storedHash.equals(existingHash);
    }
    return false;
  }
}
