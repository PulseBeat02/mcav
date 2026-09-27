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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures what is written to the standard error while it is open, which is the log a browser helper keeps for its
 * server. The tests of a module run one after the other, so no other test writes meanwhile.
 */
public final class StandardError implements AutoCloseable {

  private final PrintStream original = System.err;
  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

  /**
   * Starts capturing.
   */
  public StandardError() {
    System.setErr(new PrintStream(this.captured, true, StandardCharsets.UTF_8));
  }

  /**
   * Gets what was written so far.
   *
   * @return the text
   */
  public String text() {
    return this.captured.toString(StandardCharsets.UTF_8);
  }

  /**
   * Stops capturing.
   */
  @Override
  public void close() {
    System.setErr(this.original);
  }
}
