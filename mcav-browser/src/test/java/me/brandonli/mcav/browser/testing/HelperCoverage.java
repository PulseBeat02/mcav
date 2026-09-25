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

/**
 * Carries the JaCoCo agent of the test JVM into the browser helpers the tests start, so the code that only runs
 * inside a helper process, such as CEF's start, counts for the coverage of the module. The helpers append to
 * {@code build/jacoco/helper.exec}, which the coverage report of the module reads besides the one of the tests.
 */
public final class HelperCoverage {

  private HelperCoverage() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets the JVM options that give a helper the coverage agent of this JVM.
   *
   * @return the agent option, or no option when the tests run without JaCoCo, as under PIT
   */
  public static List<String> jvmOptions() {
    final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    final List<String> arguments = runtime.getInputArguments();
    final Path destination = Path.of("build", "jacoco", "helper.exec").toAbsolutePath();
    for (final String argument : arguments) {
      if (argument.startsWith("-javaagent:") && argument.contains("jacocoagent")) {
        final int options = argument.indexOf('=');
        final String agent = options < 0 ? argument : argument.substring(0, options);
        return List.of(
          agent + "=destfile=" + destination + ",append=true,includes=me.brandonli.mcav.*:org.cef.browser.McavOffscreenBrowser*"
        );
      }
    }
    return List.of();
  }
}
