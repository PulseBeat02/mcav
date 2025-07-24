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

import static java.util.Objects.requireNonNull;

import de.maxhenkel.voicechat.api.Entity;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audio.AudioConverter;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of the SVCFilter interface that processes audio samples for a set of players.
 */
public final class SVCFilterImpl implements SVCFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(SVCFilterImpl.class);

  private static final AudioFormat INPUT_FORMAT = new AudioFormat(48000, 16, 2, true, false);
  private static final AudioFormat OUTPUT_FORMAT = new AudioFormat(48000, 16, 1, true, false);

  private static final int FRAME_SIZE = 960;

  private final Object[] players;
  private final Entity[] channels;
  private final AudioPlayer[] audioPlayers;
  private final OpusEncoder[] encoders;
  private final Supplier<short[]> supplier;
  private final BlockingQueue<short[]> frameQueue;
  private final ByteArrayOutputStream sampleBuffer;

  private volatile boolean isRunning;

  SVCFilterImpl(final Object... players) {
    final VoicechatServerApi voiceChatApi = SVCModule.getVoiceChatApi();
    final int len = players.length;
    this.frameQueue = new LinkedBlockingQueue<>();
    this.sampleBuffer = new ByteArrayOutputStream();
    this.players = players;
    this.channels = new Entity[len];
    this.audioPlayers = new AudioPlayer[len];
    this.encoders = new OpusEncoder[len];
    this.isRunning = true;
    this.supplier = () -> {
      try {
        final short[] frame = this.frameQueue.poll(50, TimeUnit.MILLISECONDS);
        return frame != null ? frame : new short[FRAME_SIZE];
      } catch (final InterruptedException e) {
        final Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
        return new short[FRAME_SIZE];
      }
    };
    for (int i = 0; i < len; i++) {
      final Object player = players[i];
      this.channels[i] = voiceChatApi.fromEntity(player);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final VoicechatServerApi voiceChatApi = SVCModule.getVoiceChatApi();
    for (int i = 0; i < this.players.length; i++) {
      final UUID uuid = UUID.randomUUID();
      final Entity entity = this.channels[i];
      final EntityAudioChannel audioChannel = requireNonNull(voiceChatApi.createEntityAudioChannel(uuid, entity));
      final OpusEncoder encoder = voiceChatApi.createEncoder();
      final AudioPlayer player = voiceChatApi.createAudioPlayer(audioChannel, encoder, this.supplier);
      this.audioPlayers[i] = player;
      this.encoders[i] = encoder;
      player.startPlaying();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    this.isRunning = false;

    final byte[] remainingBytes = this.sampleBuffer.toByteArray();
    if (remainingBytes.length > 0) {
      final VoicechatServerApi voiceChatApi = SVCModule.getVoiceChatApi();
      final AudioConverter converter = voiceChatApi.getAudioConverter();
      final short[] remainingShorts = converter.bytesToShorts(remainingBytes);
      final short[] finalFrame = new short[FRAME_SIZE];
      System.arraycopy(remainingShorts, 0, finalFrame, 0, Math.min(remainingShorts.length, FRAME_SIZE));
      if (!this.frameQueue.offer(finalFrame)) {
        LOGGER.error("Warning: Final audio frame dropped due to full queue");
      }
    }

    for (int i = 0; i < this.players.length; i++) {
      final AudioPlayer player = this.audioPlayers[i];
      if (player != null) {
        player.stopPlaying();
      }
      final OpusEncoder encoder = this.encoders[i];
      if (encoder != null) {
        encoder.close();
      }
    }

    this.frameQueue.clear();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    if (!this.isRunning) {
      return;
    }

    final VoicechatServerApi voiceChatApi = SVCModule.getVoiceChatApi();
    final AudioConverter converter = voiceChatApi.getAudioConverter();
    final byte[] arr = new byte[samples.remaining()];
    samples.get(arr);
    samples.rewind();

    try {
      final ByteArrayInputStream bais = new ByteArrayInputStream(arr);
      final AudioInputStream stereoStream = new AudioInputStream(bais, INPUT_FORMAT, arr.length / INPUT_FORMAT.getFrameSize());
      final AudioInputStream monoStream = AudioSystem.getAudioInputStream(OUTPUT_FORMAT, stereoStream);
      final byte[] monoBytes = monoStream.readAllBytes();
      this.sampleBuffer.write(monoBytes, 0, monoBytes.length);

      final byte[] bufferedBytes = this.sampleBuffer.toByteArray();
      int bytesProcessed = 0;
      final int BYTES_PER_FRAME = FRAME_SIZE * 2;
      while (bytesProcessed + BYTES_PER_FRAME <= bufferedBytes.length) {
        final byte[] frameBytes = new byte[BYTES_PER_FRAME];
        System.arraycopy(bufferedBytes, bytesProcessed, frameBytes, 0, BYTES_PER_FRAME);
        final short[] frame = converter.bytesToShorts(frameBytes);
        if (!this.frameQueue.offer(frame)) {
          LOGGER.error("Warning: Audio frame dropped due to full queue");
        }
        bytesProcessed += BYTES_PER_FRAME;
      }

      this.sampleBuffer.reset();
      if (bytesProcessed < bufferedBytes.length) {
        this.sampleBuffer.write(bufferedBytes, bytesProcessed, bufferedBytes.length - bytesProcessed);
      }

      monoStream.close();
      stereoStream.close();
    } catch (final IOException e) {
      throw new RuntimeException("Failed to convert audio format", e);
    }
  }
}
