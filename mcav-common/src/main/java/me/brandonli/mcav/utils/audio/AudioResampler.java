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
package me.brandonli.mcav.utils.audio;

import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

/**
 * Converts interleaved PCM audio between sample rates, channel counts and sample formats with FFmpeg's
 * libswresample. Every mcav player hands audio pipelines signed 16-bit stereo samples at 48 kHz (see
 * {@link AudioFilter}); outputs that expect something else, such as a speech recognizer at 16 kHz mono, a telephony
 * bridge at 8 kHz or an engine that mixes floating point samples, use a resampler to convert them.
 *
 * <pre>{@code
 * final AudioResampler resampler = AudioResampler.fromPipelineFormat(16_000, 1, SampleFormat.SIGNED_16_BIT);
 * final AudioPipelineStep pipeline = AudioPipelineStep.of((samples, metadata) -> {
 *   final byte[] speech = resampler.resample(samples);
 *   recognizer.accept(speech);
 *   // the samples were only read, so the filter reports that it left them untouched
 *   return false;
 * });
 *
 * // once playback has ended
 * final byte[] remaining = resampler.flush();
 * recognizer.accept(remaining);
 * resampler.close();
 * }</pre>
 *
 * <p>Changing the sample rate needs a few samples of look-ahead, so the resampler holds back a short tail of every
 * buffer and emits it with the next one; {@link #flush()} drains that tail at the end of a stream. Channel counts
 * map to FFmpeg's default layouts (mono, stereo, 2.1, quad, 5.0, 5.1, 6.1 and 7.1); mixing down averages the
 * channels and mixing up spreads them without raising the volume.
 *
 * <p>A resampler holds a native context, so it must be {@linkplain #close() closed} when it is no longer needed. An
 * instance keeps state between calls and is not thread-safe: use it from one thread at a time, which is the case
 * for a filter of an audio pipeline because players run their pipelines on a single decoding thread.
 */
public final class AudioResampler implements AutoCloseable {

  /**
   * The largest channel count a resampler accepts, the number of channels of a 7.1 layout.
   */
  public static final int MAX_CHANNELS = 8;

  private final int inputSampleRate;
  private final int inputChannels;
  private final SampleFormat inputFormat;
  private final int outputSampleRate;
  private final int outputChannels;
  private final SampleFormat outputFormat;
  private final int inputFrameSize;
  private final int outputFrameSize;
  private final AVChannelLayout inputLayout;
  private final AVChannelLayout outputLayout;
  private final SwrContext context;
  private boolean closed;

  private AudioResampler(
    final int inputSampleRate,
    final int inputChannels,
    final SampleFormat inputFormat,
    final int outputSampleRate,
    final int outputChannels,
    final SampleFormat outputFormat
  ) {
    this.inputSampleRate = inputSampleRate;
    this.inputChannels = inputChannels;
    this.inputFormat = inputFormat;
    this.outputSampleRate = outputSampleRate;
    this.outputChannels = outputChannels;
    this.outputFormat = outputFormat;
    final int inputBytesPerSample = inputFormat.getBytesPerSample();
    final int outputBytesPerSample = outputFormat.getBytesPerSample();
    this.inputFrameSize = inputChannels * inputBytesPerSample;
    this.outputFrameSize = outputChannels * outputBytesPerSample;
    this.inputLayout = new AVChannelLayout();
    this.outputLayout = new AVChannelLayout();
    this.context = new SwrContext();
    avutil.av_channel_layout_default(this.inputLayout, inputChannels);
    avutil.av_channel_layout_default(this.outputLayout, outputChannels);
  }

  /**
   * Creates a resampler between two arbitrary interleaved PCM formats.
   *
   * @param inputSampleRate  the sample rate of the samples passed to {@link #resample(ByteBuffer)}, in hertz
   * @param inputChannels    the number of interleaved input channels, from 1 to {@value #MAX_CHANNELS}
   * @param inputFormat      the encoding of the input samples
   * @param outputSampleRate the sample rate of the returned samples, in hertz
   * @param outputChannels   the number of interleaved output channels, from 1 to {@value #MAX_CHANNELS}
   * @param outputFormat     the encoding of the returned samples
   * @return a new resampler, which must be closed by the caller
   * @throws IllegalArgumentException if a sample rate is not positive or a channel count is out of range
   * @throws IllegalStateException    if FFmpeg cannot set up the conversion
   */
  public static AudioResampler create(
    final int inputSampleRate,
    final int inputChannels,
    final SampleFormat inputFormat,
    final int outputSampleRate,
    final int outputChannels,
    final SampleFormat outputFormat
  ) {
    checkSampleRate(inputSampleRate, "Input");
    checkChannels(inputChannels, "Input");
    Preconditions.checkNotNull(inputFormat, "Input format must not be null");
    checkSampleRate(outputSampleRate, "Output");
    checkChannels(outputChannels, "Output");
    Preconditions.checkNotNull(outputFormat, "Output format must not be null");

    final AudioResampler resampler = new AudioResampler(
      inputSampleRate,
      inputChannels,
      inputFormat,
      outputSampleRate,
      outputChannels,
      outputFormat
    );
    resampler.configure();
    return resampler;
  }

  /**
   * Creates a resampler whose input is the audio every mcav player hands to audio pipelines: signed 16-bit stereo
   * samples at {@value AudioFilter#SAMPLE_RATE} Hz.
   *
   * @param outputSampleRate the sample rate of the returned samples, in hertz
   * @param outputChannels   the number of interleaved output channels, from 1 to {@value #MAX_CHANNELS}
   * @param outputFormat     the encoding of the returned samples
   * @return a new resampler, which must be closed by the caller
   * @throws IllegalArgumentException if the sample rate is not positive or the channel count is out of range
   * @throws IllegalStateException    if FFmpeg cannot set up the conversion
   */
  public static AudioResampler fromPipelineFormat(final int outputSampleRate, final int outputChannels, final SampleFormat outputFormat) {
    return create(
      AudioFilter.SAMPLE_RATE,
      AudioFilter.CHANNELS,
      SampleFormat.SIGNED_16_BIT,
      outputSampleRate,
      outputChannels,
      outputFormat
    );
  }

  private static void checkSampleRate(final int sampleRate, final String side) {
    Preconditions.checkArgument(sampleRate > 0, "%s sample rate must be positive, was %s", side, sampleRate);
  }

  private static void checkChannels(final int channels, final String side) {
    final boolean inRange = channels >= 1 && channels <= MAX_CHANNELS;
    Preconditions.checkArgument(inRange, "%s channels must be between 1 and %s, was %s", side, MAX_CHANNELS, channels);
  }

  private void configure() {
    final Pointer noLoggingContext = new Pointer();
    final int outputFfmpegFormat = this.outputFormat.getFfmpegFormat();
    final int inputFfmpegFormat = this.inputFormat.getFfmpegFormat();
    final int allocation = swresample.swr_alloc_set_opts2(
      this.context,
      this.outputLayout,
      outputFfmpegFormat,
      this.outputSampleRate,
      this.inputLayout,
      inputFfmpegFormat,
      this.inputSampleRate,
      0,
      noLoggingContext
    );
    this.closeOnFailure(allocation, "allocate the resampler");

    final int initialization = swresample.swr_init(this.context);
    this.closeOnFailure(initialization, "initialize the resampler");
  }

  /**
   * Converts a buffer of interleaved input samples.
   *
   * <p>The samples between the position and the limit of the buffer are read without changing the buffer, so the
   * buffer of an audio pipeline can be passed directly. A trailing incomplete frame is ignored. When the sample rate
   * changes, the last few samples are held back until the next call or {@link #flush()}, so the output of a single
   * call can be slightly shorter than the rate ratio suggests while the output of a whole stream is not.
   *
   * @param input interleaved samples in the input format, in native byte order
   * @return the converted interleaved samples in the output format and native byte order, possibly empty
   * @throws IllegalStateException if the resampler is closed or FFmpeg fails to convert the samples
   */
  public byte[] resample(final ByteBuffer input) {
    Preconditions.checkNotNull(input, "Input must not be null");
    this.ensureOpen();

    final ByteBuffer source = input.duplicate();
    final int frames = source.remaining() / this.inputFrameSize;
    if (frames == 0) {
      return new byte[0];
    }

    final byte[] samples = new byte[frames * this.inputFrameSize];
    source.get(samples);
    try (final BytePointer inputData = new BytePointer(samples); final PointerPointer<BytePointer> inputPlanes = new PointerPointer<>(1L)) {
      inputPlanes.put(0, inputData);
      return this.convert(inputPlanes, frames);
    }
  }

  /**
   * Drains the samples held back by earlier calls to {@link #resample(ByteBuffer)} at the end of a stream, then
   * resets the resampler, so it can be reused for a new stream that does not continue the previous one.
   *
   * @return the remaining converted interleaved samples, empty when nothing was held back
   * @throws IllegalStateException if the resampler is closed or FFmpeg fails to drain or reset it
   */
  public byte[] flush() {
    this.ensureOpen();
    try (final PointerPointer<BytePointer> noInput = new PointerPointer<>()) {
      final byte[] remaining = this.convert(noInput, 0);
      final int reset = swresample.swr_init(this.context);
      checkResult(reset, "reset the resampler");
      return remaining;
    }
  }

  private byte[] convert(final PointerPointer<BytePointer> inputPlanes, final int inputFrames) {
    final int upperBound = swresample.swr_get_out_samples(this.context, inputFrames);
    final int boundFrames = checkResult(upperBound, "compute the output size");
    final int capacityFrames = Math.max(boundFrames, 1);
    final long capacityBytes = (long) capacityFrames * this.outputFrameSize;

    try (
      final BytePointer outputData = new BytePointer(capacityBytes);
      final PointerPointer<BytePointer> outputPlanes = new PointerPointer<>(1L)
    ) {
      outputPlanes.put(0, outputData);
      final int converted = swresample.swr_convert(this.context, outputPlanes, capacityFrames, inputPlanes, inputFrames);
      final int convertedFrames = checkResult(converted, "convert the samples");
      final int convertedBytes = Math.multiplyExact(convertedFrames, this.outputFrameSize);
      final byte[] output = new byte[convertedBytes];
      outputData.get(output);
      return output;
    }
  }

  private void ensureOpen() {
    Preconditions.checkState(!this.closed, "The resampler has been closed");
  }

  /**
   * Closes this resampler when a native setup call failed, so no native memory leaks, then reports the failure.
   *
   * @param result the return value of the native call
   * @param action what the call did, completing the sentence "Could not ..."
   * @throws IllegalStateException if the result is negative
   */
  void closeOnFailure(final int result, final String action) {
    if (result < 0) {
      this.close();
    }
    checkResult(result, action);
  }

  /**
   * Checks the return value of a native FFmpeg call, where negative values are error codes.
   *
   * @param result the return value of the native call
   * @param action what the call did, completing the sentence "Could not ..."
   * @return the result, when it is not negative
   * @throws IllegalStateException with FFmpeg's description of the error if the result is negative
   */
  static int checkResult(final int result, final String action) {
    if (result < 0) {
      final String message = describeError(result, action);
      throw new IllegalStateException(message);
    }
    return result;
  }

  private static String describeError(final int code, final String action) {
    try (final BytePointer description = new BytePointer(avutil.AV_ERROR_MAX_STRING_SIZE)) {
      avutil.av_strerror(code, description, avutil.AV_ERROR_MAX_STRING_SIZE);
      // without a limit JavaCPP reads up to the terminating zero instead of the whole buffer
      description.limit(0);
      final String text = description.getString();
      return "Could not " + action + ": " + text + " (FFmpeg error " + code + ")";
    }
  }

  /**
   * Returns the sample rate of the samples passed to {@link #resample(ByteBuffer)}.
   *
   * @return the input sample rate in hertz
   */
  public int getInputSampleRate() {
    return this.inputSampleRate;
  }

  /**
   * Returns the number of interleaved channels of the samples passed to {@link #resample(ByteBuffer)}.
   *
   * @return the input channel count
   */
  public int getInputChannels() {
    return this.inputChannels;
  }

  /**
   * Returns the encoding of the samples passed to {@link #resample(ByteBuffer)}.
   *
   * @return the input sample format
   */
  public SampleFormat getInputFormat() {
    return this.inputFormat;
  }

  /**
   * Returns the sample rate of the samples this resampler returns.
   *
   * @return the output sample rate in hertz
   */
  public int getOutputSampleRate() {
    return this.outputSampleRate;
  }

  /**
   * Returns the number of interleaved channels of the samples this resampler returns.
   *
   * @return the output channel count
   */
  public int getOutputChannels() {
    return this.outputChannels;
  }

  /**
   * Returns the encoding of the samples this resampler returns.
   *
   * @return the output sample format
   */
  public SampleFormat getOutputFormat() {
    return this.outputFormat;
  }

  /**
   * Returns whether this resampler has been closed and can no longer convert samples.
   *
   * @return {@code true} once {@link #close()} has been called
   */
  public boolean isClosed() {
    return this.closed;
  }

  /**
   * Frees the native resampling context. Samples still held back are discarded, so call {@link #flush()} first to
   * keep them. Closing an already closed resampler does nothing.
   */
  @Override
  public void close() {
    if (this.closed) {
      return;
    }
    this.closed = true;
    swresample.swr_free(this.context);
    avutil.av_channel_layout_uninit(this.inputLayout);
    avutil.av_channel_layout_uninit(this.outputLayout);
    this.inputLayout.close();
    this.outputLayout.close();
  }
}
