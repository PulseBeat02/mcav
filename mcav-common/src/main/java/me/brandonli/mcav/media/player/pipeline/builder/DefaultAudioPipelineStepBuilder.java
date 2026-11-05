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

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import org.checkerframework.checker.nullness.qual.Nullable;

final class DefaultAudioPipelineStepBuilder
  extends AbstractPipelineStepBuilder<ByteBuffer, OriginalAudioMetadata, AudioFilter, AudioPipelineStep, AudioPipelineStepBuilder>
  implements AudioPipelineStepBuilder
{

  DefaultAudioPipelineStepBuilder() {}

  @Override
  AudioPipelineStep createStep(final @Nullable AudioPipelineStep next, final AudioFilter filter) {
    return AudioPipelineStep.of(next, filter);
  }

  @Override
  AudioPipelineStep createEmptyStep() {
    return AudioPipelineStep.NO_OPERATION;
  }

  @Override
  AudioPipelineStepBuilder self() {
    return this;
  }
}
