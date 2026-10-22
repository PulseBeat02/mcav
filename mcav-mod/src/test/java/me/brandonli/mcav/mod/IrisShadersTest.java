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

  @Test
  void shouldReportNoShaderPackAndTouchNothingOfIrisWithoutIris() {
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      final Mcv2Report report = new IrisShaders(false, () -> true).report();
      assertEquals(new Mcv2Report(false, ShaderPack.NONE, false), report);
      iris.verifyNoInteractions();
    }
  }

  @Test
  void shouldReportWhatIrisSaysOfItsShaderPack() {
    final IrisApi api = mock(IrisApi.class);
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenReturn(api);
      final IrisShaders shaders = new IrisShaders(true, () -> false);
      when(api.isShaderPackInUse()).thenReturn(true);
      final Mcv2Report reportWithShaderPack = shaders.report();
      assertEquals(new Mcv2Report(true, ShaderPack.IN_USE, false), reportWithShaderPack);
      when(api.isShaderPackInUse()).thenReturn(false);
      final Mcv2Report reportWithoutShaderPack = shaders.report();
      assertEquals(new Mcv2Report(true, ShaderPack.NONE, false), reportWithoutShaderPack);
    }
  }

  @Test
  void shouldTellWhetherMcv2DecodesUnderTheShaderPackInUse() {
    final IrisApi api = mock(IrisApi.class);
    final AtomicBoolean decodes = new AtomicBoolean(true);
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenReturn(api);
      final IrisShaders shaders = new IrisShaders(true, decodes::get);
      when(api.isShaderPackInUse()).thenReturn(true);
      final Mcv2Report reportWhileDecoding = shaders.report();
      assertEquals(new Mcv2Report(true, ShaderPack.IN_USE, true), reportWhileDecoding);
      decodes.set(false);
      final Mcv2Report reportWhileNotDecoding = shaders.report();
      assertEquals(new Mcv2Report(true, ShaderPack.IN_USE, false), reportWhileNotDecoding);
      decodes.set(true);
      when(api.isShaderPackInUse()).thenReturn(false);
      final Mcv2Report reportWithoutShaderPack = shaders.report();
      assertEquals(new Mcv2Report(true, ShaderPack.NONE, false), reportWithoutShaderPack, "nothing to decode under");
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
      final IrisShaders shaders = new IrisShaders(true, () -> false);
      final Mcv2Report firstReport = shaders.report();
      final Mcv2Report secondReport = shaders.report();
      assertEquals(new Mcv2Report(true, ShaderPack.UNKNOWN, false), firstReport);
      assertEquals(new Mcv2Report(true, ShaderPack.UNKNOWN, false), secondReport);
      iris.verify(IrisApi::getInstance, times(1));
    }
  }

  @Test
  void shouldReportAnIrisWhoseApiLacksTheQuestionAsUnknown() {
    final IrisApi api = mock(IrisApi.class);
    when(api.isShaderPackInUse()).thenThrow(new AbstractMethodError("isShaderPackInUse"));
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenReturn(api);
      final Mcv2Report report = new IrisShaders(true, () -> false).report();
      assertEquals(new Mcv2Report(true, ShaderPack.UNKNOWN, false), report);
    }
  }
}
