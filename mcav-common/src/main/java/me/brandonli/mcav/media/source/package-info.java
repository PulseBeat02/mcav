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
 * Describes media inputs and detects their types from user-supplied strings.
 *
 * <p>{@link me.brandonli.mcav.media.source.SourceDetectionHelper} tries detectors in order and chooses the
 * accepting detector with the greatest priority, keeping the first on a tie. Detection is a syntax or existence
 * check, not a guarantee that a decoder can open the input. File, URI, device and FFmpeg factories can also be
 * called directly. The static/dynamic distinction is a source category, not a measured duration.
 *
 * <p>Descriptions do not open decoder resources. File, URI, device and direct FFmpeg descriptions are immutable;
 * generated sources may retain stateful suppliers or animation frames. Players and their callers own the resources
 * needed for playback. Custom detectors and suppliers define their own thread-safety requirements.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.source;
