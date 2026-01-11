import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { useLiveAPIContext } from "../contexts/LiveAPIContext";
import { useWebcam } from "../hooks/use-webcam";
import { useScreenCapture } from "../hooks/use-screen-capture";
import { AudioRecorder } from "../lib/audio-recorder";
import { analyzeFrame } from "../lib/vision-service";
import {
  startFrameCapture,
  stopFrameCapture,
  getLatestFrame,
  captureHighQualityFrame,
  clearFrameBuffer
} from "../lib/frame-buffer";
import { ChatMessage } from "./ChatPanel";
import { XenoAvatar } from "./XenoAvatar";
import {
  Mic,
  MicOff,
  Video,
  VideoOff,
  Monitor,
  Phone,
  PhoneOff,
  X
} from "lucide-react";

interface VoiceInterfaceProps {
  videoRef: React.RefObject<HTMLVideoElement | null>;
  onVideoStreamChange: (stream: MediaStream | null) => void;
  onAudioMutedChange?: (muted: boolean) => void;
  onWebcamChange?: (active: boolean) => void;
  onScreenShareChange?: (active: boolean) => void;
  onAssistantSpeakingChange?: (speaking: boolean) => void;
  onNewMessage?: (message: ChatMessage) => void; // For future transcript integration
  videoQuality?: number;
}

// Full Orb Visualizer
function OrbVisualizer({
  isActive,
  isListening,
  isSpeaking,
  volume
}: {
  isActive: boolean;
  isListening: boolean;
  isSpeaking: boolean;
  volume: number;
}) {
  const intensity = Math.min(volume * 3, 1);
  const size = 200 + (isSpeaking ? intensity * 40 : 0);

  const orbColor = useMemo(() => {
    if (isSpeaking) return "from-blue-500 to-cyan-400";
    if (isListening) return "from-green-500 to-emerald-400";
    return "from-violet-500 to-purple-400";
  }, [isSpeaking, isListening]);

  if (!isActive) {
    return (
      <div className="w-48 h-48 rounded-full bg-white/5 border border-white/10 flex items-center justify-center">
        <div className="w-32 h-32 rounded-full bg-gradient-to-br from-gray-600 to-gray-700 opacity-50" />
      </div>
    );
  }

  return (
    <div className="relative flex items-center justify-center">
      {/* Glow */}
      <motion.div
        className={`absolute rounded-full bg-gradient-to-br ${orbColor} blur-3xl opacity-30`}
        animate={{
          width: size + 80,
          height: size + 80,
          opacity: isSpeaking ? [0.3, 0.5, 0.3] : 0.2,
        }}
        transition={{ duration: 1.5, repeat: Infinity, ease: "easeInOut" }}
      />

      {/* Main orb */}
      <motion.div
        className={`rounded-full bg-gradient-to-br ${orbColor} shadow-2xl`}
        animate={{ width: size, height: size }}
        transition={{ duration: 0.1, ease: "easeOut" }}
      />

      {/* Inner highlight */}
      <motion.div
        className="absolute top-1/4 left-1/4 w-1/3 h-1/3 rounded-full bg-white/20 blur-xl"
        animate={{ opacity: [0.2, 0.4, 0.2] }}
        transition={{ duration: 2, repeat: Infinity, ease: "easeInOut" }}
      />

      {/* Pulse rings */}
      <AnimatePresence>
        {isSpeaking && [0, 1, 2].map((i) => (
          <motion.div
            key={i}
            className="absolute rounded-full border-2 border-blue-400/50"
            initial={{ width: size, height: size, opacity: 0.5 }}
            animate={{
              width: [size, size + 150],
              height: [size, size + 150],
              opacity: [0.5, 0],
            }}
            transition={{ duration: 1.5, repeat: Infinity, delay: i * 0.4, ease: "easeOut" }}
          />
        ))}
      </AnimatePresence>
    </div>
  );
}

// Control Button
function ControlButton({
  onClick,
  active,
  icon: Icon,
  label,
  disabled,
  variant = "default",
  size = "md"
}: {
  onClick: () => void;
  active?: boolean;
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  disabled?: boolean;
  variant?: "default" | "primary" | "danger";
  size?: "sm" | "md";
}) {
  const sizeClasses = size === "sm" ? "w-10 h-10 rounded-xl" : "w-14 h-14 rounded-2xl";
  const iconSize = size === "sm" ? "w-5 h-5" : "w-6 h-6";

  const variantClasses = {
    default: active
      ? "bg-white/10 border-white/20 text-white"
      : "bg-white/5 border-white/10 text-white/60 hover:bg-white/10 hover:text-white",
    primary: "bg-blue-500 hover:bg-blue-600 text-white shadow-lg shadow-blue-500/25",
    danger: "bg-red-500 hover:bg-red-600 text-white shadow-lg shadow-red-500/25"
  };

  return (
    <motion.button
      onClick={onClick}
      disabled={disabled}
      className={`${sizeClasses} flex items-center justify-center transition-all duration-200 ${variantClasses[variant]} border disabled:opacity-40 disabled:cursor-not-allowed`}
      whileHover={{ scale: disabled ? 1 : 1.05 }}
      whileTap={{ scale: disabled ? 1 : 0.95 }}
      title={label}
    >
      <Icon className={iconSize} />
    </motion.button>
  );
}

export function VoiceInterface({
  videoRef,
  onVideoStreamChange,
  onAudioMutedChange,
  onWebcamChange,
  onScreenShareChange,
  onAssistantSpeakingChange,
  onNewMessage: _onNewMessage,
  videoQuality: _videoQuality = 75
}: VoiceInterfaceProps) {
  const { client, connected, connect, disconnect, volume } = useLiveAPIContext();
  const [audioMuted, setAudioMuted] = useState(false);
  const [inVolume, setInVolume] = useState(0);
  const [audioRecorder] = useState(() => new AudioRecorder());
  const [isUserSpeaking, setIsUserSpeaking] = useState(false);
  const [isAssistantSpeaking, setIsAssistantSpeaking] = useState(false);

  const webcam = useWebcam();
  const screenCapture = useScreenCapture();
  const [activeVideoStream, setActiveVideoStream] = useState<MediaStream | null>(null);
  const renderCanvasRef = useRef<HTMLCanvasElement>(null);
  const videoPreviewRef = useRef<HTMLVideoElement>(null);

  // Throttle volume updates
  const lastVolumeUpdate = useRef(0);
  const throttledSetVolume = useCallback((vol: number) => {
    const now = Date.now();
    if (now - lastVolumeUpdate.current > 50) {
      lastVolumeUpdate.current = now;
      setInVolume(vol);
    }
  }, []);

  // User speaking detection
  const isUserSpeakingRef = useRef(false);
  const speakingTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    if (inVolume > 0.1 && connected && !audioMuted) {
      if (!isUserSpeakingRef.current) {
        isUserSpeakingRef.current = true;
        setIsUserSpeaking(true);
      }
      if (speakingTimeoutRef.current) clearTimeout(speakingTimeoutRef.current);
      speakingTimeoutRef.current = setTimeout(() => {
        isUserSpeakingRef.current = false;
        setIsUserSpeaking(false);
      }, 500);
    }
    return () => {
      if (speakingTimeoutRef.current) clearTimeout(speakingTimeoutRef.current);
    };
  }, [inVolume, connected, audioMuted]);

  // Assistant speaking detection
  useEffect(() => {
    const speaking = volume > 0.01;
    setIsAssistantSpeaking(speaking);
    onAssistantSpeakingChange?.(speaking);
  }, [volume, onAssistantSpeakingChange]);

  // Notify parent of audio muted changes
  useEffect(() => {
    onAudioMutedChange?.(audioMuted);
  }, [audioMuted, onAudioMutedChange]);

  // Notify parent of webcam changes
  useEffect(() => {
    onWebcamChange?.(webcam.isStreaming);
  }, [webcam.isStreaming, onWebcamChange]);

  // Notify parent of screen share changes
  useEffect(() => {
    onScreenShareChange?.(screenCapture.isStreaming);
  }, [screenCapture.isStreaming, onScreenShareChange]);

  // Audio recording
  useEffect(() => {
    const onData = (base64: string) => {
      if (connected) {
        try {
          client.sendRealtimeInput([{ mimeType: "audio/pcm;rate=16000", data: base64 }]);
        } catch { }
      }
    };

    if (connected && !audioMuted && audioRecorder) {
      audioRecorder.on("data", onData).on("volume", throttledSetVolume);
      audioRecorder.start().catch(console.error);
    } else {
      audioRecorder.stop();
    }

    return () => {
      audioRecorder.off("data", onData).off("volume", throttledSetVolume);
    };
  }, [connected, client, audioMuted, audioRecorder, throttledSetVolume]);

  // Capture a frame from the video for vision analysis
  // ALWAYS capture fresh frame for accurate, current visual state
  const captureFrame = useCallback((): string | null => {
    const video = videoPreviewRef.current || videoRef.current;

    // Always try to capture fresh frame first (most accurate)
    if (video && video.readyState >= 2 && video.videoWidth > 0) {
      const hqFrame = captureHighQualityFrame(video);
      if (hqFrame) {
        console.log(`[Vision] Captured FRESH frame (${hqFrame.width}x${hqFrame.height})`);
        return hqFrame.data;
      }
    }

    // Fallback to buffer only if live capture fails
    const bufferedFrame = getLatestFrame();
    if (bufferedFrame) {
      console.log(`[Vision] Using buffered frame (${bufferedFrame.width}x${bufferedFrame.height}) - live capture failed`);
      return bufferedFrame.data;
    }

    // Legacy fallback using canvas
    const canvas = renderCanvasRef.current;
    if (!video || !canvas || video.readyState < 2 || video.videoWidth === 0) {
      console.log('[Vision] Cannot capture frame - video not ready');
      return null;
    }

    const ctx = canvas.getContext("2d")!;
    const isScreenShare = screenCapture.isStreaming;

    // High quality for vision analysis
    const scale = isScreenShare ? 0.9 : 0.8;
    canvas.width = video.videoWidth * scale;
    canvas.height = video.videoHeight * scale;

    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    const base64 = canvas.toDataURL("image/jpeg", 0.95);
    const data = base64.slice(base64.indexOf(",") + 1);

    console.log(`[Vision] Captured frame (${canvas.width}x${canvas.height})`);
    return data;
  }, [screenCapture.isStreaming, videoRef]);

  // Handle tool calls from the AI model
  useEffect(() => {
    const handleToolCall = async (toolCall: { functionCalls: Array<{ name: string; id: string; args: any }> }) => {
      // Process each function call in the tool call
      for (const fc of toolCall.functionCalls) {
        console.log('[Tool] Received call:', fc.name, fc.args);

        if (fc.name === 'analyzeVisual') {
          const query = fc.args?.query || 'describe what you see';

          // Check if video is active
          if (!activeVideoStream) {
            client.sendToolResponse({
              functionResponses: [{
                id: fc.id,
                name: fc.name,
                response: { response: "No camera or screen sharing is active. Please enable camera or screen sharing first." }
              }]
            });
            continue;
          }

          // Capture current frame
          const frameData = captureFrame();
          if (!frameData) {
            client.sendToolResponse({
              functionResponses: [{
                id: fc.id,
                name: fc.name,
                response: { response: "Could not capture frame. The video may not be ready yet." }
              }]
            });
            continue;
          }

          console.log('[Vision] Analyzing frame with query:', query);

          // Analyze with vision service
          try {
            const analysis = await analyzeFrame(frameData);

            // Format response as string for the audio model
            let responseText: string;
            if (analysis && analysis.context) {
              // Use the full context directly - it contains the complete description
              responseText = analysis.context;
            } else if (analysis && analysis.description) {
              responseText = analysis.description;
            } else {
              responseText = "I couldn't analyze the image clearly. Please try again.";
            }

            console.log('[Vision] Analysis result:', responseText);

            // Send response in official SDK format
            client.sendToolResponse({
              functionResponses: [{
                id: fc.id,
                name: fc.name,
                response: { response: responseText }
              }]
            });
          } catch (error) {
            console.error('[Vision] Analysis error:', error);
            client.sendToolResponse({
              functionResponses: [{
                id: fc.id,
                name: fc.name,
                response: { response: "Failed to analyze the visual. Please try again." }
              }]
            });
          }
        }
      }
    };

    client.on('toolcall', handleToolCall);
    return () => {
      client.off('toolcall', handleToolCall);
    };
  }, [client, activeVideoStream, captureFrame, screenCapture.isStreaming]);

  // Set video srcObject when stream changes
  useEffect(() => {
    if (videoRef.current && activeVideoStream) {
      videoRef.current.srcObject = activeVideoStream;
    }
  }, [activeVideoStream, videoRef]);

  // Handle stream selection
  useEffect(() => {
    if (webcam.isStreaming) {
      setActiveVideoStream(webcam.stream);
      onVideoStreamChange(webcam.stream);
    } else if (screenCapture.isStreaming) {
      setActiveVideoStream(screenCapture.stream);
      onVideoStreamChange(screenCapture.stream);
    } else {
      setActiveVideoStream(null);
      onVideoStreamChange(null);
    }
  }, [webcam.isStreaming, webcam.stream, screenCapture.isStreaming, screenCapture.stream, onVideoStreamChange]);

  // Stable callback ref for video preview - sets srcObject when element mounts
  const setVideoPreviewRef = useCallback((el: HTMLVideoElement | null) => {
    videoPreviewRef.current = el;
    if (el && activeVideoStream) {
      el.srcObject = activeVideoStream;
    }
  }, [activeVideoStream]);

  // Also update srcObject when stream changes on existing element
  // And start/stop frame buffer capture
  useEffect(() => {
    // Clear old frames when video source changes
    clearFrameBuffer();

    if (videoPreviewRef.current && activeVideoStream) {
      videoPreviewRef.current.srcObject = activeVideoStream;
      // Start continuous frame capture with fresh buffer
      startFrameCapture(videoPreviewRef.current);
      console.log('[FrameBuffer] Started continuous frame capture');
    } else {
      // Stop frame capture when no video
      stopFrameCapture();
    }

    return () => {
      stopFrameCapture();
    };
  }, [activeVideoStream]);

  const handleConnect = useCallback(async () => {
    if (connected) {
      await disconnect();
      webcam.stop();
      screenCapture.stop();
      audioRecorder.stop();
    } else {
      await connect();
    }
  }, [connected, connect, disconnect, webcam, screenCapture, audioRecorder]);

  const handleVideoToggle = useCallback(async (type: 'webcam' | 'screen') => {
    if (type === 'webcam') {
      if (webcam.isStreaming) {
        webcam.stop();
      } else {
        // Stop screen capture first, small delay, then start webcam
        screenCapture.stop();
        await new Promise(r => setTimeout(r, 100));
        await webcam.start();
      }
    } else {
      if (screenCapture.isStreaming) {
        screenCapture.stop();
      } else {
        // Stop webcam first, small delay, then start screen capture
        webcam.stop();
        await new Promise(r => setTimeout(r, 100));
        try {
          await screenCapture.start();
          console.log('[Screen] Screen capture started');
        } catch (err) {
          console.error('[Screen] Failed to start screen capture:', err);
        }
      }
    }
  }, [webcam, screenCapture]);

  const stopVideo = useCallback(() => {
    webcam.stop();
    screenCapture.stop();
  }, [webcam, screenCapture]);

  const toggleMute = useCallback(() => setAudioMuted(prev => !prev), []);

  const isVideoActive = webcam.isStreaming || screenCapture.isStreaming;
  const videoType = webcam.isStreaming ? 'Camera' : 'Screen';

  return (
    <div className="flex-1 flex flex-col">
      <canvas ref={renderCanvasRef} className="hidden" />

      {/* XENO Avatar - Animated Character */}
      {connected && (
        <XenoAvatar
          isSpeaking={isAssistantSpeaking}
          isListening={isUserSpeaking}
          isThinking={!isAssistantSpeaking && !isUserSpeaking && connected}
          audioLevel={volume}
        />
      )}

      {/* Main Content */}
      <main className="flex-1 flex flex-col items-center justify-center px-6 py-8">

        {/* Video Active Layout */}
        <AnimatePresence mode="wait">
          {isVideoActive && activeVideoStream ? (
            <motion.div
              key="video"
              initial={{ opacity: 0, scale: 0.95 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.95 }}
              transition={{ duration: 0.3 }}
              className="w-full max-w-4xl"
            >
              {/* Video Header */}
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center space-x-3">
                  <div className={`w-3 h-3 rounded-full ${webcam.isStreaming ? 'bg-blue-500' : 'bg-green-500'} animate-pulse`} />
                  <span className="text-white/80 font-medium">{videoType} Sharing</span>
                  <span className="text-white/40 text-sm">• AI can see this</span>
                </div>
                <motion.button
                  onClick={stopVideo}
                  className="flex items-center space-x-2 px-3 py-1.5 rounded-lg bg-red-500/20 border border-red-500/30 text-red-400 hover:bg-red-500/30 transition-all"
                  whileHover={{ scale: 1.02 }}
                  whileTap={{ scale: 0.98 }}
                >
                  <X className="w-4 h-4" />
                  <span className="text-sm font-medium">Stop</span>
                </motion.button>
              </div>

              {/* Video Preview */}
              <div className="relative rounded-2xl overflow-hidden border border-white/10 shadow-2xl bg-black aspect-video">
                <video
                  ref={setVideoPreviewRef}
                  autoPlay
                  muted
                  playsInline
                  className="w-full h-full object-contain"
                />

                {/* Overlay Controls */}
                <div className="absolute bottom-4 left-1/2 -translate-x-1/2 flex items-center space-x-3 bg-black/50 backdrop-blur-sm px-4 py-2 rounded-full">
                  <ControlButton
                    onClick={toggleMute}
                    active={!audioMuted}
                    disabled={!connected}
                    icon={audioMuted ? MicOff : Mic}
                    label="Mute"
                    size="sm"
                  />
                  <motion.button
                    onClick={handleConnect}
                    className={`w-12 h-12 rounded-full flex items-center justify-center ${connected ? "bg-red-500" : "bg-blue-500"
                      }`}
                    whileHover={{ scale: 1.05 }}
                    whileTap={{ scale: 0.95 }}
                  >
                    {connected ? <PhoneOff className="w-5 h-5 text-white" /> : <Phone className="w-5 h-5 text-white" />}
                  </motion.button>
                  <ControlButton
                    onClick={() => handleVideoToggle('webcam')}
                    active={webcam.isStreaming}
                    disabled={!connected}
                    icon={webcam.isStreaming ? Video : VideoOff}
                    label="Camera"
                    size="sm"
                  />
                  <ControlButton
                    onClick={() => handleVideoToggle('screen')}
                    active={screenCapture.isStreaming}
                    disabled={!connected}
                    icon={Monitor}
                    label="Screen"
                    size="sm"
                  />
                </div>
              </div>
            </motion.div>
          ) : (
            /* Default Orb Layout */
            <motion.div
              key="orb"
              initial={{ opacity: 0, scale: 0.95 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.95 }}
              transition={{ duration: 0.3 }}
              className="flex flex-col items-center"
            >
              {/* Status */}
              <motion.div
                className="text-center mb-8"
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
              >
                <h1 className="text-2xl font-semibold text-white/90 mb-2">
                  {connected
                    ? isAssistantSpeaking
                      ? "Speaking..."
                      : isUserSpeaking
                        ? "Listening..."
                        : "Ready"
                    : "Start a Conversation"
                  }
                </h1>
                <p className="text-white/50 text-sm">
                  {connected
                    ? "Speak naturally or enable video"
                    : "Click the button below to connect"
                  }
                </p>
              </motion.div>

              {/* Orb */}
              <div className="mb-12">
                <OrbVisualizer
                  isActive={connected}
                  isListening={isUserSpeaking}
                  isSpeaking={isAssistantSpeaking}
                  volume={Math.max(inVolume, volume)}
                />
              </div>

              {/* Controls */}
              <motion.div
                className="flex items-center space-x-4"
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.1 }}
              >
                <ControlButton
                  onClick={toggleMute}
                  active={!audioMuted}
                  disabled={!connected}
                  icon={audioMuted ? MicOff : Mic}
                  label="Mute"
                />

                <motion.button
                  onClick={handleConnect}
                  className={`w-20 h-20 rounded-full flex items-center justify-center transition-all duration-200 ${connected
                    ? "bg-red-500 hover:bg-red-600 shadow-lg shadow-red-500/30"
                    : "bg-blue-500 hover:bg-blue-600 shadow-lg shadow-blue-500/30"
                    }`}
                  whileHover={{ scale: 1.05 }}
                  whileTap={{ scale: 0.95 }}
                >
                  {connected ? <PhoneOff className="w-8 h-8 text-white" /> : <Phone className="w-8 h-8 text-white" />}
                </motion.button>

                <ControlButton
                  onClick={() => handleVideoToggle('webcam')}
                  active={webcam.isStreaming}
                  disabled={!connected}
                  icon={webcam.isStreaming ? Video : VideoOff}
                  label="Camera"
                />

                <ControlButton
                  onClick={() => handleVideoToggle('screen')}
                  active={screenCapture.isStreaming}
                  disabled={!connected}
                  icon={Monitor}
                  label="Screen Share"
                />
              </motion.div>
            </motion.div>
          )}
        </AnimatePresence>
      </main>

      {/* Footer */}
      <footer className="px-6 py-4 text-center">
        <p className="text-white/50 text-sm font-bold tracking-wider">XENO AI</p>
      </footer>
    </div>
  );
}
