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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;
import net.irisshaders.iris.api.v0.IrisApi;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

final class IrisShadersTest {

  private static final boolean IRIS_INSTALLED = true;
  private static final boolean IRIS_MISSING = false;

  private static final boolean DECODES = true;
  private static final boolean DOES_NOT_DECODE = false;

  @Test
  void shouldReportNoShaderPackAndTouchNothingOfIrisWithoutIris() {
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      final Mcv2Report report = new IrisShaders(IRIS_MISSING, () -> true).report();
      assertEquals(Mcv2Report.of(IRIS_MISSING, ShaderPack.NONE, DOES_NOT_DECODE), report);
      iris.verifyNoInteractions();
    }
  }

  @Test
  void shouldReportWhatIrisSaysOfItsShaderPack() {
    final IrisApi api = mock(IrisApi.class);
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenReturn(api);
      final IrisShaders shaders = new IrisShaders(IRIS_INSTALLED, () -> false);
      when(api.isShaderPackInUse()).thenReturn(true);
      final Mcv2Report reportWithShaderPack = shaders.report();
      assertEquals(Mcv2Report.of(IRIS_INSTALLED, ShaderPack.IN_USE, DOES_NOT_DECODE), reportWithShaderPack);
      when(api.isShaderPackInUse()).thenReturn(false);
      final Mcv2Report reportWithoutShaderPack = shaders.report();
      assertEquals(Mcv2Report.of(IRIS_INSTALLED, ShaderPack.NONE, DOES_NOT_DECODE), reportWithoutShaderPack);
    }
  }

  @Test
  void shouldTellWhetherMcv2DecodesUnderTheShaderPackInUse() {
    final IrisApi api = mock(IrisApi.class);
    final AtomicBoolean decodes = new AtomicBoolean(true);
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenReturn(api);
      final IrisShaders shaders = new IrisShaders(IRIS_INSTALLED, decodes::get);
      when(api.isShaderPackInUse()).thenReturn(true);
      final Mcv2Report reportWhileDecoding = shaders.report();
      assertEquals(Mcv2Report.of(IRIS_INSTALLED, ShaderPack.IN_USE, DECODES), reportWhileDecoding);
      decodes.set(false);
      final Mcv2Report reportWhileNotDecoding = shaders.report();
      assertEquals(Mcv2Report.of(IRIS_INSTALLED, ShaderPack.IN_USE, DOES_NOT_DECODE), reportWhileNotDecoding);
      decodes.set(true);
      when(api.isShaderPackInUse()).thenReturn(false);
      final Mcv2Report reportWithoutShaderPack = shaders.report();
      assertEquals(Mcv2Report.of(IRIS_INSTALLED, ShaderPack.NONE, DOES_NOT_DECODE), reportWithoutShaderPack, "nothing to decode under");
    }
  }

  @Test
  void shouldTreatIris1Point11Point7AsTestedWhateverItsBuildMetadata() {
    final boolean releaseTested = IrisShaders.isTested("1.11.7");
    final boolean buildMetadataTested = IrisShaders.isTested("1.11.7+mc26.3");
    final boolean longerPatchTested = IrisShaders.isTested("1.11.70");
    final boolean olderPatchTested = IrisShaders.isTested("1.11.6+mc26.3");
    final boolean preReleaseTested = IrisShaders.isTested("1.11.7-beta.1");
    final boolean emptyTested = IrisShaders.isTested("");
    assertTrue(releaseTested);
    assertTrue(buildMetadataTested);
    assertFalse(longerPatchTested);
    assertFalse(olderPatchTested);
    assertFalse(preReleaseTested);
    assertFalse(emptyTested);
  }

  @Test
  void shouldReportAnIrisWithoutTheApiAsUnknownAndNotAskItAgain() {
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenThrow(new NoClassDefFoundError("net/irisshaders/iris/api/v0/IrisApi"));
      final IrisShaders shaders = new IrisShaders(IRIS_INSTALLED, () -> false);
      final Mcv2Report firstReport = shaders.report();
      final Mcv2Report secondReport = shaders.report();
      assertEquals(Mcv2Report.of(IRIS_INSTALLED, ShaderPack.UNKNOWN, DOES_NOT_DECODE), firstReport);
      assertEquals(Mcv2Report.of(IRIS_INSTALLED, ShaderPack.UNKNOWN, DOES_NOT_DECODE), secondReport);
      iris.verify(IrisApi::getInstance, times(1));
    }
  }

  @Test
  void shouldReportAnIrisWhoseApiLacksTheQuestionAsUnknown() {
    final IrisApi api = mock(IrisApi.class);
    when(api.isShaderPackInUse()).thenThrow(new AbstractMethodError("isShaderPackInUse"));
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenReturn(api);
      final Mcv2Report report = new IrisShaders(IRIS_INSTALLED, () -> false).report();
      assertEquals(Mcv2Report.of(IRIS_INSTALLED, ShaderPack.UNKNOWN, DOES_NOT_DECODE), report);
    }
  }
}
