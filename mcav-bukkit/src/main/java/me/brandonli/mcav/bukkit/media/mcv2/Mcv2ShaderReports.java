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

final class Mcv2ShaderReports implements PluginMessageListener {

  static final String CHANNEL = "mcav:mcv2";

  static final int VERSION = 1;

  static final int MAX_BYTES = 64;

  static final int BURST = 5;

  static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

  private static final Logger LOGGER = LoggerFactory.getLogger(Mcv2ShaderReports.class);

  private static final String TOO_LONG = "Ignoring an MCV2 client report of {} bytes from {}: a report has at most {}";

  private static final String TOO_OFTEN = "Ignoring an MCV2 client report from {}: more than one a second";

  private static final String UNKNOWN_VERSION = "Ignoring an MCV2 client report of version {} from {}";

  private static final String MALFORMED = "Ignoring a malformed MCV2 client report from {}";

  private static final String REPORTED = "The MCV2 client mod of {} reports {}: MCV2 screens show them {}";

  private static final String DITHERED_MAPS = "the dithered maps";

  private static final String VIDEO = "the video";

  private static final int REPORT_BYTES = 4;

  private static final int NO_SHADER_PACK = 0;

  private static final int SHADER_PACK_IN_USE = 1;

  private static final int SHADER_PACK_UNKNOWN = 2;

  private static final long BURST_NANOSECONDS = (BURST - 1) * INTERVAL_NANOS;

  private final LongSupplier clock;

  private final Map<UUID, Report> reports;

  private final Map<UUID, Long> allowances;

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
    final UUID viewerId = player.getUniqueId();
    if (!this.admit(viewerId)) {
      LOGGER.debug(TOO_OFTEN, name);
      return;
    }
    final Report report = parse(message, name);
    if (report == null) {
      return;
    }
    final Report before = this.reports.put(viewerId, report);
    if (!Objects.equals(before, report)) {
      LOGGER.info(REPORTED, name, report.describe(), report.blocksDecoding() ? DITHERED_MAPS : VIDEO);
    }
  }

  private boolean admit(final UUID player) {
    final long now = this.clock.getAsLong();
    final Long room = this.allowances.get(player);
    final long next = room == null || room - now < 0 ? now : room;
    if (next - now > BURST_NANOSECONDS) {
      return false;
    }
    this.allowances.put(player, next + INTERVAL_NANOS);
    return true;
  }

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

  boolean blocksDecoding(final UUID player) {
    final Report report = this.reports.get(player);
    return report != null && report.blocksDecoding();
  }

  boolean hasReported(final UUID player) {
    return this.reports.containsKey(player);
  }

  synchronized void forget(final UUID player) {
    this.reports.remove(player);
    this.allowances.remove(player);
  }

  private record Report(boolean irisPresent, int shaderPack, boolean decodesUnderShaders) {
    private boolean blocksDecoding() {
      return this.shaderPack == SHADER_PACK_IN_USE && !this.decodesUnderShaders;
    }

    private String describe() {
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
