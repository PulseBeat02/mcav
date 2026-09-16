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
package me.brandonli.mcav.utils.ffmpeg;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.UUID;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.runtime.CommandTask;
import me.brandonli.mcav.utils.runtime.ProcessException;

/**
 * Extracts the audio track of media into standalone audio files with the FFmpeg program bundled with MCAV.
 *
 * <p>The audio is written as stereo Ogg Vorbis, a compact and royalty-free format that is played by Minecraft
 * resource packs, web browsers, game engines and most media players.
 *
 * <pre>{@code
 * final Source media = UriSource.uri(URI.create("https://example.com/video.mp4"));
 * final Path audio = AudioExtractor.extractOggVorbis(media);
 * }</pre>
 */
public final class AudioExtractor {

  private static final String OGG_EXTENSION = ".ogg";

  private AudioExtractor() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Extracts the audio of the source and transcodes it to stereo Ogg Vorbis. Mono and surround audio is mixed to
   * two channels, and any video is dropped.
   *
   * <p>Every call writes a new file with a random name into the MCAV cache folder, {@code ~/.mcav/cache}, so
   * extracting the same source twice yields two files. The caller owns the file and may move or delete it. This
   * method blocks until FFmpeg finishes, which can take a while for long media or slow network sources, so avoid
   * calling it on threads that must stay responsive.
   *
   * @param source the media to extract the audio from, such as a file or a URL
   * @return the absolute path of the new Ogg Vorbis file
   * @throws NullPointerException  if the source is null
   * @throws IOException           if FFmpeg cannot be started, its output cannot be read, or the thread is
   *                               interrupted while waiting for FFmpeg
   * @throws ProcessException      if FFmpeg exits with a non-zero exit code, for example because the source does
   *                               not exist, cannot be decoded, or has no audio track
   * @throws UncheckedIOException  if the cache folder cannot be created
   * @throws UnsatisfiedLinkError  if the bundled FFmpeg is not available for this platform
   */
  public static Path extractOggVorbis(final Source source) throws IOException {
    Preconditions.checkNotNull(source, "Source must not be null");
    final String input = source.getResource();
    final Path outputDirectory = IOUtils.getCachedFolder();
    final UUID id = UUID.randomUUID();
    final String fileName = id + OGG_EXTENSION;
    final Path outputFile = outputDirectory.resolve(fileName);
    final String output = outputFile.toString();

    final FFmpegCommand command = FFmpegTemplates.extractOggVorbis(input, output);
    final CommandTask task = command.createTask();
    task.runChecked();
    return outputFile;
  }
}
