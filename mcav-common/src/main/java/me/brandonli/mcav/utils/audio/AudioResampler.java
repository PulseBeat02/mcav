package me.brandonli.mcav.utils.audio;

import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;

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

  public AudioResampler(final OriginalAudioMetadata metadata, final int outputSampleFormat, final int outputSampleRate, final int outputChannels) {
    this(metadata.getSamplingFormat(), metadata.getAudioSampleRate(), metadata.getAudioChannels(), outputSampleFormat, outputSampleRate, outputChannels);
  }

  @SuppressWarnings("all") // checker
  public AudioResampler(final int inputSampleFormat, final int inputSampleRate, final int inputChannels, final int outputSampleFormat, final int outputSampleRate, final int outputChannels) {
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
    int ret = swresample.swr_alloc_set_opts2(this.context, this.outChLayout, outputSampleFormat, outputSampleRate, this.inChLayout, inputSampleFormat, inputSampleRate, 0, null);
    if (ret < 0) {
      throw new AssertionError("Failed to set SwrContext options: " + ret);
    }

    ret = swresample.swr_init(this.context);
    if (ret < 0) {
      throw new AssertionError("Failed to initialize SwrContext: " + ret);
    }
  }

  @SuppressWarnings("all") // checker
  public byte[] resample(final byte[] input) {
    final int inputSamples = input.length / (this.inputBytesPerSample * this.inputChannels);
    final long outputSamples = avutil.av_rescale_rnd(swresample.swr_get_delay(this.context, this.inputSampleRate) + inputSamples, this.outputSampleRate, this.inputSampleRate, avutil.AV_ROUND_UP);
    final int outputBufferSize = (int) outputSamples * this.outputBytesPerSample * this.outputChannels;

    try (final BytePointer inputPtr = new BytePointer(input); final BytePointer outputPtr = new BytePointer(outputBufferSize); final PointerPointer<BytePointer> outArgs = new PointerPointer<>(outputPtr); final PointerPointer<BytePointer> inArgs = new PointerPointer<>(inputPtr)) {
      BytePointer[] outPtrs = new BytePointer[]{outputPtr};
      BytePointer[] inPtrs = new BytePointer[]{inputPtr};
      int convertedSamples = swresample.swr_convert(this.context,
              new PointerPointer(outPtrs), (int) outputSamples,
              new PointerPointer(inPtrs), inputSamples);
      if (convertedSamples < 0) {
        throw new AssertionError("swr_convert failed: " + convertedSamples);
      }

      final int actualOutputSize = convertedSamples * this.outputBytesPerSample * this.outputChannels;
      final byte[] output = new byte[actualOutputSize];
      outputPtr.get(output);

      return output;
    }
  }

  @Override
  public void close() {
    swresample.swr_free(this.context);
    avutil.av_channel_layout_uninit(this.inChLayout);
    avutil.av_channel_layout_uninit(this.outChLayout);
  }
}