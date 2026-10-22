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

const CUSHION_SECONDS = 0.1;
const MAXIMUM_LEAD_SECONDS = 0.5;

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
    private maximumBufferSize: number = 480000;

    constructor(options: PcmProcessorOptions) {
        this.options = {
            encoding: options.encoding || "16bitInt",
            channels: options.channels || 2,
            sampleRate: options.sampleRate || 48000,
            flushingTime: options.flushingTime || 2000,
            audioContext: options.audioContext || (
                "webkitAudioContext" in window
                    ? new (window as WebkitWindow).webkitAudioContext()
                    : new AudioContext()
            )
        };
        this.samples = new Float32Array();
        this.audioContext = this.options.audioContext;
        this.waveformData = new Float32Array(256);
        this.smoothedData = new Float32Array(256);

        this.gainNode = this.audioContext.createGain();
        this.gainNode.connect(this.audioContext.destination);
        this.startTime = this.audioContext.currentTime;
        this.processingTimestamp = Date.now();
    }

    feed(data: ArrayBuffer): void {
        if (this.isDestroyed) return;

        const currentTime = Date.now();
        if (currentTime - this.processingTimestamp > 3000) {
            this.flush();
        }
        this.processingTimestamp = currentTime;

        const view = new DataView(data);
        const decodedSamples = new Float32Array(data.byteLength / 2);
        for (let i = 0; i < decodedSamples.length; i++) {
            const sample = view.getInt16(i * 2, true);
            decodedSamples[i] = sample / 32768.0;
        }

        this.updateVisualizer(decodedSamples);

        if (this.samples.length > this.maximumBufferSize) {
            const keepSamples = Math.floor(this.maximumBufferSize / 2);
            this.samples = this.samples.slice(-keepSamples);
        }

        const newSamples = new Float32Array(this.samples.length + decodedSamples.length);
        newSamples.set(this.samples);
        newSamples.set(decodedSamples, this.samples.length);
        this.samples = newSamples;

        const channels = this.options.channels;
        const cushion = Math.floor(this.options.sampleRate * CUSHION_SECONDS);
        if (this.audioContext.state !== "running" || this.audioContext.currentTime === this.scheduledAt) {
            // a context that does not play yet, or whose clock has not moved since sound was last scheduled (Chrome
            // reports a starting context as running before its audio device runs), keeps only the newest sound, which
            // plays once it runs; whole frames are dropped, so the channels stay aligned
            const extraFrames = Math.floor(this.samples.length / channels) - cushion;
            if (extraFrames > 0) {
                this.samples = this.samples.slice(extraFrames * channels);
            }
            return;
        }
        if (this.samples.length / channels > cushion) {
            this.play();
        }
    }

    private updateVisualizer(data: Float32Array): void {
        if (this.isDestroyed) return;

        const samplesPerPoint = Math.floor(data.length / this.waveformData.length);
        for (let i = 0; i < this.waveformData.length; i++) {
            let peak = 0;
            for (let j = 0; j < samplesPerPoint; j++) {
                const index = i * samplesPerPoint + j;
                if (index < data.length) {
                    peak = Math.max(peak, Math.abs(data[index]));
                }
            }
            this.waveformData[i] = peak * 2;
            this.smoothedData[i] = this.smoothedData[i] * 0.85 + this.waveformData[i] * 0.15;
        }
    }

    getVisualizerData(): VisualizerData {
        this.hue = (this.hue + 0.5) % 360;
        return {smoothedData: this.smoothedData, hue: this.hue};
    }

    private play(): void {
        if (this.samples.length === 0 || this.isDestroyed) return;

        const channels = this.options.channels;
        const length = Math.floor(this.samples.length / channels);
        if (length === 0) return;
        const audioBuffer = this.audioContext.createBuffer(channels, length, this.options.sampleRate);

        const now = this.audioContext.currentTime;
        const late = this.startTime - now > MAXIMUM_LEAD_SECONDS;
        if (late) {
            this.stopQueued();
        }
        const underrun = late || this.startTime < now;
        if (underrun) {
            this.startTime = now + CUSHION_SECONDS;
        }

        for (let channel = 0; channel < channels; channel++) {
            const audioData = audioBuffer.getChannelData(channel);
            let offset = channel;
            for (let i = 0; i < length; i++) {
                audioData[i] = this.samples[offset];
                offset += channels;
            }
            if (underrun) {
                const fade = Math.min(64, length);
                for (let i = 0; i < fade; i++) {
                    audioData[i] = (audioData[i] * i) / fade;
                }
            }
        }

        const bufferSource = this.audioContext.createBufferSource();
        bufferSource.buffer = audioBuffer;
        bufferSource.connect(this.gainNode);
        this.activeSources.add(bufferSource);
        bufferSource.onended = () => {
            this.activeSources.delete(bufferSource);
        };
        bufferSource.start(this.startTime);
        this.startTime += audioBuffer.duration;
        this.scheduledAt = now;
        this.samples = this.samples.slice(length * channels);
    }

    private stopQueued(): void {
        this.activeSources.forEach(source => {
            try {
                source.stop(this.audioContext.currentTime);
            } catch {
            }
        });
        this.activeSources.clear();
    }

    destroy(): void {
        this.isDestroyed = true;

        this.activeSources.forEach(source => {
            try {
                source.stop(this.audioContext.currentTime);
                source.disconnect();
            } catch {
            }
        });
        this.activeSources.clear();

        if (this.gainNode) {
            try {
                this.gainNode.disconnect();
            } catch {
            }
        }

        this.samples = new Float32Array();
        this.startTime = 0;
        this.waveformData = new Float32Array(256);
        this.smoothedData = new Float32Array(256);
    }

    flush(): void {
        this.activeSources.forEach(source => {
            try {
                source.stop(this.audioContext.currentTime);
            } catch {
            }
        });
        this.activeSources.clear();

        this.samples = new Float32Array();
        this.startTime = this.audioContext.currentTime;
        this.waveformData = new Float32Array(256);
        this.smoothedData = new Float32Array(256);
    }
}

const formatDuration = (seconds?: number): string => {
    if (!seconds || seconds === 0) return "--:--";
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    if (hours > 0) {
        return `${hours}:${minutes.toString().padStart(2, "0")}:${remainingSeconds.toString().padStart(2, "0")}`;
    }
    return `${minutes}:${remainingSeconds.toString().padStart(2, "0")}`;
};

const formatNumber = (value?: number): string => {
    if (!value || value === 0) return "0";
    if (value >= 1000000) {
        return (value / 1000000).toFixed(1) + "M";
    }
    if (value >= 1000) {
        return (value / 1000).toFixed(1) + "K";
    }
    return value.toString();
};

const formatDate = (dateString?: string): string => {
    if (!dateString) return "Unknown date";
    try {
        const date = new Date(dateString.replace(/(\d{4})(\d{2})(\d{2})/, "$1-$2-$3"));
        return date.toLocaleDateString("en-US", {year: "numeric", month: "short", day: "numeric"});
    } catch {
        return "Unknown date";
    }
};

const unknownMediaInformation: MediaInformation = {
    title: "Unknown Title",
    uploader: "Unknown Artist",
    description: "No description available",
    thumbnail: undefined,
    duration: 0,
    view_count: 0,
    like_count: 0,
    upload_date: undefined
};

const loadMediaInformation = async (): Promise<MediaInformation> => {
    try {
        const response = await fetch("/media");
        if (!response.ok) {
            return unknownMediaInformation;
        }
        const data = await response.json();
        return {
            title: data?.title || "Unknown Title",
            uploader: data?.uploader || data?.channel || data?.uploader_id || "Unknown Artist",
            description: data?.description || "No description available",
            thumbnail: data?.thumbnail,
            duration: data?.duration || 0,
            view_count: data?.view_count || 0,
            like_count: data?.like_count || 0,
            upload_date: data?.upload_date || undefined
        };
    } catch (exception) {
        console.error("Error fetching media information:", exception);
        return unknownMediaInformation;
    }
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

const AudioStreamPlayer = (): React.JSX.Element => {
    const [statusText, setStatusText] = useState("Disconnected");
    const [statusConnected, setStatusConnected] = useState(false);
    const updateStatus = useCallback(
        (text: string, connected = false) => {
            setStatusText(text);
            setStatusConnected(connected);
        },
        []
    );
    const [isConnected, setIsConnected] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [volume, setVolume] = useState(100);
    const volumeRef = useRef(100);
    const streamingRef = useRef(false);
    const connectRef = useRef<() => void>(() => {});
    const [mediaInformation, setMediaInformation] = useState<MediaInformation>({
        title: "Waiting for stream...",
        uploader: "Unknown Artist",
        description: "Connect to start streaming",
        duration: 0,
        view_count: 0,
        like_count: 0,
        upload_date: undefined
    });
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const animationIdRef = useRef<number | undefined>(undefined);
    const audioContextRef = useRef<AudioContext | undefined>(undefined);
    const socketRef = useRef<WebSocket | undefined>(undefined);
    const pcmProcessorRef = useRef<PcmProcessor | undefined>(undefined);
    const metadataIntervalRef = useRef<NodeJS.Timeout | undefined>(undefined);
    const heartbeatIntervalRef = useRef<NodeJS.Timeout | undefined>(undefined);
    const reconnectTimeoutRef = useRef<NodeJS.Timeout | undefined>(undefined);
    const connectTimeoutRef = useRef<NodeJS.Timeout | undefined>(undefined);
    const reconnectAttemptsRef = useRef(0);
    const shouldReconnectRef = useRef(false);
    const titleRef = useRef<HTMLHeadingElement>(null);
    const titleWrapperRef = useRef<HTMLDivElement>(null);
    const placeholderRef = useRef<HTMLDivElement>(null);
    const [failedThumbnail, setFailedThumbnail] = useState<string | undefined>(undefined);
    const mediaInformationRequestRef = useRef<Promise<MediaInformation> | undefined>(undefined);

    const maximumReconnectAttempts = 5;

    const requestMediaInformation = useCallback((): Promise<MediaInformation> => {
        if (!mediaInformationRequestRef.current) {
            mediaInformationRequestRef.current = loadMediaInformation().finally(() => {
                mediaInformationRequestRef.current = undefined;
            });
        }
        return mediaInformationRequestRef.current;
    }, []);

    const fetchMediaInformation = useCallback(async () => {
        const information = await requestMediaInformation();
        setMediaInformation(previous => isSameMediaInformation(previous, information) ? previous : information);
    }, [requestMediaInformation]);

    const startMetadataRefresh = useCallback(() => {
        if (metadataIntervalRef.current) {
            clearInterval(metadataIntervalRef.current);
        }
        fetchMediaInformation().then(() => {});
        metadataIntervalRef.current = setInterval(fetchMediaInformation, 5000);
    }, [fetchMediaInformation]);

    const stopMetadataRefresh = useCallback(() => {
        if (metadataIntervalRef.current) {
            clearInterval(metadataIntervalRef.current);
            metadataIntervalRef.current = undefined;
        }
    }, []);

    const animateVisualizer = useCallback(() => {
        const canvas = canvasRef.current;
        const context = canvas?.getContext("2d");
        const processor = pcmProcessorRef.current;
        if (!canvas || !context || !processor) return;
        if (animationIdRef.current !== undefined) {
            cancelAnimationFrame(animationIdRef.current);
            animationIdRef.current = undefined;
        }

        const dataLength = 256;

        let gradient: CanvasGradient | undefined;
        let lastHue = -1;
        let lastWidth = -1;

        const draw = () => {
            // a dispatched callback can outlive cancellation and must not adopt a replacement processor
            if (pcmProcessorRef.current !== processor) return;
            if (!canvasRef.current || !pcmProcessorRef.current || pcmProcessorRef.current.isDestroyed) {
                animationIdRef.current = undefined;
                return;
            }

            const {smoothedData, hue} = processor.getVisualizerData();
            const width = canvas.width;
            const height = canvas.height;
            const centerY = height / 2;
            const maximumAmplitude = height * 0.4;
            const xStep = width / dataLength;
            context.fillStyle = "#000";
            context.fillRect(0, 0, width, height);

            if (!gradient || Math.abs(hue - lastHue) > 5 || width !== lastWidth) {
                gradient = context.createLinearGradient(0, 0, width, 0);
                gradient.addColorStop(0, `hsl(${hue}, 100%, 50%)`);
                gradient.addColorStop(0.5, `hsl(${hue + 60}, 100%, 50%)`);
                gradient.addColorStop(1, `hsl(${hue + 120}, 100%, 50%)`);
                lastHue = hue;
                lastWidth = width;
            }

            context.shadowBlur = 0;
            context.strokeStyle = gradient;
            context.lineWidth = 2;

            context.beginPath();

            const step = 4;
            context.moveTo(0, centerY);
            for (let i = 0; i < dataLength; i += step) {
                const x = i * xStep;
                const amplitude = smoothedData[i] * maximumAmplitude;
                context.lineTo(x, centerY - amplitude);
            }

            for (let i = dataLength - 1; i >= 0; i -= step) {
                const x = i * xStep;
                const amplitude = smoothedData[i] * maximumAmplitude;
                context.lineTo(x, centerY + amplitude);
            }

            context.closePath();
            context.fillStyle = `hsla(${hue}, 100%, 50%, 0.2)`;
            context.fill();
            context.stroke();

            if (canvasRef.current && pcmProcessorRef.current) {
                animationIdRef.current = requestAnimationFrame(draw);
            }
        };

        animationIdRef.current = requestAnimationFrame(draw);
    }, []);

    const startHeartbeat = useCallback(() => {
        if (heartbeatIntervalRef.current) {
            clearInterval(heartbeatIntervalRef.current);
        }
        heartbeatIntervalRef.current = setInterval(() => {
            if (socketRef.current?.readyState === WebSocket.OPEN) {
                try {
                    socketRef.current.send(new ArrayBuffer(0));
                } catch (exception) {
                    console.error("Heartbeat failed:", exception);
                }
            }
        }, 30000);
    }, []);

    const stopHeartbeat = useCallback(() => {
        if (heartbeatIntervalRef.current) {
            clearInterval(heartbeatIntervalRef.current);
            heartbeatIntervalRef.current = undefined;
        }
    }, []);

    const attemptReconnect = useCallback(() => {
        if (reconnectTimeoutRef.current) return;

        reconnectAttemptsRef.current++;

        if (reconnectAttemptsRef.current > maximumReconnectAttempts) {
            updateStatus("Maximum reconnection attempts reached");
            shouldReconnectRef.current = false;
            setIsConnected(false);
            return;
        }

        const delay = Math.min(1000 * Math.pow(2, reconnectAttemptsRef.current - 1), 30000);

        reconnectTimeoutRef.current = setTimeout(() => {
            reconnectTimeoutRef.current = undefined;
            if (shouldReconnectRef.current && audioContextRef.current?.state === "running") {
                connectRef.current();
            }
        }, delay);
    }, [updateStatus]);

    const stopStream = useCallback(() => {
        shouldReconnectRef.current = false;
        streamingRef.current = false;

        if (reconnectTimeoutRef.current) {
            clearTimeout(reconnectTimeoutRef.current);
            reconnectTimeoutRef.current = undefined;
        }

        if (connectTimeoutRef.current) {
            clearTimeout(connectTimeoutRef.current);
            connectTimeoutRef.current = undefined;
        }

        if (socketRef.current) {
            socketRef.current.close();
            socketRef.current = undefined;
        }

        if (pcmProcessorRef.current) {
            pcmProcessorRef.current.destroy();
            pcmProcessorRef.current = undefined;
        }

        if (animationIdRef.current) {
            cancelAnimationFrame(animationIdRef.current);
            animationIdRef.current = undefined;
        }

        stopMetadataRefresh();
        stopHeartbeat();

        setIsConnected(false);
        setIsLoading(false);
        updateStatus("Disconnected");

        const canvas = canvasRef.current;
        if (canvas) {
            const context = canvas.getContext("2d");
            if (context) {
                context.clearRect(0, 0, canvas.width, canvas.height);
            }
        }

        if (placeholderRef.current) {
            placeholderRef.current.style.display = "block";
        }
        if (canvasRef.current) {
            canvasRef.current.style.display = "none";
        }
    }, [stopMetadataRefresh, stopHeartbeat, updateStatus]);

    const connectWebSocket = useCallback(() => {
        if (animationIdRef.current !== undefined) {
            cancelAnimationFrame(animationIdRef.current);
            animationIdRef.current = undefined;
        }
        if (pcmProcessorRef.current) {
            pcmProcessorRef.current.destroy();
            pcmProcessorRef.current = undefined;
        }

        if (connectTimeoutRef.current) {
            clearTimeout(connectTimeoutRef.current);
        }

        startMetadataRefresh();

        connectTimeoutRef.current = setTimeout(() => {
            connectTimeoutRef.current = undefined;
            try {
                setIsLoading(true);
                updateStatus("Connecting...");

                pcmProcessorRef.current = new PcmProcessor({
                    encoding: "16bitInt",
                    channels: 2,
                    sampleRate: 48000,
                    flushingTime: 2000,
                    audioContext: audioContextRef.current!
                });

                pcmProcessorRef.current.gainNode.gain.value = volumeRef.current / 100;

                const scheme = window.location.protocol === "https:" ? "wss" : "ws";
                const endpoint = `${scheme}://${window.location.host}/audio`;

                const socket = new WebSocket(endpoint);
                socket.binaryType = "arraybuffer";
                socketRef.current = socket;

                // a socket the page closed or replaced may still report its end; it no longer acts on the page, or its
                // late close would reconnect a stream that was stopped and started again, next to the new one
                socket.onopen = () => {
                    if (socketRef.current !== socket) return;
                    updateStatus("Connected", true);
                    streamingRef.current = false;
                    setIsConnected(true);
                    shouldReconnectRef.current = true;
                    setIsLoading(false);
                    startHeartbeat();

                    if (placeholderRef.current) {
                        placeholderRef.current.style.display = "none";
                    }
                    if (canvasRef.current) {
                        canvasRef.current.style.display = "block";
                    }

                    reconnectAttemptsRef.current = 0;
                    animateVisualizer();

                    if (reconnectTimeoutRef.current) {
                        clearTimeout(reconnectTimeoutRef.current);
                        reconnectTimeoutRef.current = undefined;
                    }
                };

                socket.onmessage = (event) => {
                    if (socketRef.current !== socket) return;
                    try {
                        if (event.data.byteLength > 0 && pcmProcessorRef.current && !pcmProcessorRef.current.isDestroyed) {
                            if (!streamingRef.current) {
                                streamingRef.current = true;
                                updateStatus("Streaming audio", true);
                            }
                            pcmProcessorRef.current.feed(event.data);
                        }
                    } catch (exception) {
                        console.error("Error processing audio data:", exception);
                        if (pcmProcessorRef.current) {
                            pcmProcessorRef.current.flush();
                        }
                    }
                };

                socket.onerror = (event) => {
                    if (socketRef.current !== socket) return;
                    console.error("WebSocket error:", event);
                    setIsLoading(false);
                    if (shouldReconnectRef.current) {
                        updateStatus("Connection error - reconnecting...");
                        attemptReconnect();
                    } else {
                        updateStatus("Connection error");
                        stopStream();
                    }
                };

                socket.onclose = () => {
                    if (socketRef.current !== socket) return;
                    streamingRef.current = false;
                    stopHeartbeat();
                    setIsLoading(false);
                    if (shouldReconnectRef.current) {
                        updateStatus("Disconnected - reconnecting...");
                        attemptReconnect();
                    } else {
                        updateStatus("Disconnected");
                        stopStream();
                    }
                };
            } catch (exception) {
                console.error("Error starting stream:", exception);
                updateStatus(`Error: ${(exception as Error).message}`);
                setIsLoading(false);
                if (shouldReconnectRef.current) {
                    attemptReconnect();
                }
            }
        }, 100);
    }, [startMetadataRefresh, startHeartbeat, stopHeartbeat, animateVisualizer, attemptReconnect, updateStatus, stopStream]);

    useEffect(() => {
        connectRef.current = connectWebSocket;
    }, [connectWebSocket]);

    const startPlayback = useCallback(() => {
        if (shouldReconnectRef.current) {
            return;
        }
        shouldReconnectRef.current = true;
        reconnectAttemptsRef.current = 0;
        setIsLoading(true);

        if (pcmProcessorRef.current) {
            pcmProcessorRef.current.destroy();
            pcmProcessorRef.current = undefined;
        }

        if (audioContextRef.current) {
            try {
                audioContextRef.current.close().then(() => {});
            } catch {
            }
            audioContextRef.current = undefined;
        }

        if ("webkitAudioContext" in window) {
            audioContextRef.current = new (window as WebkitWindow).webkitAudioContext();
        } else {
            audioContextRef.current = new AudioContext();
        }
        updateStatus(`Audio permission: ${audioContextRef.current.state}`);

        if (audioContextRef.current.state === "running") {
            connectWebSocket();
        } else {
            audioContextRef.current.resume().then(() => {
                updateStatus("Audio permission granted!");
                if (audioContextRef.current?.state === "running") {
                    connectWebSocket();
                } else {
                    shouldReconnectRef.current = false;
                    setIsLoading(false);
                }
            }).catch(error => {
                shouldReconnectRef.current = false;
                setIsLoading(false);
                updateStatus(`Failed to get audio permission: ${error}`);
            });
        }
    }, [connectWebSocket, updateStatus]);

    const stopPlayback = useCallback(() => {
        shouldReconnectRef.current = false;
        stopStream();
    }, [stopStream]);

    const changeVolume = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
        const newVolume = parseInt(event.target.value, 10);
        volumeRef.current = newVolume;
        setVolume(newVolume);
        if (pcmProcessorRef.current?.gainNode) {
            pcmProcessorRef.current.gainNode.gain.value = newVolume / 100;
        }
    }, []);

    const updateTitleScrolling = useCallback(() => {
        const title = titleRef.current;
        const wrapper = titleWrapperRef.current;

        if (!title || !wrapper) return;

        const titleWidth = title.scrollWidth;
        const wrapperWidth = wrapper.clientWidth;

        if (titleWidth > wrapperWidth && wrapperWidth > 0) {
            title.classList.add(styles.scrolling);
        } else {
            title.classList.remove(styles.scrolling);
        }
    }, []);

    useEffect(() => {
        let unmounted = false;
        requestMediaInformation().then(information => {
            if (!unmounted) {
                setMediaInformation(information);
            }
        });

        const resizeCanvas = () => {
            const canvas = canvasRef.current;
            if (canvas && canvas.parentElement) {
                const bounds = canvas.parentElement.getBoundingClientRect();
                const ratio = window.devicePixelRatio || 1;
                canvas.width = Math.max(1, Math.round(bounds.width * ratio));
                canvas.height = Math.max(1, Math.round(bounds.height * ratio));
            }
        };

        const resizeObserver = new ResizeObserver(resizeCanvas);
        const canvasContainer = canvasRef.current?.parentElement;
        if (canvasContainer) {
            resizeObserver.observe(canvasContainer);
        }
        resizeCanvas();

        return () => {
            unmounted = true;
            resizeObserver.disconnect();

            shouldReconnectRef.current = false;

            if (animationIdRef.current) {
                cancelAnimationFrame(animationIdRef.current);
                animationIdRef.current = undefined;
            }

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

            if (socketRef.current?.readyState === WebSocket.OPEN) {
                socketRef.current.close();
            }

            if (pcmProcessorRef.current) {
                pcmProcessorRef.current.destroy();
            }

            if (audioContextRef.current) {
                try {
                    audioContextRef.current.close().then(() => {});
                } catch {
                }
            }
        };
    }, [requestMediaInformation]);

    useEffect(() => {
        if (mediaInformation.title && mediaInformation.title !== "Waiting for stream...") {
            setTimeout(updateTitleScrolling, 100);
        }
    }, [mediaInformation.title, updateTitleScrolling]);

    return (
        <div className={styles.container}>
            <div className={styles.playerContainer}>
                <div className={styles.playerHeader}>
                    <h1>Audio Stream Player</h1>
                </div>

                <div
                    className={`${styles.mediaInformation} ${mediaInformation.title && mediaInformation.title !== "Waiting for stream..." ? styles.loaded : ""}`}>
                    <div className={styles.mediaThumbnail}>
                        {mediaInformation.thumbnail && mediaInformation.thumbnail !== failedThumbnail ? (
                            <Image
                                src={mediaInformation.thumbnail}
                                alt={mediaInformation.title || "Media thumbnail"}
                                width={128}
                                height={128}
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
                                {mediaInformation.title || "Waiting for stream..."}
                            </h2>
                        </div>
                        <p className={styles.mediaUploader}>
                            {mediaInformation.uploader || mediaInformation.channel || mediaInformation.uploader_id || "Unknown Artist"}
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
                            {mediaInformation.description || "Connect to start streaming"}
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
                        max="100"
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
                        href="https://github.com/PulseBeat02/mcav"
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
                        href="https://mcav.readthedocs.io/en/latest/intro.html"
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
