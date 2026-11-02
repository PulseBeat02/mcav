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
package me.brandonli.mcav.media.player.multimedia.vlc;

import com.google.common.annotations.VisibleForTesting;
import com.sun.jna.Pointer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.bytedeco.ffmpeg.global.avutil;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;

// Copies audio samples out of VLC and runs the audio pipeline of a player on a render thread. VLC is asked for 16-bit
// stereo samples at 48 kHz in the byte order of the machine, which is the only 16-bit format every VLC build accepts.
// The samples are copied into little-endian order, the format the audio pipeline expects, which is a plain copy on
// little-endian machines and swaps the bytes of every sample on big-endian ones. VLC calls play on its audio output
// thread, which must not block, so when the pipeline falls far behind, the oldest chunks are dropped to keep the
// latency bounded. Pausing, seeking, and stopping drop the queued chunks, so no stale audio plays afterward.
final class AudioRenderer extends AudioCallbackAdapter {

  private static final String THREAD_NAME = "mcav-vlc-render-audio";

  private static final int QUEUE_CAPACITY = 64;
  private static final long QUEUE_POLL_MILLISECONDS = 50L;
  private static final String PCM_CODEC = "pcm_s16le";
  private static final int PCM_BITRATE = AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE * Byte.SIZE;
  private static final OriginalAudioMetadata METADATA = OriginalAudioMetadata.of(
    PCM_CODEC,
    PCM_BITRATE,
    AudioFilter.SAMPLE_RATE,
    AudioFilter.CHANNELS,
    avutil.AV_SAMPLE_FMT_S16
  );

  private final VideoPlayerMultiplexer owner;
  private final BlockingQueue<ByteBuffer> queue;
  private final RenderThread thread;

  // Samples are dropped until start() is called.
  AudioRenderer(final VideoPlayerMultiplexer owner) {
    this.owner = owner;
    this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    this.thread = new RenderThread(THREAD_NAME, failure -> reportFailure(owner, "Audio rendering failed.", failure));
  }

  private static void reportFailure(final VideoPlayerMultiplexer owner, final String message, final Throwable failure) {
    final BiConsumer<String, Throwable> handler = owner.getExceptionHandler();
    handler.accept(message, failure);
  }

  void start() {
    this.thread.start(this::renderNext, this.queue::clear);
  }

  void stop() {
    this.thread.stop();
    this.queue.clear();
  }

  @Override
  public void play(final MediaPlayer mediaPlayer, final Pointer samples, final int sampleCount, final long pts) {
    if (!this.thread.isRunning()) {
      return;
    }

    final AudioAttachableCallback callback = this.owner.getAudioAttachableCallback();
    final AudioPipelineStep step = callback.retrieve();
    final boolean empty = step.isNoOp();
    if (empty) {
      return;
    }

    final int length = sampleCount * AudioFilter.FRAME_SIZE;
    final ByteBuffer nativeBuffer = samples.getByteBuffer(0, length);
    final ByteOrder nativeOrder = ByteOrder.nativeOrder();
    final ByteBuffer copy = copySamples(nativeBuffer, nativeOrder);
    while (!this.queue.offer(copy)) {
      this.queue.poll();
    }
  }

  // Drops the queued samples, because VLC paused the playback and they would otherwise keep playing.
  @Override
  public void pause(final MediaPlayer mediaPlayer, final long pts) {
    this.queue.clear();
  }

  // Drops the queued samples, because VLC discards its buffered audio, for example when seeking.
  @Override
  public void flush(final MediaPlayer mediaPlayer, final long pts) {
    this.queue.clear();
  }

  @VisibleForTesting
  static ByteBuffer copySamples(final ByteBuffer nativeSamples, final ByteOrder order) {
    final ByteBuffer source = nativeSamples.duplicate();
    source.order(order);
    final ShortBuffer sourceSamples = source.asShortBuffer();
    final int length = source.remaining();
    final ByteBuffer copy = ByteBuffer.allocate(length);
    copy.order(ByteOrder.LITTLE_ENDIAN);
    final ShortBuffer targetSamples = copy.asShortBuffer();
    targetSamples.put(sourceSamples);
    return copy;
  }

  private void renderNext() throws InterruptedException {
    final ByteBuffer samples = this.queue.poll(QUEUE_POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
    if (samples != null) {
      this.runPipeline(samples);
    }
  }

  private void runPipeline(final ByteBuffer samples) {
    final AudioAttachableCallback callback = this.owner.getAudioAttachableCallback();
    try {
      final AudioPipelineStep pipeline = callback.retrieve();
      pipeline.processAll(samples, METADATA);
    } catch (final RuntimeException | Error exception) {
      // filters are user code, and native filters can throw a LinkageError, so any failure is reported and the render
      // thread keeps playing; only a virtual machine error ends the thread, because reporting it would hide it
      ThrowableUtils.throwIfFatal(exception);
      reportFailure(this.owner, "Audio filter failed.", exception);
    }
  }
}
