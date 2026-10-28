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
package me.brandonli.mcav.lwjgl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.util.List;
import me.brandonli.mcav.module.MCAVModule;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class LwjglModuleTest {

  private static final String MODULE_NAME = "lwjgl";

  @Test
  void shouldBeNamedLwjgl() {
    final LwjglModule module = new LwjglModule();
    final String name = module.getModuleName();
    assertEquals(MODULE_NAME, name);
  }

  @Test
  void shouldStartAndStopWithoutPreparingAnything() {
    final MCAVModule module = new LwjglModule();
    try (final MockedConstruction<GlTextureFilter> filters = Mockito.mockConstruction(GlTextureFilter.class)) {
      module.start();

      final List<GlTextureFilter> afterFirstStart = filters.constructed();
      assertEquals(List.of(), afterFirstStart);

      module.stop();

      final List<GlTextureFilter> afterFirstStop = filters.constructed();
      assertEquals(List.of(), afterFirstStop);

      module.start();

      final List<GlTextureFilter> afterSecondStart = filters.constructed();
      assertEquals(List.of(), afterSecondStart);

      module.stop();

      final List<GlTextureFilter> afterSecondStop = filters.constructed();
      assertEquals(List.of(), afterSecondStop);
    }

    final String name = module.getModuleName();
    assertEquals(MODULE_NAME, name);
  }

  @Test
  void shouldBeCreatableByTheModuleLoader() throws ReflectiveOperationException {
    final Constructor<LwjglModule> constructor = LwjglModule.class.getConstructor();
    final LwjglModule module = constructor.newInstance();
    final String name = module.getModuleName();
    assertEquals(MODULE_NAME, name);
  }
}
