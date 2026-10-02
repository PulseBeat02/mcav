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
 * Classifies the JVM's operating system, processor family and word size for native dependency selection.
 *
 * <p>{@link me.brandonli.mcav.utils.os.OSUtils} caches its classification when initialized, using
 * {@code os.name}, {@code os.arch} and {@code sun.arch.data.model}. It describes the running JVM, including any
 * emulation, rather than independently probing the physical CPU. Later property changes do not update it.
 *
 * <p>{@link me.brandonli.mcav.utils.os.Platform} is an immutable value that can also describe a requested target.
 * A known OS and architecture family does not guarantee an installer has a matching download or that the host
 * can load a binary. All returned values are safe to share and have no cleanup requirement.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.utils.os;
