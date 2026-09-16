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
package me.brandonli.mcav.http;

import java.nio.file.Path;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.source.file.FileSource;

/**
 * Plays the audio of a file in the browser. Pass the path of a media file as the first argument, then open the
 * printed address.
 */
public final class HttpAudioExample {

  private HttpAudioExample() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Starts a web server on port 3000 and plays the file into it until the JVM exits.
   *
   * @param args the path of the media file
   */
  static void main(final String[] args) {
    if (args.length == 0) {
      System.err.println("Usage: HttpAudioExample <file>");
      return;
    }
    final MCAVApi api = MCAV.api();
    api.install(HttpModule.class);

    final Path path = Path.of(args[0]);
    final HttpResult http = startServer(path);
    final VideoPlayerMultiplexer player = play(http, path);
    final String url = http.getFullUrl();
    System.out.println("Listen at " + url);

    final Thread shutdownHook = new Thread(() -> {
      player.release();
      http.stop();
      api.release();
    });
    final Runtime runtime = Runtime.getRuntime();
    runtime.addShutdownHook(shutdownHook);
  }

  private static HttpResult startServer(final Path path) {
    final HttpResult http = HttpResult.port(3000);
    http.start();
    final String title = path.toString();
    final MediaInfo info = MediaInfo.titled(title);
    http.setCurrentMedia(info);
    return http;
  }

  private static VideoPlayerMultiplexer play(final HttpResult http, final Path path) {
    final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    final AudioPipelineStep pipeline = AudioPipelineStep.of(http);
    audio.attach(pipeline);
    final FileSource source = FileSource.path(path);
    player.start(source);
    return player;
  }
}
