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
package me.brandonli.mcav.svc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.utils.audio.MonoDownmixer;

/**
 * Plays a file through the speakers in mono, exactly as Simple Voice Chat receives it, to check the downmix
 * without a Minecraft server. Pass the path of a media file as the first argument.
 */
public final class MonoDownmixExample {

  private MonoDownmixExample() {
    throw new UnsupportedOperationException("Example class cannot be instantiated");
  }

  /**
   * Plays the file given as the first argument until Enter is pressed.
   *
   * @param args the command line arguments, of which the first is the path of the media file
   * @throws LineUnavailableException if the speakers cannot be opened
   */
  static void main(final String[] args) throws LineUnavailableException {
    if (args.length == 0) {
      System.err.println("Usage: MonoDownmixExample <file>");
      return;
    }

    final MCAVApi api = MCAV.api();
    api.install();
    try (final SourceDataLine line = openMonoLine()) {
      final VideoPlayerMultiplexer player = playInto(line, args[0]);
      IO.readln("Playing in mono; press Enter to stop");
      player.release();
    } finally {
      api.release();
    }
  }

  private static SourceDataLine openMonoLine() throws LineUnavailableException {
    final AudioFormat format = new AudioFormat(48_000, 16, 1, true, false);
    final SourceDataLine line = AudioSystem.getSourceDataLine(format);
    line.open(format);
    line.start();
    return line;
  }

  private static VideoPlayerMultiplexer playInto(final SourceDataLine line, final String file) {
    final AudioPipelineStep pipeline = AudioPipelineStep.of((samples, _) -> playMono(line, samples));
    final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    audio.attach(pipeline);

    final Path path = Path.of(file);
    final FileSource source = FileSource.path(path);
    player.start(source);
    return player;
  }

  private static boolean playMono(final SourceDataLine line, final ByteBuffer samples) {
    final short[] mono = MonoDownmixer.downmix(samples);
    final ByteBuffer bytes = ByteBuffer.allocate(mono.length * 2);
    bytes.order(ByteOrder.LITTLE_ENDIAN);
    for (final short sample : mono) {
      bytes.putShort(sample);
    }
    final byte[] array = bytes.array();
    line.write(array, 0, array.length);
    return false;
  }
}
