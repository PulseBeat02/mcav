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
 * Resolves Maven dependencies and adds their copied jars to a caller-owned class loader at runtime.
 *
 * <p>{@link me.brandonli.mcav.installer.MCAVInstaller#injector(java.nio.file.Path, java.lang.ClassLoader)}
 * creates an installer. Select a published mcav module with {@link me.brandonli.mcav.installer.Artifact}, or
 * pass Maven coordinates to {@link me.brandonli.mcav.installer.MCAVInstaller#loadDependencies(String, String, String, JarLoader)}.
 * The default {@link me.brandonli.mcav.installer.JarLoader} supports URL-based class loaders; a custom loader
 * can use the host's own dependency-loading mechanism.
 *
 * <p>Loading blocks on network access and file copies. Serialize calls that share a destination folder.
 * The installer closes each resolver session and its copy executor, while the caller keeps ownership of the
 * class loader. Successful injection lasts for that loader's lifetime and has no uninstall operation.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.installer;
