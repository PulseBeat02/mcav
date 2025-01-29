/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.utils.ffmpeg.FFmpegCommand;
import me.brandonli.mcav.utils.ffmpeg.FFmpegTemplates;
import me.brandonli.mcav.utils.runtime.CommandTask;

public final class FFmpegExtractorExample {

  public static void main(final String[] args) throws IOException, InterruptedException {
    final UriSource source = UriSource.uri(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    final YTDLPParser parser = YTDLPParser.simple();
    final URLParseDump dump = parser.parse(source);
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final UriSource audioFormat = selector.getAudioSource(dump).toUriSource();
    final String raw = audioFormat.getResource();

    final Path ogg = Path.of("output.ogg");
    final Path absolute = ogg.toAbsolutePath();
    final String path = absolute.toString();
    final FFmpegCommand command = FFmpegTemplates.extractAudio(raw, "vorbis", path);
    final CommandTask task = command.execute();
    System.out.println(Arrays.stream(task.getCommand()).reduce("", (a, b) -> a + " " + b));
    final Process process = task.getProcess();

    process.waitFor();
    System.out.println(task.getOutput());
  }
}
