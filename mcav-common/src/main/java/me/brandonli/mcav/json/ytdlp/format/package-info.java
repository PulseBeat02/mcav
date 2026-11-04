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
 * Immutable data models for the JSON emitted by yt-dlp.
 *
 * <p>{@link me.brandonli.mcav.json.ytdlp.format.UrlParseDump} describes a resolved video and its available
 * {@link me.brandonli.mcav.json.ytdlp.format.Format} objects. Other models describe thumbnails, fragments,
 * headers, download hints and extractor version metadata. Gson fills the private fields; getters return absent
 * references as empty {@code Optional}s or empty lists, and absent primitive values as zero or false. These models do
 * not validate ranges, URLs or consistency between fields.
 *
 * <p>Instances are immutable and thread-safe, and their lists are unmodifiable. Format selection returns objects
 * from these lists. {@link me.brandonli.mcav.json.ytdlp.format.Format#toUriSource()} wraps only the URL; request
 * headers and other playback options must be applied separately by the caller.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.json.ytdlp.format;
