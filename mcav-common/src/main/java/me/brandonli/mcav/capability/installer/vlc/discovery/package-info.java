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
 * Provides bounded native VLC discovery strategies for vlcj.
 *
 * <p>{@link me.brandonli.mcav.capability.installer.vlc.discovery.VLCDiscoveryStrategies} creates strategies
 * for system locations or one known library directory. Strategies search supported providers in priority order,
 * require the platform's library file names, and publish the corresponding plugin directory to native VLC.
 * Use {@link me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit} for the normal discovery/load workflow.
 *
 * <p>Discovery performs file-system I/O. Publishing a plugin path changes process-wide native environment state,
 * and macOS discovery preloads the core native library. Coordinate native discovery/loading rather than racing
 * independent configurations. Custom directory formats and environment callbacks must remain valid for the
 * strategy's lifetime.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.capability.installer.vlc.discovery;
