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
package me.brandonli.mcav.browser;

import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import me.brandonli.mcav.media.player.PlayerException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A private X display for one browser helper on Linux.
 *
 * <p>JCEF creates a small X11 window when CEF starts, even for a browser that never shows one, so CEF on Linux needs
 * an X server; Chromium itself runs with its headless platform. A server has no display, so every helper gets its own
 * {@code Xvfb}, which listens on no TCP port and accepts only clients that know the random cookie written into its
 * authority file, which only the owner can read. The display number is chosen by Xvfb ({@code -displayfd}), and Xvfb
 * exits when its last client disconnects ({@code -terminate}), besides being stopped by {@link #close()}.
 */
final class XvfbDisplay implements AutoCloseable {

  /**
   * The name of the program.
   */
  static final String PROGRAM = "Xvfb";

  private static final long START_TIMEOUT_MILLIS = 15_000L;
  private static final long STOP_TIMEOUT_MILLIS = 5_000L;
  private static final int COOKIE_BYTES = 16;
  private static final int FAMILY_WILD = 0xFFFF;
  private static final String COOKIE_NAME = "MIT-MAGIC-COOKIE-1";
  private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

  private final Process process;
  private final String display;
  private final Path authority;

  private XvfbDisplay(final Process process, final String display, final Path authority) {
    this.process = process;
    this.display = display;
    this.authority = authority;
  }

  /**
   * Finds Xvfb on the {@code PATH}.
   *
   * @param path the value of the {@code PATH} variable, or null if it is not set
   * @return the program, or empty if it is not installed
   */
  static Optional<Path> find(final @Nullable String path) {
    if (path == null) {
      return Optional.empty();
    }
    final String separator = Pattern.quote(File.pathSeparator);
    final String[] folders = path.split(separator, -1);
    for (final String folder : folders) {
      if (folder.isEmpty()) {
        continue;
      }
      final Path candidate = Path.of(folder, PROGRAM);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /**
   * Starts a display.
   *
   * @param program the Xvfb program
   * @param folder  the private folder of the session, where the authority file is written
   * @return the running display
   * @throws PlayerException if Xvfb cannot be started or does not report its display in time
   */
  static XvfbDisplay start(final Path program, final Path folder) {
    final Path authority = folder.resolve("Xauthority");
    try {
      writeAuthority(authority, createCookie());
    } catch (final IOException exception) {
      throw new PlayerException("The X authority file cannot be written: " + exception.getMessage(), exception);
    }
    final List<String> command = createCommand(program, authority);
    final ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.redirectInput(ProcessBuilder.Redirect.PIPE);
    final Process process;
    try {
      process = builder.start();
    } catch (final IOException exception) {
      throw new PlayerException("Xvfb cannot be started: " + exception.getMessage(), exception);
    }
    try {
      final String number = readDisplayNumber(process.getInputStream(), START_TIMEOUT_MILLIS);
      return new XvfbDisplay(process, ":" + number, authority);
    } catch (final PlayerException exception) {
      stopProcess(process, STOP_TIMEOUT_MILLIS);
      throw exception;
    }
  }

  /**
   * Builds the command line of Xvfb.
   *
   * @param program   the program
   * @param authority the authority file
   * @return the command line
   */
  @VisibleForTesting
  static List<String> createCommand(final Path program, final Path authority) {
    final String programPath = program.toString();
    final String authorityPath = authority.toString();
    return List.of(programPath, "-displayfd", "1", "-auth", authorityPath, "-nolisten", "tcp", "-screen", "0", "16x16x24", "-terminate");
  }

  private static byte[] createCookie() {
    final SecureRandom random = new SecureRandom();
    final byte[] cookie = new byte[COOKIE_BYTES];
    random.nextBytes(cookie);
    return cookie;
  }

  /**
   * Writes an X authority file with one cookie that matches every display, readable by the owner only.
   *
   * @param authority the file
   * @param cookie    the cookie
   * @throws IOException if the file cannot be written
   */
  @VisibleForTesting
  static void writeAuthority(final Path authority, final byte[] cookie) throws IOException {
    final byte[] entry = createAuthorityEntry(cookie);
    final FileAttribute<Set<PosixFilePermission>> ownerOnly = PosixFilePermissions.asFileAttribute(OWNER_ONLY);
    Files.createFile(authority, ownerOnly);
    Files.write(authority, entry);
  }

  /**
   * Encodes one entry of an X authority file: family, address, display number, method name and cookie, each counted
   * with a big-endian 16-bit length. The family matches every host and the empty display number every display.
   *
   * @param cookie the cookie
   * @return the entry
   */
  @VisibleForTesting
  static byte[] createAuthorityEntry(final byte[] cookie) {
    final byte[] name = COOKIE_NAME.getBytes(StandardCharsets.US_ASCII);
    // family, an empty address, an empty display number, then the name and the data, each counted, big-endian
    final ByteBuffer entry = ByteBuffer.allocate(2 + 2 + 2 + 2 + name.length + 2 + cookie.length);
    entry.putShort((short) FAMILY_WILD);
    entry.putShort((short) 0);
    entry.putShort((short) 0);
    entry.putShort((short) name.length);
    entry.put(name);
    entry.putShort((short) cookie.length);
    entry.put(cookie);
    return entry.array();
  }

  /**
   * Reads the display number Xvfb writes once it accepts connections.
   *
   * @param output        the standard output of Xvfb
   * @param timeoutMillis how long to wait
   * @return the display number
   * @throws PlayerException if Xvfb exits, writes something else or takes too long
   */
  @VisibleForTesting
  static String readDisplayNumber(final InputStream output, final long timeoutMillis) {
    final CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> readLine(output));
    try {
      final String number = line.get(timeoutMillis, TimeUnit.MILLISECONDS);
      if (!number.matches("\\d{1,5}")) {
        throw new PlayerException("Xvfb reported no display number: " + number);
      }
      return number;
    } catch (final InterruptedException exception) {
      final Thread thread = Thread.currentThread();
      thread.interrupt();
      throw new PlayerException("Interrupted while waiting for Xvfb", exception);
    } catch (final ExecutionException | TimeoutException exception) {
      throw new PlayerException("Xvfb did not report a display within " + timeoutMillis + " ms", exception);
    }
  }

  private static String readLine(final InputStream output) {
    final StringBuilder line = new StringBuilder();
    try {
      int next = output.read();
      while (next >= 0 && next != '\n' && line.length() < 16) {
        line.append((char) next);
        next = output.read();
      }
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
    return line.toString().trim();
  }

  /**
   * Gets the display, such as {@code :1}, for the {@code DISPLAY} variable.
   *
   * @return the display
   */
  String getDisplay() {
    return this.display;
  }

  /**
   * Gets the authority file, for the {@code XAUTHORITY} variable.
   *
   * @return the file
   */
  Path getAuthority() {
    return this.authority;
  }

  /**
   * Stops Xvfb, forcibly if it does not stop in time.
   */
  @Override
  public void close() {
    stopProcess(this.process, STOP_TIMEOUT_MILLIS);
  }

  /**
   * Stops a process, forcibly if it does not stop in time; an interrupt stops it forcibly at once and is kept.
   *
   * @param process       the process
   * @param timeoutMillis how long each stop may take
   */
  @VisibleForTesting
  static void stopProcess(final Process process, final long timeoutMillis) {
    process.destroy();
    try {
      final boolean exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
      }
    } catch (final InterruptedException exception) {
      process.destroyForcibly();
      final Thread thread = Thread.currentThread();
      thread.interrupt();
    }
  }
}
