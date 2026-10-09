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

import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {stripTypeScriptTypes} from 'node:module';
import {test} from 'node:test';
import vm from 'node:vm';

const PAGE = new URL('../src/app/page.tsx', import.meta.url);
const RATE = 48000;
const CHUNK_FRAMES = 1024;
const CHUNK_SECONDS = CHUNK_FRAMES / RATE;

function pageProcessor() {
    const source = readFileSync(PAGE, 'utf8');
    const start = source.indexOf('class PCMProcessor {');
    assert.ok(start >= 0, 'the page declares the class PCMProcessor');
    let depth = 0;
    let end = source.indexOf('{', start);
    do {
        const char = source[end];
        if (char === '{') {
            depth++;
        } else if (char === '}') {
            depth--;
        }
        end++;
    } while (depth > 0);
    const constants = source.slice(source.indexOf('interface PCMProcessorOptions'), start).replace(/interface PCMProcessorOptions \{[^}]*}/, '');
    return stripTypeScriptTypes(`${constants}\n${source.slice(start, end)}\nPCMProcessor;`);
}

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
        const context = this;
        const source = {
            buffer: null,
            connect() {},
            disconnect() {},
            start(when) {
                context.started.push({source, when, duration: source.buffer.duration});
            },
            stop() {
                context.stopped.push(source);
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

function processor(state) {
    const PCMProcessor = vm.runInContext(pageProcessor(), vm.createContext({
        Float32Array, DataView, ArrayBuffer, Math, Date, Set, window: {},
    }), {filename: PAGE.pathname});
    const context = new FakeAudioContext(state);
    return {pcm: new PCMProcessor({encoding: '16bitInt', channels: 2, sampleRate: RATE, audioCtx: context}), context};
}

function chunk() {
    const view = new DataView(new ArrayBuffer(CHUNK_FRAMES * 4));
    for (let frame = 0; frame < CHUNK_FRAMES; frame++) {
        const sample = Math.round(Math.sin(frame / 8) * 1000);
        view.setInt16(frame * 4, sample, true);
        view.setInt16(frame * 4 + 2, sample, true);
    }
    return view.buffer;
}

function feedLive(pcm, context, seconds) {
    const chunks = Math.round(seconds / CHUNK_SECONDS);
    for (let index = 0; index < chunks; index++) {
        context.currentTime += CHUNK_SECONDS;
        pcm.feed(chunk());
    }
}

test('sound that arrives while the context does not play yet does not delay the sound after it', () => {
    for (const starting of ['suspended', 'running']) {
        for (let chunks = Math.round(1 / CHUNK_SECONDS); chunks <= Math.round(1.6 / CHUNK_SECONDS); chunks += 3) {
            const {pcm, context} = processor(starting);
            for (let index = 0; index < chunks; index++) {
                pcm.feed(chunk());
            }
            context.state = 'running';
            feedLive(pcm, context, 2);
            const late = context.queuedAhead();
            assert.ok(late <= 0.21, `${chunks} chunks while ${starting}: the sound plays as late as steady sound, not ${late.toFixed(3)} s`);
        }
    }
});

test('a burst of sound is not kept as a delay for good', () => {
    const {pcm, context} = processor('running');
    feedLive(pcm, context, 1);
    for (let index = 0; index < Math.round(1 / CHUNK_SECONDS); index++) {
        context.currentTime += 0.001;
        pcm.feed(chunk());
    }
    feedLive(pcm, context, 2);
    assert.ok(context.queuedAhead() <= 0.5, `the sound plays at most half a second late, not ${context.queuedAhead().toFixed(3)} s`);
    assert.ok(context.stopped.length > 0, 'what was queued too far ahead was dropped');
});

test('steady sound keeps a short cushion and is never dropped', () => {
    const {pcm, context} = processor('running');
    feedLive(pcm, context, 5);
    assert.ok(context.queuedAhead() > 0, 'sound is queued ahead of the clock');
    assert.ok(context.queuedAhead() <= 0.25, `the cushion is short: ${context.queuedAhead().toFixed(3)} s`);
    assert.deepEqual(context.stopped, [], 'nothing was dropped');
    const scheduled = context.started.reduce((total, entry) => total + entry.duration, 0);
    assert.ok(Math.abs(scheduled + 0.1 - 5) < 0.15, `all of the sound is scheduled: ${scheduled.toFixed(3)} s of 5 s`);
});
