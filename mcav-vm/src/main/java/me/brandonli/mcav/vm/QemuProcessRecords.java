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

import static java.util.Objects.requireNonNull;

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
import me.brandonli.mcav.utils.IoUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// A killed JVM cannot stop its QEMU processes, which keep their memory and VNC port, so each one is recorded with its
// start instant and that of its JVM. reap() stops those whose JVM is gone; a matching start instant keeps a reused id safe.
final class QemuProcessRecords {

  private static final Logger LOGGER = LoggerFactory.getLogger(QemuProcessRecords.class);

  private static final String NOT_RECORDED = "QEMU process {} could not be recorded, so it keeps running if this server is killed.";

  private static final String USER_HOME_PROPERTY = "user.home";
  private static final String MCAV_FOLDER = ".mcav";
  private static final String RECORDS_FOLDER = "vm";

  private static final String SUFFIX = ".qemu";
  private static final String RECORD_GLOB = "*%s".formatted(SUFFIX);

  private static final int STARTED_LINE = 0;
  private static final int OWNER_PROCESS_ID_LINE = 1;
  private static final int OWNER_STARTED_LINE = 2;

  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

  private final Path folder;
  private final ProcessHandle owner;
  private final LongFunction<Optional<ProcessHandle>> lookup;
  private final Duration stopTimeout;

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

  static QemuProcessRecords ofUser() {
    final @Nullable String homeOrNull = System.getProperty(USER_HOME_PROPERTY);
    final String home = requireNonNull(homeOrNull, "The JVM always sets the user home!");
    final Path folder = Path.of(home, MCAV_FOLDER, RECORDS_FOLDER);
    final ProcessHandle owner = ProcessHandle.current();
    return new QemuProcessRecords(folder, owner, ProcessHandle::of, STOP_TIMEOUT);
  }

  // without start instants a reused process id could not be told apart, so the process is not recorded
  void add(final ProcessHandle process) {
    final long processId = process.pid();
    final @Nullable Instant started = this.startOf(process);
    final @Nullable Instant ownerStarted = this.startOf(this.owner);
    if (started == null || ownerStarted == null) {
      LOGGER.warn(NOT_RECORDED, processId);
      return;
    }

    final long ownerProcessId = this.owner.pid();
    final String record = "%s\n%s\n%s\n".formatted(started, ownerProcessId, ownerStarted);
    final Path file = this.fileOf(processId);
    try {
      Files.createDirectories(this.folder);
      Files.writeString(file, record, StandardCharsets.US_ASCII);
    } catch (final IOException exception) {
      LOGGER.warn(NOT_RECORDED, processId, exception);
    }
  }

  void remove(final long processId) {
    final Path file = this.fileOf(processId);
    try {
      Files.deleteIfExists(file);
    } catch (final IOException exception) {
      LOGGER.warn("The record of QEMU process {} could not be deleted.", processId, exception);
    }
  }

  int reap() {
    final boolean folderExists = Files.isDirectory(this.folder);
    if (!folderExists) {
      return 0;
    }

    int stopped = 0;
    try (final DirectoryStream<Path> records = Files.newDirectoryStream(this.folder, RECORD_GLOB)) {
      for (final Path record : records) {
        stopped += this.reapRecord(record);
      }
    } catch (final IOException exception) {
      LOGGER.warn("The records of QEMU processes in {} could not be read.", this.folder, exception);
    }

    return stopped;
  }

  private int reapRecord(final Path record) {
    try {
      return this.reapOne(record);
    } catch (final IOException exception) {
      LOGGER.warn("The record {} of a QEMU process could not be read or deleted.", record, exception);
      return 0;
    }
  }

  private int reapOne(final Path record) throws IOException {
    final Optional<ProcessRecord> parsed = this.parse(record);
    final boolean ownerRunning = parsed.filter(this::isOwnerRunning).isPresent();
    if (ownerRunning) {
      return 0;
    }

    final Optional<ProcessHandle> process = parsed.flatMap(this::findRecordedProcess);
    process.ifPresent(this::stop);
    Files.deleteIfExists(record);

    return process.isPresent() ? 1 : 0;
  }

  private boolean isOwnerRunning(final ProcessRecord record) {
    final long ownerProcessId = record.ownerProcessId();
    final Instant ownerStarted = record.ownerStarted();
    final Optional<ProcessHandle> owner = this.findProcess(ownerProcessId, ownerStarted);
    return owner.isPresent();
  }

  private Optional<ProcessHandle> findRecordedProcess(final ProcessRecord record) {
    final long processId = record.processId();
    final Instant started = record.started();
    return this.findProcess(processId, started);
  }

  private Optional<ProcessHandle> findProcess(final long processId, final Instant started) {
    final Optional<ProcessHandle> process = this.lookup.apply(processId);
    return process.filter(handle -> this.isStartedAt(handle, started));
  }

  private boolean isStartedAt(final ProcessHandle process, final Instant started) {
    final @Nullable Instant start = this.startOf(process);
    return started.equals(start);
  }

  private void stop(final ProcessHandle process) {
    process.destroy();

    final CompletableFuture<ProcessHandle> exit = process.onExit();
    final long timeout = this.stopTimeout.toMillis();
    exit.completeOnTimeout(process, timeout, TimeUnit.MILLISECONDS).join();

    final boolean alive = process.isAlive();
    if (alive) {
      process.destroyForcibly();
    }

    final long processId = process.pid();
    LOGGER.warn("Stopped QEMU process {}, which a server that ended without releasing it left running.", processId);
  }

  private @Nullable Instant startOf(final ProcessHandle process) {
    final ProcessHandle.Info information = process.info();
    final Optional<Instant> start = information.startInstant();
    return start.orElse(null);
  }

  private Optional<ProcessRecord> parse(final Path record) throws IOException {
    final String name = IoUtils.getName(record);
    final List<String> lines = Files.readAllLines(record, StandardCharsets.US_ASCII);
    try {
      final int processIdLength = name.length() - SUFFIX.length();
      final String processIdText = name.substring(0, processIdLength);
      final long processId = Long.parseLong(processIdText);
      final String startedLine = lines.get(STARTED_LINE);
      final Instant started = Instant.parse(startedLine);
      final String ownerProcessIdLine = lines.get(OWNER_PROCESS_ID_LINE);
      final long ownerProcessId = Long.parseLong(ownerProcessIdLine);
      final String ownerStartedLine = lines.get(OWNER_STARTED_LINE);
      final Instant ownerStarted = Instant.parse(ownerStartedLine);
      final ProcessRecord parsed = new ProcessRecord(processId, started, ownerProcessId, ownerStarted);
      return Optional.of(parsed);
    } catch (final NumberFormatException | DateTimeParseException | IndexOutOfBoundsException exception) {
      return Optional.empty();
    }
  }

  private Path fileOf(final long processId) {
    final String fileName = "%s%s".formatted(processId, SUFFIX);
    return this.folder.resolve(fileName);
  }

  private record ProcessRecord(long processId, Instant started, long ownerProcessId, Instant ownerStarted) {}
}
