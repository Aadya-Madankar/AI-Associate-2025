import { useState, useEffect } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Mic } from "lucide-react";

interface PermissionStatus {
  microphone: boolean;
}

export function PermissionHandler() {
  const [permissions, setPermissions] = useState<PermissionStatus>({
    microphone: false
  });
  const [showPermissions, setShowPermissions] = useState(false);

  const checkPermissions = async () => {
    try {
      const micPermission = await navigator.permissions.query({ name: 'microphone' as PermissionName });
      setPermissions({ microphone: micPermission.state === 'granted' });
      if (micPermission.state !== 'granted') {
        setShowPermissions(true);
      }
    } catch (error) {
      console.log("Permission check not supported");
    }
  };

  const requestPermissions = async () => {
    try {
      await navigator.mediaDevices.getUserMedia({ audio: true });
      setPermissions({ microphone: true });
      setShowPermissions(false);
    } catch (error) {
      console.error("Permission denied:", error);
    }
  };

  useEffect(() => {
    checkPermissions();
  }, []);

  return (
    <AnimatePresence>
      {showPermissions && (
        <motion.div
          className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
        >
          <motion.div
            className="bg-zinc-900/90 border border-white/10 rounded-3xl p-8 max-w-sm w-full shadow-2xl"
            initial={{ scale: 0.9, opacity: 0, y: 20 }}
            animate={{ scale: 1, opacity: 1, y: 0 }}
            exit={{ scale: 0.9, opacity: 0, y: 20 }}
            transition={{ type: "spring", damping: 25, stiffness: 300 }}
          >
            {/* Icon */}
            <div className="flex justify-center mb-6">
              <div className="w-20 h-20 rounded-full bg-blue-500/20 flex items-center justify-center">
                <div className="w-14 h-14 rounded-full bg-blue-500/30 flex items-center justify-center">
                  <Mic className="w-7 h-7 text-blue-400" />
                </div>
              </div>
            </div>

            {/* Content */}
            <div className="text-center space-y-3 mb-8">
              <h3 className="text-xl font-semibold text-white">
                Microphone Access
              </h3>
              <p className="text-white/50 text-sm leading-relaxed">
                XENO needs microphone access to hear you. Camera access will be requested separately when needed.
              </p>
            </div>

            {/* Status */}
            <div className="flex items-center justify-center space-x-2 mb-6">
              <div className={`w-2 h-2 rounded-full ${permissions.microphone ? 'bg-green-500' : 'bg-yellow-500'}`} />
              <span className="text-white/60 text-sm">
                {permissions.microphone ? 'Granted' : 'Waiting for permission'}
              </span>
            </div>

            {/* Actions */}
            <div className="flex space-x-3">
              <button
                onClick={() => setShowPermissions(false)}
                className="flex-1 py-3 px-4 rounded-xl bg-white/5 border border-white/10 text-white/70 hover:bg-white/10 hover:text-white transition-all font-medium"
              >
                Skip
              </button>
              <button
                onClick={requestPermissions}
                className="flex-1 py-3 px-4 rounded-xl bg-blue-500 hover:bg-blue-600 text-white font-medium shadow-lg shadow-blue-500/25 transition-all"
              >
                Allow
              </button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
