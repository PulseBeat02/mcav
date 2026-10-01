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
 * Loads the JavaCV natives and prepares optional programs during mcav bootstrap.
 *
 * <p>{@link me.brandonli.mcav.loader.DependencyLoader} is exposed for custom bootstraps, but normal applications
 * use {@link me.brandonli.mcav.MCAVApi#install(Class[])}. Core native loading is synchronous; the API starts VLC
 * and yt-dlp preparation on background workers and tracks their readiness separately from this loader's failure
 * flags. Loading can extract files, download programs and initialize process-wide native libraries.
 *
 * <p>The capability set tolerates concurrent preparation of different optional programs. A newly constructed
 * loader has no recorded failures and therefore reports capabilities before their preparation has been attempted;
 * use the main API's readiness methods when deciding whether a player can be created.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.loader;
