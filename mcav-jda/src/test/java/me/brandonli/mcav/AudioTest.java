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

import com.sun.jna.Pointer;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine.Info;
import javax.sound.sampled.SourceDataLine;
import javax.swing.*;
import me.brandonli.mcav.utils.ByteUtils;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

@SuppressWarnings("all")
public class AudioTest {

  private static final String FORMAT = "";

  private static final int RATE = 44100;

  private static final int CHANNELS = 2;

  private final CountDownLatch sync = new CountDownLatch(1);

  private final EmbeddedMediaPlayerComponent mediaPlayerComponent;

  public static void main(String[] args) throws Exception {
    args = new String[] { "https://archive.org/download/rick-roll/Rick%20Roll.mp4" };

    if (args.length != 1) {
      System.out.println("Specify an MRL");
      System.exit(1);
    }

    new AudioTest().play(args[0]);

    System.out.println("Exit normally");

    // Force exit since the JFrame keeps us running
    System.exit(0);
  }

  public AudioTest() throws Exception {
    this.mediaPlayerComponent = new EmbeddedMediaPlayerComponent();
    this.mediaPlayerComponent.mediaPlayer().audio().callback("S16N", RATE, CHANNELS, new JavaSoundCallback(RATE, CHANNELS));

    final JPanel cp = new JPanel();
    cp.setLayout(new BorderLayout());
    cp.add(this.mediaPlayerComponent, BorderLayout.CENTER);

    final JFrame f = new JFrame();
    f.setContentPane(cp);
    f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    f.setBounds(100, 100, 1000, 800);
    f.setVisible(true);

    f.addWindowListener(
      new WindowAdapter() {
        @Override
        public void windowClosing(final WindowEvent e) {
          AudioTest.this.mediaPlayerComponent.release();
          System.exit(0);
        }
      }
    );
  }

  private void play(final String mrl) throws Exception {
    this.mediaPlayerComponent.mediaPlayer().media().play(mrl);
    try {
      this.sync.await();
    } catch (final InterruptedException e) {
      e.printStackTrace();
    }
    this.mediaPlayerComponent.release();
  }

  /**
   *
   */
  private class JavaSoundCallback extends AudioCallbackAdapter {

    private static final int BLOCK_SIZE = 4;
    private static final int SAMPLE_BITS = 16; // BLOCK_SIZE * 8 / channels ???

    private final AudioFormat audioFormat;
    private final Info info;
    private final SourceDataLine dataLine;

    public JavaSoundCallback(final int rate, final int channels) throws Exception {
      this.audioFormat = new AudioFormat(44100, SAMPLE_BITS, channels, true, true);
      this.info = new Info(SourceDataLine.class, this.audioFormat);
      this.dataLine = (SourceDataLine) AudioSystem.getLine(this.info);
      this.start();
    }

    private void start() throws Exception {
      System.out.println("start()");
      this.dataLine.open(this.audioFormat);
      this.dataLine.start();
    }

    private void stop() {
      System.out.println("stop()");
      this.dataLine.close();
    }

    @Override
    public void play(final MediaPlayer mediaPlayer, final Pointer samples, final int sampleCount, final long pts) {
      final int bufferSize = sampleCount * BLOCK_SIZE;
      final byte[] data = samples.getByteArray(0, bufferSize);
      final ByteBuffer sampleData = ByteBuffer.wrap(data);
      final ByteBuffer clamped1 = ByteUtils.clampNativeBufferToLittleEndian(sampleData);
      final ByteBuffer clamped = ByteUtils.clampNativeBufferToBigEndian(clamped1);
      this.dataLine.write(clamped.array(), 0, bufferSize);
    }

    @Override
    public void drain(final MediaPlayer mediaPlayer) {
      System.out.println("drain()");
      this.dataLine.drain();
    }
  }
}
