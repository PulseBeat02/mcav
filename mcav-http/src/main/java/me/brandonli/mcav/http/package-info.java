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
 * Serves a browser audio player and current-media metadata over HTTP and WebSocket.
 *
 * <p>Create an {@link me.brandonli.mcav.http.HttpResult} directly or with an
 * {@link me.brandonli.mcav.http.HttpResultBuilder}, start it, and attach it to a player's audio pipeline.
 * The bundled page reads media snapshots from {@code /media} and receives 48 kHz signed 16-bit stereo
 * little-endian PCM from {@code /audio}. The {@link me.brandonli.mcav.http.HttpResult} example shows setup.
 *
 * <p>Servers bind to every interface unless a builder supplies a local bind address. The domain controls the
 * advertised HTTP URL. The caller owns each server: detach producers and stop it when finished, including
 * before unloading its class loader. {@link me.brandonli.mcav.http.HttpModule} does not stop server instances.
 * Builders are mutable and not thread-safe; {@link me.brandonli.mcav.http.MediaInfo} snapshots are immutable.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.http;
