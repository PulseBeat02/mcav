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
 * Holds lightweight dimensions, points and pairs used in API arguments and results.
 *
 * <p>{@link me.brandonli.mcav.utils.immutable.Dimension} validates nonnegative pixel dimensions and treats
 * either zero side as empty. {@link me.brandonli.mcav.utils.immutable.Point} stores any double coordinates without
 * validation; the consuming API defines their units and valid range. Both are immutable and can be shared.
 *
 * <p>{@link me.brandonli.mcav.utils.immutable.Pair} is shallowly immutable: its non-null references never change,
 * but the referenced objects are not copied and can themselves be mutable. Equality and hashing delegate to them,
 * so mutating pair elements can change the pair's equality or hash value. These values own no closeable resources.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.utils.immutable;
