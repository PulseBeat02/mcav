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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AudioResampler}.
 */
final class AudioResamplerTest {

  private static final int PIPELINE_RATE = 48_000;
  private static final int SPEECH_RATE = 16_000;
  private static final int TONE_FREQUENCY = 1_000;
  private static final int TONE_AMPLITUDE = 10_000;
  private static final int EINVAL = -22;
  private static final int STEADY_STATE_START = 1_000;
  private static final int STEADY_STATE_END = 15_000;

  private static ByteBuffer samples16(final short... samples) {
    final ByteBuffer buffer = ByteBuffer.allocate(samples.length * Short.BYTES);
    final ByteOrder nativeOrder = ByteOrder.nativeOrder();
    buffer.order(nativeOrder);
    for (final short sample : samples) {
      buffer.putShort(sample);
    }
    buffer.flip();
    return buffer;
  }

  private static ByteBuffer stereoTone(final int frames) {
    final short[] samples = new short[frames * 2];
    for (int frame = 0; frame < frames; frame++) {
      final double phase = (2.0 * Math.PI * TONE_FREQUENCY * frame) / PIPELINE_RATE + Math.PI / 4.0;
      final double value = Math.sin(phase);
      final short sample = (short) Math.round(value * TONE_AMPLITUDE);
      samples[frame * 2] = sample;
      samples[frame * 2 + 1] = sample;
    }
    return samples16(samples);
  }

  private static short[] toShorts(final byte[] bytes) {
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    final ByteOrder nativeOrder = ByteOrder.nativeOrder();
    buffer.order(nativeOrder);
    final ShortBuffer view = buffer.asShortBuffer();
    final int count = view.remaining();
    final short[] samples = new short[count];
    view.get(samples);
    return samples;
  }

  private static float[] toFloats(final byte[] bytes) {
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    final ByteOrder nativeOrder = ByteOrder.nativeOrder();
    buffer.order(nativeOrder);
    final FloatBuffer view = buffer.asFloatBuffer();
    final int count = view.remaining();
    final float[] samples = new float[count];
    view.get(samples);
    return samples;
  }

  private static byte[] concat(final byte[] first, final byte[] second) {
    final byte[] joined = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, joined, first.length, second.length);
    return joined;
  }

  private static byte[] bytesOf(final ByteBuffer buffer) {
    final ByteBuffer copy = buffer.duplicate();
    final int count = copy.remaining();
    final byte[] bytes = new byte[count];
    copy.get(bytes);
    return bytes;
  }

  private static int steadyStateZeroCrossings(final short[] samples) {
    int crossings = 0;
    for (int index = STEADY_STATE_START + 1; index < STEADY_STATE_END; index++) {
      final boolean previousNegative = samples[index - 1] < 0;
      final boolean currentNegative = samples[index] < 0;
      if (previousNegative != currentNegative) {
        crossings++;
      }
    }
    return crossings;
  }

  private static int peak(final short[] samples, final int from, final int to) {
    int peak = 0;
    for (int index = from; index < to; index++) {
      final int magnitude = Math.abs(samples[index]);
      peak = Math.max(peak, magnitude);
    }
    return peak;
  }

  private static void assertWithin(final int expected, final int actual, final int tolerance) {
    final int difference = Math.abs(expected - actual);
    assertTrue(difference <= tolerance, "expected " + expected + " +- " + tolerance + " but was " + actual);
  }

  private static AudioResampler pipelineIdentity() {
    return AudioResampler.fromPipelineFormat(PIPELINE_RATE, AudioFilter.CHANNELS, SampleFormat.SIGNED_16_BIT);
  }

  private static void createAndClose(
    final int inputSampleRate,
    final int inputChannels,
    final SampleFormat inputFormat,
    final int outputSampleRate,
    final int outputChannels,
    final SampleFormat outputFormat
  ) {
    try (
      final AudioResampler resampler = AudioResampler.create(
        inputSampleRate,
        inputChannels,
        inputFormat,
        outputSampleRate,
        outputChannels,
        outputFormat
      )
    ) {
      final boolean closed = resampler.isClosed();
      assertFalse(closed);
    }
  }

  private static void createFromPipelineAndClose(final int outputSampleRate, final int outputChannels, final SampleFormat outputFormat) {
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(outputSampleRate, outputChannels, outputFormat)) {
      final boolean closed = resampler.isClosed();
      assertFalse(closed);
    }
  }

  @Test
  void downsamplesPipelineAudioToSpeechAudioOfTheRightLength() {
    final ByteBuffer tone = stereoTone(PIPELINE_RATE);
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      final byte[] converted = resampler.resample(tone);
      final byte[] remaining = resampler.flush();
      final byte[] whole = concat(converted, remaining);
      final short[] mono = toShorts(whole);
      assertEquals(0, whole.length % Short.BYTES);
      assertWithin(SPEECH_RATE, mono.length, 1);
    }
  }

  @Test
  void downsamplingKeepsTheFrequencyAndLoudnessOfATone() {
    final ByteBuffer tone = stereoTone(PIPELINE_RATE);
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      final byte[] converted = resampler.resample(tone);
      final byte[] remaining = resampler.flush();
      final byte[] whole = concat(converted, remaining);
      final short[] mono = toShorts(whole);
      final int crossings = steadyStateZeroCrossings(mono);
      final int expectedCrossings = (2 * TONE_FREQUENCY * (STEADY_STATE_END - STEADY_STATE_START)) / SPEECH_RATE;
      final int loudest = peak(mono, STEADY_STATE_START, STEADY_STATE_END);
      assertWithin(expectedCrossings, crossings, 1);
      assertWithin(TONE_AMPLITUDE, loudest, TONE_AMPLITUDE / 50);
    }
  }

  @Test
  void convertingAStreamInChunksMatchesConvertingItAtOnce() {
    final ByteBuffer tone = stereoTone(PIPELINE_RATE);
    final byte[] atOnce;
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      final byte[] converted = resampler.resample(tone);
      final byte[] remaining = resampler.flush();
      atOnce = concat(converted, remaining);
    }

    byte[] chunked = new byte[0];
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      final int chunkBytes = (PIPELINE_RATE / 10) * AudioFilter.FRAME_SIZE;
      for (int offset = 0; offset < tone.limit(); offset += chunkBytes) {
        final ByteBuffer chunk = tone.slice(offset, chunkBytes);
        final byte[] converted = resampler.resample(chunk);
        chunked = concat(chunked, converted);
      }
      final byte[] remaining = resampler.flush();
      chunked = concat(chunked, remaining);
    }
    assertArrayEquals(atOnce, chunked);
  }

  @Test
  void flushDrainsTheSamplesHeldBackForLookAhead() {
    final ByteBuffer tone = stereoTone(4_800);
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      final byte[] converted = resampler.resample(tone);
      final byte[] remaining = resampler.flush();
      final int convertedFrames = converted.length / Short.BYTES;
      final int remainingFrames = remaining.length / Short.BYTES;
      assertTrue(convertedFrames < 1_600, "the resampler must hold back look-ahead samples");
      assertTrue(remainingFrames > 0, "flush must return the held back samples");
      assertWithin(1_600, convertedFrames + remainingFrames, 1);
    }
  }

  @Test
  void flushResetsTheResamplerForANewStream() {
    final ByteBuffer tone = stereoTone(4_800);
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      final byte[] firstConverted = resampler.resample(tone);
      final byte[] firstRemaining = resampler.flush();
      final byte[] secondConverted = resampler.resample(tone);
      final byte[] secondRemaining = resampler.flush();
      final byte[] nothingLeft = resampler.flush();
      assertArrayEquals(firstConverted, secondConverted);
      assertArrayEquals(firstRemaining, secondRemaining);
      assertEquals(0, nothingLeft.length);
    }
  }

  @Test
  void flushingAFreshResamplerReturnsNothing() {
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      final byte[] remaining = resampler.flush();
      assertEquals(0, remaining.length);
    }
  }

  @Test
  void mixesStereoDownToMonoByAveraging() {
    final short[] frames = new short[200];
    for (int index = 0; index < frames.length; index += 2) {
      frames[index] = 1_000;
      frames[index + 1] = 3_000;
    }
    final ByteBuffer stereo = samples16(frames);
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(PIPELINE_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      final byte[] converted = resampler.resample(stereo);
      final byte[] remaining = resampler.flush();
      final short[] mono = toShorts(converted);
      final short[] expected = new short[100];
      Arrays.fill(expected, (short) 2_000);
      assertArrayEquals(expected, mono);
      assertEquals(0, remaining.length);
    }
  }

  @Test
  void acceptsEveryChannelCountUpToTheMaximumWithoutRaisingTheVolume() {
    final ByteBuffer stereo = samples16((short) 8_000, (short) -8_000, (short) 8_000, (short) -8_000);
    for (int channels = 1; channels <= AudioResampler.MAX_CHANNELS; channels++) {
      try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(PIPELINE_RATE, channels, SampleFormat.SIGNED_16_BIT)) {
        final byte[] converted = resampler.resample(stereo);
        final short[] samples = toShorts(converted);
        final int loudest = peak(samples, 0, samples.length);
        assertEquals(2 * channels, samples.length, "channels: " + channels);
        assertTrue(loudest <= 8_000, "channels: " + channels + ", peak: " + loudest);
      }
    }
  }

  @Test
  void convertsSigned16BitSamplesToFloatingPoint() {
    final ByteBuffer input = samples16((short) 16_384, (short) -16_384, (short) 0, (short) 8_192, Short.MIN_VALUE, Short.MAX_VALUE);
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(PIPELINE_RATE, 2, SampleFormat.FLOAT_32_BIT)) {
      final byte[] converted = resampler.resample(input);
      final float[] samples = toFloats(converted);
      final float[] expected = { 0.5f, -0.5f, 0.0f, 0.25f, -1.0f, 32_767.0f / 32_768.0f };
      assertArrayEquals(expected, samples, 1.0e-6f);
    }
  }

  @Test
  void convertsFloatingPointSamplesToSigned16BitAndClips() {
    final float[] floats = { 0.5f, -0.25f, -1.0f, 2.0f };
    final ByteBuffer input = ByteBuffer.allocate(floats.length * Float.BYTES);
    final ByteOrder nativeOrder = ByteOrder.nativeOrder();
    input.order(nativeOrder);
    for (final float sample : floats) {
      input.putFloat(sample);
    }
    input.flip();
    try (
      final AudioResampler resampler = AudioResampler.create(
        PIPELINE_RATE,
        2,
        SampleFormat.FLOAT_32_BIT,
        PIPELINE_RATE,
        2,
        SampleFormat.SIGNED_16_BIT
      )
    ) {
      final byte[] converted = resampler.resample(input);
      final short[] samples = toShorts(converted);
      final short[] expected = { 16_384, -8_192, Short.MIN_VALUE, Short.MAX_VALUE };
      assertArrayEquals(expected, samples);
    }
  }

  @Test
  void roundTripsThroughEveryFormat() {
    final ByteBuffer input = samples16(Short.MIN_VALUE, (short) -256, (short) 0, (short) 256, (short) 32_512, (short) 1_024);
    final byte[] original = bytesOf(input);
    for (final SampleFormat format : SampleFormat.values()) {
      try (
        final AudioResampler there = AudioResampler.fromPipelineFormat(PIPELINE_RATE, 2, format);
        final AudioResampler back = AudioResampler.create(PIPELINE_RATE, 2, format, PIPELINE_RATE, 2, SampleFormat.SIGNED_16_BIT)
      ) {
        final byte[] converted = there.resample(input);
        final ByteBuffer convertedBuffer = ByteBuffer.wrap(converted);
        final byte[] restored = back.resample(convertedBuffer);
        final int bytesPerSample = format.getBytesPerSample();
        final String formatName = format.name();
        assertEquals(6 * bytesPerSample, converted.length, formatName);
        assertArrayEquals(original, restored, formatName);
      }
    }
  }

  @Test
  void identityConversionReturnsTheSameSamples() {
    final ByteBuffer input = samples16((short) 1, (short) -2, (short) 300, (short) -400, Short.MAX_VALUE, Short.MIN_VALUE);
    final byte[] original = bytesOf(input);
    try (final AudioResampler resampler = pipelineIdentity()) {
      final byte[] converted = resampler.resample(input);
      final byte[] remaining = resampler.flush();
      assertArrayEquals(original, converted);
      assertEquals(0, remaining.length);
    }
  }

  @Test
  void leavesTheCallersBufferUnchanged() {
    final ByteBuffer source = samples16((short) 9, (short) 9, (short) 1, (short) 2, (short) 3, (short) 4, (short) 9, (short) 9);
    final int sourceBytes = source.remaining();
    final ByteBuffer direct = ByteBuffer.allocateDirect(sourceBytes);
    direct.put(source);
    direct.position(4);
    direct.limit(12);
    direct.order(ByteOrder.BIG_ENDIAN);
    final ByteBuffer readOnly = direct.asReadOnlyBuffer();
    readOnly.order(ByteOrder.BIG_ENDIAN);
    final byte[] expected = bytesOf(readOnly);
    try (final AudioResampler resampler = pipelineIdentity()) {
      final byte[] converted = resampler.resample(readOnly);
      final int position = readOnly.position();
      final int limit = readOnly.limit();
      final ByteOrder order = readOnly.order();
      final short[] samples = toShorts(converted);
      final short[] expectedSamples = { 1, 2, 3, 4 };
      assertArrayEquals(expected, converted);
      assertArrayEquals(expectedSamples, samples);
      assertEquals(4, position);
      assertEquals(12, limit);
      assertEquals(ByteOrder.BIG_ENDIAN, order);
    }
  }

  @Test
  void ignoresATrailingIncompleteFrame() {
    final ByteBuffer input = ByteBuffer.allocate(3 * AudioFilter.FRAME_SIZE + 3);
    final ByteOrder nativeOrder = ByteOrder.nativeOrder();
    input.order(nativeOrder);
    for (short sample = 1; sample <= 6; sample++) {
      input.putShort(sample);
    }
    input.put((byte) 7);
    input.put((byte) 8);
    input.put((byte) 9);
    input.flip();
    try (final AudioResampler resampler = pipelineIdentity()) {
      final byte[] converted = resampler.resample(input);
      final short[] samples = toShorts(converted);
      final short[] expected = { 1, 2, 3, 4, 5, 6 };
      assertArrayEquals(expected, samples);
    }
  }

  @Test
  void returnsNothingForInputWithoutACompleteFrame() {
    final ByteBuffer empty = ByteBuffer.allocate(0);
    final ByteBuffer partial = ByteBuffer.allocate(AudioFilter.FRAME_SIZE - 1);
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      final byte[] fromEmpty = resampler.resample(empty);
      final byte[] fromPartial = resampler.resample(partial);
      final byte[] remaining = resampler.flush();
      assertEquals(0, fromEmpty.length);
      assertEquals(0, fromPartial.length);
      assertEquals(0, remaining.length);
    }
  }

  @Test
  void reportsItsConfiguration() {
    try (
      final AudioResampler resampler = AudioResampler.create(44_100, 1, SampleFormat.FLOAT_64_BIT, 8_000, 6, SampleFormat.UNSIGNED_8_BIT)
    ) {
      final int inputSampleRate = resampler.getInputSampleRate();
      final int inputChannels = resampler.getInputChannels();
      final SampleFormat inputFormat = resampler.getInputFormat();
      final int outputSampleRate = resampler.getOutputSampleRate();
      final int outputChannels = resampler.getOutputChannels();
      final SampleFormat outputFormat = resampler.getOutputFormat();
      assertEquals(44_100, inputSampleRate);
      assertEquals(1, inputChannels);
      assertEquals(SampleFormat.FLOAT_64_BIT, inputFormat);
      assertEquals(8_000, outputSampleRate);
      assertEquals(6, outputChannels);
      assertEquals(SampleFormat.UNSIGNED_8_BIT, outputFormat);
    }
  }

  @Test
  void pipelineFormatMatchesWhatThePlayersDeliver() {
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(8_000, 1, SampleFormat.SIGNED_32_BIT)) {
      final int inputSampleRate = resampler.getInputSampleRate();
      final int inputChannels = resampler.getInputChannels();
      final SampleFormat inputFormat = resampler.getInputFormat();
      final int bytesPerSample = inputFormat.getBytesPerSample();
      final int outputSampleRate = resampler.getOutputSampleRate();
      final int outputChannels = resampler.getOutputChannels();
      final SampleFormat outputFormat = resampler.getOutputFormat();
      assertEquals(AudioFilter.SAMPLE_RATE, inputSampleRate);
      assertEquals(AudioFilter.CHANNELS, inputChannels);
      assertEquals(SampleFormat.SIGNED_16_BIT, inputFormat);
      assertEquals(AudioFilter.BYTES_PER_SAMPLE, bytesPerSample);
      assertEquals(8_000, outputSampleRate);
      assertEquals(1, outputChannels);
      assertEquals(SampleFormat.SIGNED_32_BIT, outputFormat);
    }
  }

  @Test
  void cannotBeUsedOnceClosed() {
    final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT);
    final boolean closedBefore = resampler.isClosed();
    resampler.close();
    final boolean closedAfter = resampler.isClosed();
    final ByteBuffer tone = stereoTone(10);
    assertFalse(closedBefore);
    assertTrue(closedAfter);
    assertThrows(IllegalStateException.class, () -> resampler.resample(tone));
    assertThrows(IllegalStateException.class, resampler::flush);
  }

  @Test
  void closingTwiceIsHarmless() {
    final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT);
    resampler.close();
    resampler.close();
    final boolean closed = resampler.isClosed();
    assertTrue(closed);
  }

  @Test
  void rejectsInvalidSampleRates() {
    final SampleFormat format = SampleFormat.SIGNED_16_BIT;
    assertThrows(IllegalArgumentException.class, () -> createAndClose(0, 2, format, PIPELINE_RATE, 2, format));
    assertThrows(IllegalArgumentException.class, () -> createAndClose(-1, 2, format, PIPELINE_RATE, 2, format));
    assertThrows(IllegalArgumentException.class, () -> createAndClose(PIPELINE_RATE, 2, format, 0, 2, format));
    assertThrows(IllegalArgumentException.class, () -> createAndClose(PIPELINE_RATE, 2, format, -1, 2, format));
    assertThrows(IllegalArgumentException.class, () -> createFromPipelineAndClose(0, 1, format));
  }

  @Test
  void rejectsInvalidChannelCounts() {
    final SampleFormat format = SampleFormat.SIGNED_16_BIT;
    final int tooMany = AudioResampler.MAX_CHANNELS + 1;
    assertThrows(IllegalArgumentException.class, () -> createAndClose(PIPELINE_RATE, 0, format, PIPELINE_RATE, 2, format));
    assertThrows(IllegalArgumentException.class, () -> createAndClose(PIPELINE_RATE, tooMany, format, PIPELINE_RATE, 2, format));
    assertThrows(IllegalArgumentException.class, () -> createAndClose(PIPELINE_RATE, 2, format, PIPELINE_RATE, 0, format));
    assertThrows(IllegalArgumentException.class, () -> createAndClose(PIPELINE_RATE, 2, format, PIPELINE_RATE, tooMany, format));
    assertThrows(IllegalArgumentException.class, () -> createFromPipelineAndClose(PIPELINE_RATE, 0, format));
    assertThrows(IllegalArgumentException.class, () -> createFromPipelineAndClose(PIPELINE_RATE, tooMany, format));
  }

  @Test
  void rejectsNullArguments() {
    final SampleFormat format = SampleFormat.SIGNED_16_BIT;
    assertThrows(NullPointerException.class, () -> createAndClose(PIPELINE_RATE, 2, null, PIPELINE_RATE, 2, format));
    assertThrows(NullPointerException.class, () -> createAndClose(PIPELINE_RATE, 2, format, PIPELINE_RATE, 2, null));
    assertThrows(NullPointerException.class, () -> createFromPipelineAndClose(PIPELINE_RATE, 2, null));
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, format)) {
      assertThrows(NullPointerException.class, () -> resampler.resample(null));
    }
  }

  @Test
  void checkResultPassesSuccessfulResultsThrough() {
    final int zero = AudioResampler.checkResult(0, "do nothing");
    final int positive = AudioResampler.checkResult(1_234, "convert the samples");
    assertEquals(0, zero);
    assertEquals(1_234, positive);
  }

  @Test
  void checkResultDescribesFfmpegErrors() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      AudioResampler.checkResult(EINVAL, "convert the samples")
    );
    final String message = exception.getMessage();
    assertEquals("Could not convert the samples: Invalid argument (FFmpeg error -22)", message);
  }

  @Test
  void closeOnFailureKeepsTheResamplerOpenOnSuccess() {
    try (final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT)) {
      resampler.closeOnFailure(0, "initialize the resampler");
      final boolean closed = resampler.isClosed();
      assertFalse(closed);
    }
  }

  @Test
  void closeOnFailureReleasesTheResamplerAndReportsTheError() {
    final AudioResampler resampler = AudioResampler.fromPipelineFormat(SPEECH_RATE, 1, SampleFormat.SIGNED_16_BIT);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      resampler.closeOnFailure(EINVAL, "initialize the resampler")
    );
    final String message = exception.getMessage();
    final boolean closed = resampler.isClosed();
    assertTrue(closed);
    assertEquals("Could not initialize the resampler: Invalid argument (FFmpeg error -22)", message);
  }
}
