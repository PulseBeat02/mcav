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
package me.brandonli.mcav.bukkit.media.mcv2;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the next frame of a channel must be a keyframe. Whoever needs one requests it, such as the main thread when a
 * viewer is shown the screen, and the encoder's thread takes the requests for every frame it encodes.
 */
final class Mcv2KeyframeRequest {

  // Separate reads and clears can lose a concurrent request.
  private final AtomicBoolean requested;

  /**
   * Creates the requests of a channel, with none waiting.
   */
  Mcv2KeyframeRequest() {
    this.requested = new AtomicBoolean();
  }

  /**
   * Asks for the next frame to be a keyframe.
   */
  void request() {
    this.requested.set(true);
  }

  /**
   * Takes the requests for the next frame. Every request is taken by exactly one frame: the next one, or the one after
   * it when the request comes while this frame takes them.
   *
   * @return true if the frame should be a keyframe
   */
  boolean take() {
    return this.requested.getAndSet(false);
  }
}
