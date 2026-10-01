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
 * Converts PCM formats, downmixes stereo and queues delayed audio for live sources.
 *
 * <p>{@link me.brandonli.mcav.utils.audio.AudioResampler} holds a native conversion context and stream history.
 * Use one at a time per stream, flush at the end to drain and reset it, and close it to release native resources.
 * Resampling uses native byte order; the pipeline format is little-endian and can be passed directly on
 * little-endian hosts. Returned sample arrays are caller-owned and input buffer positions are preserved.
 *
 * <p>{@link me.brandonli.mcav.utils.audio.MonoDownmixer} averages complete stereo frames into a new mono array.
 * {@link me.brandonli.mcav.utils.audio.DelayedAudioOutput} copies accepted bytes into a bounded queue and invokes
 * pipelines on its daemon worker. Stop producers and close the output when finished. A callback that outlives
 * its bounded close wait still owns its invocation; do not release its external resources prematurely.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.utils.audio;
