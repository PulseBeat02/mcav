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
const STATE_INCREMENT = 0x6d2b79f5;
const STATE_RANGE = 2 ** 32;

const FIRST_SHIFT = 15;
const SECOND_SHIFT = 7;
const THIRD_SHIFT = 14;
const SECOND_MULTIPLIER = 61;

const HALF = 0.5;

const MAXIMUM_NUMBER = 1000;
const MAXIMUM_LENGTH = 4;

const TEXT_CHARACTERS = "aZ09 .:/-_\"\\é";

/**
 * Creates a seeded source of random numbers (mulberry32), so a failing seed can be replayed.
 *
 * @param seed the seed, of which the low 32 bits count
 * @returns a function that returns the next number in [0, 1)
 */
export const createRandom = seed => {
    let state = seed >>> 0;
    const next = () => {
        state = (state + STATE_INCREMENT) >>> 0;
        const mixed = Math.imul(state ^ (state >>> FIRST_SHIFT), state | 1);
        const remixed = mixed ^ (mixed + Math.imul(mixed ^ (mixed >>> SECOND_SHIFT), mixed | SECOND_MULTIPLIER));
        return ((remixed ^ (remixed >>> THIRD_SHIFT)) >>> 0) / STATE_RANGE;
    };
    return next;
};

/**
 * Returns a random integer from zero up to, but not including, the limit.
 *
 * @param random the source of random numbers
 * @param limit the exclusive upper bound
 * @returns the integer
 */
export const randomInteger = (random, limit) => {
    return Math.floor(random() * limit);
};

/**
 * Returns true or false, each half of the time.
 *
 * @param random the source of random numbers
 * @returns the boolean
 */
export const randomBoolean = random => {
    return random() < HALF;
};

const randomSignedInteger = random => {
    return randomInteger(random, MAXIMUM_NUMBER) - randomInteger(random, MAXIMUM_NUMBER);
};

const randomFraction = random => {
    return (random() - random()) * MAXIMUM_NUMBER;
};

const randomCharacter = random => {
    const index = randomInteger(random, TEXT_CHARACTERS.length);
    return TEXT_CHARACTERS[index];
};

const randomText = random => {
    const length = randomInteger(random, MAXIMUM_LENGTH + 1);
    return Array.from({length}, () => randomCharacter(random)).join("");
};

const SCALARS = [() => null, randomBoolean, randomSignedInteger, randomFraction, randomText];

const randomArray = (random, keys, depth) => {
    const length = randomInteger(random, MAXIMUM_LENGTH + 1);
    return Array.from({length}, () => randomJsonValue(random, keys, depth - 1));
};

const randomEntry = (random, keys, depth) => {
    const key = keys[randomInteger(random, keys.length)];
    const value = randomJsonValue(random, keys, depth - 1);
    return [key, value];
};

const randomObject = (random, keys, depth) => {
    const length = randomInteger(random, MAXIMUM_LENGTH + 1);
    const entries = Array.from({length}, () => randomEntry(random, keys, depth));
    return Object.fromEntries(entries);
};

/**
 * Returns a random value that JSON can hold: null, a boolean, a number, a string, or, while depth is left, an array or
 * an object whose keys are drawn from the given keys.
 *
 * @param random the source of random numbers
 * @param keys the keys that objects may have
 * @param depth how many levels of arrays and objects may still nest
 * @returns the value
 */
export const randomJsonValue = (random, keys, depth) => {
    const kinds = depth > 0 ? [...SCALARS, randomArray, randomObject] : SCALARS;
    const kind = kinds[randomInteger(random, kinds.length)];
    return kind(random, keys, depth);
};
