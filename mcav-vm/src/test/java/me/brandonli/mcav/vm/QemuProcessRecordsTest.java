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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QemuProcessRecordsTest {

  private static final long GONE = Long.MAX_VALUE;

  private static final String RECORDS_FOLDER = "records";
  private static final String RECORD_SUFFIX = ".qemu";
  private static final String GARBAGE_RECORD = "12.qemu";
  private static final String UNNAMED_RECORD = "abc.qemu";
  private static final String SHORT_RECORD = "13.qemu";
  private static final String UNREADABLE_RECORD = "14.qemu";
  private static final String INSIDE_FOLDER = "inside";
  private static final String UNRELATED_FILE = "notes.txt";

  private static final String SLEEPER_PREFIX = "Sleep";
  private static final String JAVA_SUFFIX = ".java";

  private static final String USER_HOME_PROPERTY = "user.home";
  private static final String MCAV_FOLDER = ".mcav";
  private static final String VM_FOLDER = "vm";

  private static final String RECORD_FORMAT = "%s\n%s\n%s\n";
  private static final String LONG_AGO = "2001-01-01T00:00:00Z";

  private static final String PROCESS_ENDED_MESSAGE = "the process ended";

  private static final Duration STOP_TIMEOUT = Duration.ofMillis(500);

  private static final long TIMEOUT_SECONDS = 10L;

  @TempDir
  private Path directory;

  private final List<Process> children = new ArrayList<>();

  @AfterEach
  void stopChildren() throws InterruptedException {
    for (final Process child : this.children) {
      child.destroyForcibly();
      child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  private Process child(final StopRequest stopRequest) throws IOException {
    final List<String> command = this.sleeper(stopRequest);
    // JVM startup diagnostics are separate from the fixture's readiness protocol.
    final Process process = new ProcessBuilder(command).redirectError(Redirect.INHERIT).start();
    this.children.add(process);
    final InputStream standardOutput = process.getInputStream();
    final byte[] ready = standardOutput.readNBytes(5);
    final String announcement = new String(ready, StandardCharsets.US_ASCII);
    assertEquals("ready", announcement);

    return process;
  }

  private List<String> sleeper(final StopRequest stopRequest) throws IOException {
    final String hook = switch (stopRequest) {
      case OBEYED -> "";
      case IGNORED -> "Runtime.getRuntime().addShutdownHook(new Thread(Sleep::sleep));";
    };
    final String source = """
    class Sleep {
      static void sleep() {
        try {
          Thread.sleep(300_000L);
        } catch (InterruptedException exception) {
        }
      }

      public static void main(String[] arguments) {
        %s
        System.out.println("ready");
        System.out.flush();
        sleep();
      }
    }
    """.formatted(hook);
    final String fileName = "%s%s%s".formatted(SLEEPER_PREFIX, this.children.size(), JAVA_SUFFIX);
    final Path file = this.directory.resolve(fileName);
    Files.writeString(file, source);
    final String java = this.javaCommand();
    return List.of(java, file.toString());
  }

  private String javaCommand() {
    final ProcessHandle current = ProcessHandle.current();
    final ProcessHandle.Info information = current.info();
    final Optional<String> command = information.command();
    return command.orElseThrow();
  }

  private void recordForAGoneServer(final Process qemu) {
    final ProcessHandle server = this.goneServer();
    final QemuProcessRecords records = this.records(server);
    final ProcessHandle handle = qemu.toHandle();
    records.add(handle);
  }

  private ProcessHandle goneServer() {
    final ProcessHandle owner = mock(ProcessHandle.class);
    final ProcessHandle.Info information = mock(ProcessHandle.Info.class);
    when(owner.pid()).thenReturn(GONE);
    when(owner.info()).thenReturn(information);
    when(information.startInstant()).thenReturn(Optional.of(Instant.parse("2026-10-05T00:00:00Z")));
    return owner;
  }

  private QemuProcessRecords records(final ProcessHandle owner) {
    final Path folder = this.directory.resolve(RECORDS_FOLDER);
    return new QemuProcessRecords(folder, owner, ProcessHandle::of, STOP_TIMEOUT);
  }

  private int reapForThisServer() {
    final ProcessHandle current = ProcessHandle.current();
    final QemuProcessRecords records = this.records(current);
    return records.reap();
  }

  private boolean endsWithinTheTimeout(final Process process) {
    final CompletableFuture<Process> exit = process.onExit();
    return exit.completeOnTimeout(null, TIMEOUT_SECONDS, TimeUnit.SECONDS).join() != null;
  }

  private Path recordOf(final long processId) {
    final Path folder = this.directory.resolve(RECORDS_FOLDER);
    final String recordName = "%s%s".formatted(processId, RECORD_SUFFIX);
    return folder.resolve(recordName);
  }

  @Test
  void shouldRecordAProcessTheLauncherStartsUntilItEnds() throws IOException, InterruptedException {
    final VmProcess.RecordingLauncher launcher = new VmProcess.RecordingLauncher(this.records(ProcessHandle.current()), Map.of());
    final Process qemu = launcher.launch(this.sleeper(StopRequest.OBEYED));
    this.children.add(qemu);
    final boolean recorded = Files.isRegularFile(this.recordOf(qemu.pid()));
    qemu.destroyForcibly();
    qemu.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    launcher.onProcessEnded(qemu);

    final boolean recordLeft = Files.exists(this.recordOf(qemu.pid()));
    assertTrue(recorded, "a started QEMU is recorded at once, before anything can kill this JVM");
    assertFalse(recordLeft, "an ended QEMU is forgotten");
  }

  @Test
  void shouldStartQemuWithTheEnvironmentOfItsProgram() {
    final Map<String, String> environment = Map.of("QEMU_MODULE_DIR", "modules");
    final VmProcess.RecordingLauncher launcher = new VmProcess.RecordingLauncher(this.records(ProcessHandle.current()), environment);
    final List<String> command = List.of("qemu-system-x86_64");

    final ProcessBuilder builder = launcher.createBuilder(command);

    final Map<String, String> builderEnvironment = builder.environment();
    final String modules = builderEnvironment.get("QEMU_MODULE_DIR");
    final boolean merged = builder.redirectErrorStream();
    final List<String> builderCommand = builder.command();
    assertEquals("modules", modules);
    assertTrue(merged);
    assertEquals(command, builderCommand);
  }

  @Test
  void shouldStopTheQemuOfAServerThatIsGone() throws IOException {
    final Process qemu = this.child(StopRequest.OBEYED);
    this.recordForAGoneServer(qemu);
    final boolean recorded = Files.exists(this.recordOf(qemu.pid()));
    assertTrue(recorded);

    final int stopped = this.reapForThisServer();

    final boolean ended = this.endsWithinTheTimeout(qemu);
    final boolean recordLeft = Files.exists(this.recordOf(qemu.pid()));
    assertEquals(1, stopped);
    assertTrue(ended, PROCESS_ENDED_MESSAGE);
    assertFalse(recordLeft, "its record is gone");
  }

  @Test
  void shouldKillAQemuThatIgnoresTheRequestToEnd() throws IOException {
    final Process qemu = this.child(StopRequest.IGNORED);
    this.recordForAGoneServer(qemu);

    final int stopped = this.reapForThisServer();

    final boolean killed = this.endsWithinTheTimeout(qemu);
    assertEquals(1, stopped);
    assertTrue(killed, "the process was killed");
  }

  @Test
  void shouldLeaveTheQemuOfAServerThatStillRuns() throws IOException {
    final Process qemu = this.child(StopRequest.OBEYED);
    final QemuProcessRecords records = this.records(ProcessHandle.current());
    records.add(qemu.toHandle());

    final int stopped = records.reap();

    final boolean alive = qemu.isAlive();
    final boolean recorded = Files.exists(this.recordOf(qemu.pid()));
    assertEquals(0, stopped);
    assertTrue(alive, "this server releases it itself");
    assertTrue(recorded);

    records.remove(qemu.pid());
    final boolean recordLeft = Files.exists(this.recordOf(qemu.pid()));
    assertFalse(recordLeft, "a process that ended is forgotten");
  }

  @Test
  void shouldTreatAServerWhoseIdAnotherProcessReusedAsGone() throws IOException {
    final Process qemu = this.child(StopRequest.OBEYED);
    final ProcessHandle handle = qemu.toHandle();
    final ProcessHandle.Info information = handle.info();
    final Optional<Instant> start = information.startInstant();
    final Instant started = start.orElseThrow();
    final Path record = this.recordOf(qemu.pid());
    Files.createDirectories(record.getParent());
    final ProcessHandle current = ProcessHandle.current();
    final long reused = current.pid();
    Files.writeString(record, RECORD_FORMAT.formatted(started, reused, LONG_AGO));

    final int stopped = this.reapForThisServer();

    final boolean ended = this.endsWithinTheTimeout(qemu);
    assertEquals(1, stopped);
    assertTrue(ended, PROCESS_ENDED_MESSAGE);
  }

  @Test
  void shouldNeverTouchAProcessThatReusedTheId() throws IOException {
    final Process other = this.child(StopRequest.OBEYED);
    final Path record = this.recordOf(other.pid());
    Files.createDirectories(record.getParent());
    Files.writeString(record, RECORD_FORMAT.formatted(LONG_AGO, GONE, LONG_AGO));

    final int stopped = this.reapForThisServer();

    final boolean alive = other.isAlive();
    final boolean recordLeft = Files.exists(record);
    assertEquals(0, stopped);
    assertTrue(alive);
    assertFalse(recordLeft, "the record of the process that ended is deleted");
  }

  @Test
  void shouldDeleteRecordsItCannotReadAndKeepReapingTheOthers() throws IOException {
    final Path folder = this.directory.resolve(RECORDS_FOLDER);
    Files.createDirectories(folder);
    final Path garbage = folder.resolve(GARBAGE_RECORD);
    Files.writeString(garbage, "not a record");
    final Path unnamed = folder.resolve(UNNAMED_RECORD);
    Files.writeString(unnamed, RECORD_FORMAT.formatted(LONG_AGO, 1, LONG_AGO));
    final Path shortRecord = folder.resolve(SHORT_RECORD);
    Files.writeString(shortRecord, "%s\n".formatted(LONG_AGO));
    final Path unreadable = folder.resolve(UNREADABLE_RECORD);
    Files.createDirectories(unreadable.resolve(INSIDE_FOLDER));
    final Process qemu = this.child(StopRequest.OBEYED);
    this.recordForAGoneServer(qemu);
    final Path unrelated = folder.resolve(UNRELATED_FILE);
    Files.writeString(unrelated, "kept");

    final int stopped = this.reapForThisServer();

    final boolean garbageLeft = Files.exists(garbage);
    final boolean unnamedLeft = Files.exists(unnamed);
    final boolean shortRecordLeft = Files.exists(shortRecord);
    final boolean unreadableKept = Files.isDirectory(unreadable);
    final boolean unrelatedKept = Files.exists(unrelated);
    assertEquals(1, stopped);
    assertFalse(garbageLeft);
    assertFalse(unnamedLeft);
    assertFalse(shortRecordLeft);
    assertTrue(unreadableKept);
    assertTrue(unrelatedKept, "only records are reaped");
  }

  @Test
  void shouldHaveNothingToReapWithoutAFolder() throws IOException {
    final int stoppedWithoutAFolder = this.reapForThisServer();
    assertEquals(0, stoppedWithoutAFolder);

    Files.writeString(this.directory.resolve(RECORDS_FOLDER), "a file, not a folder");
    final int stoppedWithAFile = this.reapForThisServer();
    assertEquals(0, stoppedWithAFile);
  }

  @Test
  void shouldOnlyLogAFolderItCannotList() throws IOException {
    final Path folder = mock(Path.class);
    final FileSystem fileSystem = mock(FileSystem.class);
    final FileSystemProvider provider = mock(FileSystemProvider.class);
    final BasicFileAttributes attributes = mock(BasicFileAttributes.class);
    final IOException unlistable = new IOException("Permission denied!");
    when(folder.getFileSystem()).thenReturn(fileSystem);
    when(fileSystem.provider()).thenReturn(provider);
    when(provider.readAttributesIfExists(folder, BasicFileAttributes.class)).thenReturn(attributes);
    when(attributes.isDirectory()).thenReturn(true);
    when(provider.newDirectoryStream(eq(folder), any())).thenThrow(unlistable);
    final QemuProcessRecords records = new QemuProcessRecords(folder, ProcessHandle.current(), ProcessHandle::of, STOP_TIMEOUT);
    final int stopped = records.reap();
    assertEquals(0, stopped);
  }

  @Test
  void shouldNotRecordAProcessWithoutAKnownStart() throws IOException {
    final ProcessHandle unknown = mock(ProcessHandle.class);
    final ProcessHandle.Info information = mock(ProcessHandle.Info.class);
    when(unknown.pid()).thenReturn(42L);
    when(unknown.info()).thenReturn(information);
    when(information.startInstant()).thenReturn(Optional.empty());
    final ProcessHandle current = ProcessHandle.current();
    final QemuProcessRecords records = this.records(current);
    records.add(unknown);
    final boolean unknownRecorded = Files.exists(this.recordOf(42L));
    assertFalse(unknownRecorded, "it could not be told from a later process with its id");

    final Process qemu = this.child(StopRequest.OBEYED);
    final QemuProcessRecords recordsOfAnUnknownServer = this.records(unknown);
    final ProcessHandle handle = qemu.toHandle();
    recordsOfAnUnknownServer.add(handle);
    final boolean recorded = Files.exists(this.recordOf(qemu.pid()));
    assertFalse(recorded);
  }

  @Test
  void shouldOnlyLogARecordThatCannotBeWrittenOrDeleted() throws IOException {
    Files.writeString(this.directory.resolve(RECORDS_FOLDER), "a file where the folder belongs");
    final Process qemu = this.child(StopRequest.OBEYED);
    this.recordForAGoneServer(qemu);
    Files.delete(this.directory.resolve(RECORDS_FOLDER));
    final Path record = this.recordOf(7L);
    Files.createDirectories(record.resolve(INSIDE_FOLDER));
    final ProcessHandle current = ProcessHandle.current();
    final QemuProcessRecords records = this.records(current);
    records.remove(7L);
    final boolean kept = Files.isDirectory(record);
    assertTrue(kept, "a folder named like a record is not deleted");
  }

  @Test
  void shouldKeepTheRecordsOfAUserInTheirHome() throws IOException {
    final String previous = System.getProperty(USER_HOME_PROPERTY);
    System.setProperty(USER_HOME_PROPERTY, this.directory.toString());
    try {
      final QemuProcessRecords records = QemuProcessRecords.ofUser();
      final Process qemu = this.child(StopRequest.OBEYED);
      records.add(qemu.toHandle());
      final String recordName = "%s%s".formatted(qemu.pid(), RECORD_SUFFIX);
      final Path record = this.directory.resolve(MCAV_FOLDER).resolve(VM_FOLDER).resolve(recordName);
      final boolean recorded = Files.exists(record);
      assertTrue(recorded);

      records.remove(qemu.pid());
      final boolean recordLeft = Files.exists(record);
      assertFalse(recordLeft);
    } finally {
      System.setProperty(USER_HOME_PROPERTY, previous);
    }
  }

  private enum StopRequest {
    OBEYED,
    IGNORED,
  }
}
