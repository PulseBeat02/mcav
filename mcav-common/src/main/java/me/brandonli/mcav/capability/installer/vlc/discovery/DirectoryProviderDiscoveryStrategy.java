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
package me.brandonli.mcav.capability.installer.vlc.discovery;

/*
 * This file is part of VLCJ.
 *
 * VLCJ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VLCJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VLCJ.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2009-2025 Caprica Software Limited.
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.factory.discovery.provider.*;
import uk.co.caprica.vlcj.factory.discovery.strategy.BaseNativeDiscoveryStrategy;

/**
 * Implementation of a native discovery strategy that searches a list of well-known directories.
 * <p>
 * The standard {@link ServiceLoader} mechanism is used to load {@link DiscoveryDirectoryProvider} instances that will
 * provide the lists of directories to search.
 * <p>
 * By using service loader, a client application can easily add their own search directories simply by adding their own
 * implementation of a discovery directory provider to the run-time classpath, and adding/registering their provider
 * class in <code>META-INF/services/uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider</code> - the
 * client application need not concern itself directly with the default {@link NativeDiscovery} component.
 * <p>
 * Provider implementations have a priority. All of the standard provider implementations have a priority &lt; 0, see
 * {@link DiscoveryProviderPriority}. A client application with its own provider implementations can return a priority
 * value as appropriate to ensure their own provider is used before or after the other implementations.
 */
public abstract class DirectoryProviderDiscoveryStrategy extends BaseNativeDiscoveryStrategy {

  /**
   * Service loader for the directory provider implementations.
   */
  private final List<DiscoveryDirectoryProvider> directoryProviders = List.of(
    new ConfigDirConfigFileDiscoveryDirectoryProvider(),
    new JnaLibraryPathDirectoryProvider(),
    new LinuxWellKnownDirectoryProvider(),
    new MacOsWellKnownDirectoryProvider(),
    new WindowsInstallDirectoryProvider(),
    new SystemPathDirectoryProvider(),
    new UserDirDirectoryProvider(),
    new UserDirConfigFileDiscoveryDirectoryProvider()
  );

  /**
   * Create a new native discovery strategy.
   *
   * @param filenamePatterns  filename patterns to search for, as regular expressions
   * @param pluginPathFormats directory name templates used to find the VLC plugin directory, printf style.
   */
  public DirectoryProviderDiscoveryStrategy(final String[] filenamePatterns, final String[] pluginPathFormats) {
    super(filenamePatterns, pluginPathFormats);
  }

  @Override
  public final List<String> discoveryDirectories() {
    final List<String> directories = new ArrayList<>();
    for (final DiscoveryDirectoryProvider provider : this.getSupportedProviders()) {
      directories.addAll(Arrays.asList(provider.directories()));
    }
    return directories;
  }

  private List<DiscoveryDirectoryProvider> getSupportedProviders() {
    final List<DiscoveryDirectoryProvider> result = new ArrayList<>();
    for (final DiscoveryDirectoryProvider service : this.directoryProviders) {
      if (service.supported()) {
        result.add(service);
      }
    }
    return this.sort(result);
  }

  private List<DiscoveryDirectoryProvider> sort(final List<DiscoveryDirectoryProvider> providers) {
    providers.sort((p1, p2) -> p2.priority() - p1.priority());
    return providers;
  }
}
