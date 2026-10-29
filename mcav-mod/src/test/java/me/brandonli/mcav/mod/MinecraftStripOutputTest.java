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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;

final class MinecraftStripOutputTest {

  private static final int WIDTH = 854;
  private static final int HEIGHT = 480;

  private static final int ROWS = 3;

  private final GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);

  private final RenderTarget main = mock(RenderTarget.class);

  private final GpuTexture mainColour = mock(GpuTexture.class);

  private final CommandEncoder commands = mock(CommandEncoder.class);

  private final GraphicsResourceAllocator allocator = mock(GraphicsResourceAllocator.class);

  // in the order the device made them
  private final List<GpuTexture> textures = new ArrayList<>();

  private MockedStatic<RenderSystem> system;

  @BeforeEach
  void device() {
    when(this.device.getDeviceInfo().limits().maxTextureSize()).thenReturn(16_384);
    when(
      this.device.createTexture(ArgumentMatchers.<Supplier<String>>any(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt())
    ).thenAnswer(_ -> {
      final GpuTexture texture = mock(GpuTexture.class);
      this.textures.add(texture);
      return texture;
    });
    this.system = mockStatic(RenderSystem.class);
    this.system.when(RenderSystem::getDevice).thenReturn(this.device);
    this.main.width = WIDTH;
    this.main.height = HEIGHT;
    when(this.main.getColorTexture()).thenReturn(this.mainColour);
  }

  @AfterEach
  void close() {
    this.system.close();
  }

  // each row of one value, so the order of the rows shows
  private byte[] strip(final int width) {
    final byte[] strip = new byte[ROWS * width * TransportStrip.CHANNELS];
    for (int row = 0; row < ROWS; row++) {
      for (int at = row * width * TransportStrip.CHANNELS; at < (row + 1) * width * TransportStrip.CHANNELS; at++) {
        strip[at] = (byte) (row + 1);
      }
    }
    return strip;
  }

  @Test
  void shouldWriteTheStripOverTheTopRowsAndRunThePacksChainOnAnEmptyOutline() {
    final PostChain chain = mock(PostChain.class);
    final MinecraftStripOutput output = new MinecraftStripOutput(() -> this.main, () -> chain, () -> this.commands);
    final int width = output.width();
    final int height = output.height();
    assertEquals(WIDTH, width);
    assertEquals(HEIGHT, height);
    final ArgumentCaptor<ByteBuffer> pixels = ArgumentCaptor.forClass(ByteBuffer.class);
    final boolean decoded = output.decode(this.strip(WIDTH), ROWS, this.allocator);
    assertTrue(decoded);
    verify(this.commands).writeToTexture(
      same(this.mainColour),
      pixels.capture(),
      eq(0),
      eq(0),
      eq(0),
      eq(HEIGHT - ROWS),
      eq(WIDTH),
      eq(ROWS)
    );
    final ByteBuffer written = pixels.getValue();
    final int remaining = written.remaining();
    final byte firstByte = written.get(0);
    final byte lastByteOfTheFirstRow = written.get(WIDTH * TransportStrip.CHANNELS - 1);
    final byte firstByteOfTheSecondRow = written.get(WIDTH * TransportStrip.CHANNELS);
    final byte lastByte = written.get(ROWS * WIDTH * TransportStrip.CHANNELS - 1);
    assertEquals(ROWS * WIDTH * TransportStrip.CHANNELS, remaining);
    assertEquals(3, firstByte);
    assertEquals(3, lastByteOfTheFirstRow);
    assertEquals(2, firstByteOfTheSecondRow);
    assertEquals(1, lastByte);
    final ArgumentCaptor<LevelTargetBundle> targets = ArgumentCaptor.forClass(LevelTargetBundle.class);
    verify(chain).addToFrame(any(FrameGraphBuilder.class), eq(WIDTH), eq(HEIGHT), targets.capture());
    final RenderTarget main = targets.getValue().main.get();
    assertSame(this.main, main);
    final TextureTarget outline = (TextureTarget) targets.getValue().entityOutline.get();
    final int outlineWidth = outline.width;
    final int outlineHeight = outline.height;
    assertEquals(WIDTH, outlineWidth);
    assertEquals(HEIGHT, outlineHeight);
    final ArgumentCaptor<ByteBuffer> zeros = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(this.commands).writeToTexture(any(GpuTexture.class), zeros.capture(), eq(0), eq(0), eq(0), eq(0), eq(WIDTH), eq(HEIGHT));
    final int zerosRemaining = zeros.getValue().remaining();
    assertEquals(WIDTH * HEIGHT * TransportStrip.CHANNELS, zerosRemaining);
    for (int at = 0; at < zeros.getValue().limit(); at++) {
      final byte zero = zeros.getValue().get(at);
      assertEquals(0, zero);
    }
    verify(this.device.createCommandEncoder()).copyTextureToTexture(
      any(),
      same(outline.getColorTexture()),
      eq(0),
      eq(0),
      eq(0),
      eq(0),
      eq(0),
      eq(WIDTH),
      eq(HEIGHT)
    );
  }

  @Test
  void shouldKeepItsTargetsWhileTheScreenKeepsItsSize() {
    final PostChain chain = mock(PostChain.class);
    final MinecraftStripOutput output = new MinecraftStripOutput(() -> this.main, () -> chain, () -> this.commands);
    output.decode(this.strip(WIDTH), ROWS, this.allocator);
    output.decode(this.strip(WIDTH), ROWS, this.allocator);
    verify(this.device, times(2)).createTexture(
      ArgumentMatchers.<Supplier<String>>any(),
      anyInt(),
      any(),
      anyInt(),
      anyInt(),
      anyInt(),
      anyInt()
    );
    final ArgumentCaptor<LevelTargetBundle> targets = ArgumentCaptor.forClass(LevelTargetBundle.class);
    verify(chain, times(2)).addToFrame(any(FrameGraphBuilder.class), eq(WIDTH), eq(HEIGHT), targets.capture());
    final TextureTarget first = (TextureTarget) targets.getAllValues().get(0).entityOutline.get();
    final RenderTarget second = targets.getAllValues().get(1).entityOutline.get();
    assertSame(first, second);
    final GpuTexture firstColour = first.getColorTexture();
    this.main.height = 720;
    output.decode(this.strip(WIDTH), ROWS, this.allocator);
    this.main.width = 1280;
    output.decode(this.strip(1280), ROWS, this.allocator);
    verify(this.device, times(6)).createTexture(
      ArgumentMatchers.<Supplier<String>>any(),
      anyInt(),
      any(),
      anyInt(),
      anyInt(),
      anyInt(),
      anyInt()
    );
    verify(chain).addToFrame(any(FrameGraphBuilder.class), eq(WIDTH), eq(720), targets.capture());
    verify(chain).addToFrame(any(FrameGraphBuilder.class), eq(1280), eq(720), targets.capture());
    final TextureTarget resized = (TextureTarget) targets.getValue().entityOutline.get();
    final int resizedWidth = resized.width;
    final int resizedHeight = resized.height;
    assertNotSame(first, resized);
    assertEquals(1280, resizedWidth);
    assertEquals(720, resizedHeight);
    verify(firstColour).close();
    for (int index = 0; index < 4; index++) {
      verify(this.textures.get(index)).close();
    }
    verify(this.textures.get(4), never()).close();
    verify(this.textures.get(5), never()).close();
    verify(this.mainColour, never()).close();
  }

  @Test
  void shouldRunTheFrameOfThePacksChainAndKeepItsBufferForAStripOfTheSameSize() {
    final PostChain chain = mock(PostChain.class);
    final AtomicInteger passes = new AtomicInteger();
    doAnswer(invocation -> {
      final FramePass pass = invocation.<FrameGraphBuilder>getArgument(0).addPass("mcv2 test");
      pass.disableCulling();
      pass.executes(passes::incrementAndGet);
      return null;
    })
      .when(chain)
      .addToFrame(any(FrameGraphBuilder.class), anyInt(), anyInt(), any());
    final MinecraftStripOutput output = new MinecraftStripOutput(() -> this.main, () -> chain, () -> this.commands);
    output.decode(this.strip(WIDTH), ROWS, this.allocator);
    output.decode(this.strip(WIDTH), ROWS, this.allocator);
    final int passCount = passes.get();
    assertEquals(2, passCount, "the chain's passes run every frame");
    final ArgumentCaptor<ByteBuffer> pixels = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(this.commands, times(2)).writeToTexture(
      same(this.mainColour),
      pixels.capture(),
      eq(0),
      eq(0),
      eq(0),
      eq(HEIGHT - ROWS),
      eq(WIDTH),
      eq(ROWS)
    );
    final ByteBuffer reusedPixels = pixels.getAllValues().get(1);
    assertSame(pixels.getAllValues().get(0), reusedPixels);
  }

  @Test
  void shouldGrowItsBufferForALargerStrip() {
    final PostChain chain = mock(PostChain.class);
    final MinecraftStripOutput output = new MinecraftStripOutput(() -> this.main, () -> chain, () -> this.commands);
    output.decode(this.strip(WIDTH), ROWS, this.allocator);
    final byte[] tall = new byte[10 * WIDTH * TransportStrip.CHANNELS];
    tall[0] = 9;
    final ArgumentCaptor<ByteBuffer> pixels = ArgumentCaptor.forClass(ByteBuffer.class);
    output.decode(tall, 10, this.allocator);
    verify(this.commands).writeToTexture(same(this.mainColour), pixels.capture(), eq(0), eq(0), eq(0), eq(470), eq(WIDTH), eq(10));
    final int tallRemaining = pixels.getValue().remaining();
    final byte flippedFirstByte = pixels.getValue().get(9 * WIDTH * TransportStrip.CHANNELS);
    assertEquals(10 * WIDTH * TransportStrip.CHANNELS, tallRemaining);
    assertEquals(9, flippedFirstByte);
    output.decode(this.strip(WIDTH), ROWS, this.allocator);
    verify(this.commands, times(2)).writeToTexture(
      same(this.mainColour),
      pixels.capture(),
      eq(0),
      eq(0),
      eq(0),
      eq(HEIGHT - ROWS),
      eq(WIDTH),
      eq(ROWS)
    );
    final int shortRemaining = pixels.getValue().remaining();
    assertEquals(ROWS * WIDTH * TransportStrip.CHANNELS, shortRemaining);
  }

  @Test
  void shouldWriteNothingWhileThePackHasNoChain() {
    final MinecraftStripOutput output = new MinecraftStripOutput(() -> this.main, () -> null, () -> this.commands);
    final boolean decoded = output.decode(this.strip(WIDTH), ROWS, this.allocator);
    assertFalse(decoded);
    verifyNoInteractions(this.commands);
  }
}
