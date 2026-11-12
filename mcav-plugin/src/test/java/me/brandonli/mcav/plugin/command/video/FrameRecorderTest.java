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
package me.brandonli.mcav.plugin.command.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameRecorderTest {

  private static final String RECORD_FILE_NAME = "screen-1-5.mcs";
  private static final String RECORDS_FOLDER_NAME = "records";

  @TempDir
  private Path folder;

  @Test
  void shouldAppendEveryFrameAfterItsLength() throws IOException {
    final Path file = this.folder.resolve(RECORDS_FOLDER_NAME).resolve(RECORD_FILE_NAME);
    final FrameRecorder recorder = new FrameRecorder(file);
    recorder.accept(new byte[] { 7, 8, 9 });
    recorder.accept(new byte[] { 1 });

    final byte[] allBytes = Files.readAllBytes(file);
    final byte[] expected = { 3, 0, 0, 0, 7, 8, 9, 1, 0, 0, 0, 1 };
    assertArrayEquals(expected, allBytes);
  }

  @Test
  void shouldStopRecordingAtTheFirstFailure() throws IOException {
    final Path file = this.folder.resolve(RECORD_FILE_NAME);
    Files.createDirectories(file);
    final FrameRecorder recorder = new FrameRecorder(file);
    recorder.accept(new byte[] { 7 });
    Files.delete(file);
    recorder.accept(new byte[] { 8 });

    final boolean exists = Files.exists(file);
    assertFalse(exists);
  }

  @Test
  void shouldRefuseANullFileAndTheRootOfTheFileSystem() {
    final String nullMessage = assertThrows(NullPointerException.class, () -> new FrameRecorder(null)).getMessage();
    assertEquals("File must not be null!", nullMessage);

    final Path root = this.folder.getRoot();
    final String rootMessage = assertThrows(IllegalArgumentException.class, () -> new FrameRecorder(root)).getMessage();
    final String expected = "Stream file %s must not be the root of the file system!".formatted(root);
    assertEquals(expected, rootMessage);
  }
}
