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
 * Extracts platform VLC archives into private installations.
 *
 * <p>{@link me.brandonli.mcav.capability.installer.vlc.installation.InstallationStrategy} implementations handle
 * Windows zip archives, macOS disk images and Linux AppImages. They search for nonempty companion native libraries,
 * return their containing directory and delete the downloaded archive after extraction succeeds. Normally
 * {@link me.brandonli.mcav.capability.installer.vlc.VLCInstaller} chooses and invokes the strategy.
 *
 * <p>Extraction can launch external tools and performs blocking file operations, including removal of earlier
 * installation directories. Callers must supply trusted, verified archives and serialize strategies that share
 * a destination. The strategy borrows its installer; it does not own media players or a native VLC instance.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.capability.installer.vlc.installation;
