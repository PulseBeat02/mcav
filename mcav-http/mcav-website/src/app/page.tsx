'use client';

import React, {useCallback, useEffect, useRef, useState} from 'react';
import styles from './page.module.css';

interface MediaInfo {
    title?: string;
    uploader?: string;
    channel?: string;
    uploader_id?: string;
    description?: string;
    thumbnail?: string;
    duration?: number;
    view_count?: number;
    like_count?: number;
    upload_date?: string | null;
}

interface PCMProcessorOptions {
    encoding?: string;
    channels?: number;
    sampleRate?: number;
    flushingTime?: number;
    audioCtx?: AudioContext;
}

class PCMProcessor {
    private options: Required<PCMProcessorOptions>;
    private samples: Float32Array;
    private audioCtx: AudioContext;
    private waveformData: Float32Array;
    private smoothedData: Float32Array;
    public isDestroyed: boolean = false;
    private activeSources: Set<AudioBufferSourceNode> = new Set();
    private hue: number = 0;
    public gainNode: GainNode;
    private startTime: number;
    private processingTimestamp: number;
    private maxBufferSize: number = 480000;

    constructor(options: PCMProcessorOptions) {
        this.options = {
            encoding: options.encoding || '16bitInt',
            channels: options.channels || 2,
            sampleRate: options.sampleRate || 48000,
            flushingTime: options.flushingTime || 2000,
            audioCtx: options.audioCtx || (
                'webkitAudioContext' in window
                    ? new (window as typeof window & { webkitAudioContext: typeof AudioContext }).webkitAudioContext()
                    : new AudioContext()
            )
        };
        this.samples = new Float32Array();
        this.audioCtx = this.options.audioCtx;
        this.waveformData = new Float32Array(256);
        this.smoothedData = new Float32Array(256);

        this.gainNode = this.audioCtx.createGain();
        this.gainNode.connect(this.audioCtx.destination);
        this.startTime = this.audioCtx.currentTime;
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
        const float32Array = new Float32Array(data.byteLength / 2);
        for (let i = 0; i < float32Array.length; i++) {
            const int16 = view.getInt16(i * 2, true);
            float32Array[i] = int16 / 32768.0;
        }

        this.updateVisualizer(float32Array);

        if (this.samples.length > this.maxBufferSize) {
            const keepSamples = Math.floor(this.maxBufferSize / 2);
            this.samples = this.samples.slice(-keepSamples);
        }

        const newSamples = new Float32Array(this.samples.length + float32Array.length);
        newSamples.set(this.samples);
        newSamples.set(float32Array, this.samples.length);
        this.samples = newSamples;

        if (this.samples.length / this.options.channels > this.options.sampleRate / 2) {
            this.play();
        }
    }

    private updateVisualizer(data: Float32Array): void {
        if (this.isDestroyed) return;

        const samplesPerPoint = Math.floor(data.length / this.waveformData.length);
        for (let i = 0; i < this.waveformData.length; i++) {
            let max = 0;
            for (let j = 0; j < samplesPerPoint; j++) {
                const idx = i * samplesPerPoint + j;
                if (idx < data.length) {
                    max = Math.max(max, Math.abs(data[idx]));
                }
            }
            this.waveformData[i] = max * 2;
            this.smoothedData[i] = this.smoothedData[i] * 0.85 + this.waveformData[i] * 0.15;
        }
    }

    getVisualizerData(): { smoothedData: Float32Array; hue: number } {
        this.hue = (this.hue + 0.5) % 360;
        return {smoothedData: this.smoothedData, hue: this.hue};
    }

    private play(): void {
        if (this.samples.length === 0 || this.isDestroyed) return;

        const bufferSource = this.audioCtx.createBufferSource();
        const length = this.samples.length / this.options.channels;
        const audioBuffer = this.audioCtx.createBuffer(
            this.options.channels,
            length,
            this.options.sampleRate
        );

        for (let channel = 0; channel < this.options.channels; channel++) {
            const audioData = audioBuffer.getChannelData(channel);
            let offset = channel;
            let decrement = 50;
            for (let i = 0; i < length; i++) {
                audioData[i] = this.samples[offset];
                if (i < 50) {
                    audioData[i] = (audioData[i] * i) / 50;
                }
                if (i >= (length - 51)) {
                    audioData[i] = (audioData[i] * decrement--) / 50;
                }
                offset += this.options.channels;
            }
        }

        if (this.startTime < this.audioCtx.currentTime) {
            this.startTime = this.audioCtx.currentTime;
        }

        bufferSource.buffer = audioBuffer;
        bufferSource.connect(this.gainNode);

        this.activeSources.add(bufferSource);

        bufferSource.onended = () => {
            this.activeSources.delete(bufferSource);
        };

        bufferSource.start(this.startTime);
        this.startTime += audioBuffer.duration;
        this.samples = new Float32Array();
    }

    destroy(): void {
        this.isDestroyed = true;

        this.activeSources.forEach(source => {
            try {
                source.stop(this.audioCtx.currentTime);
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
                source.stop(this.audioCtx.currentTime);
            } catch {
            }
        });
        this.activeSources.clear();

        this.samples = new Float32Array();
        this.startTime = this.audioCtx.currentTime;
        this.waveformData = new Float32Array(256);
        this.smoothedData = new Float32Array(256);
    }
}

const formatDuration = (seconds?: number): string => {
    if (!seconds || seconds === 0) return '--:--';
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    if (hours > 0) {
        return `${hours}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
    }
    return `${minutes}:${secs.toString().padStart(2, '0')}`;
};

const formatNumber = (num?: number): string => {
    if (!num || num === 0) return '0';
    if (num >= 1000000) {
        return (num / 1000000).toFixed(1) + 'M';
    }
    if (num >= 1000) {
        return (num / 1000).toFixed(1) + 'K';
    }
    return num.toString();
};

const formatDate = (dateString?: string | null): string => {
    if (!dateString) return 'Unknown date';
    try {
        const date = new Date(dateString.replace(/(\d{4})(\d{2})(\d{2})/, '$1-$2-$3'));
        return date.toLocaleDateString('en-US', {year: 'numeric', month: 'short', day: 'numeric'});
    } catch {
        return 'Unknown date';
    }
};

export default function AudioStreamPlayer() {
    const [statusText, setStatusText] = useState('Disconnected');
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
    const [mediaInfo, setMediaInfo] = useState<MediaInfo>({
        title: 'Waiting for stream...',
        uploader: 'Unknown Artist',
        description: 'Connect to start streaming',
        duration: 0,
        view_count: 0,
        like_count: 0,
        upload_date: null
    });
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const animationIdRef = useRef<number | null>(null);
    const audioContextRef = useRef<AudioContext | null>(null);
    const wsRef = useRef<WebSocket | null>(null);
    const pcmProcessorRef = useRef<PCMProcessor | null>(null);
    const metadataIntervalRef = useRef<NodeJS.Timeout | null>(null);
    const heartbeatIntervalRef = useRef<NodeJS.Timeout | null>(null);
    const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);
    const reconnectAttemptsRef = useRef(0);
    const shouldReconnectRef = useRef(false);
    const titleRef = useRef<HTMLHeadingElement>(null);
    const titleWrapperRef = useRef<HTMLDivElement>(null);
    const placeholderRef = useRef<HTMLDivElement>(null);
    const [isImageError, setIsImageError] = useState(false);

    const maxReconnectAttempts = 5;

    const fetchMediaInfo = useCallback(async () => {
        try {
            const response = await fetch('/media');
            if (!response.ok) {
                setMediaInfo({
                    title: 'Unknown Title',
                    uploader: 'Unknown Artist',
                    description: 'No description available',
                    thumbnail: undefined,
                    duration: 0,
                    view_count: 0,
                    like_count: 0,
                    upload_date: null
                });
                return;
            }
            const data = await response.json();
            setMediaInfo({
                title: data?.title || 'Unknown Title',
                uploader: data?.uploader || data?.channel || data?.uploader_id || 'Unknown Artist',
                description: data?.description || 'No description available',
                thumbnail: data?.thumbnail,
                duration: data?.duration || 0,
                view_count: data?.view_count || 0,
                like_count: data?.like_count || 0,
                upload_date: data?.upload_date || null
            });
        } catch (error) {
            console.error('Error fetching media info:', error);
            setMediaInfo({
                title: 'Unknown Title',
                uploader: 'Unknown Artist',
                description: 'No description available',
                thumbnail: undefined,
                duration: 0,
                view_count: 0,
                like_count: 0,
                upload_date: null
            });
        }
    }, []);

    const startMetadataRefresh = useCallback(() => {
        if (metadataIntervalRef.current) {
            clearInterval(metadataIntervalRef.current);
        }
        fetchMediaInfo().then(() => {});
        metadataIntervalRef.current = setInterval(fetchMediaInfo, 5000);
    }, [fetchMediaInfo]);

    const stopMetadataRefresh = useCallback(() => {
        if (metadataIntervalRef.current) {
            clearInterval(metadataIntervalRef.current);
            metadataIntervalRef.current = null;
        }
    }, []);

    const animateVisualizer = useCallback(() => {
        const canvas = canvasRef.current;
        const ctx = canvas?.getContext('2d');
        if (!canvas || !ctx || !pcmProcessorRef.current) return;

        const width = canvas.width;
        const height = canvas.height;
        const centerY = height / 2;
        const maxAmplitude = height * 0.4;
        const dataLength = 256;
        const xStep = width / dataLength;

        let gradient: CanvasGradient | null = null;
        let lastHue = -1;

        const draw = () => {

            if (!canvasRef.current || !pcmProcessorRef.current || pcmProcessorRef.current.isDestroyed) {
                animationIdRef.current = null;
                return;
            }

            const {smoothedData, hue} = pcmProcessorRef.current.getVisualizerData();
            ctx.fillStyle = '#000';
            ctx.fillRect(0, 0, width, height);

            if (!gradient || Math.abs(hue - lastHue) > 5) {
                gradient = ctx.createLinearGradient(0, 0, width, 0);
                gradient.addColorStop(0, `hsl(${hue}, 100%, 50%)`);
                gradient.addColorStop(0.5, `hsl(${hue + 60}, 100%, 50%)`);
                gradient.addColorStop(1, `hsl(${hue + 120}, 100%, 50%)`);
                lastHue = hue;
            }

            ctx.shadowBlur = 0;
            ctx.strokeStyle = gradient;
            ctx.lineWidth = 2;

            ctx.beginPath();

            const step = 4;
            ctx.moveTo(0, centerY);
            for (let i = 0; i < dataLength; i += step) {
                const x = i * xStep;
                const amplitude = smoothedData[i] * maxAmplitude;
                ctx.lineTo(x, centerY - amplitude);
            }

            for (let i = dataLength - 1; i >= 0; i -= step) {
                const x = i * xStep;
                const amplitude = smoothedData[i] * maxAmplitude;
                ctx.lineTo(x, centerY + amplitude);
            }

            ctx.closePath();
            ctx.fillStyle = `hsla(${hue}, 100%, 50%, 0.2)`;
            ctx.fill();
            ctx.stroke();

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
            if (wsRef.current?.readyState === WebSocket.OPEN) {
                try {
                    wsRef.current.send(new ArrayBuffer(0));
                } catch (e) {
                    console.error('Heartbeat failed:', e);
                }
            }
        }, 30000);
    }, []);

    const stopHeartbeat = useCallback(() => {
        if (heartbeatIntervalRef.current) {
            clearInterval(heartbeatIntervalRef.current);
            heartbeatIntervalRef.current = null;
        }
    }, []);

    const attemptReconnect = useCallback(() => {
        if (reconnectTimeoutRef.current) return;

        reconnectAttemptsRef.current++;

        if (reconnectAttemptsRef.current > maxReconnectAttempts) {
            updateStatus('Maximum reconnection attempts reached');
            shouldReconnectRef.current = false;
            setIsConnected(false);
            return;
        }

        const delay = Math.min(1000 * Math.pow(2, reconnectAttemptsRef.current - 1), 30000);

        reconnectTimeoutRef.current = setTimeout(() => {
            reconnectTimeoutRef.current = null;
            if (shouldReconnectRef.current && audioContextRef.current?.state === 'running') {
                connectWebSocket();
            }
        }, delay);
    }, []);

    const connectWebSocket = useCallback(() => {
        if (pcmProcessorRef.current) {
            pcmProcessorRef.current.destroy();
            pcmProcessorRef.current = null;
        }

        startMetadataRefresh();

        setTimeout(() => {
            try {
                setIsLoading(true);
                updateStatus('Connecting...');

                pcmProcessorRef.current = new PCMProcessor({
                    encoding: '16bitInt',
                    channels: 2,
                    sampleRate: 48000,
                    flushingTime: 2000,
                    audioCtx: audioContextRef.current!
                });

                pcmProcessorRef.current.gainNode.gain.value = volume / 100;

                const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
                const endpoint = `${scheme}://${window.location.host}/audio`;

                wsRef.current = new WebSocket(endpoint);
                wsRef.current.binaryType = 'arraybuffer';

                wsRef.current.onopen = () => {
                    updateStatus('Connected', true);
                    setIsConnected(true);
                    shouldReconnectRef.current = true;
                    setIsLoading(false);
                    startHeartbeat();

                    if (placeholderRef.current) {
                        placeholderRef.current.style.display = 'none';
                    }
                    if (canvasRef.current) {
                        canvasRef.current.style.display = 'block';
                    }

                    reconnectAttemptsRef.current = 0;
                    animateVisualizer();

                    if (reconnectTimeoutRef.current) {
                        clearTimeout(reconnectTimeoutRef.current);
                        reconnectTimeoutRef.current = null;
                    }
                };

                wsRef.current.onmessage = (e) => {
                    try {
                        if (e.data.byteLength > 0 && pcmProcessorRef.current && !pcmProcessorRef.current.isDestroyed) {
                            if (statusText !== 'Streaming audio') {
                                updateStatus('Streaming audio', true);
                            }
                            pcmProcessorRef.current.feed(e.data);
                        }
                    } catch (error) {
                        console.error('Error processing audio data:', error);
                        if (pcmProcessorRef.current) {
                            pcmProcessorRef.current.flush();
                        }
                    }
                };

                wsRef.current.onerror = (e) => {
                    console.error('WebSocket error:', e);
                    setIsLoading(false);
                    if (shouldReconnectRef.current) {
                        updateStatus('Connection error - reconnecting...');
                        attemptReconnect();
                    } else {
                        updateStatus('Connection error');
                        stopStream();
                    }
                };

                wsRef.current.onclose = () => {
                    stopHeartbeat();
                    setIsLoading(false);
                    if (shouldReconnectRef.current) {
                        updateStatus('Disconnected - reconnecting...');
                        attemptReconnect();
                    } else {
                        updateStatus('Disconnected');
                        stopStream();
                    }
                };
            } catch (error) {
                console.error('Error starting stream:', error);
                updateStatus(`Error: ${(error as Error).message}`);
                setIsLoading(false);
                if (shouldReconnectRef.current) {
                    attemptReconnect();
                }
            }
        }, 100);
    }, [volume, startMetadataRefresh, startHeartbeat, stopHeartbeat, animateVisualizer, attemptReconnect, updateStatus, statusText]);

    const stopStream = useCallback(() => {
        shouldReconnectRef.current = false;

        if (reconnectTimeoutRef.current) {
            clearTimeout(reconnectTimeoutRef.current);
            reconnectTimeoutRef.current = null;
        }

        if (wsRef.current) {
            wsRef.current.close();
            wsRef.current = null;
        }

        if (pcmProcessorRef.current) {
            pcmProcessorRef.current.destroy();
            pcmProcessorRef.current = null;
        }

        if (animationIdRef.current) {
            cancelAnimationFrame(animationIdRef.current);
            animationIdRef.current = null;
        }

        stopMetadataRefresh();
        stopHeartbeat();

        setIsConnected(false);
        updateStatus('Disconnected');

        const canvas = canvasRef.current;
        if (canvas) {
            const ctx = canvas.getContext('2d');
            if (ctx) {
                ctx.clearRect(0, 0, canvas.width, canvas.height);
            }
        }

        if (placeholderRef.current) {
            placeholderRef.current.style.display = 'block';
        }
        if (canvasRef.current) {
            canvasRef.current.style.display = 'none';
        }
    }, [stopMetadataRefresh, stopHeartbeat, updateStatus]);

    const handleStart = useCallback(() => {
        shouldReconnectRef.current = true;
        reconnectAttemptsRef.current = 0;

        if (pcmProcessorRef.current) {
            pcmProcessorRef.current.destroy();
            pcmProcessorRef.current = null;
        }

        if (audioContextRef.current) {
            try {
                audioContextRef.current.close().then(() => {});
            } catch {
            }
            audioContextRef.current = null;
        }

        if ('webkitAudioContext' in window) {
            audioContextRef.current = new (window as typeof window & {
                webkitAudioContext: typeof AudioContext
            }).webkitAudioContext();
        } else {
            audioContextRef.current = new AudioContext();
        }
        updateStatus(`Audio permission: ${audioContextRef.current.state}`);

        if (audioContextRef.current.state === 'running') {
            connectWebSocket();
        } else {
            audioContextRef.current.resume().then(() => {
                updateStatus('Audio permission granted!');
                if (audioContextRef.current!.state === 'running') {
                    connectWebSocket();
                }
            }).catch(err => {
                updateStatus(`Failed to get audio permission: ${err}`);
            });
        }
    }, [connectWebSocket, updateStatus]);

    const handleStop = useCallback(() => {
        shouldReconnectRef.current = false;
        stopStream();
    }, [stopStream]);

    const handleVolumeChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
        const newVolume = parseInt(e.target.value);
        setVolume(newVolume);
        if (pcmProcessorRef.current?.gainNode) {
            pcmProcessorRef.current.gainNode.gain.value = newVolume / 100;
        }
    }, []);

    const checkTitleScrolling = useCallback(() => {
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
        fetchMediaInfo().then(() => {});

        const resizeCanvas = () => {
            const canvas = canvasRef.current;
            if (canvas && canvas.parentElement) {
                const rect = canvas.parentElement.getBoundingClientRect();
                canvas.width = rect.width;
                canvas.height = rect.height;
            }
        };

        window.addEventListener('resize', resizeCanvas);
        resizeCanvas();

        return () => {
            window.removeEventListener('resize', resizeCanvas);

            shouldReconnectRef.current = false;

            if (animationIdRef.current) {
                cancelAnimationFrame(animationIdRef.current);
                animationIdRef.current = null;
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

            if (wsRef.current?.readyState === WebSocket.OPEN) {
                wsRef.current.close();
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
    }, [fetchMediaInfo]);

    useEffect(() => {
        if (mediaInfo.title && mediaInfo.title !== 'Waiting for stream...') {
            setTimeout(checkTitleScrolling, 100);
        }
    }, [mediaInfo.title, checkTitleScrolling]);

    useEffect(() => {
        setIsImageError(false);
    }, [mediaInfo.thumbnail]);

    return (
        <div className={styles.container}>
            <div className={styles.playerContainer}>
                <div className={styles.playerHeader}>
                    <h1>Audio Stream Player</h1>
                </div>

                <div
                    className={`${styles.mediaInfo} ${mediaInfo.title && mediaInfo.title !== 'Waiting for stream...' ? styles.loaded : ''}`}>
                    <div className={styles.mediaThumbnail}>
                        {!isImageError && mediaInfo.thumbnail ? (
                            <img
                                src={mediaInfo.thumbnail}
                                alt={mediaInfo.title || 'Media thumbnail'}
                                width={128}
                                height={128}
                                onError={() => setIsImageError(true)}
                                className={styles.mediaThumbnailImage}
                            />
                        ) : (
                            <span className={styles.placeholderIcon}>♫</span>
                        )}
                    </div>
                    <div className={styles.mediaDetails}>
                        <div className={styles.mediaTitleWrapper} ref={titleWrapperRef}>
                            <h2 className={styles.mediaTitle} ref={titleRef}>
                                {mediaInfo.title || 'Waiting for stream...'}
                            </h2>
                        </div>
                        <p className={styles.mediaUploader}>
                            {mediaInfo.uploader || mediaInfo.channel || mediaInfo.uploader_id || 'Unknown Artist'}
                        </p>
                        <div className={styles.mediaMetadata}>
                            <div className={styles.metadataItem}>
                                <span className={styles.metadataIcon}>🕑</span>
                                <span>{formatDuration(mediaInfo.duration)}</span>
                            </div>
                            <div className={styles.metadataItem}>
                                <span className={styles.metadataIcon}>👁</span>
                                <span>{formatNumber(mediaInfo.view_count)} views</span>
                            </div>
                            <div className={styles.metadataItem}>
                                <span className={styles.metadataIcon}>👍</span>
                                <span>{formatNumber(mediaInfo.like_count)} likes</span>
                            </div>
                            <div className={styles.metadataItem}>
                                <span className={styles.metadataIcon}>📅</span>
                                <span>{formatDate(mediaInfo.upload_date)}</span>
                            </div>
                        </div>
                        <p className={styles.mediaDescription}>
                            {mediaInfo.description || 'Connect to start streaming'}
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
                        style={{ display: 'none' }}
                    />
                </div>

                <div className={styles.volumeControl}>
                    <div className={styles.volumeIcon}>🔊</div>
                    <input
                        type="range"
                        min="0"
                        max="100"
                        value={volume}
                        onChange={handleVolumeChange}
                        className={styles.volumeSlider}
                    />
                </div>

                <div className={styles.controls}>
                    <button
                        onClick={handleStart}
                        disabled={isConnected}
                        className={styles.btn}
                    >
                        Start
                    </button>
                    <button
                        onClick={handleStop}
                        disabled={!isConnected}
                        className={styles.btn}
                    >
                        Stop
                    </button>
                </div>

                <div className={styles.statusPanel}>
                    <div className={`${styles.status} ${statusConnected ? styles.statusConnected : ''}`}>
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
}