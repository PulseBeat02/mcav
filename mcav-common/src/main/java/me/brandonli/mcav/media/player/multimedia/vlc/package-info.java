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
 * Implements multimedia playback through VLC and vlcj with a shared native engine.
 *
 * <p>Wait for the VLC capability to become ready before constructing a
 * {@link me.brandonli.mcav.media.player.multimedia.vlc.VLCPlayer}. Opening waits for playback events for up to
 * five seconds. A timeout alone is accepted as a pending slow open; a later VLC error is sent to the player's
 * exception handler. Thus a true start result does not guarantee that a frame has arrived.
 *
 * <p>Video and audio pipelines run on dedicated rendering threads. Video keeps only the latest pending frame;
 * callbacks borrow reusable buffers and must copy data they retain. Lifecycle operations are serialized, and
 * release is terminal. It releases the player's native references and requests renderer shutdown, waiting up to
 * five seconds per render thread without joining the caller itself. The caller owns and releases attached filters.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.multimedia.vlc;
