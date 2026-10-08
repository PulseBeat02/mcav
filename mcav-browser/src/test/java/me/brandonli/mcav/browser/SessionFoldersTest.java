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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link SessionFolders}: the folder of a session names its server, and a later start removes the folders of
 * servers that are gone, and nothing else.
 */
class SessionFoldersTest {

  private static final long GONE = Long.MAX_VALUE;

  @TempDir
  private Path temporary;

  private static ProcessHandle server(final long pid, final Optional<Instant> started) {
    final ProcessHandle server = mock(ProcessHandle.class);
    final ProcessHandle.Info info = mock(ProcessHandle.Info.class);
    when(server.pid()).thenReturn(pid);
    when(server.info()).thenReturn(info);
    when(info.startInstant()).thenReturn(started);
    return server;
  }

  private Path folderOf(final ProcessHandle server) throws IOException {
    final Path folder = Files.createTempDirectory(this.temporary, SessionFolders.PREFIX);
    new SessionFolders(server, ProcessHandle::of).record(folder);
    Files.writeString(folder.resolve("Cookies"), "the page's cookies");
    return folder;
  }

  @Test
  void theFolderOfAServerThatIsGoneIsRemovedAndThatOfARunningOneKept() throws IOException {
    final Path gone = this.folderOf(server(GONE, Optional.of(Instant.parse("2026-10-05T00:00:00Z"))));
    final Path running = this.folderOf(ProcessHandle.current());
    assertEquals(GONE + "\n2026-10-05T00:00:00Z\n", Files.readString(gone.resolve(SessionFolders.OWNER_FILE), StandardCharsets.US_ASCII));

    final int removed = SessionFolders.ofThisServer().removeStale(this.temporary);

    assertEquals(1, removed);
    assertFalse(Files.exists(gone), "a killed server left it behind");
    assertTrue(Files.exists(running.resolve("Cookies")), "this server releases it itself");
  }

  @Test
  void aServerWhoseIdAnotherProcessTookIsGone() throws IOException {
    final long reused = ProcessHandle.current().pid();
    final Path folder = this.folderOf(server(reused, Optional.of(Instant.parse("2001-01-01T00:00:00Z"))));
    assertEquals(1, SessionFolders.ofThisServer().removeStale(this.temporary));
    assertFalse(Files.exists(folder));
  }

  @Test
  void aFolderThatNamesNoServerOrNoneThatCanBeReadIsLeftAlone() throws IOException {
    final Path unnamed = this.folderOf(server(GONE, Optional.empty()));
    assertFalse(Files.exists(unnamed.resolve(SessionFolders.OWNER_FILE)), "a server of unknown start names nothing");
    final Path garbage = Files.createDirectory(this.temporary.resolve(SessionFolders.PREFIX + "garbage"));
    Files.writeString(garbage.resolve(SessionFolders.OWNER_FILE), "not\na record\n");
    final Path truncated = Files.createDirectory(this.temporary.resolve(SessionFolders.PREFIX + "truncated"));
    Files.writeString(truncated.resolve(SessionFolders.OWNER_FILE), GONE + "\n");
    final Path unreadable = Files.createDirectory(this.temporary.resolve(SessionFolders.PREFIX + "unreadable"));
    final Path record = Files.writeString(unreadable.resolve(SessionFolders.OWNER_FILE), GONE + "\n2001-01-01T00:00:00Z\n");
    final boolean posix = record.getFileSystem().supportedFileAttributeViews().contains("posix");
    if (posix) {
      Files.setPosixFilePermissions(record, PosixFilePermissions.fromString("-w-------"));
    }
    final Path other = Files.createDirectory(this.temporary.resolve("other-folder"));
    Files.writeString(other.resolve(SessionFolders.OWNER_FILE), GONE + "\n2001-01-01T00:00:00Z\n");
    final boolean readable = Files.isReadable(record);

    final int removed = SessionFolders.ofThisServer().removeStale(this.temporary);

    assertEquals(readable ? 1 : 0, removed, "a user who may read every file reads the record");
    for (final Path kept : List.of(unnamed, garbage, truncated, other)) {
      assertTrue(Files.exists(kept), kept.toString());
    }
  }

  @Test
  void aFolderOfAnotherUserIsLeftAloneEvenIfItsRecordNamesAServerThatIsGone() throws IOException {
    final Path planted = this.folderOf(server(GONE, Optional.of(Instant.parse("2026-10-05T00:00:00Z"))));
    final ProcessHandle serverOfAnotherUser = server(ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant());
    when(serverOfAnotherUser.info().user()).thenReturn(Optional.of("not-" + Files.getOwner(planted).getName()));
    final ProcessHandle serverOfNoKnownUser = server(ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant());
    when(serverOfNoKnownUser.info().user()).thenReturn(Optional.empty());

    if (planted.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals(0, new SessionFolders(serverOfAnotherUser, ProcessHandle::of).removeStale(this.temporary));
      assertEquals(0, new SessionFolders(serverOfNoKnownUser, ProcessHandle::of).removeStale(this.temporary));
      assertTrue(Files.exists(planted.resolve("Cookies")), "only folders of the server's own user are removed");
      assertEquals(1, SessionFolders.ofThisServer().removeStale(this.temporary));
    } else {
      assertEquals(1, new SessionFolders(serverOfAnotherUser, ProcessHandle::of).removeStale(this.temporary));
      assertFalse(Files.exists(planted));
    }
  }

  @Test
  void aFolderOnAFileSystemWithoutOwnersIsRemovedByItsRecordAlone() throws IOException {
    final ProcessHandle serverOfAnotherUser = server(ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant());
    when(serverOfAnotherUser.info().user()).thenReturn(Optional.of("someone-else"));
    final Path zip = this.temporary.resolve("folders.zip");
    try (final FileSystem zipped = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
      final Path root = zipped.getPath("/");
      final Path folder = Files.createDirectory(root.resolve(SessionFolders.PREFIX + "zipped"));
      Files.writeString(folder.resolve(SessionFolders.OWNER_FILE), GONE + "\n2001-01-01T00:00:00Z\n");

      assertEquals(1, new SessionFolders(serverOfAnotherUser, ProcessHandle::of).removeStale(root));
      assertFalse(Files.exists(folder), "as on Windows, whose temporary folder belongs to its user alone");
    }
  }

  @Test
  void aTemporaryFolderThatCannotBeListedIsOnlyLogged() {
    assertEquals(0, SessionFolders.ofThisServer().removeStale(this.temporary.resolve("missing")));
  }

  @Test
  void aRecordThatCannotBeWrittenLeavesTheFolderUnnamed() {
    final Path missing = this.temporary.resolve("missing");
    new SessionFolders(ProcessHandle.current(), ProcessHandle::of).record(missing);
    assertFalse(Files.exists(missing));
  }
}
