import { useState, useRef, useEffect, useMemo, useCallback } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Mic, MicOff, Video, VideoOff, Play, Pause, Square, Send, Trash2 } from "lucide-react";
import { useLiveAPIContext } from "../contexts/LiveAPIContext";
import { useWebcam } from "../hooks/use-webcam";
import { useScreenCapture } from "../hooks/use-screen-capture";
import { AudioRecorder } from "../lib/audio-recorder";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { ScrollArea } from "./ui/scroll-area";
import { PolkaDotSphere } from "./PolkaDotSphere"; // Import our new component

interface VoiceInterfaceProps {
  videoRef: React.RefObject<HTMLVideoElement | null>;
  onVideoStreamChange: (stream: MediaStream | null) => void;
}

interface Message {
  id: string;
  type: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

export function VoiceInterface({ videoRef, onVideoStreamChange }: VoiceInterfaceProps) {
  const { client, connected, connect, disconnect, volume } = useLiveAPIContext();
  const [isRecording, setIsRecording] = useState(false);
  const [audioMuted, setAudioMuted] = useState(false);
  const [inVolume, setInVolume] = useState(0);
  const [audioRecorder] = useState(() => new AudioRecorder());
  
  
  // New states for speech detection
  const [isUserSpeaking, setIsUserSpeaking] = useState(false);
  const [isAssistantSpeaking, setIsAssistantSpeaking] = useState(false);
  
  const webcam = useWebcam();
  const screenCapture = useScreenCapture();
  const [activeVideoStream, setActiveVideoStream] = useState<MediaStream | null>(null);
  const renderCanvasRef = useRef<HTMLCanvasElement>(null);

  // Conversation state
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);

  // Stable video state to prevent flickering
  const isVideoActive = useMemo(() => 
    webcam.isStreaming || screenCapture.isStreaming, 
    [webcam.isStreaming, screenCapture.isStreaming]
  );

  const videoType = useMemo(() => 
    webcam.isStreaming ? 'camera' : screenCapture.isStreaming ? 'screen' : null, 
    [webcam.isStreaming, screenCapture.isStreaming]
  );

  const addMessage = useCallback((type: 'user' | 'assistant', content: string) => {
    const newMessage: Message = {
      id: Date.now().toString(),
      type,
      content,
      timestamp: new Date(),
    };
    setMessages(prev => [...prev, newMessage]);
  }, []);

  const handleConnect = useCallback(async () => {
    try {
      if (connected) {
        await disconnect();
        setIsRecording(false);
        setIsUserSpeaking(false);
        setIsAssistantSpeaking(false);
      } else {
        await connect();
        setIsRecording(true);
      }
    } catch (error) {
      console.error("Connection error:", error);
    }
  }, [connected, connect, disconnect]);

  const toggleMute = useCallback(() => {
    setAudioMuted(!audioMuted);
  }, [audioMuted]);

  const handleSend = useCallback(() => {
    if (!inputText.trim() || !connected) return;
    
    addMessage('user', inputText);
    client.send([{ text: inputText }]);
    setInputText("");
  }, [inputText, connected, addMessage, client]);

  const handleKeyPress = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }, [handleSend]);

  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  // Listen for AI responses and detect speaking states
  useEffect(() => {
    const handleContent = (data: any) => {
      if (data.modelTurn?.parts?.[0]?.text) {
        addMessage('assistant', data.modelTurn.parts[0].text);
        setIsAssistantSpeaking(true);
        // Stop assistant speaking after 2 seconds
        setTimeout(() => setIsAssistantSpeaking(false), 2000);
      }
    };

    client.on("content", handleContent);
    return () => {
      client.off("content", handleContent);
    };
  }, [client, addMessage]);

  // Auto-scroll to bottom
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages]);

  // Detect user speaking based on volume
  useEffect(() => {
    if (inVolume > 0.1 && connected && !audioMuted) {
      setIsUserSpeaking(true);
      const timer = setTimeout(() => setIsUserSpeaking(false), 500);
      return () => clearTimeout(timer);
    } else {
      setIsUserSpeaking(false);
    }
  }, [inVolume, connected, audioMuted]);

  // Audio recording logic
  useEffect(() => {
    const onData = (base64: string) => {
      client.sendRealtimeInput([
        {
          mimeType: "audio/pcm;rate=16000",
          data: base64,
        },
      ]);
    };

    if (connected && !audioMuted && audioRecorder) {
      audioRecorder.on("data", onData).on("volume", setInVolume).start();
    } else {
      audioRecorder.stop();
    }

    return () => {
      audioRecorder.off("data", onData).off("volume", setInVolume);
    };
  }, [connected, client, audioMuted, audioRecorder]);

  // Video streaming logic (unchanged)
  useEffect(() => {
    if (videoRef.current) {
      videoRef.current.srcObject = activeVideoStream;
    }

    let timeoutId = -1;
    function sendVideoFrame() {
      const video = videoRef.current;
      const canvas = renderCanvasRef.current;
      if (!video || !canvas) return;

      const ctx = canvas.getContext("2d")!;
      canvas.width = video.videoWidth * 0.25;
      canvas.height = video.videoHeight * 0.25;
      if (canvas.width + canvas.height > 0 && videoRef.current) {
        ctx.drawImage(videoRef.current, 0, 0, canvas.width, canvas.height);
        const base64 = canvas.toDataURL("image/jpeg", 1.0);
        const data = base64.slice(base64.indexOf(",") + 1, Infinity);
        client.sendRealtimeInput([{ mimeType: "image/jpeg", data }]);
      }

      if (connected) {
        timeoutId = window.setTimeout(sendVideoFrame, 1000 / 0.5);
      }
    }

    if (connected && activeVideoStream !== null) {
      requestAnimationFrame(sendVideoFrame);
    }

    return () => {
      clearTimeout(timeoutId);
    };
  }, [connected, activeVideoStream, client, videoRef]);

  const handleVideoToggle = useCallback(async (type: 'webcam' | 'screen') => {
    try {
      if (type === 'webcam') {
        if (webcam.isStreaming) {
          webcam.stop();
          setActiveVideoStream(null);
          onVideoStreamChange(null);
        } else {
          const stream = await webcam.start();
          setActiveVideoStream(stream);
          onVideoStreamChange(stream);
          screenCapture.stop();
        }
      } else {
        if (screenCapture.isStreaming) {
          screenCapture.stop();
          setActiveVideoStream(null);
          onVideoStreamChange(null);
        } else {
          const stream = await screenCapture.start();
          setActiveVideoStream(stream);
          onVideoStreamChange(stream);
          webcam.stop();
        }
      }
    } catch (error) {
      console.error(`Error toggling ${type}:`, error);
      alert(`Could not access ${type}. Please check permissions.`);
    }
  }, [webcam, screenCapture, onVideoStreamChange]);

  // Memoized Video Component
  const videoComponent = useMemo(() => {
    if (!isVideoActive || !activeVideoStream) return null;

    return (
      <div
        className="relative rounded-3xl overflow-hidden border-4 border-slate-600/50 dark:border-slate-600/50 light:border-emerald-200/30 shadow-2xl bg-slate-800 dark:bg-slate-800 light:bg-gradient-to-br light:from-emerald-500 light:to-teal-500"
        style={{ width: '480px', height: '360px' }}
      >
        <video
          autoPlay
          muted
          playsInline
          className="w-full h-full object-cover"
          ref={(el) => {
            if (el && activeVideoStream) {
              el.srcObject = activeVideoStream;
            }
          }}
        />
        
        <div className="absolute top-4 left-4 bg-slate-900/80 dark:bg-slate-900/80 light:bg-white/90 text-slate-200 dark:text-slate-200 light:text-emerald-800 px-3 py-1 rounded-full text-sm font-medium border border-slate-600/50 dark:border-slate-600/50 light:border-white/50">
          {videoType === 'camera' ? '📹 Camera' : '🖥️ Screen Share'}
        </div>
        
        {connected && (
          <div className="absolute top-4 right-4 bg-emerald-500/90 dark:bg-emerald-500/90 light:bg-gradient-to-r light:from-green-400 light:to-emerald-500 text-white px-3 py-1 rounded-full text-sm font-medium shadow-lg">
            🔴 Live
          </div>
        )}
      </div>
    );
  }, [isVideoActive, activeVideoStream, videoType, connected]);

  // NEW: Memoized Polka Dot Sphere Component (replaces AudioVisualizer)
  const polkaDotSphereComponent = useMemo(() => (
    <div className="relative flex items-center justify-center">
      <PolkaDotSphere 
        isActive={connected && isRecording}
        volume={Math.max(inVolume, volume)}
        isUserSpeaking={isUserSpeaking}
        isAssistantSpeaking={isAssistantSpeaking}
        size={400}
      />
      
      {/* Central Microphone Button - positioned over the sphere */}
      <motion.div
        className="absolute z-30"
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.95 }}
      >
        <Button
          onClick={handleConnect}
          size="lg"
          className={`w-20 h-20 rounded-full text-white shadow-2xl transition-all duration-300 border-3 backdrop-blur-sm ${
            connected 
              ? 'bg-red-500/90 hover:bg-red-600/90 border-red-400/50 shadow-red-500/30' 
              : 'bg-gradient-to-br from-emerald-500/90 to-teal-500/90 dark:from-blue-500/90 dark:to-cyan-500/90 hover:from-emerald-600/90 hover:to-teal-600/90 dark:hover:from-blue-600/90 dark:hover:to-cyan-600/90 border-emerald-400/50 dark:border-blue-400/50 shadow-emerald-500/30 dark:shadow-blue-500/30'
          }`}
        >
          {connected ? (
            <Pause className="h-8 w-8" />
          ) : (
            <Play className="h-8 w-8 ml-0.5" />
          )}
        </Button>
      </motion.div>
    </div>
  ), [connected, isRecording, inVolume, volume, isUserSpeaking, isAssistantSpeaking, handleConnect]);

  return (
    <div className="h-full grid grid-cols-3 gap-6">
      {/* Hidden canvas for video processing */}
      <canvas ref={renderCanvasRef} className="hidden" />

      {/* Left Column - Main Voice Interface */}
      <div className="col-span-2 flex flex-col items-center justify-center space-y-8">
        
        {/* Main Content Area - Either Video or Polka Dot Sphere */}
        <div className="relative flex items-center justify-center">
          <AnimatePresence mode="wait" initial={false}>
            {isVideoActive ? (
              <motion.div
                key="video-display"
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.9 }}
                transition={{ 
                  duration: 0.3,
                  ease: "easeInOut"
                }}
              >
                {videoComponent}
              </motion.div>
            ) : (
              <motion.div
                key="sphere-display"
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.9 }}
                transition={{ 
                  duration: 0.3,
                  ease: "easeInOut"
                }}
              >
                {polkaDotSphereComponent}
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* Status Text */}
        {useMemo(() => (
          <motion.div 
            className="text-center space-y-3 max-w-md"
            animate={{ opacity: connected ? 1 : 0.8 }}
            transition={{ duration: 0.3 }}
          >
            <h2 className="text-3xl font-bold text-slate-100 dark:text-slate-100 light:text-white">
              {connected ? "XENO is listening..." : "Ready to connect"}
            </h2>
            <p className="text-slate-400 dark:text-slate-400 light:text-white/90 text-lg leading-relaxed">
              {isVideoActive 
                ? `${videoType === 'camera' ? 'Camera' : 'Screen'} is active - I can see you clearly` 
                : connected 
                  ? "Speak naturally, watch the sphere respond" 
                  : "Click to start your AI conversation"
              }
            </p>
          </motion.div>
        ), [connected, isVideoActive, videoType])}

        {/* Control Panel */}
        {useMemo(() => (
          <div className="flex items-center space-x-6">
            <Button
              variant="outline"
              size="icon"
              onClick={toggleMute}
              disabled={!connected}
              className={`w-14 h-14 rounded-full transition-all duration-200 ${
                audioMuted 
                  ? 'bg-red-500/20 border-red-500/50 text-red-300 hover:bg-red-500/30' 
                  : 'bg-slate-700/50 dark:bg-slate-700/50 light:bg-emerald-100/30 border-slate-600/50 dark:border-slate-600/50 light:border-emerald-200/50 text-slate-300 dark:text-slate-300 light:text-emerald-800 hover:bg-slate-600/50 dark:hover:bg-slate-600/50 light:hover:bg-emerald-100/50 hover:text-slate-200 dark:hover:text-slate-200 light:hover:text-emerald-700'
              } backdrop-blur-sm border-2`}
            >
              {audioMuted ? <MicOff className="h-6 w-6" /> : <Mic className="h-6 w-6" />}
            </Button>

            <Button
              variant="outline"
              size="icon"
              onClick={() => handleVideoToggle('webcam')}
              disabled={!connected}
              className={`w-14 h-14 rounded-full transition-all duration-200 ${
                webcam.isStreaming 
                  ? 'bg-blue-500/20 dark:bg-blue-500/20 light:bg-teal-400/20 border-blue-500/50 dark:border-blue-500/50 light:border-teal-400/50 text-blue-300 dark:text-blue-300 light:text-teal-700 hover:bg-blue-500/30 dark:hover:bg-blue-500/30 light:hover:bg-teal-400/30' 
                  : 'bg-slate-700/50 dark:bg-slate-700/50 light:bg-emerald-100/30 border-slate-600/50 dark:border-slate-600/50 light:border-emerald-200/50 text-slate-300 dark:text-slate-300 light:text-emerald-800 hover:bg-slate-600/50 dark:hover:bg-slate-600/50 light:hover:bg-emerald-100/50 hover:text-slate-200 dark:hover:text-slate-200 light:hover:text-emerald-700'
              } backdrop-blur-sm border-2`}
            >
              {webcam.isStreaming ? <Video className="h-6 w-6" /> : <VideoOff className="h-6 w-6" />}
            </Button>

            <Button
              variant="outline"
              size="icon"
              onClick={() => handleVideoToggle('screen')}
              disabled={!connected}
              className={`w-14 h-14 rounded-full transition-all duration-200 ${
                screenCapture.isStreaming 
                  ? 'bg-emerald-500/20 dark:bg-emerald-500/20 light:bg-green-400/20 border-emerald-500/50 dark:border-emerald-500/50 light:border-green-400/50 text-emerald-300 dark:text-emerald-300 light:text-green-700 hover:bg-emerald-500/30 dark:hover:bg-emerald-500/30 light:hover:bg-green-400/30' 
                  : 'bg-slate-700/50 dark:bg-slate-700/50 light:bg-emerald-100/30 border-slate-600/50 dark:border-slate-600/50 light:border-emerald-200/50 text-slate-300 dark:text-slate-300 light:text-emerald-800 hover:bg-slate-600/50 dark:hover:bg-slate-600/50 light:hover:bg-emerald-100/50 hover:text-slate-200 dark:hover:text-slate-200 light:hover:text-emerald-700'
              } backdrop-blur-sm border-2`}
            >
              <Square className="h-6 w-6" />
            </Button>
          </div>
        ), [connected, audioMuted, webcam.isStreaming, screenCapture.isStreaming, toggleMute, handleVideoToggle])}

        {/* Quick Actions */}
        <AnimatePresence>
          {isVideoActive && (
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 20 }}
              transition={{ duration: 0.2 }}
              className="flex items-center space-x-4"
            >
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  if (webcam.isStreaming) {
                    webcam.stop();
                  } else {
                    screenCapture.stop();
                  }
                  setActiveVideoStream(null);
                  onVideoStreamChange(null);
                }}
                className="bg-red-500/20 border-red-500/50 text-red-300 hover:bg-red-500/30 backdrop-blur-sm"
              >
                Stop {videoType === 'camera' ? 'Camera' : 'Screen Share'}
              </Button>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* Right Column - Conversation Panel (unchanged) */}
      <div className="col-span-1">
        <div className="h-full bg-slate-800/50 dark:bg-slate-800/50 light:bg-conversation-light backdrop-blur-xl border border-slate-700/50 dark:border-slate-700/50 light:border-emerald-200/30 rounded-2xl flex flex-col shadow-2xl transition-all duration-500">
          {/* Conversation Header */}
          <div className="p-4 border-b border-slate-700/50 dark:border-slate-700/50 light:border-emerald-200/30">
            <div className="flex items-center justify-between">
              <h3 className="text-slate-200 dark:text-slate-200 light:text-emerald-800 font-semibold">Conversation</h3>
              <Button
                variant="ghost"
                size="icon"
                onClick={clearMessages}
                className="text-slate-400 dark:text-slate-400 light:text-emerald-600 hover:text-slate-200 dark:hover:text-slate-200 light:hover:text-emerald-800 hover:bg-slate-700/50 dark:hover:bg-slate-700/50 light:hover:bg-emerald-100/50 transition-all duration-200"
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          </div>

          {/* Messages */}
          <ScrollArea className="flex-1 p-4" ref={scrollRef}>
            <div className="space-y-4">
              {messages.length === 0 ? (
                <div className="text-center text-slate-400 dark:text-slate-400 light:text-emerald-600 py-8">
                  <p>No messages yet</p>
                  <p className="text-sm">Start a conversation with XENO</p>
                </div>
              ) : (
                messages.map((message) => (
                  <motion.div
                    key={message.id}
                    initial={{ opacity: 0, y: 20 }}
                    animate={{ opacity: 1, y: 0 }}
                    className={`flex ${message.type === 'user' ? 'justify-end' : 'justify-start'}`}
                  >
                    <div
                      className={`max-w-[80%] p-3 rounded-lg ${
                        message.type === 'user'
                          ? 'bg-blue-600 dark:bg-blue-600 light:bg-emerald-600 text-white shadow-lg'
                          : 'bg-slate-700/50 dark:bg-slate-700/50 light:bg-emerald-100/50 text-slate-200 dark:text-slate-200 light:text-emerald-800 border border-slate-600/50 dark:border-slate-600/50 light:border-emerald-200/50'
                      }`}
                    >
                      <p className="text-sm">{message.content}</p>
                      <p className="text-xs opacity-70 mt-1">
                        {message.timestamp.toLocaleTimeString()}
                      </p>
                    </div>
                  </motion.div>
                ))
              )}
            </div>
          </ScrollArea>

          {/* Input */}
          <div className="p-4 border-t border-slate-700/50 dark:border-slate-700/50 light:border-emerald-200/30">
            <div className="flex space-x-2">
              <Input
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                onKeyPress={handleKeyPress}
                placeholder="Type a message..."
                disabled={!connected}
                className="bg-slate-700/50 dark:bg-slate-700/50 light:bg-emerald-50/50 border-slate-600/50 dark:border-slate-600/50 light:border-emerald-200/50 text-slate-200 dark:text-slate-200 light:text-emerald-800 placeholder:text-slate-400 dark:placeholder:text-slate-400 light:placeholder:text-emerald-500 focus:border-blue-500/50 dark:focus:border-blue-500/50 light:focus:border-emerald-400/50 focus:ring-blue-500/20 dark:focus:ring-blue-500/20 light:focus:ring-emerald-500/20"
              />
              <Button
                onClick={handleSend}
                disabled={!connected || !inputText.trim()}
                size="icon"
                className="bg-blue-600 dark:bg-blue-600 light:bg-emerald-600 hover:bg-blue-700 dark:hover:bg-blue-700 light:hover:bg-emerald-700 disabled:bg-slate-600 disabled:text-slate-400 transition-all duration-200"
              >
                <Send className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
