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
 * Describes optional playback and processing capabilities and tracks background preparation.
 *
 * <p>Use {@link me.brandonli.mcav.MCAVApi#hasCapability(Capability)} for a current availability snapshot and
 * {@link me.brandonli.mcav.MCAVApi#whenCapabilityReady(Capability)} for completion notification. VLC and yt-dlp
 * may still be preparing after core installation returns; FFmpeg loading and face-detection availability are
 * decided during core installation.
 *
 * <p>{@link me.brandonli.mcav.capability.CapabilityGuard} is a thread-safe gate used by factories that require
 * prepared programs. An unknown capability is permitted by the guard, which does not itself install or verify
 * anything. Independent guards are intended for custom bootstraps; normal callers use the API's readiness checks.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.capability;
