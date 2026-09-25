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
  void takesOnlyWholeFramesOfItsOwnBinding(final byte[] input) {
    if (input.length == 0) {
      return;
    }
    final String method = (input[0] & 1) == 1 ? PageAudio.BINDING_EVENT : "Runtime.consoleAPICalled";
    final String parameters = new String(input, 1, input.length - 1, StandardCharsets.UTF_8);
    final PageAudio.Chunk chunk = PageAudio.parse(method, parameters);
    if (chunk != null) {
      check(chunk.samples());
      assertTrue(PageAudio.BINDING_EVENT.equals(method), "only a call of a binding is sound");
      assertTrue(parameters.startsWith(PREFIX), "only a call of mcav's binding is sound");
      final String payload = parameters.substring(PREFIX.length(), parameters.indexOf('"', PREFIX.length()));
      assertTrue(payload.chars().allMatch(PageAudioFuzzTest::isBase64), "the payload is Base64 and nothing else");
    }
  }

  /**
   * The input is the payload of a call of the binding.
   *
   * @param input the payload
   */
  @FuzzTest(maxDuration = "30s")
  void takesOnlyWholeFramesOfAnyPayload(final byte[] input) {
    final String payload = new String(input, StandardCharsets.ISO_8859_1);
    final PageAudio.Chunk chunk = PageAudio.parse(PageAudio.BINDING_EVENT, PREFIX + payload + "\",\"executionContextId\":1}");
    if (chunk != null) {
      check(chunk.samples());
      assertTrue(chunk.context() == 1, "the context is the one of the call");
      assertTrue(payload.chars().allMatch(PageAudioFuzzTest::isBase64), "the payload is Base64 and nothing else");
      final byte[] decoded = java.util.Base64.getDecoder().decode(payload);
      assertTrue(Arrays.equals(chunk.samples(), decoded), "the samples are the payload");
    }
  }

  private static void check(final byte[] samples) {
    assertTrue(samples.length > 0, "sound is not empty");
    assertTrue(samples.length <= HelperProtocol.MAX_AUDIO_BYTES, "sound is within the limit of a message");
    assertTrue(samples.length % HelperProtocol.AUDIO_FRAME_BYTES == 0, "sound holds whole frames");
  }

  private static boolean isBase64(final int character) {
    final boolean letter = (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    final boolean digit = character >= '0' && character <= '9';
    return letter || digit || character == '+' || character == '/' || character == '=';
  }
}
