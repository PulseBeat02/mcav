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
package me.brandonli.mcav.media.player.pipeline.filter;

/**
 * A processing step that is applied to every sample of a media stream.
 *
 * @param <A> the type of sample, such as a video frame or a buffer of audio samples
 * @param <B> the type of metadata describing the original stream
 */
@FunctionalInterface
public interface Filter<A, B> {
  /**
   * Processes one sample, usually by modifying it in place. Pipelines pass the sample on to their next step whatever
   * this method returns; they never discard samples.
   *
   * @param samples  the sample to process
   * @param metadata the metadata of the original stream the sample belongs to
   * @return true if the filter changed the sample or may have changed it, false if it left the sample untouched;
   * filters that work on a copy of a sample use this to skip copying unchanged samples back
   */
  boolean applyFilter(final A samples, final B metadata);
}
