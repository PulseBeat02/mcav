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
package me.brandonli.mcav.sandbox.command.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FrameRecorder}: frames are appended after their little-endian length, and a recording that cannot be
 * written stops without disturbing the screen.
 */
final class FrameRecorderTest {

  @TempDir
  private Path folder;

  @Test
  void appendsEveryFrameAfterItsLength() throws IOException {
    final Path file = this.folder.resolve("records").resolve("screen-1-5.mcs");
    final FrameRecorder recorder = new FrameRecorder(file);
    recorder.accept(new byte[] { 7, 8, 9 });
    recorder.accept(new byte[] { 1 });
    assertArrayEquals(new byte[] { 3, 0, 0, 0, 7, 8, 9, 1, 0, 0, 0, 1 }, Files.readAllBytes(file));
  }

  @Test
  void stopsRecordingAtTheFirstFailure() throws IOException {
    final Path file = this.folder.resolve("screen-1-5.mcs");
    Files.createDirectories(file);
    final FrameRecorder recorder = new FrameRecorder(file);
    recorder.accept(new byte[] { 7 });
    Files.delete(file);
    // a recording that failed once is over, whatever happens to the file after
    recorder.accept(new byte[] { 8 });
    assertFalse(Files.exists(file));
    assertThrows(NullPointerException.class, () -> new FrameRecorder(null));
    assertThrows(IllegalArgumentException.class, () -> new FrameRecorder(this.folder.getRoot()));
  }
}
