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
 * Implements multimedia playback with JavaCV frame grabbers for FFmpeg, OpenCV and capture devices.
 *
 * <p>{@link me.brandonli.mcav.media.player.multimedia.cv.AbstractVideoPlayerCV} owns sessions with separate
 * decoding and rendering workers, bounded queues and a playback clock. It normalizes output to packed BGR video
 * and 48 kHz signed 16-bit little-endian stereo audio. Pipelines run on render workers and must copy borrowed
 * data before retaining it. Native libraries must be loadable on the host.
 *
 * <p>Lifecycle operations use a player lock, with decoder acquisition and worker joins outside that lock.
 * Concurrent starts can be refused. Release is terminal and cancels pending replacement; workers that ignore
 * interrupts can outlive their five-second per-worker join. Callers own attached filters and should release them
 * only after callbacks finish. Subclasses return fresh unstarted grabbers from the protected factory; the player
 * configures, starts and closes them, including cleanup after failed opening.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.multimedia.cv;
