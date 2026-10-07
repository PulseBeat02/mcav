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
package me.brandonli.mcav.client;

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

  private final GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);

  private final RenderTarget main = mock(RenderTarget.class);

  private final GpuTexture mainColour = mock(GpuTexture.class);

  private final CommandEncoder commands = mock(CommandEncoder.class);

  private final GraphicsResourceAllocator allocator = mock(GraphicsResourceAllocator.class);

  /** Every texture the device made, in order. */
  private final List<GpuTexture> textures = new ArrayList<>();

  private MockedStatic<RenderSystem> system;

  @BeforeEach
  void device() {
    // render targets made on a device that only pretends: every texture a mock
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
    this.main.width = 854;
    this.main.height = 480;
    when(this.main.getColorTexture()).thenReturn(this.mainColour);
  }

  @AfterEach
  void close() {
    this.system.close();
  }

  /** A strip of three rows, each of one value, so the order of its rows shows. */
  private static byte[] strip(final int width) {
    final byte[] strip = new byte[3 * width * 4];
    for (int row = 0; row < 3; row++) {
      for (int at = row * width * 4; at < (row + 1) * width * 4; at++) {
        strip[at] = (byte) (row + 1);
      }
    }
    return strip;
  }

  @Test
  void writesTheStripOverTheTopRowsAndRunsThePacksChainOnAnEmptyOutline() {
    final PostChain chain = mock(PostChain.class);
    final MinecraftStripOutput output = new MinecraftStripOutput(() -> this.main, () -> chain, () -> this.commands);
    assertEquals(854, output.width());
    assertEquals(480, output.height());
    final ArgumentCaptor<ByteBuffer> pixels = ArgumentCaptor.forClass(ByteBuffer.class);
    assertTrue(output.decode(strip(854), 3, this.allocator));
    verify(this.commands).writeToTexture(same(this.mainColour), pixels.capture(), eq(0), eq(0), eq(0), eq(477), eq(854), eq(3));
    // texture rows count from the bottom: the strip's last row comes first
    final ByteBuffer written = pixels.getValue();
    assertEquals(3 * 854 * 4, written.remaining());
    assertEquals(3, written.get(0));
    assertEquals(3, written.get(854 * 4 - 1));
    assertEquals(2, written.get(854 * 4));
    assertEquals(1, written.get(3 * 854 * 4 - 1));
    final ArgumentCaptor<LevelTargetBundle> targets = ArgumentCaptor.forClass(LevelTargetBundle.class);
    verify(chain).addToFrame(any(FrameGraphBuilder.class), eq(854), eq(480), targets.capture());
    assertSame(this.main, targets.getValue().main.get());
    final TextureTarget outline = (TextureTarget) targets.getValue().entityOutline.get();
    assertEquals(854, outline.width);
    assertEquals(480, outline.height);
    // the transparent target, written once as zeros, is copied over the outline the chain runs on
    final ArgumentCaptor<ByteBuffer> zeros = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(this.commands).writeToTexture(any(GpuTexture.class), zeros.capture(), eq(0), eq(0), eq(0), eq(0), eq(854), eq(480));
    assertEquals(854 * 480 * 4, zeros.getValue().remaining());
    for (int at = 0; at < zeros.getValue().limit(); at++) {
      assertEquals(0, zeros.getValue().get(at));
    }
    verify(this.device.createCommandEncoder()).copyTextureToTexture(
      any(),
      same(outline.getColorTexture()),
      eq(0),
      eq(0),
      eq(0),
      eq(0),
      eq(0),
      eq(854),
      eq(480)
    );
  }

  @Test
  void keepsItsTargetsWhileTheScreenKeepsItsSize() {
    final PostChain chain = mock(PostChain.class);
    final MinecraftStripOutput output = new MinecraftStripOutput(() -> this.main, () -> chain, () -> this.commands);
    output.decode(strip(854), 3, this.allocator);
    output.decode(strip(854), 3, this.allocator);
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
    verify(chain, times(2)).addToFrame(any(FrameGraphBuilder.class), eq(854), eq(480), targets.capture());
    final TextureTarget first = (TextureTarget) targets.getAllValues().get(0).entityOutline.get();
    assertSame(first, targets.getAllValues().get(1).entityOutline.get());
    final GpuTexture firstColour = first.getColorTexture();
    // a new height, then a new width: new targets each time, the old ones destroyed
    this.main.height = 720;
    output.decode(strip(854), 3, this.allocator);
    this.main.width = 1280;
    output.decode(strip(1280), 3, this.allocator);
    verify(this.device, times(6)).createTexture(
      ArgumentMatchers.<Supplier<String>>any(),
      anyInt(),
      any(),
      anyInt(),
      anyInt(),
      anyInt(),
      anyInt()
    );
    verify(chain).addToFrame(any(FrameGraphBuilder.class), eq(854), eq(720), targets.capture());
    verify(chain).addToFrame(any(FrameGraphBuilder.class), eq(1280), eq(720), targets.capture());
    final TextureTarget resized = (TextureTarget) targets.getValue().entityOutline.get();
    assertNotSame(first, resized);
    assertEquals(1280, resized.width);
    assertEquals(720, resized.height);
    verify(firstColour).close();
    // the outline and the transparent target of each size before the last are destroyed, the last pair kept
    for (int index = 0; index < 4; index++) {
      verify(this.textures.get(index)).close();
    }
    verify(this.textures.get(4), never()).close();
    verify(this.textures.get(5), never()).close();
    verify(this.mainColour, never()).close();
  }

  @Test
  void runsTheFrameOfThePacksChainAndKeepsItsBufferForAStripOfTheSameSize() {
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
    output.decode(strip(854), 3, this.allocator);
    output.decode(strip(854), 3, this.allocator);
    assertEquals(2, passes.get(), "the chain's passes run every frame");
    final ArgumentCaptor<ByteBuffer> pixels = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(this.commands, times(2)).writeToTexture(same(this.mainColour), pixels.capture(), eq(0), eq(0), eq(0), eq(477), eq(854), eq(3));
    assertSame(pixels.getAllValues().get(0), pixels.getAllValues().get(1));
  }

  @Test
  void growsItsBufferForALargerStrip() {
    final PostChain chain = mock(PostChain.class);
    final MinecraftStripOutput output = new MinecraftStripOutput(() -> this.main, () -> chain, () -> this.commands);
    output.decode(strip(854), 3, this.allocator);
    final byte[] tall = new byte[10 * 854 * 4];
    tall[0] = 9;
    final ArgumentCaptor<ByteBuffer> pixels = ArgumentCaptor.forClass(ByteBuffer.class);
    output.decode(tall, 10, this.allocator);
    verify(this.commands).writeToTexture(same(this.mainColour), pixels.capture(), eq(0), eq(0), eq(0), eq(470), eq(854), eq(10));
    assertEquals(10 * 854 * 4, pixels.getValue().remaining());
    assertEquals(9, pixels.getValue().get(9 * 854 * 4));
    output.decode(strip(854), 3, this.allocator);
    verify(this.commands, times(2)).writeToTexture(same(this.mainColour), pixels.capture(), eq(0), eq(0), eq(0), eq(477), eq(854), eq(3));
    assertEquals(3 * 854 * 4, pixels.getValue().remaining());
  }

  @Test
  void writesNothingWhileThePackHasNoChain() {
    final MinecraftStripOutput output = new MinecraftStripOutput(() -> this.main, () -> null, () -> this.commands);
    assertFalse(output.decode(strip(854), 3, this.allocator));
    verifyNoInteractions(this.commands);
  }
}
