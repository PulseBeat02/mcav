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
 * Finds, downloads and prepares external programs used by optional mcav capabilities.
 *
 * <p>{@link me.brandonli.mcav.capability.installer.Installer} separates discovery of an existing installation
 * from downloading. {@link me.brandonli.mcav.capability.installer.Download} describes a platform-specific URL
 * and optional SHA-256 digest. {@link me.brandonli.mcav.capability.installer.AbstractInstaller} supplies lazy
 * platform selection, per-instance download serialization and persistent installation-path configuration.
 *
 * <p>Discovery and download perform file or network I/O and can block. Existing installations are reused;
 * callers should coordinate separate installer instances that target the same files. An installer does not
 * own a running media player, and disposing of a player does not delete its cached programs.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.capability.installer;
