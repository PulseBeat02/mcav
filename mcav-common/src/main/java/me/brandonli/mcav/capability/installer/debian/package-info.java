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
 * Installs shared libraries and programs from pinned Debian packages, without a package manager or administrator
 * rights.
 *
 * <p>{@link me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller#createVlcInstaller()} installs VLC
 * for 64-bit ARM Linux, and
 * {@link me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller#createGtk2Installer()} installs the
 * GTK 2 libraries that the object detection of OpenCV needs on Linux.
 * {@link me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller#loadLibraries()} then loads them into
 * the JVM in dependency order.
 * {@link me.brandonli.mcav.capability.installer.debian.DebianProgramInstaller#create(String, String, java.util.List)}
 * installs the programs of a package list instead, to run as processes. Packages are downloaded from
 * {@code snapshot.debian.org}, checked against their pinned SHA-256 and extracted by MCAV itself into the cache
 * folder.
 *
 * <p>Downloading, extraction and loading block the caller. Installed libraries are kept for reuse.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.capability.installer.debian;
