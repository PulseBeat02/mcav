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
 * Bootstraps mcav, its optional modules, native dependencies and capability readiness.
 *
 * <p>Obtain a fresh {@link me.brandonli.mcav.MCAVApi} with {@link me.brandonli.mcav.MCAV#api()}, install it once
 * for the application's lifetime and release it at shutdown. Stop caller-owned media players, servers and
 * filters before releasing the API. Module startup and native preparation can block, while optional VLC and
 * yt-dlp preparation continues in the background; {@link me.brandonli.mcav.MCAVApi#whenCapabilityReady}
 * provides completion notification. The API's class documentation includes a lifecycle example.
 *
 * <p>The default API serializes installation and release. Readiness queries describe the current installation,
 * so a query can race with shutdown. Failures of bootstrap are reported as
 * {@link me.brandonli.mcav.MCAVLoadingException}; a failed installation may be retried.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav;
