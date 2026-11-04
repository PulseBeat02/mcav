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
package me.brandonli.mcav.browser.testing;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.util.List;

// Helpers append to build/jacoco/helper.exec, which the coverage report reads besides the file of the tests.
public final class HelperCoverage {

  private static final String JAVA_AGENT_PREFIX = "-javaagent:";
  private static final String JACOCO_AGENT_NAME = "jacocoagent";
  private static final char AGENT_OPTIONS_SEPARATOR = '=';

  private static final String COVERED_CLASSES = "me.brandonli.mcav.*:org.cef.browser.MCAVOffscreenBrowser*";

  private static final Path DESTINATION = Path.of("build", "jacoco", "helper.exec");

  private HelperCoverage() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static List<String> jvmOptions() {
    final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    final List<String> arguments = runtime.getInputArguments();
    final Path destination = DESTINATION.toAbsolutePath();
    for (final String argument : arguments) {
      if (argument.startsWith(JAVA_AGENT_PREFIX) && argument.contains(JACOCO_AGENT_NAME)) {
        final int separatorIndex = argument.indexOf(AGENT_OPTIONS_SEPARATOR);
        final String agent = separatorIndex < 0 ? argument : argument.substring(0, separatorIndex);
        return List.of("%s=destfile=%s,append=true,includes=%s".formatted(agent, destination, COVERED_CLASSES));
      }
    }

    return List.of();
  }
}
