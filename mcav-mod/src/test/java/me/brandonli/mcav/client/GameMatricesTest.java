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
package me.brandonli.mcav.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.junit.jupiter.api.Test;

final class GameMatricesTest {

  private static final float[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

  @Test
  void readsAPoseColumnByColumn() {
    final GameMatrices matrices = GameMatrices.find().orElseThrow();
    final PoseStack stack = new PoseStack();
    stack.translate(1F, 2F, 3F);
    stack.scale(2F, 4F, 8F);
    assertArrayEquals(new float[] { 2, 0, 0, 0, 0, 4, 0, 0, 0, 0, 8, 0, 1, 2, 3, 1 }, matrices.pose(stack.last()));
  }

  @Test
  void readsTheRotationOfTheCamera() throws ReflectiveOperationException {
    final GameMatrices matrices = GameMatrices.find().orElseThrow();
    final CameraRenderState camera = new CameraRenderState();
    assertArrayEquals(IDENTITY, matrices.viewRotation(camera));
    final PoseStack stack = new PoseStack();
    stack.translate(5F, 6F, 7F);
    final Object translation = PoseStack.Pose.class.getMethod("pose").invoke(stack.last());
    CameraRenderState.class.getField("viewRotationMatrix").set(camera, translation);
    assertArrayEquals(new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 5, 6, 7, 1 }, matrices.viewRotation(camera));
  }

  @Test
  void aGameWithoutTheseMembersOrWhoseMembersCannotBeReadHasNoReader() {
    assertTrue(GameMatrices.find(PoseStack.Pose.class, "matrix", CameraRenderState.class, "viewRotationMatrix").isEmpty());
    assertTrue(GameMatrices.find(PoseStack.Pose.class, "pose", CameraRenderState.class, "rotationMatrix").isEmpty());
    assertTrue(GameMatrices.find(String.class, "length", CameraRenderState.class, "viewRotationMatrix").isEmpty());
    assertTrue(GameMatrices.find(PoseStack.Pose.class, "pose", Members.class, "matrix").isEmpty());
    assertTrue(GameMatrices.find().isPresent());
  }

  @Test
  void aMemberThatFailsIsAnIllegalState() throws ReflectiveOperationException {
    final PoseStack.Pose pose = new PoseStack().last();
    final CameraRenderState camera = new CameraRenderState();
    final GameMatrices hidden = new GameMatrices(Members.class.getDeclaredMethod("hidden"), Members.class.getDeclaredField("secret"));
    assertInstanceOf(IllegalAccessException.class, assertThrows(IllegalStateException.class, () -> hidden.pose(pose)).getCause());
    assertInstanceOf(IllegalAccessException.class, assertThrows(IllegalStateException.class, () -> hidden.viewRotation(camera)).getCause());
    final GameMatrices failing = new GameMatrices(Members.class.getMethod("fails"), Members.class.getField("matrix"));
    assertInstanceOf(InvocationTargetException.class, assertThrows(IllegalStateException.class, () -> failing.pose(pose)).getCause());
    assertInstanceOf(
      IllegalArgumentException.class,
      assertThrows(IllegalStateException.class, () -> failing.viewRotation(camera)).getCause()
    );
    final GameMatrices strings = new GameMatrices(String.class.getMethod("length"), Members.class.getField("matrix"));
    assertInstanceOf(IllegalArgumentException.class, assertThrows(IllegalStateException.class, () -> strings.pose(pose)).getCause());
  }

  @Test
  void onlyAFourByFourFloatMatrixIsReadAsOne() {
    assertThrows(IllegalStateException.class, () -> GameMatrices.floats(null));
    assertThrows(IllegalStateException.class, () -> GameMatrices.floats("a matrix"));
    assertInstanceOf(IOException.class, assertThrows(IllegalStateException.class, () -> GameMatrices.floats(new Floats(9))).getCause());
    assertInstanceOf(IOException.class, assertThrows(IllegalStateException.class, () -> GameMatrices.floats(new Floats(17))).getCause());
    assertInstanceOf(IOException.class, assertThrows(IllegalStateException.class, () -> GameMatrices.floats(new Floats(-1))).getCause());
    assertArrayEquals(new float[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 }, GameMatrices.floats(new Floats(16)));
  }

  /** Writes the floats 0, 1, 2 and on, as many as asked, or fails for a negative count. */
  public static final class Floats implements Externalizable {

    private static final long serialVersionUID = 1L;

    private final int count;

    public Floats() {
      this(0);
    }

    Floats(final int count) {
      this.count = count;
    }

    @Override
    public void writeExternal(final ObjectOutput out) throws IOException {
      if (this.count < 0) {
        throw new IOException("failed");
      }
      for (int index = 0; index < this.count; index++) {
        out.writeFloat(index);
      }
    }

    @Override
    public void readExternal(final ObjectInput in) {
      throw new UnsupportedOperationException();
    }
  }

  /** Members that are not the game's: one that fails, and two no other class may read. */
  static final class Members {

    public final Object matrix = new Object();

    @SuppressWarnings("unused")
    private final Object secret = new Object();

    public static Object fails() {
      throw new IllegalStateException("fails");
    }

    @SuppressWarnings("unused")
    private static Object hidden() {
      return new Object();
    }
  }
}
