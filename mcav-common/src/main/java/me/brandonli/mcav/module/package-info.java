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
 * Defines optional mcav integrations and their startup/shutdown lifecycle.
 *
 * <p>Pass {@link me.brandonli.mcav.module.MCAVModule} implementation classes to
 * {@link me.brandonli.mcav.MCAVApi#install(Class[])}. {@link me.brandonli.mcav.module.ModuleLoader} constructs
 * each distinct class with a no-argument constructor, starts modules in request order and stops them in reverse
 * order. A failing start is followed by a stop attempt for that module; when using the loader directly, callers
 * must shut down previously started modules themselves.
 *
 * <p>Loader lifecycle operations are serialized, but the module's own API determines how its methods may be used
 * from other threads. Module registration does not imply ownership of every player or server created through
 * that module; each backend documents which resources its caller must release.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.module;
