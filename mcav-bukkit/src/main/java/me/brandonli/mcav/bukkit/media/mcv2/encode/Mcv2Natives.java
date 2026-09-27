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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Resources;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The optional native accelerator of the live encoder's pixel kernels. A small library per platform ships inside the
 * jar; where one exists for the operating system and processor, the live searches reconstruct, fit and search with it
 * instead of the Java kernels, which compute the same values ({@link NativeKernels}). Everything else stays Java: the
 * decisions, the reference search, the decoder, the frame parser, the verification and the transport.
 *
 * <p>The library is extracted, never from a path any configuration names, into the folder {@link #install} is given -
 * a plugin's data folder, not {@code /tmp}, which hosted servers often mount without execution - under a name that
 * holds its SHA-256, so a library still loaded elsewhere is never overwritten, and it is checked against the SHA-256
 * compiled into this class before it is loaded. {@code auto} (the default) uses it, {@code off} never does; the system
 * property {@value #PROPERTY} wins over the configured value. Whatever fails - no library for the platform, a checksum,
 * the extraction, the load, the JVM refusing native access - is logged once, and the Java kernels run. Which kernels
 * run is logged once, when the first live encoder needs them, and {@link #describe()} tells it.
 */
public final class Mcv2Natives {

  /** The system property that turns the native kernels on ({@value #AUTO}) or off ({@value #OFF}) over the configuration. */
  public static final String PROPERTY = "mcv2.native";

  /** Uses the native kernels where a library loads: the default. */
  public static final String AUTO = "auto";

  /** Always runs the Java kernels. */
  public static final String OFF = "off";

  /**
   * The system property naming the highest level to use, for measurements: scalar, sse2, sse41, avx2, avx512, neon,
   * sve256 or sve512.
   */
  static final String LEVEL_PROPERTY = "mcv2.native.level";

  /** The library interface these bindings are written for, {@code MCV2_ABI}. */
  static final int ABI = 2;

  /** Where Linux gives a process its auxiliary vector, which holds the processor's features. */
  static final Path AUXV = Path.of("/proc/self/auxv");

  /** The SHA-256 of each platform's library in the jar, which must match before it is loaded. */
  static final Map<String, String> DIGESTS = Map.of(
    "linux-aarch64",
    "caf5e65b587c0b360528a3ec5d942d6d8fa5cecb75760005dfe86ac5a0f7c64b",
    "linux-x86_64",
    "00109fa1b7b477a4f65ea102bded265edf32a4ee51a06dfaa59682f17d84a4ec",
    "macos-aarch64",
    "c8696a7661e64b418c2f8e8cc5799e48f2bfa9e73bc27bdefb9dbaf149ae60d8",
    "macos-x86_64",
    "5e99dda5e4ef2395f2c9814bdaba394db9d04291ffbc29ecde1ca6925341cd99",
    "windows-aarch64",
    "ee879280dba2396af4a007f45060b716bab61e16a3bdf66f3d4d87839b2c444b",
    "windows-x86_64",
    "5422771b3b3cde3badce82db482d1bc0e99d54d1c1c755c7598f02e3009dcbe0"
  );

  /**
   * The levels in order of preference, each architecture's widest first: the first the processor runs is the one used.
   * Scalar runs only when asked for, as every processor runs SSE2 or NEON.
   */
  private static final NativeKernels.Level[] PREFERENCE = {
    NativeKernels.Level.AVX512,
    NativeKernels.Level.AVX2,
    NativeKernels.Level.SSE41,
    NativeKernels.Level.SSE2,
    NativeKernels.Level.SVE512,
    NativeKernels.Level.SVE256,
    NativeKernels.Level.NEON,
    NativeKernels.Level.SCALAR,
  };

  /** The auxiliary vector's entry of the processor's features, {@code AT_HWCAP}. */
  private static final long AT_HWCAP = 16;

  private static final FunctionDescriptor QUERY = FunctionDescriptor.of(JAVA_INT);

  private static final FunctionDescriptor LEVELS = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);

  private static final Logger LOGGER = LoggerFactory.getLogger(Mcv2Natives.class);

  private static final String ACTIVE = "MCV2 kernels: {}";

  private static final String FAILED = "MCV2 native kernels did not load, so the Java kernels run: {}";

  private static @Nullable Path directory;

  private static String configured = AUTO;

  private static @Nullable Resolution resolution;

  /**
   * Which kernels the live encoders use.
   *
   * @param binding     the native kernels' binding, or null for the Java kernels
   * @param library     the loaded library, or null
   * @param levels      the levels the processor runs, as the library's bits, or 0 without a library
   * @param description what is active and why, as logged
   * @param failed      whether a library should have loaded and did not
   */
  record Resolution(
    NativeKernels.@Nullable Binding binding,
    @Nullable SymbolLookup library,
    int levels,
    String description,
    boolean failed
  ) {
    /**
     * Gets the factory of the kernels of a live search's coders.
     *
     * @return the factory
     */
    Kernels.Factory factory() {
      final NativeKernels.Binding bound = this.binding;
      return bound == null ? JavaKernels.FACTORY : () -> new NativeKernels(bound);
    }

    /** The Java kernels, and why. */
    static Resolution java(final String reason, final boolean failed) {
      return new Resolution(null, null, 0, "Java, " + reason, failed);
    }
  }

  private Mcv2Natives() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gives the native kernels a folder to extract their library into and the configured mode; a plugin calls this once
   * as it starts, before any live encoder runs. Which kernels run is decided again at the next live encoder.
   *
   * @param folder the folder, such as the plugin's data folder; created if missing
   * @param mode   {@value #AUTO} or {@value #OFF}; the system property {@value #PROPERTY} wins over it
   * @throws IllegalArgumentException if the mode is neither
   */
  public static synchronized void install(final Path folder, final String mode) {
    Preconditions.checkArgument(AUTO.equals(mode) || OFF.equals(mode), "The MCV2 native mode must be auto or off");
    directory = folder;
    configured = mode;
    resolution = null;
  }

  /**
   * Tells which kernels the live encoders use and why, deciding it if no live encoder has yet: for example
   * {@code native avx2 (linux-x86_64)} or {@code Java, turned off by mcv2.native}.
   *
   * @return the description, as logged
   */
  public static synchronized String describe() {
    return resolved().description();
  }

  /**
   * Gets the factory of the kernels a live encoder's coders use.
   *
   * @return the native kernels' factory where the library loaded, else the Java kernels'
   */
  static synchronized Kernels.Factory factory() {
    return resolved().factory();
  }

  /**
   * Gets what the kernels resolved to, resolving and logging it once.
   *
   * @return the resolution
   */
  static synchronized Resolution resolved() {
    Resolution current = resolution;
    if (current == null) {
      current = resolve(
        System.getProperty(PROPERTY, configured),
        platform(System.getProperty("os.name", ""), System.getProperty("os.arch", "")),
        directory,
        platform -> read(Mcv2Natives.class.getResourceAsStream("natives/" + platform + "/" + libraryName(platform))),
        System.getProperty(LEVEL_PROPERTY)
      );
      if (current.failed()) {
        LOGGER.warn(FAILED, current.description());
      }
      LOGGER.info(ACTIVE, current.description());
      resolution = current;
    }
    return current;
  }

  /**
   * Names the platform the jar's libraries are built for, such as {@code linux-x86_64}.
   *
   * @param osName the {@code os.name} property
   * @param osArch the {@code os.arch} property
   * @return the platform, or null for an operating system or processor no library is built for
   */
  static @Nullable String platform(final String osName, final String osArch) {
    final String name = osName.toLowerCase(Locale.ROOT);
    final String os;
    if (name.startsWith("linux")) {
      os = "linux";
    } else if (name.startsWith("windows")) {
      os = "windows";
    } else if (name.startsWith("mac")) {
      os = "macos";
    } else {
      return null;
    }
    return switch (osArch.toLowerCase(Locale.ROOT)) {
      case "amd64", "x86_64" -> os + "-x86_64";
      case "aarch64", "arm64" -> os + "-aarch64";
      default -> null;
    };
  }

  /**
   * Gets the file name of a platform's library.
   *
   * @param platform the platform
   * @return the file name inside the jar
   */
  static String libraryName(final String platform) {
    if (platform.startsWith("windows")) {
      return "mcv2kernels.dll";
    }
    return platform.startsWith("macos") ? "libmcv2kernels.dylib" : "libmcv2kernels.so";
  }

  /**
   * Reads a library from the jar.
   *
   * @param stream the resource, or null when the jar has none
   * @return its bytes, or null when there is none
   * @throws UncheckedIOException if it cannot be read
   */
  static byte@Nullable[] read(final @Nullable InputStream stream) {
    if (stream == null) {
      return null;
    }
    try (stream) {
      return stream.readAllBytes();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Decides which kernels run, loading the library when the mode and platform allow it.
   *
   * @param mode      the mode, {@value #AUTO} or {@value #OFF}
   * @param platform  the platform, or null when none has a library
   * @param folder    the folder to extract into, or null when none was given
   * @param resources reads a platform's library, or gives null when the jar has none
   * @param highest   the highest level to use, or null for the best the processor runs
   * @return the resolution
   */
  static Resolution resolve(
    final String mode,
    final @Nullable String platform,
    final @Nullable Path folder,
    final Function<String, byte@Nullable[]> resources,
    final @Nullable String highest
  ) {
    if (OFF.equals(mode)) {
      return Resolution.java("turned off by " + PROPERTY + "=" + OFF, false);
    }
    if (!AUTO.equals(mode)) {
      return Resolution.java("unknown mode " + PROPERTY + "=" + mode, true);
    }
    if (platform == null) {
      return Resolution.java("no library for this operating system and processor", false);
    }
    final String digest = DIGESTS.get(platform);
    final byte[] bytes;
    try {
      bytes = digest == null ? null : resources.apply(platform);
    } catch (final UncheckedIOException exception) {
      return Resolution.java("the library could not be read: " + exception, true);
    }
    if (digest == null || bytes == null) {
      return Resolution.java("no library for " + platform, false);
    }
    return load(platform, digest, bytes, folder, highest);
  }

  /**
   * Checks, extracts, loads and binds a platform's library.
   *
   * @param platform the platform
   * @param digest   the SHA-256 the library must have
   * @param bytes    the library
   * @param folder   the folder to extract into, or null when none was given
   * @param highest  the highest level to use, or null for the best the processor runs
   * @return the resolution
   */
  // loads only the library the jar ships, checked against the digest compiled in
  @SuppressWarnings("restricted")
  static Resolution load(
    final String platform,
    final String digest,
    final byte[] bytes,
    final @Nullable Path folder,
    final @Nullable String highest
  ) {
    if (!digest.equals(Mcv2Resources.sha256(bytes))) {
      return Resolution.java("the library for " + platform + " does not match its SHA-256", true);
    }
    if (folder == null) {
      return Resolution.java("no folder was given to extract the library into", true);
    }
    final Path file;
    try {
      file = extract(folder, "mcv2kernels-" + digest + "-" + libraryName(platform), bytes, digest);
    } catch (final IOException exception) {
      return Resolution.java("the library could not be extracted: " + exception, true);
    }
    final SymbolLookup library;
    try {
      library = SymbolLookup.libraryLookup(file, Arena.global());
    } catch (final IllegalCallerException | IllegalArgumentException exception) {
      return Resolution.java("the library could not be loaded: " + exception, true);
    }
    return bind(library, platform, highest);
  }

  /**
   * Writes a checked library into a folder under a name holding its SHA-256, unless a file with that name and content
   * is there already.
   *
   * @param folder the folder, created if missing
   * @param name   the file name
   * @param bytes  the library, whose SHA-256 is checked already
   * @param digest its SHA-256
   * @return the file
   * @throws IOException if the file cannot be written
   */
  static Path extract(final Path folder, final String name, final byte[] bytes, final String digest) throws IOException {
    Files.createDirectories(folder);
    final Path file = folder.resolve(name);
    if (Files.isRegularFile(file) && digest.equals(Mcv2Resources.sha256(Files.readAllBytes(file)))) {
      return file;
    }
    // written beside it and moved into place, so no reader ever sees a partial library
    final Path partial = Files.createTempFile(folder, name, ".partial");
    try {
      Files.write(partial, bytes);
      Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(partial);
    }
    return file;
  }

  /**
   * Binds a loaded library: checks its interface version and binds the best level the processor runs.
   *
   * @param library  the library's symbols
   * @param platform the platform, for the description; a Linux library is given the processor's features
   * @param highest  the highest level to use, or null for the best the processor runs
   * @return the resolution
   */
  static Resolution bind(final SymbolLookup library, final String platform, final @Nullable String highest) {
    return bind(library, platform, highest, ABI);
  }

  /**
   * Binds a loaded library written for an interface: checks its interface version and binds the best level the
   * processor runs.
   *
   * @param library  the library's symbols
   * @param platform the platform, for the description; a Linux library is given the processor's features
   * @param highest  the highest level to use, or null for the best the processor runs
   * @param expected the interface the library must have
   * @return the resolution
   */
  // the levels of the checked library the loader just loaded
  @SuppressWarnings("restricted")
  static Resolution bind(final SymbolLookup library, final String platform, final @Nullable String highest, final int expected) {
    final Linker linker = Linker.nativeLinker();
    try {
      final int abi = query(linker, library, "mcv2_abi");
      if (abi != expected) {
        return Resolution.java("the library's interface " + abi + " is not " + expected, true);
      }
      final long features = platform.startsWith("linux") ? hwcap(AUXV) : 0;
      final MethodHandle cpuLevels = MethodHandles.insertArguments(
        linker.downcallHandle(library.findOrThrow("mcv2_cpu_levels"), LEVELS),
        0,
        features
      );
      final int levels = (int) cpuLevels.invokeExact();
      final NativeKernels.Level level = level(levels, highest);
      final NativeKernels.Binding binding = NativeKernels.Binding.of(library, level);
      return new Resolution(binding, library, levels, "native " + level.symbol() + " (" + platform + ")", false);
    } catch (final Throwable exception) {
      // a missing symbol, or a JVM that refuses native access
      return Resolution.java("the library could not be bound: " + exception, true);
    }
  }

  /**
   * Reads the processor's features the Linux kernel gives this process, {@code AT_HWCAP}: they tell an AArch64
   * library whether SVE may run, which it cannot ask itself, as it imports nothing.
   *
   * @param auxv the process's auxiliary vector, {@link #AUXV}
   * @return the features, or 0 where the vector cannot be read or has none
   */
  static long hwcap(final Path auxv) {
    final byte[] bytes;
    try {
      bytes = Files.readAllBytes(auxv);
    } catch (final IOException exception) {
      return 0;
    }
    // pairs of a type and a value, in machine words
    final ByteBuffer entries = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
    while (entries.remaining() >= 2 * Long.BYTES) {
      final long type = entries.getLong();
      final long value = entries.getLong();
      if (type == AT_HWCAP) {
        return value;
      }
    }
    return 0;
  }

  /** Calls a library function without arguments that returns an int. */
  // a query of the checked library the loader just loaded
  @SuppressWarnings("restricted")
  private static int query(final Linker linker, final SymbolLookup library, final String name) throws Throwable {
    final MethodHandle handle = linker.downcallHandle(library.findOrThrow(name), QUERY);
    return (int) handle.invokeExact();
  }

  /**
   * Chooses the level to use: the first of the preference the processor runs, at most the highest one asked for.
   *
   * @param levels  the levels the processor runs, as the library's bits; scalar always among them
   * @param highest the highest level to use, or null for any
   * @return the level
   */
  static NativeKernels.Level level(final int levels, final @Nullable String highest) {
    boolean allowed = highest == null;
    for (final NativeKernels.Level level : PREFERENCE) {
      allowed |= level.symbol().equals(highest);
      if (allowed && level.in(levels)) {
        return level;
      }
    }
    return NativeKernels.Level.SCALAR;
  }
}
