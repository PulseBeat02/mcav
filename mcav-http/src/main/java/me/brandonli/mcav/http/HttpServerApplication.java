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
package me.brandonli.mcav.http;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * The Spring Boot application behind {@link HttpResultImpl}. The owning {@link HttpResultImpl} is registered as a
 * bean before the context starts, so the WebSocket handler and the controller can reach it from the first
 * request on.
 */
@SpringBootApplication
@EnableWebSocket
class HttpServerApplication {

  HttpServerApplication() {
    // configuration only
  }

  @Bean
  AudioWebSocketHandler audioWebSocketHandler(final HttpResultImpl result) {
    return new AudioWebSocketHandler(result);
  }

  @Bean
  WebSocketConfigurer audioWebSocketConfigurer(final AudioWebSocketHandler handler) {
    return (final WebSocketHandlerRegistry registry) -> {
      final WebSocketHandlerRegistration registration = registry.addHandler(handler, "/audio");
      registration.setAllowedOrigins("*");
    };
  }
}
