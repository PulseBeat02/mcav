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

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.LineBorder;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;

public class BufferTest {

  private static final AudioFormat INPUT_FORMAT = new AudioFormat(48000, 16, 2, true, false);
  private static final AudioFormat OUTPUT_FORMAT = new AudioFormat(48000, 16, 1, true, false);
  private static final SourceDataLine LINE_OUTPUT;
  private static final int WRITE_SIZE = 1920; // Fixed write size
  private static final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream(); // Buffer for leftover bytes

  static {
    try {
      final DataLine.Info info = new DataLine.Info(SourceDataLine.class, OUTPUT_FORMAT);
      LINE_OUTPUT = (SourceDataLine) AudioSystem.getLine(info);
      LINE_OUTPUT.open(OUTPUT_FORMAT);
      LINE_OUTPUT.start();
    } catch (final LineUnavailableException e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  public static void main(final String[] args) throws Exception {
    final MCAVApi api = MCAV.api();
    api.install();

    final JFrame video = new JFrame("Video Player");
    video.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    video.setSize(1920, 1080);
    video.getContentPane().setBackground(Color.GREEN);

    final JLabel videoLabel = new JLabel();
    videoLabel.setPreferredSize(new Dimension(1920, 1080));
    videoLabel.setHorizontalAlignment(JLabel.CENTER);
    videoLabel.setVerticalAlignment(JLabel.CENTER);
    videoLabel.setBorder(new LineBorder(Color.BLACK, 3));

    video.setLayout(new BorderLayout());
    video.add(videoLabel, BorderLayout.CENTER);
    video.setVisible(true);

    BufferedImage bufferedImage;
    ImageIcon icon;
    final UriSource source = UriSource.uri(
      URI.create("http://distribution.bbb3d.renderfarming.net/video/mp4/bbb_sunflower_native_60fps_normal.mp4")
    );

    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of((samples, metadata) -> {
      final byte[] arr = new byte[samples.remaining()];
      samples.get(arr);
      samples.rewind();
      final ByteArrayInputStream bais = new ByteArrayInputStream(arr);
      final AudioInputStream stereoStream = new AudioInputStream(bais, INPUT_FORMAT, arr.length / INPUT_FORMAT.getFrameSize());
      final AudioInputStream monoStream = AudioSystem.getAudioInputStream(OUTPUT_FORMAT, stereoStream);
      final byte[] monoBytes;
      try {
        monoBytes = monoStream.readAllBytes();
      } catch (final IOException e) {
        throw new RuntimeException(e);
      }

      audioBuffer.write(monoBytes, 0, monoBytes.length);

      final byte[] bufferedBytes = audioBuffer.toByteArray();
      int offset = 0;

      while (offset + WRITE_SIZE <= bufferedBytes.length) {
        LINE_OUTPUT.write(bufferedBytes, offset, WRITE_SIZE);
        offset += WRITE_SIZE;
      }

      audioBuffer.reset();
      if (offset < bufferedBytes.length) {
        audioBuffer.write(bufferedBytes, offset, bufferedBytes.length - offset);
      }
    });

    final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
    multiplexer.setExceptionHandler((context, throwable) -> {
      System.err.println("Error occurred while processing media: " + context);
      throwable.printStackTrace();
    });

    final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
    audioCallback.attach(audioPipelineStep);

    final DimensionAttachableCallback dimensionCallback = multiplexer.getDimensionAttachableCallback();
    final me.brandonli.mcav.utils.immutable.Dimension dimension = new me.brandonli.mcav.utils.immutable.Dimension(256, 144);
    dimensionCallback.attach(dimension);

    Thread.getAllStackTraces()
      .keySet()
      .forEach(thread1 -> {
        thread1.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
          System.err.println("Uncaught exception in thread: " + thread.getName());
          throwable.printStackTrace();
        });
      });

    multiplexer.start(source);

    Runtime.getRuntime()
      .addShutdownHook(
        new Thread(() -> {
          // Flush any remaining buffered audio
          final byte[] remainingBytes = audioBuffer.toByteArray();
          if (remainingBytes.length > 0) {
            // Pad with zeros to reach WRITE_SIZE if needed
            final byte[] finalWrite = new byte[WRITE_SIZE];
            System.arraycopy(remainingBytes, 0, finalWrite, 0, Math.min(remainingBytes.length, WRITE_SIZE));
            LINE_OUTPUT.write(finalWrite, 0, WRITE_SIZE);
          }

          LINE_OUTPUT.drain();
          LINE_OUTPUT.stop();
          LINE_OUTPUT.close();

          multiplexer.release();
          api.release();
        })
      );
  }
}
