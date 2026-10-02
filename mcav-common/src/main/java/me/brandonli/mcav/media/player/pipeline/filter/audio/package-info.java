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
 * Processes normalized 48 kHz signed 16-bit little-endian stereo PCM.
 *
 * <p>Each interleaved sample frame occupies {@link me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter#FRAME_SIZE}
 * bytes. Original metadata can describe a different source format. Filters borrow buffers for the duration of the
 * call; duplicate a buffer to read without changing its position, and copy bytes before retaining them.
 *
 * <p>{@link me.brandonli.mcav.media.player.pipeline.filter.audio.VolumeFilter} changes complete 16-bit samples
 * in place. {@link me.brandonli.mcav.media.player.pipeline.filter.audio.DirectAudioOutput} writes to the host's
 * sound device and may block. Start resource-owning filters before attaching them, stop producers before release,
 * and do not assume that releasing a player releases its filters. Concrete filters describe concurrent-use limits.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter.audio;
