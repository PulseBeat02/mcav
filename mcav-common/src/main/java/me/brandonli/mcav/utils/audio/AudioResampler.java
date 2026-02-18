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

import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;

/**
 * A utility class for resampling audio data using FFmpeg's libswresample.
 */
public final class AudioResampler implements AutoCloseable {

  private final SwrContext context;
  private final AVChannelLayout inChLayout;
  private final AVChannelLayout outChLayout;

  private final int inputSampleRate;
  private final int inputChannels;
  private final int outputSampleRate;
  private final int outputChannels;
  private final int inputBytesPerSample;
  private final int outputBytesPerSample;

  /**
   * Constructs an AudioResampler with the specified input metadata and desired output format.
   * @param metadata the original audio metadata containing the input format information
   * @param outputSampleFormat the desired output sample format (e.g., AV_SAMPLE_FMT_S16)
   * @param outputSampleRate the desired output sample rate (e.g., 44100)
   * @param outputChannels the desired number of output channels (e.g., 2 for stereo)
   */
  public AudioResampler(
    final OriginalAudioMetadata metadata,
    final int outputSampleFormat,
    final int outputSampleRate,
    final int outputChannels
  ) {
    this(
      metadata.getSamplingFormat(),
      metadata.getAudioSampleRate(),
      metadata.getAudioChannels(),
      outputSampleFormat,
      outputSampleRate,
      outputChannels
    );
  }

  /**
   * Constructs an AudioResampler with the specified input format and desired output format.
   * @param inputSampleFormat the input sample format (e.g., AV_SAMPLE_FMT_S16)
   * @param inputSampleRate the input sample rate (e.g., 48000)
   * @param inputChannels the number of input channels (e.g., 2 for stereo)
   * @param outputSampleFormat the desired output sample format (e.g., AV_SAMPLE_FMT_S16)
   * @param outputSampleRate the desired output sample rate (e.g., 44100)
   * @param outputChannels the desired number of output channels (e.g., 2 for stereo)
   * @throws AssertionError if the SwrContext cannot be initialized with the specified parameters
   */
  @SuppressWarnings("all") // checker
  public AudioResampler(
    final int inputSampleFormat,
    final int inputSampleRate,
    final int inputChannels,
    final int outputSampleFormat,
    final int outputSampleRate,
    final int outputChannels
  ) {
    this.inputSampleRate = inputSampleRate;
    this.inputChannels = inputChannels;
    this.outputSampleRate = outputSampleRate;
    this.outputChannels = outputChannels;
    this.inputBytesPerSample = avutil.av_get_bytes_per_sample(inputSampleFormat);
    this.outputBytesPerSample = avutil.av_get_bytes_per_sample(outputSampleFormat);
    this.inChLayout = new AVChannelLayout();
    this.outChLayout = new AVChannelLayout();
    avutil.av_channel_layout_default(this.inChLayout, inputChannels);
    avutil.av_channel_layout_default(this.outChLayout, outputChannels);

    this.context = new SwrContext();
    int ret = swresample.swr_alloc_set_opts2(
      this.context,
      this.outChLayout,
      outputSampleFormat,
      outputSampleRate,
      this.inChLayout,
      inputSampleFormat,
      inputSampleRate,
      0,
      null
    );
    if (ret < 0) {
      throw new AssertionError("Failed to set SwrContext options: " + ret);
    }

    ret = swresample.swr_init(this.context);
    if (ret < 0) {
      throw new AssertionError("Failed to initialize SwrContext: " + ret);
    }
  }

  /**
   * Resamples the given input audio data and returns the resampled output data.
   * @param input the input audio data as a byte array, where the length should be a multiple of (inputBytesPerSample * inputChannels)
   * @return a byte array containing the resampled audio data, where the length will be a multiple of (outputBytesPerSample * outputChannels)
   */
  @SuppressWarnings("all") // checker
  public byte[] resample(final byte[] input) {
    final int inputSamples = input.length / (this.inputBytesPerSample * this.inputChannels);
    final long outputSamples = avutil.av_rescale_rnd(
      swresample.swr_get_delay(this.context, this.inputSampleRate) + inputSamples,
      this.outputSampleRate,
      this.inputSampleRate,
      avutil.AV_ROUND_UP
    );
    final int outputBufferSize = (int) outputSamples * this.outputBytesPerSample * this.outputChannels;

    try (
      final BytePointer inputPtr = new BytePointer(input);
      final BytePointer outputPtr = new BytePointer(outputBufferSize);
      final PointerPointer<BytePointer> outArgs = new PointerPointer<>(outputPtr);
      final PointerPointer<BytePointer> inArgs = new PointerPointer<>(inputPtr)
    ) {
      BytePointer[] outPtrs = new BytePointer[] { outputPtr };
      BytePointer[] inPtrs = new BytePointer[] { inputPtr };
      int convertedSamples = swresample.swr_convert(
        this.context,
        new PointerPointer(outPtrs),
        (int) outputSamples,
        new PointerPointer(inPtrs),
        inputSamples
      );
      if (convertedSamples < 0) {
        throw new AssertionError("swr_convert failed: " + convertedSamples);
      }

      final int actualOutputSize = convertedSamples * this.outputBytesPerSample * this.outputChannels;
      final byte[] output = new byte[actualOutputSize];
      outputPtr.get(output);

      return output;
    }
  }

  /**
   * Closes the AudioResampler and releases any native resources.
   */
  @Override
  public void close() {
    swresample.swr_free(this.context);
    avutil.av_channel_layout_uninit(this.inChLayout);
    avutil.av_channel_layout_uninit(this.outChLayout);
  }
}
