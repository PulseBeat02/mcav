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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

final class Mcv2ShadersTest {

  private static final Identifier TEXTURE = Identifier.withDefaultNamespace("map/1");

  @BeforeAll
  static void bootstrap() {
    // a map's data needs the game's registries
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @AfterEach
  void uninstall() {
    ShaderDecoder.install(Optional.empty());
  }

  @Test
  void handsEveryHookToTheDecoder() {
    final ShaderDecoder decoder = mock(ShaderDecoder.class);
    ShaderDecoder.install(Optional.of(decoder));
    final MapRenderState state = new MapRenderState();
    state.texture = TEXTURE;
    final MapItemSavedData data = MapItemSavedData.createForClient((byte) 0, false, Level.OVERWORLD);
    Mcv2Shaders.extracted(state, data);
    verify(decoder).extracted(TEXTURE, data.colors);
    final PoseStack pose = new PoseStack();
    Mcv2Shaders.posed(state, pose);
    verify(decoder).posed(TEXTURE, pose.last());
    final Object projection = new Object();
    Mcv2Shaders.projected(projection);
    verify(decoder).projected(projection);
    final GraphicsResourceAllocator allocator = mock(GraphicsResourceAllocator.class);
    final CameraRenderState camera = new CameraRenderState();
    Mcv2Shaders.rendered(allocator, camera);
    verify(decoder).rendered(allocator, camera);
  }

  @Test
  void aMapWithoutATextureAndAClientWithoutADecoderAreLeftAlone() {
    final ShaderDecoder decoder = mock(ShaderDecoder.class);
    ShaderDecoder.install(Optional.of(decoder));
    final MapRenderState state = new MapRenderState();
    final MapItemSavedData data = MapItemSavedData.createForClient((byte) 0, false, Level.OVERWORLD);
    Mcv2Shaders.extracted(state, data);
    Mcv2Shaders.posed(state, new PoseStack());
    verifyNoInteractions(decoder);
    ShaderDecoder.install(Optional.empty());
    state.texture = TEXTURE;
    Mcv2Shaders.extracted(state, data);
    Mcv2Shaders.posed(state, new PoseStack());
    Mcv2Shaders.projected(new Object());
    Mcv2Shaders.rendered(mock(GraphicsResourceAllocator.class), new CameraRenderState());
    verifyNoInteractions(decoder);
  }

  @Test
  void startsADecoderThatDecodesUnderShadersOnlyWithTheTestedIris() throws ReflectiveOperationException {
    assertTrue(hooked(Mcv2Shaders.start(Optional.of("1.11.7+mc26.3"))));
    assertFalse(hooked(Mcv2Shaders.start(Optional.of("1.11.8+mc26.3"))));
    assertFalse(hooked(Mcv2Shaders.start(Optional.empty())));
  }

  /** Runs every hook of the started decoder on a frame without MCV2 maps, and asks whether it decodes under shaders. */
  private static boolean hooked(final BooleanSupplier decodes) throws ReflectiveOperationException {
    assertFalse(decodes.getAsBoolean());
    final ShaderDecoder decoder = ShaderDecoder.current().orElseThrow();
    final PoseStack stack = new PoseStack();
    decoder.extracted(TEXTURE, new byte[Mcv2Maps.COLOURS]);
    decoder.posed(TEXTURE, stack.last());
    decoder.projected(PoseStack.Pose.class.getMethod("pose").invoke(stack.last()));
    decoder.rendered(mock(GraphicsResourceAllocator.class), new CameraRenderState());
    return decodes.getAsBoolean();
  }

  @Test
  void readsTheGamesTargetsChainAndDevice() throws ReflectiveOperationException {
    final Minecraft minecraft = mock(Minecraft.class);
    final GameRenderer gameRenderer = mock(GameRenderer.class);
    final RenderTarget main = mock(RenderTarget.class);
    when(gameRenderer.mainRenderTarget()).thenReturn(main);
    // a final field, which a mock leaves unset
    final Field field = Minecraft.class.getField("gameRenderer");
    field.setAccessible(true);
    field.set(minecraft, gameRenderer);
    final ShaderManager shaders = mock(ShaderManager.class);
    final PostChain chain = mock(PostChain.class);
    when(minecraft.getShaderManager()).thenReturn(shaders);
    when(shaders.getPostChain(Mcv2Shaders.OUTLINE_CHAIN, LevelTargetBundle.OUTLINE_TARGETS)).thenReturn(chain);
    final GpuDevice device = mock(GpuDevice.class);
    final CommandEncoder commands = mock(CommandEncoder.class);
    when(device.createCommandEncoder()).thenReturn(commands);
    try (
      final MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
      final MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class)
    ) {
      game.when(Minecraft::getInstance).thenReturn(minecraft);
      system.when(RenderSystem::getDevice).thenReturn(device);
      assertSame(main, Mcv2Shaders.mainTarget());
      assertSame(chain, Mcv2Shaders.outlineChain());
      assertSame(commands, Mcv2Shaders.commands());
    }
  }

  @Test
  void asksIrisWhetherItDrawsAShaderPack() {
    final IrisApi api = mock(IrisApi.class);
    try (final MockedStatic<IrisApi> iris = mockStatic(IrisApi.class)) {
      iris.when(IrisApi::getInstance).thenReturn(api);
      when(api.isShaderPackInUse()).thenReturn(true);
      assertTrue(Mcv2Shaders.shaderPackInUse());
      when(api.isShaderPackInUse()).thenReturn(false);
      assertFalse(Mcv2Shaders.shaderPackInUse());
    }
  }

  @Test
  void readsTheLayoutOfTheLoadedMcv2Pack() {
    final Minecraft minecraft = mock(Minecraft.class);
    final ResourceManager resources = mock(ResourceManager.class);
    when(minecraft.getResourceManager()).thenReturn(resources);
    final Resource layout = new Resource(null, () ->
      new ByteArrayInputStream("const int MCV2_SCREENS = 1;".getBytes(StandardCharsets.UTF_8))
    );
    try (final MockedStatic<Minecraft> game = mockStatic(Minecraft.class)) {
      game.when(Minecraft::getInstance).thenReturn(minecraft);
      when(resources.getResource(Mcv2Shaders.LAYOUT)).thenReturn(Optional.empty());
      assertEquals(Optional.empty(), Mcv2Shaders.layout());
      when(resources.getResource(Mcv2Shaders.LAYOUT)).thenReturn(Optional.of(layout));
      assertEquals(Optional.of("const int MCV2_SCREENS = 1;"), Mcv2Shaders.layout());
    }
    assertEquals(Identifier.fromNamespaceAndPath("mcav", "shaders/include/mcv2_config.glsl"), Mcv2Shaders.LAYOUT);
    assertEquals(Identifier.withDefaultNamespace("entity_outline"), Mcv2Shaders.OUTLINE_CHAIN);
  }

  @Test
  void aLayoutThatCannotBeReadIsNone() {
    final Resource unreadable = new Resource(null, () -> {
      throw new IOException("gone");
    });
    assertEquals(Optional.empty(), Mcv2Shaders.read(unreadable));
  }
}
