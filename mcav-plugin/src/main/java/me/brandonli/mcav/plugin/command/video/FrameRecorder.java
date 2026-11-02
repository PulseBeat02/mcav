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

// writes the stream format /mcav mcv2 play reads, so measurements can decode what viewers were sent
final class FrameRecorder implements Consumer<byte[]> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FrameRecorder.class);

  private final Path file;

  private final Path folder;

  private volatile boolean failed;

  FrameRecorder(final Path file) {
    Preconditions.checkNotNull(file, "File must not be null!");

    final Path absolute = file.toAbsolutePath();
    final Path parent = absolute.getParent();
    if (parent == null) {
      final String message = "Stream file %s must not be the root of the file system!".formatted(file);
      throw new IllegalArgumentException(message);
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
      LOGGER.warn("Cannot record the MCV2 frames into {}, the recording stops.", this.file, exception);
    }
  }
}
