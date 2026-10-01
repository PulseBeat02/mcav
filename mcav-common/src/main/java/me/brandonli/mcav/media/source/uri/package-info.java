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
 * Describes URI inputs such as network streams and web pages resolved through yt-dlp.
 *
 * <p>The direct {@link me.brandonli.mcav.media.source.uri.UriSource#uri(java.net.URI)} factory retains any
 * non-null URI; it does not contact the network or require a scheme and host. The detector is stricter and
 * requires both. {@link me.brandonli.mcav.media.source.uri.UriSource#isDirect()} uses a known media extension
 * in the URI path as a heuristic, ignoring query and fragment; it does not inspect response headers or content.
 * Default sources are immutable and the detector is stateless. They own no connections; the selected player
 * owns decoding resources and must be released by its caller.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.source.uri;
