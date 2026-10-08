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
package me.brandonli.mcav.vm;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import me.brandonli.mcav.utils.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the QEMU processes a JVM starts, so that a later start of the module stops those a killed JVM left
 * running.
 *
 * <p>QEMU is a process of its own: a JVM that is killed (a SIGKILL, a crash, the out-of-memory killer) cannot stop
 * it, and it keeps its memory and its VNC port. Every started process gets a file in the records folder, named after
 * its process id, with its start instant and the process id and start instant of the JVM that started it; the file is
 * deleted when the process ends. {@link #reap()} stops every recorded process whose JVM is gone while the process is
 * still the one recorded (the same start instant, so a reused process id is never touched), and deletes the records
 * of processes that ended.
 */
final class QemuProcessRecords {

  private static final Logger LOGGER = LoggerFactory.getLogger(QemuProcessRecords.class);
  private static final String STOPPED = "Stopped QEMU process {}, which a server that ended without releasing it left running";
  private static final String NOT_RECORDED = "QEMU process {} could not be recorded; if this server is killed, it keeps running";
  private static final String NOT_FORGOTTEN = "The record of QEMU process {} could not be deleted";
  private static final String NOT_READ = "The records of QEMU processes in {} could not be read";
  private static final String RECORD_NOT_READ = "The record {} of a QEMU process could not be read";
  private static final String SUFFIX = ".qemu";
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

  private final Path folder;
  private final ProcessHandle owner;
  private final LongFunction<Optional<ProcessHandle>> lookup;
  private final Duration stopTimeout;

  /**
   * Creates the records of the user running the JVM, in {@code ~/.mcav/vm}.
   *
   * @return the records
   */
  static QemuProcessRecords ofUser() {
    final String home = System.getProperty("user.home");
    final Path folder = Path.of(home, ".mcav", "vm");
    return new QemuProcessRecords(folder, ProcessHandle.current(), ProcessHandle::of, STOP_TIMEOUT);
  }

  /**
   * Creates records in a folder.
   *
   * @param folder the folder of the record files
   * @param owner       the process that starts QEMU, this JVM outside of tests
   * @param lookup      finds a running process by its id
   * @param stopTimeout how long a process may take to end once asked to, before it is killed
   */
  @VisibleForTesting
  QemuProcessRecords(
    final Path folder,
    final ProcessHandle owner,
    final LongFunction<Optional<ProcessHandle>> lookup,
    final Duration stopTimeout
  ) {
    this.folder = folder;
    this.owner = owner;
    this.lookup = lookup;
    this.stopTimeout = stopTimeout;
  }

  /**
   * Records a started process. A process whose start instant the system does not report cannot be told from a later
   * process with its id, so it is not recorded; nor is one that could not be written, which is logged.
   *
   * @param process the started process
   */
  void add(final ProcessHandle process) {
    final long pid = process.pid();
    final Optional<Instant> started = startOf(process);
    final Optional<Instant> ownerStarted = startOf(this.owner);
    if (started.isEmpty() || ownerStarted.isEmpty()) {
      LOGGER.warn(NOT_RECORDED, pid);
      return;
    }
    final String record = started.get() + "\n" + this.owner.pid() + "\n" + ownerStarted.get() + "\n";
    try {
      Files.createDirectories(this.folder);
      Files.writeString(this.fileOf(pid), record, StandardCharsets.US_ASCII);
    } catch (final IOException exception) {
      LOGGER.warn(NOT_RECORDED, pid, exception);
    }
  }

  /**
   * Deletes the record of a process that ended.
   *
   * @param pid the process id
   */
  void remove(final long pid) {
    try {
      Files.deleteIfExists(this.fileOf(pid));
    } catch (final IOException exception) {
      LOGGER.warn(NOT_FORGOTTEN, pid, exception);
    }
  }

  /**
   * Stops every recorded process whose JVM is gone and that is still the recorded process, and deletes every record
   * that no longer belongs to a running JVM.
   *
   * @return how many processes were stopped
   */
  int reap() {
    if (!Files.isDirectory(this.folder)) {
      return 0;
    }
    int stopped = 0;
    try (final DirectoryStream<Path> records = Files.newDirectoryStream(this.folder, "*" + SUFFIX)) {
      for (final Path record : records) {
        stopped += this.reapRecord(record);
      }
    } catch (final IOException exception) {
      LOGGER.warn(NOT_READ, this.folder, exception);
    }
    return stopped;
  }

  private int reapRecord(final Path record) {
    try {
      return this.reapOne(record);
    } catch (final IOException exception) {
      LOGGER.warn(RECORD_NOT_READ, record, exception);
      return 0;
    }
  }

  private int reapOne(final Path record) throws IOException {
    final Optional<ProcessRecord> parsed = parse(record);
    if (parsed.isPresent() && this.isRunning(parsed.get().ownerPid(), parsed.get().ownerStarted())) {
      return 0;
    }
    int stopped = 0;
    if (parsed.isPresent()) {
      final ProcessRecord found = parsed.get();
      final Optional<ProcessHandle> process = this.lookup
        .apply(found.pid())
        .filter(handle -> startOf(handle).equals(Optional.of(found.started())));
      if (process.isPresent()) {
        this.stop(process.get());
        stopped = 1;
      }
    }
    Files.deleteIfExists(record);
    return stopped;
  }

  private boolean isRunning(final long pid, final Instant started) {
    final Optional<ProcessHandle> process = this.lookup.apply(pid);
    return process.isPresent() && startOf(process.get()).equals(Optional.of(started));
  }

  private void stop(final ProcessHandle process) {
    process.destroy();
    final CompletableFuture<ProcessHandle> exit = process.onExit();
    final long timeout = this.stopTimeout.toMillis();
    exit.completeOnTimeout(process, timeout, TimeUnit.MILLISECONDS).join();
    if (process.isAlive()) {
      process.destroyForcibly();
    }
    final long pid = process.pid();
    LOGGER.warn(STOPPED, pid);
  }

  private static Optional<Instant> startOf(final ProcessHandle process) {
    final ProcessHandle.Info info = process.info();
    return info.startInstant();
  }

  private static Optional<ProcessRecord> parse(final Path record) throws IOException {
    final String name = IOUtils.getName(record);
    final List<String> lines = Files.readAllLines(record, StandardCharsets.US_ASCII);
    try {
      final long pid = Long.parseLong(name.substring(0, name.length() - SUFFIX.length()));
      final Instant started = Instant.parse(lines.get(0));
      final long ownerPid = Long.parseLong(lines.get(1));
      final Instant ownerStarted = Instant.parse(lines.get(2));
      return Optional.of(new ProcessRecord(pid, started, ownerPid, ownerStarted));
    } catch (final NumberFormatException | DateTimeParseException | IndexOutOfBoundsException exception) {
      return Optional.empty();
    }
  }

  private Path fileOf(final long pid) {
    return this.folder.resolve(pid + SUFFIX);
  }

  /**
   * A recorded process: its id and start instant, and those of the JVM that started it.
   *
   * @param pid          the process id
   * @param started      when the process started
   * @param ownerPid     the process id of the JVM that started it
   * @param ownerStarted when that JVM started
   */
  private record ProcessRecord(long pid, Instant started, long ownerPid, Instant ownerStarted) {}
}
