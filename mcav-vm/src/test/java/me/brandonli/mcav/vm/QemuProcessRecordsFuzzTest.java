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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

// records lie in the user's home, open to any text; an input's first line names the record, the rest is its content
@Tag("fuzz")
final class QemuProcessRecordsFuzzTest {

  private static final String TEMPORARY_DIRECTORY_PREFIX = "mcav-vm-records-fuzz";
  private static final String RECORD_SUFFIX = ".qemu";
  private static final String NAME_SEPARATOR = "\n";

  private static final Pattern RECORD_NAME = Pattern.compile("[0-9A-Za-z+-]{0,40}");

  private static final Instant STARTED = Instant.parse("2026-10-05T00:00:00Z");

  private static final long SERVER_PROCESS_ID = 1L;

  private static final Duration STOP_TIMEOUT = Duration.ZERO;

  @FuzzTest(maxDuration = "30s")
  void shouldStopOnlyTheProcessARecordNamesAndDeleteItsRecord(final FuzzedDataProvider data) throws IOException {
    final String input = data.consumeRemainingAsAsciiString();
    final int nameEnd = input.indexOf(NAME_SEPARATOR);
    if (nameEnd < 0) {
      return;
    }

    final String name = input.substring(0, nameEnd);
    final Matcher nameMatcher = RECORD_NAME.matcher(name);
    final boolean nameable = nameMatcher.matches();
    if (!nameable) {
      return;
    }

    final String content = input.substring(nameEnd + 1);
    final Path folder = Files.createTempDirectory(TEMPORARY_DIRECTORY_PREFIX);
    final String recordName = "%s%s".formatted(name, RECORD_SUFFIX);
    final Path record = folder.resolve(recordName);
    try {
      Files.writeString(record, content, StandardCharsets.US_ASCII);
      this.checkReap(folder, record, name);
    } finally {
      Files.deleteIfExists(record);
      Files.delete(folder);
    }
  }

  private void checkReap(final Path folder, final Path record, final String name) {
    final List<Long> stopped = new ArrayList<>();
    final ProcessHandle server = new FakeProcessHandle(SERVER_PROCESS_ID, stopped);
    final LongFunction<Optional<ProcessHandle>> lookup = processId -> this.findRunning(processId, stopped);
    final QemuProcessRecords records = new QemuProcessRecords(folder, server, lookup, STOP_TIMEOUT);
    final int stoppedCount = records.reap();
    final List<Long> expected = this.expectedStops(stoppedCount, name);
    assertEquals(expected, stopped, "only the process the record names is stopped");

    final boolean kept = Files.exists(record);
    final boolean stoppedButKept = stoppedCount > 0 && kept;
    assertFalse(stoppedButKept, "the record of a stopped process is deleted");
  }

  private Optional<ProcessHandle> findRunning(final long processId, final List<Long> stopped) {
    final ProcessHandle process = new FakeProcessHandle(processId, stopped);
    return Optional.of(process);
  }

  private List<Long> expectedStops(final int stoppedCount, final String name) {
    if (stoppedCount == 0) {
      return List.of();
    }

    final long processId = Long.parseLong(name);
    return List.of(processId);
  }

  private static final class FakeProcessHandle implements ProcessHandle {

    private final long processId;
    private final List<Long> stopped;

    FakeProcessHandle(final long processId, final List<Long> stopped) {
      this.processId = processId;
      this.stopped = stopped;
    }

    @Override
    public long pid() {
      return this.processId;
    }

    @Override
    public Optional<ProcessHandle> parent() {
      return Optional.empty();
    }

    @Override
    public Stream<ProcessHandle> children() {
      return Stream.empty();
    }

    @Override
    public Stream<ProcessHandle> descendants() {
      return Stream.empty();
    }

    @Override
    public Info info() {
      return new StartedInfo();
    }

    @Override
    public CompletableFuture<ProcessHandle> onExit() {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public boolean supportsNormalTermination() {
      return true;
    }

    @Override
    public boolean destroy() {
      this.stopped.add(this.processId);

      return true;
    }

    @Override
    public boolean destroyForcibly() {
      return this.destroy();
    }

    @Override
    public boolean isAlive() {
      return false;
    }

    @Override
    public int compareTo(final ProcessHandle other) {
      final long otherProcessId = other.pid();
      return Long.compare(this.processId, otherProcessId);
    }
  }

  private static final class StartedInfo implements ProcessHandle.Info {

    @Override
    public Optional<String> command() {
      return Optional.empty();
    }

    @Override
    public Optional<String> commandLine() {
      return Optional.empty();
    }

    @Override
    public Optional<String[]> arguments() {
      return Optional.empty();
    }

    @Override
    public Optional<Instant> startInstant() {
      return Optional.of(STARTED);
    }

    @Override
    public Optional<Duration> totalCpuDuration() {
      return Optional.empty();
    }

    @Override
    public Optional<String> user() {
      return Optional.empty();
    }
  }
}
