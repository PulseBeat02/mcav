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
package me.brandonli.block;

import java.util.Optional;
import me.brandonli.Mcav;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

public final class ModBlocks {

  private static final Identifier TELEVISION_SCREEN_IDENTIFIER = Identifier.of(Mcav.MOD_ID, "television_screen");

  public static final Block TELEVISION_SCREEN_BLOCK = Registry.register(
    Registries.BLOCK,
    TELEVISION_SCREEN_IDENTIFIER,
    new TelevisionScreenBlock(createTelevisionScreenSettings())
  );

  public static final Item TELEVISION_SCREEN_BLOCK_ITEM = Registry.register(
    Registries.ITEM,
    TELEVISION_SCREEN_IDENTIFIER,
    new BlockItem(TELEVISION_SCREEN_BLOCK, createTelevisionScreenItemSettings())
  );

  private static Item.Settings createTelevisionScreenItemSettings() {
    final RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, TELEVISION_SCREEN_IDENTIFIER);
    return new Item.Settings().registryKey(itemKey);
  }

  private static AbstractBlock.Settings createTelevisionScreenSettings() {
    final AbstractBlock.Settings baseSettings = AbstractBlock.Settings.copy(Blocks.BLACK_CONCRETE);
    final Identifier lootTableIdentifier = Identifier.of(Mcav.MOD_ID, "blocks/television_screen");
    final RegistryKey<LootTable> lootTableKey = RegistryKey.of(RegistryKeys.LOOT_TABLE, lootTableIdentifier);
    final RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, TELEVISION_SCREEN_IDENTIFIER);
    return baseSettings.strength(2.0f).sounds(BlockSoundGroup.STONE).nonOpaque().lootTable(Optional.of(lootTableKey)).registryKey(blockKey);
  }

  public static void init() {
    ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
      entries.add(TELEVISION_SCREEN_BLOCK);
    });
  }
}
