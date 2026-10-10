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

/**
 * The game's matrices as floats. The game keeps them as JOML matrices, and JOML's class files carry type annotations
 * their class file version predates, which the build's javac lint rejects: so the mod names no JOML type. It finds the
 * game's matrices by name, once, and reads them through JOML's {@link Externalizable} form, which writes the sixteen
 * floats of a 4x4 matrix column by column.
 */
final class GameMatrices {

  /** The floats of a 4x4 matrix. */
  private static final int FLOATS = 16;

  private static final String POSE_METHOD = "pose";

  private static final String VIEW_ROTATION_FIELD = "viewRotationMatrix";

  private static final String UNREADABLE = "The game's matrix could not be read";

  private static final String NOT_A_MATRIX = "Not a JOML 4x4 float matrix: %s";

  private final Method pose;

  private final Field viewRotation;

  /**
   * Constructs the reader.
   *
   * @param pose         the method that gives a pose's matrix
   * @param viewRotation the field that holds the camera's rotation
   */
  GameMatrices(final Method pose, final Field viewRotation) {
    this.pose = pose;
    this.viewRotation = viewRotation;
  }

  /**
   * The reader of this game's matrices.
   *
   * @return the reader, or empty when the game has no matrices of those names, or they cannot be read
   */
  static Optional<GameMatrices> find() {
    return find(PoseStack.Pose.class, POSE_METHOD, CameraRenderState.class, VIEW_ROTATION_FIELD);
  }

  /**
   * The reader of the given members, tried once on new objects of the game's, so a game whose matrices cannot be read
   * this way gets no reader at all instead of a failure while it renders.
   *
   * @param poses           the class of poses
   * @param poseMethod      the name of the method that gives a pose's matrix
   * @param cameras         the class of cameras
   * @param rotationField   the name of the field that holds a camera's rotation
   * @return the reader, or empty
   */
  static Optional<GameMatrices> find(final Class<?> poses, final String poseMethod, final Class<?> cameras, final String rotationField) {
    try {
      final GameMatrices matrices = new GameMatrices(poses.getMethod(poseMethod), cameras.getField(rotationField));
      matrices.pose(new PoseStack().last());
      matrices.viewRotation(new CameraRenderState());
      return Optional.of(matrices);
    } catch (final NoSuchMethodException | NoSuchFieldException | IllegalStateException unreadable) {
      return Optional.empty();
    }
  }

  /**
   * A pose's matrix.
   *
   * @param pose the pose
   * @return its matrix, column-major
   */
  float[] pose(final PoseStack.Pose pose) {
    try {
      return floats(this.pose.invoke(pose));
    } catch (final IllegalAccessException | InvocationTargetException | IllegalArgumentException unreadable) {
      throw new IllegalStateException(UNREADABLE, unreadable);
    }
  }

  /**
   * The camera's rotation.
   *
   * @param camera the camera of a frame
   * @return its rotation, column-major
   */
  float[] viewRotation(final CameraRenderState camera) {
    try {
      return floats(this.viewRotation.get(camera));
    } catch (final IllegalAccessException | IllegalArgumentException unreadable) {
      throw new IllegalStateException(UNREADABLE, unreadable);
    }
  }

  /**
   * The floats of one of the game's matrices.
   *
   * @param matrix a JOML 4x4 float matrix
   * @return its floats, column-major
   * @throws IllegalStateException when it is something else
   */
  static float[] floats(final @Nullable Object matrix) {
    if (!(matrix instanceof final Externalizable serialisable)) {
      throw new IllegalStateException(String.format(NOT_A_MATRIX, matrix));
    }
    try {
      final MatrixFloats floats = new MatrixFloats();
      serialisable.writeExternal(floats);
      return floats.values();
    } catch (final IOException unreadable) {
      throw new IllegalStateException(String.format(NOT_A_MATRIX, matrix), unreadable);
    }
  }

  /** Keeps the floats a matrix writes for serialisation; it has no stream underneath, so it is never closed. */
  private static final class MatrixFloats extends ObjectOutputStream {

    private static final String NOT_FLOATS = "A 4x4 float matrix writes 16 floats and nothing else";

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
