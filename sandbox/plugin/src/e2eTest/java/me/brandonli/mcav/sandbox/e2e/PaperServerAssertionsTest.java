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
package me.brandonli.mcav.sandbox.e2e;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParseException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PaperServerAssertionsTest {

  private static final String IDLE_MEDIA = "{\"duration\":0,\"view_count\":0,\"like_count\":0}";
  private static final List<String> STARTUP = List.of(
    "MCAV loaded in 1 ms",
    "JavaCV natives loaded in 1 ms",
    "Simple Voice Chat audio is ready"
  );

  @Test
  void acceptsTheCompleteIdleSnapshotInAnyFieldOrder() {
    PaperServerEndToEndTest.assertRanCleanly(0, IDLE_MEDIA, STARTUP);
    PaperServerEndToEndTest.assertRanCleanly(0, "{\"like_count\":0,\"duration\":0,\"view_count\":0}", STARTUP);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "{}",
      "{\"duration\":0,\"view_count\":0}",
      "{\"duration\":1,\"view_count\":0,\"like_count\":0}",
      "{\"duration\":\"0\",\"view_count\":0,\"like_count\":0}",
      "{\"duration\":0,\"view_count\":0,\"like_count\":0,\"title\":\"stale\"}",
    }
  )
  void refusesIncompleteOrStaleIdleSnapshots(final String response) {
    assertThrows(AssertionError.class, () -> PaperServerEndToEndTest.assertRanCleanly(0, response, STARTUP));
  }

  @ParameterizedTest
  @ValueSource(strings = { "{", "{duration:0,view_count:0,like_count:0}", "{\"duration\":0,\"view_count\":0,\"like_count\":0} trailing" })
  void refusesMalformedMediaInformation(final String response) {
    assertThrows(JsonParseException.class, () -> PaperServerEndToEndTest.assertRanCleanly(0, response, STARTUP));
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "[Server thread/ERROR]: media failed",
      "Error occurred while enabling MCAV",
      "Exception in thread \"render\" java.lang.IllegalStateException",
      "[render/WARN]: Video filter failed",
      "[render/WARN]: Audio filter failed",
      "[render/WARN]: Failed to decode media",
      "[render/WARN]: Failed to render a frame",
      "[render/WARN]: Failed to start playback of clip.mp4",
      "[render/WARN]: Failed to start VLC playback of clip.mp4",
    }
  )
  void refusesPlaybackFailuresAndUncaughtExceptions(final String line) {
    assertThrows(AssertionError.class, () -> PaperServerEndToEndTest.assertNoErrors(List.of(line)));
  }

  @Test
  void acceptsUnrelatedServerWarnings() {
    PaperServerEndToEndTest.assertNoErrors(
      List.of("[Server thread/WARN]: This server is running in offline mode", "[Server thread/WARN]: Can't keep up!")
    );
  }
}
