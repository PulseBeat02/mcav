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
package me.brandonli.mcav.media.player.pipeline.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link PipelineBuilder} and the step builders.
 */
final class PipelineBuilderTest {

  @Test
  void videoBuilderChainsFiltersInTheOrderTheyWereAdded() {
    final List<Integer> calls = new ArrayList<>();
    final VideoFilter first = (_, _) -> calls.add(1);
    final VideoFilter second = (_, _) -> calls.add(2);
    final VideoFilter third = (_, _) -> calls.add(3);
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    final VideoPipelineStepBuilder returned = builder.then(first);
    builder.then(second);
    builder.then(third);
    final int size = builder.size();
    final VideoPipelineStep step = builder.build();
    final ImageBuffer image = mock(ImageBuffer.class);
    step.processAll(image, OriginalVideoMetadata.EMPTY);
    final List<Integer> expected = List.of(1, 2, 3);
    assertSame(builder, returned);
    assertEquals(3, size);
    assertEquals(expected, calls);
  }

  @Test
  void audioBuilderChainsFiltersInTheOrderTheyWereAdded() {
    final List<Integer> calls = new ArrayList<>();
    final AudioFilter first = (_, _) -> calls.add(1);
    final AudioFilter second = (_, _) -> calls.add(2);
    final AudioPipelineStepBuilder builder = PipelineBuilder.audio();
    final AudioPipelineStepBuilder returned = builder.then(first);
    builder.then(second);
    final AudioPipelineStep step = builder.build();
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of("pcm", -1, 48_000, 2, -1);
    step.processAll(samples, metadata);
    final List<Integer> expected = List.of(1, 2);
    assertSame(builder, returned);
    assertEquals(expected, calls);
  }

  @Test
  void emptyBuildersBuildTheEmptyPipelines() {
    final VideoPipelineStepBuilder video = PipelineBuilder.video();
    final AudioPipelineStepBuilder audio = PipelineBuilder.audio();
    final VideoPipelineStep videoStep = video.build();
    final AudioPipelineStep audioStep = audio.build();
    final int size = video.size();
    assertSame(VideoPipelineStep.NO_OP, videoStep);
    assertSame(AudioPipelineStep.NO_OP, audioStep);
    assertEquals(0, size);
  }

  @Test
  void rejectsMissingFilters() {
    final VideoPipelineStepBuilder video = PipelineBuilder.video();
    final AudioPipelineStepBuilder audio = PipelineBuilder.audio();
    assertThrows(NullPointerException.class, () -> video.then(null));
    assertThrows(NullPointerException.class, () -> audio.then(null));
  }
}
