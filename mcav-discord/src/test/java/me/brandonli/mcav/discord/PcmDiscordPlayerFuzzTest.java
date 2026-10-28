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
package me.brandonli.mcav.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.managers.Presence;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

// Fuzzes the samples and titles a player gets from outside the process. Whatever the chunks, JDA takes the samples
// byte-swapped and in order, with at most 150 frames waiting; whatever the title, JDA accepts the activity name and
// shows it trimmed, or cut at a character boundary. The seeds are a few typical inputs and the titles that broke it.
@Tag("fuzz")
final class PcmDiscordPlayerFuzzTest {

  private static final int FRAME_BYTES = 3840;
  private static final int FRAME_MILLISECONDS = 20;
  private static final int MAXIMUM_FRAMES = 150;

  private static final int MAXIMUM_CHUNK_BYTES = 8 * FRAME_BYTES;
  private static final int MAXIMUM_SKIPPED_BYTES = 8;

  private static final List<Operation> OPERATIONS = List.of(Operation.values());
  private static final List<ByteOrder> ORDERS = List.of(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN);

  private static final OriginalAudioMetadata METADATA = OriginalAudioMetadata.of(
    "pcm_s16le",
    OriginalAudioMetadata.UNKNOWN,
    AudioFilter.SAMPLE_RATE,
    AudioFilter.CHANNELS,
    OriginalAudioMetadata.UNKNOWN
  );

  private static final int MAXIMUM_NAME_LENGTH = 128;
  private static final String UNKNOWN_TITLE = "Unknown title";
  private static final String ELLIPSIS = "…";
  private static final char LAST_CONTROL_CHARACTER = '\u001F';

  @FuzzTest(maxDuration = "30s")
  void shouldSendTheSwappedSamplesInOrderWhateverTheChunks(final FuzzedDataProvider data) {
    final JDA jda = Mockito.mock(JDA.class);
    final PcmDiscordPlayer player = new PcmDiscordPlayer(jda);
    final ExpectedFrames expected = new ExpectedFrames();
    while (data.remainingBytes() > 0) {
      final Operation operation = data.pickValue(OPERATIONS);
      switch (operation) {
        case FILTER -> this.filterChunk(data, player, expected);
        case PROVIDE -> this.provideFrame(player, expected);
        case FLUSH -> this.flush(player, expected);
      }
      this.assertQueued(player, expected);
    }
  }

  private void filterChunk(final FuzzedDataProvider data, final PcmDiscordPlayer player, final ExpectedFrames expected) {
    final byte[] chunk = this.consumeChunk(data);
    final ByteBuffer samples = this.wrapChunk(data, chunk);
    final int position = samples.position();
    final ByteOrder order = samples.order();

    final boolean changed = player.applyFilter(samples, METADATA);
    final int positionAfter = samples.position();
    final ByteOrder orderAfter = samples.order();
    assertFalse(changed);
    assertEquals(position, positionAfter);
    assertEquals(order, orderAfter);

    final byte[] swapped = this.swapBytePairs(chunk);
    expected.add(swapped);
  }

  private byte[] consumeChunk(final FuzzedDataProvider data) {
    final int length = data.consumeInt(0, MAXIMUM_CHUNK_BYTES);
    final long seed = data.consumeLong();
    final SplittableRandom random = new SplittableRandom(seed);
    final byte[] chunk = new byte[length];
    random.nextBytes(chunk);
    return chunk;
  }

  private ByteBuffer wrapChunk(final FuzzedDataProvider data, final byte[] chunk) {
    final int skipped = data.consumeInt(0, MAXIMUM_SKIPPED_BYTES);
    final ByteOrder order = data.pickValue(ORDERS);
    final byte[] array = new byte[skipped + chunk.length];
    System.arraycopy(chunk, 0, array, skipped, chunk.length);
    final ByteBuffer buffer = ByteBuffer.wrap(array, skipped, chunk.length);
    return buffer.order(order);
  }

  private byte[] swapBytePairs(final byte[] samples) {
    final byte[] swapped = samples.clone();
    for (int offset = 0; offset + 1 < samples.length; offset += Short.BYTES) {
      swapped[offset] = samples[offset + 1];
      swapped[offset + 1] = samples[offset];
    }

    return swapped;
  }

  private void provideFrame(final PcmDiscordPlayer player, final ExpectedFrames expected) {
    final ByteBuffer frame = player.provide20MsAudio();
    if (expected.size() == 0) {
      assertNull(frame);
      return;
    }

    final ByteBuffer expectedFrame = expected.takeFrame();
    assertEquals(expectedFrame, frame);
  }

  private void flush(final PcmDiscordPlayer player, final ExpectedFrames expected) {
    player.flush();
    expected.clear();
  }

  private void assertQueued(final PcmDiscordPlayer player, final ExpectedFrames expected) {
    final int frames = expected.size();
    final boolean queued = frames > 0;
    final long expectedMilliseconds = (long) frames * FRAME_MILLISECONDS;
    final boolean providable = player.canProvide();
    final long queuedMilliseconds = player.getQueuedMilliseconds();
    assertEquals(queued, providable);
    assertEquals(expectedMilliseconds, queuedMilliseconds);
  }

  @FuzzTest(maxDuration = "30s")
  void shouldShowEveryTitleAsAnActivityNameThatDiscordAccepts(final FuzzedDataProvider data) {
    final String title = this.consumeTitle(data);
    final JDA jda = Mockito.mock(JDA.class);
    final Presence presence = Mockito.mock(Presence.class);
    Mockito.when(jda.getPresence()).thenReturn(presence);
    final PcmDiscordPlayer player = new PcmDiscordPlayer(jda);

    player.setPlaying(title);
    final String name = this.shownActivityName(presence);
    final int length = name.length();
    final boolean fits = length <= MAXIMUM_NAME_LENGTH;
    assertTrue(fits);
    this.assertShownAs(title, name);
  }

  private String consumeTitle(final FuzzedDataProvider data) {
    final StringBuilder title = new StringBuilder();
    while (data.remainingBytes() > 0) {
      final char character = data.consumeChar();
      title.append(character);
    }

    return title.toString();
  }

  private String shownActivityName(final Presence presence) {
    final ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
    final Presence verified = Mockito.verify(presence);
    final Activity captured = captor.capture();
    verified.setActivity(captured);
    final Activity activity = captor.getValue();
    return activity.getName();
  }

  private void assertShownAs(final String title, final String name) {
    final String visible = this.removeSurroundingInvisibles(title);
    final int visibleLength = visible.length();
    if (visible.isEmpty()) {
      assertEquals(UNKNOWN_TITLE, name);
      return;
    }

    if (visibleLength <= MAXIMUM_NAME_LENGTH) {
      assertEquals(visible, name);
      return;
    }

    this.assertCutAtACharacterBoundary(visible, name);
  }

  private String removeSurroundingInvisibles(final String title) {
    int start = 0;
    int end = title.length();
    while (start < end && this.isInvisible(title.charAt(start))) {
      start++;
    }

    while (end > start && this.isInvisible(title.charAt(end - 1))) {
      end--;
    }

    return title.substring(start, end);
  }

  private boolean isInvisible(final char character) {
    return Character.isWhitespace(character) || character <= LAST_CONTROL_CHARACTER;
  }

  private void assertCutAtACharacterBoundary(final String visible, final String name) {
    final int cut = name.length() - ELLIPSIS.length();
    final String kept = visible.substring(0, cut);
    final char last = visible.charAt(cut - 1);
    final char next = visible.charAt(cut);
    final boolean splitsCharacter = Character.isSurrogatePair(last, next);
    final int shortestCut = MAXIMUM_NAME_LENGTH - ELLIPSIS.length() - 1;
    final boolean keepsEnough = cut >= shortestCut;
    assertEquals(kept + ELLIPSIS, name);
    assertFalse(splitsCharacter);
    assertTrue(keepsEnough);
  }

  private enum Operation {
    FILTER,
    PROVIDE,
    FLUSH,
  }

  private static final class ExpectedFrames {

    private final Deque<ByteBuffer> frames;
    private final ByteArrayOutputStream partial;

    ExpectedFrames() {
      this.frames = new ArrayDeque<>();
      this.partial = new ByteArrayOutputStream();
    }

    void add(final byte[] samples) {
      this.partial.writeBytes(samples);
      final byte[] pending = this.partial.toByteArray();
      final int frameCount = pending.length / FRAME_BYTES;
      final int queuedBytes = frameCount * FRAME_BYTES;
      for (int start = 0; start < queuedBytes; start += FRAME_BYTES) {
        final byte[] frame = Arrays.copyOfRange(pending, start, start + FRAME_BYTES);
        this.queue(frame);
      }

      this.partial.reset();
      this.partial.write(pending, queuedBytes, pending.length - queuedBytes);
    }

    private void queue(final byte[] frame) {
      final ByteBuffer wrapped = ByteBuffer.wrap(frame);
      if (this.frames.size() == MAXIMUM_FRAMES) {
        this.frames.removeFirst();
      }
      this.frames.addLast(wrapped);
    }

    ByteBuffer takeFrame() {
      return this.frames.removeFirst();
    }

    void clear() {
      this.frames.clear();
      this.partial.reset();
    }

    int size() {
      return this.frames.size();
    }
  }
}
