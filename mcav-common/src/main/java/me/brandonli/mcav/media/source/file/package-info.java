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
 * Describes local media paths and opens ordinary file streams for application-managed I/O.
 *
 * <p>{@link me.brandonli.mcav.media.source.file.FileSource#path(java.nio.file.Path)} does not require the path
 * to exist. Detection checks existence and also accepts directories; it does not prove the file is decodable.
 * A {@link me.brandonli.mcav.media.source.file.Writable} holds only a path. Each call to open a stream is independent,
 * uses the supplied NIO options, and gives ownership of that stream to the caller. With no output options the
 * usual NIO create/truncate behavior applies. The immutable default handles have nothing to close; opened
 * streams and players have their own lifetimes and synchronization requirements.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.source.file;
