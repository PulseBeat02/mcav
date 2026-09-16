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
package me.brandonli.mcav.sandbox.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.command.image.ImageManager;
import me.brandonli.mcav.sandbox.command.interaction.BrowserCommand;
import me.brandonli.mcav.sandbox.command.interaction.VirtualizeCommand;
import me.brandonli.mcav.sandbox.command.video.VideoPlayerManager;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.TestCommandManager;
import me.brandonli.mcav.sandbox.testing.TestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.description.CommandDescription;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.execution.CommandExecutor;
import org.incendo.cloud.execution.CommandResult;
import org.incendo.cloud.minecraft.extras.RichDescription;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link AnnotationParserHandler} by parsing every command of the plugin into a command manager that
 * registers nothing on a server.
 */
final class AnnotationParserHandlerTest {

  private static final List<String> EXPECTED_SYNTAXES = List.of(
    "mcav dump",
    "mcav help query",
    "mcav screen blockDimensions mapId material location",
    "mcav image release",
    "mcav image map playerSelector imageResolution blockDimensions mapId ditheringAlgorithm mrl",
    "mcav video pause",
    "mcav video resume",
    "mcav video release",
    "mcav video hologram set location",
    "mcav video hologram disable",
    "mcav video map playerSelector playerType audioType videoResolution blockDimensions mapId ditheringAlgorithm flags mrl",
    "mcav browser interact",
    "mcav browser release",
    "mcav browser create playerSelector browserResolution quality nth blockDimensions mapId ditheringAlgorithm url",
    "mcav vm interact",
    "mcav vm release",
    "mcav vm create playerSelector vmResolution targetFps blockDimensions mapId ditheringAlgorithm architecture flags"
  );

  private MCAVSandbox plugin;
  private VideoPlayerManager videoManager;
  private TestCommandManager commands;
  private LegacyPaperCommandManager<CommandSender> paperManager;

  private MockedStatic<LegacyPaperCommandManager<CommandSender>> paperManagers;

  @BeforeEach
  void mockServer() {
    final Server server = TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getServer()).thenReturn(server);
    this.videoManager = mock(VideoPlayerManager.class);
    final ImageManager imageManager = mock(ImageManager.class);
    final AudioProvider audioProvider = mock(AudioProvider.class);
    when(this.plugin.getVideoPlayerManager()).thenReturn(this.videoManager);
    when(this.plugin.getImageManager()).thenReturn(imageManager);
    when(this.plugin.getAudioProvider()).thenReturn(audioProvider);
    this.commands = new TestCommandManager();
    this.paperManager = this.commands.asPaperManager();
    this.paperManagers = Mockito.mockStatic();
    this.paperManagers.when(() -> LegacyPaperCommandManager.createNative(eq(this.plugin), notNull())).thenReturn(this.paperManager);
  }

  @AfterEach
  void closeMocks() {
    this.paperManagers.close();
  }

  @Test
  void usesThePaperCommandManagerOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    final CommandManager<CommandSender> manager = handler.getManager();
    assertSame(this.paperManager, manager);
    verify(this.paperManager, never()).registerBrigadier();
  }

  @Test
  void registersBrigadierWhenPaperSupportsIt() {
    doReturn(true).when(this.paperManager).hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER);
    doNothing().when(this.paperManager).registerBrigadier();
    new AnnotationParserHandler(this.plugin);
    verify(this.paperManager).registerBrigadier();
  }

  @Test
  void registersEveryCommandOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final Set<String> syntaxes = this.commands.syntaxes();
    final int count = syntaxes.size();
    final String registeredSyntaxes = String.valueOf(syntaxes);
    assertEquals(25, count, registeredSyntaxes);
    for (final String syntax : EXPECTED_SYNTAXES) {
      final boolean registered = syntaxes.contains(syntax);
      assertTrue(registered, syntax + " in " + syntaxes);
    }

    final PluginManager pluginManager = TestServer.pluginManager();
    verify(pluginManager).registerEvents(any(BrowserCommand.class), eq(this.plugin));
    verify(pluginManager).registerEvents(any(VirtualizeCommand.class), eq(this.plugin));
  }

  @Test
  void describesTheCommandsInTheLanguageOfThePlugin() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final Command<CommandSender> dump = this.commands.command("mcav dump");
    final CommandDescription commandDescription = dump.commandDescription();
    final Description description = commandDescription.description();
    final RichDescription rich = (RichDescription) description;
    final Component contents = rich.contents();
    final String text = Components.plain(contents);
    assertEquals("Creates a dump of the current server information", text);
  }

  @Test
  void runsTheParsedCommands() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    final VideoPlayerMultiplexer player = mock(VideoPlayerMultiplexer.class);
    when(this.videoManager.getPlayer()).thenReturn(player);
    final CommandSender sender = mock(CommandSender.class);
    when(sender.hasPermission(anyString())).thenReturn(true);
    final CommandExecutor<CommandSender> executor = this.commands.commandExecutor();
    final CompletableFuture<CommandResult<CommandSender>> execution = executor.executeCommand(sender, "mcav video pause");
    execution.join();
    verify(player).pause();
    final List<Component> messages = Components.received(sender);
    final Component paused = Message.PAUSE_PLAYER.build();
    assertEquals(List.of(paused), messages);
  }

  @Test
  void shutsEveryFeatureDown() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this.plugin);
    handler.registerCommands();
    try (final MockedStatic<HandlerList> handlers = Mockito.mockStatic(HandlerList.class)) {
      handler.shutdownCommands();
      handlers.verify(() -> HandlerList.unregisterAll(any(BrowserCommand.class)));
      handlers.verify(() -> HandlerList.unregisterAll(any(VirtualizeCommand.class)));
    }
  }

  @Test
  void rendersDescriptionsWithTheMessagesOfThePlugin() {
    final RichDescription description = AnnotationParserHandler.describe("mcav.command.video.pause.info");
    final Component contents = description.contents();
    final String text = Components.plain(contents);
    assertEquals("Pauses the current video player", text);
    final RichDescription none = AnnotationParserHandler.describe("");
    final boolean empty = none.isEmpty();
    assertTrue(empty);
    assertThrows(MissingResourceException.class, () -> AnnotationParserHandler.describe("mcav.unknown"));
  }

  @Test
  void refusesANullPlugin() {
    assertThrows(NullPointerException.class, () -> new AnnotationParserHandler(null));
  }
}
