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
 * The {@code /mcav video} commands, which play a video and control it while it plays, and the {@code /mcav mcv2}
 * commands, which play a stream that is already MCV2-encoded on a wall of maps.
 *
 * <p>{@link me.brandonli.mcav.plugin.command.video.AbstractVideoCommand} holds the flow of the commands that start a
 * video: it resolves the media on a worker thread and starts the player with the display type of the subclass.
 * {@link me.brandonli.mcav.plugin.command.video.VideoPlayerManager} holds the video that is playing, and
 * {@link me.brandonli.mcav.plugin.command.video.Mcv2Support} the resource pack that every MCV2 screen shares.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.plugin.command.video;
