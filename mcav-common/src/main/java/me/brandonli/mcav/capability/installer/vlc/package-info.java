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
 * Finds a system VLC installation or prepares a private copy for vlcj.
 *
 * <p>{@link me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit} first tries system discovery,
 * then an existing cached installation, then a download through
 * {@link me.brandonli.mcav.capability.installer.vlc.VLCInstaller}. Factory-created kits serialize native loading
 * and reuse the first successful result. The installer selects a platform archive and delegates extraction
 * to an operating-system-specific strategy.
 *
 * <p>Installation can block on network access, archive extraction and native loading. It is normally run by
 * mcav's background capability preparation. Installed files persist for reuse, and native load state is shared;
 * callers still own and release each media player they create.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.capability.installer.vlc;
