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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class ShaderDecoderTest {

  private static final PackLayout LAYOUT = PackLayout.parse(PackLayoutTest.packConstants("7u", "2", "0", 1)).orElseThrow();

  private static final byte[] PAGE = MapFixtures.colours(MapFixtures.pageSymbols(7, 1));

  private static final byte[] ANCHOR = MapFixtures.colours(MapFixtures.anchorSymbols(0, 0, 4, 2, 0, 7));

  private static final float[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

  /** The projection of {@link #projection}: a translation by (0.5, 0.25, -1). */
  private static final float[] PROJECTION = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0.5F, 0.25F, -1F, 1 };

  /** Two slots of five rows at 854 pixels, then the descriptor row. */
  private static final int ROWS = 11;

  private final AtomicLong clock = new AtomicLong();

  private final AtomicBoolean shaderPack = new AtomicBoolean(true);

  private final AtomicBoolean shadowPass = new AtomicBoolean();

  private final AtomicInteger layoutReads = new AtomicInteger();

  private final AtomicReference<Optional<String>> packConstants = new AtomicReference<>(
    Optional.of(PackLayoutTest.packConstants("7u", "2", "0", 1))
  );

  private final StripOutput output = mock(StripOutput.class);

  private final GraphicsResourceAllocator allocator = mock(GraphicsResourceAllocator.class);

  private final CameraRenderState camera = new CameraRenderState();

  private ShaderDecoder decoder(final boolean testedIris, final int width, final int height) {
    when(this.output.width()).thenReturn(width);
    when(this.output.height()).thenReturn(height);
    return new ShaderDecoder(
      testedIris,
      this.shaderPack::get,
      this.shadowPass::get,
      () -> {
        this.layoutReads.incrementAndGet();
        return this.packConstants.get();
      },
      this.clock::get,
      GameMatrices.find().orElseThrow(),
      this.output
    );
  }

  /** The projection the game renders with: a JOML matrix, which only reflection names. */
  private static Object projection() throws ReflectiveOperationException {
    final PoseStack stack = new PoseStack();
    stack.translate(0.5F, 0.25F, -1F);
    return PoseStack.Pose.class.getMethod("pose").invoke(stack.last());
  }

  /** One frame of a page and an anchor, the anchor drawn at (1, 2, 3) from the camera. */
  private static void frame(final ShaderDecoder decoder) throws ReflectiveOperationException {
    decoder.extracted("page", PAGE);
    decoder.extracted("anchor", ANCHOR);
    final PoseStack stack = new PoseStack();
    stack.translate(1F, 2F, 3F);
    decoder.posed("anchor", stack.last());
    decoder.projecting();
    decoder.projected(projection());
  }

  @Test
  void decodesTheFrameOnceTheShaderPacksFinalImageIsDone() throws ReflectiveOperationException {
    final ShaderDecoder decoder = this.decoder(true, 854, 480);
    frame(decoder);
    decoder.rendered(this.allocator, this.camera);
    final ArgumentCaptor<byte[]> strip = ArgumentCaptor.forClass(byte[].class);
    verify(this.output).decode(strip.capture(), eq(ROWS), same(this.allocator));
    final StripAnchor anchor = StripAnchor.of(ANCHOR, 1F, 2F, -0.01F + 3F);
    assertArrayEquals(TransportStrip.build(LAYOUT, 854, List.of(PAGE), List.of(anchor), IDENTITY, PROJECTION), strip.getValue());
    assertTrue(decoder.decodesUnderShaders());
  }

  @Test
  void onlyTheProjectionTheGameRendersTheLevelWithIsTheFrames() throws ReflectiveOperationException {
    final ShaderDecoder decoder = this.decoder(true, 854, 480);
    decoder.extracted("page", PAGE);
    decoder.extracted("anchor", ANCHOR);
    final PoseStack stack = new PoseStack();
    stack.translate(1F, 2F, 3F);
    decoder.posed("anchor", stack.last());
    final PoseStack other = new PoseStack();
    other.translate(9F, 9F, 9F);
    final Object elsewhere = PoseStack.Pose.class.getMethod("pose").invoke(other.last());
    decoder.projected(elsewhere);
    decoder.projecting();
    decoder.projected(projection());
    decoder.projected(elsewhere);
    decoder.rendered(this.allocator, this.camera);
    final ArgumentCaptor<byte[]> strip = ArgumentCaptor.forClass(byte[].class);
    verify(this.output).decode(strip.capture(), eq(ROWS), same(this.allocator));
    final StripAnchor anchor = StripAnchor.of(ANCHOR, 1F, 2F, -0.01F + 3F);
    assertArrayEquals(TransportStrip.build(LAYOUT, 854, List.of(PAGE), List.of(anchor), IDENTITY, PROJECTION), strip.getValue());
    final ShaderDecoder unmarked = this.decoder(true, 854, 480);
    unmarked.extracted("page", PAGE);
    unmarked.extracted("anchor", ANCHOR);
    unmarked.posed("anchor", stack.last());
    unmarked.projected(projection());
    unmarked.rendered(this.allocator, this.camera);
    verify(this.output).decode(any(), anyInt(), any());
    assertFalse(unmarked.decodesUnderShaders(), "the level's projection never came");
  }

  @Test
  void theMapsOfIrissShadowPassAreNotWhereTheScreensAre() throws ReflectiveOperationException {
    final ShaderDecoder decoder = this.decoder(true, 854, 480);
    decoder.extracted("page", PAGE);
    decoder.extracted("anchor", ANCHOR);
    this.shadowPass.set(true);
    final PoseStack sun = new PoseStack();
    sun.translate(50F, 60F, 70F);
    decoder.posed("anchor", sun.last());
    this.shadowPass.set(false);
    final PoseStack stack = new PoseStack();
    stack.translate(1F, 2F, 3F);
    decoder.posed("anchor", stack.last());
    decoder.projecting();
    decoder.projected(projection());
    decoder.rendered(this.allocator, this.camera);
    final ArgumentCaptor<byte[]> strip = ArgumentCaptor.forClass(byte[].class);
    verify(this.output).decode(strip.capture(), eq(ROWS), same(this.allocator));
    final StripAnchor anchor = StripAnchor.of(ANCHOR, 1F, 2F, -0.01F + 3F);
    assertArrayEquals(TransportStrip.build(LAYOUT, 854, List.of(PAGE), List.of(anchor), IDENTITY, PROJECTION), strip.getValue());
  }

  @Test
  void decodesUnderShadersOnlyWithTheTestedIrisOnceTheGameCalledEveryHook() throws ReflectiveOperationException {
    for (int missing = 0; missing < 4; missing++) {
      final ShaderDecoder decoder = this.decoder(true, 854, 480);
      if (missing != 0) {
        decoder.extracted("picture", new byte[Mcv2Maps.COLOURS]);
      }
      if (missing != 1) {
        decoder.posed("picture", new PoseStack().last());
      }
      if (missing != 2) {
        decoder.projecting();
        decoder.projected(projection());
      }
      if (missing != 3) {
        decoder.rendered(this.allocator, this.camera);
      }
      assertFalse(decoder.decodesUnderShaders(), "hook " + missing + " never ran");
    }
    final ShaderDecoder untested = this.decoder(false, 854, 480);
    frame(untested);
    untested.rendered(this.allocator, this.camera);
    assertFalse(untested.decodesUnderShaders());
    verify(this.output, never()).decode(any(), anyInt(), any());
  }

  @Test
  void leavesAFrameWithoutProjectionMapsOrShaderPackAsIrisDrewIt() throws ReflectiveOperationException {
    final ShaderDecoder decoder = this.decoder(true, 854, 480);
    decoder.extracted("page", PAGE);
    decoder.rendered(this.allocator, this.camera);
    decoder.projecting();
    decoder.projected(projection());
    decoder.rendered(this.allocator, this.camera);
    this.shaderPack.set(false);
    frame(decoder);
    decoder.rendered(this.allocator, this.camera);
    this.shaderPack.set(true);
    decoder.projecting();
    decoder.projected(projection());
    decoder.rendered(this.allocator, this.camera);
    verify(this.output, never()).decode(any(), anyInt(), any());
    assertTrue(decoder.decodesUnderShaders());
  }

  @Test
  void needsTheLayoutOfAnMcv2Pack() throws ReflectiveOperationException {
    this.packConstants.set(Optional.empty());
    final ShaderDecoder decoder = this.decoder(true, 854, 480);
    frame(decoder);
    decoder.rendered(this.allocator, this.camera);
    this.packConstants.set(Optional.of("// a pack without MCV2 screens"));
    this.clock.set(1_000_000_000L);
    frame(decoder);
    decoder.rendered(this.allocator, this.camera);
    verify(this.output, never()).decode(any(), anyInt(), any());
  }

  @Test
  void needsAWindowWideEnoughForADescriptorAndHighEnoughForTheStrip() throws ReflectiveOperationException {
    for (final int[] size : List.of(new int[] { 63, 4096 }, new int[] { 854, ROWS - 1 }, new int[] { 854, ROWS })) {
      final ShaderDecoder decoder = this.decoder(true, size[0], size[1]);
      frame(decoder);
      decoder.rendered(this.allocator, this.camera);
    }
    verify(this.output, never()).decode(any(), anyInt(), any());
    final ShaderDecoder fits = this.decoder(true, 854, ROWS + 1);
    frame(fits);
    fits.rendered(this.allocator, this.camera);
    verify(this.output).decode(any(), eq(ROWS), same(this.allocator));
    final ShaderDecoder narrow = this.decoder(true, 64, 4096);
    frame(narrow);
    narrow.rendered(this.allocator, this.camera);
    verify(this.output).decode(any(), eq(PackLayout.rowsPerPage(64) * 2 + 1), same(this.allocator));
  }

  @Test
  void readsThePacksLayoutAgainEverySecond() throws ReflectiveOperationException {
    final ShaderDecoder decoder = this.decoder(true, 854, 480);
    for (final long now : new long[] { 0, 999_999_999L, 1_000_000_000L, 1_999_999_999L, 2_000_000_000L }) {
      this.clock.set(now);
      frame(decoder);
      decoder.rendered(this.allocator, this.camera);
    }
    assertEquals(3, this.layoutReads.get());
    verify(this.output, times(5)).decode(any(), eq(ROWS), same(this.allocator));
  }

  @Test
  void theDecoderTheMixinsReportToIsTheOneInstalled() {
    final ShaderDecoder decoder = this.decoder(true, 854, 480);
    ShaderDecoder.install(Optional.of(decoder));
    assertEquals(Optional.of(decoder), ShaderDecoder.current());
    ShaderDecoder.install(Optional.empty());
    assertEquals(Optional.empty(), ShaderDecoder.current());
  }
}
