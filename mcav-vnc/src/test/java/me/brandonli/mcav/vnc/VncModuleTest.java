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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import me.brandonli.mcav.module.MCAVModule;
import org.junit.jupiter.api.Test;

final class VncModuleTest {

  private static final String MODULE_NAME = "vnc";

  @Test
  void shouldBeNamedVnc() {
    final VncModule module = new VncModule();
    final String name = module.getModuleName();
    assertEquals(MODULE_NAME, name);
  }

  @Test
  void shouldReleaseNoPlayerWhenStopped() {
    final MCAVModule module = new VncModule();
    final VncPlayer player = VncPlayer.create();
    module.start();
    module.stop();

    final boolean released = player.release();
    assertTrue(released, "the player was still unreleased after the module stopped");
  }

  @Test
  void shouldBeCreatableByTheModuleLoader() throws ReflectiveOperationException {
    final Constructor<VncModule> constructor = VncModule.class.getConstructor();
    final VncModule module = constructor.newInstance();
    final String name = module.getModuleName();
    assertEquals(MODULE_NAME, name);
  }
}
