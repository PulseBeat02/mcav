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
package me.brandonli.mcav.plugin.data;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.command.interaction.VncAllowList;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Tag;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@Tag("fuzz")
final class PluginDataConfigurationMapperFuzzTest {

  private static final String TEMPORARY_DIRECTORY_PREFIX = "mcav-plugin-configuration-fuzz";
  private static final String CONFIGURATION_FILE_NAME = "config.yml";

  private static final int LOWEST_PORT = 1;
  private static final int HIGHEST_PORT = 65_535;

  private static final char HOST_SEPARATOR = ',';

  @FuzzTest(maxDuration = "30s")
  void shouldReadAnyFileIntoValidSettingsOrFailWithAPluginException(final FuzzedDataProvider data) throws IOException {
    final byte[] contents = data.consumeRemainingAsBytes();
    final Path folder = Files.createTempDirectory(TEMPORARY_DIRECTORY_PREFIX);
    final Path file = folder.resolve(CONFIGURATION_FILE_NAME);
    try {
      Files.write(file, contents);
      this.checkSettings(folder);
    } finally {
      Files.deleteIfExists(file);
      Files.delete(folder);
    }
  }

  private void checkSettings(final Path folder) {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final File folderFile = folder.toFile();
    when(plugin.getDataFolder()).thenReturn(folderFile);
    try (
      final MockedStatic<JavaPlugin> javaPlugin = Mockito.mockStatic(JavaPlugin.class);
      final StandardErrorCapture _ = StandardErrorCapture.start()
    ) {
      javaPlugin.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(plugin);
      final PluginDataConfigurationMapper mapper = new PluginDataConfigurationMapper(plugin);
      this.deserializeAndCheck(mapper);
    }
  }

  private void deserializeAndCheck(final PluginDataConfigurationMapper mapper) {
    try {
      mapper.deserialize();
    } catch (final PluginException exception) {
      return;
    }

    this.checkPorts(mapper);
    this.checkMcv2(mapper);
    this.checkRefusedHosts(mapper);
    this.checkVncEntries(mapper);
  }

  private void checkPorts(final PluginDataConfigurationMapper mapper) {
    final int httpPort = mapper.getHttpPort();
    final int packPort = mapper.getMcv2PackHttpPort();
    final boolean httpPortValid = this.isPort(httpPort);
    final boolean packPortValid = this.isPort(packPort);
    assertTrue(httpPortValid, "HTTP port %s must be a valid port!".formatted(httpPort));
    assertTrue(packPortValid, "Pack port %s must be a valid port!".formatted(packPort));
  }

  private boolean isPort(final int port) {
    return port >= LOWEST_PORT && port <= HIGHEST_PORT;
  }

  private void checkMcv2(final PluginDataConfigurationMapper mapper) {
    final int threads = mapper.getMcv2EncoderThreads();
    final boolean threadsValid = threads >= 0 && threads <= Pool.MAXIMUM_THREADS;
    assertTrue(threadsValid, "Encoder thread count %s must fit the encoder pool!".formatted(threads));

    final String mode = mapper.getMcv2Native();
    final boolean modeValid = mode.equals(Mcv2.NATIVE_AUTO) || mode.equals(Mcv2.NATIVE_OFF);
    assertTrue(modeValid, "Native mode %s must be auto or off!".formatted(mode));
  }

  private void checkRefusedHosts(final PluginDataConfigurationMapper mapper) {
    final List<String> hosts = mapper.getBrowserRefusedHosts();
    for (final String host : hosts) {
      final boolean single = !host.isEmpty() && host.chars().noneMatch(this::isSeparatorOrWhitespace);
      assertTrue(single, "Refused host %s must be a single host name!".formatted(host));
    }
  }

  private boolean isSeparatorOrWhitespace(final int character) {
    return character == HOST_SEPARATOR || Character.isWhitespace(character);
  }

  private void checkVncEntries(final PluginDataConfigurationMapper mapper) {
    final VncAllowList allowList = mapper.getVncAllowList();
    final List<VncAllowList.Entry> entries = allowList.getEntries();
    for (final VncAllowList.Entry entry : entries) {
      final String host = entry.getHost();
      final int port = entry.getPort();
      final boolean named = !host.isBlank() && this.isPort(port);
      assertTrue(named, "VNC entry %s:%s must name a host and a valid port!".formatted(host, port));
    }
  }
}
