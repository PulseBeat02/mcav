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
const ENCODING = "utf8";

const CONSTANT_DECLARATION = /^const [A-Z][A-Z0-9_]*: .+;$/gm;
const PROCESSOR_CLASS = "PcmProcessor";
const PROCESSOR_DECLARATION = `class ${PROCESSOR_CLASS} {`;

const OPENING_BRACE = "{";
const CLOSING_BRACE = "}";

const LINE_SEPARATOR = "\n";

const RUNNING = "running";
const SUSPENDED = "suspended";

const SOUND_ENCODING = "16bitInt";
const CHANNELS = 2;
const BYTES_PER_SAMPLE = 2;
const BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE;
const LITTLE_ENDIAN = true;

const WAVE_PERIOD_FRAMES = 8;
const WAVE_AMPLITUDE = 1000;

const SAMPLE_RATE = 48000;
const CHUNK_FRAMES = 1024;
const CHUNK_SECONDS = CHUNK_FRAMES / SAMPLE_RATE;
const CHUNKS_PER_SECOND = Math.round(1 / CHUNK_SECONDS);

const SETTLING_SECONDS = 2;

const DECIMAL_PLACES = 3;

const buildProcessorScript = () => {
    const source = readFileSync(PAGE, ENCODING);
    const start = source.indexOf(PROCESSOR_DECLARATION);
    assert.ok(start >= 0, `the page declares the class ${PROCESSOR_CLASS}`);
    let depth = 0;
    let end = source.indexOf(OPENING_BRACE, start);
    do {
        const character = source[end];
        if (character === OPENING_BRACE) {
            depth++;
        } else if (character === CLOSING_BRACE) {
            depth--;
        }
        end++;
    } while (depth > 0);
    const constants = source.match(CONSTANT_DECLARATION).join(LINE_SEPARATOR);
    return stripTypeScriptTypes([constants, source.slice(start, end), `${PROCESSOR_CLASS};`].join(LINE_SEPARATOR));
};

class FakeAudioContext {
    constructor(state) {
        this.state = state;
        this.currentTime = 0;
        this.destination = {};
        this.started = [];
        this.stopped = [];
    }

    createGain() {
        return {gain: {value: 1}, connect() {}, disconnect() {}};
    }

    createBuffer(channels, length, sampleRate) {
        const data = Array.from({length: channels}, () => new Float32Array(length));
        return {duration: length / sampleRate, getChannelData: channel => data[channel]};
    }

    createBufferSource() {
        const source = {
            buffer: null,
            connect() {},
            disconnect() {},
            start: when => {
                this.started.push({source, when, duration: source.buffer.duration});
            },
            stop: () => {
                this.stopped.push(source);
            },
        };
        return source;
    }

    queuedAhead() {
        const playing = this.started.filter(entry => !this.stopped.includes(entry.source));
        const end = Math.max(...playing.map(entry => entry.when + entry.duration));
        return end - this.currentTime;
    }
}

const createProcessor = state => {
    const PcmProcessor = vm.runInContext(buildProcessorScript(), vm.createContext({
        Float32Array, DataView, ArrayBuffer, Math, Date, Set, window: {},
    }), {filename: PAGE.pathname});
    const context = new FakeAudioContext(state);
    return {processor: new PcmProcessor({encoding: SOUND_ENCODING, channels: CHANNELS, sampleRate: SAMPLE_RATE, audioContext: context}), context};
};

const createChunk = () => {
    const view = new DataView(new ArrayBuffer(CHUNK_FRAMES * BYTES_PER_FRAME));
    for (let frame = 0; frame < CHUNK_FRAMES; frame++) {
        const sample = Math.round(Math.sin(frame / WAVE_PERIOD_FRAMES) * WAVE_AMPLITUDE);
        view.setInt16(frame * BYTES_PER_FRAME, sample, LITTLE_ENDIAN);
        view.setInt16(frame * BYTES_PER_FRAME + BYTES_PER_SAMPLE, sample, LITTLE_ENDIAN);
    }
    return view.buffer;
};

const feedLive = (processor, context, seconds) => {
    const chunks = Math.round(seconds / CHUNK_SECONDS);
    for (let index = 0; index < chunks; index++) {
        context.currentTime += CHUNK_SECONDS;
        processor.feed(createChunk());
    }
};

const feedAtOnce = (processor, chunks) => {
    for (let index = 0; index < chunks; index++) {
        processor.feed(createChunk());
    }
};

test("should not let sound that arrives before the context plays delay the sound after it", () => {
    for (const starting of [SUSPENDED, RUNNING]) {
        for (let chunks = CHUNKS_PER_SECOND; chunks <= Math.round(1.6 / CHUNK_SECONDS); chunks += 3) {
            const {processor, context} = createProcessor(starting);
            feedAtOnce(processor, chunks);
            context.state = RUNNING;
            feedLive(processor, context, SETTLING_SECONDS);

            const late = context.queuedAhead();
            assert.ok(late <= 0.21, `${chunks} chunks while ${starting}: the sound plays as late as steady sound, not ${late.toFixed(DECIMAL_PLACES)} s`);
        }
    }
});

test("should not keep a burst of sound as a delay for good", () => {
    const {processor, context} = createProcessor(RUNNING);
    feedLive(processor, context, 1);
    for (let index = 0; index < CHUNKS_PER_SECOND; index++) {
        context.currentTime += 0.001;
        processor.feed(createChunk());
    }
    feedLive(processor, context, SETTLING_SECONDS);

    const late = context.queuedAhead();
    assert.ok(late <= 0.5, `the sound plays at most half a second late, not ${late.toFixed(DECIMAL_PLACES)} s`);

    const dropped = context.stopped.length;
    assert.ok(dropped > 0, "what was queued too far ahead was dropped");
});

test("should keep a short cushion of steady sound and drop none of it", () => {
    const {processor, context} = createProcessor(RUNNING);
    feedLive(processor, context, 5);

    const cushion = context.queuedAhead();
    assert.ok(cushion > 0, "sound is queued ahead of the clock");
    assert.ok(cushion <= 0.25, `the cushion is short: ${cushion.toFixed(DECIMAL_PLACES)} s`);

    const dropped = context.stopped;
    assert.deepEqual(dropped, [], "nothing was dropped");

    const scheduled = context.started.reduce((total, entry) => total + entry.duration, 0);
    assert.ok(Math.abs(scheduled + 0.1 - 5) < 0.15, `all of the sound is scheduled: ${scheduled.toFixed(DECIMAL_PLACES)} s of 5 s`);
});
