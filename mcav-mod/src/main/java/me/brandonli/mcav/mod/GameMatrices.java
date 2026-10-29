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
package me.brandonli.mcav.mod;

import com.mojang.blaze3d.vertex.PoseStack;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.checkerframework.checker.nullness.qual.Nullable;

// names no JOML type: the build's javac lint rejects the type annotations of JOML's class files; the matrices are
// found by name, once, and read through JOML's Externalizable form, sixteen floats column by column
final class GameMatrices {

  // a 4x4 matrix
  private static final int FLOATS = 16;

  private static final String POSE_METHOD = "pose";

  private static final String VIEW_ROTATION_FIELD = "viewRotationMatrix";

  private static final String UNREADABLE = "The game's matrix could not be read!";

  private static final String NOT_A_MATRIX = "Matrix %s is not a JOML 4x4 float matrix!";

  private final Method pose;

  private final Field viewRotation;

  GameMatrices(final Method pose, final Field viewRotation) {
    this.pose = pose;
    this.viewRotation = viewRotation;
  }

  static Optional<GameMatrices> find() {
    return find(PoseStack.Pose.class, POSE_METHOD, CameraRenderState.class, VIEW_ROTATION_FIELD);
  }

  // tried once, so a game whose matrices cannot be read gets no reader instead of failing while it renders
  static Optional<GameMatrices> find(final Class<?> poses, final String poseMethod, final Class<?> cameras, final String rotationField) {
    try {
      final GameMatrices matrices = new GameMatrices(poses.getMethod(poseMethod), cameras.getField(rotationField));
      final PoseStack stack = new PoseStack();
      final PoseStack.Pose pose = stack.last();
      matrices.pose(pose);
      matrices.viewRotation(new CameraRenderState());
      return Optional.of(matrices);
    } catch (final NoSuchMethodException | NoSuchFieldException | IllegalStateException exception) {
      return Optional.empty();
    }
  }

  // column-major
  float[] pose(final PoseStack.Pose pose) {
    try {
      return floats(this.pose.invoke(pose));
    } catch (final IllegalAccessException | InvocationTargetException | IllegalArgumentException exception) {
      throw new IllegalStateException(UNREADABLE, exception);
    }
  }

  // column-major
  float[] viewRotation(final CameraRenderState camera) {
    try {
      return floats(this.viewRotation.get(camera));
    } catch (final IllegalAccessException | IllegalArgumentException exception) {
      throw new IllegalStateException(UNREADABLE, exception);
    }
  }

  // the floats of a JOML 4x4 float matrix, column-major
  static float[] floats(final @Nullable Object matrix) {
    if (!(matrix instanceof final Externalizable serialisable)) {
      final String message = NOT_A_MATRIX.formatted(matrix);
      throw new IllegalStateException(message);
    }

    try {
      final MatrixFloats floats = new MatrixFloats();
      serialisable.writeExternal(floats);
      return floats.values();
    } catch (final IOException exception) {
      final String message = NOT_A_MATRIX.formatted(matrix);
      throw new IllegalStateException(message, exception);
    }
  }

  // no stream underneath, so it is never closed
  private static final class MatrixFloats extends ObjectOutputStream {

    private static final String NOT_FLOATS = "A 4x4 float matrix must write 16 floats and nothing else!";

    private final float[] values = new float[FLOATS];

    private int written;

    private MatrixFloats() throws IOException {
      super();
    }

    @Override
    public void writeFloat(final float value) throws IOException {
      if (this.written == FLOATS) {
        throw new IOException(NOT_FLOATS);
      }
      this.values[this.written++] = value;
    }

    private float[] values() throws IOException {
      if (this.written != FLOATS) {
        throw new IOException(NOT_FLOATS);
      }
      return this.values;
    }
  }
}
