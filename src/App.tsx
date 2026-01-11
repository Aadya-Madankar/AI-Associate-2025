import { useRef, useState, useCallback } from "react";
import { LiveAPIProvider } from "./contexts/LiveAPIContext";
import { ThemeProvider } from "./contexts/ThemeContext";
import { VoiceInterface } from "./components/VoiceInterface";
import { PermissionHandler } from "./components/PermissionHandler";
import { Sidebar } from "./components/Sidebar";
import { StatusBar } from "./components/StatusBar";
import { ChatPanel, ChatMessage } from "./components/ChatPanel";
import { useLiveAPIContext } from "./contexts/LiveAPIContext";
import { Menu, MessageSquare, X } from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";

const API_KEY = import.meta.env.VITE_GEMINI_API_KEY as string;

if (typeof API_KEY !== "string") {
  throw new Error("Please set VITE_GEMINI_API_KEY in your .env file");
}

const host = "generativelanguage.googleapis.com";
const uri = `wss://${host}/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent`;

function AppContent() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [videoStream, setVideoStream] = useState<MediaStream | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [chatPanelOpen, setChatPanelOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  // Demo conversations - in production, this would be fetched from storage
  const conversations: Array<{ id: string; title: string; time: string }> = [];
  const [settings, setSettings] = useState({
    videoQuality: 75,
    audioEnabled: true
  });

  // Get connection state from context
  const { connected } = useLiveAPIContext();

  // Track media states
  const [audioMuted, setAudioMuted] = useState(false);
  const [isWebcamActive, setIsWebcamActive] = useState(false);
  const [isScreenShareActive, setIsScreenShareActive] = useState(false);
  const [isAssistantSpeaking, setIsAssistantSpeaking] = useState(false);

  // Callbacks for VoiceInterface to update states
  const handleAudioMutedChange = useCallback((muted: boolean) => {
    setAudioMuted(muted);
  }, []);

  const handleWebcamChange = useCallback((active: boolean) => {
    setIsWebcamActive(active);
  }, []);

  const handleScreenShareChange = useCallback((active: boolean) => {
    setIsScreenShareActive(active);
  }, []);

  const handleAssistantSpeakingChange = useCallback((speaking: boolean) => {
    setIsAssistantSpeaking(speaking);
  }, []);

  const handleNewMessage = useCallback((message: ChatMessage) => {
    setMessages(prev => [...prev, message]);
  }, []);

  const handleSelectConversation = useCallback((id: string) => {
    console.log('Selected conversation:', id);
    setSidebarOpen(false);
  }, []);

  return (
    <div className="min-h-screen bg-mesh-dark dark flex">
      <PermissionHandler />

      {/* Sidebar */}
      <Sidebar
        isOpen={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        conversations={conversations}
        onSelectConversation={handleSelectConversation}
        settings={settings}
        onSettingsChange={setSettings}
      />

      {/* Main Content */}
      <div className={`flex-1 flex flex-col transition-all duration-300 ${sidebarOpen ? 'md:ml-72' : ''}`}>
        {/* Top Bar */}
        <header className="flex items-center justify-between px-4 py-3 border-b border-white/5 glass">
          <div className="flex items-center gap-3">
            {/* Menu Button */}
            <motion.button
              onClick={() => setSidebarOpen(!sidebarOpen)}
              className="p-2 rounded-xl bg-white/5 hover:bg-white/10 text-white/60 hover:text-white transition-all"
              whileHover={{ scale: 1.05 }}
              whileTap={{ scale: 0.95 }}
            >
              {sidebarOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
            </motion.button>

            {/* Logo */}
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-lg gradient-primary flex items-center justify-center">
                <span className="text-white font-bold text-sm">X</span>
              </div>
              <span className="text-white/90 font-semibold hidden sm:inline">XENO Live</span>
            </div>
          </div>

          {/* Connection Badge */}
          <AnimatePresence>
            {connected && (
              <motion.div
                initial={{ opacity: 0, scale: 0.8 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.8 }}
                className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-green-500/20 border border-green-500/30"
              >
                <div className="w-2 h-2 rounded-full bg-green-500 animate-pulse" />
                <span className="text-green-400 text-xs font-medium">Live</span>
              </motion.div>
            )}
          </AnimatePresence>

          {/* Chat Toggle */}
          <motion.button
            onClick={() => setChatPanelOpen(!chatPanelOpen)}
            className={`p-2 rounded-xl transition-all ${chatPanelOpen
              ? 'bg-indigo-500/20 text-indigo-400'
              : 'bg-white/5 hover:bg-white/10 text-white/60 hover:text-white'
              }`}
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
          >
            <MessageSquare className="w-5 h-5" />
          </motion.button>
        </header>

        {/* Status Bar */}
        <StatusBar
          connected={connected}
          audioMuted={audioMuted}
          isWebcamActive={isWebcamActive}
          isScreenShareActive={isScreenShareActive}
          latency={connected ? 85 : undefined}
        />

        {/* Main Area */}
        <div className="flex-1 flex">
          {/* Voice Interface */}
          <main className="flex-1 flex flex-col">
            <VoiceInterface
              videoRef={videoRef}
              onVideoStreamChange={setVideoStream}
              onAudioMutedChange={handleAudioMutedChange}
              onWebcamChange={handleWebcamChange}
              onScreenShareChange={handleScreenShareChange}
              onAssistantSpeakingChange={handleAssistantSpeakingChange}
              onNewMessage={handleNewMessage}
              videoQuality={settings.videoQuality}
            />
          </main>

          {/* Chat Panel */}
          <AnimatePresence>
            {chatPanelOpen && (
              <motion.div
                initial={{ opacity: 0, width: 0 }}
                animate={{ opacity: 1, width: 384 }}
                exit={{ opacity: 0, width: 0 }}
                className="border-l border-white/5 overflow-hidden"
              >
                <ChatPanel
                  messages={messages}
                  isVisible={chatPanelOpen}
                  isAssistantSpeaking={isAssistantSpeaking}
                />
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>

      {/* Hidden video element for streaming */}
      {videoStream && (
        <video
          ref={videoRef}
          autoPlay
          muted
          playsInline
          className="absolute opacity-0 pointer-events-none w-1 h-1"
        />
      )}
    </div>
  );
}

function App() {
  return (
    <ThemeProvider>
      <LiveAPIProvider url={uri} apiKey={API_KEY}>
        <AppContent />
      </LiveAPIProvider>
    </ThemeProvider>
  );
}

export default App;
