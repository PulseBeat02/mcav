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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import org.junit.jupiter.api.Test;

/**
 * Tests the video and audio pipeline steps.
 */
final class PipelineStepTest {

  private static final OriginalAudioMetadata AUDIO_METADATA = OriginalAudioMetadata.of("pcm", -1, 48_000, 2, -1);

  @Test
  void videoStepsRunTheirFiltersInOrder() {
    final List<String> calls = new ArrayList<>();
    final ImageBuffer image = mock(ImageBuffer.class);
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(2, 2);
    final VideoFilter second = (_, _) -> calls.add("second");
    final VideoFilter first = (buffer, videoMetadata) -> {
      assertSame(image, buffer);
      assertSame(metadata, videoMetadata);
      return calls.add("first");
    };
    final VideoPipelineStep last = VideoPipelineStep.of(second);
    final VideoPipelineStep head = VideoPipelineStep.of(last, first);
    head.processAll(image, metadata);
    final VideoPipelineStep next = head.next();
    final boolean headIsLast = head.isLast();
    final boolean lastIsLast = last.isLast();
    final VideoFilter filter = head.getFilter();
    final VideoPipelineStep self = head.self();
    final List<String> expected = List.of("first", "second");
    assertEquals(expected, calls);
    assertSame(last, next);
    assertFalse(headIsLast);
    assertTrue(lastIsLast);
    assertSame(first, filter);
    assertSame(head, self);
  }

  @Test
  void audioStepsRewindTheSamplesBeforeEveryFilter() {
    final List<Integer> positions = new ArrayList<>();
    final AudioFilter consuming = (samples, _) -> {
      final int position = samples.position();
      positions.add(position);
      samples.getInt();
      return true;
    };
    final AudioPipelineStep last = AudioPipelineStep.of(consuming);
    final AudioPipelineStep head = AudioPipelineStep.of(last, consuming);
    final ByteBuffer samples = ByteBuffer.allocate(8);
    samples.position(4);
    head.processAll(samples, AUDIO_METADATA);
    final AudioPipelineStep next = head.next();
    final AudioFilter filter = head.getFilter();
    final AudioPipelineStep self = head.self();
    final List<Integer> expected = List.of(0, 0);
    assertEquals(expected, positions, "every filter sees the samples from the start");
    assertSame(last, next);
    assertSame(consuming, filter);
    assertSame(head, self);
  }

  @Test
  void runsEveryFilterWhateverTheFiltersBeforeItReturned() {
    final List<String> calls = new ArrayList<>();
    final ImageBuffer image = mock(ImageBuffer.class);
    final VideoFilter untouchedVideo = (_, _) -> {
      calls.add("video left untouched");
      return false;
    };
    final VideoFilter nextVideo = (_, _) -> calls.add("next video filter");
    final AudioFilter untouchedAudio = (_, _) -> {
      calls.add("audio left untouched");
      return false;
    };
    final AudioFilter nextAudio = (_, _) -> calls.add("next audio filter");
    final VideoPipelineStep lastVideo = VideoPipelineStep.of(nextVideo);
    final VideoPipelineStep video = VideoPipelineStep.of(lastVideo, untouchedVideo);
    final AudioPipelineStep lastAudio = AudioPipelineStep.of(nextAudio);
    final AudioPipelineStep audio = AudioPipelineStep.of(lastAudio, untouchedAudio);
    final ByteBuffer samples = ByteBuffer.allocate(4);
    video.processAll(image, OriginalVideoMetadata.EMPTY);
    audio.processAll(samples, AUDIO_METADATA);
    final List<String> expected = List.of("video left untouched", "next video filter", "audio left untouched", "next audio filter");
    assertEquals(expected, calls, "false only reports an untouched sample, it never stops the pipeline");
  }

  @Test
  void emptyPipelinesDoNothing() {
    final ImageBuffer image = mock(ImageBuffer.class);
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final OriginalVideoMetadata videoMetadata = OriginalVideoMetadata.EMPTY;
    VideoPipelineStep.NO_OP.processAll(image, videoMetadata);
    AudioPipelineStep.NO_OP.processAll(samples, AUDIO_METADATA);
    final VideoPipelineStep videoNext = VideoPipelineStep.NO_OP.next();
    final AudioPipelineStep audioNext = AudioPipelineStep.NO_OP.next();
    final VideoFilter videoFilter = VideoPipelineStep.NO_OP.getFilter();
    final AudioFilter audioFilter = AudioPipelineStep.NO_OP.getFilter();
    assertNull(videoNext);
    assertNull(audioNext);
    assertSame(VideoFilter.NO_OP, videoFilter);
    assertSame(AudioFilter.NO_OP, audioFilter);
  }

  @Test
  void onlyTheEmptyPipelinesAreNoOperations() {
    final VideoPipelineStep video = VideoPipelineStep.of(VideoFilter.NO_OP);
    final AudioPipelineStep audio = AudioPipelineStep.of(AudioFilter.NO_OP);
    final boolean emptyVideo = VideoPipelineStep.NO_OP.isNoOp();
    final boolean emptyAudio = AudioPipelineStep.NO_OP.isNoOp();
    final boolean videoStep = video.isNoOp();
    final boolean audioStep = audio.isNoOp();
    assertTrue(emptyVideo);
    assertTrue(emptyAudio);
    assertFalse(videoStep, "a step that happens to do nothing is still a pipeline the user set");
    assertFalse(audioStep, "a step that happens to do nothing is still a pipeline the user set");
  }

  @Test
  void emptyPipelinesRejectMissingData() {
    final ImageBuffer image = mock(ImageBuffer.class);
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final VideoPipelineStep video = VideoPipelineStep.NO_OP;
    final AudioPipelineStep audio = AudioPipelineStep.NO_OP;
    assertThrows(NullPointerException.class, () -> video.process(null, OriginalVideoMetadata.EMPTY));
    assertThrows(NullPointerException.class, () -> video.process(image, null));
    assertThrows(NullPointerException.class, () -> audio.process(null, AUDIO_METADATA));
    assertThrows(NullPointerException.class, () -> audio.process(samples, null));
  }

  @Test
  void rejectsMissingFilters() {
    assertThrows(NullPointerException.class, () -> VideoPipelineStep.of(null));
    assertThrows(NullPointerException.class, () -> VideoPipelineStep.of(null, null));
    assertThrows(NullPointerException.class, () -> AudioPipelineStep.of(null));
    assertThrows(NullPointerException.class, () -> AudioPipelineStep.of(null, null));
  }

  @Test
  void rejectsMissingSamplesAndMetadata() {
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final VideoPipelineStep video = VideoPipelineStep.of(VideoFilter.NO_OP);
    final AudioPipelineStep audio = AudioPipelineStep.of(AudioFilter.NO_OP);
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
