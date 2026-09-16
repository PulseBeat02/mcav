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
package me.brandonli.mcav.sandbox.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.http.HttpResult;
import me.brandonli.mcav.http.MediaInfo;
import me.brandonli.mcav.jda.DiscordPlayer;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.svc.SVCFilter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link AudioProvider}.
 *
 * <p>Most tests start the outputs on the calling thread, so they are ready when {@code initialize} returns. The
 * tests about starting in the background use an executor that only collects the tasks, which run when the test
 * says so.
 */
final class AudioProviderTest {

  private final URLParseDump dump = new URLParseDump();
  private final Object[] players = { "Steve" };
  private final List<Runnable> deferred = new ArrayList<>();

  private MCAVSandbox sandbox;
  private PluginDataConfigurationMapper configuration;
  private ExecutorService startup;
  private AudioProvider provider;
  private MockedStatic<JDABuilder> builders;
  private MockedStatic<DiscordPlayer> discordPlayers;
  private MockedStatic<HttpResult> httpResults;
  private MockedStatic<SVCFilter> svcFilters;

  private JDABuilder builder;
  private JDA jda;
  private Guild guild;
  private VoiceChannel channel;
  private AudioManager audioManager;
  private DiscordPlayer discord;
  private HttpResult httpServer;
  private SVCFilter voiceChatFilter;

  @BeforeEach
  void createProvider() {
    TestServer.reset();
    this.sandbox = mock(MCAVSandbox.class);
    this.configuration = mock(PluginDataConfigurationMapper.class);
    when(this.sandbox.getConfiguration()).thenReturn(this.configuration);
    when(this.configuration.getDiscordBotToken()).thenReturn("token");
    when(this.configuration.getDiscordBotGuildId()).thenReturn("100");
    when(this.configuration.getDiscordBotChannelId()).thenReturn("200");
    when(this.configuration.getHttpHostName()).thenReturn("mc.example.com");
    when(this.configuration.getHttpPort()).thenReturn(3000);
    this.startup = MoreExecutors.newDirectExecutorService();
    this.provider = new AudioProvider(this.sandbox, this.startup);
    this.mockOutputs();
  }

  private void mockOutputs() {
    this.builder = mock(JDABuilder.class);
    this.jda = mock(JDA.class);
    this.guild = mock(Guild.class);
    this.channel = mock(VoiceChannel.class);
    this.audioManager = mock(AudioManager.class);
    this.discord = mock(DiscordPlayer.class);
    this.httpServer = mock(HttpResult.class);
    this.voiceChatFilter = mock(SVCFilter.class);
    when(this.builder.build()).thenReturn(this.jda);
    when(this.jda.getGuildById("100")).thenReturn(this.guild);
    when(this.guild.getVoiceChannelById("200")).thenReturn(this.channel);
    when(this.guild.getAudioManager()).thenReturn(this.audioManager);
    this.builders = Mockito.mockStatic(JDABuilder.class);
    // JDA requires the result of createLight to be used, so the stubbed call assigns it to an unnamed variable
    this.builders.when(() -> {
        final JDABuilder _ = JDABuilder.createLight("token", GatewayIntent.GUILD_VOICE_STATES);
      }).thenReturn(this.builder);
    this.discordPlayers = Mockito.mockStatic(DiscordPlayer.class);
    this.discordPlayers.when(() -> DiscordPlayer.voice(this.jda)).thenReturn(this.discord);
    this.httpResults = Mockito.mockStatic(HttpResult.class);
    this.httpResults.when(() -> HttpResult.http("mc.example.com", 3000)).thenReturn(this.httpServer);
    this.svcFilters = Mockito.mockStatic(SVCFilter.class);
    this.svcFilters.when(() -> SVCFilter.svc(this.players)).thenReturn(this.voiceChatFilter);
  }

  @AfterEach
  void closeStaticMocks() {
    this.builders.close();
    this.discordPlayers.close();
    this.httpResults.close();
    this.svcFilters.close();
    this.startup.shutdownNow();
  }

  private void enableDiscord() {
    when(this.configuration.isDiscordBotEnabled()).thenReturn(true);
  }

  private void enableHttp() {
    when(this.configuration.isHttpEnabled()).thenReturn(true);
  }

  // an executor that keeps the tasks until runDeferred(), like a thread that has not got to them yet
  private void startInTheBackground() {
    final ExecutorService executor = mock(ExecutorService.class);
    doAnswer(invocation -> {
      final Runnable task = invocation.getArgument(0);
      this.deferred.add(task);
      return null;
    })
      .when(executor)
      .execute(any(Runnable.class));
    this.startup = executor;
    this.provider = new AudioProvider(this.sandbox, executor);
  }

  private int runDeferred() {
    final List<Runnable> tasks = new ArrayList<>(this.deferred);
    this.deferred.clear();
    for (final Runnable task : tasks) {
      task.run();
    }
    return tasks.size();
  }

  private String discordFailure() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players)
    );
    return exception.getMessage();
  }

  private String httpFailure() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players)
    );
    return exception.getMessage();
  }

  @Test
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> new AudioProvider(null));
    assertThrows(NullPointerException.class, () -> new AudioProvider(this.sandbox, null));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(null, this.dump, this.players));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(AudioArgument.NONE, null, this.players));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(AudioArgument.NONE, this.dump, null));
  }

  @Test
  void linksToTheConfiguredVoiceChannel() {
    final String url = this.provider.constructVoiceChannelUrl();
    assertEquals("https://discord.com/channels/100/200", url);
  }

  @Test
  void linksToTheConfiguredWebPageBeforeItStarts() {
    final String url = this.provider.constructHttpUrl();
    assertEquals("http://mc.example.com:3000/", url);
  }

  @Test
  void reportsWhichOutputsAreEnabled() {
    final boolean discordBefore = this.provider.isDiscordBotEnabled();
    final boolean httpBefore = this.provider.isHttpEnabled();
    assertFalse(discordBefore);
    assertFalse(httpBefore);
    this.enableDiscord();
    this.enableHttp();
    final boolean discordAfter = this.provider.isDiscordBotEnabled();
    final boolean httpAfter = this.provider.isHttpEnabled();
    assertTrue(discordAfter);
    assertTrue(httpAfter);
  }

  @Test
  void startsNothingWhenEveryOutputIsDisabled() {
    this.provider.initialize();
    this.builders.verifyNoInteractions();
    this.httpResults.verifyNoInteractions();
    final PluginManager pluginManager = TestServer.pluginManager();
    verifyNoInteractions(pluginManager);
    final boolean discordReady = this.provider.isDiscordBotReady();
    final boolean httpReady = this.provider.isHttpReady();
    assertFalse(discordReady);
    assertFalse(httpReady);
  }

  @Test
  void logsTheBotInAndPlaysIntoTheConfiguredChannel() throws InterruptedException {
    this.enableDiscord();
    this.provider.initialize();
    verify(this.builder).enableCache(CacheFlag.VOICE_STATE);
    verify(this.jda).awaitReady();
    final boolean ready = this.provider.isDiscordBotReady();
    assertTrue(ready);
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    assertSame(this.discord, filter);
    final InOrder order = inOrder(this.discord, this.audioManager);
    order.verify(this.discord).flush();
    order.verify(this.audioManager).setSendingHandler(this.discord);
    order.verify(this.audioManager).openAudioConnection(this.channel);
    order.verify(this.discord).setCurrentMedia(this.dump);
  }

  @Test
  void logsTheBotInWithoutMakingTheCallerWait() throws InterruptedException {
    this.startInTheBackground();
    this.enableDiscord();
    this.provider.initialize();
    verify(this.builder).build();
    verify(this.jda, never()).awaitReady();
    final boolean readyWhileStarting = this.provider.isDiscordBotReady();
    assertFalse(readyWhileStarting);
    final String message = this.discordFailure();
    assertEquals("The Discord bot is not ready", message);
    final int ran = this.runDeferred();
    assertEquals(1, ran);
    verify(this.jda).awaitReady();
    final boolean ready = this.provider.isDiscordBotReady();
    assertTrue(ready);
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    assertSame(this.discord, filter);
  }

  @Test
  void shutsTheBotDownWhenItIsNotAMemberOfTheGuild() {
    this.enableDiscord();
    when(this.jda.getGuildById("100")).thenReturn(null);
    this.provider.initialize();
    verify(this.jda).shutdownNow();
    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
    final String message = this.discordFailure();
    assertEquals("The Discord bot is not ready", message);
    this.provider.shutdown();
    verify(this.jda, never()).shutdown();
  }

  @Test
  void shutsTheBotDownWhenTheVoiceChannelDoesNotExist() {
    this.enableDiscord();
    when(this.guild.getVoiceChannelById("200")).thenReturn(null);
    this.provider.initialize();
    verify(this.jda).shutdownNow();
    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
    this.discordPlayers.verifyNoInteractions();
  }

  @Test
  void shutsTheBotDownAndKeepsTheInterruptWhenInterruptedWhileLoggingIn() throws InterruptedException {
    this.enableDiscord();
    doThrow(new InterruptedException("stop")).when(this.jda).awaitReady();
    this.provider.initialize();
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);
    verify(this.jda).shutdownNow();
    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
  }

  @Test
  void startsTheWebPageAndShowsTheMediaOnIt() {
    this.enableHttp();
    when(this.httpServer.getFullUrl()).thenReturn("http://mc.example.com:3000/");
    this.provider.initialize();
    verify(this.httpServer).start();
    final boolean ready = this.provider.isHttpReady();
    assertTrue(ready);
    final String url = this.provider.constructHttpUrl();
    assertEquals("http://mc.example.com:3000/", url);
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players);
    assertSame(this.httpServer, filter);
    verify(this.httpServer).setCurrentMedia(this.dump);
  }

  @Test
  void startsTheWebPageWithoutMakingTheCallerWait() {
    this.startInTheBackground();
    this.enableHttp();
    this.provider.initialize();
    verify(this.httpServer, never()).start();
    final boolean readyWhileStarting = this.provider.isHttpReady();
    assertFalse(readyWhileStarting);
    final String message = this.httpFailure();
    assertEquals("The audio web page is not ready", message);
    final int ran = this.runDeferred();
    assertEquals(1, ran);
    verify(this.httpServer).start();
    final boolean ready = this.provider.isHttpReady();
    assertTrue(ready);
  }

  @Test
  void leavesTheWebPageUnavailableWhenItFailsToStart() {
    this.enableHttp();
    doThrow(new IllegalStateException("port 3000 is in use")).when(this.httpServer).start();
    this.provider.initialize();
    final boolean ready = this.provider.isHttpReady();
    assertFalse(ready);
    final String message = this.httpFailure();
    assertEquals("The audio web page is not ready", message);
    final String url = this.provider.constructHttpUrl();
    assertEquals("http://mc.example.com:3000/", url);
  }

  @Test
  void refusesOutputsThatWereNotStarted() {
    final String httpMessage = this.httpFailure();
    final String discordMessage = this.discordFailure();
    assertEquals("The audio web page is not ready", httpMessage);
    assertEquals("The Discord bot is not ready", discordMessage);
  }

  @Test
  void playsNoAudioForTheNoneOutput() {
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.NONE, this.dump, this.players);
    assertSame(AudioFilter.NO_OP, filter);
  }

  @Test
  void registersForTheServerLoadWhenVoiceChatIsInstalled() {
    when(this.configuration.isSimpleVoiceChatEnabled()).thenReturn(true);
    final PluginManager pluginManager = TestServer.pluginManager();
    when(pluginManager.isPluginEnabled("voicechat")).thenReturn(true);
    this.provider.initialize();
    verify(pluginManager).registerEvents(any(ServerLoadListener.class), eq(this.sandbox));
  }

  @Test
  void failsWhenVoiceChatIsEnabledButNotInstalled() {
    when(this.configuration.isSimpleVoiceChatEnabled()).thenReturn(true);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, this.provider::initialize);
    final String message = exception.getMessage();
    assertEquals("Simple Voice Chat audio is enabled, but the voicechat plugin is not installed", message);
    final PluginManager pluginManager = TestServer.pluginManager();
    verify(pluginManager, never()).registerEvents(any(), any());
  }

  @Test
  void playsThroughVoiceChatSpeakersUntilReleased() {
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    assertSame(this.voiceChatFilter, filter);
    verify(this.voiceChatFilter).start();
    this.provider.releaseAudioFilter();
    this.provider.releaseAudioFilter();
    verify(this.voiceChatFilter, times(1)).release();
  }

  @Test
  void disconnectsEveryOutputWhenTheVideoIsReleased() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    this.provider.releaseAudioFilter();
    verify(this.audioManager).setSendingHandler(null);
    verify(this.audioManager).closeAudioConnection();
    verify(this.discord).flush();
    verify(this.httpServer).setCurrentMedia(MediaInfo.EMPTY);
    verify(this.httpServer, never()).stop();
    verify(this.jda, never()).shutdown();
  }

  @Test
  void releasesNothingWhenNothingWasStarted() {
    this.provider.releaseAudioFilter();
    this.provider.shutdown();
    verifyNoInteractions(this.audioManager, this.discord, this.httpServer, this.voiceChatFilter, this.jda);
    final boolean stopped = this.startup.isShutdown();
    assertTrue(stopped);
  }

  @Test
  void stopsEveryOutputOnceWhenShutDown() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    this.provider.shutdown();
    this.provider.shutdown();
    verify(this.httpServer, times(1)).stop();
    verify(this.jda, times(1)).shutdown();
    verify(this.audioManager, times(1)).closeAudioConnection();
    final String message = this.discordFailure();
    assertEquals("The Discord bot is not ready", message);
    final boolean httpReady = this.provider.isHttpReady();
    assertFalse(httpReady);
    final String url = this.provider.constructHttpUrl();
    assertEquals("http://mc.example.com:3000/", url);
    final boolean stopped = this.startup.isShutdown();
    assertTrue(stopped);
  }

  @Test
  void stopsOutputsThatFinishStartingAfterTheShutdown() throws InterruptedException {
    this.startInTheBackground();
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    this.provider.shutdown();
    verify(this.jda).shutdown();
    verify(this.startup).shutdownNow();
    verify(this.httpServer, never()).stop();
    final int ran = this.runDeferred();
    assertEquals(2, ran);
    verify(this.jda).awaitReady();
    verify(this.httpServer).start();
    verify(this.httpServer).stop();
    final boolean discordReady = this.provider.isDiscordBotReady();
    final boolean httpReady = this.provider.isHttpReady();
    assertFalse(discordReady);
    assertFalse(httpReady);
    verify(this.jda, never()).shutdownNow();
  }

  @Test
  void doesNotShutDownABotTwiceWhenItFailsAfterTheShutdown() {
    this.startInTheBackground();
    this.enableDiscord();
    when(this.jda.getGuildById("100")).thenReturn(null);
    this.provider.initialize();
    this.provider.shutdown();
    this.runDeferred();
    verify(this.jda, times(1)).shutdown();
    verify(this.jda, never()).shutdownNow();
    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
  }
}
