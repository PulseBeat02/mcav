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

  private static final String VOICE_CHAT_REQUIRED_PATH = "dependencies/server/voicechat/required";

  private static final String CLASS_FILE_EXTENSION = ".class";

  private static final char PATH_SEPARATOR = '/';

  @Test
  void shouldDeclareInTheDescriptorEveryPermissionTheCommandsCheckForOperatorsAndNoOther()
    throws IOException, URISyntaxException, InvalidConfigurationException {
    final YamlConfiguration descriptor = this.readDescriptor();
    final ConfigurationSection permissions = descriptor.getConfigurationSection("permissions");
    assertNotNull(permissions, "the descriptor declares permissions");

    final Set<String> declared = new TreeSet<>(permissions.getKeys(false));

    final Set<String> expected = this.checkedPermissions();
    assertEquals(expected, declared);

    final String pluginDefault = descriptor.getString("default-permission");
    for (final String name : declared) {
      final String string = permissions.getString("%s/default".formatted(name), pluginDefault);
      assertEquals("op", string, "%s is for operators until granted".formatted(name));

      final boolean blank = permissions.getString("%s/description".formatted(name), "").isBlank();
      assertFalse(blank, "%s says what it allows".formatted(name));
    }
  }

  private YamlConfiguration readDescriptor() throws IOException, InvalidConfigurationException {
    final YamlConfiguration descriptor = new YamlConfiguration();
    descriptor.options().pathSeparator(PATH_SEPARATOR);
    try (final InputStream in = PluginDescriptorTest.class.getResourceAsStream(DESCRIPTOR_RESOURCE_PATH)) {
      assertNotNull(in, "the build writes the descriptor");

      final Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
      descriptor.load(reader);
    }

    return descriptor;
  }

  private Set<String> checkedPermissions() throws IOException, URISyntaxException {
    final Path classes = Path.of(MCAVSandbox.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    final Set<String> checked = new TreeSet<>();
    final List<Path> files;
    try (final Stream<Path> walk = Files.walk(classes.resolve(COMMANDS))) {
      files = walk.filter(file -> file.toString().endsWith(CLASS_FILE_EXTENSION)).toList();
    }
    final ClassLoader loader = PluginDescriptorTest.class.getClassLoader();
    for (final Path file : files) {
      final String relative = classes.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
      final String name = relative.substring(0, relative.length() - CLASS_FILE_EXTENSION.length()).replace(PATH_SEPARATOR, '.');
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

  @Test
  void shouldNameThePluginItsLoaderAndAnOptionalVoiceChatInTheDescriptor() throws IOException, InvalidConfigurationException {
    final YamlConfiguration descriptor = this.readDescriptor();
    final String main = descriptor.getString("main");
    final String loader = descriptor.getString("loader");
    final String pluginClassName = MCAVSandbox.class.getName();
    final String loaderClassName = MCAVLoader.class.getName();
    assertEquals(pluginClassName, main);
    assertEquals(loaderClassName, loader);

    final boolean voiceChatRequired = descriptor.getBoolean(VOICE_CHAT_REQUIRED_PATH, true);
    assertFalse(voiceChatRequired, "MCAV starts without Simple Voice Chat and reports a missing one itself");
  }
}
