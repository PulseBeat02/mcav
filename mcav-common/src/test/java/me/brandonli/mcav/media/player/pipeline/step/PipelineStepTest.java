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
package me.brandonli.mcav.media.player.pipeline.step;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import org.junit.jupiter.api.Test;

final class PipelineStepTest {

  private static final OriginalAudioMetadata AUDIO_METADATA = OriginalAudioMetadata.of("pcm", -1, 48_000, 2, -1);

  private static final int SAMPLE_BUFFER_BYTES = 4;

  @Test
  void shouldRunTheFiltersOfVideoStepsInOrder() {
    final List<String> calls = new ArrayList<>();
    final ImageBuffer image = mock(ImageBuffer.class);
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(2, 2);
    final VideoFilter second = (_, _) -> calls.add("second");
    final VideoFilter first = (buffer, videoMetadata) -> this.checkAndRecordFirst(image, metadata, buffer, videoMetadata, calls);
    final VideoPipelineStep last = VideoPipelineStep.of(second);
    final VideoPipelineStep head = VideoPipelineStep.of(last, first);
    head.processAll(image, metadata);
    final Optional<VideoPipelineStep> next = head.next();
    final Optional<VideoPipelineStep> expectedNext = Optional.of(last);
    final boolean headIsLast = head.isLast();
    final boolean lastIsLast = last.isLast();
    final VideoFilter filter = head.getFilter();
    final VideoPipelineStep self = head.self();
    final List<String> expected = List.of("first", "second");
    assertEquals(expected, calls);
    assertEquals(expectedNext, next);
    assertFalse(headIsLast);
    assertTrue(lastIsLast);
    assertSame(first, filter);
    assertSame(head, self);
  }

  private boolean checkAndRecordFirst(
    final ImageBuffer image,
    final OriginalVideoMetadata metadata,
    final ImageBuffer buffer,
    final OriginalVideoMetadata videoMetadata,
    final List<String> calls
  ) {
    assertSame(image, buffer);
    assertSame(metadata, videoMetadata);

    return calls.add("first");
  }

  @Test
  void shouldApplyOnlyTheFilterOfItsOwnStepWhenProcessing() {
    final List<String> calls = new ArrayList<>();
    final VideoFilter videoFilter = (_, _) -> calls.add("video filter");
    final VideoFilter nextVideo = (_, _) -> calls.add("next video filter");
    final AudioFilter audioFilter = (_, _) -> calls.add("audio filter");
    final AudioFilter nextAudio = (_, _) -> calls.add("next audio filter");
    final VideoPipelineStep lastVideo = VideoPipelineStep.of(nextVideo);
    final VideoPipelineStep video = VideoPipelineStep.of(lastVideo, videoFilter);
    final AudioPipelineStep lastAudio = AudioPipelineStep.of(nextAudio);
    final AudioPipelineStep audio = AudioPipelineStep.of(lastAudio, audioFilter);
    final ImageBuffer image = mock(ImageBuffer.class);
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES);
    video.process(image, OriginalVideoMetadata.EMPTY);
    audio.process(samples, AUDIO_METADATA);
    final List<String> expected = List.of("video filter", "audio filter");
    assertEquals(expected, calls);
  }

  @Test
  void shouldRewindTheSamplesBeforeEveryFilterInAudioSteps() {
    final List<Integer> positions = new ArrayList<>();
    final AudioFilter consuming = (samples, _) -> this.recordPositionAndConsume(positions, samples);
    final AudioPipelineStep last = AudioPipelineStep.of(consuming);
    final AudioPipelineStep head = AudioPipelineStep.of(last, consuming);
    final ByteBuffer samples = ByteBuffer.allocate(8);
    samples.position(4);
    head.processAll(samples, AUDIO_METADATA);
    final Optional<AudioPipelineStep> next = head.next();
    final Optional<AudioPipelineStep> expectedNext = Optional.of(last);
    final AudioFilter filter = head.getFilter();
    final AudioPipelineStep self = head.self();
    final List<Integer> expected = List.of(0, 0);
    assertEquals(expected, positions, "every filter sees the samples from the start");
    assertEquals(expectedNext, next);
    assertSame(consuming, filter);
    assertSame(head, self);
  }

  private boolean recordPositionAndConsume(final List<Integer> positions, final ByteBuffer samples) {
    final int position = samples.position();
    positions.add(position);
    samples.getInt();

    return true;
  }

  @Test
  void shouldRunEveryFilterWhateverTheFiltersBeforeItReturned() {
    final List<String> calls = new ArrayList<>();
    final ImageBuffer image = mock(ImageBuffer.class);
    final VideoFilter untouchedVideo = (_, _) -> this.recordUntouched(calls, "video left untouched");
    final VideoFilter nextVideo = (_, _) -> calls.add("next video filter");
    final AudioFilter untouchedAudio = (_, _) -> this.recordUntouched(calls, "audio left untouched");
    final AudioFilter nextAudio = (_, _) -> calls.add("next audio filter");
    final VideoPipelineStep lastVideo = VideoPipelineStep.of(nextVideo);
    final VideoPipelineStep video = VideoPipelineStep.of(lastVideo, untouchedVideo);
    final AudioPipelineStep lastAudio = AudioPipelineStep.of(nextAudio);
    final AudioPipelineStep audio = AudioPipelineStep.of(lastAudio, untouchedAudio);
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES);
    video.processAll(image, OriginalVideoMetadata.EMPTY);
    audio.processAll(samples, AUDIO_METADATA);
    final List<String> expected = List.of("video left untouched", "next video filter", "audio left untouched", "next audio filter");
    assertEquals(expected, calls, "false only reports an untouched sample, it never stops the pipeline");
  }

  private boolean recordUntouched(final List<String> calls, final String call) {
    calls.add(call);

    return false;
  }

  @Test
  void shouldStopTheChainAtAFailingFilter() {
    final List<String> calls = new ArrayList<>();
    final IllegalStateException videoFailure = new IllegalStateException("Video filter failed!");
    final IllegalStateException audioFailure = new IllegalStateException("Audio filter failed!");
    final VideoFilter failingVideo = (_, _) -> this.failFilter(videoFailure);
    final VideoFilter nextVideo = (_, _) -> calls.add("next video filter");
    final AudioFilter failingAudio = (_, _) -> this.failFilter(audioFailure);
    final AudioFilter nextAudio = (_, _) -> calls.add("next audio filter");
    final VideoPipelineStep lastVideo = VideoPipelineStep.of(nextVideo);
    final VideoPipelineStep video = VideoPipelineStep.of(lastVideo, failingVideo);
    final AudioPipelineStep lastAudio = AudioPipelineStep.of(nextAudio);
    final AudioPipelineStep audio = AudioPipelineStep.of(lastAudio, failingAudio);
    final ImageBuffer image = mock(ImageBuffer.class);
    final OriginalVideoMetadata videoMetadata = OriginalVideoMetadata.EMPTY;
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES);
    final IllegalStateException videoThrown = assertThrows(IllegalStateException.class, () -> video.processAll(image, videoMetadata));
    final IllegalStateException audioThrown = assertThrows(IllegalStateException.class, () -> audio.processAll(samples, AUDIO_METADATA));
    assertSame(videoFailure, videoThrown);
    assertSame(audioFailure, audioThrown);
    assertEquals(List.of(), calls);
  }

  private boolean failFilter(final IllegalStateException failure) {
    throw failure;
  }

  @Test
  void shouldDoNothingInEmptyPipelines() {
    final ImageBuffer image = mock(ImageBuffer.class);
    final byte[] original = { 1, 2, 3, 4 };
    final ByteBuffer samples = ByteBuffer.wrap(original.clone());
    final OriginalVideoMetadata videoMetadata = OriginalVideoMetadata.EMPTY;
    VideoPipelineStep.NO_OPERATION.processAll(image, videoMetadata);
    AudioPipelineStep.NO_OPERATION.processAll(samples, AUDIO_METADATA);
    final Optional<VideoPipelineStep> videoNext = VideoPipelineStep.NO_OPERATION.next();
    final Optional<AudioPipelineStep> audioNext = AudioPipelineStep.NO_OPERATION.next();
    final VideoFilter videoFilter = VideoPipelineStep.NO_OPERATION.getFilter();
    final AudioFilter audioFilter = AudioPipelineStep.NO_OPERATION.getFilter();
    final boolean videoNextMissing = videoNext.isEmpty();
    final boolean audioNextMissing = audioNext.isEmpty();
    final byte[] sampleBytes = samples.array();
    assertTrue(videoNextMissing);
    assertTrue(audioNextMissing);
    assertSame(VideoFilter.NO_OPERATION, videoFilter);
    assertSame(AudioFilter.NO_OPERATION, audioFilter);
    assertArrayEquals(original, sampleBytes);
    verifyNoInteractions(image);
  }

  @Test
  void shouldTreatOnlyTheEmptyPipelinesAsNoOperations() {
    final VideoPipelineStep video = VideoPipelineStep.of(VideoFilter.NO_OPERATION);
    final AudioPipelineStep audio = AudioPipelineStep.of(AudioFilter.NO_OPERATION);
    final boolean emptyVideo = VideoPipelineStep.NO_OPERATION.isNoOperation();
    final boolean emptyAudio = AudioPipelineStep.NO_OPERATION.isNoOperation();
    final boolean videoStep = video.isNoOperation();
    final boolean audioStep = audio.isNoOperation();
    assertTrue(emptyVideo);
    assertTrue(emptyAudio);
    assertFalse(videoStep, "a step that happens to do nothing is still a pipeline the user set");
    assertFalse(audioStep, "a step that happens to do nothing is still a pipeline the user set");
  }

  @Test
  void shouldRejectMissingDataInEmptyPipelines() {
    final ImageBuffer image = mock(ImageBuffer.class);
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES);
    final VideoPipelineStep video = VideoPipelineStep.NO_OPERATION;
    final AudioPipelineStep audio = AudioPipelineStep.NO_OPERATION;
    assertThrows(NullPointerException.class, () -> video.process(null, OriginalVideoMetadata.EMPTY));
    assertThrows(NullPointerException.class, () -> video.process(image, null));
    assertThrows(NullPointerException.class, () -> audio.process(null, AUDIO_METADATA));
    assertThrows(NullPointerException.class, () -> audio.process(samples, null));
  }

  @Test
  void shouldRejectMissingFilters() {
    assertThrows(NullPointerException.class, () -> VideoPipelineStep.of(null));
    assertThrows(NullPointerException.class, () -> VideoPipelineStep.of(null, null));
    assertThrows(NullPointerException.class, () -> AudioPipelineStep.of(null));
    assertThrows(NullPointerException.class, () -> AudioPipelineStep.of(null, null));
  }

  @Test
  void shouldEndTheChainAtAStepCreatedWithoutANextStep() {
    final VideoPipelineStep video = VideoPipelineStep.of(null, VideoFilter.NO_OPERATION);
    final AudioPipelineStep audio = AudioPipelineStep.of(null, AudioFilter.NO_OPERATION);
    final boolean videoIsLast = video.isLast();
    final boolean audioIsLast = audio.isLast();
    assertTrue(videoIsLast);
    assertTrue(audioIsLast);
  }

  @Test
  void shouldRejectMissingSamplesAndMetadata() {
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES);
    final VideoPipelineStep video = VideoPipelineStep.of(VideoFilter.NO_OPERATION);
    final AudioPipelineStep audio = AudioPipelineStep.of(AudioFilter.NO_OPERATION);
    try (final ImageBuffer image = mock(ImageBuffer.class)) {
      assertThrows(NullPointerException.class, () -> video.process(null, OriginalVideoMetadata.EMPTY));
      assertThrows(NullPointerException.class, () -> video.process(image, null));
      assertThrows(NullPointerException.class, () -> video.processAll(null, OriginalVideoMetadata.EMPTY));
      assertThrows(NullPointerException.class, () -> video.processAll(image, null));
    }

    assertThrows(NullPointerException.class, () -> audio.process(null, AUDIO_METADATA));
    assertThrows(NullPointerException.class, () -> audio.process(samples, null));
    assertThrows(NullPointerException.class, () -> audio.processAll(null, AUDIO_METADATA));
    assertThrows(NullPointerException.class, () -> audio.processAll(samples, null));
  }
}
