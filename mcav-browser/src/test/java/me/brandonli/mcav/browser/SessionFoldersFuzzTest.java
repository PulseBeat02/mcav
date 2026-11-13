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

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.utils.IoUtils;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class SessionFoldersFuzzTest {

  private static final String TEMPORARY_FOLDER_PREFIX = "mcav-session-fuzz";
  private static final String SESSION_FOLDER = SessionFolders.PREFIX + "fuzz";

  private static final int RECORD_LINES = 2;

  @FuzzTest(maxDuration = "30s")
  void shouldRemoveAFolderOnlyWhenItsRecordNamesAServerThatIsGone(final FuzzedDataProvider data) throws IOException {
    final byte[] record = data.consumeRemainingAsBytes();
    final Path temporary = Files.createTempDirectory(TEMPORARY_FOLDER_PREFIX);
    try {
      final Path folder = Files.createDirectory(temporary.resolve(SESSION_FOLDER));
      Files.write(folder.resolve(SessionFolders.OWNER_FILE), record);
      final SessionFolders folders = new SessionFolders(ProcessHandle.current(), processId -> Optional.empty());

      final boolean namesAServer = this.namesAServer(record);
      final int removed = folders.removeStale(temporary);
      final boolean folderLeft = Files.exists(folder);
      final int expectedRemoved = namesAServer ? 1 : 0;
      assertEquals(expectedRemoved, removed);
      assertEquals(!namesAServer, folderLeft);
    } finally {
      IoUtils.deleteRecursively(temporary);
    }
  }

  private boolean namesAServer(final byte[] record) {
    // a byte outside US-ASCII becomes U+FFFD, which neither parser accepts
    final String text = new String(record, StandardCharsets.US_ASCII);
    final List<String> lines = text.lines().toList();
    if (lines.size() != RECORD_LINES) {
      return false;
    }

    try {
      final long _ = Long.parseLong(lines.get(0));
      final Instant _ = Instant.parse(lines.get(1));
    } catch (final NumberFormatException | DateTimeParseException exception) {
      return false;
    }

    return true;
  }
}
