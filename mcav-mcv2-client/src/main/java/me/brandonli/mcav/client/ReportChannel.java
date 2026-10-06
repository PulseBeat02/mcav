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
package me.brandonli.mcav.client;

/** The plugin channel of the server the client plays on, as a loader offers it. */
interface ReportChannel {
  /**
   * Checks whether the server listens on the channel: a server learns of a report only once it registered the channel,
   * which an MCAV server does as the player joins.
   *
   * @return true if a report may be sent now
   */
  boolean isOpen();

  /**
   * Sends a report.
   *
   * @param report the report's bytes
   */
  void send(byte[] report);
}
