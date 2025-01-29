"use client";

import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
    Box,
    Card,
    CardContent,
    CardMedia,
    Typography,
    Button,
    Slider,
    IconButton,
    Chip,
    Avatar,
    Divider,
    LinearProgress,
    useTheme,
    alpha,
    GlobalStyles,
} from '@mui/material';
import {
    PlayArrow,
    Stop,
    VolumeUp,
    VolumeDown,
    VolumeMute,
    Person,
    Wifi,
    WifiOff,
    SignalWifiStatusbar4Bar,
    SignalWifiStatusbarConnectedNoInternet4,
    GitHub,
    MenuBook,
} from '@mui/icons-material';
import { styled } from '@mui/material/styles';

// Global font styles
const globalStyles = (
    <GlobalStyles
        styles={{
            '@import': "url('https://fonts.googleapis.com/css2?family=Poppins:wght@300;400;500;600;700&display=swap')",
            body: {
                fontFamily: '"Poppins", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
            },
        }}
    />
);

// Styled components for custom styling
const PlayerCard = styled(Card)(({ theme }) => ({
    maxWidth: 600,
    margin: '0 auto',
    background: 'rgba(18, 18, 18, 0.95)',
    border: '1px solid rgba(29, 185, 84, 0.2)',
    borderRadius: 16,
    boxShadow: '0 10px 30px rgba(0, 0, 0, 0.5), 0 0 40px rgba(29, 185, 84, 0.1)',
    backdropFilter: 'blur(20px)',
}));

const VisualizerContainer = styled(Box)(({ theme }) => ({
    height: 120,
    background: 'rgba(0, 0, 0, 0.4)',
    border: '1px solid rgba(29, 185, 84, 0.3)',
    borderRadius: 12,
    position: 'relative',
    overflow: 'hidden',
    display: 'flex',
    alignItems: 'flex-end',
    justifyContent: 'center',
    gap: 2,
    padding: theme.spacing(2),
    boxShadow: 'inset 0 2px 10px rgba(0, 0, 0, 0.5)',
}));

const VisualizerBar = styled(Box)(({ theme }) => ({
    width: 4,
    minHeight: 4,
    background: '#1db954',
    borderRadius: '2px 2px 0 0',
    transition: 'height 0.1s ease',
    boxShadow: '0 0 8px rgba(29, 185, 84, 0.5)',
    filter: 'brightness(1.2)',
}));

const ControlsContainer = styled(Box)(({ theme }) => ({
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.spacing(2),
    padding: theme.spacing(2),
}));

const VolumeContainer = styled(Box)(({ theme }) => ({
    display: 'flex',
    alignItems: 'center',
    gap: theme.spacing(1),
    padding: `0 ${theme.spacing(2)}`,
}));

// PCM Processor class (simplified for React)
class PCMProcessor {
    private audioCtx: AudioContext;
    private gainNode: GainNode;
    private samples: Float32Array;
    private options: any;
    private isDestroyed: boolean;
    private activeSources: Set<AudioBufferSourceNode>;
    private startTime: number;
    private onVisualizerUpdate?: (data: Float32Array) => void;

    constructor(options: any) {
        this.options = {
            encoding: options.encoding || '16bitInt',
            channels: options.channels || 2,
            sampleRate: options.sampleRate || 48000,
            flushingTime: options.flushingTime || 2000,
            ...options,
        };
        this.samples = new Float32Array();
        this.audioCtx = options.audioCtx || new (window.AudioContext || (window as any).webkitAudioContext)();
        this.isDestroyed = false;
        this.activeSources = new Set();
        this.onVisualizerUpdate = options.onVisualizerUpdate;

        this.gainNode = this.audioCtx.createGain();
        this.gainNode.connect(this.audioCtx.destination);
        this.startTime = this.audioCtx.currentTime;
    }

    feed(data: ArrayBuffer) {
        if (this.isDestroyed) return;

        const view = new DataView(data);
        const float32Array = new Float32Array(data.byteLength / 2);
        for (let i = 0; i < float32Array.length; i++) {
            const int16 = view.getInt16(i * 2, true);
            float32Array[i] = int16 / 32768.0;
        }

        if (this.onVisualizerUpdate) {
            this.onVisualizerUpdate(float32Array);
        }

        const newSamples = new Float32Array(this.samples.length + float32Array.length);
        newSamples.set(this.samples);
        newSamples.set(float32Array, this.samples.length);
        this.samples = newSamples;

        if (this.samples.length / this.options.channels > this.options.sampleRate / 2) {
            this.play();
        }
    }

    private play() {
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
                if (i >= length - 51) {
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

    setVolume(volume: number) {
        if (this.gainNode) {
            this.gainNode.gain.value = volume;
        }
    }

    destroy() {
        this.isDestroyed = true;
        this.activeSources.forEach(source => {
            try {
                source.stop(this.audioCtx.currentTime);
                source.disconnect();
            } catch (e) {}
        });
        this.activeSources.clear();

        if (this.gainNode) {
            try {
                this.gainNode.disconnect();
            } catch (e) {}
        }

        this.samples = new Float32Array();
        this.startTime = 0;
    }
}

// Audio metadata interface
interface AudioMetadata {
    title: string;
    uploader: string;
    description: string;
    thumbnail: string;
}

// Connection status enum
enum ConnectionStatus {
    Disconnected = 'Disconnected',
    Connecting = 'Connecting...',
    Connected = 'Connected',
    Streaming = 'Streaming audio',
    Error = 'Connection error',
}

const AudioStreamPlayer: React.FC = () => {
    const theme = useTheme();
    const [isPlaying, setIsPlaying] = useState(false);
    const [volume, setVolume] = useState(100);
    const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>(ConnectionStatus.Disconnected);
    const [visualizerData, setVisualizerData] = useState<number[]>(new Array(64).fill(0));

    // Audio metadata state
    const [metadata] = useState<AudioMetadata>({
        title: "Title",
        uploader: "Uploader",
        description: "Description",
        thumbnail: "data:image/svg+xml;base64," + btoa(`
      <svg width="300" height="200" xmlns="http://www.w3.org/2000/svg">
        <rect width="100%" height="100%" fill="#000"/>
        <rect x="140" y="90" width="20" height="20" fill="#00ff00"/>
        <text x="150" y="140" text-anchor="middle" fill="#666" font-family="monospace" font-size="10">STREAM</text>
      </svg>
    `),
    });

    const audioContextRef = useRef<AudioContext | null>(null);
    const pcmProcessorRef = useRef<PCMProcessor | null>(null);
    const wsRef = useRef<WebSocket | null>(null);
    const animationRef = useRef<number | null>(null);

    const updateVisualizer = useCallback((data: Float32Array) => {
        const barCount = 64;
        const samplesPerBar = Math.floor(data.length / barCount);
        const newData: number[] = [];

        for (let i = 0; i < barCount; i++) {
            const startSample = i * samplesPerBar;
            let sum = 0;
            for (let j = 0; j < samplesPerBar; j++) {
                if (startSample + j < data.length) {
                    sum += Math.abs(data[startSample + j]);
                }
            }
            const avg = sum / samplesPerBar;
            newData[i] = avg;
        }

        setVisualizerData(prev =>
            prev.map((val, i) => val * 0.7 + (newData[i] || 0) * 0.3)
        );
    }, []);

    const connectWebSocket = useCallback(async () => {
        try {
            setConnectionStatus(ConnectionStatus.Connecting);

            // Clean up existing connections
            if (pcmProcessorRef.current) {
                pcmProcessorRef.current.destroy();
                pcmProcessorRef.current = null;
            }

            if (audioContextRef.current) {
                await audioContextRef.current.close();
            }

            // Create new audio context
            audioContextRef.current = new (window.AudioContext || (window as any).webkitAudioContext)();

            if (audioContextRef.current.state !== 'running') {
                await audioContextRef.current.resume();
            }

            // Create PCM processor
            pcmProcessorRef.current = new PCMProcessor({
                encoding: '16bitInt',
                channels: 2,
                sampleRate: 48000,
                flushingTime: 2000,
                audioCtx: audioContextRef.current,
                onVisualizerUpdate: updateVisualizer,
            });

            pcmProcessorRef.current.setVolume(volume / 100);

            // Create WebSocket connection
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const wsUrl = `${protocol}//${window.location.hostname}:8080`; // Replace with your WebSocket port

            wsRef.current = new WebSocket(wsUrl);
            wsRef.current.binaryType = 'arraybuffer';

            wsRef.current.onopen = () => {
                setConnectionStatus(ConnectionStatus.Connected);
                setIsPlaying(true);
            };

            wsRef.current.onmessage = (event) => {
                if (event.data.byteLength > 0 && pcmProcessorRef.current) {
                    setConnectionStatus(ConnectionStatus.Streaming);
                    pcmProcessorRef.current.feed(event.data);
                }
            };

            wsRef.current.onerror = () => {
                setConnectionStatus(ConnectionStatus.Error);
                stopStream();
            };

            wsRef.current.onclose = () => {
                setConnectionStatus(ConnectionStatus.Disconnected);
                stopStream();
            };

        } catch (error) {
            console.error('Error starting stream:', error);
            setConnectionStatus(ConnectionStatus.Error);
            stopStream();
        }
    }, [volume, updateVisualizer]);

    const stopStream = useCallback(() => {
        if (wsRef.current) {
            wsRef.current.close();
            wsRef.current = null;
        }

        if (pcmProcessorRef.current) {
            pcmProcessorRef.current.destroy();
            pcmProcessorRef.current = null;
        }

        setIsPlaying(false);
        setConnectionStatus(ConnectionStatus.Disconnected);
        setVisualizerData(new Array(64).fill(0));
    }, []);

    const handleVolumeChange = useCallback((_: Event, newValue: number | number[]) => {
        const vol = Array.isArray(newValue) ? newValue[0] : newValue;
        setVolume(vol);
        if (pcmProcessorRef.current) {
            pcmProcessorRef.current.setVolume(vol / 100);
        }
    }, []);

    const getNetworkIcon = () => {
        switch (connectionStatus) {
            case ConnectionStatus.Connected:
            case ConnectionStatus.Streaming:
                return <SignalWifiStatusbar4Bar fontSize="small" sx={{ color: '#1db954' }} />;
            case ConnectionStatus.Connecting:
                return <SignalWifiStatusbarConnectedNoInternet4 fontSize="small" sx={{ color: '#ffa726' }} />;
            case ConnectionStatus.Error:
                return <WifiOff fontSize="small" sx={{ color: '#f44336' }} />;
            default:
                return <Wifi fontSize="small" sx={{ color: '#666' }} />;
        }
    };

    const getVolumeIcon = () => {
        if (volume === 0) return <VolumeMute />;
        if (volume < 50) return <VolumeDown />;
        return <VolumeUp />;
    };

    const getStatusColor = () => {
        switch (connectionStatus) {
            case ConnectionStatus.Connected:
            case ConnectionStatus.Streaming:
                return 'success';
            case ConnectionStatus.Error:
                return 'error';
            case ConnectionStatus.Connecting:
                return 'warning';
            default:
                return 'default';
        }
    };

    // Cleanup on unmount
    useEffect(() => {
        return () => {
            stopStream();
            if (audioContextRef.current) {
                audioContextRef.current.close();
            }
            if (animationRef.current) {
                cancelAnimationFrame(animationRef.current);
            }
        };
    }, [stopStream]);

    return (
        <>
            {globalStyles}
            <Box sx={{
                minHeight: '100vh',
                display: 'flex',
                alignItems: 'center',
                p: 2,
                background: 'linear-gradient(135deg, #191414, #333)',
                fontFamily: '"Poppins", sans-serif',
            }}>
                <PlayerCard>
                    {/* Thumbnail */}
                    <CardMedia
                        component="img"
                        height="200"
                        image={metadata.thumbnail}
                        alt={metadata.title}
                        sx={{
                            background: '#000',
                        }}
                    />

                    <CardContent sx={{ pb: 0, background: 'rgba(40, 40, 40, 0.8)', color: '#f5f5f5', padding: 3 }}>
                        {/* Metadata */}
                        <Typography
                            variant="h5"
                            component="h1"
                            gutterBottom
                            sx={{
                                fontWeight: 500,
                                fontFamily: '"Poppins", sans-serif',
                                color: '#fff',
                                fontSize: '1.2rem',
                            }}
                        >
                            {metadata.title}
                        </Typography>

                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                            <Avatar
                                sx={{
                                    width: 24,
                                    height: 24,
                                    background: 'rgba(29, 185, 84, 0.2)',
                                    border: '1px solid rgba(29, 185, 84, 0.3)',
                                }}
                            >
                                <Person fontSize="small" sx={{ color: '#1db954' }} />
                            </Avatar>
                            <Typography variant="body2" sx={{ color: 'rgba(255, 255, 255, 0.8)', fontFamily: '"Poppins", sans-serif' }}>
                                {metadata.uploader}
                            </Typography>
                            {getNetworkIcon()}
                        </Box>

                        <Typography
                            variant="body2"
                            sx={{
                                mb: 2,
                                fontFamily: '"Poppins", sans-serif',
                                lineHeight: 1.4,
                                fontWeight: 400,
                                color: '#888',
                                fontSize: '0.8rem',
                            }}
                        >
                            {metadata.description}
                        </Typography>

                        <Divider sx={{ mb: 2, borderColor: 'rgba(255, 255, 255, 0.1)' }} />

                        {/* Visualizer */}
                        <VisualizerContainer>
                            {visualizerData.map((height, index) => (
                                <VisualizerBar
                                    key={index}
                                    sx={{
                                        height: `${Math.max(4, height * 100)}px`,
                                        opacity: isPlaying ? 1 : 0.3,
                                    }}
                                />
                            ))}
                            {!isPlaying && (
                                <Box
                                    sx={{
                                        position: 'absolute',
                                        top: '50%',
                                        left: '50%',
                                        transform: 'translate(-50%, -50%)',
                                        color: 'rgba(255, 255, 255, 0.5)',
                                        fontSize: '0.9rem',
                                        fontFamily: '"Poppins", sans-serif',
                                        textAlign: 'center',
                                    }}
                                >
                                    Connect to start visualization
                                </Box>
                            )}
                        </VisualizerContainer>

                        {/* Volume Control */}
                        <VolumeContainer sx={{ mt: 3 }}>
                            <IconButton size="small" sx={{ color: '#f5f5f5' }}>
                                {getVolumeIcon()}
                            </IconButton>
                            <Slider
                                value={volume}
                                onChange={handleVolumeChange}
                                aria-labelledby="volume-slider"
                                sx={{
                                    flexGrow: 1,
                                    color: '#1db954',
                                    height: 5,
                                    '& .MuiSlider-track': {
                                        backgroundColor: '#1db954',
                                        border: 'none',
                                        borderRadius: 5,
                                    },
                                    '& .MuiSlider-thumb': {
                                        backgroundColor: '#1db954',
                                        width: 16,
                                        height: 16,
                                        boxShadow: '0 0 8px rgba(29, 185, 84, 0.5)',
                                        '&:hover': {
                                            transform: 'scale(1.2)',
                                            boxShadow: '0 0 12px rgba(29, 185, 84, 0.7)',
                                        },
                                    },
                                    '& .MuiSlider-rail': {
                                        backgroundColor: 'rgba(255, 255, 255, 0.2)',
                                        borderRadius: 5,
                                    },
                                }}
                                disabled={!isPlaying}
                            />
                            <Typography
                                variant="caption"
                                sx={{
                                    minWidth: 32,
                                    textAlign: 'right',
                                    color: 'rgba(255, 255, 255, 0.7)',
                                    fontFamily: '"Poppins", sans-serif',
                                    fontSize: '0.75rem',
                                    ml: 2,
                                }}
                            >
                                {volume}%
                            </Typography>
                        </VolumeContainer>

                        {/* Controls */}
                        <ControlsContainer>
                            <Button
                                variant="contained"
                                startIcon={<PlayArrow />}
                                onClick={connectWebSocket}
                                disabled={isPlaying}
                                size="large"
                                sx={{
                                    minWidth: 120,
                                    borderRadius: '50px',
                                    background: '#1db954',
                                    color: 'white',
                                    fontFamily: '"Poppins", sans-serif',
                                    fontSize: '0.9rem',
                                    fontWeight: 600,
                                    textTransform: 'none',
                                    py: 1.5,
                                    boxShadow: '0 4px 12px rgba(29, 185, 84, 0.3)',
                                    '&:hover': {
                                        background: '#1ed760',
                                        transform: 'translateY(-2px)',
                                        boxShadow: '0 6px 16px rgba(29, 185, 84, 0.4)',
                                    },
                                    '&:disabled': {
                                        background: '#666',
                                        color: '#999',
                                        transform: 'none',
                                        boxShadow: 'none',
                                    }
                                }}
                            >
                                Start
                            </Button>
                            <Button
                                variant="contained"
                                startIcon={<Stop />}
                                onClick={stopStream}
                                disabled={!isPlaying}
                                size="large"
                                sx={{
                                    minWidth: 120,
                                    borderRadius: '50px',
                                    background: '#1db954',
                                    color: 'white',
                                    fontFamily: '"Poppins", sans-serif',
                                    fontSize: '0.9rem',
                                    fontWeight: 600,
                                    textTransform: 'none',
                                    py: 1.5,
                                    boxShadow: '0 4px 12px rgba(29, 185, 84, 0.3)',
                                    '&:hover': {
                                        background: '#1ed760',
                                        transform: 'translateY(-2px)',
                                        boxShadow: '0 6px 16px rgba(29, 185, 84, 0.4)',
                                    },
                                    '&:disabled': {
                                        background: '#666',
                                        color: '#999',
                                        transform: 'none',
                                        boxShadow: 'none',
                                    }
                                }}
                            >
                                Stop
                            </Button>
                        </ControlsContainer>

                        {/* Status */}
                        <Box sx={{
                            textAlign: 'center',
                            py: 2,
                            background: 'rgba(0, 0, 0, 0.2)',
                            borderRadius: 2,
                            mt: 2,
                            border: '1px solid rgba(255, 255, 255, 0.05)',
                            position: 'relative',
                        }}>
                            <Typography
                                variant="body2"
                                sx={{
                                    color: connectionStatus === ConnectionStatus.Connected || connectionStatus === ConnectionStatus.Streaming ? '#1db954' : 'rgba(255, 255, 255, 0.7)',
                                    fontFamily: '"Poppins", sans-serif',
                                    fontSize: '0.85rem',
                                }}
                            >
                                {connectionStatus}
                            </Typography>

                            {/* Bottom right icons */}
                            <Box sx={{
                                position: 'absolute',
                                bottom: 8,
                                right: 8,
                                display: 'flex',
                                gap: 1
                            }}>
                                <IconButton
                                    size="small"
                                    sx={{
                                        color: 'rgba(255, 255, 255, 0.5)',
                                        '&:hover': { color: '#1db954' }
                                    }}
                                    onClick={() => window.open('https://github.com', '_blank')}
                                >
                                    <GitHub fontSize="small" />
                                </IconButton>
                                <IconButton
                                    size="small"
                                    sx={{
                                        color: 'rgba(255, 255, 255, 0.5)',
                                        '&:hover': { color: '#1db954' }
                                    }}
                                    onClick={() => window.open('/docs', '_blank')}
                                >
                                    <MenuBook fontSize="small" />
                                </IconButton>
                            </Box>
                        </Box>

                        {/* Loading indicator */}
                        {connectionStatus === ConnectionStatus.Connecting && (
                            <LinearProgress
                                sx={{
                                    mt: 1,
                                    borderRadius: 1,
                                    backgroundColor: 'rgba(255, 255, 255, 0.1)',
                                    '& .MuiLinearProgress-bar': {
                                        backgroundColor: '#1db954',
                                        borderRadius: 1,
                                    }
                                }}
                            />
                        )}
                    </CardContent>
                </PlayerCard>
            </Box>
        </>
    );
};

export default AudioStreamPlayer;