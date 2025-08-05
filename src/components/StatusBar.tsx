import { motion } from "framer-motion";
import { Volume2, Wifi, Battery } from "lucide-react";
import { useLiveAPIContext } from "../contexts/LiveAPIContext";

export function StatusBar() {
  const { connected, volume } = useLiveAPIContext();

  return (
    <div className="h-16 bg-status-dark dark:bg-status-dark light:bg-status-light backdrop-blur-xl border-b border-slate-700/50 dark:border-slate-700/50 light:border-emerald-200/30 flex items-center justify-between px-6 transition-all duration-700">
      {/* Left - Connection Status */}
      <div className="flex items-center space-x-4">
        <div className="flex items-center space-x-2">
          <div className={`w-2 h-2 rounded-full ${connected ? 'bg-emerald-400 animate-pulse' : 'bg-red-400'}`} />
          <span className="text-slate-200 dark:text-slate-200 light:text-white text-sm font-medium">
            {connected ? 'Connected' : 'Disconnected'}
          </span>
        </div>
        
        <div className="flex items-center space-x-2">
          <Volume2 className="w-4 h-4 text-slate-400 dark:text-slate-400 light:text-emerald-100" />
          <div className="w-16 h-1 bg-slate-600 dark:bg-slate-600 light:bg-emerald-200/50 rounded-full overflow-hidden">
            <motion.div
              className="h-full bg-gradient-to-r from-emerald-400 to-cyan-400 dark:from-emerald-400 dark:to-cyan-400 light:from-teal-300 light:to-emerald-300 rounded-full"
              initial={{ width: 0 }}
              animate={{ width: `${Math.min(volume * 100, 100)}%` }}
              transition={{ duration: 0.1 }}
            />
          </div>
        </div>
      </div>

      {/* Right - System Status */}
      <div className="flex items-center space-x-4">
        <div className="flex items-center space-x-2 text-slate-400 dark:text-slate-400 light:text-emerald-100 text-sm">
          <Wifi className="w-4 h-4" />
          <Battery className="w-4 h-4" />
          <span>{new Date().toLocaleTimeString()}</span>
        </div>
      </div>
    </div>
  );
}
