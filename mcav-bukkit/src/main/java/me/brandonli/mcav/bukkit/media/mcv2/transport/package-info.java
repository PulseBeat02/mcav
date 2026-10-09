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
 * Splits validated MCV2 frames into checksummed symbol pages and reassembles out-of-order pages.
 *
 * <p>{@link TransportPages} supports six-bit symbols; the Minecraft map alphabet uses six-bit symbols. A map carries
 * whole rows, so strip row padding using the useful-symbol count before passing pages to {@link PageAssembler}.
 * Each assembler belongs to one stream and must be used serially. It holds at most four incomplete frames and
 * does not advance decoder references. CRCs detect corruption and do not authenticate a sender.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.media.mcv2.transport;
