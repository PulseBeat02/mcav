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
 * Selects a raw FFmpeg input and its demuxer or capture format.
 *
 * <p>For example, {@code FFmpegDirectSource.mrl("desktop", "gdigrab")} describes Windows desktop capture.
 * The detector recognizes {@code format||input}, requiring both sides to be nonblank. The direct factory only
 * requires a nonblank format: validity of the locator and backend availability are checked when FFmpeg opens it.
 * Use {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer#ffmpeg()} and release the player afterward.
 * The default source is immutable and the detector is stateless; neither owns native resources.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.source.ffmpeg;
