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
package me.brandonli.mcav.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class LocalHttpServerTest {

  private static final String QUEUED_PATH = "/queued";
  private static final String UNKNOWN_PATH = "/unknown";

  private static final int OK_STATUS = 200;
  private static final int NOT_FOUND_STATUS = 404;
  private static final int SERVICE_UNAVAILABLE_STATUS = 503;

  private static final String BUSY_BODY = "busy";
  private static final String READY_BODY = "ready";

  private static final Duration TIMEOUT = Duration.ofMillis(100);

  private LocalHttpServer server;
  private HttpClient client;

  @BeforeEach
  void setUp() {
    this.server = LocalHttpServer.start();
    this.client = HttpClient.newHttpClient();
  }

  @AfterEach
  void tearDown() {
    this.client.close();
    this.server.close();
  }

  @Test
  void shouldReplayTheQueuedResponsesOfAPathInOrderAndRepeatTheLast() throws IOException, InterruptedException {
    final byte[] busy = BUSY_BODY.getBytes(StandardCharsets.UTF_8);
    final byte[] ready = READY_BODY.getBytes(StandardCharsets.UTF_8);
    this.server.respond(QUEUED_PATH, SERVICE_UNAVAILABLE_STATUS, busy);
    this.server.respond(QUEUED_PATH, OK_STATUS, ready);

    final HttpResponse<String> first = this.fetch(QUEUED_PATH);
    final HttpResponse<String> second = this.fetch(QUEUED_PATH);
    final HttpResponse<String> third = this.fetch(QUEUED_PATH);
    final List<Integer> statuses = List.of(first.statusCode(), second.statusCode(), third.statusCode());
    final List<String> bodies = List.of(first.body(), second.body(), third.body());
    final int requests = this.server.getRequestCount(QUEUED_PATH);
    assertEquals(List.of(SERVICE_UNAVAILABLE_STATUS, OK_STATUS, OK_STATUS), statuses);
    assertEquals(List.of(BUSY_BODY, READY_BODY, READY_BODY), bodies);
    assertEquals(3, requests);
  }

  private HttpResponse<String> fetch(final String path) throws IOException, InterruptedException {
    final URI uri = this.server.uri(path);
    final HttpRequest request = HttpRequest.newBuilder(uri).build();
    return this.client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void shouldAnswerNotFoundForAPathWithoutQueuedResponses() throws IOException, InterruptedException {
    final byte[] ready = READY_BODY.getBytes(StandardCharsets.UTF_8);
    this.server.respond(QUEUED_PATH, OK_STATUS, ready);

    final HttpResponse<String> response = this.fetch(UNKNOWN_PATH);
    final int status = response.statusCode();
    assertEquals(NOT_FOUND_STATUS, status);
  }

  @Test
  void shouldReportMissingRequestsOnlyOnceTheTimeoutEnds() throws InterruptedException {
    final long before = System.nanoTime();
    final boolean received = this.server.awaitRequests(QUEUED_PATH, 1, TIMEOUT);
    final long elapsed = System.nanoTime() - before;
    final boolean waitedOutTheTimeout = elapsed >= TIMEOUT.toNanos();
    assertFalse(received);
    assertTrue(waitedOutTheTimeout);
  }
}
