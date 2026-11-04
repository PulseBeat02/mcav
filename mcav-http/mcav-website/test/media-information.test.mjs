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
import { createRandom, randomBoolean, randomInteger, randomJsonValue } from "./fuzz-random.mjs";

const PAGE = new URL("../src/app/page.tsx", import.meta.url);
const ENCODING = "utf8";

const CONSTANT_DECLARATION = /^const [A-Z][A-Z0-9_]*: .+;$/gm;
const DECLARATION_END = "\n};";

const DECLARATIONS = [
    "UNKNOWN_MEDIA_INFORMATION",
    "isText",
    "isCount",
    "toMediaInformation",
    "readMediaInformation",
    "reportMediaInformationFailure",
    "loadMediaInformation"
];

const LINE_SEPARATOR = "\n";
const NAME_SEPARATOR = ", ";

const MEDIA_PATH = "/media";

const ARTIST = "Artist";
const CHANNEL = "Channel";
const UPLOADER_ID = "@uploader";

const FAILURE_MESSAGE = "The network is down!";
const FAILURE_REPORT = "Could not fetch the media information.";

const MEDIA_INFORMATION_KEYS = [
    "title",
    "uploader",
    "channel",
    "uploader_id",
    "description",
    "thumbnail",
    "duration",
    "view_count",
    "like_count",
    "upload_date",
    "webpage_url"
];
const FUZZ_BODY_COUNT = 1000;
const FUZZ_DEPTH = 2;

class FakeServer {
    constructor(answer) {
        this.answer = answer;
        this.requestedPaths = [];
    }

    fetch(path) {
        this.requestedPaths.push(path);

        return this.answer();
    }
}

const sliceDeclaration = (source, name) => {
    const start = source.indexOf(`const ${name}`);
    assert.ok(start >= 0, `the page declares ${name}`);

    const end = source.indexOf(DECLARATION_END, start) + DECLARATION_END.length;
    return source.slice(start, end);
};

const buildMediaInformationScript = () => {
    const source = readFileSync(PAGE, ENCODING);
    const constants = source.match(CONSTANT_DECLARATION).join(LINE_SEPARATOR);
    const declarations = DECLARATIONS.map(name => sliceDeclaration(source, name)).join(LINE_SEPARATOR);
    const pageExports = `({${DECLARATIONS.join(NAME_SEPARATOR)}});`;
    return stripTypeScriptTypes([constants, declarations, pageExports].join(LINE_SEPARATOR));
};

const loadPage = server => {
    const errors = [];
    const context = vm.createContext({
        fetch: path => server.fetch(path),
        console: {error: (...report) => errors.push(report)},
    });
    const page = vm.runInContext(buildMediaInformationScript(), context, {filename: PAGE.pathname});
    return {...page, reportedErrors: () => errors};
};

const serverSending = body => {
    const response = {ok: true, json: () => Promise.resolve(body)};
    return new FakeServer(() => Promise.resolve(response));
};

const serverSendingText = text => {
    const response = {ok: true, json: () => Promise.resolve(text).then(JSON.parse)};
    return new FakeServer(() => Promise.resolve(response));
};

const serverAnsweringWithAnError = () => {
    const response = {ok: false};
    return new FakeServer(() => Promise.resolve(response));
};

const unreachableServer = failure => {
    return new FakeServer(() => Promise.reject(failure));
};

// deepEqual compares prototypes, and the objects the page makes have those of its context
const copyOf = information => ({...information});

const isFilledText = value => {
    return typeof value === "string" && value !== "";
};

const isOptionalText = value => {
    return value === undefined || typeof value === "string";
};

const isNonNegativeNumber = value => {
    return typeof value === "number" && value >= 0;
};

const assertDisplayable = (information, origin) => {
    const texts = [information.title, information.uploader, information.description];
    const optionalTexts = [information.thumbnail, information.upload_date];
    const counts = [information.duration, information.view_count, information.like_count];
    assert.ok(texts.every(isFilledText), `the title, artist and description of ${origin} are text`);
    assert.ok(optionalTexts.every(isOptionalText), `the thumbnail and upload date of ${origin} are text or missing`);
    assert.ok(counts.every(isNonNegativeNumber), `the duration and counts of ${origin} are numbers of at least zero`);
};

const randomBody = random => {
    const value = randomJsonValue(random, MEDIA_INFORMATION_KEYS, FUZZ_DEPTH);
    const text = JSON.stringify(value);
    if (randomBoolean(random)) {
        return text;
    }

    const length = randomInteger(random, text.length);
    return text.slice(0, length);
};

const checkBody = (body, seed) => {
    const page = loadPage(serverSendingText(body));
    const origin = `body ${JSON.stringify(body)} of seed ${seed}`;
    return page.loadMediaInformation()
        .then(information => assertDisplayable(information, origin));
};

test("should request the media information from the media path", () => {
    const server = serverSending({});
    const page = loadPage(server);

    return page.loadMediaInformation()
        .then(() => server.requestedPaths)
        .then(paths => assert.deepEqual(paths, [MEDIA_PATH]));
});

test("should keep the media information the server sends", () => {
    const sent = {
        title: "Song",
        uploader: ARTIST,
        description: "About the song",
        thumbnail: "cover.jpg",
        duration: 61,
        view_count: 2,
        like_count: 1,
        upload_date: "20240102"
    };
    const page = loadPage(serverSending(sent));

    return page.loadMediaInformation()
        .then(copyOf)
        .then(information => assert.deepEqual(information, sent));
});

test("should name the uploader as the artist before the channel and the uploader id", () => {
    const page = loadPage(serverSending({uploader: ARTIST, channel: CHANNEL, uploader_id: UPLOADER_ID}));

    return page.loadMediaInformation()
        .then(information => information.uploader)
        .then(artist => assert.equal(artist, ARTIST));
});

test("should name the channel as the artist when the uploader is missing", () => {
    const page = loadPage(serverSending({channel: CHANNEL, uploader_id: UPLOADER_ID}));

    return page.loadMediaInformation()
        .then(information => information.uploader)
        .then(artist => assert.equal(artist, CHANNEL));
});

test("should name the uploader id as the artist when the uploader and the channel are missing", () => {
    const page = loadPage(serverSending({uploader_id: UPLOADER_ID}));

    return page.loadMediaInformation()
        .then(information => information.uploader)
        .then(artist => assert.equal(artist, UPLOADER_ID));
});

test("should keep only the media information fields that have their declared type", () => {
    const sent = {
        title: {},
        uploader: 1,
        channel: CHANNEL,
        description: [],
        thumbnail: true,
        duration: "61",
        view_count: -2,
        like_count: "1",
        upload_date: 20240102
    };
    const page = loadPage(serverSending(sent));
    const expected = {...page.UNKNOWN_MEDIA_INFORMATION, uploader: CHANNEL};

    return page.loadMediaInformation()
        .then(copyOf)
        .then(information => assert.deepEqual(information, expected));
});

test("should show displayable media information for any body the server sends", () => {
    const seed = Date.now();
    const random = createRandom(seed);
    const bodies = Array.from({length: FUZZ_BODY_COUNT}, () => randomBody(random));

    return bodies.reduce((previous, body) => previous.then(() => checkBody(body, seed)), Promise.resolve());
});

test("should fall back to the unknown media information when the server sends no object", () => {
    const page = loadPage(serverSending(null));

    return page.loadMediaInformation()
        .then(information => assert.deepEqual(information, page.UNKNOWN_MEDIA_INFORMATION));
});

test("should fall back to the unknown media information when the server answers with an error", () => {
    const page = loadPage(serverAnsweringWithAnError());

    return page.loadMediaInformation()
        .then(information => assert.equal(information, page.UNKNOWN_MEDIA_INFORMATION));
});

test("should report no failure when the server answers with an error", () => {
    const page = loadPage(serverAnsweringWithAnError());

    return page.loadMediaInformation()
        .then(page.reportedErrors)
        .then(errors => assert.deepEqual(errors, []));
});

test("should fall back to the unknown media information when the server sends a body that is not json", () => {
    const page = loadPage(serverSendingText("{\"title\":"));

    return page.loadMediaInformation()
        .then(information => assert.equal(information, page.UNKNOWN_MEDIA_INFORMATION))
        .then(page.reportedErrors)
        .then(errors => assert.deepEqual(errors.map(report => report[0]), [FAILURE_REPORT]));
});

test("should fall back to the unknown media information when the request fails", () => {
    const failure = new Error(FAILURE_MESSAGE);
    const page = loadPage(unreachableServer(failure));

    return page.loadMediaInformation()
        .then(information => assert.equal(information, page.UNKNOWN_MEDIA_INFORMATION));
});

test("should report a request that fails", () => {
    const failure = new Error(FAILURE_MESSAGE);
    const page = loadPage(unreachableServer(failure));
    const expected = [[FAILURE_REPORT, failure]];

    return page.loadMediaInformation()
        .then(page.reportedErrors)
        .then(errors => assert.deepEqual(errors, expected));
});
