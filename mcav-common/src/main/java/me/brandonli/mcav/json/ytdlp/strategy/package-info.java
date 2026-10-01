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
 * Selects audio and video formats from yt-dlp metadata.
 *
 * <p>{@link me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy} offers extension/protocol preferences and
 * numeric quality ranking. {@link me.brandonli.mcav.json.ytdlp.strategy.StrategySelector} pairs separate audio
 * and video strategies and throws {@link me.brandonli.mcav.json.ytdlp.strategy.NoMatchingFormatException} when
 * a required selection is empty. Direct strategy calls instead return an empty {@link java.util.Optional}.
 *
 * <p>Built-in strategies are stateless and skip missing format lists, null entries and absent required markers.
 * They return the original mutable format objects. Do not mutate a dump or its format list during selection;
 * custom strategies define their own threading requirements. Selection performs no media download or decoding.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.json.ytdlp.strategy;
