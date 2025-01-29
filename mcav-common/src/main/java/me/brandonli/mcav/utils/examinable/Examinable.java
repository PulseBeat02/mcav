/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.utils.examinable;

import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

public interface Examinable {
  List<? extends ExaminableProperty<?>> getExaminableProperties();

  <T> @Nullable T get(final ExaminableProperty<T> property);

  <T> T getOrThrow(final ExaminableProperty<T> property);

  <T> void set(final ExaminableProperty<T> property, final T value);

  <T> boolean has(final ExaminableProperty<T> property);
}
