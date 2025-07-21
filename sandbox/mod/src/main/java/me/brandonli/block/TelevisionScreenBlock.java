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

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class TelevisionScreenBlock extends Block implements BlockEntityProvider {

  public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

  private static final VoxelShape NORTH_SHAPE = Block.createCuboidShape(0.0, 0.0, 15.0, 16.0, 16.0, 16.0);
  private static final VoxelShape SOUTH_SHAPE = Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 1.0);
  private static final VoxelShape WEST_SHAPE = Block.createCuboidShape(15.0, 0.0, 0.0, 16.0, 16.0, 16.0);
  private static final VoxelShape EAST_SHAPE = Block.createCuboidShape(0.0, 0.0, 0.0, 1.0, 16.0, 16.0);

  public TelevisionScreenBlock(final Settings settings) {
    super(settings);
  }

  @Override
  protected MapCodec<? extends Block> getCodec() {
    return MapCodec.unit(this);
  }

  @Override
  protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
    builder.add(FACING);
    super.appendProperties(builder);
  }

  @Override
  public BlockState getPlacementState(final ItemPlacementContext ctx) {
    final Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
    return this.getDefaultState().with(FACING, facing);
  }

  @Override
  protected VoxelShape getOutlineShape(
    final BlockState state,
    final BlockView world,
    final BlockPos pos,
    final net.minecraft.block.ShapeContext context
  ) {
    return switch (state.get(FACING)) {
      case NORTH -> NORTH_SHAPE;
      case SOUTH -> SOUTH_SHAPE;
      case WEST -> WEST_SHAPE;
      case EAST -> EAST_SHAPE;
      default -> VoxelShapes.fullCube();
    };
  }

  @Override
  protected ActionResult onUse(
    final BlockState state,
    final World world,
    final BlockPos pos,
    final PlayerEntity player,
    final BlockHitResult hit
  ) {
    return ActionResult.SUCCESS;
  }

  @Override
  public @Nullable BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
    return new TelevisionScreenBlockEntity(pos, state);
  }
}
