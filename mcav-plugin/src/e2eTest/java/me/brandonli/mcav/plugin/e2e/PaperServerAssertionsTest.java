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
package me.brandonli.mcav.plugin.e2e;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class PaperServerAssertionsTest {

  private static final int SUCCESS_EXIT_CODE = 0;

  private static final String MCAV_LOADED = "MCAV loaded in 1 ms.";
  private static final String NATIVES_LOADED = "JavaCV natives loaded in 1 ms.";
  private static final String VOICE_CHAT_READY = "Simple Voice Chat audio is ready.";
  private static final List<String> STARTUP = List.of(MCAV_LOADED, NATIVES_LOADED, VOICE_CHAT_READY);

  private static final String CONSOLE_ERROR = "[06:13:33 ERROR]: [MCAV Sandbox] Media failed.";

  private static final String IDLE_SNAPSHOT = "{\"duration\":0,\"view_count\":0,\"like_count\":0}";

  @Test
  void shouldAcceptTheCompleteIdleSnapshotInAnyFieldOrder() {
    PaperServerAssertions.assertRanCleanly(SUCCESS_EXIT_CODE, IDLE_SNAPSHOT, STARTUP);
    PaperServerAssertions.assertRanCleanly(SUCCESS_EXIT_CODE, "{\"like_count\":0,\"duration\":0,\"view_count\":0}", STARTUP);
  }

  @ParameterizedTest
  @MethodSource("uncleanRuns")
  void shouldRefuseAnUncleanRunAndNameWhatFailed(final int exitCode, final List<String> output, final String expectedReport) {
    final AssertionError error = assertThrows(AssertionError.class, () ->
      PaperServerAssertions.assertRanCleanly(exitCode, IDLE_SNAPSHOT, output)
    );
    final String report = error.getMessage();

    final boolean named = report.contains(expectedReport);
    assertTrue(named, report);
  }

  static Stream<Arguments> uncleanRuns() {
    final List<String> withoutMcav = List.of(NATIVES_LOADED, VOICE_CHAT_READY);
    final List<String> withoutNatives = List.of(MCAV_LOADED, VOICE_CHAT_READY);
    final List<String> withoutVoiceChat = List.of(MCAV_LOADED, NATIVES_LOADED);
    final List<String> withError = List.of(MCAV_LOADED, NATIVES_LOADED, VOICE_CHAT_READY, CONSOLE_ERROR);
    return Stream.of(
      Arguments.argumentSet("a server that stops with an error code", 1, STARTUP, "the server stops cleanly"),
      Arguments.argumentSet("a server where MCAV never loaded", SUCCESS_EXIT_CODE, withoutMcav, "MCAV loaded in"),
      Arguments.argumentSet("a server without the JavaCV natives", SUCCESS_EXIT_CODE, withoutNatives, "JavaCV natives loaded in"),
      Arguments.argumentSet("a server without voice chat audio", SUCCESS_EXIT_CODE, withoutVoiceChat, "Simple Voice Chat audio is ready"),
      Arguments.argumentSet("a server that logged an error", SUCCESS_EXIT_CODE, withError, CONSOLE_ERROR)
    );
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
  void shouldRefuseIncompleteOrStaleIdleSnapshots(final String response) {
    assertThrows(AssertionError.class, () -> PaperServerAssertions.assertRanCleanly(SUCCESS_EXIT_CODE, response, STARTUP));
  }

  @ParameterizedTest
  @ValueSource(strings = { "{", "{duration:0,view_count:0,like_count:0}", "{\"duration\":0,\"view_count\":0,\"like_count\":0} trailing" })
  void shouldRefuseMalformedMediaInformation(final String response) {
    assertThrows(JsonParseException.class, () -> PaperServerAssertions.assertRanCleanly(SUCCESS_EXIT_CODE, response, STARTUP));
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
      "[render/WARN]: Playback of clip.mp4 could not be started.",
      "[render/WARN]: VLC playback of clip.mp4 could not be started.",
      CONSOLE_ERROR,
      "[06:13:33 WARN]: [MCAV Sandbox] Failed to decode media.",
    }
  )
  void shouldRefuseErrorsUncaughtExceptionsAndPlaybackFailures(final String line) {
    assertThrows(AssertionError.class, () -> PaperServerAssertions.assertNoErrors(List.of(line)));
  }

  @Test
  void shouldAcceptUnrelatedServerWarnings() {
    PaperServerAssertions.assertNoErrors(
      List.of(
        "[Server thread/WARN]: This server is running in offline mode",
        "[Server thread/WARN]: Can't keep up!",
        "[06:13:33 WARN]: **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!"
      )
    );
  }
}
