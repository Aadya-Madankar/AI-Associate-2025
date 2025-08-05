import { useRef, useState } from "react";
import { LiveAPIProvider } from "./contexts/LiveAPIContext";
import { ThemeProvider } from "./contexts/ThemeContext";
import { Sidebar } from "./components/Sidebar";
import { StatusBar } from "./components/StatusBar";
import { VoiceInterface } from "./components/VoiceInterface";
import { PermissionHandler } from "./components/PermissionHandler";

const API_KEY = import.meta.env.VITE_GEMINI_API_KEY as string;

if (typeof API_KEY !== "string") {
  throw new Error("Please set VITE_GEMINI_API_KEY in your .env file");
}

const host = "generativelanguage.googleapis.com";
const uri = `wss://${host}/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent`;

function AppContent() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [videoStream, setVideoStream] = useState<MediaStream | null>(null);

  return (
    <div className="min-h-screen transition-all duration-700 bg-gradient-dark dark:bg-gradient-dark light:bg-gradient-light flex">
      
      <PermissionHandler />
      <Sidebar />
      
      <div className="flex-1 flex flex-col">
        <StatusBar />
        <main className="flex-1 p-6">
          <VoiceInterface 
            videoRef={videoRef}
            onVideoStreamChange={setVideoStream}
          />
        </main>
      </div>

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
