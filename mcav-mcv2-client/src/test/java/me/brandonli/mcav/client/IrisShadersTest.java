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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import net.irisshaders.iris.api.v0.IrisApi;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

final class IrisShadersTest {

  @Test
  void withoutIrisReportsNoShaderPackAndTouchesNothingOfIris() {
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      assertEquals(new Mcv2Report(false, ShaderPack.NONE, false), new IrisShaders(false).report());
      iris.verifyNoInteractions();
    }
  }

  @Test
  void reportsWhatIrisSaysOfItsShaderPack() {
    final IrisApi api = mock(IrisApi.class);
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenReturn(api);
      final IrisShaders shaders = new IrisShaders(true);
      when(api.isShaderPackInUse()).thenReturn(true);
      assertEquals(new Mcv2Report(true, ShaderPack.IN_USE, false), shaders.report());
      when(api.isShaderPackInUse()).thenReturn(false);
      assertEquals(new Mcv2Report(true, ShaderPack.NONE, false), shaders.report());
    }
  }

  @Test
  void anIrisWithoutTheApiIsReportedUnknownAndNotAskedAgain() {
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenThrow(new NoClassDefFoundError("net/irisshaders/iris/api/v0/IrisApi"));
      final IrisShaders shaders = new IrisShaders(true);
      assertEquals(new Mcv2Report(true, ShaderPack.UNKNOWN, false), shaders.report());
      assertEquals(new Mcv2Report(true, ShaderPack.UNKNOWN, false), shaders.report());
      iris.verify(IrisApi::getInstance, times(1));
    }
  }

  @Test
  void anIrisWhoseApiLacksTheQuestionIsReportedUnknown() {
    final IrisApi api = mock(IrisApi.class);
    when(api.isShaderPackInUse()).thenThrow(new AbstractMethodError("isShaderPackInUse"));
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenReturn(api);
      assertEquals(new Mcv2Report(true, ShaderPack.UNKNOWN, false), new IrisShaders(true).report());
    }
  }
}
