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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class Mcv2ShaderReportsFuzzTest {

  private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000061");

  private static final int REPORT_BYTES = 4;

  private static final int IRIS_PRESENT_INDEX = 1;
  private static final int SHADER_PACK_INDEX = 2;
  private static final int DECODES_UNDER_SHADERS_INDEX = 3;

  private static final byte NO_SHADER_PACK = 0;
  private static final byte SHADER_PACK_IN_USE = 1;
  private static final byte SHADER_PACK_UNKNOWN = 2;

  private Player viewer;

  @BeforeEach
  void createViewer() {
    this.viewer = mock(Player.class, withSettings().stubOnly());
    when(this.viewer.getUniqueId()).thenReturn(VIEWER_ID);
    when(this.viewer.getName()).thenReturn("Alice");
  }

  @FuzzTest(maxDuration = "30s")
  void shouldKeepOnlyAWellFormedReportOfAnyBytes(final byte[] message) {
    final Mcv2ShaderReports reports = new Mcv2ShaderReports(System::nanoTime);
    reports.onPluginMessageReceived(Mcv2ShaderReports.CHANNEL, this.viewer, message);

    final boolean wellFormed = this.isWellFormed(message);
    final boolean reported = reports.hasReported(VIEWER_ID);
    assertEquals(wellFormed, reported, "only a well-formed report is kept");

    final boolean expectedBlocking = wellFormed && this.isShaderPackUndecoded(message);
    final boolean blocksDecoding = reports.blocksDecoding(VIEWER_ID);
    assertEquals(expectedBlocking, blocksDecoding, "only a shader pack in use that MCV2 does not decode under blocks it");
  }

  private boolean isWellFormed(final byte[] message) {
    if (message.length != REPORT_BYTES || message[0] != Mcv2ShaderReports.VERSION) {
      return false;
    }

    final boolean irisPresent = message[IRIS_PRESENT_INDEX] == 1;
    final byte shaderPack = message[SHADER_PACK_INDEX];
    final boolean irisShaderPack = shaderPack == SHADER_PACK_IN_USE || shaderPack == SHADER_PACK_UNKNOWN;
    final boolean knownShaderPack = shaderPack == NO_SHADER_PACK || (irisPresent && irisShaderPack);
    return this.isFlag(message[IRIS_PRESENT_INDEX]) && this.isFlag(message[DECODES_UNDER_SHADERS_INDEX]) && knownShaderPack;
  }

  private boolean isFlag(final byte value) {
    return value == 0 || value == 1;
  }

  private boolean isShaderPackUndecoded(final byte[] message) {
    return message[SHADER_PACK_INDEX] == SHADER_PACK_IN_USE && message[DECODES_UNDER_SHADERS_INDEX] == 0;
  }
}
