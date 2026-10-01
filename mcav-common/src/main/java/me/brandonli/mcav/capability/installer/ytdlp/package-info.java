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
 * Installs the pinned yt-dlp executable used to resolve media URLs from web pages.
 *
 * <p>{@link me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller#shared()} provides the normal installer,
 * serializing concurrent downloads to the default cache folder. Factories also allow a separate destination.
 * Download descriptors and digests are read from the bundled installer resource; zip distributions are extracted
 * and return the executable inside them.
 *
 * <p>Downloading and extraction block the caller and keep installed files for reuse. This package prepares the
 * program; {@link me.brandonli.mcav.json.ytdlp.YTDLPParser} runs it and parses its output. Coordinate separate
 * installer instances that target the same folder, and use the capability readiness API during normal bootstrap.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.capability.installer.ytdlp;
