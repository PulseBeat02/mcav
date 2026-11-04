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
 * Validates and decodes MCV2 frames, builds shader resource packs and delivers live video to Bukkit map screens.
 *
 * <p>For raw data, parse a complete frame with {@link Mcv2Decoder} and decode it with the required RGB reference.
 * Immutable parsed frames may be shared; receivers, pacing state and stream encoders need serialized access. Decoder
 * output arrays and borrowed references have explicit ownership rules.
 *
 * <p>For server playback, configure a screen with {@link Mcv2Configuration}, acquire a lease from an
 * {@link Mcv2PackServer}, and create an {@link Mcv2Result} using that lease's configuration and the pack server's
 * {@link Mcv2Viewers}. Start and release the result on the main thread; submit frames from one media thread between
 * those lifecycle calls. Release the result before closing its lease, and shut down the pack server when its screens
 * stop. The caller supplies the visible map wall. Client pack status and socket-write callbacks do not acknowledge
 * successful video decoding. A server too small to encode a video while it plays encodes it ahead with
 * {@link Mcv2FileEncoder}.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.bukkit.media.mcv2;
