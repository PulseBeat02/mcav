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
package me.brandonli.mcav.loader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller;
import me.brandonli.mcav.capability.installer.debian.DebianLibraryInstallers;
import me.brandonli.mcav.capability.installer.vlc.UnsupportedOperatingSystemException;
import me.brandonli.mcav.capability.installer.vlc.VlcInstallationKit;
import me.brandonli.mcav.capability.installer.vlc.VlcInstallerFailures;
import me.brandonli.mcav.capability.installer.ytdlp.YtdlpInstaller;
import me.brandonli.mcav.testing.TemporaryUserHome;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.natives.NativeLoadingException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.bytedeco.ffmpeg.avutil.Callback_Pointer_int_String_Pointer;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.presets.opencv_objdetect;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.Logger;

final class DependencyLoaderTest {

  private static final String OPENBLAS_LOAD = "org.bytedeco.openblas.load";
  private static final String PATHS_FIRST = "org.bytedeco.javacpp.pathsFirst";
  private static final int CONCURRENT_ROUNDS = 500;
  private static final long MAXIMUM_PLAUSIBLE_MILLISECONDS = 60_000L;
  private static final long TIMEOUT_SECONDS = 10L;

  private static final String DEFAULT_PATHS_FIRST = "false";

  private static final String CACHE = "org.bytedeco.javacpp.cachedir";
  private static final String CACHE_ALIAS = "org.bytedeco.javacpp.cacheDir";
  private static final String TEMPORARY_DIRECTORY = "java.io.tmpdir";

  private static final String HOME_NAME = "home";
  private static final String HOME_CACHE_PATH = ".javacpp/cache";
  private static final String FALLBACK_CACHE_PATH = "mcav/cache/javacpp";
  private static final String CUSTOM_CACHE = "custom-cache";

  private static final Pattern NATIVES_LOADED = Pattern.compile("JavaCV natives loaded in (\\d+) ms");

  private static final String OBJECT_DETECTION_MODULE = "objdetect";
  private static final String FACE_DETECTION_MODULE_NAME = "Face detection";
  private static final List<String> OBJECT_DETECTION_LOADED = List.of(OBJECT_DETECTION_MODULE);

  private static final Platform LINUX_X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);
  private static final Platform LINUX_ARM_64 = Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64);

  private static final String LOAD_ORDER_FILE = "load-order.txt";
  private static final String GTK2_LIBRARY_PATH = "usr/lib/x86_64-linux-gnu/libgtk-x11-2.0.so.0.2400.33";
  private static final String UNHASHED_PACKAGE_URL = "https://deb.example/pool/libgtk2.0-0.deb";

  private static final String YTDLP_FILE_NAME = "yt-dlp";
  private static final String PREPARED_FILE_NAME = "prepared";

  @Test
  void shouldStartWithEveryCapability() {
    final DependencyLoader loader = new DependencyLoader();
    for (final Capability capability : Capability.values()) {
      final boolean available = loader.hasCapability(capability);
      assertTrue(available, capability::name);
    }

    assertThrows(NullPointerException.class, () -> loader.hasCapability(null));
  }

  @Test
  void shouldLoadTheBundledNativesAheadOfTheSystemLibraries() {
    final DependencyLoader loader = new DependencyLoader();
    final String previousPathsFirst = System.getProperty(PATHS_FIRST);
    System.clearProperty(PATHS_FIRST);
    final String pathsFirst;
    try {
      assertDoesNotThrow(() -> loader.loadModules());

      pathsFirst = System.getProperty(PATHS_FIRST);
    } finally {
      restoreProperty(PATHS_FIRST, previousPathsFirst);
    }

    final String openBlas = System.getProperty(OPENBLAS_LOAD);
    final boolean hasFfmpeg = loader.hasCapability(Capability.FFMPEG);
    assertEquals(DEFAULT_PATHS_FIRST, pathsFirst);
    assertNull(openBlas, "OpenCV links OpenBLAS, so the library must not disable it");
    assertTrue(hasFfmpeg);
  }

  private static void restoreProperty(final String key, final @Nullable String value) {
    if (value == null) {
      return;
    }

    System.setProperty(key, value);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldSendFfmpegErrorsToTheJavaLogOnceTheNativesAreLoaded() {
    final DependencyLoader loader = new DependencyLoader();
    loader.loadModules();
    avutil.av_log_set_level(avutil.AV_LOG_INFO);
    avutil.av_log_set_callback((Callback_Pointer_int_String_Pointer) null);
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      loader.loadModules();
      avutil.av_log(null, avutil.AV_LOG_ERROR, "mcav ffmpeg log marker\n");
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);

    final int level = avutil.av_log_get_level();
    final boolean forwarded = log.contains("mcav ffmpeg log marker");
    assertEquals(avutil.AV_LOG_ERROR, level);
    assertTrue(forwarded, log);
  }

  @Test
  void shouldLoadFaceDetectionAgainWhenTheNativesAreLoaded() {
    final DependencyLoader loader = new DependencyLoader();
    final boolean objectDetectionLinks = DependencyLoader.loadOptionalModule(() -> Loader.load(opencv_objdetect.class), "face detection");
    assumeTrue(objectDetectionLinks, "The object detection natives must link on this system!");

    loader.loadFaceDetection(() -> this.throwError(new UnsatisfiedLinkError("no GTK!")));

    loader.loadModules();

    final boolean hasFaceDetection = loader.hasCapability(Capability.FACE_DETECTION);
    assertTrue(hasFaceDetection);
  }

  private void throwError(final Error error) {
    throw error;
  }

  @Test
  void shouldRunOpenCvOnTheCallingThreadUnlessToldOtherwise() {
    final DependencyLoader loader = new DependencyLoader();
    assertDoesNotThrow(() -> loader.loadModules());
    // OpenCV's own pool would outlive every player, a thread per processor but one, named after the first caller
    final int threads = opencv_core.getNumThreads();
    assertEquals(1, threads);

    opencv_core.setNumThreads(5);
    assertDoesNotThrow(() -> loader.loadModules());

    final int threadsAfterReload = opencv_core.getNumThreads();
    assertEquals(1, threadsAfterReload, "loading the natives sets OpenCV's threads");

    final String previous = System.getProperty(DependencyLoader.OPENCV_THREADS_PROPERTY);
    try {
      System.setProperty(DependencyLoader.OPENCV_THREADS_PROPERTY, "3");
      DependencyLoader.configureOpenCvThreads();
      final int configuredThreads = opencv_core.getNumThreads();
      assertEquals(3, configuredThreads);
    } finally {
      if (previous == null) {
        System.clearProperty(DependencyLoader.OPENCV_THREADS_PROPERTY);
      } else {
        System.setProperty(DependencyLoader.OPENCV_THREADS_PROPERTY, previous);
      }

      DependencyLoader.configureOpenCvThreads();
    }
  }

  @Test
  void shouldConfigureJavaCppOnlyWhereTheApplicationHasNot() {
    final String previousPathsFirst = System.getProperty(PATHS_FIRST);
    try {
      System.clearProperty(PATHS_FIRST);
      DependencyLoader.configureJavaCpp();
      final String defaultPathsFirst = System.getProperty(PATHS_FIRST);
      System.setProperty(PATHS_FIRST, "true");
      DependencyLoader.configureJavaCpp();
      final String customPathsFirst = System.getProperty(PATHS_FIRST);
      assertEquals(DEFAULT_PATHS_FIRST, defaultPathsFirst);
      assertEquals("true", customPathsFirst, "a value set by the application is kept");
    } finally {
      resetProperty(PATHS_FIRST, previousPathsFirst);
    }
  }

  @Test
  void shouldKeepTheJavaCppCacheInAHomeThatCanRunPrograms(@TempDir final Path directory) {
    final Path home = directory.resolve(HOME_NAME);

    final @Nullable String cache = this.configureJavaCppIn(home, directory, Map.of());
    assertNull(cache, "JavaCPP picks the cache in the home itself");
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldMoveTheJavaCppCacheWhenTheHomeCannotHoldIt(@TempDir final Path directory) throws IOException {
    final Path home = directory.resolve(HOME_NAME);
    Files.writeString(home, HOME_NAME);
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    final @Nullable String cache;
    try {
      cache = this.configureJavaCppIn(home, directory, Map.of());
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);
    final Path homeCache = home.resolve(HOME_CACHE_PATH);
    final Path fallback = directory.resolve(FALLBACK_CACHE_PATH);
    final String expected = fallback.toString();
    final String warning = "JavaCPP cache folder %s cannot be written or cannot run programs, using %s instead.".formatted(
      homeCache,
      fallback
    );
    final boolean warned = log.contains(warning);
    assertEquals(expected, cache);
    assertTrue(warned, log);
  }

  @Test
  void shouldKeepTheJavaCppCacheTheApplicationChose(@TempDir final Path directory) throws IOException {
    final Path home = directory.resolve(HOME_NAME);
    Files.writeString(home, HOME_NAME);
    final Map<String, String> custom = Map.of(CACHE, CUSTOM_CACHE);
    final Map<String, String> customAlias = Map.of(CACHE_ALIAS, CUSTOM_CACHE);

    final @Nullable String cache = this.configureJavaCppIn(home, directory, custom);
    final @Nullable String aliasCache = this.configureJavaCppIn(home, directory, customAlias);
    assertEquals(CUSTOM_CACHE, cache);
    assertNull(aliasCache, "JavaCPP reads the other spelling of the property as well");
  }

  // configures JavaCPP with the user home and temporary directory moved and the given properties set, and returns the
  // cache folder it was told to use
  private @Nullable String configureJavaCppIn(final Path home, final Path temporary, final Map<String, String> properties) {
    final @Nullable String previousCache = System.getProperty(CACHE);
    final @Nullable String previousAlias = System.getProperty(CACHE_ALIAS);
    final @Nullable String previousPathsFirst = System.getProperty(PATHS_FIRST);
    final @Nullable String previousTemporary = System.getProperty(TEMPORARY_DIRECTORY);
    System.clearProperty(CACHE);
    System.clearProperty(CACHE_ALIAS);
    properties.forEach(System::setProperty);
    final String rawTemporary = temporary.toString();
    System.setProperty(TEMPORARY_DIRECTORY, rawTemporary);
    try (final TemporaryUserHome _ = TemporaryUserHome.redirectTo(home)) {
      DependencyLoader.configureJavaCpp();
      return System.getProperty(CACHE);
    } finally {
      resetProperty(CACHE, previousCache);
      resetProperty(CACHE_ALIAS, previousAlias);
      resetProperty(PATHS_FIRST, previousPathsFirst);
      resetProperty(TEMPORARY_DIRECTORY, previousTemporary);
    }
  }

  @Test
  void shouldReportNativesThatCannotBeLinked() {
    final UnsatisfiedLinkError linkError = new UnsatisfiedLinkError("no jniopencv_core!");
    final NativeLoadingException linkFailure = assertThrows(NativeLoadingException.class, () ->
      DependencyLoader.loadModules(LINUX_X86_64, () -> this.throwError(linkError))
    );
    final NoClassDefFoundError missingClass = new NoClassDefFoundError("org/bytedeco/opencv/global/opencv_core");
    final NativeLoadingException classFailure = assertThrows(NativeLoadingException.class, () ->
      DependencyLoader.loadModules(LINUX_X86_64, () -> this.throwError(missingClass))
    );
    final Throwable linkCause = linkFailure.getCause();
    final Throwable classCause = classFailure.getCause();
    final String linkMessage = linkFailure.getMessage();
    final boolean namesTheLibrary = linkMessage.contains("no jniopencv_core");
    assertSame(linkError, linkCause);
    assertSame(missingClass, classCause);
    assertTrue(namesTheLibrary, linkMessage);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("platformsWithoutNatives")
  void shouldFailNamingThePlatformWhereJavaCvHasNoNatives(final Platform platform) {
    final AtomicBoolean loaded = new AtomicBoolean();
    final NativeLoadingException failure = assertThrows(NativeLoadingException.class, () ->
      DependencyLoader.loadModules(platform, () -> loaded.set(true))
    );
    final String message = failure.getMessage();
    final String expected = "Platform %s has no FFmpeg and OpenCV natives!".formatted(platform);
    final boolean attempted = loaded.get();
    assertEquals(expected, message);
    assertFalse(attempted, "nothing is extracted or loaded where no natives exist");
  }

  private static Stream<Platform> platformsWithoutNatives() {
    return Stream.of(
      Platform.ofPlatform(Os.FREEBSD, Arch.X86, Bits.BITS_64),
      Platform.ofPlatform(Os.FREEBSD, Arch.ARM, Bits.BITS_64),
      Platform.ofPlatform(Os.WINDOWS, Arch.ARM, Bits.BITS_64),
      Platform.ofPlatform(Os.OTHER, Arch.X86, Bits.BITS_64)
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("platformsWithNatives")
  void shouldLoadTheNativesOnEveryPlatformJavaCvSupports(final Platform platform) {
    final AtomicBoolean loaded = new AtomicBoolean();
    DependencyLoader.loadModules(platform, () -> loaded.set(true));

    final boolean attempted = loaded.get();
    assertTrue(attempted);
  }

  private static Stream<Platform> platformsWithNatives() {
    return Stream.of(
      Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64),
      Platform.ofPlatform(Os.MAC, Arch.X86, Bits.BITS_64),
      Platform.ofPlatform(Os.MAC, Arch.ARM, Bits.BITS_64),
      LINUX_X86_64,
      Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64)
    );
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldReportHowLongTheNativesTookToLoad() {
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      DependencyLoader.loadModules(LINUX_X86_64, () -> {});
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);

    final Matcher loaded = NATIVES_LOADED.matcher(log);
    final boolean reported = loaded.find();
    assertTrue(reported, log);

    final long elapsed = Long.parseLong(loaded.group(1));
    final boolean plausible = elapsed < MAXIMUM_PLAUSIBLE_MILLISECONDS;
    assertTrue(plausible, log);
  }

  @Test
  void shouldTolerateAMissingFfmpegDeviceLibraryAndStillConfigureFfmpeg() {
    final UnsatisfiedLinkError deviceError = new UnsatisfiedLinkError("no jniavdevice in java.library.path!");
    final AtomicBoolean configured = new AtomicBoolean();
    assertDoesNotThrow(() -> DependencyLoader.loadFfmpeg(() -> this.throwError(deviceError), () -> configured.set(true)));

    final boolean setupRan = configured.get();
    assertTrue(setupRan, "the log callback is set up even without the device library");
  }

  @Test
  void shouldTolerateTheMissingDeviceLibraryAgainWhenFfmpegIsLoadedASecondTime() {
    final AtomicInteger configured = new AtomicInteger();
    final Runnable loader = FailingDeviceLibrary::touch;
    assertDoesNotThrow(() -> DependencyLoader.loadFfmpeg(loader, configured::incrementAndGet));

    final NoClassDefFoundError again = assertThrows(NoClassDefFoundError.class, loader::run);
    assertDoesNotThrow(() -> DependencyLoader.loadFfmpeg(loader, configured::incrementAndGet));

    final int configuredCount = configured.get();
    assertEquals(2, configuredCount);

    final String stackTrace = Throwables.getStackTraceAsString(again);
    final boolean namesTheLibrary = stackTrace.contains("no jniavdevice");
    assertTrue(namesTheLibrary, again::toString);
  }

  @Test
  void shouldTolerateTheDeviceClassThatFailedBeforeWithoutItsCause() {
    final NoClassDefFoundError deviceClass = new NoClassDefFoundError("Could not initialize class org.bytedeco.ffmpeg.global.avdevice");
    final AtomicBoolean configured = new AtomicBoolean();
    assertDoesNotThrow(() -> DependencyLoader.loadFfmpeg(() -> this.throwError(deviceClass), () -> configured.set(true)));

    final boolean configuredWithoutDevice = configured.get();
    assertTrue(configuredWithoutDevice, "FFmpeg is configured without the device library");
  }

  @Test
  void shouldReportAnotherFfmpegClassThatFailedBefore() {
    final NoClassDefFoundError codecClass = new NoClassDefFoundError("Could not initialize class avcodec");
    codecClass.initCause(new ExceptionInInitializerError("Exception java.lang.UnsatisfiedLinkError: no jniavcodec!"));
    final AtomicBoolean configured = new AtomicBoolean();
    final NativeLoadingException failure = this.loadFfmpegFailingWith(codecClass, configured);
    final Throwable cause = failure.getCause();
    assertSame(codecClass, cause);

    final String message = failure.getMessage();
    final boolean namesTheClass = message.contains("Could not initialize class avcodec");
    assertTrue(namesTheClass, failure.getMessage());

    final boolean configuredAfterFailure = configured.get();
    assertFalse(configuredAfterFailure, "FFmpeg that failed to load is not configured");
  }

  @Test
  void shouldConfigureFfmpegAfterLoadingIt() {
    final List<String> steps = new ArrayList<>();
    DependencyLoader.loadFfmpeg(() -> steps.add("load"), () -> steps.add("setup"));
    final List<String> expected = List.of("load", "setup");
    assertEquals(expected, steps);
  }

  @Test
  void shouldReportEveryOtherFfmpegLinkFailure() {
    final UnsatisfiedLinkError codecError = new UnsatisfiedLinkError("no jniavcodec!");
    final AtomicBoolean configured = new AtomicBoolean();
    final NativeLoadingException codecFailure = this.loadFfmpegFailingWith(codecError, configured);

    final Throwable codecCause = codecFailure.getCause();
    final String codecMessage = codecFailure.getMessage();
    final boolean namesTheLibrary = codecMessage.contains("no jniavcodec");
    final boolean setupRan = configured.get();

    assertSame(codecError, codecCause);
    assertTrue(namesTheLibrary, codecMessage);
    assertFalse(setupRan, "FFmpeg that failed to load is not configured");
  }

  @Test
  void shouldReportFfmpegLinkFailuresWithoutAMessage() {
    final UnsatisfiedLinkError silentError = new UnsatisfiedLinkError();
    final AtomicBoolean configured = new AtomicBoolean();
    final NativeLoadingException silentFailure = this.loadFfmpegFailingWith(silentError, configured);

    final Throwable silentCause = silentFailure.getCause();
    final boolean setupRan = configured.get();

    assertSame(silentError, silentCause);
    assertFalse(setupRan, "FFmpeg that failed to load is not configured");
  }

  @Test
  void shouldKeepTheVlcCapabilityWhenVlcIsReady() {
    final boolean hasVlc = vlcAvailableAfter(() -> {});
    assertTrue(hasVlc);
  }

  @Test
  void shouldRemoveOnlyTheVlcCapabilityWhenTheDownloadFails() {
    final DependencyLoader loader = new DependencyLoader();
    loader.installVlc(() -> this.failVlcStart(new InstallationException("offline!")));
    final boolean hasVlc = loader.hasCapability(Capability.VLC);
    final boolean hasYtdlp = loader.hasCapability(Capability.YT_DLP);
    final boolean hasFfmpeg = loader.hasCapability(Capability.FFMPEG);
    assertFalse(hasVlc);
    assertTrue(hasYtdlp);
    assertTrue(hasFfmpeg);
  }

  private <FailureType extends Throwable> void failVlcStart(final FailureType failure) throws FailureType {
    throw failure;
  }

  @Test
  void shouldNotLetOlderPreparationsOverwriteTheLatestOutcome() throws Exception {
    for (final Capability capability : List.of(Capability.VLC, Capability.YT_DLP)) {
      for (final PreparationOutcome latestOutcome : List.of(PreparationOutcome.FAILS, PreparationOutcome.SUCCEEDS)) {
        this.assertTheLatestPreparationWins(capability, latestOutcome);
      }
    }
  }

  private void assertTheLatestPreparationWins(final Capability capability, final PreparationOutcome latestOutcome)
    throws InterruptedException {
    final DependencyLoader loader = new DependencyLoader();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch finish = new CountDownLatch(1);
    final Runnable olderPreparation = () -> this.awaitOlderPreparation(entered, finish, latestOutcome);
    final Thread older = new Thread(() -> this.prepareProgram(loader, capability, olderPreparation), "old-preparation");
    older.start();
    try {
      final boolean enteredInTime = entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(enteredInTime);

      older.interrupt();
      this.prepareProgram(loader, capability, () -> this.runNewerPreparation(latestOutcome));
      finish.countDown();
      final boolean olderEnded = older.join(Duration.ofSeconds(TIMEOUT_SECONDS));
      assertTrue(olderEnded);

      final boolean capable = loader.hasCapability(capability);
      final boolean expected = latestOutcome == PreparationOutcome.SUCCEEDS;
      assertEquals(expected, capable, capability + " keeps the newest preparation outcome");
    } finally {
      finish.countDown();
      final boolean ended = older.join(Duration.ofSeconds(TIMEOUT_SECONDS));
      assertTrue(ended);
    }
  }

  private void awaitOlderPreparation(final CountDownLatch entered, final CountDownLatch finish, final PreparationOutcome latestOutcome) {
    entered.countDown();
    Uninterruptibles.awaitUninterruptibly(finish);

    if (latestOutcome == PreparationOutcome.SUCCEEDS) {
      throw new IllegalStateException("old failure!");
    }
  }

  private void prepareProgram(final DependencyLoader loader, final Capability capability, final Runnable prepare) {
    if (capability == Capability.VLC) {
      loader.installVlc(prepare::run);
    } else {
      final Installer installer = mock(Installer.class);
      when(installer.isSupported()).thenReturn(true);
      when(installer.download(FileMode.EXECUTABLE)).thenAnswer(_ -> this.prepareDownload(prepare));

      loader.installYtdlp(installer);
    }
  }

  private Path prepareDownload(final Runnable prepare) {
    prepare.run();

    return Path.of(PREPARED_FILE_NAME);
  }

  private void runNewerPreparation(final PreparationOutcome latestOutcome) {
    if (latestOutcome == PreparationOutcome.FAILS) {
      throw new IllegalStateException("new failure!");
    }
  }

  @Test
  void shouldRestoreTheCapabilitiesWhenALaterPreparationSucceeds() throws IOException {
    final DependencyLoader loader = new DependencyLoader();
    loader.installVlc(() -> this.failVlcStart(new InstallationException("offline!")));
    final Installer installer = mock(Installer.class);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenThrow(new InstallationException("offline!")).thenReturn(Path.of(YTDLP_FILE_NAME));
    loader.installYtdlp(installer);
    loader.loadFaceDetection(() -> this.throwError(new UnsatisfiedLinkError("no libgtk-x11-2.0!")));
    final boolean vlcBeforeRetry = loader.hasCapability(Capability.VLC);
    assertFalse(vlcBeforeRetry);

    final boolean ytdlpBeforeRetry = loader.hasCapability(Capability.YT_DLP);
    assertFalse(ytdlpBeforeRetry);

    final boolean faceDetectionBeforeRetry = loader.hasCapability(Capability.FACE_DETECTION);
    assertFalse(faceDetectionBeforeRetry);

    loader.installVlc(() -> {});
    loader.installYtdlp(installer);
    loader.loadFaceDetection(() -> {});
    final boolean vlcAfterRetry = loader.hasCapability(Capability.VLC);
    assertTrue(vlcAfterRetry);

    final boolean ytdlpAfterRetry = loader.hasCapability(Capability.YT_DLP);
    assertTrue(ytdlpAfterRetry);

    final boolean faceDetectionAfterRetry = loader.hasCapability(Capability.FACE_DETECTION);
    assertTrue(faceDetectionAfterRetry);
  }

  @Test
  void shouldRemoveTheVlcCapabilityOnUnsupportedSystems() {
    final UnsupportedOperatingSystemException unsupported = VlcInstallerFailures.unsupportedSystem(
      "Operating system FREEBSD does not support installing VLC automatically!"
    );
    final boolean hasVlc = vlcAvailableAfter(() -> this.failVlcStart(unsupported));
    assertFalse(hasVlc);
  }

  @Test
  void shouldNotHideVirtualMachineErrorsWhilePreparingVlc() {
    final DependencyLoader loader = new DependencyLoader();
    final OutOfMemoryError outOfMemory = new OutOfMemoryError("heap is full!");
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> loader.installVlc(() -> this.failVlcStart(outOfMemory)));
    assertSame(outOfMemory, thrown, "running out of memory is no reason to run without VLC");
  }

  @Test
  void shouldRemoveTheYtdlpCapabilityOnUnexpectedRuntimeFailures() throws IOException {
    final Installer installer = mock(Installer.class);
    final IllegalStateException failure = new IllegalStateException("broken download list!");
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenThrow(failure);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYtdlp(installer);
    final boolean hasYtdlp = loader.hasCapability(Capability.YT_DLP);
    assertFalse(hasYtdlp);
  }

  @Test
  void shouldRemoveTheVlcCapabilityWhenNativesCannotBeLinked() {
    final UnsatisfiedLinkError wrongArchitecture = new UnsatisfiedLinkError("incompatible architecture (have 'x86_64', need 'arm64')!");
    final NoClassDefFoundError missingJna = new NoClassDefFoundError("Could not initialize class com.sun.jna.Native");
    final boolean withWrongArchitecture = vlcAvailableAfter(() -> this.failVlcStart(wrongArchitecture));
    final boolean withoutJna = vlcAvailableAfter(() -> this.failVlcStart(missingJna));
    assertFalse(withWrongArchitecture);
    assertFalse(withoutJna);
  }

  @Test
  void shouldRemoveTheVlcCapabilityOnUnexpectedRuntimeFailures() {
    final IllegalStateException failure = new IllegalStateException("broken plugin cache!");
    final boolean hasVlc = vlcAvailableAfter(() -> this.failVlcStart(failure));
    assertFalse(hasVlc);
  }

  @Test
  void shouldStartTheInstallationKitOfTheLibrary() throws IOException {
    final VlcInstallationKit kit = mock(VlcInstallationKit.class);
    when(kit.start()).thenReturn(Optional.empty());
    try (final MockedStatic<VlcInstallationKit> kits = mockStatic(VlcInstallationKit.class)) {
      kits.when(VlcInstallationKit::create).thenReturn(kit);
      final DependencyLoader loader = new DependencyLoader();
      loader.installVlc();
      final boolean hasVlc = loader.hasCapability(Capability.VLC);
      assertTrue(hasVlc);
    }

    verify(kit).start();
  }

  @Test
  void shouldInstallYtdlpWhenItIsSupported() throws IOException {
    final Installer installer = mock(Installer.class);
    final Path installed = Path.of(YTDLP_FILE_NAME);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenReturn(installed);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYtdlp(installer);
    final boolean hasYtdlp = loader.hasCapability(Capability.YT_DLP);
    assertTrue(hasYtdlp);
    verify(installer).download(FileMode.EXECUTABLE);
  }

  @Test
  void shouldRemoveTheYtdlpCapabilityWithoutDownloadingOnUnsupportedPlatforms() throws IOException {
    final Installer installer = mock(Installer.class);
    when(installer.isSupported()).thenReturn(false);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYtdlp(installer);
    final boolean hasYtdlp = loader.hasCapability(Capability.YT_DLP);
    final boolean hasVlc = loader.hasCapability(Capability.VLC);
    assertFalse(hasYtdlp);
    assertTrue(hasVlc);
    verify(installer, never()).download(any(FileMode.class));
  }

  @Test
  void shouldRemoveTheYtdlpCapabilityWhenTheDownloadFails() throws IOException {
    final Installer installer = mock(Installer.class);
    final InstallationException offline = new InstallationException("offline!");
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenThrow(offline);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYtdlp(installer);
    final boolean hasYtdlp = loader.hasCapability(Capability.YT_DLP);
    assertFalse(hasYtdlp);
  }

  @Test
  void shouldRemoveTheYtdlpCapabilityWhenTheDownloadCannotBeMarkedExecutable() throws IOException {
    final Installer installer = mock(Installer.class);
    final IOException permissionDenied = new IOException("Operation not permitted!");
    final FileOperationException chmodFailure = new FileOperationException("Operation not permitted!", permissionDenied);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenThrow(chmodFailure);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYtdlp(installer);
    final boolean hasYtdlp = loader.hasCapability(Capability.YT_DLP);
    final boolean hasVlc = loader.hasCapability(Capability.VLC);
    assertFalse(hasYtdlp);
    assertTrue(hasVlc);
  }

  @Test
  void shouldUseTheSharedYtdlpInstallerOfTheLibrary() throws IOException {
    final YtdlpInstaller installer = mock(YtdlpInstaller.class);
    final Path installed = Path.of(YTDLP_FILE_NAME);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenReturn(installed);
    try (final MockedStatic<YtdlpInstaller> installers = mockStatic(YtdlpInstaller.class)) {
      installers.when(YtdlpInstaller::shared).thenReturn(installer);
      final DependencyLoader loader = new DependencyLoader();
      loader.installYtdlp();
      final boolean hasYtdlp = loader.hasCapability(Capability.YT_DLP);
      assertTrue(hasYtdlp);
    }

    verify(installer).download(FileMode.EXECUTABLE);
  }

  @Test
  void shouldKeepEveryRemovalWhenVlcAndYtdlpFailAtTheSameTime() throws Exception {
    // MCAV installs VLC and yt-dlp on two threads; a removal lost by an unsafe set would report a missing program as
    // available. The race is timing dependent, so it is repeated many times.
    final Installer unsupported = mock(Installer.class);
    when(unsupported.isSupported()).thenReturn(false);
    try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
      for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
        final DependencyLoader loader = this.failBothInstallationsTogether(executor, unsupported);
        final boolean vlcAvailable = loader.hasCapability(Capability.VLC);
        final boolean ytdlpAvailable = loader.hasCapability(Capability.YT_DLP);
        assertFalse(vlcAvailable, "round " + round);
        assertFalse(ytdlpAvailable, "round " + round);
      }
    }
  }

  private DependencyLoader failBothInstallationsTogether(final ExecutorService executor, final Installer unsupported) throws Exception {
    final DependencyLoader loader = new DependencyLoader();
    final CyclicBarrier barrier = new CyclicBarrier(2);
    final Future<?> vlcInstallation = executor.submit(() -> this.failVlcInstallationAfter(barrier, loader));
    final Future<?> ytdlpInstallation = executor.submit(() -> this.installYtdlpAfter(barrier, loader, unsupported));
    vlcInstallation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    ytdlpInstallation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    return loader;
  }

  private @Nullable Void failVlcInstallationAfter(final CyclicBarrier barrier, final DependencyLoader loader)
    throws InterruptedException, BrokenBarrierException {
    barrier.await();
    loader.installVlc(() -> this.failVlcStart(new InstallationException("offline!")));

    return null;
  }

  private @Nullable Void installYtdlpAfter(final CyclicBarrier barrier, final DependencyLoader loader, final Installer installer)
    throws InterruptedException, BrokenBarrierException {
    barrier.await();
    loader.installYtdlp(installer);

    return null;
  }

  @Test
  void shouldReportFaceDetectionOnlyWhenItsNativesLoad() {
    final List<String> loaded = new ArrayList<>();
    final DependencyLoader available = new DependencyLoader();
    available.loadFaceDetection(() -> loaded.add(OBJECT_DETECTION_MODULE));
    final UnsatisfiedLinkError missingGtk = new UnsatisfiedLinkError("libgtk-x11-2.0.so.0: cannot open shared object file!");
    final DependencyLoader missing = new DependencyLoader();
    missing.loadFaceDetection(() -> this.throwError(missingGtk));
    final boolean detectionWithNatives = available.hasCapability(Capability.FACE_DETECTION);
    final boolean detectionWithoutNatives = missing.hasCapability(Capability.FACE_DETECTION);
    final boolean ffmpegWithoutDetection = missing.hasCapability(Capability.FFMPEG);
    assertTrue(detectionWithNatives);
    assertFalse(detectionWithoutNatives);
    assertTrue(ffmpegWithoutDetection, "only face detection is lost");
    assertEquals(OBJECT_DETECTION_LOADED, loaded);
  }

  @Test
  void shouldReportOptionalModulesThatLoad() {
    final List<String> loaded = new ArrayList<>();
    final boolean available = DependencyLoader.loadOptionalModule(() -> loaded.add(OBJECT_DETECTION_MODULE), FACE_DETECTION_MODULE_NAME);
    assertTrue(available);
    assertEquals(OBJECT_DETECTION_LOADED, loaded);
  }

  @Test
  void shouldKeepWorkingWhenAnOptionalModuleCannotBeLinked() {
    final UnsatisfiedLinkError missingGtk = new UnsatisfiedLinkError("libgtk-x11-2.0.so.0: cannot open shared object file!");
    final NoClassDefFoundError failedInitializer = new NoClassDefFoundError("Could not initialize class opencv_highgui");
    final boolean withoutGtk = DependencyLoader.loadOptionalModule(() -> this.throwError(missingGtk), FACE_DETECTION_MODULE_NAME);
    final boolean withoutClass = DependencyLoader.loadOptionalModule(() -> this.throwError(failedInitializer), FACE_DETECTION_MODULE_NAME);
    assertFalse(withoutGtk);
    assertFalse(withoutClass);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("platformsWithDebianGtk2")
  void shouldInstallAndLoadGtk2WhereLinuxLacksIt(final Platform platform, @TempDir final Path folder) throws IOException {
    final Path library = folder.resolve(GTK2_LIBRARY_PATH);
    final Path libraryDirectory = library.getParent();
    Files.createDirectories(libraryDirectory);
    Files.writeString(library, "gtk", StandardCharsets.UTF_8);
    Files.writeString(folder.resolve(LOAD_ORDER_FILE), GTK2_LIBRARY_PATH, StandardCharsets.UTF_8);
    final List<String> loaded = new ArrayList<>();
    final DebianLibraryInstaller installer = DebianLibraryInstallers.create(folder, new Download[0], platform, loaded::add);

    DependencyLoader.provideGtk2(platform, libraryName -> false, () -> installer);

    final List<String> expected = List.of(library.toString());
    assertEquals(expected, loaded);
  }

  private static Stream<Platform> platformsWithDebianGtk2() {
    return Stream.of(LINUX_X86_64, LINUX_ARM_64);
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldWarnWithoutFailingWhenGtk2CannotBeInstalled(@TempDir final Path folder) {
    final Download[] downloads = { new Download(LINUX_ARM_64, UNHASHED_PACKAGE_URL) };
    final DebianLibraryInstaller installer = DebianLibraryInstallers.create(folder, downloads, LINUX_ARM_64, this::refuseToLoad);
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      DependencyLoader.provideGtk2(LINUX_ARM_64, libraryName -> false, () -> installer);
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);
    final boolean warned = log.contains("GTK 2 could not be installed, so face detection is not available.");
    final boolean namesTheDownload = log.contains("Debian package %s has no pinned hash!".formatted(UNHASHED_PACKAGE_URL));
    assertTrue(warned, log);
    assertTrue(namesTheDownload, log);
  }

  @Test
  void shouldUseTheGtk2OfTheSystem() {
    assertDoesNotThrow(() -> DependencyLoader.provideGtk2(LINUX_X86_64, libraryName -> true, this::refuseToInstall));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("platformsWithoutDebianGtk2")
  void shouldNotLookForGtk2OffDebianPlatforms(final Platform platform) {
    assertDoesNotThrow(() -> DependencyLoader.provideGtk2(platform, this::refuseToLookUp, this::refuseToInstall));
  }

  private static Stream<Platform> platformsWithoutDebianGtk2() {
    return Stream.of(
      Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64),
      Platform.ofPlatform(Os.MAC, Arch.ARM, Bits.BITS_64),
      Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_32),
      Platform.ofPlatform(Os.LINUX, Arch.OTHER, Bits.BITS_64)
    );
  }

  @Test
  void shouldProvideGtk2BeforeLoadingTheObjectDetectionNatives() {
    final List<String> lookedUp = new ArrayList<>();
    final Logger logger = mock(Logger.class);
    final DependencyLoader loader = new DependencyLoader(logger, lookedUp::add, this::refuseToInstall);
    loader.loadNatives(LINUX_ARM_64);

    final List<String> expected = List.of("libgtk-x11-2.0.so.0");
    assertEquals(expected, lookedUp);
  }

  private boolean refuseToLookUp(final String library) {
    return fail("GTK 2 is only looked up on 64-bit Linux!");
  }

  private DebianLibraryInstaller refuseToInstall() {
    return fail("GTK 2 is only installed where the system lacks it!");
  }

  private void refuseToLoad(final String library) {
    fail("A failed installation loads nothing!");
  }

  @Test
  void shouldLogHowLongPreparingVlcTook() {
    final Logger logger = mock(Logger.class);
    final DependencyLoader loader = new DependencyLoader(logger);
    loader.installVlc(() -> {});
    verify(logger).info("Preparing VLC...");
    assertLoggedAPlausibleTime(logger, "VLC ready in {} ms.");
  }

  private static void assertLoggedAPlausibleTime(final Logger logger, final String template) {
    final ArgumentCaptor<Object> elapsedCaptor = ArgumentCaptor.forClass(Object.class);
    verify(logger).info(eq(template), elapsedCaptor.capture());

    final long elapsed = (Long) elapsedCaptor.getValue();
    final boolean plausible = elapsed >= 0 && elapsed < MAXIMUM_PLAUSIBLE_MILLISECONDS;
    assertTrue(plausible, "elapsed " + elapsed + " ms");
  }

  @Test
  void shouldWarnWhenVlcCannotBePrepared() {
    final Logger logger = mock(Logger.class);
    final DependencyLoader offline = new DependencyLoader(logger);
    offline.installVlc(() -> this.failVlcStart(new InstallationException("offline!")));
    final UnsupportedOperatingSystemException unsupported = VlcInstallerFailures.unsupportedSystem(
      "Operating system FREEBSD does not support installing VLC automatically!"
    );
    final String unsupportedReason = unsupported.getMessage();
    final DependencyLoader unsupportedLoader = new DependencyLoader(logger);
    unsupportedLoader.installVlc(() -> this.failVlcStart(unsupported));
    verify(logger).warn("VLC is not available because of \"{}\", so VLC players cannot be used.", "offline!");
    verify(logger).warn("VLC is not available because of \"{}\", so VLC players cannot be used.", unsupportedReason);
  }

  @Test
  void shouldLogACancelledVlcPreparationInsteadOfWarning() {
    final Logger logger = mock(Logger.class);
    final DependencyLoader loader = new DependencyLoader(logger);
    loader.installVlc(this::interruptAndFailVlcStart);
    final boolean interrupted = Thread.interrupted();
    final boolean hasVlc = loader.hasCapability(Capability.VLC);
    assertTrue(interrupted, "the interrupt is kept, so the installation thread ends");
    assertFalse(hasVlc);
    verify(logger).info("Preparing {} was cancelled.", "VLC");
    verify(logger, never()).warn(anyString(), any(Object.class));
  }

  private void interruptAndFailVlcStart() {
    final Thread current = Thread.currentThread();
    current.interrupt();
    throw new InstallationException("Interrupted while downloading!");
  }

  @Test
  void shouldLogHowLongPreparingYtdlpTookAndWhyItFailed() throws IOException {
    final Logger logger = mock(Logger.class);
    final Installer working = mock(Installer.class);
    final Path installed = Path.of(YTDLP_FILE_NAME);
    when(working.isSupported()).thenReturn(true);
    when(working.download(FileMode.EXECUTABLE)).thenReturn(installed);
    final Installer offline = mock(Installer.class);
    final InstallationException offlineFailure = new InstallationException("offline!");
    when(offline.isSupported()).thenReturn(true);
    when(offline.download(FileMode.EXECUTABLE)).thenThrow(offlineFailure);
    final Installer unsupported = mock(Installer.class);
    when(unsupported.isSupported()).thenReturn(false);
    final DependencyLoader workingLoader = new DependencyLoader(logger);
    workingLoader.installYtdlp(working);
    final DependencyLoader offlineLoader = new DependencyLoader(logger);
    offlineLoader.installYtdlp(offline);
    final DependencyLoader unsupportedLoader = new DependencyLoader(logger);
    unsupportedLoader.installYtdlp(unsupported);
    assertLoggedAPlausibleTime(logger, "yt-dlp ready in {} ms.");
    verify(logger).warn("yt-dlp could not be installed because of \"{}\", so URL parsing cannot be used.", "offline!");
    final Platform platform = Platform.getCurrentPlatform();
    verify(logger).warn("yt-dlp is not available for platform {}, URL parsing cannot be used.", platform);
  }

  @Test
  void shouldLogACancelledYtdlpPreparationInsteadOfWarning() throws IOException {
    final Logger logger = mock(Logger.class);
    final Installer installer = mock(Installer.class);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenAnswer(_ -> this.interruptAndFailDownload());
    final DependencyLoader loader = new DependencyLoader(logger);
    loader.installYtdlp(installer);
    final boolean interrupted = Thread.interrupted();
    final boolean hasYtdlp = loader.hasCapability(Capability.YT_DLP);
    assertTrue(interrupted, "the interrupt is kept, so the installation thread ends");
    assertFalse(hasYtdlp);
    verify(logger).info("Preparing {} was cancelled.", "yt-dlp");
    verify(logger, never()).warn(anyString(), any(Object.class));
  }

  private Path interruptAndFailDownload() {
    final Thread current = Thread.currentThread();
    current.interrupt();
    throw new InstallationException("Interrupted while downloading!");
  }

  private static boolean vlcAvailableAfter(final DependencyLoader.VlcStarter starter) {
    final DependencyLoader loader = new DependencyLoader();
    loader.installVlc(starter);
    return loader.hasCapability(Capability.VLC);
  }

  private NativeLoadingException loadFfmpegFailingWith(final LinkageError error, final AtomicBoolean configured) {
    final Runnable failingLoader = () -> this.throwError(error);
    final Runnable setup = () -> configured.set(true);
    return assertThrows(NativeLoadingException.class, () -> DependencyLoader.loadFfmpeg(failingLoader, setup));
  }

  private static void resetProperty(final String key, final @Nullable String value) {
    if (value == null) {
      System.clearProperty(key);
      return;
    }

    System.setProperty(key, value);
  }

  private static final class FailingDeviceLibrary {

    private static final int LOADED = fail();

    private FailingDeviceLibrary() {}

    private static int fail() {
      throw new UnsatisfiedLinkError("no jniavdevice in java.library.path!");
    }

    static void touch() {
      assertEquals(0, LOADED);
    }
  }

  private enum PreparationOutcome {
    FAILS,
    SUCCEEDS,
  }
}
