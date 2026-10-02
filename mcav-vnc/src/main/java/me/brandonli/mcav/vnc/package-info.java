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
 * Streams VNC desktops into video pipelines and forwards keyboard and pointer input.
 *
 * <p>Build an immutable {@link me.brandonli.mcav.vnc.VNCSource} with the server address and optional scaling,
 * then create a {@link me.brandonli.mcav.vnc.VNCPlayer}, attach its pipeline and start the connection.
 * Zero dimensions preserve the corresponding remote-screen dimension. The requested frame rate is an upper
 * target; unchanged screens can produce fewer updates. The source and player classes contain setup examples.
 *
 * <p>The default player separates protocol reception from rendering and keeps only the newest pending frame.
 * Its pipeline receives borrowed image buffers valid during the callback. Release players before unloading
 * the backend, then release caller-owned filters as appropriate. {@link me.brandonli.mcav.vnc.VNCModule}
 * does not track or release players. Source builders are mutable and not thread-safe.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.vnc;
