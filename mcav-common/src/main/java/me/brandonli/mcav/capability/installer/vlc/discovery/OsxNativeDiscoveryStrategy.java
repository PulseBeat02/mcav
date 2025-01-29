/*
 * This file is part of mcav, a media playback library for Minecraft
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

import com.sun.jna.NativeLibrary;
import uk.co.caprica.vlcj.binding.lib.LibC;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;

/**
 * Default implementation of a native discovery strategy that searches directories on the OSX operating system.
 */
public class OsxNativeDiscoveryStrategy extends DirectoryProviderDiscoveryStrategy {

  private static final String[] FILENAME_PATTERNS = new String[] { "libvlc\\.dylib", "libvlccore\\.dylib" };

  /**
   * Format string to prepare the plugin path environment variable value.
   */
  private static final String[] PLUGIN_PATH_FORMATS = new String[] { "%s/../plugins" };

  public OsxNativeDiscoveryStrategy() {
    super(FILENAME_PATTERNS, PLUGIN_PATH_FORMATS);
  }

  @Override
  public boolean supported() {
    return RuntimeUtil.isMac();
  }

  @Override
  public boolean onFound(final String path) {
    this.forceLoadLibVlcCore(path);
    return true;
  }

  /**
   * On later versions of OSX, it is necessary to force-load libvlccore before libvlc, otherwise libvlc will fail to
   * load.
   *
   * @param path
   */
  private void forceLoadLibVlcCore(final String path) {
    NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), path);
    NativeLibrary.getInstance(RuntimeUtil.getLibVlcCoreLibraryName());
  }

  @Override
  protected boolean setPluginPath(final String pluginPath) {
    return LibC.INSTANCE.setenv(PLUGIN_ENV_NAME, pluginPath, 1) == 0;
  }
}
