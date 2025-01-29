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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;

public class VideoAttachableCallbackImpl implements VideoAttachableCallback {

  private final AtomicReference<VideoPipelineStep> pipeline;

  VideoAttachableCallbackImpl() {
    this.pipeline = new AtomicReference<>(VideoPipelineStep.NO_OP);
  }

  @Override
  public void attach(final VideoPipelineStep pipeline) {
    requireNonNull(pipeline);
    this.pipeline.set(pipeline);
  }

  @Override
  public void detach() {
    this.pipeline.set(VideoPipelineStep.NO_OP);
  }

  @Override
  public boolean isAttached() {
    return this.pipeline.get() != VideoPipelineStep.NO_OP;
  }

  @Override
  public VideoPipelineStep getPipeline() {
    return this.pipeline.get();
  }
}
