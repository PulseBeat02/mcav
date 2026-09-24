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
package me.brandonli.mcav.bukkit.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import me.brandonli.mcav.bukkit.media.lookup.BlockPaletteLookup;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.scoreboard.Criteria;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

/**
 * Prepares the static state of Minecraft and Bukkit once, before the first test class runs.
 *
 * <p>Many Minecraft classes, such as map packets and players, can only be loaded after the game registries were
 * bootstrapped, and some Bukkit classes, such as {@link Criteria} and the block palette, read the server while they
 * are initialized. If one of them was first loaded without that preparation, it would stay broken for the rest of
 * the test run, so this extension is registered for every test class through extension auto-detection. The block
 * data of the palette are Mockito mocks that answer {@link BlockData#getMaterial()}, and {@link Criteria#DUMMY} is
 * a Mockito mock.
 */
public final class MinecraftTestBootstrap implements BeforeAllCallback {

  private static boolean bootstrapped;

  /**
   * Bootstraps Minecraft before the test class runs, unless that already happened.
   *
   * @param context the context of the test class
   */
  @Override
  public void beforeAll(final @NonNull ExtensionContext context) {
    bootstrap();
  }

  /**
   * Bootstraps the Minecraft registries and initializes the Bukkit classes that read the server, unless that
   * already happened.
   */
  public static synchronized void bootstrap() {
    if (bootstrapped) {
      return;
    }
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    try (final MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
      // the criteria are created lazily, because creating a mock of Criteria initializes its constants
      bukkit.when(() -> Bukkit.getScoreboardCriteria(anyString())).thenAnswer(MinecraftTestBootstrap::createCriteria);
      bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenAnswer(MinecraftTestBootstrap::createBlockData);

      final Criteria initialized = Criteria.DUMMY;
      if (initialized == null) {
        throw new IllegalStateException("The scoreboard criteria were initialized before the bootstrap");
      }
      BlockPaletteLookup.init();
      // A cold init must build the palette while server access is available, before playback asks for it.
      bukkit.verify(() -> Bukkit.createBlockData(any(Material.class)), Mockito.atLeastOnce());
    }
    bootstrapped = true;
  }

  private static Criteria createCriteria(final InvocationOnMock invocation) {
    final String name = invocation.getArgument(0);
    final Criteria criteria = mock(Criteria.class, name);
    when(criteria.getName()).thenReturn(name);
    return criteria;
  }

  private static BlockData createBlockData(final InvocationOnMock invocation) {
    final Material material = invocation.getArgument(0);
    final String name = material.name();
    final BlockData data = mock(BlockData.class, name);
    when(data.getMaterial()).thenReturn(material);
    return data;
  }
}
