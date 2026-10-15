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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Tag;

/**
 * Feeds {@link PageAudio#parse(String, String)} what a page controls: the parameters of a binding event.
 */
@Tag("fuzz")
final class PageAudioFuzzTest {

  private static final String PREFIX = "{\"name\":\"" + PageAudio.BINDING + "\",\"payload\":\"";

  /**
   * The first byte picks the event, odd for a call of a binding; the rest is its parameters.
   *
   * @param input the event
   */
  @FuzzTest(maxDuration = "30s")
  void shouldTakeOnlyWholeFramesOfItsOwnBinding(final byte[] input) {
    if (input.length == 0) {
      return;
    }

    final String method = (input[0] & 1) == 1 ? PageAudio.BINDING_EVENT : "Runtime.consoleAPICalled";
    final String parameters = new String(input, 1, input.length - 1, StandardCharsets.UTF_8);
    final PageAudio.Chunk chunk = PageAudio.parse(method, parameters);
    if (chunk != null) {
      check(chunk.samples());
      final boolean fromABinding = PageAudio.BINDING_EVENT.equals(method);
      assertTrue(fromABinding, "only a call of a binding is sound");
      final boolean fromOwnBinding = parameters.startsWith(PREFIX);
      assertTrue(fromOwnBinding, "only a call of mcav's binding is sound");
      final String payload = parameters.substring(PREFIX.length(), parameters.indexOf('"', PREFIX.length()));
      final boolean base64Only = payload.chars().allMatch(PageAudioFuzzTest::isBase64);
      assertTrue(base64Only, "the payload is Base64 and nothing else");
      final boolean longEnough = PageAudio.leastBytes(parameters) <= chunk.samples().length;
      assertTrue(longEnough, "sound is never refused for its length");
    }
  }

  /**
   * The input is the payload of a call of the binding.
   *
   * @param input the payload
   */
  @FuzzTest(maxDuration = "30s")
  void shouldTakeOnlyWholeFramesOfAnyPayload(final byte[] input) {
    final String payload = new String(input, StandardCharsets.ISO_8859_1);
    final String parameters = PREFIX + payload + "\",\"executionContextId\":1}";
    final PageAudio.Chunk chunk = PageAudio.parse(PageAudio.BINDING_EVENT, parameters);
    if (chunk != null) {
      final boolean longEnough = PageAudio.leastBytes(parameters) <= chunk.samples().length;
      assertTrue(longEnough, "sound is never refused for its length");
      check(chunk.samples());
      final boolean contextMatches = chunk.context() == 1;
      assertTrue(contextMatches, "the context is the one of the call");
      final boolean base64Only = payload.chars().allMatch(PageAudioFuzzTest::isBase64);
      assertTrue(base64Only, "the payload is Base64 and nothing else");
      final byte[] decoded = Base64.getDecoder().decode(payload);
      final boolean samplesArePayload = Arrays.equals(chunk.samples(), decoded);
      assertTrue(samplesArePayload, "the samples are the payload");
    }
  }

  private static void check(final byte[] samples) {
    final boolean notEmpty = samples.length > 0;
    assertTrue(notEmpty, "sound is not empty");
    final boolean withinMessageLimit = samples.length <= HelperProtocol.MAXIMUM_AUDIO_BYTES;
    assertTrue(withinMessageLimit, "sound is within the limit of a message");
    final boolean wholeFrames = samples.length % HelperProtocol.AUDIO_FRAME_BYTES == 0;
    assertTrue(wholeFrames, "sound holds whole frames");
  }

  private static boolean isBase64(final int character) {
    final boolean isLetter = (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    final boolean isDigit = character >= '0' && character <= '9';
    return isLetter || isDigit || character == '+' || character == '/' || character == '=';
  }
}
