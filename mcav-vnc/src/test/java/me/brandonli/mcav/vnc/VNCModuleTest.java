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
package me.brandonli.mcav.vnc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import me.brandonli.mcav.module.MCAVModule;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link VNCModule}.
 */
final class VNCModuleTest {

  @Test
  void isNamedVnc() {
    final VNCModule module = new VNCModule();
    final String name = module.getModuleName();
    assertEquals("vnc", name);
  }

  @Test
  void startsAndStopsWithoutPreparingAnything() {
    final MCAVModule module = new VNCModule();
    module.start();
    module.stop();
    module.start();
    module.stop();
    final String name = module.getModuleName();
    assertEquals("vnc", name);
  }

  @Test
  void canBeCreatedByTheModuleLoader() throws ReflectiveOperationException {
    final Constructor<VNCModule> constructor = VNCModule.class.getConstructor();
    final VNCModule module = constructor.newInstance();
    final String name = module.getModuleName();
    assertEquals("vnc", name);
  }
}
