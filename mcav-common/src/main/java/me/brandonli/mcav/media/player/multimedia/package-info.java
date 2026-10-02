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
 * Selects multimedia backends and exposes playback, pause, seek and exception-handler contracts.
 *
 * <p>{@link me.brandonli.mcav.media.player.multimedia.VideoPlayer} factories create a
 * {@link me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer}. Attach output pipelines, choose a
 * source and start it; use two sources when audio and video are separate. The player owns decoder resources,
 * while the caller owns filters, output devices and explicitly supplied executors. Release players explicitly.
 * Frames and audio buffers are borrowed during pipeline callbacks and must be copied before retention.
 *
 * <p>Native opening and cleanup can block. Async helpers submit the synchronous call; their futures preserve
 * its result or failure. Cancelling a future does not stop playback or guarantee interruption. Backends differ
 * in opening and seek support, so inspect their concrete contracts. Error callbacks run on the failing thread,
 * may be concurrent, must return promptly and must not throw.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.multimedia;
