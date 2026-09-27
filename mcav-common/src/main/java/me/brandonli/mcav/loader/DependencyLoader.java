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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.utils.natives.NativeLoadingException;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.opencv.presets.opencv_core;
import org.bytedeco.opencv.presets.opencv_imgcodecs;
import org.bytedeco.opencv.presets.opencv_imgproc;
import org.bytedeco.opencv.presets.opencv_objdetect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the native libraries of JavaCV and installs the optional programs VLC and yt-dlp.
 *
 * <p>Every optional program or native library maps to a {@link Capability}. When one cannot be installed or loaded,
 * the capability is removed instead of failing the whole library, so features that do not need it keep working.
 * All methods are called by {@link me.brandonli.mcav.MCAV} and are not meant to be called directly: the natives are
 * loaded during installation, and VLC and yt-dlp are installed afterward on two background threads at the same time,
 * so the capabilities are thread-safe. A background thread that is interrupted, because the library was released,
 * stops its installation; the capability is then removed and the cancellation is logged instead of a warning.
 */
public final class DependencyLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(DependencyLoader.class);
  private static final String JNI_AVDEVICE = "jniavdevice";
  // the class of the device library, which a second load names without the library when the first failure is not kept
  private static final String AVDEVICE_CLASS = "org.bytedeco.ffmpeg.global.avdevice";
  // only the modules the filters use are loaded up front; JavaCPP loads any other module on first use
  private static final List<Class<?>> OPENCV_MODULES = List.of(opencv_core.class, opencv_imgproc.class, opencv_imgcodecs.class);
  private static final String FACE_DETECTION = "Face detection";
  private static final String PATHS_FIRST_PROPERTY = "org.bytedeco.javacpp.pathsFirst";
  private static final String PATHS_FIRST_VALUE = "false";

  private final Set<Capability> capabilities;
  private final Logger logger;

  /**
   * Constructs a new loader. {@link me.brandonli.mcav.MCAV} creates one for you; only create your own if you embed
   * the installation steps into a custom bootstrap.
   */
  public DependencyLoader() {
    this(LOGGER);
  }

  /**
   * Constructs a new loader that reports the installation of VLC and yt-dlp to the specified logger.
   *
   * @param logger reports the progress and the failures of the VLC and yt-dlp installations
   */
  @VisibleForTesting
  DependencyLoader(final Logger logger) {
    // VLC and yt-dlp remove their capabilities from two threads at the same time, which a plain EnumSet can lose
    final Set<Capability> available = ConcurrentHashMap.newKeySet();
    final Set<Capability> every = EnumSet.allOf(Capability.class);
    available.addAll(every);
    this.capabilities = available;
    this.logger = logger;
  }

  /**
   * Checks whether a capability is available.
   *
   * @param capability the capability to check
   * @return true if the program or native library behind the capability was installed or loaded successfully
   */
  public boolean hasCapability(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
    return this.capabilities.contains(capability);
  }

  /**
   * Loads the native libraries of FFmpeg and the OpenCV modules the filters use. The libraries are extracted from
   * the platform jars into the JavaCPP cache on first use, which takes a few seconds once per machine. Whether face
   * detection is available afterward is reported by {@link Capability#FACE_DETECTION}.
   *
   * @throws NativeLoadingException if the libraries cannot be loaded on this platform
   */
  public void loadModules() {
    loadModules(this::loadNatives);
  }

  /**
   * Loads the natives with the specified loader and turns linking failures into a {@link NativeLoadingException}.
   *
   * @param nativeLoader loads the native libraries
   * @throws NativeLoadingException if the loader fails to link a library
   */
  @VisibleForTesting
  static void loadModules(final Runnable nativeLoader) {
    final long start = System.currentTimeMillis();
    LOGGER.info("Loading JavaCV natives...");
    try {
      nativeLoader.run();
    } catch (final UnsatisfiedLinkError | NoClassDefFoundError exception) {
      final String reason = exception.getMessage();
      throw new NativeLoadingException("Failed to load the JavaCV natives: " + reason, exception);
    }
    final long end = System.currentTimeMillis();
    final long elapsed = end - start;
    LOGGER.info("JavaCV natives loaded in {} ms", elapsed);
  }

  private void loadNatives() {
    configureJavaCpp();
    Loader.load(Loader.class);
    loadFFmpeg(DependencyLoader::loadFFmpegLibraries, DependencyLoader::configureFFmpegLogging);
    for (final Class<?> module : OPENCV_MODULES) {
      Loader.load(module);
    }
    this.loadFaceDetection(DependencyLoader::loadObjectDetection);
  }

  /**
   * Sets the JavaCPP system properties the library relies on. A property the application has set already is left
   * alone, so it can still be tuned with {@code -D} on the command line.
   *
   * <p>{@code org.bytedeco.openblas.load} is deliberately not set to {@code none}: that value tells JavaCPP the
   * application provides the BLAS symbols itself, and the OpenCV natives of JavaCV 1.5.14 link OpenBLAS, so
   * {@code jniopencv_core} then fails with "Can't find dependent libraries".
   */
  @VisibleForTesting
  static void configureJavaCpp() {
    // the JNI glue is compiled against the bundled FFmpeg and OpenCV, so the bundled libraries must be found before
    // a system FFmpeg or OpenCV of another version, which would fail to link or crash at runtime
    final String current = System.getProperty(PATHS_FIRST_PROPERTY);
    if (current == null) {
      System.setProperty(PATHS_FIRST_PROPERTY, PATHS_FIRST_VALUE);
    }
  }

  private static void loadObjectDetection() {
    Loader.load(opencv_objdetect.class);
  }

  /**
   * Loads the natives face detection needs and records the result in {@link Capability#FACE_DETECTION}.
   *
   * @param objectDetectionLoader loads the object detection natives
   */
  @VisibleForTesting
  void loadFaceDetection(final Runnable objectDetectionLoader) {
    // the object detection natives link OpenCV's GUI module, which needs GTK 2 on Linux; servers often lack it, and
    // only face detection needs these natives, so everything else keeps working without them
    final boolean available = loadOptionalModule(objectDetectionLoader, FACE_DETECTION);
    if (!available) {
      this.capabilities.remove(Capability.FACE_DETECTION);
    }
  }

  /**
   * Loads natives that only one feature needs. If they cannot be linked, the feature is reported as unavailable
   * instead of failing the whole library.
   *
   * @param nativeLoader loads the natives
   * @param feature      the feature that needs them, for the log
   * @return true if the natives were loaded
   */
  @VisibleForTesting
  static boolean loadOptionalModule(final Runnable nativeLoader, final String feature) {
    try {
      nativeLoader.run();
      return true;
    } catch (final LinkageError error) {
      final String reason = error.getMessage();
      LOGGER.warn("{} is not available on this system, because its native libraries cannot be loaded: {}", feature, reason);
      return false;
    }
  }

  /**
   * Loads FFmpeg with the specified loader and then runs the setup that needs FFmpeg. A missing device library only
   * disables capture devices, so that failure is logged instead of thrown, and the setup still runs. That holds for
   * every load in a JVM: after the first, the JVM reports the device library's class as failed, naming the first
   * failure as the cause while it keeps that, as when a plugin that installs mcav is disabled and enabled again.
   *
   * @param ffmpegLoader loads the FFmpeg libraries
   * @param setup        configures FFmpeg once it is loaded, such as its logging
   * @throws NativeLoadingException if any other FFmpeg library cannot be linked
   */
  @VisibleForTesting
  static void loadFFmpeg(final Runnable ffmpegLoader, final Runnable setup) {
    try {
      ffmpegLoader.run();
    } catch (final UnsatisfiedLinkError | NoClassDefFoundError exception) {
      final boolean avdeviceOnly = Throwables.getCausalChain(exception)
        .stream()
        .map(Throwable::getMessage)
        .anyMatch(message -> message != null && (message.contains(JNI_AVDEVICE) || message.endsWith(AVDEVICE_CLASS)));
      if (!avdeviceOnly) {
        throw new NativeLoadingException("Failed to load FFmpeg: " + exception.getMessage(), exception);
      }
      LOGGER.warn("The FFmpeg device library is not available, capture devices will not work");
    }
    setup.run();
  }

  private static void loadFFmpegLibraries() {
    Loader.load(ffmpeg.class);
  }

  private static void configureFFmpegLogging() {
    FFmpegLogCallback.set();
    FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR);
  }

  /**
   * Finds or installs VLC. If VLC is not available for this platform or the installation fails, the
   * {@link Capability#VLC} capability is removed.
   */
  public void installVLC() {
    this.installVLC(DependencyLoader::startInstallationKit);
  }

  private static Optional<Path> startInstallationKit() throws IOException {
    final VLCInstallationKit kit = VLCInstallationKit.create();
    return kit.start();
  }

  /**
   * Prepares VLC with the specified starter and removes the VLC capability when that fails for any reason: a failed
   * download, an unsupported system, or natives that cannot be linked, such as an Intel VLC on Apple silicon or a
   * JNA that cannot load.
   *
   * @param starter finds or installs VLC
   */
  @VisibleForTesting
  void installVLC(final VLCStarter starter) {
    this.logger.info("Preparing VLC...");
    final long start = System.currentTimeMillis();
    try {
      starter.start();
      final long end = System.currentTimeMillis();
      final long elapsed = end - start;
      this.logger.info("VLC ready in {} ms", elapsed);
    } catch (final IOException | RuntimeException | LinkageError exception) {
      // every mcav failure is a RuntimeException, and LinkageError covers VLC natives that cannot be linked; other
      // errors, such as an OutOfMemoryError, are not a reason to run without VLC and must reach the caller
      this.capabilities.remove(Capability.VLC);
      final boolean cancelled = this.logCancellation(Capability.VLC);
      if (!cancelled) {
        final String reason = exception.getMessage();
        this.logger.warn("VLC is not available, VLC players cannot be used: {}", reason);
      }
    }
  }

  /**
   * Logs that the installation of a program was cancelled, if the current thread was interrupted. The library
   * interrupts its installation threads when it is released, which makes a running download fail; that failure is
   * expected and not worth a warning.
   *
   * @param capability the capability of the program
   * @return true if the installation was cancelled
   */
  private boolean logCancellation(final Capability capability) {
    final Thread currentThread = Thread.currentThread();
    final boolean cancelled = currentThread.isInterrupted();
    if (cancelled) {
      final String name = capability.getDisplayName();
      this.logger.info("Preparing {} was cancelled", name);
    }
    return cancelled;
  }

  /**
   * Installs yt-dlp. If yt-dlp is not available for this platform or the installation fails, the
   * {@link Capability#YT_DLP} capability is removed.
   */
  public void installYTDLP() {
    final YTDLPInstaller installer = YTDLPInstaller.shared();
    this.installYTDLP(installer);
  }

  /**
   * Prepares yt-dlp with the specified installer and removes the yt-dlp capability when that fails for any reason: a
   * failed download, a downloaded file that cannot be marked as executable, or any other unchecked failure of the
   * installer.
   *
   * @param installer the yt-dlp installer
   */
  @VisibleForTesting
  void installYTDLP(final Installer installer) {
    final boolean supported = installer.isSupported();
    if (!supported) {
      this.capabilities.remove(Capability.YT_DLP);
      this.logger.warn("yt-dlp is not available for this platform, URL parsing cannot be used");
      return;
    }
    this.logger.info("Preparing yt-dlp...");
    final long start = System.currentTimeMillis();
    try {
      installer.download(true);
      final long end = System.currentTimeMillis();
      final long elapsed = end - start;
      this.logger.info("yt-dlp ready in {} ms", elapsed);
    } catch (final IOException | RuntimeException exception) {
      // an installer reports unchecked failures, such as a cache folder that cannot be created, as RuntimeExceptions
      this.capabilities.remove(Capability.YT_DLP);
      final boolean cancelled = this.logCancellation(Capability.YT_DLP);
      if (!cancelled) {
        final String reason = exception.getMessage();
        this.logger.warn("yt-dlp could not be installed, URL parsing cannot be used: {}", reason);
      }
    }
  }

  /**
   * Finds or installs VLC.
   */
  @FunctionalInterface
  interface VLCStarter {
    /**
     * Finds or installs VLC and loads its libraries.
     *
     * @return the directory the libraries were loaded from, if known
     * @throws IOException if VLC has to be downloaded and the download fails
     */
    Optional<Path> start() throws IOException;
  }
}
