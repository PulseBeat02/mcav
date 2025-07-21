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

import me.brandonli.util.GlobalTextureState;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class TelevisionScreenBlockEntity extends BlockEntity {

  public TelevisionScreenBlockEntity(final BlockPos pos, final BlockState state) {
    super(ModBlockEntities.TELEVISION_SCREEN_BLOCK_ENTITY, pos, state);
  }

  @Override
  public void writeData(final WriteView nbt) {
    super.writeData(nbt);
  }

  @Override
  public void readData(final ReadView nbt) {
    super.readData(nbt);
  }

  public Direction getFacing() {
    final BlockState state = this.getCachedState();
    return state.get(TelevisionScreenBlock.FACING);
  }

  public int getGlTextureId() {
    return GlobalTextureState.getCurrentGlTextureId();
  }

  public int getTextureWidth() {
    return GlobalTextureState.getTextureWidth();
  }

  public int getTextureHeight() {
    return GlobalTextureState.getTextureHeight();
  }

  public boolean isStreaming() {
    return GlobalTextureState.isStreaming();
  }

  public Identifier getStreamSource() {
    return GlobalTextureState.getTextureIdentifier();
  }
}
