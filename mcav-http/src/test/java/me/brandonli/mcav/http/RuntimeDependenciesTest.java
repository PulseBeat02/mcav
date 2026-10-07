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
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RuntimeDependenciesTest {

  @Test
  void lifecycleAnnotationsHaveOneRuntimeDefinition() throws IOException {
    final ClassLoader loader = RuntimeDependenciesTest.class.getClassLoader();
    for (final String name : List.of("jakarta/annotation/PostConstruct.class", "jakarta/annotation/PreDestroy.class")) {
      final List<URL> definitions = Collections.list(loader.getResources(name));
      assertEquals(1, definitions.size(), "one runtime definition of " + name + ": " + definitions);
    }
  }
}
