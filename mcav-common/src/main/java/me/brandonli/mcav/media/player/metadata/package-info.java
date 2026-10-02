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
/**
 * Carries immutable snapshots of source audio and video properties alongside pipeline data.
 *
 * <p>Original metadata continues to describe the decoder's input properties after filters transform a frame.
 * Read the current image's dimensions when a preceding filter may have resized it. Audio samples always use
 * {@link me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter}'s normalized PCM format even when
 * source metadata describes another sample rate or channel count.
 *
 * <p>Factories validate positive dimensions, sample rate and channel count where applicable. Bitrates, video
 * frame rates and sample-format identifiers are retained without validation; {@code UNKNOWN} denotes unavailable
 * information. {@link me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata#EMPTY} has unknown dimensions
 * as well. Default instances are immutable, shareable across threads and require no cleanup.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.metadata;
