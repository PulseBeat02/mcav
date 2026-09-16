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
package me.brandonli.mcav.media.player.attachable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.junit.jupiter.api.Test;

/**
 * Tests the attachable callbacks and {@link AbstractAttachableCallback}.
 */
final class AttachableCallbackTest {

  @Test
  void videoCallbackFallsBackToTheEmptyPipeline() {
    final VideoAttachableCallback callback = VideoAttachableCallback.create();
    final VideoPipelineStep initial = callback.retrieve();
    final boolean initiallyAttached = callback.isAttached();
    final VideoPipelineStep step = VideoPipelineStep.of(VideoFilter.NO_OP);
    callback.attach(step);
    final VideoPipelineStep attached = callback.retrieve();
    final boolean attachedState = callback.isAttached();
    callback.detach();
    final VideoPipelineStep detached = callback.retrieve();
    final boolean detachedState = callback.isAttached();
    assertSame(VideoPipelineStep.NO_OP, initial);
    assertFalse(initiallyAttached);
    assertSame(step, attached);
    assertTrue(attachedState);
    assertSame(VideoPipelineStep.NO_OP, detached);
    assertFalse(detachedState);
  }

  @Test
  void audioCallbackFallsBackToTheEmptyPipeline() {
    final AudioAttachableCallback callback = AudioAttachableCallback.create();
    final AudioPipelineStep initial = callback.retrieve();
    final AudioPipelineStep step = AudioPipelineStep.of(AudioFilter.NO_OP);
    callback.attach(step);
    final AudioPipelineStep attached = callback.retrieve();
    callback.detach();
    final boolean detachedState = callback.isAttached();
    assertSame(AudioPipelineStep.NO_OP, initial);
    assertSame(step, attached);
    assertFalse(detachedState);
  }

  @Test
  void dimensionCallbackAcceptsOnlyNonEmptySizes() {
    final DimensionAttachableCallback callback = DimensionAttachableCallback.create();
    final Dimension initial = callback.retrieve();
    final Dimension size = Dimension.of(160, 90);
    callback.attach(size);
    final Dimension attached = callback.retrieve();
    final boolean attachedState = callback.isAttached();
    final Dimension empty = Dimension.of(0, 90);
    assertEquals(Dimension.NONE, initial);
    assertEquals(size, attached);
    assertTrue(attachedState);
    assertThrows(IllegalArgumentException.class, () -> callback.attach(empty));
    assertThrows(NullPointerException.class, () -> callback.attach(null));
  }

  @Test
  void countsAnAttachedValueAsAttachedEvenWhenItIsTheFallback() {
    final VideoAttachableCallback callback = VideoAttachableCallback.create();
    final VideoPipelineStep fallback = callback.retrieve();
    callback.attach(fallback);
    final boolean attachedState = callback.isAttached();
    final VideoPipelineStep attached = callback.retrieve();
    callback.detach();
    final boolean detachedState = callback.isAttached();
    final VideoPipelineStep detached = callback.retrieve();
    assertTrue(attachedState, "attaching marks the slot attached, whatever value is attached");
    assertSame(fallback, attached);
    assertFalse(detachedState);
    assertSame(fallback, detached);
  }

  @Test
  void rejectsNullValues() {
    final VideoAttachableCallback callback = VideoAttachableCallback.create();
    assertThrows(NullPointerException.class, () -> callback.attach(null));
    assertThrows(NullPointerException.class, NullFallbackCallback::new);
  }

  /**
   * A callback that passes a null fallback, which the base class must reject.
   */
  private static final class NullFallbackCallback extends AbstractAttachableCallback<String> {

    NullFallbackCallback() {
      super(null);
    }
  }
}
