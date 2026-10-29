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
package me.brandonli.mcav.mod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class Mcv2ReportTest {

  private static final boolean IRIS_INSTALLED = true;
  private static final boolean IRIS_MISSING = false;

  private static final boolean DECODES = true;
  private static final boolean DOES_NOT_DECODE = false;

  @Test
  void shouldWriteVersionOneAsFourBytes() {
    final byte[] withoutIris = Mcv2Report.of(IRIS_MISSING, ShaderPack.NONE, DOES_NOT_DECODE).toBytes();
    final byte[] withShaderPackInUse = Mcv2Report.of(IRIS_INSTALLED, ShaderPack.IN_USE, DOES_NOT_DECODE).toBytes();
    final byte[] decodingUnderAnUnknownPack = Mcv2Report.of(IRIS_INSTALLED, ShaderPack.UNKNOWN, DECODES).toBytes();
    assertArrayEquals(new byte[] { 1, 0, 0, 0 }, withoutIris);
    assertArrayEquals(new byte[] { 1, 1, 1, 0 }, withShaderPackInUse);
    assertArrayEquals(new byte[] { 1, 1, 2, 1 }, decodingUnderAnUnknownPack);
  }

  @Test
  void shouldReturnItsParts() {
    final Mcv2Report decoding = Mcv2Report.of(IRIS_INSTALLED, ShaderPack.IN_USE, DECODES);
    final Mcv2Report withoutIris = Mcv2Report.of(IRIS_MISSING, ShaderPack.NONE, DOES_NOT_DECODE);

    final boolean installedIris = decoding.isIrisPresent();
    final boolean missingIris = withoutIris.isIrisPresent();
    assertTrue(installedIris);
    assertFalse(missingIris);

    final ShaderPack shaderPack = decoding.getShaderPack();
    assertEquals(ShaderPack.IN_USE, shaderPack);

    final boolean decodes = decoding.canDecodeUnderShaders();
    final boolean doesNotDecode = withoutIris.canDecodeUnderShaders();
    assertTrue(decodes);
    assertFalse(doesNotDecode);
  }

  @Test
  void shouldEqualAReportOfTheSameParts() {
    final Mcv2Report report = Mcv2Report.of(IRIS_INSTALLED, ShaderPack.IN_USE, DECODES);
    final Mcv2Report same = Mcv2Report.of(IRIS_INSTALLED, ShaderPack.IN_USE, DECODES);
    final Object itself = report;

    final boolean equalToItself = report.equals(itself);
    final boolean equalToSame = report.equals(same);
    assertTrue(equalToItself);
    assertTrue(equalToSame);

    final int hash = report.hashCode();
    final int sameHash = same.hashCode();
    assertEquals(hash, sameHash);
  }

  @Test
  void shouldDifferFromAReportOfOtherParts() {
    final Mcv2Report report = Mcv2Report.of(IRIS_INSTALLED, ShaderPack.IN_USE, DECODES);
    final Mcv2Report otherIris = Mcv2Report.of(IRIS_MISSING, ShaderPack.IN_USE, DECODES);
    final Mcv2Report otherShaderPack = Mcv2Report.of(IRIS_INSTALLED, ShaderPack.NONE, DECODES);
    final Mcv2Report otherDecoding = Mcv2Report.of(IRIS_INSTALLED, ShaderPack.IN_USE, DOES_NOT_DECODE);
    final Object notAReport = ShaderPack.IN_USE;
    assertNotEquals(report, otherIris);
    assertNotEquals(report, otherShaderPack);
    assertNotEquals(report, otherDecoding);
    assertNotEquals(report, notAReport);

    final int hash = report.hashCode();
    final int otherHash = otherDecoding.hashCode();
    assertNotEquals(hash, otherHash);
  }

  @Test
  void shouldDescribeItsParts() {
    final Mcv2Report report = Mcv2Report.of(IRIS_INSTALLED, ShaderPack.UNKNOWN, DOES_NOT_DECODE);

    final String description = report.toString();
    assertEquals("Mcv2Report{irisPresent=true, shaderPack=UNKNOWN, decodesUnderShaders=false}", description);
  }
}
