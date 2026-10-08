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

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the frames an MCV2 screen sends into a stream file, each after its length as a little-endian 32-bit number,
 * the format {@code /mcav mcv2 play} reads, so a measurement can decode what the viewers were sent with the reference
 * decoder. The first failure to write is logged and ends the recording; the screen plays on.
 */
final class FrameRecorder implements Consumer<byte[]> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FrameRecorder.class);

  private static final String CANNOT_RECORD = "Cannot record the MCV2 frames into {}, the recording stops";

  private final Path file;

  private final Path folder;

  private volatile boolean failed;

  /**
   * Constructs a recorder.
   *
   * @param file the stream file, appended to
   * @throws IllegalArgumentException if the file is the root of the file system
   */
  FrameRecorder(final Path file) {
    Preconditions.checkNotNull(file, "File must not be null");
    final Path absolute = file.toAbsolutePath();
    final Path parent = absolute.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("A stream file is not the root of the file system: " + file);
    }
    this.file = absolute;
    this.folder = parent;
  }

  @Override
  public void accept(final byte[] frame) {
    if (this.failed) {
      return;
    }
    final ByteBuffer record = ByteBuffer.allocate(Integer.BYTES + frame.length).order(ByteOrder.LITTLE_ENDIAN);
    record.putInt(frame.length).put(frame);
    try {
      Files.createDirectories(this.folder);
      Files.write(this.file, record.array(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (final IOException exception) {
      this.failed = true;
      LOGGER.warn(CANNOT_RECORD, this.file, exception);
    }
  }
}
