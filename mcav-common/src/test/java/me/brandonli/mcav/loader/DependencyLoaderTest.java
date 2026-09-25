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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Throwables;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.capability.installer.vlc.UnsupportedOperatingSystemException;
import me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit;
import me.brandonli.mcav.capability.installer.vlc.VlcInstallerFailures;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.utils.natives.NativeLoadingException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.Logger;

/**
 * Tests {@link DependencyLoader}.
 */
final class DependencyLoaderTest {

  private static final String OPENBLAS_LOAD = "org.bytedeco.openblas.load";
  private static final String PATHS_FIRST = "org.bytedeco.javacpp.pathsFirst";
  private static final int CONCURRENT_ROUNDS = 500;
  private static final List<String> OBJECT_DETECTION_LOADED = List.of("objdetect");

  @Test
  void startsWithEveryCapability() {
    final DependencyLoader loader = new DependencyLoader();
    for (final Capability capability : Capability.values()) {
      final boolean available = loader.hasCapability(capability);
      assertTrue(available, capability::name);
    }
    assertThrows(NullPointerException.class, () -> loader.hasCapability(null));
  }

  @Test
  void loadsTheBundledNativesAheadOfTheSystemLibraries() {
    final DependencyLoader loader = new DependencyLoader();
    assertDoesNotThrow(() -> loader.loadModules());
    final String pathsFirst = System.getProperty(PATHS_FIRST);
    final String openBlas = System.getProperty(OPENBLAS_LOAD);
    final boolean ffmpeg = loader.hasCapability(Capability.FFMPEG);
    assertEquals("false", pathsFirst);
    assertNull(openBlas, "OpenCV links OpenBLAS, so the library must not disable it");
    assertTrue(ffmpeg);
  }

  @Test
  void configuresJavaCppOnlyWhereTheApplicationHasNot() {
    final String previousPathsFirst = System.getProperty(PATHS_FIRST);
    try {
      System.clearProperty(PATHS_FIRST);
      DependencyLoader.configureJavaCpp();
      final String defaultPathsFirst = System.getProperty(PATHS_FIRST);
      System.setProperty(PATHS_FIRST, "true");
      DependencyLoader.configureJavaCpp();
      final String customPathsFirst = System.getProperty(PATHS_FIRST);
      assertEquals("false", defaultPathsFirst);
      assertEquals("true", customPathsFirst, "a value set by the application is kept");
    } finally {
      restorePathsFirst(previousPathsFirst);
    }
  }

  @Test
  void reportsNativesThatCannotBeLinked() {
    final UnsatisfiedLinkError linkError = new UnsatisfiedLinkError("no jniopencv_core");
    final NativeLoadingException linkFailure = assertThrows(NativeLoadingException.class, () ->
      DependencyLoader.loadModules(() -> {
        throw linkError;
      })
    );
    final NoClassDefFoundError missingClass = new NoClassDefFoundError("org/bytedeco/opencv/global/opencv_core");
    final NativeLoadingException classFailure = assertThrows(NativeLoadingException.class, () ->
      DependencyLoader.loadModules(() -> {
        throw missingClass;
      })
    );
    final Throwable linkCause = linkFailure.getCause();
    final Throwable classCause = classFailure.getCause();
    final String linkMessage = linkFailure.getMessage();
    final boolean namesTheLibrary = linkMessage.contains("no jniopencv_core");
    assertSame(linkError, linkCause);
    assertSame(missingClass, classCause);
    assertTrue(namesTheLibrary, linkMessage);
  }

  @Test
  void toleratesAMissingFFmpegDeviceLibraryAndStillConfiguresFFmpeg() {
    final UnsatisfiedLinkError deviceError = new UnsatisfiedLinkError("no jniavdevice in java.library.path");
    final AtomicBoolean configured = new AtomicBoolean();
    assertDoesNotThrow(() ->
      DependencyLoader.loadFFmpeg(
        () -> {
          throw deviceError;
        },
        () -> configured.set(true)
      )
    );
    final boolean setupRan = configured.get();
    assertTrue(setupRan, "the log callback is set up even without the device library");
  }

  @Test
  void toleratesTheMissingDeviceLibraryAgainWhenFFmpegIsLoadedASecondTime() {
    // a plugin disabled and enabled again loads FFmpeg twice in one JVM: the second load finds the class failed
    final AtomicInteger configured = new AtomicInteger();
    final Runnable loader = FailingDeviceLibrary::touch;
    assertDoesNotThrow(() -> DependencyLoader.loadFFmpeg(loader, configured::incrementAndGet));
    final NoClassDefFoundError again = assertThrows(NoClassDefFoundError.class, loader::run);
    assertDoesNotThrow(() -> DependencyLoader.loadFFmpeg(loader, configured::incrementAndGet));
    assertEquals(2, configured.get());
    assertTrue(Throwables.getStackTraceAsString(again).contains("no jniavdevice"), again::toString);
  }

  @Test
  void reportsAnotherFFmpegClassThatFailedBefore() {
    final NoClassDefFoundError codecClass = new NoClassDefFoundError("Could not initialize class avcodec");
    codecClass.initCause(new ExceptionInInitializerError("Exception java.lang.UnsatisfiedLinkError: no jniavcodec"));
    final AtomicBoolean configured = new AtomicBoolean();
    final NativeLoadingException failure = loadFFmpegFailingWith(codecClass, configured);
    assertSame(codecClass, failure.getCause());
    assertTrue(failure.getMessage().contains("Could not initialize class avcodec"), failure.getMessage());
    assertFalse(configured.get(), "FFmpeg that failed to load is not configured");
  }

  @Test
  void configuresFFmpegAfterLoadingIt() {
    final List<String> steps = new ArrayList<>();
    DependencyLoader.loadFFmpeg(() -> steps.add("load"), () -> steps.add("setup"));
    final List<String> expected = List.of("load", "setup");
    assertEquals(expected, steps);
  }

  @Test
  void reportsEveryOtherFFmpegLinkFailure() {
    final UnsatisfiedLinkError codecError = new UnsatisfiedLinkError("no jniavcodec");
    final AtomicBoolean configured = new AtomicBoolean();
    final NativeLoadingException codecFailure = loadFFmpegFailingWith(codecError, configured);

    final Throwable codecCause = codecFailure.getCause();
    final String codecMessage = codecFailure.getMessage();
    final boolean namesTheLibrary = codecMessage.contains("no jniavcodec");
    final boolean setupRan = configured.get();

    assertSame(codecError, codecCause);
    assertTrue(namesTheLibrary, codecMessage);
    assertFalse(setupRan, "FFmpeg that failed to load is not configured");
  }

  @Test
  void reportsFFmpegLinkFailuresWithoutAMessage() {
    final UnsatisfiedLinkError silentError = new UnsatisfiedLinkError();
    final AtomicBoolean configured = new AtomicBoolean();
    final NativeLoadingException silentFailure = loadFFmpegFailingWith(silentError, configured);

    final Throwable silentCause = silentFailure.getCause();
    final boolean setupRan = configured.get();

    assertSame(silentError, silentCause);
    assertFalse(setupRan, "FFmpeg that failed to load is not configured");
  }

  @Test
  void keepsTheVlcCapabilityWhenVlcIsReady() {
    final Path libraryDirectory = Path.of("vlc");
    final Optional<Path> found = Optional.of(libraryDirectory);
    final boolean vlc = vlcAvailableAfter(() -> found);
    assertTrue(vlc);
  }

  @Test
  void removesOnlyTheVlcCapabilityWhenTheDownloadFails() {
    final DependencyLoader loader = new DependencyLoader();
    loader.installVLC(() -> {
      throw new IOException("offline");
    });
    final boolean vlc = loader.hasCapability(Capability.VLC);
    final boolean ytdlp = loader.hasCapability(Capability.YT_DLP);
    final boolean ffmpeg = loader.hasCapability(Capability.FFMPEG);
    assertFalse(vlc);
    assertTrue(ytdlp);
    assertTrue(ffmpeg);
  }

  @Test
  void removesTheVlcCapabilityOnUnsupportedSystems() {
    final UnsupportedOperatingSystemException unsupported = VlcInstallerFailures.unsupportedSystem(
      "VLC cannot be installed automatically on FREEBSD"
    );
    final boolean vlc = vlcAvailableAfter(() -> {
      throw unsupported;
    });
    assertFalse(vlc);
  }

  @Test
  void doesNotHideVirtualMachineErrorsWhilePreparingVlc() {
    final DependencyLoader loader = new DependencyLoader();
    final OutOfMemoryError outOfMemory = new OutOfMemoryError("heap is full");
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () ->
      loader.installVLC(() -> {
        throw outOfMemory;
      })
    );
    assertSame(outOfMemory, thrown, "running out of memory is no reason to run without VLC");
  }

  @Test
  void removesTheYtdlpCapabilityOnUnexpectedRuntimeFailures() throws IOException {
    final Installer installer = mock(Installer.class);
    final IllegalStateException failure = new IllegalStateException("broken download list");
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(true)).thenThrow(failure);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYTDLP(installer);
    final boolean ytdlp = loader.hasCapability(Capability.YT_DLP);
    assertFalse(ytdlp);
  }

  @Test
  void removesTheVlcCapabilityWhenNativesCannotBeLinked() {
    final UnsatisfiedLinkError wrongArchitecture = new UnsatisfiedLinkError("incompatible architecture (have 'x86_64', need 'arm64')");
    final NoClassDefFoundError missingJna = new NoClassDefFoundError("Could not initialize class com.sun.jna.Native");
    final boolean withWrongArchitecture = vlcAvailableAfter(() -> {
      throw wrongArchitecture;
    });
    final boolean withoutJna = vlcAvailableAfter(() -> {
      throw missingJna;
    });
    assertFalse(withWrongArchitecture);
    assertFalse(withoutJna);
  }

  @Test
  void removesTheVlcCapabilityOnUnexpectedRuntimeFailures() {
    final IllegalStateException failure = new IllegalStateException("broken plugin cache");
    final boolean vlc = vlcAvailableAfter(() -> {
      throw failure;
    });
    assertFalse(vlc);
  }

  @Test
  void startsTheInstallationKitOfTheLibrary() throws IOException {
    final VLCInstallationKit kit = mock(VLCInstallationKit.class);
    when(kit.start()).thenReturn(Optional.empty());
    try (final MockedStatic<VLCInstallationKit> kits = mockStatic(VLCInstallationKit.class)) {
      kits.when(VLCInstallationKit::create).thenReturn(kit);
      final DependencyLoader loader = new DependencyLoader();
      loader.installVLC();
      final boolean vlc = loader.hasCapability(Capability.VLC);
      assertTrue(vlc);
    }
    verify(kit).start();
  }

  @Test
  void installsYtdlpWhenItIsSupported() throws IOException {
    final Installer installer = mock(Installer.class);
    final Path installed = Path.of("yt-dlp");
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(true)).thenReturn(installed);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYTDLP(installer);
    final boolean ytdlp = loader.hasCapability(Capability.YT_DLP);
    assertTrue(ytdlp);
    verify(installer).download(true);
  }

  @Test
  void removesTheYtdlpCapabilityWithoutDownloadingOnUnsupportedPlatforms() throws IOException {
    final Installer installer = mock(Installer.class);
    when(installer.isSupported()).thenReturn(false);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYTDLP(installer);
    final boolean ytdlp = loader.hasCapability(Capability.YT_DLP);
    final boolean vlc = loader.hasCapability(Capability.VLC);
    assertFalse(ytdlp);
    assertTrue(vlc);
    verify(installer, never()).download(anyBoolean());
  }

  @Test
  void removesTheYtdlpCapabilityWhenTheDownloadFails() throws IOException {
    final Installer installer = mock(Installer.class);
    final IOException offline = new IOException("offline");
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(true)).thenThrow(offline);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYTDLP(installer);
    final boolean ytdlp = loader.hasCapability(Capability.YT_DLP);
    assertFalse(ytdlp);
  }

  @Test
  void removesTheYtdlpCapabilityWhenTheDownloadCannotBeMarkedExecutable() throws IOException {
    final Installer installer = mock(Installer.class);
    final IOException permissionDenied = new IOException("Operation not permitted");
    final UncheckedIOException chmodFailure = new UncheckedIOException("Operation not permitted", permissionDenied);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(true)).thenThrow(chmodFailure);
    final DependencyLoader loader = new DependencyLoader();
    loader.installYTDLP(installer);
    final boolean ytdlp = loader.hasCapability(Capability.YT_DLP);
    final boolean vlc = loader.hasCapability(Capability.VLC);
    assertFalse(ytdlp);
    assertTrue(vlc);
  }

  @Test
  void usesTheSharedYtdlpInstallerOfTheLibrary() throws IOException {
    final YTDLPInstaller installer = mock(YTDLPInstaller.class);
    final Path installed = Path.of("yt-dlp");
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(true)).thenReturn(installed);
    try (final MockedStatic<YTDLPInstaller> installers = mockStatic(YTDLPInstaller.class)) {
      installers.when(YTDLPInstaller::shared).thenReturn(installer);
      final DependencyLoader loader = new DependencyLoader();
      loader.installYTDLP();
      final boolean ytdlp = loader.hasCapability(Capability.YT_DLP);
      assertTrue(ytdlp);
    }
    verify(installer).download(true);
  }

  @Test
  void keepsEveryRemovalWhenVlcAndYtdlpFailAtTheSameTime() throws Exception {
    // MCAV installs VLC and yt-dlp on two threads; a removal lost by an unsafe set would report a missing program as
    // available. The race is timing dependent, so it is repeated many times.
    final Installer unsupported = mock(Installer.class);
    when(unsupported.isSupported()).thenReturn(false);
    try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
      for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
        final DependencyLoader loader = failBothInstallationsTogether(executor, unsupported);
        final boolean vlcAvailable = loader.hasCapability(Capability.VLC);
        final boolean ytdlpAvailable = loader.hasCapability(Capability.YT_DLP);
        assertFalse(vlcAvailable, "round " + round);
        assertFalse(ytdlpAvailable, "round " + round);
      }
    }
  }

  private static DependencyLoader failBothInstallationsTogether(final ExecutorService executor, final Installer unsupported)
    throws Exception {
    final DependencyLoader loader = new DependencyLoader();
    final CyclicBarrier barrier = new CyclicBarrier(2);
    final Future<?> vlc = executor.submit(() -> {
      barrier.await();
      loader.installVLC(() -> {
        throw new IOException("offline");
      });
      return null;
    });
    final Future<?> ytdlp = executor.submit(() -> {
      barrier.await();
      loader.installYTDLP(unsupported);
      return null;
    });
    vlc.get(10, TimeUnit.SECONDS);
    ytdlp.get(10, TimeUnit.SECONDS);
    return loader;
  }

  @Test
  void reportsFaceDetectionOnlyWhenItsNativesLoad() {
    final List<String> loaded = new ArrayList<>();
    final DependencyLoader available = new DependencyLoader();
    available.loadFaceDetection(() -> loaded.add("objdetect"));
    final UnsatisfiedLinkError missingGtk = new UnsatisfiedLinkError("libgtk-x11-2.0.so.0: cannot open shared object file");
    final DependencyLoader missing = new DependencyLoader();
    missing.loadFaceDetection(() -> {
      throw missingGtk;
    });
    final boolean detectionWithNatives = available.hasCapability(Capability.FACE_DETECTION);
    final boolean detectionWithoutNatives = missing.hasCapability(Capability.FACE_DETECTION);
    final boolean ffmpegWithoutDetection = missing.hasCapability(Capability.FFMPEG);
    assertTrue(detectionWithNatives);
    assertFalse(detectionWithoutNatives);
    assertTrue(ffmpegWithoutDetection, "only face detection is lost");
    assertEquals(OBJECT_DETECTION_LOADED, loaded);
  }

  @Test
  void reportsOptionalModulesThatLoad() {
    final List<String> loaded = new ArrayList<>();
    final boolean available = DependencyLoader.loadOptionalModule(() -> loaded.add("objdetect"), "Face detection");
    assertTrue(available);
    assertEquals(OBJECT_DETECTION_LOADED, loaded);
  }

  @Test
  void keepsWorkingWhenAnOptionalModuleCannotBeLinked() {
    final UnsatisfiedLinkError missingGtk = new UnsatisfiedLinkError("libgtk-x11-2.0.so.0: cannot open shared object file");
    final NoClassDefFoundError failedInitializer = new NoClassDefFoundError("Could not initialize class opencv_highgui");
    final boolean withoutGtk = DependencyLoader.loadOptionalModule(
      () -> {
        throw missingGtk;
      },
      "Face detection"
    );
    final boolean withoutClass = DependencyLoader.loadOptionalModule(
      () -> {
        throw failedInitializer;
      },
      "Face detection"
    );
    assertFalse(withoutGtk);
    assertFalse(withoutClass);
  }

  @Test
  void logsHowLongPreparingVlcTook() {
    final Logger logger = mock(Logger.class);
    final DependencyLoader loader = new DependencyLoader(logger);
    loader.installVLC(Optional::empty);
    verify(logger).info("Preparing VLC...");
    verify(logger).info(eq("VLC ready in {} ms"), any(Object.class));
  }

  @Test
  void warnsWhenVlcCannotBePrepared() {
    final Logger logger = mock(Logger.class);
    final DependencyLoader offline = new DependencyLoader(logger);
    offline.installVLC(() -> {
      throw new IOException("offline");
    });
    final UnsupportedOperatingSystemException unsupported = VlcInstallerFailures.unsupportedSystem(
      "VLC cannot be installed automatically on FREEBSD"
    );
    final String unsupportedReason = unsupported.getMessage();
    final DependencyLoader unsupportedLoader = new DependencyLoader(logger);
    unsupportedLoader.installVLC(() -> {
      throw unsupported;
    });
    verify(logger).warn("VLC is not available, VLC players cannot be used: {}", "offline");
    verify(logger).warn("VLC is not available, VLC players cannot be used: {}", unsupportedReason);
  }

  @Test
  void logsACancelledVlcPreparationInsteadOfWarning() {
    final Logger logger = mock(Logger.class);
    final DependencyLoader loader = new DependencyLoader(logger);
    loader.installVLC(() -> {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new IOException("Interrupted while downloading");
    });
    final boolean interrupted = Thread.interrupted();
    final boolean vlc = loader.hasCapability(Capability.VLC);
    assertTrue(interrupted, "the interrupt is kept, so the installation thread ends");
    assertFalse(vlc);
    verify(logger).info("Preparing {} was cancelled", "VLC");
    verify(logger, never()).warn(anyString(), any(Object.class));
  }

  @Test
  void logsHowLongPreparingYtdlpTookAndWhyItFailed() throws IOException {
    final Logger logger = mock(Logger.class);
    final Installer working = mock(Installer.class);
    final Path installed = Path.of("yt-dlp");
    when(working.isSupported()).thenReturn(true);
    when(working.download(true)).thenReturn(installed);
    final Installer offline = mock(Installer.class);
    final IOException offlineFailure = new IOException("offline");
    when(offline.isSupported()).thenReturn(true);
    when(offline.download(true)).thenThrow(offlineFailure);
    final Installer unsupported = mock(Installer.class);
    when(unsupported.isSupported()).thenReturn(false);
    final DependencyLoader workingLoader = new DependencyLoader(logger);
    workingLoader.installYTDLP(working);
    final DependencyLoader offlineLoader = new DependencyLoader(logger);
    offlineLoader.installYTDLP(offline);
    final DependencyLoader unsupportedLoader = new DependencyLoader(logger);
    unsupportedLoader.installYTDLP(unsupported);
    verify(logger).info(eq("yt-dlp ready in {} ms"), any(Object.class));
    verify(logger).warn("yt-dlp could not be installed, URL parsing cannot be used: {}", "offline");
    verify(logger).warn("yt-dlp is not available for this platform, URL parsing cannot be used");
  }

  @Test
  void logsACancelledYtdlpPreparationInsteadOfWarning() throws IOException {
    final Logger logger = mock(Logger.class);
    final Installer installer = mock(Installer.class);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(true)).thenAnswer(_ -> {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new IOException("Interrupted while downloading");
    });
    final DependencyLoader loader = new DependencyLoader(logger);
    loader.installYTDLP(installer);
    final boolean interrupted = Thread.interrupted();
    final boolean ytdlp = loader.hasCapability(Capability.YT_DLP);
    assertTrue(interrupted, "the interrupt is kept, so the installation thread ends");
    assertFalse(ytdlp);
    verify(logger).info("Preparing {} was cancelled", "yt-dlp");
    verify(logger, never()).warn(anyString(), any(Object.class));
  }

  private static boolean vlcAvailableAfter(final DependencyLoader.VLCStarter starter) {
    final DependencyLoader loader = new DependencyLoader();
    loader.installVLC(starter);
    return loader.hasCapability(Capability.VLC);
  }

  private static NativeLoadingException loadFFmpegFailingWith(final LinkageError error, final AtomicBoolean configured) {
    final Runnable failingLoader = () -> {
      throw error;
    };
    final Runnable setup = () -> configured.set(true);
    return assertThrows(NativeLoadingException.class, () -> DependencyLoader.loadFFmpeg(failingLoader, setup));
  }

  /**
   * A class whose initializer fails as FFmpeg's device class does without its library: the first use throws that
   * failure, and every later one a {@link NoClassDefFoundError} that names it as the cause.
   */
  static final class FailingDeviceLibrary {

    private static final int LOADED = fail();

    private FailingDeviceLibrary() {}

    private static int fail() {
      throw new UnsatisfiedLinkError("no jniavdevice in java.library.path");
    }

    static void touch() {
      assertEquals(0, LOADED);
    }
  }

  private static void restorePathsFirst(final String value) {
    if (value == null) {
      System.clearProperty(PATHS_FIRST);
      return;
    }
    System.setProperty(PATHS_FIRST, value);
  }
}
