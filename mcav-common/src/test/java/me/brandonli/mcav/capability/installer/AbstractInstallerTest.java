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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link AbstractInstaller} with a local HTTP server standing in for the download hosts.
 */
final class AbstractInstallerTest {

  private static final String NAME = "tool";
  private static final String TOOL_PATH = "/tool";
  private static final byte[] PROGRAM = "#!/bin/sh\necho tool\n".getBytes(StandardCharsets.US_ASCII);
  private static final String PROGRAM_HASH = sha256(PROGRAM);
  private static final Platform CURRENT_PLATFORM = Platform.getCurrentPlatform();
  private static final Platform OTHER_PLATFORM = Platform.ofPlatform(OS.FREEBSD, Arch.ARM, Bits.BITS_32);
  private static final Platform LINUX_X86_64 = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64);
  private static final int CONCURRENT_DOWNLOADS = 4;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  @TempDir
  private Path folder;

  @Test
  void namesExecutablesWithTheSuffixOfTheOperatingSystem() {
    final String windows = AbstractInstaller.getExecutableFileName(NAME, OS.WINDOWS);
    final String linux = AbstractInstaller.getExecutableFileName(NAME, OS.LINUX);
    final String mac = AbstractInstaller.getExecutableFileName(NAME, OS.MAC);
    final String freeBsd = AbstractInstaller.getExecutableFileName(NAME, OS.FREEBSD);
    assertEquals("tool.exe", windows);
    assertEquals(NAME, linux);
    assertEquals(NAME, mac);
    assertEquals(NAME, freeBsd);
  }

  @Test
  void rejectsInvalidConstructorArguments() {
    final Supplier<Download[]> noDownloads = () -> new Download[0];
    final Download[] downloadArray = new Download[0];
    assertThrows(NullPointerException.class, () -> new TestInstaller(null, NAME, noDownloads));
    assertThrows(NullPointerException.class, () -> new TestInstaller(this.folder, null, noDownloads));
    assertThrows(NullPointerException.class, () -> new TestInstaller(this.folder, NAME, (Supplier<Download[]>) null));
    assertThrows(NullPointerException.class, () -> new TestInstaller(this.folder, NAME, (Download[]) null));
    assertThrows(IllegalArgumentException.class, () -> new TestInstaller(this.folder, " ", downloadArray));
  }

  @Test
  void installsIntoTheCacheFolderByDefault(@TempDir final Path home) {
    try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(home)) {
      final Download[] downloads = new Download[0];
      final TestInstaller installer = new TestInstaller(NAME, downloads);
      final Path installerFolder = installer.getFolder();
      final Path defaultFolder = AbstractInstaller.getDefaultExecutableFolderPath();
      final Path cachedFolder = IOUtils.getCachedFolder();
      final Path redirectedHome = redirected.getHome();
      final Path mcavFolder = redirectedHome.resolve(".mcav");
      final Path expected = mcavFolder.resolve("cache");
      final boolean created = Files.isDirectory(expected);
      assertEquals(expected, installerFolder);
      assertEquals(defaultFolder, installerFolder);
      assertEquals(cachedFolder, defaultFolder);
      assertTrue(created);
    }
  }

  @Test
  void resolvesTheDownloadsLazilyAndOnlyOnce() {
    final AtomicInteger calls = new AtomicInteger();
    final Download current = new Download(CURRENT_PLATFORM, "https://example.com/tool", "ab12cd");
    final TestInstaller installer = new TestInstaller(this.folder, NAME, () -> {
      calls.incrementAndGet();
      return new Download[] { current };
    });
    final int callsAfterConstruction = calls.get();
    final boolean supported = installer.isSupported();
    final String url = installer.getUrl();
    final String hash = installer.getHash();
    final int callsAfterUse = calls.get();
    assertEquals(0, callsAfterConstruction);
    assertTrue(supported);
    assertEquals("https://example.com/tool", url);
    assertEquals("ab12cd", hash);
    assertEquals(1, callsAfterUse);
  }

  @Test
  void prefersTheExactPlatformOverFallbacks() {
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
  void fallsBackToCompatiblePlatformsInOrder() {
    final Platform second = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_32);
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
  void findsNothingWithoutAMatchingOrCompatiblePlatform() {
    final Platform unrelated = Platform.ofPlatform(OS.MAC, Arch.ARM, Bits.BITS_64);
    final Download download = new Download(unrelated, "https://example.com/mac", null);
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { download });
    final List<Platform> fallbacks = List.of(LINUX_X86_64);
    installer.setFallbacks(fallbacks);

    final Optional<Download> found = installer.findDownload(OTHER_PLATFORM);
    final boolean nothingFound = found.isEmpty();
    assertTrue(nothingFound);
  }

  @Test
  void offersNoFallbackPlatformsByDefault() {
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[0]);
    final List<Platform> fallbacks = installer.getFallbackPlatforms(OTHER_PLATFORM);
    final boolean noFallbacks = fallbacks.isEmpty();
    assertTrue(noFallbacks);
  }

  @Test
  void reportsUnsupportedPlatforms() {
    final Download elsewhere = new Download(OTHER_PLATFORM, "https://example.com/elsewhere", "00");
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { elsewhere });
    final boolean supported = installer.isSupported();
    final String url = installer.getUrl();
    final String hash = installer.getHash();
    assertFalse(supported);
    assertEquals("", url);
    assertNull(hash);

    final IOException thrown = assertThrows(IOException.class, () -> installer.download(true));
    final String message = thrown.getMessage();
    final boolean namesTheProgram = message.contains(NAME);
    assertTrue(namesTheProgram, message);
  }

  @Test
  void pointsAtTheExecutableBeforeTheProgramIsInstalled() {
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[0]);
    final Path path = installer.getPath();
    final OS operatingSystem = OSUtils.getOS();
    final String expectedName = AbstractInstaller.getExecutableFileName(NAME, operatingSystem);
    final Path expected = this.folder.resolve(expectedName);
    assertEquals(expected, path);
  }

  @Test
  void downloadsVerifiesAndRemembersTheProgram() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, 200, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, PROGRAM_HASH);
      final Path installed = installer.download(true);
      final Path expected = installer.getDefaultPath();
      final byte[] content = Files.readAllBytes(installed);
      final Path path = installer.getPath();

      final Properties config = this.readConfig();
      final Path absolute = installed.toAbsolutePath();
      final String expectedEntry = absolute.toString();
      final String entry = config.getProperty(NAME);
      assertEquals(expected, installed);
      assertArrayEquals(PROGRAM, content);
      assertEquals(installed, path);
      assertEquals(expectedEntry, entry);
    }
  }

  @Test
  void reusesAnExistingInstallationWithoutDownloadingAgain() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, 200, PROGRAM);
      final TestInstaller first = this.installerDownloadingFrom(server, TOOL_PATH, PROGRAM_HASH);
      final TestInstaller second = this.installerDownloadingFrom(server, TOOL_PATH, PROGRAM_HASH);
      final Path firstPath = first.download(false);
      final Path secondPath = second.download(false);
      final Path againPath = first.download(false);
      final int requests = server.getRequestCount(TOOL_PATH);
      assertEquals(firstPath, secondPath);
      assertEquals(firstPath, againPath);
      assertEquals(1, requests);
    }
  }

  @Test
  void findsAnInstallationRememberedInTheConfiguration() throws IOException {
    final Path elsewhere = this.createFileInFolder("installed-elsewhere");
    final TestInstaller writer = new TestInstaller(this.folder, NAME, new Download[0]);
    writer.writePathToConfig(elsewhere);

    final Download unreachable = new Download(CURRENT_PLATFORM, "http://127.0.0.1:1/unreachable", null);
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { unreachable });
    final Path installed = installer.download(true);
    final Path expected = elsewhere.toAbsolutePath();
    assertEquals(expected, installed);
  }

  @Test
  void downloadsAgainWhenTheRememberedInstallationIsGone() throws IOException {
    final Path gone = this.folder.resolve("gone");
    final TestInstaller writer = new TestInstaller(this.folder, NAME, new Download[0]);
    writer.writePathToConfig(gone);

    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, 200, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final Path installed = installer.download(false);
      final Path expected = installer.getDefaultPath();
      final int requests = server.getRequestCount(TOOL_PATH);
      assertEquals(expected, installed);
      assertEquals(1, requests);
    }
  }

  @Test
  void ignoresARememberedPathThatIsNotAValidPath() throws IOException {
    final Properties config = new Properties();
    config.setProperty(NAME, "bad\0path");
    this.writeConfig(config);

    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, 200, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final Path installed = installer.download(false);
      final Path expected = installer.getDefaultPath();
      assertEquals(expected, installed);
    }
  }

  @Test
  void rejectsADownloadWithTheWrongChecksumAndRemembersNothing() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, 200, PROGRAM);
      final byte[] otherContent = "something else".getBytes(StandardCharsets.US_ASCII);
      final String otherHash = sha256(otherContent);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, otherHash);
      assertThrows(IOException.class, () -> installer.download(true));

      final Path defaultPath = installer.getDefaultPath();
      final boolean exists = Files.exists(defaultPath);
      final Properties config = this.readConfig();
      final boolean nothingRemembered = config.isEmpty();
      assertFalse(exists);
      assertTrue(nothingRemembered);
    }
  }

  @Test
  void downloadsOnlyOnceWhenCalledConcurrently() throws Exception {
    final CountDownLatch releaseResponse = new CountDownLatch(1);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      // the response is held back until the download is running, so the other threads really call download() while
      // the first download is still in progress
      server.respondWhenOpened(TOOL_PATH, 200, PROGRAM, releaseResponse);
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
  void answersQuestionsWhileADownloadIsRunning() throws Exception {
    final CountDownLatch releaseResponse = new CountDownLatch(1);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respondWhenOpened(TOOL_PATH, 200, PROGRAM, releaseResponse);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final String url = installer.getUrl();
      final Path defaultPath = installer.getDefaultPath();
      final Duration limit = Duration.ofSeconds(5);
      try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
        final Future<Path> running = executor.submit(() -> installer.download(false));
        awaitToolRequest(server);
        final Path pathDuringDownload = assertTimeoutPreemptively(limit, installer::getPath);
        final boolean supportedDuringDownload = assertTimeoutPreemptively(limit, installer::isSupported);
        final String urlDuringDownload = assertTimeoutPreemptively(limit, installer::getUrl);
        releaseResponse.countDown();
        final Path installed = running.get(10, TimeUnit.SECONDS);
        assertEquals(defaultPath, pathDuringDownload);
        assertTrue(supportedDuringDownload);
        assertEquals(url, urlDuringDownload);
        assertEquals(defaultPath, installed);
      }
    }
  }

  @Test
  void reportsAProgramThatCannotBeMarkedExecutableAsAnInstallationFailure() throws IOException {
    final IOException permissionDenied = new IOException("Operation not permitted");
    final UncheckedIOException chmodFailure = new UncheckedIOException("Operation not permitted", permissionDenied);
    try (
      final LocalHttpServer server = LocalHttpServer.start();
      final MockedStatic<IOUtils> ioUtils = Mockito.mockStatic(IOUtils.class, Mockito.CALLS_REAL_METHODS)
    ) {
      server.respond(TOOL_PATH, 200, PROGRAM);
      ioUtils.when(() -> IOUtils.markExecutable(ArgumentMatchers.any(Path.class))).thenThrow(chmodFailure);
      final TestInstaller installer = this.installerDownloadingFrom(server, TOOL_PATH, null);
      final IOException thrown = assertThrows(IOException.class, () -> installer.download(true));

      final Throwable cause = thrown.getCause();
      final String message = thrown.getMessage();
      final Properties config = this.readConfig();
      final boolean namesTheProblem = message.contains("executable");
      final boolean nothingRemembered = config.isEmpty();
      assertSame(chmodFailure, cause);
      assertTrue(namesTheProblem, message);
      assertTrue(nothingRemembered, "a failed installation is not remembered");
    }
  }

  @Test
  void marksTheDownloadedProgramExecutableWithoutGivingOthersWriteAccess() throws IOException {
    final Configuration posix = posixConfiguration();
    try (final FileSystem fileSystem = Jimfs.newFileSystem(posix); final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(TOOL_PATH, 200, PROGRAM);
      final URI uri = server.uri(TOOL_PATH);
      final String url = uri.toString();
      final Download download = new Download(CURRENT_PLATFORM, url, null);
      final Path posixFolder = fileSystem.getPath("/tools");
      final TestInstaller installer = new TestInstaller(posixFolder, NAME, new Download[] { download });
      final Path installed = installer.download(true);

      final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(installed);
      final Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rwxr-xr-x");
      assertEquals(expected, permissions);
    }
  }

  @Test
  void findsAnEarlierInstallationWithoutDownloadingAnything() throws IOException {
    final Supplier<Download[]> noDownloads = () -> {
      throw new AssertionError("The downloads must not be resolved");
    };
    final TestInstaller installer = new TestInstaller(this.folder, NAME, noDownloads);
    final Optional<Path> before = installer.findInstallation();
    final Path elsewhere = this.createFileInFolder("installed-elsewhere");
    installer.writePathToConfig(elsewhere);

    final Optional<Path> after = installer.findInstallation();
    final Path path = installer.getPath();
    final Path expected = elsewhere.toAbsolutePath();
    final Optional<Path> expectedInstallation = Optional.of(expected);
    final boolean nothingBefore = before.isEmpty();
    assertTrue(nothingBefore);
    assertEquals(expectedInstallation, after);
    assertEquals(expected, path, "a found installation becomes the installation path");
  }

  @Test
  void neverOffersADownloadForAnUnknownPlatform() {
    final Platform unknownOs = Platform.ofPlatform(OS.OTHER, Arch.X86, Bits.BITS_64);
    final Platform unknownArch = Platform.ofPlatform(OS.LINUX, Arch.OTHER, Bits.BITS_64);
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
  void keepsTheEntriesOfOtherProgramsAndLeavesNoTemporaryFiles() throws IOException {
    final Path first = this.createFileInFolder("first");
    final Path second = this.createFileInFolder("second");
    final TestInstaller firstInstaller = new TestInstaller(this.folder, "first-program", new Download[0]);
    final TestInstaller secondInstaller = new TestInstaller(this.folder, "second-program", new Download[0]);
    firstInstaller.writePathToConfig(first);
    secondInstaller.writePathToConfig(second);

    final Properties config = this.readConfig();
    final Path firstAbsolute = first.toAbsolutePath();
    final Path secondAbsolute = second.toAbsolutePath();
    final String expectedFirst = firstAbsolute.toString();
    final String expectedSecond = secondAbsolute.toString();
    final String firstEntry = config.getProperty("first-program");
    final String secondEntry = config.getProperty("second-program");
    assertEquals(expectedFirst, firstEntry);
    assertEquals(expectedSecond, secondEntry);
    this.assertNoTemporaryFiles();
    assertThrows(NullPointerException.class, () -> firstInstaller.writePathToConfig(null));
  }

  @Test
  void storesArchivesUnderTheFileNameOfTheirUrl() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/files/pack-1.0.zip", 200, PROGRAM);
      final TestInstaller installer = this.installerDownloadingFrom(server, "/files/pack-1.0.zip", null);
      installer.markAsArchive();
      final Path installed = installer.download(false);

      final Path expectedArchive = this.folder.resolve("pack-1.0.zip");
      final List<Path> expectedArchives = List.of(expectedArchive);
      final List<Path> installedArchives = installer.getInstalledArchives();
      final Path expectedInstallation = this.folder.resolve("extracted");
      assertEquals(expectedArchives, installedArchives);
      assertEquals(expectedInstallation, installed);
    }
  }

  @Test
  void namesArchivesAfterTheProgramWhenTheUrlHasNoUsableFileName() {
    final List<String> urls = List.of("https://example.com/", "https://example.com/files/..", "https://example.com/files/.");
    for (final String url : urls) {
      final Download download = new Download(CURRENT_PLATFORM, url, null);
      final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[] { download });
      installer.markAsArchive();
      final String fileName = installer.getFileName();
      assertEquals("tool.archive", fileName, url);
    }
  }

  @Test
  void treatsDownloadsAsProgramsUnlessAnInstallerSaysOtherwise() {
    final PlainInstaller installer = new PlainInstaller(this.folder);
    final boolean archive = installer.isArchive();
    final String fileName = installer.getFileName();
    final OS operatingSystem = OSUtils.getOS();
    final String expected = AbstractInstaller.getExecutableFileName(NAME, operatingSystem);
    assertFalse(archive);
    assertEquals(expected, fileName);
  }

  @Test
  void storesProgramsUnderTheirExecutableName() {
    final TestInstaller installer = new TestInstaller(this.folder, NAME, new Download[0]);
    final String fileName = installer.getFileName();
    final boolean archive = installer.isArchive();
    final String name = installer.getName();
    final OS operatingSystem = OSUtils.getOS();
    final String expected = AbstractInstaller.getExecutableFileName(NAME, operatingSystem);
    assertEquals(expected, fileName);
    assertFalse(archive);
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
      final Future<Path> result = executor.submit(() -> {
        startTogether.await();
        return installer.download(false);
      });
      results.add(result);
    }
    return results;
  }

  private static void assertEveryResultIs(final Path expected, final List<Future<Path>> results) throws Exception {
    for (final Future<Path> result : results) {
      final Path path = result.get(10, TimeUnit.SECONDS);
      assertEquals(expected, path);
    }
  }

  /**
   * Waits until the tool was requested once, which the server signals as soon as the request arrives.
   */
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
    return rawFile.endsWith(".tmp");
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

  private Properties readConfig() throws IOException {
    final Properties properties = new Properties();
    final Path configFile = this.folder.resolve("config.properties");
    final boolean exists = Files.exists(configFile);
    if (!exists) {
      return properties;
    }
    try (final InputStream configInput = Files.newInputStream(configFile)) {
      properties.load(configInput);
    }
    return properties;
  }

  private void writeConfig(final Properties properties) throws IOException {
    final Path configFile = this.folder.resolve("config.properties");
    try (final OutputStream configOutput = Files.newOutputStream(configFile)) {
      properties.store(configOutput, null);
    }
  }

  private static String sha256(final byte[] content) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] hash = digest.digest(content);
      final HexFormat hex = HexFormat.of();
      return hex.formatHex(hash);
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  /**
   * An installer that overrides nothing, so it shows the defaults of {@link AbstractInstaller}.
   */
  private static final class PlainInstaller extends AbstractInstaller {

    PlainInstaller(final Path folder) {
      super(folder, NAME, new Download[0]);
    }
  }

  /**
   * An installer whose archive handling and fallback platforms are configurable.
   */
  private static final class TestInstaller extends AbstractInstaller {

    private final List<Path> installedArchives = new ArrayList<>();
    private List<Platform> fallbacks;
    private boolean archive;

    TestInstaller(final Path folder, final String name, final Supplier<Download[]> downloads) {
      super(folder, name, downloads);
    }

    TestInstaller(final Path folder, final String name, final Download[] downloads) {
      super(folder, name, downloads);
    }

    TestInstaller(final String name, final Download[] downloads) {
      super(name, downloads);
    }

    void setFallbacks(final List<Platform> fallbacks) {
      this.fallbacks = fallbacks;
    }

    void markAsArchive() {
      this.archive = true;
    }

    List<Path> getInstalledArchives() {
      return this.installedArchives;
    }

    @Override
    public boolean isArchive() {
      return this.archive;
    }

    @Override
    protected List<Platform> getFallbackPlatforms(final Platform platform) {
      if (this.fallbacks == null) {
        return super.getFallbackPlatforms(platform);
      }
      return this.fallbacks;
    }

    @Override
    protected Path install(final Path downloaded) throws IOException {
      if (!this.archive) {
        return super.install(downloaded);
      }
      this.installedArchives.add(downloaded);
      final Path installerFolder = this.getFolder();
      final Path extracted = installerFolder.resolve("extracted");
      Files.createDirectories(extracted);
      return extracted;
    }
  }
}
