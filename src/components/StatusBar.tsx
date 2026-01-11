import { motion } from "framer-motion";
import {
  Wifi,
  WifiOff,
  Mic,
  MicOff,
  Video,
  VideoOff,
  Monitor,
  Clock,
  Activity
} from "lucide-react";
import { useState, useEffect } from "react";

interface StatusBarProps {
  connected: boolean;
  audioMuted: boolean;
  isWebcamActive: boolean;
  isScreenShareActive: boolean;
  latency?: number;
}

export function StatusBar({
  connected,
  audioMuted,
  isWebcamActive,
  isScreenShareActive,
  latency
}: StatusBarProps) {
  const [sessionTime, setSessionTime] = useState(0);
  const [connectionStrength, setConnectionStrength] = useState<'excellent' | 'good' | 'poor'>('excellent');

  // Session timer
  useEffect(() => {
    let interval: NodeJS.Timeout;
    if (connected) {
      interval = setInterval(() => {
        setSessionTime(prev => prev + 1);
      }, 1000);
    } else {
      setSessionTime(0);
    }
    return () => clearInterval(interval);
  }, [connected]);

  // Connection strength based on latency
  useEffect(() => {
    if (latency) {
      if (latency < 100) setConnectionStrength('excellent');
      else if (latency < 300) setConnectionStrength('good');
      else setConnectionStrength('poor');
    }
  }, [latency]);

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  const statusColor = {
    excellent: 'text-green-400',
    good: 'text-yellow-400',
    poor: 'text-red-400'
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: -10 }}
      animate={{ opacity: 1, y: 0 }}
      className="flex items-center justify-between px-4 py-2 glass border-b border-white/5"
    >
      {/* Left: Connection Status */}
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          {connected ? (
            <>
              <div className="relative">
                <Wifi className={`w-4 h-4 ${statusColor[connectionStrength]}`} />
                <motion.div
                  className="absolute inset-0"
                  animate={{ opacity: [1, 0.5, 1] }}
                  transition={{ duration: 2, repeat: Infinity }}
                />
              </div>
              <span className="text-white/60 text-xs">Connected</span>
            </>
          ) : (
            <>
              <WifiOff className="w-4 h-4 text-white/30" />
              <span className="text-white/30 text-xs">Disconnected</span>
            </>
          )}
        </div>

        {connected && latency && (
          <div className="flex items-center gap-1.5">
            <Activity className={`w-3 h-3 ${statusColor[connectionStrength]}`} />
            <span className={`text-xs ${statusColor[connectionStrength]}`}>
              {latency}ms
            </span>
          </div>
        )}
      </div>

      {/* Center: Media Status */}
      <div className="flex items-center gap-3">
        <div className={`flex items-center gap-1.5 px-2 py-1 rounded-full ${audioMuted ? 'bg-red-500/20' : 'bg-white/5'
          }`}>
          {audioMuted ? (
            <MicOff className="w-3 h-3 text-red-400" />
          ) : (
            <Mic className="w-3 h-3 text-green-400" />
          )}
          <span className={`text-xs ${audioMuted ? 'text-red-400' : 'text-white/50'}`}>
            {audioMuted ? 'Muted' : 'Mic'}
          </span>
        </div>

        {isWebcamActive && (
          <motion.div
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            className="flex items-center gap-1.5 px-2 py-1 rounded-full bg-blue-500/20"
          >
            <Video className="w-3 h-3 text-blue-400" />
            <span className="text-xs text-blue-400">Camera</span>
          </motion.div>
        )}

        {isScreenShareActive && (
          <motion.div
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            className="flex items-center gap-1.5 px-2 py-1 rounded-full bg-green-500/20"
          >
            <Monitor className="w-3 h-3 text-green-400" />
            <span className="text-xs text-green-400">Screen</span>
          </motion.div>
        )}
      </div>

      {/* Right: Session Time */}
      <div className="flex items-center gap-2">
        {connected && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="flex items-center gap-1.5"
          >
            <Clock className="w-3 h-3 text-white/40" />
            <span className="text-white/40 text-xs font-mono">
              {formatTime(sessionTime)}
            </span>
          </motion.div>
        )}
      </div>
    </motion.div>
  );
}
