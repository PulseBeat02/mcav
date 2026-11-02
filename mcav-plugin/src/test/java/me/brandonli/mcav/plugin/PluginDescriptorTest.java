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
package me.brandonli.mcav.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.incendo.cloud.annotations.Permission;
import org.junit.jupiter.api.Test;

final class PluginDescriptorTest {

  private static final String COMMANDS = "me/brandonli/mcav/plugin/command";

  private static final String DESCRIPTOR_RESOURCE_PATH = "/paper-plugin.yml";

  @Test
  void shouldDeclareInTheDescriptorEveryPermissionTheCommandsCheckForOperatorsAndNoOther()
    throws IOException, URISyntaxException, InvalidConfigurationException {
    final YamlConfiguration descriptor = new YamlConfiguration();
    descriptor.options().pathSeparator('/');
    try (final InputStream in = PluginDescriptorTest.class.getResourceAsStream(DESCRIPTOR_RESOURCE_PATH)) {
      assertNotNull(in, "the build writes the descriptor");

      final Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
      descriptor.load(reader);
    }
    final ConfigurationSection permissions = descriptor.getConfigurationSection("permissions");
    assertNotNull(permissions, "the descriptor declares permissions");

    final Set<String> declared = new TreeSet<>(permissions.getKeys(false));

    final Set<String> expected = this.checkedPermissions();
    assertEquals(expected, declared);

    final String pluginDefault = descriptor.getString("default-permission");
    for (final String name : declared) {
      final String string = permissions.getString(name + "/default", pluginDefault);
      assertEquals("op", string, name + " is for operators until granted");

      final boolean blank = permissions.getString(name + "/description", "").isBlank();
      assertFalse(blank, name + " says what it allows");
    }
  }

  private Set<String> checkedPermissions() throws IOException, URISyntaxException {
    final Path classes = Path.of(MCAVSandbox.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    final Set<String> checked = new TreeSet<>();
    final List<Path> files;
    try (final Stream<Path> walk = Files.walk(classes.resolve(COMMANDS))) {
      files = walk.filter(file -> file.toString().endsWith(".class")).toList();
    }
    final ClassLoader loader = PluginDescriptorTest.class.getClassLoader();
    for (final Path file : files) {
      final String relative = classes.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
      final String name = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
      final Class<?> type;
      try {
        type = Class.forName(name, false, loader);
      } catch (final ClassNotFoundException exception) {
        throw new IllegalStateException(exception);
      }
      this.addCheckedPermissions(type, checked);
    }

    final boolean empty = checked.isEmpty();
    assertFalse(empty, "the commands check permissions");

    return checked;
  }

  private void addCheckedPermissions(final Class<?> type, final Set<String> checked) {
    for (final Method method : type.getDeclaredMethods()) {
      final Permission permission = method.getAnnotation(Permission.class);
      if (permission != null) {
        checked.addAll(List.of(permission.value()));
      }
    }
  }
}
