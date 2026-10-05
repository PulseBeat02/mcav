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
package me.brandonli.mcav.bukkit.media.mcv2;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shader state the MCV2 client mod of each player reports on the {@value #CHANNEL} plugin channel, which carries
 * these reports and nothing else. Iris replaces the pack's shaders while it draws a shader pack, so a player whose mod
 * reports a shader pack in use, under which MCV2 does not decode, cannot decode the screens.
 *
 * <p>Version 1 of a report is four bytes: the version; 1 if Iris is installed, else 0; the shader pack, 0 for none in
 * use, 1 for one in use, 2 when Iris cannot tell the mod; and 1 if MCV2 decodes under the shader pack, else 0. A player
 * without Iris has no shader pack.
 *
 * <p>Every report is untrusted. One longer than {@value #MAX_BYTES} bytes, of another version, or malformed is ignored
 * with a debug line, and so is one that comes faster than one a second after a burst of {@value #BURST}. A report
 * changes only the state of the player who sent it, which is forgotten when they leave. Reports arrive on the main
 * thread; the states may be read from any thread.
 */
final class Mcv2ShaderReports implements PluginMessageListener {

  /** The plugin channel of the reports. */
  static final String CHANNEL = "mcav:mcv2";

  /** The version of the reports read. */
  static final int VERSION = 1;

  /** The longest report read: a longer one is ignored unread. */
  static final int MAX_BYTES = 64;

  /** How many reports a player may send at once, before the rate of one a second applies. */
  static final int BURST = 5;

  /** The time a report costs a player's allowance. */
  static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

  private static final Logger LOGGER = LoggerFactory.getLogger(Mcv2ShaderReports.class);

  private static final String TOO_LONG = "Ignoring an MCV2 client report of {} bytes from {}: a report has at most {}";

  private static final String TOO_OFTEN = "Ignoring an MCV2 client report from {}: more than one a second";

  private static final String UNKNOWN_VERSION = "Ignoring an MCV2 client report of version {} from {}";

  private static final String MALFORMED = "Ignoring a malformed MCV2 client report from {}";

  private static final String REPORTED = "The MCV2 client mod of {} reports {}: MCV2 screens show them {}";

  private static final int REPORT_BYTES = 4;

  private static final int NO_SHADER_PACK = 0;

  private static final int SHADER_PACK_IN_USE = 1;

  private static final int SHADER_PACK_UNKNOWN = 2;

  /** How far ahead of the clock a player's allowance may run: the reports of a burst after the first. */
  private static final long BURST_NANOS = (BURST - 1) * INTERVAL_NANOS;

  private final LongSupplier clock;

  private final Map<UUID, Report> reports;

  /** When the allowance of each player has room for their next report, guarded by this. */
  private final Map<UUID, Long> allowances;

  /**
   * Constructs the reports of nobody.
   *
   * @param clock the clock of the rate limit, in nanoseconds
   */
  Mcv2ShaderReports(final LongSupplier clock) {
    this.clock = clock;
    this.reports = new ConcurrentHashMap<>();
    this.allowances = new HashMap<>();
  }

  @Override
  public synchronized void onPluginMessageReceived(final String channel, final Player player, final byte[] message) {
    final String name = player.getName();
    if (message.length > MAX_BYTES) {
      LOGGER.debug(TOO_LONG, message.length, name, MAX_BYTES);
      return;
    }
    final UUID uuid = player.getUniqueId();
    if (!this.admit(uuid)) {
      LOGGER.debug(TOO_OFTEN, name);
      return;
    }
    final Report report = parse(message, name);
    if (report == null) {
      return;
    }
    final Report before = this.reports.put(uuid, report);
    if (!Objects.equals(before, report)) {
      LOGGER.info(REPORTED, name, report.describe(), report.blocksDecoding() ? "the dithered maps" : "the video");
    }
  }

  /**
   * Takes a report out of a player's allowance, which refills by one a second up to a burst.
   *
   * @return false if the allowance has no room for it
   */
  private boolean admit(final UUID player) {
    final long now = this.clock.getAsLong();
    final Long room = this.allowances.get(player);
    final long next = room == null || room - now < 0 ? now : room;
    if (next - now > BURST_NANOS) {
      return false;
    }
    this.allowances.put(player, next + INTERVAL_NANOS);
    return true;
  }

  /** Reads a report, or logs why it is ignored. */
  private static @Nullable Report parse(final byte[] message, final String name) {
    if (message.length > 0 && message[0] != VERSION) {
      LOGGER.debug(UNKNOWN_VERSION, message[0], name);
      return null;
    }
    if (message.length != REPORT_BYTES || !isFlag(message[1]) || !isFlag(message[3]) || !isShaderPack(message[2], message[1] == 1)) {
      LOGGER.debug(MALFORMED, name);
      return null;
    }
    return new Report(message[1] == 1, message[2], message[3] == 1);
  }

  private static boolean isFlag(final byte value) {
    return value == 0 || value == 1;
  }

  private static boolean isShaderPack(final byte value, final boolean irisPresent) {
    return value == NO_SHADER_PACK || (irisPresent && (value == SHADER_PACK_IN_USE || value == SHADER_PACK_UNKNOWN));
  }

  /**
   * Checks whether a player's report says their client cannot decode MCV2.
   *
   * @param player the player's UUID
   * @return true if the player reported a shader pack in use, under which MCV2 does not decode
   */
  boolean blocksDecoding(final UUID player) {
    final Report report = this.reports.get(player);
    return report != null && report.blocksDecoding();
  }

  /**
   * Checks whether a player's MCV2 client mod reported anything since they joined.
   *
   * @param player the player's UUID
   * @return true if a report of the player was read
   */
  boolean hasReported(final UUID player) {
    return this.reports.containsKey(player);
  }

  /**
   * Forgets a player who left: their report and their allowance.
   *
   * @param player the player's UUID
   */
  synchronized void forget(final UUID player) {
    this.reports.remove(player);
    this.allowances.remove(player);
  }

  /** A report read. */
  private record Report(boolean irisPresent, int shaderPack, boolean decodesUnderShaders) {
    boolean blocksDecoding() {
      return this.shaderPack == SHADER_PACK_IN_USE && !this.decodesUnderShaders;
    }

    String describe() {
      if (!this.irisPresent) {
        return "no Iris";
      }
      return switch (this.shaderPack) {
        case NO_SHADER_PACK -> "Iris without a shader pack";
        case SHADER_PACK_IN_USE -> this.decodesUnderShaders
          ? "Iris with a shader pack MCV2 decodes under"
          : "Iris with a shader pack in use";
        default -> "Iris, whose shader pack the mod cannot tell";
      };
    }
  }
}
