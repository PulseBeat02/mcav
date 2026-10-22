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
const CALLBACKS = [
    "animateVisualizer",
    "attemptReconnect",
    "stopStream",
    "connectWebSocket",
    "connectAfterResume",
    "reportPermissionFailure",
    "startPlayback",
    "stopPlayback"
];
const CONNECTING = 0;
const CLOSED = 3;

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
    const declarations = CALLBACKS.map(name => {
        const declaration = `const ${name} = useCallback(`;
        const start = source.indexOf(declaration);
        assert.ok(start >= 0, `the page declares ${name} with useCallback`);
        return `${source.slice(start, skipBalanced(source, start + declaration.length - 1))};`;
    });
    const constants = source.match(CONSTANT_DECLARATION).join("\n");
    return stripTypeScriptTypes(`${constants}\n${declarations.join("\n")}\n({${CALLBACKS.join(", ")}});`);
};

/**
 * Loads the page's callbacks into a world of fakes.
 *
 * @param permission what the browser does with the page's request to play sound: 'granted' (the context runs at once),
 *                   'after-resume' (it runs once resumed), 'refused' (resuming fails) or 'ignored' (it stays suspended)
 */
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

test("a second Start before the first stream connected opens no second stream", () => {
    const page = loadPage();
    page.startPlayback();
    page.startPlayback();
    page.runTimers();
    assert.equal(page.sockets.length, 1, "one socket");
    assert.equal(page.liveProcessors(), 1, "one sound processor");
    page.sockets[0].opened();
    page.sockets[0].received(CHUNK_BYTES);
    assert.equal(page.processors.at(-1).fed, 1, "every piece of sound is played once");
});

test("Stop after two Starts leaves no stream behind", () => {
    const page = loadPage();
    page.startPlayback();
    page.startPlayback();
    page.runTimers();
    page.sockets.forEach(socket => socket.opened());
    page.stopPlayback();
    assert.equal(page.openSockets(), 0, "no socket stays open");
    assert.equal(page.liveProcessors(), 0, "no sound processor stays alive");
});

test("the late close of a stopped stream does not start a second one next to the new one", () => {
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
    assert.equal(page.sockets.length, 2, "no socket besides the new one");
    assert.equal(page.openSockets(), 1);
    second.received(CHUNK_BYTES);
    assert.equal(page.liveProcessors(), 1);
    assert.equal(page.processors.at(-1).fed, 1);
});

test("a Stop during a reconnect lets Start be pressed again", () => {
    const page = loadPage();
    page.startPlayback();
    page.runTimers();
    page.sockets[0].opened();
    page.sockets[0].closed();
    page.runTimers();
    assert.equal(page.state.loading, true, "connecting again");
    page.stopPlayback();
    assert.equal(page.state.loading, false);
    assert.equal(page.state.connected, false);
    page.startPlayback();
    page.runTimers();
    assert.equal(page.openSockets(), 1);
});

for (const permission of [PERMISSION_REFUSED, PERMISSION_IGNORED]) {
    test(`a Start whose sound permission is ${permission} lets Start be pressed again`, async () => {
        const page = loadPage(permission);
        page.startPlayback();
        await settle();
        assert.equal(page.state.loading, false, "nothing is loading");
        page.startPlayback();
        await settle();
        assert.equal(page.contexts.length, 2, "the second Start asks again");
        assert.equal(page.sockets.length, 0, "no stream without sound");
    });
}

test("a Start allowed to play once resumed opens one stream", async () => {
    const page = loadPage(PERMISSION_AFTER_RESUME);
    page.startPlayback();
    await settle();
    page.runTimers();
    assert.equal(page.sockets.length, 1);
});

test("reconnecting before the next animation frame keeps one visualizer loop", () => {
    const page = loadPage();
    page.startPlayback();
    page.runTimers();
    page.sockets.at(-1).opened();
    assert.equal(page.activeAnimations(), 1);
    for (let reconnect = 0; reconnect < 3; reconnect++) {
        page.sockets.at(-1).closed();
        page.runTimers();
        page.sockets.at(-1).opened();
        assert.equal(page.activeAnimations(), 1, "only the replacement owns an animation");
        const before = page.draws();
        page.runAnimationFrame();
        assert.equal(page.draws(), before + 1, "one draw per display frame");
        assert.equal(page.activeAnimations(), 1, "one successor");
    }
    page.stopPlayback();
    assert.equal(page.activeAnimations(), 0);
});

test("a dispatched animation from an older processor cannot adopt its replacement", () => {
    const page = loadPage();
    page.startPlayback();
    page.runTimers();
    page.sockets.at(-1).opened();
    const oldDraw = page.pendingAnimation();
    page.sockets.at(-1).closed();
    page.runTimers();
    page.sockets.at(-1).opened();
    oldDraw();
    assert.equal(page.draws(), 0, "the superseded processor owns no new draw");
    page.stopPlayback();
    assert.equal(page.activeAnimations(), 0, "a late callback must not forget the current animation id");
});
