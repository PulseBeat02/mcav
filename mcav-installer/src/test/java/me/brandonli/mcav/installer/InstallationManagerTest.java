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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import me.brandonli.mcav.installer.testing.LocalMavenRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.transfer.ChecksumFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link InstallationManager} against a Maven repository on the file system.
 */
final class InstallationManagerTest {

  private static final String GROUP = "me.test";
  private static final String VERSION = "1.0";
  private static final List<String> NO_DEPENDENCIES = List.of();
  private static final List<String> LEAF_ARTIFACTS = List.of(
    "runtime-dep",
    "test-dep",
    "provided-dep",
    "transitive",
    "optional-transitive"
  );

  @TempDir
  private Path directory;

  private LocalMavenRepository repository;
  private Path localRepository;
  private Path folder;

  @BeforeEach
  void publishTheArtifacts() throws IOException {
    final Path remote = this.directory.resolve("remote");
    this.repository = new LocalMavenRepository(remote);
    this.localRepository = this.directory.resolve("local");
    this.folder = this.directory.resolve("dependencies");
    Files.createDirectories(this.folder);

    this.publishApp();
    this.publishCompileDependency();
    for (final String artifactId : LEAF_ARTIFACTS) {
      this.repository.publish(GROUP, artifactId, VERSION, NO_DEPENDENCIES);
    }
  }

  private void publishApp() throws IOException {
    final String compileDependency = LocalMavenRepository.dependency(GROUP, "compile-dep", VERSION, "compile", false);
    final String runtimeDependency = LocalMavenRepository.dependency(GROUP, "runtime-dep", VERSION, "runtime", false);
    final String testDependency = LocalMavenRepository.dependency(GROUP, "test-dep", VERSION, "test", false);
    final String providedDependency = LocalMavenRepository.dependency(GROUP, "provided-dep", VERSION, "provided", false);
    final List<String> dependencies = List.of(compileDependency, runtimeDependency, testDependency, providedDependency);
    this.repository.publish(GROUP, "app", VERSION, dependencies);
  }

  private void publishCompileDependency() throws IOException {
    final String transitive = LocalMavenRepository.dependency(GROUP, "transitive", VERSION, "compile", false);
    final String optionalTransitive = LocalMavenRepository.dependency(GROUP, "optional-transitive", VERSION, "compile", true);
    final List<String> dependencies = List.of(transitive, optionalTransitive);
    this.repository.publish(GROUP, "compile-dep", VERSION, dependencies);
  }

  /**
   * Publishes a {@code platform} artifact that depends on a jar, two classifier jars of it, and a jar with the same
   * artifact id in another group.
   *
   * @return the published jars by the path their copies are expected at, relative to the artifact folder
   * @throws IOException if the files cannot be written
   */
  private Map<String, Path> publishPlatform() throws IOException {
    final Path natives = this.repository.publish(GROUP, "natives", VERSION, NO_DEPENDENCIES);
    final Path linux = this.repository.publishClassified(GROUP, "natives", VERSION, "linux-x86_64");
    final Path windows = this.repository.publishClassified(GROUP, "natives", VERSION, "windows-x86_64");
    final Path otherGroup = this.repository.publish("other.group", "natives", VERSION, NO_DEPENDENCIES);
    final List<String> dependencies = platformDependencies();
    final Path platform = this.repository.publish(GROUP, "platform", VERSION, dependencies);

    return Map.of(
      "me.test/platform/platform-1.0.jar",
      platform,
      "me.test/natives/natives-1.0.jar",
      natives,
      "me.test/natives/natives-1.0-linux-x86_64.jar",
      linux,
      "me.test/natives/natives-1.0-windows-x86_64.jar",
      windows,
      "other.group/natives/natives-1.0.jar",
      otherGroup
    );
  }

  private static List<String> platformDependencies() {
    final String nativesDependency = LocalMavenRepository.dependency(GROUP, "natives", VERSION, "compile", false);
    final String linuxDependency = LocalMavenRepository.classifiedDependency(GROUP, "natives", VERSION, "linux-x86_64");
    final String windowsDependency = LocalMavenRepository.classifiedDependency(GROUP, "natives", VERSION, "windows-x86_64");
    final String otherGroupDependency = LocalMavenRepository.dependency("other.group", "natives", VERSION, "compile", false);
    return List.of(nativesDependency, linuxDependency, windowsDependency, otherGroupDependency);
  }

  private InstallationManager createManager() {
    final String url = this.repository.getUrl();
    final RemoteRepository remote = InstallationManager.createRepository("test", url);
    final List<RemoteRepository> repositories = List.of(remote);
    return new InstallationManager(this.folder, repositories, this.localRepository);
  }

  private List<Path> download(final String artifactId) {
    try (final InstallationManager manager = this.createManager()) {
      return manager.downloadDependencies(GROUP, artifactId, VERSION);
    }
  }

  private Path publishedJar(final String artifactId) {
    final Path versionDirectory = this.repository.directoryOf(GROUP, artifactId, VERSION);
    return versionDirectory.resolve(artifactId + "-" + VERSION + ".jar");
  }

  private Path publishedJarOf(final Path copiedJar) {
    final Path fileName = copiedJar.getFileName();
    final String name = fileName.toString();
    final String artifactId = name.replace("-" + VERSION + ".jar", "");
    return this.publishedJar(artifactId);
  }

  private Path copiedJar(final String installed, final String artifactId) {
    return this.folder.resolve(installed + "/" + GROUP + "/" + artifactId + "/" + artifactId + "-" + VERSION + ".jar");
  }

  private static List<Path> regularFilesIn(final Path root) throws IOException {
    final List<Path> files = new ArrayList<>();
    try (final Stream<Path> walk = Files.walk(root)) {
      final List<Path> paths = walk.toList();
      for (final Path path : paths) {
        final boolean regular = Files.isRegularFile(path);
        if (regular) {
          files.add(path);
        }
      }
    }
    return files;
  }

  private static String relativeName(final Path root, final Path file) {
    final Path relative = root.relativize(file);
    final String name = relative.toString();
    return name.replace('\\', '/');
  }

  private static List<String> relativeNames(final Path root, final List<Path> files) {
    final List<String> names = new ArrayList<>();
    for (final Path file : files) {
      final String name = relativeName(root, file);
      names.add(name);
    }
    return names;
  }

  private static void assertSameContent(final Path expected, final Path actual, final String message) throws IOException {
    final byte[] expectedBytes = Files.readAllBytes(expected);
    final byte[] actualBytes = Files.readAllBytes(actual);
    assertArrayEquals(expectedBytes, actualBytes, message);
  }

  private static void assertUnmodifiable(final List<Path> list, final Path element) {
    assertThrows(UnsupportedOperationException.class, () -> list.add(element));
  }

  private static void assertFailsOnBadChecksumsAndChecksDaily(final RepositoryPolicy policy) {
    final boolean enabled = policy.isEnabled();
    final String checksumPolicy = policy.getChecksumPolicy();
    final String artifactUpdates = policy.getArtifactUpdatePolicy();
    final String metadataUpdates = policy.getMetadataUpdatePolicy();
    assertTrue(enabled);
    assertEquals(RepositoryPolicy.CHECKSUM_POLICY_FAIL, checksumPolicy);
    assertEquals(RepositoryPolicy.UPDATE_POLICY_DAILY, artifactUpdates);
    assertEquals(RepositoryPolicy.UPDATE_POLICY_DAILY, metadataUpdates);
  }

  private static boolean hasChecksumFailure(final Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof ChecksumFailureException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  @Test
  void usesTheRepositoriesOfTheLibraryAndItsDependencies() {
    final List<String> ids = new ArrayList<>();
    final List<String> urls = new ArrayList<>();
    for (final RemoteRepository remote : InstallationManager.REPOSITORIES) {
      final String id = remote.getId();
      final String url = remote.getUrl();
      final String layout = remote.getContentType();
      ids.add(id);
      urls.add(url);
      assertEquals("default", layout);
    }

    final List<String> expectedIds = List.of("central", "brandonli-snapshots", "papermc", "google", "codemc-releases");
    final List<String> expectedUrls = List.of(
      "https://repo.maven.apache.org/maven2/",
      "https://repo.brandonli.me/snapshots/",
      "https://repo.papermc.io/repository/maven-public/",
      "https://maven.google.com/",
      "https://repo.codemc.io/repository/maven-releases/"
    );
    assertEquals(expectedIds, ids);
    assertEquals(expectedUrls, urls);
  }

  @Test
  void everyRepositoryFailsOnBadChecksumsAndChecksForUpdatesDaily() {
    final RemoteRepository created = InstallationManager.createRepository("test", "file:///repository/");
    final List<RemoteRepository> repositories = new ArrayList<>(InstallationManager.REPOSITORIES);
    repositories.add(created);
    for (final RemoteRepository remote : repositories) {
      final RepositoryPolicy releases = remote.getPolicy(false);
      final RepositoryPolicy snapshots = remote.getPolicy(true);
      assertFailsOnBadChecksumsAndChecksDaily(releases);
      assertFailsOnBadChecksumsAndChecksDaily(snapshots);
    }
  }

  @Test
  void returnsTheSameJarsWhenTheyAreAlreadyUpToDate() {
    final List<Path> first = this.download("app");
    final List<Path> second = this.download("app");
    assertEquals(first, second, "jars that are already up to date are returned like freshly copied ones");
  }

  @Test
  void stopsItsCopyThreadsWhenClosed() {
    final InstallationManager manager = this.createManager();
    manager.downloadDependencies(GROUP, "app", VERSION);
    final boolean stoppedBeforeClose = manager.isStopped();
    manager.close();
    final boolean stoppedAfterClose = manager.isStopped();
    assertFalse(stoppedBeforeClose);
    assertTrue(stoppedAfterClose);
  }

  @Test
  void copiesOnDaemonThreadsSoTheyNeverKeepTheJvmAlive() {
    final Runnable noWork = () -> {};
    final Thread thread = InstallationManager.createCopyThread(noWork);
    final boolean daemon = thread.isDaemon();
    final String name = thread.getName();
    assertTrue(daemon);
    assertEquals("mcav-installer", name);
  }

  @Test
  void passesTheSystemPropertiesOfTheJvmToTheResolver() {
    final Map<String, String> copied = InstallationManager.copySystemProperties();
    final String javaVersion = System.getProperty("java.version");
    final String copiedVersion = copied.get("java.version");
    assertEquals(javaVersion, copiedVersion);
  }

  @Test
  void createsAndClosesTheDefaultManagerWithoutTouchingTheFolder() throws IOException {
    final InstallationManager manager = new InstallationManager(this.folder);
    manager.close();
    try (final Stream<Path> listing = Files.list(this.folder)) {
      final long count = listing.count();
      assertEquals(0, count);
    }
  }

  @Test
  void copiesTheArtifactWithItsCompileAndRuntimeDependenciesOnly() throws IOException {
    final List<Path> jars = this.download("app");
    final Path target = this.folder.resolve("app");
    final List<String> names = relativeNames(target, jars);
    final Set<String> nameSet = Set.copyOf(names);
    final Set<String> expected = Set.of(
      "me.test/app/app-1.0.jar",
      "me.test/compile-dep/compile-dep-1.0.jar",
      "me.test/runtime-dep/runtime-dep-1.0.jar",
      "me.test/transitive/transitive-1.0.jar"
    );
    final int count = names.size();
    final String first = names.getFirst();
    assertEquals(4, count);
    assertEquals(expected, nameSet);
    assertEquals("me.test/app/app-1.0.jar", first);

    for (final Path jar : jars) {
      final Path source = this.publishedJarOf(jar);
      assertSameContent(source, jar, "the copy has the content of the published jar");
    }
    final List<Path> files = regularFilesIn(target);
    final int fileCount = files.size();
    assertEquals(4, fileCount, "only the jars are left in the folder, no partial files");
    assertUnmodifiable(jars, target);
  }

  @Test
  void copiesClassifierJarsAndEqualArtifactIdsOfOtherGroupsToSeparateFiles() throws IOException {
    final Map<String, Path> expectedSources = this.publishPlatform();

    final List<Path> jars = this.download("platform");
    final Path target = this.folder.resolve("platform");
    final Set<Path> distinct = Set.copyOf(jars);
    final int count = jars.size();
    final int distinctCount = distinct.size();
    assertEquals(5, count);
    assertEquals(5, distinctCount, "every jar has its own file");

    for (final Path jar : jars) {
      final String name = relativeName(target, jar);
      final Path source = expectedSources.get(name);
      assertNotNull(source, "unexpected jar " + name);
      assertSameContent(source, jar, name);
    }
    final List<String> names = relativeNames(target, jars);
    final Set<String> nameSet = Set.copyOf(names);
    final Set<String> expectedNames = expectedSources.keySet();
    assertEquals(expectedNames, nameSet);

    final List<Path> files = regularFilesIn(target);
    final int fileCount = files.size();
    assertEquals(5, fileCount, "no jar was overwritten and no partial file is left");
  }

  @Test
  void recopiesChangedJarsAndKeepsUpToDateOnes() throws IOException {
    this.download("app");
    final Path tampered = this.copiedJar("app", "app");
    final Path untouched = this.copiedJar("app", "compile-dep");
    final Path source = this.publishedJar("app");
    final long sourceSize = Files.size(source);
    final byte[] sameSize = new byte[(int) sourceSize];
    Files.write(tampered, sameSize);
    final Instant past = Instant.parse("2001-01-01T00:00:00Z");
    final FileTime pastTime = FileTime.from(past);
    Files.setLastModifiedTime(untouched, pastTime);
    Files.setLastModifiedTime(tampered, pastTime);

    this.download("app");
    assertSameContent(source, tampered, "a jar with the same size but other content is copied again");
    final FileTime untouchedTime = Files.getLastModifiedTime(untouched);
    final FileTime restoredTime = Files.getLastModifiedTime(tampered);
    assertEquals(pastTime, untouchedTime, "an up-to-date jar is not copied again");
    assertNotEquals(pastTime, restoredTime, "a changed jar is copied again");
  }

  @Test
  void deletesTheChecksumFilesOfEarlierVersions() throws IOException {
    final List<String> expectedNames = List.of("hash.properties", "hashes.properties");
    final List<String> obsoleteNames = InstallationManager.OBSOLETE_FILE_NAMES;
    assertEquals(expectedNames, obsoleteNames);
    for (final String name : expectedNames) {
      final Path file = this.folder.resolve(name);
      // a malformed escape, which Properties.load rejects, shows that the files are never parsed
      Files.writeString(file, "me.test\\:app\\:1.0=\\uZZZZ\n");
    }

    final List<Path> jars = this.download("runtime-dep");
    final Path expected = this.copiedJar("runtime-dep", "runtime-dep");
    final List<Path> expectedJars = List.of(expected);
    assertEquals(expectedJars, jars);
    for (final String name : expectedNames) {
      final Path file = this.folder.resolve(name);
      final boolean exists = Files.exists(file);
      assertFalse(exists, name);
    }
  }

  @Test
  void installsEvenWhenAnObsoleteFileCannotBeDeleted() throws IOException {
    final Path blocked = this.folder.resolve("hash.properties");
    Files.createDirectories(blocked);
    final Path content = blocked.resolve("content.txt");
    Files.writeString(content, "keeps the directory from being deleted");

    final List<Path> jars = this.download("runtime-dep");
    final Path expected = this.copiedJar("runtime-dep", "runtime-dep");
    final List<Path> expectedJars = List.of(expected);
    final boolean stillThere = Files.isDirectory(blocked);
    assertEquals(expectedJars, jars);
    assertTrue(stillThere);
  }

  @Test
  void rejectsDownloadsWhoseChecksumDoesNotMatch() throws IOException {
    final Path jar = this.publishedJar("runtime-dep");
    final Path checksum = LocalMavenRepository.checksumFileOf(jar);
    Files.writeString(checksum, "0000000000000000000000000000000000000000");

    final InstallationException error = assertThrows(InstallationException.class, () -> this.download("runtime-dep"));
    final String message = error.getMessage();
    final boolean describesTheArtifact = message.startsWith("Failed to resolve me.test:runtime-dep:1.0: ");
    final boolean checksumFailure = hasChecksumFailure(error);
    assertTrue(describesTheArtifact, message);
    assertTrue(checksumFailure, "the checksum mismatch fails the download instead of only warning");

    final Path copied = this.copiedJar("runtime-dep", "runtime-dep");
    final boolean exists = Files.exists(copied);
    assertFalse(exists);
  }

  @Test
  void rejectsDownloadsWithoutAChecksum() throws IOException {
    final Path jar = this.publishedJar("runtime-dep");
    final Path checksum = LocalMavenRepository.checksumFileOf(jar);
    Files.delete(checksum);
    final InstallationException error = assertThrows(InstallationException.class, () -> this.download("runtime-dep"));
    final boolean checksumFailure = hasChecksumFailure(error);
    assertTrue(checksumFailure);
  }

  @Test
  void reportsArtifactsThatCannotBeResolved() {
    final InstallationException error = assertThrows(InstallationException.class, () -> this.download("missing"));
    final String message = error.getMessage();
    final Throwable cause = error.getCause();
    final boolean describesTheArtifact = message.startsWith("Failed to resolve me.test:missing:1.0: ");
    assertTrue(describesTheArtifact, message);
    assertInstanceOf(DependencyResolutionException.class, cause);
  }

  @Test
  void reportsATargetFolderThatIsAFile() throws IOException {
    final Path target = this.folder.resolve("app");
    Files.writeString(target, "in the way");
    final InstallationException error = assertThrows(InstallationException.class, () -> this.download("app"));
    final String message = error.getMessage();
    final Throwable cause = error.getCause();
    final boolean describesTheCopy = message.startsWith("Failed to copy the dependencies of app: ");
    assertTrue(describesTheCopy, message);
    assertInstanceOf(FileAlreadyExistsException.class, cause);
  }

  @Test
  void reportsJarsThatCannotBeMovedIntoPlace() throws IOException {
    final Path blocked = this.copiedJar("runtime-dep", "runtime-dep");
    Files.createDirectories(blocked);
    final Path content = blocked.resolve("content.txt");
    Files.writeString(content, "keeps the directory from being replaced");

    final InstallationException error = assertThrows(InstallationException.class, () -> this.download("runtime-dep"));
    final String message = error.getMessage();
    final Throwable cause = error.getCause();
    final boolean describesTheCopy = message.startsWith("Failed to copy the dependencies of runtime-dep: ");
    assertTrue(describesTheCopy, message);
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void placesEveryJarInTheFolderOfItsGroupAndArtifact() throws IOException {
    final Path target = this.directory.resolve("target");
    final Path destination = InstallationManager.destinationOf(target, "org.bytedeco", "javacpp", "javacpp-1.5.14-linux-x86_64.jar");
    final Path expected = target.resolve("org.bytedeco/javacpp/javacpp-1.5.14-linux-x86_64.jar");
    assertEquals(expected, destination);
  }

  @Test
  void refusesCoordinatesThatWouldLeaveTheTargetFolder() {
    final Path target = this.directory.resolve("target");
    final Path absolute = this.directory.resolve("elsewhere");
    final String absoluteGroup = absolute.toString();
    assertThrows(IOException.class, () -> InstallationManager.destinationOf(target, "..", "..", "escape.jar"));
    assertThrows(IOException.class, () -> InstallationManager.destinationOf(target, "group", "artifact", "../../../escape.jar"));

    final IOException exception = assertThrows(IOException.class, () ->
      InstallationManager.destinationOf(target, absoluteGroup, "artifact", "escape.jar")
    );
    final String message = exception.getMessage();
    final boolean namesTheTarget = message.contains("would be copied outside " + target);
    assertTrue(namesTheTarget, message);
  }

  @Test
  void awaitsCopiesInTaskOrder() throws IOException {
    final Path first = this.directory.resolve("first.jar");
    final Path second = this.directory.resolve("second.jar");
    final CompletableFuture<Path> firstFuture = CompletableFuture.completedFuture(first);
    final CompletableFuture<Path> secondFuture = CompletableFuture.completedFuture(second);
    final List<Future<Path>> futures = List.of(firstFuture, secondFuture);
    final List<Path> jars = InstallationManager.awaitAll(futures);
    final List<Path> expected = List.of(first, second);
    assertEquals(expected, jars);
    assertUnmodifiable(jars, first);
  }

  @Test
  void rethrowsCopyFailures() {
    final IOException ioFailure = new IOException("disk full");
    final CompletableFuture<Path> ioFuture = CompletableFuture.failedFuture(ioFailure);
    final List<Future<Path>> ioFutures = List.of(ioFuture);
    final IOException rethrown = assertThrows(IOException.class, () -> InstallationManager.awaitAll(ioFutures));
    assertSame(ioFailure, rethrown);

    final IllegalStateException otherFailure = new IllegalStateException("bug");
    final CompletableFuture<Path> otherFuture = CompletableFuture.failedFuture(otherFailure);
    final List<Future<Path>> otherFutures = List.of(otherFuture);
    final IOException wrapped = assertThrows(IOException.class, () -> InstallationManager.awaitAll(otherFutures));
    final Throwable cause = wrapped.getCause();
    assertSame(otherFailure, cause);
  }

  @Test
  void stopsWaitingWhenInterruptedAndKeepsTheInterruptFlag() {
    final CompletableFuture<Path> never = new CompletableFuture<>();
    final List<Future<Path>> futures = List.of(never);
    final Thread current = Thread.currentThread();
    current.interrupt();

    final IOException exception = assertThrows(IOException.class, () -> InstallationManager.awaitAll(futures));
    final boolean interrupted = Thread.interrupted();
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertTrue(interrupted);
    assertEquals("Interrupted while copying dependencies", message);
    assertInstanceOf(InterruptedException.class, cause);
  }

  @Test
  void shutdownWaitsForFinishedTasks() {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final Future<?> task = executor.submit(() -> {});
    InstallationManager.shutdown(executor, 10_000);
    final boolean terminated = executor.isTerminated();
    final boolean done = task.isDone();
    assertTrue(terminated);
    assertTrue(done);
  }

  /**
   * Submits a task that blocks until it is interrupted.
   *
   * @param executor    the executor that runs the task
   * @param interrupted counted down when the task is interrupted
   * @return the future of the task, which completes once the task has been interrupted
   * @throws InterruptedException if the test is interrupted before the task starts
   */
  private static Future<?> submitBlockingTask(final ExecutorService executor, final CountDownLatch interrupted)
    throws InterruptedException {
    final CountDownLatch started = new CountDownLatch(1);
    final Future<?> task = executor.submit(() -> {
      started.countDown();
      final CountDownLatch never = new CountDownLatch(1);
      try {
        never.await();
      } catch (final InterruptedException exception) {
        interrupted.countDown();
      }
    });
    final boolean running = started.await(10, TimeUnit.SECONDS);
    assertTrue(running);
    return task;
  }

  @Test
  void shutdownInterruptsTasksThatRunTooLong() throws InterruptedException {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch interrupted = new CountDownLatch(1);
    final Future<?> task = submitBlockingTask(executor, interrupted);
    InstallationManager.shutdown(executor, 50);
    final boolean taskInterrupted = interrupted.await(10, TimeUnit.SECONDS);
    final boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
    final boolean done = task.isDone();
    assertTrue(taskInterrupted);
    assertTrue(terminated);
    assertTrue(done);
  }

  @Test
  void shutdownStopsWaitingWhenInterruptedAndKeepsTheInterruptFlag() throws InterruptedException {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch interrupted = new CountDownLatch(1);
    final Future<?> task = submitBlockingTask(executor, interrupted);
    final Thread current = Thread.currentThread();
    current.interrupt();
    InstallationManager.shutdown(executor, 60_000);
    final boolean flagKept = Thread.interrupted();
    final boolean taskInterrupted = interrupted.await(10, TimeUnit.SECONDS);
    final boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
    final boolean done = task.isDone();
    assertTrue(flagKept);
    assertTrue(taskInterrupted);
    assertTrue(terminated);
    assertTrue(done);
  }
}
