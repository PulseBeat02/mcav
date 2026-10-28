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
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import me.brandonli.mcav.installer.testing.LocalMavenRepository;
import me.brandonli.mcav.installer.testing.Optionality;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.transfer.ChecksumFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

final class InstallationManagerTest {

  private static final String APPLICATION = "app";
  private static final String COMPILE_DEPENDENCY = "compile-dep";
  private static final String RUNTIME_DEPENDENCY = "runtime-dep";
  private static final String TEST_DEPENDENCY = "test-dep";
  private static final String PROVIDED_DEPENDENCY = "provided-dep";
  private static final String TRANSITIVE = "transitive";
  private static final String OPTIONAL_TRANSITIVE = "optional-transitive";
  private static final String NATIVES = "natives";
  private static final String PLATFORM = "platform";

  private static final String OTHER_GROUP = "other.group";
  private static final String LINUX_CLASSIFIER = "linux-x86_64";
  private static final String WINDOWS_CLASSIFIER = "windows-x86_64";

  private static final String COMPILE_SCOPE = "compile";
  private static final String RUNTIME_SCOPE = "runtime";
  private static final String TEST_SCOPE = "test";
  private static final String PROVIDED_SCOPE = "provided";

  private static final String TEST_REPOSITORY_ID = "test";

  private static final String REMOTE_FOLDER = "remote";
  private static final String LOCAL_FOLDER = "local";
  private static final String DEPENDENCIES_FOLDER = "dependencies";
  private static final String TARGET_FOLDER = "target";
  private static final String ESCAPE_FOLDER = "escape";
  private static final String ELSEWHERE_FOLDER = "elsewhere";
  private static final String PARENT_FOLDER = "..";

  private static final String CONTENT_FILE = "content.txt";
  private static final String HASH_FILE = "hash.properties";
  private static final String HASHES_FILE = "hashes.properties";
  private static final String FIRST_JAR = "first.jar";
  private static final String SECOND_JAR = "second.jar";
  private static final String ESCAPE_JAR = "escape.jar";
  private static final String PARENT_ESCAPE_JAR = "../../../escape.jar";

  private static final String APPLICATION_JAR = "me.test/app/app-1.0.jar";
  private static final String COMPILE_DEPENDENCY_JAR = "me.test/compile-dep/compile-dep-1.0.jar";
  private static final String RUNTIME_DEPENDENCY_JAR = "me.test/runtime-dep/runtime-dep-1.0.jar";
  private static final String TRANSITIVE_JAR = "me.test/transitive/transitive-1.0.jar";
  private static final String PLATFORM_JAR = "me.test/platform/platform-1.0.jar";
  private static final String NATIVES_JAR = "me.test/natives/natives-1.0.jar";
  private static final String LINUX_NATIVES_JAR = "me.test/natives/natives-1.0-linux-x86_64.jar";
  private static final String WINDOWS_NATIVES_JAR = "me.test/natives/natives-1.0-windows-x86_64.jar";
  private static final String OTHER_GROUP_NATIVES_JAR = "other.group/natives/natives-1.0.jar";
  private static final String JAVACPP_JAR = "javacpp-1.5.14-linux-x86_64.jar";
  private static final String JAVACPP_DESTINATION = "org.bytedeco/javacpp/javacpp-1.5.14-linux-x86_64.jar";

  private static final String PATH_SEPARATOR = "/";
  private static final String VERSION_SEPARATOR = "-";
  private static final String JAR_EXTENSION = ".jar";
  private static final String PART_SUFFIX = ".part";

  private static final String GROUP = "me.test";
  private static final String VERSION = "1.0";
  private static final List<String> NO_DEPENDENCIES = List.of();
  private static final List<String> LEAF_ARTIFACTS = List.of(
    RUNTIME_DEPENDENCY,
    TEST_DEPENDENCY,
    PROVIDED_DEPENDENCY,
    TRANSITIVE,
    OPTIONAL_TRANSITIVE
  );

  private static final long AWAIT_TIMEOUT_SECONDS = 10L;

  @TempDir
  private Path directory;

  private LocalMavenRepository repository;
  private Path localRepository;
  private Path folder;

  @BeforeEach
  void publishTheArtifacts() throws IOException {
    final Path remote = this.directory.resolve(REMOTE_FOLDER);
    this.repository = new LocalMavenRepository(remote);
    this.localRepository = this.directory.resolve(LOCAL_FOLDER);
    this.folder = this.directory.resolve(DEPENDENCIES_FOLDER);
    Files.createDirectories(this.folder);

    this.publishApp();
    this.publishCompileDependency();
    for (final String artifactId : LEAF_ARTIFACTS) {
      this.repository.publish(GROUP, artifactId, VERSION, NO_DEPENDENCIES);
    }
  }

  private void publishApp() throws IOException {
    final String compileDependency = LocalMavenRepository.dependency(
      GROUP,
      COMPILE_DEPENDENCY,
      VERSION,
      COMPILE_SCOPE,
      Optionality.REQUIRED
    );
    final String runtimeDependency = LocalMavenRepository.dependency(
      GROUP,
      RUNTIME_DEPENDENCY,
      VERSION,
      RUNTIME_SCOPE,
      Optionality.REQUIRED
    );
    final String testDependency = LocalMavenRepository.dependency(GROUP, TEST_DEPENDENCY, VERSION, TEST_SCOPE, Optionality.REQUIRED);
    final String providedDependency = LocalMavenRepository.dependency(
      GROUP,
      PROVIDED_DEPENDENCY,
      VERSION,
      PROVIDED_SCOPE,
      Optionality.REQUIRED
    );
    final List<String> dependencies = List.of(compileDependency, runtimeDependency, testDependency, providedDependency);
    this.repository.publish(GROUP, APPLICATION, VERSION, dependencies);
  }

  private void publishCompileDependency() throws IOException {
    final String transitive = LocalMavenRepository.dependency(GROUP, TRANSITIVE, VERSION, COMPILE_SCOPE, Optionality.REQUIRED);
    final String optionalTransitive = LocalMavenRepository.dependency(
      GROUP,
      OPTIONAL_TRANSITIVE,
      VERSION,
      COMPILE_SCOPE,
      Optionality.OPTIONAL
    );
    final List<String> dependencies = List.of(transitive, optionalTransitive);
    this.repository.publish(GROUP, COMPILE_DEPENDENCY, VERSION, dependencies);
  }

  @ParameterizedTest
  @ValueSource(strings = { "", ".", "..", "../escape", "nested/artifact", "nested\\artifact", "/absolute", "C:\\escape", "white space" })
  void shouldRejectUnsafeRootArtifactIdsBeforeCreatingDirectories(final String artifactId) throws IOException {
    final InstallationException failure = assertThrows(InstallationException.class, () -> this.download(artifactId));
    final String message = failure.getMessage();
    final String expected = "Artifact id %s must contain only letters, digits, dots, underscores or hyphens!".formatted(artifactId);
    assertEquals(expected, message);

    try (final Stream<Path> entries = Files.list(this.folder)) {
      final long count = entries.count();
      assertEquals(0, count, "invalid coordinates must not create an installation tree");
    }

    final Path escaped = this.directory.resolve(ESCAPE_FOLDER);
    final boolean escapedExists = Files.exists(escaped);
    assertFalse(escapedExists);
  }

  private List<Path> download(final String artifactId) {
    try (final InstallationManager manager = this.createManager()) {
      return manager.downloadDependencies(GROUP, artifactId, VERSION);
    }
  }

  private InstallationManager createManager() {
    final String url = this.repository.getUrl();
    final RemoteRepository remote = InstallationManager.createRepository(TEST_REPOSITORY_ID, url);
    final List<RemoteRepository> repositories = List.of(remote);
    return new InstallationManager(this.folder, repositories, this.localRepository);
  }

  @Test
  void shouldUseTheRepositoriesOfTheLibraryAndItsDependencies() {
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
  void shouldFailOnBadChecksumsAndCheckForUpdatesDailyInEveryRepository() {
    final RemoteRepository created = InstallationManager.createRepository(TEST_REPOSITORY_ID, "file:///repository/");
    final List<RemoteRepository> repositories = new ArrayList<>(InstallationManager.REPOSITORIES);
    repositories.add(created);
    for (final RemoteRepository remote : repositories) {
      final RepositoryPolicy releases = remote.getPolicy(false);
      final RepositoryPolicy snapshots = remote.getPolicy(true);
      this.assertFailsOnBadChecksumsAndChecksDaily(releases);
      this.assertFailsOnBadChecksumsAndChecksDaily(snapshots);
    }
  }

  private void assertFailsOnBadChecksumsAndChecksDaily(final RepositoryPolicy policy) {
    final boolean enabled = policy.isEnabled();
    final String checksumPolicy = policy.getChecksumPolicy();
    final String artifactUpdates = policy.getArtifactUpdatePolicy();
    final String metadataUpdates = policy.getMetadataUpdatePolicy();
    assertTrue(enabled);
    assertEquals(RepositoryPolicy.CHECKSUM_POLICY_FAIL, checksumPolicy);
    assertEquals(RepositoryPolicy.UPDATE_POLICY_DAILY, artifactUpdates);
    assertEquals(RepositoryPolicy.UPDATE_POLICY_DAILY, metadataUpdates);
  }

  @Test
  void shouldReturnTheSameJarsWhenTheyAreAlreadyUpToDate() {
    final List<Path> first = this.download(APPLICATION);
    final List<Path> second = this.download(APPLICATION);
    assertEquals(first, second, "jars that are already up to date are returned like freshly copied ones");
  }

  @Test
  void shouldStopItsCopyThreadsWhenClosed() {
    final InstallationManager manager = this.createManager();
    manager.downloadDependencies(GROUP, APPLICATION, VERSION);
    final boolean stoppedBeforeClose = manager.isStopped();
    manager.close();
    final boolean stoppedAfterClose = manager.isStopped();
    assertFalse(stoppedBeforeClose);
    assertTrue(stoppedAfterClose);
  }

  @Test
  void shouldCloseTheOwnedRepositorySystem() {
    final RepositorySystemSupplier supplier = new RepositorySystemSupplier();
    final RepositorySystem system = supplier.get();
    final AtomicBoolean ended = new AtomicBoolean();
    system.addOnSystemEndedHandler(() -> ended.set(true));
    try (
      final MockedConstruction<RepositorySystemSupplier> suppliers = mockConstruction(RepositorySystemSupplier.class, (replacement, _) ->
        when(replacement.get()).thenReturn(system)
      )
    ) {
      final InstallationManager manager = this.createManager();
      try {
        final boolean endedBefore = ended.get();
        assertFalse(endedBefore);

        manager.close();
        final boolean endedAfter = ended.get();
        assertTrue(endedAfter, "closing a manager must release its repository system, including registered services");

        final List<RepositorySystemSupplier> constructed = suppliers.constructed();
        final int constructedCount = constructed.size();
        assertEquals(1, constructedCount);
      } finally {
        manager.close();
      }
    } finally {
      system.shutdown();
    }
  }

  @Test
  void shouldCopyOnDaemonThreadsSoTheyNeverKeepTheJvmAlive() {
    final Runnable noWork = () -> {};
    final Thread thread = InstallationManager.createCopyThread(noWork);
    final boolean isDaemon = thread.isDaemon();
    final String name = thread.getName();
    assertTrue(isDaemon);
    assertEquals("mcav-installer", name);
  }

  @Test
  void shouldPassTheSystemPropertiesOfTheJvmToTheResolver() {
    final Map<String, String> copied = InstallationManager.copySystemProperties();
    final String javaVersion = System.getProperty("java.version");
    final String copiedVersion = copied.get("java.version");
    assertEquals(javaVersion, copiedVersion);
  }

  @Test
  void shouldCreateAndCloseTheDefaultManagerWithoutTouchingTheFolder() throws IOException {
    final InstallationManager manager = new InstallationManager(this.folder);
    manager.close();
    try (final Stream<Path> listing = Files.list(this.folder)) {
      final long count = listing.count();
      assertEquals(0, count);
    }
  }

  @Test
  void shouldCopyTheArtifactWithItsCompileAndRuntimeDependenciesOnly() throws IOException {
    final List<Path> jars = this.download(APPLICATION);
    final Path target = this.folder.resolve(APPLICATION);
    final List<String> names = this.relativeNames(target, jars);
    final Set<String> nameSet = Set.copyOf(names);
    final Set<String> expected = Set.of(APPLICATION_JAR, COMPILE_DEPENDENCY_JAR, RUNTIME_DEPENDENCY_JAR, TRANSITIVE_JAR);
    final int count = names.size();
    final String first = names.getFirst();
    assertEquals(4, count);
    assertEquals(expected, nameSet);
    assertEquals(APPLICATION_JAR, first);

    for (final Path jar : jars) {
      final Path source = this.publishedJarOf(jar);
      this.assertSameContent(source, jar, "the copy has the content of the published jar");
    }

    final List<Path> files = this.regularFilesIn(target);
    final int fileCount = files.size();
    assertEquals(4, fileCount, "only the jars are left in the folder, no partial files");
    this.assertUnmodifiable(jars, target);
  }

  private List<String> relativeNames(final Path root, final List<Path> files) {
    return files
      .stream()
      .map(file -> this.relativeName(root, file))
      .toList();
  }

  private String relativeName(final Path root, final Path file) {
    final Path relative = root.relativize(file);
    final String name = relative.toString();
    return name.replace('\\', '/');
  }

  private Path publishedJarOf(final Path copiedJar) {
    final Path fileName = copiedJar.getFileName();
    final String name = fileName.toString();
    final String artifactId = name.replace(VERSION_SEPARATOR + VERSION + JAR_EXTENSION, "");
    return this.publishedJar(artifactId);
  }

  private Path publishedJar(final String artifactId) {
    final Path versionDirectory = this.repository.directoryOf(GROUP, artifactId, VERSION);
    return versionDirectory.resolve(artifactId + VERSION_SEPARATOR + VERSION + JAR_EXTENSION);
  }

  private void assertSameContent(final Path expected, final Path actual, final String message) throws IOException {
    final byte[] expectedBytes = Files.readAllBytes(expected);
    final byte[] actualBytes = Files.readAllBytes(actual);
    assertArrayEquals(expectedBytes, actualBytes, message);
  }

  private List<Path> regularFilesIn(final Path root) throws IOException {
    try (final Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile).toList();
    }
  }

  private void assertUnmodifiable(final List<Path> list, final Path element) {
    assertThrows(UnsupportedOperationException.class, () -> list.add(element));
  }

  @Test
  void shouldCopyClassifierJarsAndEqualArtifactIdsOfOtherGroupsToSeparateFiles() throws IOException {
    final Map<String, Path> expectedSources = this.publishPlatform();

    final List<Path> jars = this.download(PLATFORM);
    final Path target = this.folder.resolve(PLATFORM);
    final Set<Path> distinct = Set.copyOf(jars);
    final int count = jars.size();
    final int distinctCount = distinct.size();
    assertEquals(5, count);
    assertEquals(5, distinctCount, "every jar has its own file");

    for (final Path jar : jars) {
      final String name = this.relativeName(target, jar);
      final Path source = expectedSources.get(name);
      assertNotNull(source, "unexpected jar " + name);
      this.assertSameContent(source, jar, name);
    }

    final List<String> names = this.relativeNames(target, jars);
    final Set<String> nameSet = Set.copyOf(names);
    final Set<String> expectedNames = expectedSources.keySet();
    assertEquals(expectedNames, nameSet);

    final List<Path> files = this.regularFilesIn(target);
    final int fileCount = files.size();
    assertEquals(5, fileCount, "no jar was overwritten and no partial file is left");
  }

  private Map<String, Path> publishPlatform() throws IOException {
    final Path natives = this.repository.publish(GROUP, NATIVES, VERSION, NO_DEPENDENCIES);
    final Path linux = this.repository.publishClassified(GROUP, NATIVES, VERSION, LINUX_CLASSIFIER);
    final Path windows = this.repository.publishClassified(GROUP, NATIVES, VERSION, WINDOWS_CLASSIFIER);
    final Path otherGroup = this.repository.publish(OTHER_GROUP, NATIVES, VERSION, NO_DEPENDENCIES);
    final List<String> dependencies = this.platformDependencies();
    final Path platform = this.repository.publish(GROUP, PLATFORM, VERSION, dependencies);

    return Map.of(
      PLATFORM_JAR,
      platform,
      NATIVES_JAR,
      natives,
      LINUX_NATIVES_JAR,
      linux,
      WINDOWS_NATIVES_JAR,
      windows,
      OTHER_GROUP_NATIVES_JAR,
      otherGroup
    );
  }

  private List<String> platformDependencies() {
    final String nativesDependency = LocalMavenRepository.dependency(GROUP, NATIVES, VERSION, COMPILE_SCOPE, Optionality.REQUIRED);
    final String linuxDependency = LocalMavenRepository.classifiedDependency(GROUP, NATIVES, VERSION, LINUX_CLASSIFIER);
    final String windowsDependency = LocalMavenRepository.classifiedDependency(GROUP, NATIVES, VERSION, WINDOWS_CLASSIFIER);
    final String otherGroupDependency = LocalMavenRepository.dependency(OTHER_GROUP, NATIVES, VERSION, COMPILE_SCOPE, Optionality.REQUIRED);
    return List.of(nativesDependency, linuxDependency, windowsDependency, otherGroupDependency);
  }

  @Test
  void shouldRecopyChangedJarsAndKeepUpToDateOnes() throws IOException {
    this.download(APPLICATION);
    final Path tampered = this.copiedJar(APPLICATION, APPLICATION);
    final Path untouched = this.copiedJar(APPLICATION, COMPILE_DEPENDENCY);
    final Path source = this.publishedJar(APPLICATION);
    final long sourceSize = Files.size(source);
    final byte[] sameSize = new byte[(int) sourceSize];
    Files.write(tampered, sameSize);
    final Instant past = Instant.parse("2001-01-01T00:00:00Z");
    final FileTime pastTime = FileTime.from(past);
    Files.setLastModifiedTime(untouched, pastTime);
    Files.setLastModifiedTime(tampered, pastTime);

    this.download(APPLICATION);
    this.assertSameContent(source, tampered, "a jar with the same size but other content is copied again");

    final FileTime untouchedTime = Files.getLastModifiedTime(untouched);
    final FileTime restoredTime = Files.getLastModifiedTime(tampered);
    assertEquals(pastTime, untouchedTime, "an up-to-date jar is not copied again");
    assertNotEquals(pastTime, restoredTime, "a changed jar is copied again");
  }

  private Path copiedJar(final String installed, final String artifactId) {
    return this.folder.resolve(
      installed +
        PATH_SEPARATOR +
        GROUP +
        PATH_SEPARATOR +
        artifactId +
        PATH_SEPARATOR +
        artifactId +
        VERSION_SEPARATOR +
        VERSION +
        JAR_EXTENSION
    );
  }

  @Test
  void shouldReplaceAPartialFileThatAnInterruptedCopyLeftBehind() throws IOException {
    this.download(RUNTIME_DEPENDENCY);
    final Path copied = this.copiedJar(RUNTIME_DEPENDENCY, RUNTIME_DEPENDENCY);
    final Path fileName = copied.getFileName();
    final Path partial = copied.resolveSibling(fileName + PART_SUFFIX);
    Files.writeString(copied, "outdated jar");
    Files.writeString(partial, "half-written jar");

    this.download(RUNTIME_DEPENDENCY);
    final Path source = this.publishedJar(RUNTIME_DEPENDENCY);
    final boolean partialLeft = Files.exists(partial);
    this.assertSameContent(source, copied, "the jar is copied again in full");
    assertFalse(partialLeft, "the partial file of the interrupted copy is overwritten and moved into place");
  }

  @Test
  void shouldDeleteTheChecksumFilesOfEarlierVersions() throws IOException {
    final List<String> expectedNames = List.of(HASH_FILE, HASHES_FILE);
    final List<String> obsoleteNames = InstallationManager.OBSOLETE_FILE_NAMES;
    assertEquals(expectedNames, obsoleteNames);

    for (final String name : expectedNames) {
      final Path file = this.folder.resolve(name);
      Files.writeString(file, "me.test\\:app\\:1.0=\\uZZZZ\n");
    }

    final List<Path> jars = this.download(RUNTIME_DEPENDENCY);
    final Path expected = this.copiedJar(RUNTIME_DEPENDENCY, RUNTIME_DEPENDENCY);
    final List<Path> expectedJars = List.of(expected);
    assertEquals(expectedJars, jars);

    for (final String name : expectedNames) {
      final Path file = this.folder.resolve(name);
      final boolean exists = Files.exists(file);
      assertFalse(exists, name);
    }
  }

  @Test
  void shouldInstallEvenWhenAnObsoleteFileCannotBeDeleted() throws IOException {
    final Path blocked = this.folder.resolve(HASH_FILE);
    Files.createDirectories(blocked);
    final Path content = blocked.resolve(CONTENT_FILE);
    Files.writeString(content, "keeps the directory from being deleted");

    final List<Path> jars = this.download(RUNTIME_DEPENDENCY);
    final Path expected = this.copiedJar(RUNTIME_DEPENDENCY, RUNTIME_DEPENDENCY);
    final List<Path> expectedJars = List.of(expected);
    final boolean stillThere = Files.isDirectory(blocked);
    assertEquals(expectedJars, jars);
    assertTrue(stillThere);
  }

  @Test
  void shouldRejectDownloadsWhoseChecksumDoesNotMatch() throws IOException {
    final Path jar = this.publishedJar(RUNTIME_DEPENDENCY);
    final Path checksum = LocalMavenRepository.checksumFileOf(jar);
    Files.writeString(checksum, "0000000000000000000000000000000000000000");

    final InstallationException error = assertThrows(InstallationException.class, () -> this.download(RUNTIME_DEPENDENCY));
    final String message = error.getMessage();
    final boolean checksumFailure = this.hasChecksumFailure(error);
    assertEquals("Artifact me.test:runtime-dep:1.0 could not be resolved!", message);
    assertTrue(checksumFailure, "the checksum mismatch fails the download instead of only warning");

    final Path copied = this.copiedJar(RUNTIME_DEPENDENCY, RUNTIME_DEPENDENCY);
    final boolean exists = Files.exists(copied);
    assertFalse(exists);
  }

  private boolean hasChecksumFailure(final Throwable throwable) {
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
  void shouldRejectDownloadsWithoutAChecksum() throws IOException {
    final Path jar = this.publishedJar(RUNTIME_DEPENDENCY);
    final Path checksum = LocalMavenRepository.checksumFileOf(jar);
    Files.delete(checksum);
    final InstallationException error = assertThrows(InstallationException.class, () -> this.download(RUNTIME_DEPENDENCY));
    final boolean checksumFailure = this.hasChecksumFailure(error);
    assertTrue(checksumFailure);
  }

  @Test
  void shouldReportArtifactsThatCannotBeResolved() {
    final InstallationException error = assertThrows(InstallationException.class, () -> this.download("missing"));
    final String message = error.getMessage();
    final Throwable cause = error.getCause();
    assertEquals("Artifact me.test:missing:1.0 could not be resolved!", message);
    assertInstanceOf(DependencyResolutionException.class, cause);
  }

  @Test
  void shouldReportATargetFolderThatIsAFile() throws IOException {
    final Path target = this.folder.resolve(APPLICATION);
    Files.writeString(target, "in the way");
    final InstallationException error = assertThrows(InstallationException.class, () -> this.download(APPLICATION));
    final String message = error.getMessage();
    final Throwable cause = error.getCause();
    assertEquals("Dependencies of app could not be copied!", message);
    assertInstanceOf(FileAlreadyExistsException.class, cause);
  }

  @Test
  void shouldReportJarsThatCannotBeMovedIntoPlace() throws IOException {
    final Path blocked = this.copiedJar(RUNTIME_DEPENDENCY, RUNTIME_DEPENDENCY);
    Files.createDirectories(blocked);
    final Path content = blocked.resolve(CONTENT_FILE);
    Files.writeString(content, "keeps the directory from being replaced");

    final InstallationException error = assertThrows(InstallationException.class, () -> this.download(RUNTIME_DEPENDENCY));
    final String message = error.getMessage();
    final Throwable cause = error.getCause();
    assertEquals("Dependencies of runtime-dep could not be copied!", message);
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void shouldPlaceEveryJarInTheFolderOfItsGroupAndArtifact() throws IOException {
    final Path target = this.directory.resolve(TARGET_FOLDER);
    final Path destination = InstallationManager.destinationOf(target, "org.bytedeco", "javacpp", JAVACPP_JAR);
    final Path expected = target.resolve(JAVACPP_DESTINATION);
    assertEquals(expected, destination);
  }

  @Test
  void shouldRefuseCoordinatesThatWouldLeaveTheTargetFolder() {
    final Path target = this.directory.resolve(TARGET_FOLDER);
    final Path absolute = this.directory.resolve(ELSEWHERE_FOLDER);
    final String absoluteGroup = absolute.toString();
    assertThrows(IOException.class, () -> InstallationManager.destinationOf(target, PARENT_FOLDER, PARENT_FOLDER, ESCAPE_JAR));
    assertThrows(IOException.class, () -> InstallationManager.destinationOf(target, "group", "artifact", PARENT_ESCAPE_JAR));

    final IOException exception = assertThrows(IOException.class, () ->
      InstallationManager.destinationOf(target, absoluteGroup, "artifact", ESCAPE_JAR)
    );
    final String message = exception.getMessage();
    final String expected = "Jar %s:artifact (escape.jar) would land outside %s when copied!".formatted(absoluteGroup, target);
    assertEquals(expected, message);
  }

  // found by InstallationPathsPropertyTest: a path starts with itself
  @ParameterizedTest
  @CsvSource({ "'', '', ''", "., ., .", "group, artifact, ..", "group, artifact, ''", "group, artifact, nested/inner.jar" })
  void shouldRefuseCoordinatesThatWouldNotPutTheJarUnderItsOwnName(final String groupId, final String artifactId, final String fileName) {
    final Path target = this.directory.resolve(TARGET_FOLDER);
    final Path root = this.directory.getRoot();

    assertThrows(IOException.class, () -> InstallationManager.destinationOf(target, groupId, artifactId, fileName));
    assertThrows(IOException.class, () -> InstallationManager.destinationOf(root, groupId, artifactId, fileName));
  }

  @Test
  void shouldAwaitCopiesInTaskOrder() throws IOException {
    final Path first = this.directory.resolve(FIRST_JAR);
    final Path second = this.directory.resolve(SECOND_JAR);
    final CompletableFuture<Path> firstFuture = CompletableFuture.completedFuture(first);
    final CompletableFuture<Path> secondFuture = CompletableFuture.completedFuture(second);
    final List<Future<Path>> futures = List.of(firstFuture, secondFuture);
    final List<Path> jars = InstallationManager.awaitAll(futures);
    final List<Path> expected = List.of(first, second);
    assertEquals(expected, jars);
    this.assertUnmodifiable(jars, first);
  }

  @Test
  void shouldRethrowCopyFailures() {
    final IOException ioFailure = new IOException("Disk is full!");
    final CompletableFuture<Path> ioFuture = CompletableFuture.failedFuture(ioFailure);
    final List<Future<Path>> ioFutures = List.of(ioFuture);
    final IOException rethrown = assertThrows(IOException.class, () -> InstallationManager.awaitAll(ioFutures));
    assertSame(ioFailure, rethrown);

    final IllegalStateException otherFailure = new IllegalStateException("Copy failed!");
    final CompletableFuture<Path> otherFuture = CompletableFuture.failedFuture(otherFailure);
    final List<Future<Path>> otherFutures = List.of(otherFuture);
    final IOException wrapped = assertThrows(IOException.class, () -> InstallationManager.awaitAll(otherFutures));
    final Throwable cause = wrapped.getCause();
    assertSame(otherFailure, cause);
  }

  @Test
  void shouldStopWaitingWhenInterruptedAndKeepTheInterruptFlag() {
    final CompletableFuture<Path> never = new CompletableFuture<>();
    final List<Future<Path>> futures = List.of(never);
    final Thread current = Thread.currentThread();
    current.interrupt();

    final IOException exception = assertThrows(IOException.class, () -> InstallationManager.awaitAll(futures));
    final boolean interrupted = Thread.interrupted();
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertTrue(interrupted);
    assertEquals("Copying the dependencies was interrupted!", message);
    assertInstanceOf(InterruptedException.class, cause);
  }

  @Test
  void shouldWaitForFinishedTasksOnShutdown() {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final Future<?> task = executor.submit(() -> {});
    InstallationManager.shutdown(executor, 10_000);
    final boolean terminated = executor.isTerminated();
    final boolean done = task.isDone();
    assertTrue(terminated);
    assertTrue(done);
  }

  @Test
  void shouldInterruptTasksThatRunTooLongOnShutdown() throws InterruptedException {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch interrupted = new CountDownLatch(1);
    final Future<?> task = this.submitBlockingTask(executor, interrupted);
    InstallationManager.shutdown(executor, 50);
    final boolean taskInterrupted = interrupted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean terminated = executor.awaitTermination(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean done = task.isDone();
    assertTrue(taskInterrupted);
    assertTrue(terminated);
    assertTrue(done);
  }

  private Future<?> submitBlockingTask(final ExecutorService executor, final CountDownLatch interrupted) throws InterruptedException {
    final CountDownLatch started = new CountDownLatch(1);
    final Future<?> task = executor.submit(() -> this.blockUntilInterrupted(started, interrupted));
    final boolean running = started.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(running);

    return task;
  }

  private void blockUntilInterrupted(final CountDownLatch started, final CountDownLatch interrupted) {
    started.countDown();

    final CountDownLatch never = new CountDownLatch(1);
    try {
      never.await();
    } catch (final InterruptedException exception) {
      interrupted.countDown();
    }
  }

  @Test
  void shouldStopWaitingOnShutdownWhenInterruptedAndKeepTheInterruptFlag() throws InterruptedException {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch interrupted = new CountDownLatch(1);
    final Future<?> task = this.submitBlockingTask(executor, interrupted);
    final Thread current = Thread.currentThread();
    current.interrupt();
    InstallationManager.shutdown(executor, 60_000);
    final boolean flagKept = Thread.interrupted();
    final boolean taskInterrupted = interrupted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean terminated = executor.awaitTermination(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean done = task.isDone();
    assertTrue(flagKept);
    assertTrue(taskInterrupted);
    assertTrue(terminated);
    assertTrue(done);
  }
}
