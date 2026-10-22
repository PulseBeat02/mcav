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

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { stripTypeScriptTypes } from "node:module";
import { test } from "node:test";
import vm from "node:vm";

const PAGE = new URL("../src/app/page.tsx", import.meta.url);
const CONSTANT_DECLARATION = /^const [A-Z][A-Z0-9_]*: .+;$/gm;
const ENCODING = "utf8";
const ARROW_BODY = "=> {";
const HELPERS = ["toHslColor", "createGradient", "traceWaveform", "drawWaveform"];
const CALLBACKS = [
    "cancelAnimation",
    "animateVisualizer",
    "reconnect",
    "attemptReconnect",
    "cancelReconnect",
    "cancelConnect",
    "closeSocket",
    "destroyProcessor",
    "clearVisualizer",
    "hideVisualizer",
    "stopStream",
    "showVisualizer",
    "startConnectedStream",
    "feedProcessor",
    "playReceivedSound",
    "reconnectOrStopAfterError",
    "reconnectOrStopAfterClose",
    "openSocket",
    "openStream",
    "connectWebSocket",
    "connectAfterResume",
    "reportPermissionFailure",
    "startPlayback",
    "stopPlayback"
];
const CONNECTING = 0;
const CLOSED = 3;

// how the browser answers the request to play sound: the context runs at once, runs once resumed, fails to resume or stays suspended
const PERMISSION_GRANTED = "granted";
const PERMISSION_AFTER_RESUME = "after-resume";
const PERMISSION_REFUSED = "refused";
const PERMISSION_IGNORED = "ignored";

const CHUNK_BYTES = 4;

const MAXIMUM_TIMER_ROUNDS = 10;

const skipString = (source, index) => {
    let at = index + 1;
    while (source[at] !== source[index]) {
        at += source[at] === "\\" ? 2 : 1;
    }
    return at + 1;
};

const skipTemplate = (source, index) => {
    let at = index + 1;
    while (source[at] !== "`") {
        if (source[at] === "\\") {
            at += 2;
        } else if (source[at] === "$" && source[at + 1] === "{") {
            at = skipBalanced(source, at + 1);
        } else {
            at++;
        }
    }
    return at + 1;
};

const skipBalanced = (source, index) => {
    const closing = {"(": ")", "{": "}", "[": "]"};
    const expected = [closing[source[index]]];
    let at = index + 1;
    while (expected.length > 0) {
        assert.ok(at < source.length, "the page ends inside a callback");
        const character = source[at];
        const next = source[at + 1];
        if (character === "/" && next === "/") {
            at = source.indexOf("\n", at);
        } else if (character === "/" && next === "*") {
            at = source.indexOf("*/", at) + 2;
        } else if (character === "'" || character === "\"") {
            at = skipString(source, at);
        } else if (character === "`") {
            at = skipTemplate(source, at);
        } else if (character in closing) {
            expected.push(closing[character]);
            at++;
        } else {
            if (character === expected.at(-1)) {
                expected.pop();
            }
            at++;
        }
    }
    return at;
};

const pageCallbacks = () => {
    const source = readFileSync(PAGE, ENCODING);
    const helpers = HELPERS.map(name => {
        const start = source.indexOf(`const ${name} = (`);
        assert.ok(start >= 0, `the page declares the helper ${name}`);
        const body = source.indexOf(ARROW_BODY, start) + ARROW_BODY.length - 1;
        return `${source.slice(start, skipBalanced(source, body))};`;
    });
    const declarations = CALLBACKS.map(name => {
        const declaration = `const ${name} = useCallback(`;
        const start = source.indexOf(declaration);
        assert.ok(start >= 0, `the page declares ${name} with useCallback`);
        return `${source.slice(start, skipBalanced(source, start + declaration.length - 1))};`;
    });
    const constants = source.match(CONSTANT_DECLARATION).join("\n");
    const script = `${constants}\n${helpers.join("\n")}\n${declarations.join("\n")}\n({${CALLBACKS.join(", ")}});`;
    return stripTypeScriptTypes(script);
};

const loadPage = (permission = PERMISSION_GRANTED) => {
    const timers = new Map();
    const animations = new Map();
    let nextAnimation = 1;
    let draws = 0;
    let nextTimer = 1;
    const sockets = [];
    const processors = [];
    const contexts = [];
    const state = {loading: false, connected: false};

    class FakeSocket {
        static OPEN = 1;

        constructor(url) {
            this.url = url;
            this.readyState = CONNECTING;
            sockets.push(this);
        }

        close() {
            this.readyState = CLOSED;
        }

        send() {
        }

        opened() {
            this.readyState = FakeSocket.OPEN;
            this.onopen?.();
        }

        received(bytes) {
            this.onmessage?.({data: new ArrayBuffer(bytes)});
        }

        closed() {
            this.readyState = CLOSED;
            this.onclose?.();
        }
    }

    class FakeProcessor {
        constructor() {
            this.isDestroyed = false;
            this.fed = 0;
            this.gainNode = {gain: {value: 1}};
            processors.push(this);
        }

        destroy() {
            this.isDestroyed = true;
        }

        feed() {
            this.fed++;
        }

        flush() {
        }

        getVisualizerData() {
            return {smoothedData: new Float32Array(256), hue: 60};
        }
    }

    class FakeAudioContext {
        constructor() {
            this.state = permission === PERMISSION_GRANTED ? "running" : "suspended";
            contexts.push(this);
        }

        resume() {
            if (permission === PERMISSION_REFUSED) {
                return Promise.reject(new Error("NotAllowedError"));
            }
            if (permission === PERMISSION_AFTER_RESUME) {
                this.state = "running";
            }
            return Promise.resolve();
        }

        close() {
            this.state = "closed";
            return Promise.resolve();
        }
    }

    const ref = current => ({current});
    const doNothing = () => {
    };
    const context = vm.createContext({
        console, ArrayBuffer, Promise, Math,
        WebSocket: FakeSocket, PcmProcessor: FakeProcessor, AudioContext: FakeAudioContext,
        window: {location: {protocol: "http:", host: "player.test"}},
        useCallback: callback => callback,
        setTimeout: callback => {
            const id = nextTimer++;
            timers.set(id, callback);
            return id;
        },
        clearTimeout: id => timers.delete(id),
        requestAnimationFrame: callback => {
            const identifier = nextAnimation++;
            animations.set(identifier, callback);
            return identifier;
        },
        cancelAnimationFrame: identifier => animations.delete(identifier),
        socketRef: ref(undefined), pcmProcessorRef: ref(undefined), audioContextRef: ref(undefined), connectRef: ref(doNothing),
        shouldReconnectRef: ref(false), streamingRef: ref(false), reconnectTimeoutRef: ref(undefined),
        connectTimeoutRef: ref(undefined), reconnectAttemptsRef: ref(0), animationIdRef: ref(undefined), volumeRef: ref(100),
        canvasRef: ref({
            width: 320, height: 120, style: {},
            getContext: () => ({
                fillRect: doNothing, clearRect: doNothing, beginPath: doNothing, moveTo: doNothing, lineTo: doNothing,
                closePath: doNothing, fill: doNothing,
                createLinearGradient: () => ({addColorStop: doNothing}),
                stroke: () => { draws++; },
            }),
        }), placeholderRef: ref(null),
        setIsLoading: loading => {
            state.loading = loading;
        },
        setIsConnected: connected => {
            state.connected = connected;
        },
        updateStatus: doNothing, startMetadataRefresh: doNothing, stopMetadataRefresh: doNothing, startHeartbeat: doNothing,
        stopHeartbeat: doNothing, reportCloseFailure: doNothing,
    });
    const page = vm.runInContext(pageCallbacks(), context, {filename: PAGE.pathname});
    context.connectRef.current = page.connectWebSocket;
    return {
        ...page, sockets, processors, contexts, state,
        activeAnimations: () => animations.size,
        pendingAnimation: () => animations.values().next().value,
        draws: () => draws,
        runAnimationFrame() {
            const due = [...animations.values()];
            animations.clear();
            due.forEach(callback => callback());
        },
        runTimers() {
            for (let round = 0; round < MAXIMUM_TIMER_ROUNDS && timers.size > 0; round++) {
                const due = [...timers.entries()];
                timers.clear();
                due.forEach(([, callback]) => callback());
            }
        },
        liveProcessors: () => processors.filter(processor => !processor.isDestroyed).length,
        openSockets: () => sockets.filter(socket => socket.readyState !== CLOSED).length,
    };
};

const settle = () => {
    return new Promise(resolve => setImmediate(resolve));
};

test("should open no second stream for a second Start before the first stream connected", () => {
    const page = loadPage();
    page.startPlayback();
    page.startPlayback();
    page.runTimers();

    const socketCount = page.sockets.length;
    assert.equal(socketCount, 1, "one socket");

    const liveProcessors = page.liveProcessors();
    assert.equal(liveProcessors, 1, "one sound processor");

    const socket = page.sockets[0];
    socket.opened();
    socket.received(CHUNK_BYTES);

    const feeds = page.processors.at(-1).fed;
    assert.equal(feeds, 1, "every piece of sound is played once");
});

test("should leave no stream behind after a Stop that follows two Starts", () => {
    const page = loadPage();
    page.startPlayback();
    page.startPlayback();
    page.runTimers();
    page.sockets.forEach(socket => socket.opened());
    page.stopPlayback();

    const openSockets = page.openSockets();
    assert.equal(openSockets, 0, "no socket stays open");

    const liveProcessors = page.liveProcessors();
    assert.equal(liveProcessors, 0, "no sound processor stays alive");
});

test("should start no second stream next to the new one when a stopped stream closes late", () => {
    const page = loadPage();
    page.startPlayback();
    page.runTimers();
    const first = page.sockets[0];
    first.opened();
    page.stopPlayback();
    page.startPlayback();
    page.runTimers();
    const second = page.sockets[1];
    second.opened();
    first.closed();
    page.runTimers();

    const socketCount = page.sockets.length;
    assert.equal(socketCount, 2, "no socket besides the new one");
    const openSockets = page.openSockets();
    assert.equal(openSockets, 1);

    second.received(CHUNK_BYTES);

    const liveProcessors = page.liveProcessors();
    assert.equal(liveProcessors, 1);
    const feeds = page.processors.at(-1).fed;
    assert.equal(feeds, 1);
});

test("should let Start be pressed again after a Stop during a reconnect", () => {
    const page = loadPage();
    page.startPlayback();
    page.runTimers();
    const socket = page.sockets[0];
    socket.opened();
    socket.closed();
    page.runTimers();

    const reconnecting = page.state.loading;
    assert.equal(reconnecting, true, "connecting again");

    page.stopPlayback();

    const loading = page.state.loading;
    assert.equal(loading, false);
    const connected = page.state.connected;
    assert.equal(connected, false);

    page.startPlayback();
    page.runTimers();

    const openSockets = page.openSockets();
    assert.equal(openSockets, 1);
});

for (const permission of [PERMISSION_REFUSED, PERMISSION_IGNORED]) {
    test(`should let Start be pressed again after a Start whose sound permission is ${permission}`, async () => {
        const page = loadPage(permission);
        page.startPlayback();
        await settle();

        const loading = page.state.loading;
        assert.equal(loading, false, "nothing is loading");

        page.startPlayback();
        await settle();

        const contextCount = page.contexts.length;
        assert.equal(contextCount, 2, "the second Start asks again");

        const socketCount = page.sockets.length;
        assert.equal(socketCount, 0, "no stream without sound");
    });
}

test("should open one stream for a Start allowed to play once resumed", async () => {
    const page = loadPage(PERMISSION_AFTER_RESUME);
    page.startPlayback();
    await settle();
    page.runTimers();

    const socketCount = page.sockets.length;
    assert.equal(socketCount, 1);
});

test("should keep one visualizer loop when reconnecting before the next animation frame", () => {
    const page = loadPage();
    page.startPlayback();
    page.runTimers();
    page.sockets.at(-1).opened();

    const animations = page.activeAnimations();
    assert.equal(animations, 1);

    for (let reconnect = 0; reconnect < 3; reconnect++) {
        page.sockets.at(-1).closed();
        page.runTimers();
        page.sockets.at(-1).opened();

        const replacementAnimations = page.activeAnimations();
        assert.equal(replacementAnimations, 1, "only the replacement owns an animation");

        const drawsBefore = page.draws();
        page.runAnimationFrame();

        const drawsAfter = page.draws();
        assert.equal(drawsAfter, drawsBefore + 1, "one draw per display frame");

        const successors = page.activeAnimations();
        assert.equal(successors, 1, "one successor");
    }

    page.stopPlayback();

    const remainingAnimations = page.activeAnimations();
    assert.equal(remainingAnimations, 0);
});

test("should let no dispatched animation of an older processor adopt its replacement", () => {
    const page = loadPage();
    page.startPlayback();
    page.runTimers();
    page.sockets.at(-1).opened();
    const oldDraw = page.pendingAnimation();
    page.sockets.at(-1).closed();
    page.runTimers();
    page.sockets.at(-1).opened();
    oldDraw();

    const draws = page.draws();
    assert.equal(draws, 0, "the superseded processor owns no new draw");

    page.stopPlayback();

    const animations = page.activeAnimations();
    assert.equal(animations, 0, "a late callback must not forget the current animation id");
});
