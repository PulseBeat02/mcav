/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.player.pipeline.filter.audio;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.Filter;

/**
 * Represents a functional interface for applying audio-specific filters within an audio processing pipeline.
 * This interface extends {@link Filter} and operates on audio data represented as {@link ByteBuffer}
 * alongside its associated metadata of type {@link AudioMetadata}.
 * <p>
 * Implementations of this interface define a specific transformation or operation applied
 * to the raw audio samples and associated metadata. It is commonly used in audio pipeline
 * construction where multiple filters can be chained to sequentially process audio data.
 * <p>
 * This interface is a functional interface and can therefore be represented as a lambda expression
 * or a method reference to simplify usage in functional programming contexts.
 */
@FunctionalInterface
public interface AudioFilter extends Filter<ByteBuffer, AudioMetadata> {}
