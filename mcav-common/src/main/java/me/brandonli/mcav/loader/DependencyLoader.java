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
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import me.brandonli.mcav.MCAVException;
import me.brandonli.mcav.capability.Availability;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller;
import me.brandonli.mcav.capability.installer.vlc.VlcInstallationKit;
import me.brandonli.mcav.capability.installer.ytdlp.YtdlpInstaller;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.ffmpeg.FfmpegExecutableProvider;
import me.brandonli.mcav.utils.natives.NativeLoadingException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.opencv.presets.opencv_core;
import org.bytedeco.opencv.presets.opencv_imgcodecs;
import org.bytedeco.opencv.presets.opencv_imgproc;
import org.bytedeco.opencv.presets.opencv_objdetect;
import org.checkerframework.checker.nullness.qual.Nullable;
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
 *
 * @since 1.0.0
 */
public final class DependencyLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(DependencyLoader.class);
  private static final String JNI_AVDEVICE = "jniavdevice";
  private static final String AVDEVICE_CLASS = "org.bytedeco.ffmpeg.global.avdevice";
  private static final List<Class<?>> OPENCV_MODULES = List.of(opencv_core.class, opencv_imgproc.class, opencv_imgcodecs.class);
  private static final String FACE_DETECTION = "Face detection";
  private static final String PATHS_FIRST_PROPERTY = "org.bytedeco.javacpp.pathsFirst";
  static final String OPENCV_THREADS_PROPERTY = "mcav.opencv.threads";
  private static final String PATHS_FIRST_VALUE = "false";

  private static final String GTK2_LIBRARY = "libgtk-x11-2.0.so.0";

  private static final String CACHE_PROPERTY = "org.bytedeco.javacpp.cachedir";
  private static final String CACHE_ALIAS_PROPERTY = "org.bytedeco.javacpp.cacheDir";
  private static final String USER_HOME_PROPERTY = "user.home";

  private static final String HOME_CACHE_ROOT = ".javacpp";
  private static final String HOME_CACHE_DIRECTORY = "cache";
  private static final String CACHE_FOLDER = "javacpp";

  private final Set<Capability> capabilities;
  private final Map<Capability, Object> preparations = new EnumMap<>(Capability.class);
  private final Logger logger;
  private final Predicate<String> systemLibraries;
  private final Supplier<DebianLibraryInstaller> gtk2Installers;

  /**
   * Constructs a new loader. {@link me.brandonli.mcav.MCAV} creates one for you; only create your own if you embed
   * the installation steps into a custom bootstrap.
   *
   * @since 1.0.0
   */
  public DependencyLoader() {
    this(LOGGER);
  }

  @VisibleForTesting
  DependencyLoader(final Logger logger) {
    this(logger, OsUtils::isLibraryAvailable, DebianLibraryInstaller::createGtk2Installer);
  }

  @VisibleForTesting
  DependencyLoader(final Logger logger, final Predicate<String> systemLibraries, final Supplier<DebianLibraryInstaller> gtk2Installers) {
    // VLC and yt-dlp remove their capabilities from two threads at the same time, which a plain EnumSet can lose
    final Set<Capability> available = ConcurrentHashMap.newKeySet();
    final Set<Capability> every = EnumSet.allOf(Capability.class);
    available.addAll(every);
    this.capabilities = available;
    this.logger = logger;
    this.systemLibraries = systemLibraries;
    this.gtk2Installers = gtk2Installers;
  }

  /**
   * Checks whether preparation has recorded a failure for a capability. A new loader optimistically contains
   * every capability, so this method alone is not a readiness test. Normal callers should use
   * {@link me.brandonli.mcav.MCAVApi#hasCapability(Capability)}, which also tracks background completion.
   *
   * @param capability the capability to check
   * @return true if the last preparation of the capability did not fail; initially true for every capability
   * @throws NullPointerException if {@code capability} is null
   * @since 1.0.0
   */
  public boolean hasCapability(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null!");

    return this.capabilities.contains(capability);
  }

  /**
   * Loads the native libraries of FFmpeg and the OpenCV modules the filters use. The libraries are extracted from
   * the platform jars into the JavaCPP cache on first use, which takes a few seconds once per machine. Whether face
   * detection is available afterward is reported by {@link Capability#FACE_DETECTION}.
   *
   * <p>JavaCV ships no natives for FreeBSD on any processor or for Windows on ARM, so there it fails at once with a
   * message that names the platform.
   *
   * @throws NativeLoadingException if the libraries cannot be loaded on this platform, or it has none
   * @since 1.0.0
   */
  public void loadModules() {
    final Platform platform = Platform.getCurrentPlatform();
    loadModules(platform, () -> this.loadNatives(platform));
  }

  @VisibleForTesting
  static void loadModules(final Platform platform, final Runnable nativeLoader) {
    final boolean bundled = hasBundledNatives(platform);
    if (!bundled) {
      final String message = "Platform %s has no FFmpeg and OpenCV natives!".formatted(platform);
      throw new NativeLoadingException(message);
    }

    final long start = System.currentTimeMillis();
    LOGGER.info("Loading JavaCV natives...");
    try {
      nativeLoader.run();
    } catch (final UnsatisfiedLinkError | NoClassDefFoundError exception) {
      final String reason = exception.getMessage();
      final String message = "Failed with \"%s\" while loading the JavaCV natives!".formatted(reason);
      throw new NativeLoadingException(message, exception);
    }

    final long end = System.currentTimeMillis();
    final long elapsed = end - start;
    LOGGER.info("JavaCV natives loaded in {} ms.", elapsed);
  }

  private static boolean hasBundledNatives(final Platform platform) {
    final Os os = platform.getOs();
    final Arch arch = platform.getArch();
    return switch (os) {
      case WINDOWS -> arch != Arch.ARM;
      case MAC, LINUX -> true;
      case FREEBSD, OTHER -> false;
    };
  }

  @VisibleForTesting
  void loadNatives(final Platform platform) {
    configureJavaCpp();
    Loader.load(Loader.class);
    // extracting the ffmpeg program loads every FFmpeg library, after the system libraries that they link
    loadFfmpeg(FfmpegExecutableProvider::getFfmpegPath, DependencyLoader::configureFfmpegLogging);
    for (final Class<?> module : OPENCV_MODULES) {
      Loader.load(module);
    }

    configureOpenCvThreads();
    this.loadFaceDetection(() -> this.loadObjectDetection(platform));
  }

  // Sets how many threads OpenCV runs its own work on, such as a resize: one by default, so it runs on the calling
  // thread. Otherwise OpenCV starts a pool of one thread per processor but one at its first parallel call, named after
  // the thread that made it, which outlives every player and works outside the encoders' budget. The system property
  // OPENCV_THREADS_PROPERTY sets another number; 0 runs everything sequentially, and below 0 is OpenCV's default.
  @VisibleForTesting
  static void configureOpenCvThreads() {
    final int threads = Integer.getInteger(OPENCV_THREADS_PROPERTY, 1);
    org.bytedeco.opencv.global.opencv_core.setNumThreads(threads); // fqn: the presets class of the same name is imported to load the natives
  }

  // Sets the JavaCPP system properties the library relies on. A property the application has set already is left alone,
  // so it can still be tuned with -D on the command line. org.bytedeco.openblas.load is deliberately not set to none:
  // that value tells JavaCPP the application provides the BLAS symbols itself, and the OpenCV natives of JavaCV 1.5.14
  // link OpenBLAS, so jniopencv_core then fails with "Can't find dependent libraries".
  @VisibleForTesting
  static void configureJavaCpp() {
    // the JNI glue is compiled against the bundled FFmpeg and OpenCV, so the bundled libraries must be found before
    // a system FFmpeg or OpenCV of another version, which would fail to link or crash at runtime
    final String current = System.getProperty(PATHS_FIRST_PROPERTY);
    if (current == null) {
      System.setProperty(PATHS_FIRST_PROPERTY, PATHS_FIRST_VALUE);
    }

    configureJavaCppCache();
  }

  // JavaCPP only checks that ~/.javacpp/cache is writable, so its natives fail to load from a home mounted noexec
  private static void configureJavaCppCache() {
    final @Nullable String configured = System.getProperty(CACHE_PROPERTY);
    final @Nullable String configuredAlias = System.getProperty(CACHE_ALIAS_PROPERTY);
    if (configured != null || configuredAlias != null) {
      return;
    }

    final @Nullable String homeOrNull = System.getProperty(USER_HOME_PROPERTY);
    final String home = Objects.requireNonNull(homeOrNull, "The JVM always sets the user home!");
    final Path homeCache = Path.of(home, HOME_CACHE_ROOT, HOME_CACHE_DIRECTORY);
    final boolean runnable = IoUtils.createRunnableFolder(homeCache);
    if (runnable) {
      return;
    }

    final Path cache = IoUtils.getCachedFolder();
    final Path fallback = cache.resolve(CACHE_FOLDER);
    final String rawFallback = fallback.toString();
    LOGGER.warn("JavaCPP cache folder {} cannot be written or cannot run programs, using {} instead.", homeCache, fallback);
    System.setProperty(CACHE_PROPERTY, rawFallback);
  }

  private void loadObjectDetection(final Platform platform) {
    provideGtk2(platform, this.systemLibraries, this.gtk2Installers);
    Loader.load(opencv_objdetect.class);
  }

  // The object detection natives link OpenCV's GUI module, which needs GTK 2 on Linux. When the system has none, the
  // pinned Debian packages of GTK 2 are installed into the cache and loaded first, so the module links against them. A
  // failed installation only costs face detection, which then fails to link and is reported as unavailable.
  @VisibleForTesting
  static void provideGtk2(
    final Platform platform,
    final Predicate<String> systemLibraries,
    final Supplier<DebianLibraryInstaller> installerFactory
  ) {
    final boolean debianPackages = hasDebianGtk2(platform);
    if (!debianPackages) {
      return;
    }

    final boolean installed = systemLibraries.test(GTK2_LIBRARY);
    if (installed) {
      return;
    }

    try {
      installGtk2(installerFactory);
    } catch (final MCAVException exception) {
      LOGGER.warn("GTK 2 could not be installed, so face detection is not available.", exception);
    }
  }

  private static boolean hasDebianGtk2(final Platform platform) {
    final Os os = platform.getOs();
    final Arch arch = platform.getArch();
    final Bits bits = platform.getBits();
    return os == Os.LINUX && bits == Bits.BITS_64 && arch != Arch.OTHER;
  }

  private static void installGtk2(final Supplier<DebianLibraryInstaller> installerFactory) {
    final DebianLibraryInstaller installer = installerFactory.get();
    installer.download(FileMode.EXECUTABLE);
    installer.loadLibraries();
  }

  @VisibleForTesting
  void loadFaceDetection(final Runnable objectDetectionLoader) {
    // the object detection natives link OpenCV's GUI module, which needs GTK 2 on Linux; where it cannot be provided,
    // only face detection is lost, so everything else keeps working without these natives
    final boolean available = loadOptionalModule(objectDetectionLoader, FACE_DETECTION);
    if (available) {
      this.capabilities.add(Capability.FACE_DETECTION);
    } else {
      this.capabilities.remove(Capability.FACE_DETECTION);
    }
  }

  // Loads natives that only one feature needs. If they cannot be linked, the feature is reported as unavailable instead
  // of failing the whole library.
  @VisibleForTesting
  static boolean loadOptionalModule(final Runnable nativeLoader, final String feature) {
    try {
      nativeLoader.run();

      return true;
    } catch (final LinkageError exception) {
      final String reason = exception.getMessage();
      LOGGER.warn("{} is not available on this system, because its native libraries failed with \"{}\" while loading.", feature, reason);

      return false;
    }
  }

  // Loads FFmpeg with the specified loader and then runs the setup that needs FFmpeg. A missing device library only
  // disables capture devices, so that failure is logged instead of thrown, and the setup still runs. That holds for
  // every load in a JVM: after the first, the JVM reports the device library's class as failed, naming the first
  // failure as the cause while it keeps that, as when a plugin that installs mcav is disabled and enabled again.
  @VisibleForTesting
  static void loadFfmpeg(final Runnable ffmpegLoader, final Runnable setup) {
    try {
      ffmpegLoader.run();
    } catch (final UnsatisfiedLinkError | NoClassDefFoundError exception) {
      final boolean avdeviceOnly = Throwables.getCausalChain(exception)
        .stream()
        .map(Throwable::getMessage)
        .anyMatch(message -> message != null && (message.contains(JNI_AVDEVICE) || message.endsWith(AVDEVICE_CLASS)));
      if (!avdeviceOnly) {
        final String reason = exception.getMessage();
        final String message = "Failed with \"%s\" while loading FFmpeg!".formatted(reason);
        throw new NativeLoadingException(message, exception);
      }

      LOGGER.warn("The FFmpeg device library is not available, capture devices will not work.");
    }

    setup.run();
  }

  private static void configureFfmpegLogging() {
    FFmpegLogCallback.set();
    FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR);
  }

  /**
   * Finds or installs VLC. If VLC is not available for this platform or the installation fails, the
   * {@link Capability#VLC} capability is removed.
   *
   * @since 1.0.0
   */
  public void installVlc() {
    this.installVlc(DependencyLoader::startInstallationKit);
  }

  @VisibleForTesting
  void installVlc(final VlcStarter starter) {
    final Object preparation = this.beginPreparation(Capability.VLC);
    this.logger.info("Preparing VLC...");
    final long start = System.currentTimeMillis();
    try {
      starter.start();
      this.recordPreparation(Capability.VLC, preparation, Availability.AVAILABLE);
      final long end = System.currentTimeMillis();
      final long elapsed = end - start;
      this.logger.info("VLC ready in {} ms.", elapsed);
    } catch (final RuntimeException | LinkageError exception) {
      this.recordPreparation(Capability.VLC, preparation, Availability.UNAVAILABLE);
      final boolean cancelled = this.logCancellation(Capability.VLC);
      if (!cancelled) {
        final String reason = exception.getMessage();
        this.logger.warn("VLC is not available because of \"{}\", so VLC players cannot be used.", reason);
      }
    }
  }

  private Object beginPreparation(final Capability capability) {
    final Object preparation = new Object();
    synchronized (this.preparations) {
      this.preparations.put(capability, preparation);
    }

    return preparation;
  }

  private void recordPreparation(final Capability capability, final Object preparation, final Availability availability) {
    synchronized (this.preparations) {
      // Cancellation may return before an uncooperative installer; only the latest attempt owns the capability.
      if (!preparation.equals(this.preparations.get(capability))) {
        return;
      }

      if (availability == Availability.AVAILABLE) {
        this.capabilities.add(capability);
      } else {
        this.capabilities.remove(capability);
      }
    }
  }

  private static void startInstallationKit() {
    final VlcInstallationKit kit = VlcInstallationKit.create();
    final Optional<Path> _ = kit.start();
  }

  // Logs that the installation of a program was cancelled, if the current thread was interrupted. The library
  // interrupts its installation threads when it is released, which makes a running download fail; that failure is
  // expected and not worth a warning.
  private boolean logCancellation(final Capability capability) {
    final Thread currentThread = Thread.currentThread();
    final boolean cancelled = currentThread.isInterrupted();
    if (cancelled) {
      final String name = capability.getDisplayName();
      this.logger.info("Preparing {} was cancelled.", name);
    }

    return cancelled;
  }

  /**
   * Installs yt-dlp. If yt-dlp is not available for this platform or the installation fails, the
   * {@link Capability#YT_DLP} capability is removed.
   *
   * @since 1.0.0
   */
  public void installYtdlp() {
    final YtdlpInstaller installer = YtdlpInstaller.shared();
    this.installYtdlp(installer);
  }

  @VisibleForTesting
  void installYtdlp(final Installer installer) {
    final Object preparation = this.beginPreparation(Capability.YT_DLP);
    final boolean supported = installer.isSupported();
    if (!supported) {
      this.recordPreparation(Capability.YT_DLP, preparation, Availability.UNAVAILABLE);
      final Platform platform = Platform.getCurrentPlatform();
      this.logger.warn("yt-dlp is not available for platform {}, URL parsing cannot be used.", platform);
      return;
    }

    this.logger.info("Preparing yt-dlp...");
    final long start = System.currentTimeMillis();
    try {
      installer.download(FileMode.EXECUTABLE);
      this.recordPreparation(Capability.YT_DLP, preparation, Availability.AVAILABLE);
      final long end = System.currentTimeMillis();
      final long elapsed = end - start;
      this.logger.info("yt-dlp ready in {} ms.", elapsed);
    } catch (final RuntimeException exception) {
      this.recordPreparation(Capability.YT_DLP, preparation, Availability.UNAVAILABLE);
      final boolean cancelled = this.logCancellation(Capability.YT_DLP);
      if (!cancelled) {
        final String reason = exception.getMessage();
        this.logger.warn("yt-dlp could not be installed because of \"{}\", so URL parsing cannot be used.", reason);
      }
    }
  }

  @FunctionalInterface
  interface VlcStarter {
    void start();
  }
}
