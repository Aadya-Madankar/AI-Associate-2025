import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import {
  Settings,
  Volume2,
  X,
  HelpCircle,
  Zap
} from "lucide-react";

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
  conversations: Array<{ id: string; title: string; time: string }>;
  onSelectConversation: (id: string) => void;
  settings: {
    videoQuality: number;
    audioEnabled: boolean;
  };
  onSettingsChange: (settings: any) => void;
}

export function Sidebar({
  isOpen,
  onClose,
  settings,
  onSettingsChange
}: SidebarProps) {
  const [activeTab, setActiveTab] = useState<'howto' | 'settings'>('settings');

  const howToSteps = [
    { icon: '🎙️', title: 'Voice Chat', desc: 'Click the phone button to start talking' },
    { icon: '📸', title: 'Camera', desc: 'Enable camera to show things to XENO' },
    { icon: '🖥️', title: 'Screen Share', desc: 'Share screen for visual assistance' },
    { icon: '🔍', title: 'Vision Tool', desc: 'XENO can see and analyze visuals' },
    { icon: '🌐', title: 'Search', desc: 'Real-time web search for information' },
  ];

  return (
    <>
      {/* Overlay */}
      <AnimatePresence>
        {isOpen && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 bg-black/50 backdrop-blur-sm z-40 md:hidden"
            onClick={onClose}
          />
        )}
      </AnimatePresence>

      {/* Sidebar */}
      <motion.aside
        initial={{ x: -288 }}
        animate={{ x: isOpen ? 0 : -288 }}
        transition={{ type: "spring", damping: 25, stiffness: 200 }}
        className="fixed left-0 top-0 h-full w-72 z-50 flex flex-col glass-dark border-r border-white/5"
      >
        {/* Header - XENO Branding */}
        <div className="flex items-center justify-between p-4 border-b border-white/5">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-violet-500 via-purple-500 to-indigo-600 flex items-center justify-center shadow-lg shadow-purple-500/30">
              <Zap className="w-5 h-5 text-white" />
            </div>
            <div>
              <h1 className="text-white font-bold text-lg tracking-tight">XENO Live</h1>
              <p className="text-white/40 text-xs">Conversational AI</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="md:hidden p-2 rounded-lg hover:bg-white/5 text-white/60 hover:text-white transition-all"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Navigation Tabs - Only How to Use & Settings */}
        <div className="flex p-2 gap-1 border-b border-white/5">
          {[
            { id: 'howto', icon: HelpCircle, label: 'How to Use' },
            { id: 'settings', icon: Settings, label: 'Settings' },
          ].map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as any)}
              className={`flex-1 flex items-center justify-center gap-2 py-2 px-3 rounded-lg text-sm transition-all ${activeTab === tab.id
                ? 'bg-white/10 text-white'
                : 'text-white/50 hover:text-white hover:bg-white/5'
                }`}
            >
              <tab.icon className="w-4 h-4" />
              <span>{tab.label}</span>
            </button>
          ))}
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-4">
          <AnimatePresence mode="wait">
            {activeTab === 'howto' && (
              <motion.div
                key="howto"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                className="space-y-3"
              >
                <span className="label">Getting Started</span>
                <div className="mt-3 space-y-2">
                  {howToSteps.map((step, i) => (
                    <motion.div
                      key={step.title}
                      initial={{ opacity: 0, x: -10 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: i * 0.05 }}
                      className="p-3 rounded-xl bg-white/5 border border-white/5"
                    >
                      <div className="flex items-start gap-3">
                        <span className="text-xl">{step.icon}</span>
                        <div>
                          <p className="text-white/80 text-sm font-medium">{step.title}</p>
                          <p className="text-white/40 text-xs mt-0.5">{step.desc}</p>
                        </div>
                      </div>
                    </motion.div>
                  ))}
                </div>
              </motion.div>
            )}

            {activeTab === 'settings' && (
              <motion.div
                key="settings"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                className="space-y-6"
              >
                {/* Audio - Noise Suppression Only */}
                <div>
                  <span className="label">Audio Settings</span>
                  <div className="mt-3 p-4 rounded-xl bg-white/5 border border-white/5">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <div className="w-8 h-8 rounded-lg bg-indigo-500/20 flex items-center justify-center">
                          <Volume2 className="w-4 h-4 text-indigo-400" />
                        </div>
                        <div>
                          <span className="text-white/80 text-sm">Noise Suppression</span>
                          <p className="text-white/40 text-xs">Reduce background noise</p>
                        </div>
                      </div>
                      <div className={`w-12 h-7 rounded-full p-1 cursor-pointer transition-all ${settings.audioEnabled ? 'bg-indigo-500' : 'bg-white/20'
                        }`}
                        onClick={() => onSettingsChange({ ...settings, audioEnabled: !settings.audioEnabled })}
                      >
                        <div className={`w-5 h-5 bg-white rounded-full transition-transform shadow-md ${settings.audioEnabled ? 'translate-x-5' : 'translate-x-0'
                          }`} />
                      </div>
                    </div>
                  </div>
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* Footer - XENO AI Branding */}
        <div className="p-4 border-t border-white/5">
          <div className="text-center">
            <div className="flex items-center justify-center gap-2 mb-2">
              <div className="w-6 h-6 rounded-lg bg-gradient-to-br from-violet-500 to-indigo-600 flex items-center justify-center">
                <Zap className="w-3.5 h-3.5 text-white" />
              </div>
              <span className="text-white/70 font-bold tracking-wide">XENO AI</span>
            </div>
            <p className="text-white/30 text-xs">Built by Aadya</p>
          </div>
        </div>
      </motion.aside>
    </>
  );
}
