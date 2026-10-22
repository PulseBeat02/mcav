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
import java.util.Optional;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

final class GameMatricesTest {

  private static final float[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

  private static final String POSE_METHOD = "pose";

  private static final String VIEW_ROTATION_FIELD = "viewRotationMatrix";

  private static final String MATRIX_FIELD = "matrix";

  private static final String LENGTH_METHOD = "length";

  @Test
  void shouldReadAPoseColumnByColumn() {
    final GameMatrices matrices = GameMatrices.find().orElseThrow();
    final PoseStack stack = new PoseStack();
    stack.translate(1F, 2F, 3F);
    stack.scale(2F, 4F, 8F);
    final float[] pose = matrices.pose(stack.last());
    assertArrayEquals(new float[] { 2, 0, 0, 0, 0, 4, 0, 0, 0, 0, 8, 0, 1, 2, 3, 1 }, pose);
  }

  @Test
  void shouldReadTheRotationOfTheCamera() throws ReflectiveOperationException {
    final GameMatrices matrices = GameMatrices.find().orElseThrow();
    final CameraRenderState camera = new CameraRenderState();
    final float[] rotation = matrices.viewRotation(camera);
    assertArrayEquals(IDENTITY, rotation);
    final PoseStack stack = new PoseStack();
    stack.translate(5F, 6F, 7F);
    final Object translation = PoseStack.Pose.class.getMethod(POSE_METHOD).invoke(stack.last());
    CameraRenderState.class.getField(VIEW_ROTATION_FIELD).set(camera, translation);
    final float[] translatedRotation = matrices.viewRotation(camera);
    assertArrayEquals(new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 5, 6, 7, 1 }, translatedRotation);
  }

  @Test
  void shouldHaveNoReaderForAGameWithoutTheseMembersOrWhoseMembersCannotBeRead() {
    final Optional<GameMatrices> unknownPoseMethod = GameMatrices.find(
      PoseStack.Pose.class,
      "matrix",
      CameraRenderState.class,
      VIEW_ROTATION_FIELD
    );
    final Optional<GameMatrices> unknownRotationField = GameMatrices.find(
      PoseStack.Pose.class,
      POSE_METHOD,
      CameraRenderState.class,
      "rotationMatrix"
    );
    final Optional<GameMatrices> foreignPoseMethod = GameMatrices.find(
      String.class,
      LENGTH_METHOD,
      CameraRenderState.class,
      VIEW_ROTATION_FIELD
    );
    final Optional<GameMatrices> foreignRotationField = GameMatrices.find(PoseStack.Pose.class, POSE_METHOD, Members.class, MATRIX_FIELD);
    final Optional<GameMatrices> gameMatrices = GameMatrices.find();
    assertTrue(unknownPoseMethod.isEmpty());
    assertTrue(unknownRotationField.isEmpty());
    assertTrue(foreignPoseMethod.isEmpty());
    assertTrue(foreignRotationField.isEmpty());
    assertTrue(gameMatrices.isPresent());
  }

  @Test
  void shouldThrowAnIllegalStateWhenAMemberFails() throws ReflectiveOperationException {
    final PoseStack.Pose pose = new PoseStack().last();
    final CameraRenderState camera = new CameraRenderState();
    final GameMatrices hidden = new GameMatrices(Members.class.getDeclaredMethod("hidden"), Members.class.getDeclaredField("secret"));
    final Throwable hiddenPose = this.causeOf(() -> hidden.pose(pose));
    final Throwable hiddenRotation = this.causeOf(() -> hidden.viewRotation(camera));
    assertInstanceOf(IllegalAccessException.class, hiddenPose);
    assertInstanceOf(IllegalAccessException.class, hiddenRotation);
    final GameMatrices failing = new GameMatrices(Members.class.getMethod("fails"), Members.class.getField(MATRIX_FIELD));
    final Throwable failingPose = this.causeOf(() -> failing.pose(pose));
    final Throwable failingRotation = this.causeOf(() -> failing.viewRotation(camera));
    assertInstanceOf(InvocationTargetException.class, failingPose);
    assertInstanceOf(IllegalArgumentException.class, failingRotation);
    final GameMatrices strings = new GameMatrices(String.class.getMethod(LENGTH_METHOD), Members.class.getField(MATRIX_FIELD));
    final Throwable stringPose = this.causeOf(() -> strings.pose(pose));
    assertInstanceOf(IllegalArgumentException.class, stringPose);
  }

  private Throwable causeOf(final Executable call) {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, call);
    return exception.getCause();
  }

  @Test
  void shouldReadOnlyAFourByFourFloatMatrixAsOne() {
    assertThrows(IllegalStateException.class, () -> GameMatrices.floats(null));
    assertThrows(IllegalStateException.class, () -> GameMatrices.floats("a matrix"));
    final Throwable tooShort = this.causeOf(() -> GameMatrices.floats(new Floats(9)));
    final Throwable tooLong = this.causeOf(() -> GameMatrices.floats(new Floats(17)));
    final Throwable negativeLength = this.causeOf(() -> GameMatrices.floats(new Floats(-1)));
    assertInstanceOf(IOException.class, tooShort);
    assertInstanceOf(IOException.class, tooLong);
    assertInstanceOf(IOException.class, negativeLength);
    final float[] floats = GameMatrices.floats(new Floats(16));
    assertArrayEquals(new float[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 }, floats);
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
    public void writeExternal(final ObjectOutput output) throws IOException {
      if (this.count < 0) {
        throw new IOException("Writing the floats failed!");
      }
      for (int index = 0; index < this.count; index++) {
        output.writeFloat(index);
      }
    }

    @Override
    public void readExternal(final ObjectInput input) {
      throw new UnsupportedOperationException();
    }
  }

  /** Members that are not the game's: one that fails, and two no other class may read. */
  static final class Members {

    public final Object matrix = new Object();

    @SuppressWarnings("unused")
    private final Object secret = new Object();

    public static Object fails() {
      throw new IllegalStateException("The member failed!");
    }

    @SuppressWarnings("unused")
    private static Object hidden() {
      return new Object();
    }
  }
}
