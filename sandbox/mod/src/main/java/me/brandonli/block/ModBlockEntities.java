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

import me.brandonli.Mcav;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlockEntities {

  public static final BlockEntityType<TelevisionScreenBlockEntity> TELEVISION_SCREEN_BLOCK_ENTITY = createTelevisionScreenBlockEntity();

  private static BlockEntityType<TelevisionScreenBlockEntity> createTelevisionScreenBlockEntity() {
    final Identifier identifier = Identifier.of(Mcav.MOD_ID, "television_screen_block_entity");
    final FabricBlockEntityTypeBuilder<TelevisionScreenBlockEntity> builder = FabricBlockEntityTypeBuilder.create(
      TelevisionScreenBlockEntity::new,
      ModBlocks.TELEVISION_SCREEN_BLOCK
    );
    final BlockEntityType<TelevisionScreenBlockEntity> blockEntityType = builder.build();
    return Registry.register(Registries.BLOCK_ENTITY_TYPE, identifier, blockEntityType);
  }

  public static void init() {}
}
