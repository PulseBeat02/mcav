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
package me.brandonli.mcav.bukkit.utils.resourcepack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.ffmpeg.FFmpegCommand;
import me.brandonli.mcav.utils.ffmpeg.FFmpegTemplates;

/**
 * Utility class for extracting audio from a source.
 */
public final class SoundExtractorUtils {

  private SoundExtractorUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Extracts and transcode audio to OGG Vorbis using FFmpeg. The extracted audio file is saved in a cached
   * directory with a unique name.
   *
   * @param source the source of the audio to be extracted from
   * @return the path to the saved OGG audio file
   * @throws IOException if an I/O error occurs during the extraction or file creation process
   */
  public static Path extractOggAudio(final Source source) throws IOException {
    final String input = source.getResource();
    final Path outputDir = IOUtils.getCachedFolder();
    final UUID uuid = UUID.randomUUID();
    final String name = uuid + ".ogg";
    final Path file = outputDir.resolve(name);
    final String output = file.toString();
    final FFmpegCommand command = FFmpegTemplates.extractAudio(input, "vorbis", output);
    command.execute();
    return file;
  }
}
