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
 * Transforms borrowed BGR video frames, draws annotations and manages video output lifecycles.
 *
 * <p>Filters run in pipeline order on a player's rendering thread. The frame may already have been resized by
 * another filter; use its current dimensions rather than original metadata. OpenCV filters derive from
 * {@link me.brandonli.mcav.media.player.pipeline.filter.video.MatVideoFilter}; custom image implementations are
 * copied into a temporary native image and copied back when changed. The player owns the input image.
 *
 * <p>Drawing coordinates are pixel offsets from the top left, with x increasing right and y increasing down.
 * Colors are copied from BGR component arrays; missing components become zero and only the first four are kept
 * by the native scalar, with the fourth unused for BGR images. Components normally lie in 0 through 255;
 * constructors do not validate their range. OpenCV clips drawing outside the frame where the filter permits it.
 *
 * <p>Filters that retain JavaCPP matrices or drawing objects without a release method rely on JavaCPP's automatic
 * native-memory reclamation. Resource-owning output filters implementing
 * {@link me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter} instead require explicit
 * startup and release by the caller. Never release the input image or retain its native views after a callback.
 * Concrete filter docs describe any state or concurrency restrictions.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.pipeline.filter.video;
