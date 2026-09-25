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
package me.brandonli.mcav.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A scripted browser helper that waits before it connects, so a test can act while the server waits for it: it creates
 * the file named by {@value #GATE_PROPERTY} plus {@value #STARTED_SUFFIX} once it runs, and connects once the file
 * named by {@value #GATE_PROPERTY} exists.
 */
public final class GatedHelperMain {

  /**
   * The system property that names the gate file.
   */
  static final String GATE_PROPERTY = "mcav.test.gate";

  /**
   * The suffix of the file that says the helper runs.
   */
  static final String STARTED_SUFFIX = ".started";

  private GatedHelperMain() {}

  /**
   * Waits for the gate, at most a minute, and then runs the scripted helper.
   *
   * @param args passed on
   * @throws IOException          if the file that says the helper runs cannot be created
   * @throws InterruptedException if the wait is interrupted
   */
  public static void main(final String[] args) throws IOException, InterruptedException {
    final Path gate = Path.of(System.getProperty(GATE_PROPERTY));
    Files.createFile(gate.resolveSibling(gate.getFileName() + STARTED_SUFFIX));
    final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
    while (!Files.exists(gate) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    ScriptedEngine.main(args);
  }
}
