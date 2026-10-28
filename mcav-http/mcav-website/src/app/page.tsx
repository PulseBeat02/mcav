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

"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import Image from "next/image";
import styles from "./page.module.css";

const MEDIA_PATH: string = "/media";
const AUDIO_PATH: string = "/audio";

const SECURE_PROTOCOL: string = "https:";
const SECURE_SOCKET_SCHEME: string = "wss";
const SOCKET_SCHEME: string = "ws";

const GITHUB_URL: string = "https://github.com/PulseBeat02/mcav";
const DOCUMENTATION_URL: string = "https://mcav.readthedocs.io/en/latest/intro.html";

const STREAM_ENCODING: string = "16bitInt";
const STREAM_CHANNELS: number = 2;
const STREAM_SAMPLE_RATE: number = 48000;
const FLUSHING_TIME_MILLISECONDS: number = 2000;

const BYTES_PER_SAMPLE: number = 2;
const LITTLE_ENDIAN: boolean = true;
const SAMPLE_SCALE: number = 32768.0;

const MAXIMUM_BUFFERED_SAMPLES: number = 480000;
const TRIMMED_BUFFER_SAMPLES: number = MAXIMUM_BUFFERED_SAMPLES / 2;

const IDLE_FLUSH_MILLISECONDS: number = 3000;

const CUSHION_SECONDS: number = 0.1;
const MAXIMUM_LEAD_SECONDS: number = 0.5;

const FADE_IN_FRAMES: number = 64;

const WAVEFORM_POINTS: number = 256;
const WAVEFORM_GAIN: number = 2;
const WAVEFORM_STEP: number = 4;

const PREVIOUS_PEAK_WEIGHT: number = 0.85;
const CURRENT_PEAK_WEIGHT: number = 0.15;

const HUE_STEP_DEGREES: number = 0.5;
const FULL_CIRCLE_DEGREES: number = 360;
const HUE_REDRAW_DEGREES: number = 5;
const MIDDLE_HUE_OFFSET_DEGREES: number = 60;
const END_HUE_OFFSET_DEGREES: number = 120;

const BACKGROUND_COLOR: string = "#000";
const COLOR_SATURATION: string = "100%";
const COLOR_LIGHTNESS: string = "50%";
const FILL_OPACITY: number = 0.2;
const MIDDLE_COLOR_STOP: number = 0.5;
const LINE_WIDTH: number = 2;

const VERTICAL_CENTER: number = 0.5;
const AMPLITUDE_HEIGHT_FRACTION: number = 0.4;

const DEFAULT_PIXEL_RATIO: number = 1;
const MINIMUM_CANVAS_PIXELS: number = 1;

const METADATA_REFRESH_MILLISECONDS: number = 5000;
const HEARTBEAT_MILLISECONDS: number = 30000;
const CONNECT_DELAY_MILLISECONDS: number = 100;
const TITLE_MEASURE_DELAY_MILLISECONDS: number = 100;

const MAXIMUM_RECONNECT_ATTEMPTS: number = 5;
const RECONNECT_BASE_DELAY_MILLISECONDS: number = 1000;
const RECONNECT_BACKOFF_FACTOR: number = 2;
const MAXIMUM_RECONNECT_DELAY_MILLISECONDS: number = 30000;

const MAXIMUM_VOLUME: number = 100;
const DECIMAL_RADIX: number = 10;

const THUMBNAIL_SIZE: number = 128;

const SECONDS_PER_HOUR: number = 3600;
const SECONDS_PER_MINUTE: number = 60;
const TIME_FIELD_LENGTH: number = 2;
const TIME_FIELD_PADDING: string = "0";

const MILLION: number = 1000000;
const THOUSAND: number = 1000;
const COUNT_FRACTION_DIGITS: number = 1;
const MILLIONS_SUFFIX: string = "M";
const THOUSANDS_SUFFIX: string = "K";

const UPLOAD_DATE_PATTERN: RegExp = /(\d{4})(\d{2})(\d{2})/;
const ISO_DATE_REPLACEMENT: string = "$1-$2-$3";
const DATE_LOCALE: string = "en-US";
const DATE_FORMAT: Intl.DateTimeFormatOptions = {year: "numeric", month: "short", day: "numeric"};

const DISCONNECTED_STATUS: string = "Disconnected";
const WAITING_TITLE: string = "Waiting for stream...";
const CONNECT_PROMPT: string = "Connect to start streaming";
const UNKNOWN_TITLE: string = "Unknown Title";
const UNKNOWN_ARTIST: string = "Unknown Artist";
const UNKNOWN_DATE: string = "Unknown date";
const NO_DESCRIPTION: string = "No description available";

type MediaInformation = {
    title?: string;
    uploader?: string;
    channel?: string;
    uploader_id?: string;
    description?: string;
    thumbnail?: string;
    duration?: number;
    view_count?: number;
    like_count?: number;
    upload_date?: string;
};

type PcmProcessorOptions = {
    encoding?: string;
    channels?: number;
    sampleRate?: number;
    flushingTime?: number;
    audioContext?: AudioContext;
};

type VisualizerData = {
    smoothedData: Float32Array;
    hue: number;
};

type WebkitWindow = typeof window & {
    webkitAudioContext: typeof AudioContext;
};

class PcmProcessor {
    private options: Required<PcmProcessorOptions>;
    private samples: Float32Array;
    private audioContext: AudioContext;
    private waveformData: Float32Array;
    private smoothedData: Float32Array;
    public isDestroyed: boolean = false;
    private activeSources: Set<AudioBufferSourceNode> = new Set();
    private hue: number = 0;
    public gainNode: GainNode;
    private startTime: number;
    private scheduledAt: number = -1;
    private processingTimestamp: number;

    constructor(options: PcmProcessorOptions) {
        this.options = {
            encoding: options.encoding || STREAM_ENCODING,
            channels: options.channels || STREAM_CHANNELS,
            sampleRate: options.sampleRate || STREAM_SAMPLE_RATE,
            flushingTime: options.flushingTime || FLUSHING_TIME_MILLISECONDS,
            audioContext: options.audioContext || (
                "webkitAudioContext" in window
                    ? new (window as WebkitWindow).webkitAudioContext()
                    : new AudioContext()
            )
        };
        this.samples = new Float32Array();
        this.audioContext = this.options.audioContext;
        this.waveformData = new Float32Array(WAVEFORM_POINTS);
        this.smoothedData = new Float32Array(WAVEFORM_POINTS);

        this.gainNode = this.audioContext.createGain();
        this.gainNode.connect(this.audioContext.destination);
        this.startTime = this.audioContext.currentTime;
        this.processingTimestamp = Date.now();
    }

    feed(data: ArrayBuffer): void {
        if (this.isDestroyed) {
            return;
        }

        this.flushWhenIdle();
        const decodedSamples: Float32Array = this.decode(data);
        this.updateVisualizer(decodedSamples);
        this.append(decodedSamples);

        if (this.isClockStopped()) {
            this.keepNewestSound();
            return;
        }

        const bufferedFrames: number = this.samples.length / this.options.channels;
        if (bufferedFrames > this.cushionFrames()) {
            this.play();
        }
    }

    private flushWhenIdle(): void {
        const currentTime: number = Date.now();
        if (currentTime - this.processingTimestamp > IDLE_FLUSH_MILLISECONDS) {
            this.flush();
        }

        this.processingTimestamp = currentTime;
    }

    private decode(data: ArrayBuffer): Float32Array {
        const view: DataView = new DataView(data);
        const decodedSamples: Float32Array = new Float32Array(data.byteLength / BYTES_PER_SAMPLE);
        for (let i: number = 0; i < decodedSamples.length; i++) {
            const sample: number = view.getInt16(i * BYTES_PER_SAMPLE, LITTLE_ENDIAN);
            decodedSamples[i] = sample / SAMPLE_SCALE;
        }

        return decodedSamples;
    }

    private updateVisualizer(data: Float32Array): void {
        if (this.isDestroyed) {
            return;
        }

        const samplesPerPoint: number = Math.floor(data.length / this.waveformData.length);
        for (let i: number = 0; i < this.waveformData.length; i++) {
            const peak: number = this.findPeak(data, i * samplesPerPoint, samplesPerPoint);
            this.waveformData[i] = peak * WAVEFORM_GAIN;
            this.smoothedData[i] = this.smoothedData[i] * PREVIOUS_PEAK_WEIGHT + this.waveformData[i] * CURRENT_PEAK_WEIGHT;
        }
    }

    private findPeak(data: Float32Array, start: number, length: number): number {
        const end: number = Math.min(start + length, data.length);
        let peak: number = 0;
        for (let i: number = start; i < end; i++) {
            peak = Math.max(peak, Math.abs(data[i]));
        }

        return peak;
    }

    private append(decodedSamples: Float32Array): void {
        if (this.samples.length > MAXIMUM_BUFFERED_SAMPLES) {
            this.samples = this.samples.slice(-TRIMMED_BUFFER_SAMPLES);
        }

        const newSamples: Float32Array = new Float32Array(this.samples.length + decodedSamples.length);
        newSamples.set(this.samples);
        newSamples.set(decodedSamples, this.samples.length);
        this.samples = newSamples;
    }

    private isClockStopped(): boolean {
        // Chrome reports a starting context as running before its audio device runs
        return this.audioContext.state !== "running" || this.audioContext.currentTime === this.scheduledAt;
    }

    private keepNewestSound(): void {
        const channels: number = this.options.channels;
        const extraFrames: number = Math.floor(this.samples.length / channels) - this.cushionFrames();
        if (extraFrames > 0) {
            this.samples = this.samples.slice(extraFrames * channels);
        }
    }

    private cushionFrames(): number {
        return Math.floor(this.options.sampleRate * CUSHION_SECONDS);
    }

    getVisualizerData(): VisualizerData {
        this.hue = (this.hue + HUE_STEP_DEGREES) % FULL_CIRCLE_DEGREES;
        return {smoothedData: this.smoothedData, hue: this.hue};
    }

    private play(): void {
        if (this.samples.length === 0 || this.isDestroyed) {
            return;
        }

        const channels: number = this.options.channels;
        const length: number = Math.floor(this.samples.length / channels);
        if (length === 0) {
            return;
        }

        const audioBuffer: AudioBuffer = this.audioContext.createBuffer(channels, length, this.options.sampleRate);
        const now: number = this.audioContext.currentTime;
        const late: boolean = this.startTime - now > MAXIMUM_LEAD_SECONDS;
        if (late) {
            this.stopQueued();
        }

        const underrun: boolean = late || this.startTime < now;
        if (underrun) {
            this.startTime = now + CUSHION_SECONDS;
        }

        this.fillChannels(audioBuffer, length);
        if (underrun) {
            this.fadeInChannels(audioBuffer, length);
        }

        this.schedule(audioBuffer);
        this.scheduledAt = now;
        this.samples = this.samples.slice(length * channels);
    }

    private stopQueued(): void {
        for (const source of this.activeSources) {
            this.stopSource(source);
        }

        this.activeSources.clear();
    }

    private stopSource(source: AudioBufferSourceNode): void {
        try {
            source.stop(this.audioContext.currentTime);
        } catch {
            // stop() throws for a source that never started
        }
    }

    private fillChannels(audioBuffer: AudioBuffer, length: number): void {
        const channels: number = this.options.channels;
        for (let channel: number = 0; channel < channels; channel++) {
            const audioData: Float32Array = audioBuffer.getChannelData(channel);
            this.fillChannel(audioData, channel, length);
        }
    }

    private fillChannel(audioData: Float32Array, channel: number, length: number): void {
        const channels: number = this.options.channels;
        let offset: number = channel;
        for (let i: number = 0; i < length; i++) {
            audioData[i] = this.samples[offset];
            offset += channels;
        }
    }

    private fadeInChannels(audioBuffer: AudioBuffer, length: number): void {
        const fade: number = Math.min(FADE_IN_FRAMES, length);
        for (let channel: number = 0; channel < this.options.channels; channel++) {
            const audioData: Float32Array = audioBuffer.getChannelData(channel);
            this.fadeIn(audioData, fade);
        }
    }

    private fadeIn(audioData: Float32Array, fade: number): void {
        for (let i: number = 0; i < fade; i++) {
            audioData[i] = (audioData[i] * i) / fade;
        }
    }

    private schedule(audioBuffer: AudioBuffer): void {
        const bufferSource: AudioBufferSourceNode = this.audioContext.createBufferSource();
        bufferSource.buffer = audioBuffer;
        bufferSource.connect(this.gainNode);
        this.activeSources.add(bufferSource);
        bufferSource.onended = (): boolean => this.activeSources.delete(bufferSource);
        bufferSource.start(this.startTime);
        this.startTime += audioBuffer.duration;
    }

    destroy(): void {
        this.isDestroyed = true;
        for (const source of this.activeSources) {
            this.releaseSource(source);
        }

        this.activeSources.clear();
        this.disconnectGain();
        this.samples = new Float32Array();
        this.startTime = 0;
        this.clearWaveform();
    }

    private releaseSource(source: AudioBufferSourceNode): void {
        try {
            source.stop(this.audioContext.currentTime);
            source.disconnect();
        } catch {
            // stop() throws for a source that never started
        }
    }

    private disconnectGain(): void {
        try {
            this.gainNode.disconnect();
        } catch {
            // the teardown goes on
        }
    }

    private clearWaveform(): void {
        this.waveformData = new Float32Array(WAVEFORM_POINTS);
        this.smoothedData = new Float32Array(WAVEFORM_POINTS);
    }

    flush(): void {
        this.stopQueued();
        this.samples = new Float32Array();
        this.startTime = this.audioContext.currentTime;
        this.clearWaveform();
    }
}

const padTimeField = (value: number): string => {
    return value.toString().padStart(TIME_FIELD_LENGTH, TIME_FIELD_PADDING);
};

const formatDuration = (seconds?: number): string => {
    if (!seconds) {
        return "--:--";
    }

    const hours: number = Math.floor(seconds / SECONDS_PER_HOUR);
    const minutes: number = Math.floor((seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE);
    const remainingSeconds: number = Math.floor(seconds % SECONDS_PER_MINUTE);
    if (hours > 0) {
        return `${hours}:${padTimeField(minutes)}:${padTimeField(remainingSeconds)}`;
    }

    return `${minutes}:${padTimeField(remainingSeconds)}`;
};

const formatNumber = (value?: number): string => {
    if (!value) {
        return "0";
    }

    if (value >= MILLION) {
        const millions: string = (value / MILLION).toFixed(COUNT_FRACTION_DIGITS);
        return `${millions}${MILLIONS_SUFFIX}`;
    }

    if (value >= THOUSAND) {
        const thousands: string = (value / THOUSAND).toFixed(COUNT_FRACTION_DIGITS);
        return `${thousands}${THOUSANDS_SUFFIX}`;
    }

    return value.toString();
};

const formatDate = (dateString?: string): string => {
    if (!dateString) {
        return UNKNOWN_DATE;
    }

    try {
        const isoDate: string = dateString.replace(UPLOAD_DATE_PATTERN, ISO_DATE_REPLACEMENT);
        const date: Date = new Date(isoDate);
        return date.toLocaleDateString(DATE_LOCALE, DATE_FORMAT);
    } catch {
        return UNKNOWN_DATE;
    }
};

const unknownMediaInformation: MediaInformation = {
    title: UNKNOWN_TITLE,
    uploader: UNKNOWN_ARTIST,
    description: NO_DESCRIPTION,
    thumbnail: undefined,
    duration: 0,
    view_count: 0,
    like_count: 0,
    upload_date: undefined
};

const toMediaInformation = (data: unknown): MediaInformation => {
    const received: MediaInformation = typeof data === "object" && data !== null ? data : {};
    return {
        title: received.title || UNKNOWN_TITLE,
        uploader: received.uploader || received.channel || received.uploader_id || UNKNOWN_ARTIST,
        description: received.description || NO_DESCRIPTION,
        thumbnail: received.thumbnail,
        duration: received.duration || 0,
        view_count: received.view_count || 0,
        like_count: received.like_count || 0,
        upload_date: received.upload_date || undefined
    };
};

const readMediaInformation = (response: Response): Promise<MediaInformation> => {
    if (!response.ok) {
        return Promise.resolve(unknownMediaInformation);
    }

    return response.json().then(toMediaInformation);
};

const reportMediaInformationFailure = (error: unknown): MediaInformation => {
    console.error("Could not fetch the media information.", error);
    return unknownMediaInformation;
};

const loadMediaInformation = (): Promise<MediaInformation> => {
    return fetch(MEDIA_PATH)
        .then(readMediaInformation)
        .catch(reportMediaInformationFailure);
};

const isSameMediaInformation = (first: MediaInformation, second: MediaInformation): boolean => {
    return first.title === second.title
        && first.uploader === second.uploader
        && first.description === second.description
        && first.thumbnail === second.thumbnail
        && first.duration === second.duration
        && first.view_count === second.view_count
        && first.like_count === second.like_count
        && first.upload_date === second.upload_date;
};

const reportCloseFailure = (error: unknown): void => {
    console.error("Could not close the audio context.", error);
};

const toHslColor = (hue: number): string => {
    return `hsl(${hue}, ${COLOR_SATURATION}, ${COLOR_LIGHTNESS})`;
};

const createGradient = (context: CanvasRenderingContext2D, width: number, hue: number): CanvasGradient => {
    const gradient: CanvasGradient = context.createLinearGradient(0, 0, width, 0);
    gradient.addColorStop(0, toHslColor(hue));
    gradient.addColorStop(MIDDLE_COLOR_STOP, toHslColor(hue + MIDDLE_HUE_OFFSET_DEGREES));
    gradient.addColorStop(1, toHslColor(hue + END_HUE_OFFSET_DEGREES));
    return gradient;
};

const traceWaveform = (context: CanvasRenderingContext2D, smoothedData: Float32Array, width: number, height: number): void => {
    const centerY: number = height * VERTICAL_CENTER;
    const maximumAmplitude: number = height * AMPLITUDE_HEIGHT_FRACTION;
    const xStep: number = width / WAVEFORM_POINTS;
    context.beginPath();
    context.moveTo(0, centerY);
    for (let i: number = 0; i < WAVEFORM_POINTS; i += WAVEFORM_STEP) {
        const x: number = i * xStep;
        const amplitude: number = smoothedData[i] * maximumAmplitude;
        context.lineTo(x, centerY - amplitude);
    }

    for (let i: number = WAVEFORM_POINTS - 1; i >= 0; i -= WAVEFORM_STEP) {
        const x: number = i * xStep;
        const amplitude: number = smoothedData[i] * maximumAmplitude;
        context.lineTo(x, centerY + amplitude);
    }

    context.closePath();
};

const drawWaveform = (canvas: HTMLCanvasElement, context: CanvasRenderingContext2D, gradient: CanvasGradient, data: VisualizerData): void => {
    context.fillStyle = BACKGROUND_COLOR;
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.shadowBlur = 0;
    context.strokeStyle = gradient;
    context.lineWidth = LINE_WIDTH;
    traceWaveform(context, data.smoothedData, canvas.width, canvas.height);
    context.fillStyle = `hsla(${data.hue}, ${COLOR_SATURATION}, ${COLOR_LIGHTNESS}, ${FILL_OPACITY})`;
    context.fill();
    context.stroke();
};

/**
 * Plays the server's live audio stream with the media's information, a volume control and a waveform visualizer.
 *
 * @returns the player page
 */
const AudioStreamPlayer = (): React.JSX.Element => {
    const [statusText, setStatusText] = useState<string>(DISCONNECTED_STATUS);
    const [statusConnected, setStatusConnected] = useState<boolean>(false);
    const updateStatus = useCallback(
        (text: string, connected: boolean = false): void => {
            setStatusText(text);
            setStatusConnected(connected);
        },
        []
    );
    const [isConnected, setIsConnected] = useState<boolean>(false);
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [volume, setVolume] = useState<number>(MAXIMUM_VOLUME);
    const volumeRef: React.RefObject<number> = useRef(MAXIMUM_VOLUME);
    const streamingRef: React.RefObject<boolean> = useRef(false);
    const connectRef: React.RefObject<() => void> = useRef(() => {});
    const [mediaInformation, setMediaInformation] = useState<MediaInformation>({
        title: WAITING_TITLE,
        uploader: UNKNOWN_ARTIST,
        description: CONNECT_PROMPT,
        duration: 0,
        view_count: 0,
        like_count: 0,
        upload_date: undefined
    });
    const canvasRef: React.RefObject<HTMLCanvasElement | null> = useRef(null);
    const animationIdRef: React.RefObject<number | undefined> = useRef(undefined);
    const audioContextRef: React.RefObject<AudioContext | undefined> = useRef(undefined);
    const socketRef: React.RefObject<WebSocket | undefined> = useRef(undefined);
    const pcmProcessorRef: React.RefObject<PcmProcessor | undefined> = useRef(undefined);
    const metadataIntervalRef: React.RefObject<NodeJS.Timeout | undefined> = useRef(undefined);
    const heartbeatIntervalRef: React.RefObject<NodeJS.Timeout | undefined> = useRef(undefined);
    const reconnectTimeoutRef: React.RefObject<NodeJS.Timeout | undefined> = useRef(undefined);
    const connectTimeoutRef: React.RefObject<NodeJS.Timeout | undefined> = useRef(undefined);
    const reconnectAttemptsRef: React.RefObject<number> = useRef(0);
    const shouldReconnectRef: React.RefObject<boolean> = useRef(false);
    const titleRef: React.RefObject<HTMLHeadingElement | null> = useRef(null);
    const titleWrapperRef: React.RefObject<HTMLDivElement | null> = useRef(null);
    const placeholderRef: React.RefObject<HTMLDivElement | null> = useRef(null);
    const [failedThumbnail, setFailedThumbnail] = useState<string | undefined>(undefined);
    const mediaInformationRequestRef: React.RefObject<Promise<MediaInformation> | undefined> = useRef(undefined);

    const requestMediaInformation = useCallback((): Promise<MediaInformation> => {
        if (!mediaInformationRequestRef.current) {
            mediaInformationRequestRef.current = loadMediaInformation().finally(() => {
                mediaInformationRequestRef.current = undefined;
            });
        }
        return mediaInformationRequestRef.current;
    }, []);

    const showMediaInformation = useCallback((information: MediaInformation): void => {
        setMediaInformation((previous: MediaInformation) => isSameMediaInformation(previous, information) ? previous : information);
    }, []);

    const fetchMediaInformation = useCallback((): void => {
        requestMediaInformation().then(showMediaInformation);
    }, [requestMediaInformation, showMediaInformation]);

    const startMetadataRefresh = useCallback((): void => {
        if (metadataIntervalRef.current) {
            clearInterval(metadataIntervalRef.current);
        }
        fetchMediaInformation();
        metadataIntervalRef.current = setInterval(fetchMediaInformation, METADATA_REFRESH_MILLISECONDS);
    }, [fetchMediaInformation]);

    const stopMetadataRefresh = useCallback((): void => {
        if (metadataIntervalRef.current) {
            clearInterval(metadataIntervalRef.current);
            metadataIntervalRef.current = undefined;
        }
    }, []);

    const cancelAnimation = useCallback((): void => {
        if (animationIdRef.current !== undefined) {
            cancelAnimationFrame(animationIdRef.current);
            animationIdRef.current = undefined;
        }
    }, []);

    const animateVisualizer = useCallback((): void => {
        const canvas: HTMLCanvasElement | null = canvasRef.current;
        const context: CanvasRenderingContext2D | null | undefined = canvas?.getContext("2d");
        const processor: PcmProcessor | undefined = pcmProcessorRef.current;
        if (!canvas || !context || !processor) {
            return;
        }

        cancelAnimation();
        let gradient: CanvasGradient | undefined;
        let lastHue: number = -1;
        let lastWidth: number = -1;
        const draw = (): void => {
            // a dispatched frame can outlive its cancellation
            if (pcmProcessorRef.current !== processor) {
                return;
            }

            if (!canvasRef.current || processor.isDestroyed) {
                animationIdRef.current = undefined;
                return;
            }

            const data: VisualizerData = processor.getVisualizerData();
            if (!gradient || Math.abs(data.hue - lastHue) > HUE_REDRAW_DEGREES || canvas.width !== lastWidth) {
                gradient = createGradient(context, canvas.width, data.hue);
                lastHue = data.hue;
                lastWidth = canvas.width;
            }

            drawWaveform(canvas, context, gradient, data);
            animationIdRef.current = requestAnimationFrame(draw);
        };

        animationIdRef.current = requestAnimationFrame(draw);
    }, [cancelAnimation]);

    const sendHeartbeat = useCallback((): void => {
        if (socketRef.current?.readyState !== WebSocket.OPEN) {
            return;
        }

        try {
            socketRef.current.send(new ArrayBuffer(0));
        } catch (exception: unknown) {
            console.error("Could not send the heartbeat.", exception);
        }
    }, []);

    const startHeartbeat = useCallback((): void => {
        if (heartbeatIntervalRef.current) {
            clearInterval(heartbeatIntervalRef.current);
        }

        heartbeatIntervalRef.current = setInterval(sendHeartbeat, HEARTBEAT_MILLISECONDS);
    }, [sendHeartbeat]);

    const stopHeartbeat = useCallback((): void => {
        if (heartbeatIntervalRef.current) {
            clearInterval(heartbeatIntervalRef.current);
            heartbeatIntervalRef.current = undefined;
        }
    }, []);

    const reconnect = useCallback((): void => {
        reconnectTimeoutRef.current = undefined;
        if (shouldReconnectRef.current && audioContextRef.current?.state === "running") {
            connectRef.current();
        }
    }, []);

    const attemptReconnect = useCallback((): void => {
        if (reconnectTimeoutRef.current) {
            return;
        }

        reconnectAttemptsRef.current++;

        if (reconnectAttemptsRef.current > MAXIMUM_RECONNECT_ATTEMPTS) {
            updateStatus("Maximum reconnection attempts reached");
            shouldReconnectRef.current = false;
            setIsConnected(false);
            return;
        }

        const backoff: number = Math.pow(RECONNECT_BACKOFF_FACTOR, reconnectAttemptsRef.current - 1);
        const delay: number = Math.min(RECONNECT_BASE_DELAY_MILLISECONDS * backoff, MAXIMUM_RECONNECT_DELAY_MILLISECONDS);
        reconnectTimeoutRef.current = setTimeout(reconnect, delay);
    }, [reconnect, updateStatus]);

    const cancelReconnect = useCallback((): void => {
        if (reconnectTimeoutRef.current) {
            clearTimeout(reconnectTimeoutRef.current);
            reconnectTimeoutRef.current = undefined;
        }
    }, []);

    const cancelConnect = useCallback((): void => {
        if (connectTimeoutRef.current) {
            clearTimeout(connectTimeoutRef.current);
            connectTimeoutRef.current = undefined;
        }
    }, []);

    const closeSocket = useCallback((): void => {
        if (socketRef.current) {
            socketRef.current.close();
            socketRef.current = undefined;
        }
    }, []);

    const destroyProcessor = useCallback((): void => {
        if (pcmProcessorRef.current) {
            pcmProcessorRef.current.destroy();
            pcmProcessorRef.current = undefined;
        }
    }, []);

    const clearVisualizer = useCallback((): void => {
        const canvas: HTMLCanvasElement | null = canvasRef.current;
        const context: CanvasRenderingContext2D | null | undefined = canvas?.getContext("2d");
        if (canvas && context) {
            context.clearRect(0, 0, canvas.width, canvas.height);
        }
    }, []);

    const hideVisualizer = useCallback((): void => {
        if (placeholderRef.current) {
            placeholderRef.current.style.display = "block";
        }

        if (canvasRef.current) {
            canvasRef.current.style.display = "none";
        }
    }, []);

    const stopStream = useCallback((): void => {
        shouldReconnectRef.current = false;
        streamingRef.current = false;
        cancelReconnect();
        cancelConnect();
        closeSocket();
        destroyProcessor();
        cancelAnimation();
        stopMetadataRefresh();
        stopHeartbeat();

        setIsConnected(false);
        setIsLoading(false);
        updateStatus(DISCONNECTED_STATUS);
        clearVisualizer();
        hideVisualizer();
    }, [
        cancelAnimation, cancelConnect, cancelReconnect, clearVisualizer, closeSocket, destroyProcessor, hideVisualizer,
        stopHeartbeat, stopMetadataRefresh, updateStatus
    ]);

    const showVisualizer = useCallback((): void => {
        if (placeholderRef.current) {
            placeholderRef.current.style.display = "none";
        }

        if (canvasRef.current) {
            canvasRef.current.style.display = "block";
        }
    }, []);

    const startConnectedStream = useCallback((socket: WebSocket): void => {
        if (socketRef.current !== socket) {
            return;
        }

        updateStatus("Connected", true);
        streamingRef.current = false;
        setIsConnected(true);
        shouldReconnectRef.current = true;
        setIsLoading(false);
        startHeartbeat();
        showVisualizer();
        reconnectAttemptsRef.current = 0;
        animateVisualizer();
        cancelReconnect();
    }, [animateVisualizer, cancelReconnect, showVisualizer, startHeartbeat, updateStatus]);

    const feedProcessor = useCallback((data: ArrayBuffer): void => {
        const processor: PcmProcessor | undefined = pcmProcessorRef.current;
        const hasSound: boolean = data.byteLength > 0;
        if (!hasSound || !processor || processor.isDestroyed) {
            return;
        }

        if (!streamingRef.current) {
            streamingRef.current = true;
            updateStatus("Streaming audio", true);
        }

        processor.feed(data);
    }, [updateStatus]);

    const playReceivedSound = useCallback((socket: WebSocket, event: MessageEvent): void => {
        if (socketRef.current !== socket) {
            return;
        }

        try {
            feedProcessor(event.data);
        } catch (exception: unknown) {
            console.error("Could not play the received sound.", exception);
            pcmProcessorRef.current?.flush();
        }
    }, [feedProcessor]);

    const reconnectOrStopAfterError = useCallback((socket: WebSocket, event: Event): void => {
        if (socketRef.current !== socket) {
            return;
        }

        console.error("The audio connection failed.", event);
        setIsLoading(false);
        if (shouldReconnectRef.current) {
            updateStatus("Connection error - reconnecting...");
            attemptReconnect();
            return;
        }

        updateStatus("Connection error");
        stopStream();
    }, [attemptReconnect, stopStream, updateStatus]);

    const reconnectOrStopAfterClose = useCallback((socket: WebSocket): void => {
        if (socketRef.current !== socket) {
            return;
        }

        streamingRef.current = false;
        stopHeartbeat();
        setIsLoading(false);
        if (shouldReconnectRef.current) {
            updateStatus("Disconnected - reconnecting...");
            attemptReconnect();
            return;
        }

        updateStatus(DISCONNECTED_STATUS);
        stopStream();
    }, [attemptReconnect, stopHeartbeat, stopStream, updateStatus]);

    const openSocket = useCallback((): void => {
        const scheme: string = window.location.protocol === SECURE_PROTOCOL ? SECURE_SOCKET_SCHEME : SOCKET_SCHEME;
        const endpoint: string = `${scheme}://${window.location.host}${AUDIO_PATH}`;
        const socket: WebSocket = new WebSocket(endpoint);
        socket.binaryType = "arraybuffer";
        socketRef.current = socket;
        // each handler ignores a replaced socket, whose late close would reconnect next to the new stream
        socket.onopen = (): void => startConnectedStream(socket);
        socket.onmessage = (event: MessageEvent): void => playReceivedSound(socket, event);
        socket.onerror = (event: Event): void => reconnectOrStopAfterError(socket, event);
        socket.onclose = (): void => reconnectOrStopAfterClose(socket);
    }, [playReceivedSound, reconnectOrStopAfterClose, reconnectOrStopAfterError, startConnectedStream]);

    const openStream = useCallback((): void => {
        connectTimeoutRef.current = undefined;
        try {
            setIsLoading(true);
            updateStatus("Connecting...");
            pcmProcessorRef.current = new PcmProcessor({
                encoding: STREAM_ENCODING,
                channels: STREAM_CHANNELS,
                sampleRate: STREAM_SAMPLE_RATE,
                flushingTime: FLUSHING_TIME_MILLISECONDS,
                audioContext: audioContextRef.current!
            });
            pcmProcessorRef.current.gainNode.gain.value = volumeRef.current / MAXIMUM_VOLUME;
            openSocket();
        } catch (exception: unknown) {
            console.error("Could not start the stream.", exception);
            updateStatus(`Error: ${(exception as Error).message}`);
            setIsLoading(false);
            if (shouldReconnectRef.current) {
                attemptReconnect();
            }
        }
    }, [attemptReconnect, openSocket, updateStatus]);

    const connectWebSocket = useCallback((): void => {
        cancelAnimation();
        destroyProcessor();
        cancelConnect();
        startMetadataRefresh();
        connectTimeoutRef.current = setTimeout(openStream, CONNECT_DELAY_MILLISECONDS);
    }, [cancelAnimation, cancelConnect, destroyProcessor, openStream, startMetadataRefresh]);

    useEffect(() => {
        connectRef.current = connectWebSocket;
    }, [connectWebSocket]);

    const connectAfterResume = useCallback((): void => {
        updateStatus("Audio permission granted!");
        if (audioContextRef.current?.state === "running") {
            connectWebSocket();
            return;
        }

        shouldReconnectRef.current = false;
        setIsLoading(false);
    }, [connectWebSocket, updateStatus]);

    const reportPermissionFailure = useCallback((error: unknown): void => {
        shouldReconnectRef.current = false;
        setIsLoading(false);
        updateStatus(`Failed to get audio permission: ${error}`);
    }, [updateStatus]);

    const startPlayback = useCallback((): void => {
        if (shouldReconnectRef.current) {
            return;
        }

        shouldReconnectRef.current = true;
        reconnectAttemptsRef.current = 0;
        setIsLoading(true);
        destroyProcessor();
        if (audioContextRef.current) {
            audioContextRef.current.close().catch(reportCloseFailure);
            audioContextRef.current = undefined;
        }

        const audioContext: AudioContext = "webkitAudioContext" in window
            ? new (window as WebkitWindow).webkitAudioContext()
            : new AudioContext();
        audioContextRef.current = audioContext;
        updateStatus(`Audio permission: ${audioContext.state}`);
        if (audioContext.state === "running") {
            connectWebSocket();
            return;
        }

        audioContext.resume()
            .then(connectAfterResume)
            .catch(reportPermissionFailure);
    }, [connectAfterResume, connectWebSocket, destroyProcessor, reportPermissionFailure, updateStatus]);

    const stopPlayback = useCallback((): void => {
        shouldReconnectRef.current = false;
        stopStream();
    }, [stopStream]);

    const changeVolume = useCallback((event: React.ChangeEvent<HTMLInputElement>): void => {
        const newVolume: number = parseInt(event.target.value, DECIMAL_RADIX);
        volumeRef.current = newVolume;
        setVolume(newVolume);
        if (pcmProcessorRef.current?.gainNode) {
            pcmProcessorRef.current.gainNode.gain.value = newVolume / MAXIMUM_VOLUME;
        }
    }, []);

    const updateTitleScrolling = useCallback((): void => {
        const title: HTMLHeadingElement | null = titleRef.current;
        const wrapper: HTMLDivElement | null = titleWrapperRef.current;
        if (!title || !wrapper) {
            return;
        }

        const titleWidth: number = title.scrollWidth;
        const wrapperWidth: number = wrapper.clientWidth;
        const overflowing: boolean = titleWidth > wrapperWidth && wrapperWidth > 0;
        title.classList.toggle(styles.scrolling, overflowing);
    }, []);

    const resizeCanvas = useCallback((): void => {
        const canvas: HTMLCanvasElement | null = canvasRef.current;
        const container: HTMLElement | null | undefined = canvas?.parentElement;
        if (!canvas || !container) {
            return;
        }

        const bounds: DOMRect = container.getBoundingClientRect();
        const ratio: number = window.devicePixelRatio || DEFAULT_PIXEL_RATIO;
        canvas.width = Math.max(MINIMUM_CANVAS_PIXELS, Math.round(bounds.width * ratio));
        canvas.height = Math.max(MINIMUM_CANVAS_PIXELS, Math.round(bounds.height * ratio));
    }, []);

    const clearPendingTimers = useCallback((): void => {
        if (metadataIntervalRef.current) {
            clearInterval(metadataIntervalRef.current);
        }

        if (heartbeatIntervalRef.current) {
            clearInterval(heartbeatIntervalRef.current);
        }

        if (reconnectTimeoutRef.current) {
            clearTimeout(reconnectTimeoutRef.current);
        }

        if (connectTimeoutRef.current) {
            clearTimeout(connectTimeoutRef.current);
        }
    }, []);

    const releasePlayer = useCallback((): void => {
        shouldReconnectRef.current = false;
        cancelAnimation();
        clearPendingTimers();
        if (socketRef.current?.readyState === WebSocket.OPEN) {
            socketRef.current.close();
        }

        pcmProcessorRef.current?.destroy();
        audioContextRef.current?.close().catch(reportCloseFailure);
    }, [cancelAnimation, clearPendingTimers]);

    useEffect(() => {
        let unmounted: boolean = false;
        const showWhileMounted = (information: MediaInformation): void => {
            if (!unmounted) {
                setMediaInformation(information);
            }
        };

        requestMediaInformation().then(showWhileMounted);
        const resizeObserver: ResizeObserver = new ResizeObserver(resizeCanvas);
        const canvasContainer: HTMLElement | null | undefined = canvasRef.current?.parentElement;
        if (canvasContainer) {
            resizeObserver.observe(canvasContainer);
        }

        resizeCanvas();
        const release = (): void => {
            unmounted = true;
            resizeObserver.disconnect();
            releasePlayer();
        };
        return release;
    }, [releasePlayer, requestMediaInformation, resizeCanvas]);

    useEffect(() => {
        if (mediaInformation.title && mediaInformation.title !== WAITING_TITLE) {
            setTimeout(updateTitleScrolling, TITLE_MEASURE_DELAY_MILLISECONDS);
        }
    }, [mediaInformation.title, updateTitleScrolling]);

    return (
        <div className={styles.container}>
            <div className={styles.playerContainer}>
                <div className={styles.playerHeader}>
                    <h1>Audio Stream Player</h1>
                </div>

                <div
                    className={`${styles.mediaInformation} ${mediaInformation.title && mediaInformation.title !== WAITING_TITLE ? styles.loaded : ""}`}>
                    <div className={styles.mediaThumbnail}>
                        {mediaInformation.thumbnail && mediaInformation.thumbnail !== failedThumbnail ? (
                            <Image
                                src={mediaInformation.thumbnail}
                                alt={mediaInformation.title || "Media thumbnail"}
                                width={THUMBNAIL_SIZE}
                                height={THUMBNAIL_SIZE}
                                unoptimized
                                onError={() => setFailedThumbnail(mediaInformation.thumbnail)}
                                className={styles.mediaThumbnailImage}
                            />
                        ) : (
                            <span className={styles.placeholderIcon}>♫</span>
                        )}
                    </div>
                    <div className={styles.mediaDetails}>
                        <div className={styles.mediaTitleWrapper} ref={titleWrapperRef}>
                            <h2 className={styles.mediaTitle} ref={titleRef}>
                                {mediaInformation.title || WAITING_TITLE}
                            </h2>
                        </div>
                        <p className={styles.mediaUploader}>
                            {mediaInformation.uploader || mediaInformation.channel || mediaInformation.uploader_id || UNKNOWN_ARTIST}
                        </p>
                        <div className={styles.mediaMetadata}>
                            <div className={styles.metadataItem}>
                                <span className={styles.metadataIcon}>🕑</span>
                                <span>{formatDuration(mediaInformation.duration)}</span>
                            </div>
                            <div className={styles.metadataItem}>
                                <span className={styles.metadataIcon}>👁</span>
                                <span>{formatNumber(mediaInformation.view_count)} views</span>
                            </div>
                            <div className={styles.metadataItem}>
                                <span className={styles.metadataIcon}>👍</span>
                                <span>{formatNumber(mediaInformation.like_count)} likes</span>
                            </div>
                            <div className={styles.metadataItem}>
                                <span className={styles.metadataIcon}>📅</span>
                                <span>{formatDate(mediaInformation.upload_date)}</span>
                            </div>
                        </div>
                        <p className={styles.mediaDescription}>
                            {mediaInformation.description || CONNECT_PROMPT}
                        </p>
                    </div>
                </div>

                <div className={styles.visualizer}>
                    <div ref={placeholderRef} className={styles.visualizerPlaceholder}>
                        Connect to start visualization
                    </div>
                    <canvas
                        ref={canvasRef}
                        className={styles.visualizerCanvas}
                        style={{ display: "none" }}
                    />
                </div>

                <div className={styles.volumeControl}>
                    <div className={styles.volumeIcon}>🔊</div>
                    <input
                        type="range"
                        min="0"
                        max={MAXIMUM_VOLUME}
                        value={volume}
                        onChange={changeVolume}
                        className={styles.volumeSlider}
                    />
                </div>

                <div className={styles.controls}>
                    <button
                        onClick={startPlayback}
                        disabled={isConnected || isLoading}
                        className={styles.button}
                    >
                        Start
                    </button>
                    <button
                        onClick={stopPlayback}
                        disabled={!isConnected}
                        className={styles.button}
                    >
                        Stop
                    </button>
                </div>

                <div className={styles.statusPanel}>
                    <div className={`${styles.status} ${statusConnected ? styles.statusConnected : ""}`}>
                        {isLoading && <span className={styles.loadingSpinner}></span>}
                        {statusText}
                    </div>
                </div>

                <div className={styles.footerSection}>
                    <a
                        href={GITHUB_URL}
                        className={styles.footerLink}
                        title="View on GitHub"
                    >
                        <svg viewBox="0 0 24 24" fill="currentColor">
                            <path
                                d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/>
                        </svg>
                        <span>GitHub</span>
                    </a>
                    <a
                        href={DOCUMENTATION_URL}
                        className={styles.footerLink}
                        title="Documentation"
                    >
                        <svg viewBox="0 0 24 24" fill="currentColor">
                            <path
                                d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z"/>
                        </svg>
                        <span>Docs</span>
                    </a>
                </div>
            </div>
        </div>
    );
};

export default AudioStreamPlayer;
