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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;

/**
 * Represents a specialized hosting solution that combines or builds upon capabilities from
 * the {@code PackHosting} interface. This interface serves as a marker or extension point
 * for resource pack hosting implementations that involve injection mechanisms or additional
 * hosting features beyond the standard pack hosting functionalities.
 * <p>
 * Implementations of this interface may provide injection-based techniques or advanced handling
 * of resource packs for scenarios such as dynamic updates or modified server environments.
 */
public interface InjectorHosting extends PackHosting {}
