import { motion } from "framer-motion";
import { Mic } from "lucide-react";
import { useTheme } from "../contexts/ThemeContext";

export function Sidebar() {
  useTheme();



  return (
    <motion.div
      initial={{ x: -300 }}
      animate={{ x: 0 }}
      className="w-64 bg-sidebar-dark dark:bg-sidebar-dark light:bg-sidebar-light backdrop-blur-xl border-r border-slate-700/50 dark:border-slate-700/50 light:border-emerald-200/30 flex flex-col relative z-10 transition-all duration-700"
    >
      {/* Header */}
      <div className="p-6 border-b border-slate-700/50 dark:border-slate-700/50 light:border-emerald-200/30">
        <div className="flex items-center space-x-3">
          <div className="w-10 h-10 bg-gradient-to-r from-blue-500 to-cyan-500 dark:from-blue-500 dark:to-cyan-500 light:from-emerald-600 light:to-teal-400 rounded-xl flex items-center justify-center shadow-lg">
            <span className="text-white font-bold text-lg">X</span>
          </div>
          <div>
            <h1 className="text-xl font-bold text-white dark:text-white light:text-white">AI Associate</h1>
            <p className="text-slate-400 dark:text-slate-400 light:text-emerald-100 text-sm">A friendly AI </p>
          </div>
        </div>
      </div>

      {/* Main Section */}
      <nav className="flex-1 p-4">
        <div className="flex items-center space-x-3 p-4 bg-blue-500/20 dark:bg-blue-500/20 light:bg-emerald-100/30 text-blue-300 dark:text-blue-300 light:text-emerald-800 border border-blue-500/30 dark:border-blue-500/30 light:border-emerald-200/50 rounded-lg backdrop-blur-sm">
          <Mic className="h-5 w-5" />
          <span className="font-medium">Voice Assistant</span>
        </div>
      </nav>

      
      
    </motion.div>
  );
}
