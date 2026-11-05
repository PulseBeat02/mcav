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

class SessionFoldersTest {

  private static final String NON_ASCII_FOLDER_SUFFIX = "not-ascii";
  private static final String UNRELATED_FOLDER = "other-folder";
  private static final String COOKIES_FILE = "Cookies";
  private static final String GARBAGE_FOLDER_SUFFIX = "garbage";
  private static final String TRUNCATED_FOLDER_SUFFIX = "truncated";
  private static final String UNREADABLE_FOLDER_SUFFIX = "unreadable";
  private static final String FOLDERS_ARCHIVE_FILE = "folders.zip";
  private static final String ZIPPED_FOLDER_SUFFIX = "zipped";
  private static final String MISSING_FOLDER = "missing";

  private static final String FILE_SYSTEM_ROOT = "/";

  private static final String GONE_SERVER_START = "2026-10-05T00:00:00Z";

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";

  private static final long GONE = Long.MAX_VALUE;

  private static final String LINE_SEPARATOR = "\n";

  private static final String OLD_START = "2001-01-01T00:00:00Z";

  @TempDir
  private Path temporary;

  private ProcessHandle server(final long processId, final Optional<Instant> started) {
    final ProcessHandle server = mock(ProcessHandle.class);
    final ProcessHandle.Info information = mock(ProcessHandle.Info.class);
    when(server.pid()).thenReturn(processId);
    when(server.info()).thenReturn(information);
    when(information.startInstant()).thenReturn(started);

    return server;
  }

  private Path folderOf(final ProcessHandle server) throws IOException {
    final Path folder = Files.createTempDirectory(this.temporary, SessionFolders.PREFIX);
    new SessionFolders(server, ProcessHandle::of).record(folder);
    Files.writeString(folder.resolve(COOKIES_FILE), "the page's cookies");

    return folder;
  }

  @Test
  void shouldRemoveTheFolderOfAServerThatIsGoneAndKeepThatOfARunningOne() throws IOException {
    final Path gone = this.folderOf(this.server(GONE, Optional.of(Instant.parse(GONE_SERVER_START))));
    final Path running = this.folderOf(ProcessHandle.current());
    final String ownerRecord = Files.readString(gone.resolve(SessionFolders.OWNER_FILE), StandardCharsets.US_ASCII);
    final String expectedOwnerRecord = GONE + LINE_SEPARATOR + GONE_SERVER_START + LINE_SEPARATOR;
    assertEquals(expectedOwnerRecord, ownerRecord);

    final int removed = SessionFolders.ofThisServer().removeStale(this.temporary);

    assertEquals(1, removed);

    final boolean goneFolderLeft = Files.exists(gone);
    assertFalse(goneFolderLeft, "a killed server left it behind");

    final boolean runningCookiesKept = Files.exists(running.resolve(COOKIES_FILE));
    assertTrue(runningCookiesKept, "this server releases it itself");
  }

  @Test
  void shouldTreatAServerWhoseIdAnotherProcessTookAsGone() throws IOException {
    final long reused = ProcessHandle.current().pid();
    final Path folder = this.folderOf(this.server(reused, Optional.of(Instant.parse(OLD_START))));
    final int removedCount = SessionFolders.ofThisServer().removeStale(this.temporary);
    assertEquals(1, removedCount);

    final boolean folderLeft = Files.exists(folder);
    assertFalse(folderLeft);
  }

  @Test
  void shouldLeaveAFolderThatNamesNoServerOrNoneThatCanBeReadAlone() throws IOException {
    final Path unnamed = this.folderOf(this.server(GONE, Optional.empty()));
    final boolean recordWritten = Files.exists(unnamed.resolve(SessionFolders.OWNER_FILE));
    assertFalse(recordWritten, "a server of unknown start names nothing");

    final Path garbage = Files.createDirectory(this.temporary.resolve(SessionFolders.PREFIX + GARBAGE_FOLDER_SUFFIX));
    Files.writeString(garbage.resolve(SessionFolders.OWNER_FILE), "not\na record\n");
    final Path truncated = Files.createDirectory(this.temporary.resolve(SessionFolders.PREFIX + TRUNCATED_FOLDER_SUFFIX));
    Files.writeString(truncated.resolve(SessionFolders.OWNER_FILE), GONE + LINE_SEPARATOR);
    final Path notAscii = Files.createDirectory(this.temporary.resolve(SessionFolders.PREFIX + NON_ASCII_FOLDER_SUFFIX));
    Files.write(notAscii.resolve(SessionFolders.OWNER_FILE), new byte[] { (byte) 0xff, '\n' });
    final Path unreadable = Files.createDirectory(this.temporary.resolve(SessionFolders.PREFIX + UNREADABLE_FOLDER_SUFFIX));
    final Path record = Files.writeString(
      unreadable.resolve(SessionFolders.OWNER_FILE),
      GONE + LINE_SEPARATOR + OLD_START + LINE_SEPARATOR
    );
    final boolean posix = record.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
    if (posix) {
      Files.setPosixFilePermissions(record, PosixFilePermissions.fromString("-w-------"));
    }

    final Path other = Files.createDirectory(this.temporary.resolve(UNRELATED_FOLDER));
    Files.writeString(other.resolve(SessionFolders.OWNER_FILE), GONE + LINE_SEPARATOR + OLD_START + LINE_SEPARATOR);
    final boolean readable = Files.isReadable(record);

    final int removed = SessionFolders.ofThisServer().removeStale(this.temporary);

    final int expectedRemovedCount = readable ? 1 : 0;
    assertEquals(expectedRemovedCount, removed, "a user who may read every file reads the record");

    for (final Path kept : List.of(unnamed, garbage, truncated, notAscii, other)) {
      final boolean folderKept = Files.exists(kept);
      assertTrue(folderKept, kept.toString());
    }
  }

  @Test
  void shouldLeaveAFolderOfAnotherUserAloneEvenIfItsRecordNamesAServerThatIsGone() throws IOException {
    final Path planted = this.folderOf(this.server(GONE, Optional.of(Instant.parse(GONE_SERVER_START))));
    final ProcessHandle serverOfAnotherUser = this.server(ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant());
    when(serverOfAnotherUser.info().user()).thenReturn(Optional.of("not-" + Files.getOwner(planted).getName()));
    final ProcessHandle serverOfNoKnownUser = this.server(ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant());
    when(serverOfNoKnownUser.info().user()).thenReturn(Optional.empty());

    if (planted.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW)) {
      final int removedForAnotherUser = new SessionFolders(serverOfAnotherUser, ProcessHandle::of).removeStale(this.temporary);
      assertEquals(0, removedForAnotherUser);

      final int removedForNoKnownUser = new SessionFolders(serverOfNoKnownUser, ProcessHandle::of).removeStale(this.temporary);
      assertEquals(0, removedForNoKnownUser);

      final boolean cookiesKept = Files.exists(planted.resolve(COOKIES_FILE));
      assertTrue(cookiesKept, "only folders of the server's own user are removed");

      final int removedForThisServer = SessionFolders.ofThisServer().removeStale(this.temporary);
      assertEquals(1, removedForThisServer);
    } else {
      final int removedWithoutOwners = new SessionFolders(serverOfAnotherUser, ProcessHandle::of).removeStale(this.temporary);
      assertEquals(1, removedWithoutOwners);

      final boolean plantedLeft = Files.exists(planted);
      assertFalse(plantedLeft);
    }
  }

  @Test
  void shouldRemoveAFolderOnAFileSystemWithoutOwnersByItsRecordAlone() throws IOException {
    final ProcessHandle serverOfAnotherUser = this.server(ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant());
    when(serverOfAnotherUser.info().user()).thenReturn(Optional.of("someone-else"));
    final Path zip = this.temporary.resolve(FOLDERS_ARCHIVE_FILE);
    try (final FileSystem zipped = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
      final Path root = zipped.getPath(FILE_SYSTEM_ROOT);
      final Path folder = Files.createDirectory(root.resolve(SessionFolders.PREFIX + ZIPPED_FOLDER_SUFFIX));
      Files.writeString(folder.resolve(SessionFolders.OWNER_FILE), GONE + LINE_SEPARATOR + OLD_START + LINE_SEPARATOR);

      final int removedCount = new SessionFolders(serverOfAnotherUser, ProcessHandle::of).removeStale(root);
      assertEquals(1, removedCount);

      final boolean folderLeft = Files.exists(folder);
      assertFalse(folderLeft, "as on Windows, whose temporary folder belongs to its user alone");
    }
  }

  @Test
  void shouldOnlyLogATemporaryFolderThatCannotBeListed() {
    final int removedCount = SessionFolders.ofThisServer().removeStale(this.temporary.resolve(MISSING_FOLDER));
    assertEquals(0, removedCount);
  }

  @Test
  void shouldLeaveTheFolderUnnamedWhenTheRecordCannotBeWritten() {
    final Path missing = this.temporary.resolve(MISSING_FOLDER);
    new SessionFolders(ProcessHandle.current(), ProcessHandle::of).record(missing);
    final boolean folderCreated = Files.exists(missing);
    assertFalse(folderCreated);
  }
}
