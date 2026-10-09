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
 * The MCV2 client mod. Iris draws a shader pack with its own programs, which replace the shaders of the MCV2 resource
 * pack; with Iris 1.11.7 the mod decodes MCV2 under the shader pack anyway ({@link ShaderDecoder},
 * which the mixins of {@code me.brandonli.mcav.mod.mixin} report to). On the {@code mcav:mcv2} plugin channel it tells
 * an MCAV server whether Iris draws a shader pack and whether MCV2 decodes under it, so the server shows this player the
 * dithered maps while it does not. It asks Iris through its public API alone, and works without Iris.
 *
 * <p>{@link FabricEntrypoint} and {@link NeoForgeEntrypoint} are the entry points of the two loaders; each jar holds
 * one of them and the classes they share.
 */
package me.brandonli.mcav.mod;
