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
package me.brandonli.mcav.capability.installer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.spi.FileSystemProvider;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.TemporaryUserHome;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.ChecksumMismatchException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class AbstractInstallerTest {

  private static final String NAME = "tool";
  private static final String TOOL_PATH = "/tool";
  private static final byte[] PROGRAM = "#!/bin/sh\necho tool\n".getBytes(StandardCharsets.US_ASCII);
  private static final String PROGRAM_HASH = sha256(PROGRAM);
  private static final Platform CURRENT_PLATFORM = Platform.getCurrentPlatform();
  private static final Platform OTHER_PLATFORM = Platform.ofPlatform(Os.FREEBSD, Arch.ARM, Bits.BITS_32);
  private static final Platform LINUX_X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);
  private static final int CONCURRENT_DOWNLOADS = 4;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final long DOWNLOAD_TIMEOUT_SECONDS = 10L;

  private static final int OK_STATUS = 200;

  private static final String DIGEST_ALGORITHM = "SHA-256";

  private static final String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";

  private static final String WINDOWS_EXECUTABLE_NAME = "tool.exe";
  private static final String FALLBACK_ARCHIVE_NAME = "tool.archive";
  private static final String TEMPORARY_FILE_SUFFIX = ".tmp";

  private static final String CONFIGURATION_FILE_NAME = "config.properties";
  private static final String BLOCKER_FILE_NAME = "blocker";
  private static final String TOOLS_FOLDER_NAME = "tools";
  private static final String INVALID_REMEMBERED_PATH = "bad\0path";

  private static final String ELSEWHERE_FILE_NAME = "installed-elsewhere";
  private static final String GONE_FILE_NAME = "gone";
  private static final String BUSY_FILE_NAME = "busy";
  private static final String FIRST_FILE_NAME = "first";
  private static final String SECOND_FILE_NAME = "second";

  private static final String ARCHIVE_PATH = "/files/pack-1.0.zip";
  private static final String ARCHIVE_FILE_NAME = "pack-1.0.zip";
  private static final String EXTRACTED_FOLDER_NAME = "extracted";

  private static final String POSIX_FOLDER_PATH = "/tools";

  private static final String MCAV_FOLDER_NAME = ".mcav";
  private static final String CACHE_FOLDER_NAME = "cache";
  private static final String HOME_FILE_NAME = "home";
  private static final String TEMPORARY_FOLDER_NAME = "temporary";
  private static final String TEMPORARY_CACHE_ROOT_NAME = "mcav";

  private static final String TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir";

  @TempDir
  private Path folder;

  @Test
  void shouldNameExecutablesWithTheSuffixOfTheOperatingSystem() {
    final String windows = AbstractInstaller.getExecutableFileName(NAME, Os.WINDOWS);
    final String linux = AbstractInstaller.getExecutableFileName(NAME, Os.LINUX);
    final String mac = AbstractInstaller.getExecutableFileName(NAME, Os.MAC);
    final String freeBsd = AbstractInstaller.getExecutableFileName(NAME, Os.FREEBSD);
    assertEquals(WINDOWS_EXECUTABLE_NAME, windows);
    assertEquals(NAME, linux);
    assertEquals(NAME, mac);
    assertEquals(NAME, freeBsd);
  }

  @Test
  void shouldRejectInvalidConstructorArguments() {
    final Supplier<Download[]> noDownloads = () -> new Download[0];
    final Download[] downloadArray = new Download[0];
    assertThrows(NullPointerException.class, () -> new TestInstaller(null, NAME, noDownloads));
    assertThrows(NullPointerException.class, () -> new TestInstaller(this.folder, null, noDownloads));
    assertThrows(NullPointerException.class, () -> new TestInstaller(this.folder, NAME, (Supplier<Download[]>) null));
    assertThrows(NullPointerException.class, () -> new TestInstaller(this.folder, NAME, (Download[]) null));
    assertThrows(IllegalArgumentException.class, () -> new TestInstaller(this.folder, " ", downloadArray));
  }

  @Test
  void shouldInstallIntoTheCacheFolderByDefault(@TempDir final Path home) {
    try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(home)) {
      final Download[] downloads = new Download[0];
      final TestInstaller installer = new TestInstaller(NAME, downloads);
      final Path installerFolder = installer.getFolder();
      final Path defaultFolder = AbstractInstaller.getDefaultExecutableFolderPath();
      final Path cachedFolder = IoUtils.getCachedFolder();
      final Path redirectedHome = redirected.getHome();
      final Path mcavFolder = redirectedHome.resolve(MCAV_FOLDER_NAME);
      final Path expected = mcavFolder.resolve(CACHE_FOLDER_NAME);
      final boolean created = Files.isDirectory(expected);
      assertEquals(expected, installerFolder);
      assertEquals(defaultFolder, installerFolder);
      assertEquals(cachedFolder, defaultFolder);
      assertTrue(created);
    }
  }

  @Test
  void shouldInstallIntoTheTemporaryCacheWhenTheHomeCannotHoldIt(@TempDir final Path directory) throws IOException {
    final Path home = directory.resolve(HOME_FILE_NAME);
    Files.writeString(home, "home is a file");
    final Path temporary = directory.resolve(TEMPORARY_FOLDER_NAME);

    final String previousTemporary = System.getProperty(TEMPORARY_DIRECTORY_PROPERTY);
    final String rawTemporary = temporary.toString();
    System.setProperty(TEMPORARY_DIRECTORY_PROPERTY, rawTemporary);
    try (final TemporaryUserHome _ = TemporaryUserHome.redirectTo(home)) {
      final TestInstaller installer = new TestInstaller(NAME, new Download[0]);
      final Path installerFolder = installer.getFolder();
      final Path temporaryRoot = temporary.resolve(TEMPORARY_CACHE_ROOT_NAME);
      final Path temporaryCache = temporaryRoot.resolve(CACHE_FOLDER_NAME);
      final Path expected = temporaryCache.toAbsolutePath();
      final boolean created = Files.isDirectory(installerFolder);
      assertEquals(expected, installerFolder);
      assertTrue(created);
    } finally {
      System.setProperty(TEMPORARY_DIRECTORY_PROPERTY, previousTemporary);
    }
  }

  @Test
  void shouldResolveTheDownloadsLazilyAndOnlyOnce() {
    final AtomicInteger calls = new AtomicInteger();
    final Download current = new Download(CURRENT_PLATFORM, "https://example.com/tool", "ab12cd");
    final TestInstaller installer = new TestInstaller(this.folder, NAME, () -> this.countAndListDownload(calls, current));
    final int callsAfterConstruction = calls.get();
    final boolean supported = installer.isSupported();
    final String url = installer.getUrl();
    final Optional<String> hash = installer.getHash();
    final int callsAfterUse = calls.get();
    final Optional<String> expected = Optional.of("ab12cd");
    assertEquals(0, callsAfterConstruction);
    assertTrue(supported);
    assertEquals("https://example.com/tool", url);
    assertEquals(expected, hash);
    assertEquals(1, callsAfterUse);
  }

  private Download[] countAndListDownload(final AtomicInteger calls, final Download current) {
    calls.incrementAndGet();
    return new Download[] { current };
  }

  @Test
  void shouldPreferTheExactPlatformOverFallbacks() {
    final Download exact = new Download(OTHER_PLATFORM, "https://example.com/exact", null);
    final Download compatible = new Download(LINUX_X86_64, "https://example.com/compatible", null);
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { compatible, exact });
    final List<Platform> fallbacks = List.of(LINUX_X86_64);
    installer.setFallbacks(fallbacks);

    final Optional<Download> found = installer.findDownload(OTHER_PLATFORM);
    final Optional<Download> expected = Optional.of(exact);
    assertEquals(expected, found);
  }

  @Test
  void shouldFallBackToCompatiblePlatformsInOrder() {
    final Platform second = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_32);
    final Download secondDownload = new Download(second, "https://example.com/second", null);
    final Download firstDownload = new Download(LINUX_X86_64, "https://example.com/first", null);
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { secondDownload, firstDownload });
    final List<Platform> fallbacks = List.of(LINUX_X86_64, second);
    installer.setFallbacks(fallbacks);

    final Optional<Download> found = installer.findDownload(OTHER_PLATFORM);
    final Optional<Download> expected = Optional.of(firstDownload);
    assertEquals(expected, found);
  }

  @Test
  void shouldFindNothingWithoutAMatchingOrCompatiblePlatform() {
    final Platform unrelated = Platform.ofPlatform(Os.MAC, Arch.ARM, Bits.BITS_64);
    final Download download = new Download(unrelated, "https://example.com/mac", null);
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { download });
    final List<Platform> fallbacks = List.of(LINUX_X86_64);
    installer.setFallbacks(fallbacks);

    final Optional<Download> found = installer.findDownload(OTHER_PLATFORM);
    final boolean nothingFound = found.isEmpty();
    assertTrue(nothingFound);
  }

  @Test
  void shouldOfferNoFallbackPlatformsByDefault() {
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[0]);
    final List<Platform> fallbacks = installer.getFallbackPlatforms(OTHER_PLATFORM);
    final boolean noFallbacks = fallbacks.isEmpty();
    assertTrue(noFallbacks);
  }

  @Test
  void shouldReportUnsupportedPlatforms() {
    final Download elsewhere = new Download(OTHER_PLATFORM, "https://example.com/elsewhere", "00");
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { elsewhere });
    final boolean supported = installer.isSupported();
    final String url = installer.getUrl();
    final Optional<String> hash = installer.getHash();
    final boolean hashMissing = hash.isEmpty();
    assertFalse(supported);
    assertEquals("", url);
    assertTrue(hashMissing);

    final InstallationException thrown = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
    final String message = thrown.getMessage();
    final boolean namesTheProgram = message.contains(NAME);
    assertTrue(namesTheProgram, message);
  }

  @Test
  void shouldPointAtTheExecutableBeforeTheProgramIsInstalled() {
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[0]);
    final Path path = installer.getPath();
    final Os operatingSystem = OsUtils.getOs();
    final String expectedName = AbstractInstaller.getExecutableFileName(NAME, operatingSystem);
    final Path expected = this.folder.resolve(expectedName);
    assertEquals(expected, path);
  }

  @Test
  void shouldDownloadVerifyAndRememberTheProgram() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, PROGRAM_HASH);
      final Path installed = installer.download(FileMode.EXECUTABLE);
      final Path expected = installer.getDefaultPath();
      final byte[] content = Files.readAllBytes(installed);
      final Path path = installer.getPath();

      final Properties configuration = this.readConfiguration();
      final Path absolute = installed.toAbsolutePath();
      final String expectedEntry = absolute.toString();
      final String entry = configuration.getProperty(NAME);
      assertEquals(expected, installed);
      assertArrayEquals(PROGRAM, content);
      assertEquals(installed, path);
      assertEquals(expectedEntry, entry);
    }
  }

  @Test
  void shouldReuseAnExistingInstallationWithoutDownloadingAgain() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final TestInstaller first = this.installerDownloadingFrom(server, TOOL_PATH, PROGRAM_HASH);
      final TestInstaller second = this.installerDownloadingFrom(server, TOOL_PATH, PROGRAM_HASH);
      final Path firstPath = first.download(FileMode.UNCHANGED);
      final Path secondPath = second.download(FileMode.UNCHANGED);
      final Path againPath = first.download(FileMode.UNCHANGED);
      final int requests = server.getRequestCount(TOOL_PATH);
      assertEquals(firstPath, secondPath);
      assertEquals(firstPath, againPath);
      assertEquals(1, requests);
    }
  }

  @Test
  void shouldFindAnInstallationRememberedInTheConfiguration() throws IOException {
    final Path elsewhere = this.createFileInFolder(ELSEWHERE_FILE_NAME);
    final TestInstaller writer = new TestInstaller(this.folder, NAME, new Download[0]);
    writer.writePathToConfiguration(elsewhere);

    final Download unreachable = new Download(CURRENT_PLATFORM, "http://127.0.0.1:1/unreachable", null);
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { unreachable });
    final Path installed = installer.download(FileMode.EXECUTABLE);
    final Path expected = elsewhere.toAbsolutePath();
    assertEquals(expected, installed);
  }

  @Test
  void shouldDownloadAgainWhenTheRememberedInstallationIsGone() throws IOException {
    final Path gone = this.folder.resolve(GONE_FILE_NAME);
    final TestInstaller writer = new TestInstaller(this.folder, NAME, new Download[0]);
    writer.writePathToConfiguration(gone);

    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final Path installed = installer.download(FileMode.UNCHANGED);
      final Path expected = installer.getDefaultPath();
      final int requests = server.getRequestCount(TOOL_PATH);
      assertEquals(expected, installed);
      assertEquals(1, requests);
    }
  }

  @Test
  void shouldIgnoreARememberedPathThatIsNotAValidPath() throws IOException {
    final Properties configuration = new Properties();
    configuration.setProperty(NAME, INVALID_REMEMBERED_PATH);
    this.writeConfiguration(configuration);

    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final Path installed = installer.download(FileMode.UNCHANGED);
      final Path expected = installer.getDefaultPath();
      assertEquals(expected, installed);
    }
  }

  @Test
  void shouldIgnoreAConfigurationWithAMalformedEscapeAndRememberTheNewInstallation() throws IOException {
    final Path configurationFile = this.folder.resolve(CONFIGURATION_FILE_NAME);
    Files.writeString(configurationFile, "tool=\\uZZZZ\n", StandardCharsets.ISO_8859_1);

    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final Path installed = installer.download(FileMode.UNCHANGED);
      final Path expected = installer.getDefaultPath();
      assertEquals(expected, installed);

      final Properties configuration = this.readConfiguration();
      final String remembered = configuration.getProperty(NAME);
      final Path absolute = installed.toAbsolutePath();
      final String expectedRemembered = absolute.toString();
      assertEquals(expectedRemembered, remembered);
    }
  }

  @Test
  void shouldRejectADownloadWithTheWrongChecksumAndRememberNothing() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final byte[] otherContent = "something else".getBytes(StandardCharsets.US_ASCII);
      final String otherHash = sha256(otherContent);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, otherHash);
      assertThrows(ChecksumMismatchException.class, () -> installer.download(FileMode.EXECUTABLE));

      final Path defaultPath = installer.getDefaultPath();
      final boolean exists = Files.exists(defaultPath);
      final Properties configuration = this.readConfiguration();
      final boolean nothingRemembered = configuration.isEmpty();
      assertFalse(exists);
      assertTrue(nothingRemembered);
    }
  }

  @Test
  void shouldRememberNothingWhenTheInstallationFails() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, PROGRAM_HASH);
      final InstallationException failure = new InstallationException("Installation failed!");
      installer.failInstallationWith(failure);

      final InstallationException thrown = assertThrows(InstallationException.class, () -> installer.download(FileMode.UNCHANGED));
      assertSame(failure, thrown);

      final Properties configuration = this.readConfiguration();
      final boolean nothingRemembered = configuration.isEmpty();
      assertTrue(nothingRemembered);
    }
  }

  @Test
  void shouldRejectADownloadUrlThatIsNotAValidUri() {
    final Download download = new Download(CURRENT_PLATFORM, "https://example.com/a tool");
    final Download[] downloads = new Download[] { download };
    final TestInstaller installer = new TestInstaller(this.folder, NAME, downloads);
    assertThrows(IllegalArgumentException.class, () -> installer.download(FileMode.UNCHANGED));

    final Optional<Path> installation = installer.findInstallation();
    final boolean nothingInstalled = installation.isEmpty();
    assertTrue(nothingInstalled);
  }

  @Test
  void shouldDownloadOnlyOnceWhenCalledConcurrently() throws Exception {
    final CountDownLatch releaseResponse = new CountDownLatch(1);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respondWhenOpened(TOOL_PATH, OK_STATUS, PROGRAM, releaseResponse);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, PROGRAM_HASH);
      final Path expected = installer.getDefaultPath();
      try (final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_DOWNLOADS)) {
        final List<Future<Path>> results = submitDownloadsTogether(executor, installer);
        awaitToolRequest(server);
        releaseResponse.countDown();
        assertEveryResultIs(expected, results);
      }

      final int requests = server.getRequestCount(TOOL_PATH);
      assertEquals(1, requests);
    }
  }

  @Test
  void shouldAnswerQuestionsWhileADownloadIsRunning() throws Exception {
    final CountDownLatch releaseResponse = new CountDownLatch(1);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respondWhenOpened(TOOL_PATH, OK_STATUS, PROGRAM, releaseResponse);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final String url = installer.getUrl();
      final Path defaultPath = installer.getDefaultPath();
      final Duration limit = Duration.ofSeconds(5);
      try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
        final Future<Path> running = executor.submit(() -> installer.download(FileMode.UNCHANGED));
        awaitToolRequest(server);
        final Path pathDuringDownload = assertTimeoutPreemptively(limit, installer::getPath);
        final boolean supportedDuringDownload = assertTimeoutPreemptively(limit, installer::isSupported);
        final String urlDuringDownload = assertTimeoutPreemptively(limit, installer::getUrl);
        releaseResponse.countDown();
        final Path installed = running.get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(defaultPath, pathDuringDownload);
        assertTrue(supportedDuringDownload);
        assertEquals(url, urlDuringDownload);
        assertEquals(defaultPath, installed);
      }
    }
  }

  @Test
  void shouldReportAProgramThatCannotBeMarkedExecutableAsAnInstallationFailure() throws IOException {
    final IOException permissionDenied = new IOException("Operation not permitted!");
    final FileOperationException chmodFailure = new FileOperationException("Operation not permitted!", permissionDenied);
    try (
      final LocalHttpServer server = LocalHttpServer.start();
      final MockedStatic<IoUtils> ioUtils = Mockito.mockStatic(IoUtils.class, Mockito.CALLS_REAL_METHODS)
    ) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      ioUtils.when(() -> IoUtils.markExecutable(ArgumentMatchers.any(Path.class))).thenThrow(chmodFailure);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final InstallationException thrown = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));

      final Throwable cause = thrown.getCause();
      final String message = thrown.getMessage();
      final Properties configuration = this.readConfiguration();
      final boolean namesTheProblem = message.contains("executable");
      final boolean nothingRemembered = configuration.isEmpty();
      assertSame(chmodFailure, cause);
      assertTrue(namesTheProblem, message);
      assertTrue(nothingRemembered, "a failed installation is not remembered");
    }
  }

  @Test
  void shouldPreserveThePermissionFailureWhenRemovingTheFailedDownloadAlsoFails() throws IOException {
    final FileOperationException permissionFailure = new FileOperationException("Permission was denied!");
    try (
      final LocalHttpServer server = LocalHttpServer.start();
      final MockedStatic<IoUtils> ioUtils = Mockito.mockStatic(IoUtils.class, Mockito.CALLS_REAL_METHODS)
    ) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final Path destination = installer.getDefaultPath();
      ioUtils
        .when(() -> IoUtils.markExecutable(destination))
        .thenAnswer(_ -> this.replaceWithANonemptyDirectoryAndFail(destination, permissionFailure));
      final InstallationException failure = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
      final Throwable cause = failure.getCause();
      assertSame(permissionFailure, cause);
      final String message = failure.getMessage();
      final String reason = permissionFailure.getMessage();
      final String expected = "Failed with \"%s\" while marking %s as executable!".formatted(reason, destination);
      assertEquals(expected, message);
      final Throwable[] suppressed = failure.getSuppressed();
      assertEquals(1, suppressed.length, "cleanup must not hide either failure");
      final Class<?> suppressedType = suppressed[0].getClass();
      assertEquals(DirectoryNotEmptyException.class, suppressedType);
      final String suppressedFile = ((DirectoryNotEmptyException) suppressed[0]).getFile();
      final String expectedSuppressedFile = destination.toString();
      assertEquals(expectedSuppressedFile, suppressedFile);
      final Properties configuration = this.readConfiguration();
      final Properties expectedConfiguration = new Properties();
      assertEquals(expectedConfiguration, configuration, "a failed installation is not remembered");
      final String retained = Files.readString(destination.resolve(BUSY_FILE_NAME));
      assertEquals("retained", retained);
    }
  }

  private Object replaceWithANonemptyDirectoryAndFail(final Path destination, final FileOperationException permissionFailure)
    throws IOException {
    // A nonempty replacement makes cleanup fail on every platform without relying on OS permissions.
    Files.delete(destination);
    Files.createDirectory(destination);
    Files.writeString(destination.resolve(BUSY_FILE_NAME), "retained");
    throw permissionFailure;
  }

  @ParameterizedTest
  @EnumSource(RetryInstaller.class)
  void shouldRetryExecutablePreparationAfterAPermissionFailure(final RetryInstaller retryInstaller) throws IOException {
    final boolean freshInstaller = retryInstaller == RetryInstaller.FRESH;
    try (
      final FileSystem fileSystem = Jimfs.newFileSystem(posixConfiguration());
      final LocalHttpServer server = LocalHttpServer.start();
      final MockedStatic<IoUtils> ioUtils = Mockito.mockStatic(IoUtils.class, Mockito.CALLS_REAL_METHODS)
    ) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final Download download = new Download(CURRENT_PLATFORM, server.uri(TOOL_PATH).toString(), null);
      final Path tools = fileSystem.getPath(POSIX_FOLDER_PATH);
      final TestInstaller first = new TestInstaller(tools, NAME, new Download[] { download });
      final AtomicInteger permissionAttempts = new AtomicInteger();
      ioUtils
        .when(() -> IoUtils.markExecutable(ArgumentMatchers.any(Path.class)))
        .thenAnswer(invocation -> this.failTheFirstPermissionAttempt(invocation, permissionAttempts));
      assertThrows(InstallationException.class, () -> first.download(FileMode.EXECUTABLE));
      final TestInstaller retry = freshInstaller ? new TestInstaller(tools, NAME, new Download[] { download }) : first;
      final Path installed = retry.download(FileMode.EXECUTABLE);
      final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(installed);
      final Set<PosixFilePermission> expected = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
      assertEquals(expected, permissions);
      final int attempts = permissionAttempts.get();
      assertEquals(2, attempts, "a failed preparation is retried even by a new installer");
      final byte[] content = Files.readAllBytes(installed);
      assertArrayEquals(PROGRAM, content);
    }
  }

  private @Nullable Object failTheFirstPermissionAttempt(final InvocationOnMock invocation, final AtomicInteger permissionAttempts)
    throws Throwable {
    if (permissionAttempts.getAndIncrement() == 0) {
      final String message = "Permission failed for now!";
      throw new FileOperationException(message);
    }

    return invocation.callRealMethod();
  }

  @Test
  void shouldMarkTheDownloadedProgramExecutableWithoutGivingOthersWriteAccess() throws IOException {
    final Configuration posix = posixConfiguration();
    try (final FileSystem fileSystem = Jimfs.newFileSystem(posix); final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, OK_STATUS, PROGRAM);
      final URI uri = server.uri(TOOL_PATH);
      final String url = uri.toString();
      final Download download = new Download(CURRENT_PLATFORM, url, null);
      final Path posixFolder = fileSystem.getPath(POSIX_FOLDER_PATH);
      final TestInstaller installer = new TestInstaller(posixFolder, NAME, new Download[] { download });
      final Path installed = installer.download(FileMode.EXECUTABLE);

      final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(installed);
      final Set<PosixFilePermission> expected = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
      assertEquals(expected, permissions);
    }
  }

  @Test
  void shouldRejectANullFileToMarkExecutable() {
    assertThrows(NullPointerException.class, () -> AbstractInstaller.markExecutable(null));
  }

  @Test
  void shouldReportAFolderThatCannotBeCreated() throws IOException {
    final Path blocker = this.createFileInFolder(BLOCKER_FILE_NAME);
    final Path tools = blocker.resolve(TOOLS_FOLDER_NAME);
    final Download download = new Download(CURRENT_PLATFORM, "https://example.com/tool", PROGRAM_HASH);
    final TestInstaller installer = new TestInstaller(tools, NAME, new Download[] { download });
    final InstallationException downloading = assertThrows(InstallationException.class, () -> installer.download(FileMode.EXECUTABLE));
    final InstallationException remembering = assertThrows(InstallationException.class, () -> installer.writePathToConfiguration(tools));
    final Throwable downloadingCause = downloading.getCause();
    final Throwable rememberingCause = remembering.getCause();
    assertInstanceOf(FileSystemException.class, downloadingCause);
    assertInstanceOf(FileSystemException.class, rememberingCause);
  }

  @Test
  void shouldReportAConfigurationThatCannotBeRead() throws IOException {
    final AccessDeniedException denied = new AccessDeniedException(CONFIGURATION_FILE_NAME);
    final Path unreadableFolder = this.folderWithAnUnreadableConfiguration(denied);
    final TestInstaller installer = new TestInstaller(unreadableFolder, NAME, new Download[0]);
    final InstallationException thrown = assertThrows(InstallationException.class, () -> installer.writePathToConfiguration(this.folder));
    final Throwable cause = thrown.getCause();
    assertSame(denied, cause);
  }

  // Permissions cannot deny a read to root, and Windows has none, so a fake file system denies it on every host.
  private Path folderWithAnUnreadableConfiguration(final IOException failure) throws IOException {
    final BasicFileAttributes attributes = Mockito.mock(BasicFileAttributes.class);
    Mockito.when(attributes.isRegularFile()).thenReturn(true);
    final FileSystemProvider provider = Mockito.mock(FileSystemProvider.class);
    Mockito.when(
      provider.readAttributesIfExists(
        ArgumentMatchers.any(),
        ArgumentMatchers.eq(BasicFileAttributes.class),
        ArgumentMatchers.any(LinkOption[].class)
      )
    ).thenReturn(attributes);
    Mockito.when(provider.newInputStream(ArgumentMatchers.any(), ArgumentMatchers.any(OpenOption[].class))).thenThrow(failure);
    final FileSystem fileSystem = Mockito.mock(FileSystem.class);
    Mockito.when(fileSystem.provider()).thenReturn(provider);
    final Path configuration = Mockito.mock(Path.class);
    Mockito.when(configuration.getFileSystem()).thenReturn(fileSystem);
    final Path unreadableFolder = Mockito.mock(Path.class);
    Mockito.when(unreadableFolder.resolve(CONFIGURATION_FILE_NAME)).thenReturn(configuration);
    return unreadableFolder;
  }

  @Test
  void shouldFindAnEarlierInstallationWithoutDownloadingAnything() throws IOException {
    final Supplier<Download[]> noDownloads = this::refuseToResolveDownloads;
    final TestInstaller installer = new TestInstaller(this.folder, NAME, noDownloads);
    final Optional<Path> before = installer.findInstallation();
    final Path elsewhere = this.createFileInFolder(ELSEWHERE_FILE_NAME);
    installer.writePathToConfiguration(elsewhere);

    final Optional<Path> after = installer.findInstallation();
    final Path expected = elsewhere.toAbsolutePath();
    final Optional<Path> expectedInstallation = Optional.of(expected);
    final boolean nothingBefore = before.isEmpty();
    assertTrue(nothingBefore);
    assertEquals(expectedInstallation, after);
  }

  private Download[] refuseToResolveDownloads() {
    final String message = "The downloads must not be resolved!";
    throw new AssertionError(message);
  }

  @Test
  void shouldNeverOfferADownloadForAnUnknownPlatform() {
    final Platform unknownOs = Platform.ofPlatform(Os.OTHER, Arch.X86, Bits.BITS_64);
    final Platform unknownArch = Platform.ofPlatform(Os.LINUX, Arch.OTHER, Bits.BITS_64);
    final Download forUnknownOs = new Download(unknownOs, "https://example.com/unknown-os", null);
    final Download forUnknownArch = new Download(unknownArch, "https://example.com/unknown-arch", null);
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { forUnknownOs, forUnknownArch });

    final Optional<Download> osResult = installer.findDownload(unknownOs);
    final Optional<Download> archResult = installer.findDownload(unknownArch);
    final boolean noOsDownload = osResult.isEmpty();
    final boolean noArchDownload = archResult.isEmpty();
    assertTrue(noOsDownload);
    assertTrue(noArchDownload);
  }

  @Test
  void shouldKeepTheEntriesOfOtherProgramsAndLeaveNoTemporaryFiles() throws IOException {
    final Path first = this.createFileInFolder(FIRST_FILE_NAME);
    final Path second = this.createFileInFolder(SECOND_FILE_NAME);
    final TestInstaller firstInstaller = new TestInstaller(this.folder, "first-program", new Download[0]);
    final TestInstaller secondInstaller = new TestInstaller(this.folder, "second-program", new Download[0]);
    firstInstaller.writePathToConfiguration(first);
    secondInstaller.writePathToConfiguration(second);

    final Properties configuration = this.readConfiguration();
    final Path firstAbsolute = first.toAbsolutePath();
    final Path secondAbsolute = second.toAbsolutePath();
    final String expectedFirst = firstAbsolute.toString();
    final String expectedSecond = secondAbsolute.toString();
    final String firstEntry = configuration.getProperty("first-program");
    final String secondEntry = configuration.getProperty("second-program");
    assertEquals(expectedFirst, firstEntry);
    assertEquals(expectedSecond, secondEntry);

    this.assertNoTemporaryFiles();
    assertThrows(NullPointerException.class, () -> firstInstaller.writePathToConfiguration(null));
  }

  @Test
  void shouldStoreArchivesUnderTheFileNameOfTheirUrl() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(ARCHIVE_PATH, OK_STATUS, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, ARCHIVE_PATH, null);
      installer.markAsArchive();
      final Path installed = installer.download(FileMode.UNCHANGED);

      final Path expectedArchive = this.folder.resolve(ARCHIVE_FILE_NAME);
      final List<Path> expectedArchives = List.of(expectedArchive);
      final List<Path> installedArchives = installer.getInstalledArchives();
      final Path expectedInstallation = this.folder.resolve(EXTRACTED_FOLDER_NAME);
      assertEquals(expectedArchives, installedArchives);
      assertEquals(expectedInstallation, installed);
    }
  }

  @Test
  void shouldNameArchivesAfterTheProgramWhenTheUrlHasNoUsableFileName() {
    final List<String> urls = List.of("https://example.com/", "https://example.com/files/..", "https://example.com/files/.");
    for (final String url : urls) {
      final Download download = new Download(CURRENT_PLATFORM, url, null);
      final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { download });
      installer.markAsArchive();
      final String fileName = installer.getFileName();
      assertEquals(FALLBACK_ARCHIVE_NAME, fileName, url);
    }
  }

  @Test
  void shouldTreatDownloadsAsProgramsUnlessAnInstallerSaysOtherwise() {
    final PlainInstaller installer = new PlainInstaller(this.folder);
    final boolean isArchive = installer.isArchive();
    final String fileName = installer.getFileName();
    final Os operatingSystem = OsUtils.getOs();
    final String expected = AbstractInstaller.getExecutableFileName(NAME, operatingSystem);
    assertFalse(isArchive);
    assertEquals(expected, fileName);
  }

  @Test
  void shouldReturnTheNameOfTheProgram() {
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[0]);
    final String name = installer.getName();
    assertEquals(NAME, name);
  }

  private TestInstaller installerDownloadingFrom(final LocalHttpServer server, final String path, final String hash) {
    final URI uri = server.uri(path);
    final String url = uri.toString();
    final Download download = new Download(CURRENT_PLATFORM, url, hash);
    return new TestInstaller(this.folder, NAME, new Download[] { download });
  }

  private static List<Future<Path>> submitDownloadsTogether(final ExecutorService executor, final TestInstaller installer) {
    final CyclicBarrier startTogether = new CyclicBarrier(CONCURRENT_DOWNLOADS);
    final List<Future<Path>> results = new ArrayList<>();
    for (int index = 0; index < CONCURRENT_DOWNLOADS; index++) {
      final Future<Path> result = executor.submit(() -> awaitAndDownload(startTogether, installer));
      results.add(result);
    }

    return results;
  }

  private static Path awaitAndDownload(final CyclicBarrier startTogether, final TestInstaller installer) throws Exception {
    startTogether.await();
    return installer.download(FileMode.UNCHANGED);
  }

  private static void assertEveryResultIs(final Path expected, final List<Future<Path>> results) throws Exception {
    for (final Future<Path> result : results) {
      final Path path = result.get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(expected, path);
    }
  }

  private static void awaitToolRequest(final LocalHttpServer server) throws InterruptedException {
    final boolean requested = server.awaitRequests(TOOL_PATH, 1, REQUEST_TIMEOUT);
    assertTrue(requested, "the server did not receive the request in time");
  }

  private void assertNoTemporaryFiles() throws IOException {
    try (final Stream<Path> files = Files.list(this.folder)) {
      final Stream<Path> temporaryFiles = files.filter(AbstractInstallerTest::isTemporaryFile);
      final List<Path> leftovers = temporaryFiles.toList();
      final boolean noLeftovers = leftovers.isEmpty();
      assertTrue(noLeftovers, leftovers::toString);
    }
  }

  private static boolean isTemporaryFile(final Path file) {
    final String rawFile = file.toString();
    return rawFile.endsWith(TEMPORARY_FILE_SUFFIX);
  }

  private static Configuration posixConfiguration() {
    final Configuration unix = Configuration.unix();
    final Configuration.Builder builder = unix.toBuilder();
    builder.setAttributeViews("basic", "owner", "posix", "unix");
    return builder.build();
  }

  private Path createFileInFolder(final String name) throws IOException {
    final Path file = this.folder.resolve(name);
    return Files.createFile(file);
  }

  private Properties readConfiguration() throws IOException {
    final Properties properties = new Properties();
    final Path configurationFile = this.folder.resolve(CONFIGURATION_FILE_NAME);
    final boolean exists = Files.exists(configurationFile);
    if (!exists) {
      return properties;
    }

    try (final InputStream configurationInput = Files.newInputStream(configurationFile)) {
      properties.load(configurationInput);
    }

    return properties;
  }

  private void writeConfiguration(final Properties properties) throws IOException {
    final Path configurationFile = this.folder.resolve(CONFIGURATION_FILE_NAME);
    try (final OutputStream configurationOutput = Files.newOutputStream(configurationFile)) {
      properties.store(configurationOutput, null);
    }
  }

  private static String sha256(final byte[] content) {
    try {
      final MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
      final byte[] hash = digest.digest(content);
      final HexFormat hexadecimal = HexFormat.of();
      return hexadecimal.formatHex(hash);
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  // An installer that overrides nothing, so it shows the defaults of AbstractInstaller.
  private static final class PlainInstaller extends AbstractInstaller {

    PlainInstaller(final Path folder) {
      super(folder, NAME, new Download[0]);
    }
  }

  private static final class TestInstaller extends AbstractInstaller {

    private final List<Path> installedArchives = new ArrayList<>();
    private List<Platform> fallbacks;
    private InstallationException installationFailure;
    private boolean isArchive;

    TestInstaller(final Path folder, final String name, final Supplier<Download[]> downloads) {
      super(folder, name, downloads);
    }

    TestInstaller(final Path folder, final String name, final Download[] downloads) {
      super(folder, name, downloads);
    }

    TestInstaller(final String name, final Download[] downloads) {
      super(name, downloads);
    }

    private void failInstallationWith(final InstallationException failure) {
      this.installationFailure = failure;
    }

    private void markAsArchive() {
      this.isArchive = true;
    }

    @Override
    protected List<Platform> getFallbackPlatforms(final Platform platform) {
      if (this.fallbacks == null) {
        return super.getFallbackPlatforms(platform);
      }

      return this.fallbacks;
    }

    @Override
    protected Path install(final Path downloaded) {
      if (this.installationFailure != null) {
        throw this.installationFailure;
      }

      if (!this.isArchive) {
        return super.install(downloaded);
      }

      this.installedArchives.add(downloaded);
      final Path installerFolder = this.getFolder();
      final Path extracted = installerFolder.resolve(EXTRACTED_FOLDER_NAME);
      IoUtils.createDirectoryIfNotExists(extracted);
      return extracted;
    }

    private void setFallbacks(final List<Platform> fallbacks) {
      this.fallbacks = fallbacks;
    }

    private List<Path> getInstalledArchives() {
      return this.installedArchives;
    }

    @Override
    public boolean isArchive() {
      return this.isArchive;
    }
  }

  private enum RetryInstaller {
    SAME,
    FRESH,
  }
}
