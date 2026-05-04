import { useRef, useEffect } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { User, Bot, Sparkles, Copy, Check } from "lucide-react";
import { useState } from "react";

export interface ChatMessage {
    id: string;
    role: 'user' | 'assistant';
    content: string;
    timestamp: Date;
    isThinking?: boolean;
}

interface ChatPanelProps {
    messages: ChatMessage[];
    isVisible: boolean;
    isAssistantSpeaking: boolean;
}

export function ChatPanel({ messages, isVisible, isAssistantSpeaking }: ChatPanelProps) {
    const scrollRef = useRef<HTMLDivElement>(null);
    const [copiedId, setCopiedId] = useState<string | null>(null);

    // Auto-scroll to bottom on new messages
    useEffect(() => {
        if (scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [messages]);

    const copyToClipboard = async (text: string, id: string) => {
        await navigator.clipboard.writeText(text);
        setCopiedId(id);
        setTimeout(() => setCopiedId(null), 2000);
    };

    const formatTime = (date: Date) => {
        return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    };

    if (!isVisible) return null;

    return (
        <motion.div
            initial={{ opacity: 0, x: 20 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: 20 }}
            className="w-96 h-full flex flex-col glass-card rounded-2xl overflow-hidden"
        >
            {/* Header */}
            <div className="flex items-center justify-between px-4 py-3 border-b border-white/5">
                <div className="flex items-center gap-2">
                    <div className="w-2 h-2 rounded-full bg-green-500 animate-pulse" />
                    <span className="text-white/80 font-medium text-sm">Live Transcript</span>
                </div>
                <div className="flex items-center gap-1">
                    {isAssistantSpeaking && (
                        <motion.div
                            initial={{ opacity: 0, scale: 0.8 }}
                            animate={{ opacity: 1, scale: 1 }}
                            className="flex items-center gap-1 px-2 py-1 rounded-full bg-indigo-500/20 border border-indigo-500/30"
                        >
                            <Sparkles className="w-3 h-3 text-indigo-400" />
                            <span className="text-indigo-400 text-xs">Speaking</span>
                        </motion.div>
                    )}
                </div>
            </div>

            {/* Messages */}
            <div
                ref={scrollRef}
                className="flex-1 overflow-y-auto p-4 space-y-4"
            >
                <AnimatePresence mode="popLayout">
                    {messages.length === 0 ? (
                        <motion.div
                            initial={{ opacity: 0 }}
                            animate={{ opacity: 1 }}
                            className="h-full flex flex-col items-center justify-center text-center py-12"
                        >
                            <div className="w-16 h-16 rounded-2xl bg-white/5 flex items-center justify-center mb-4">
                                <Bot className="w-8 h-8 text-white/20" />
                            </div>
                            <p className="text-white/40 text-sm">No messages yet</p>
                            <p className="text-white/20 text-xs mt-1">Start speaking to see the transcript</p>
                        </motion.div>
                    ) : (
                        messages.map((message, index) => (
                            <motion.div
                                key={message.id}
                                initial={{ opacity: 0, y: 10 }}
                                animate={{ opacity: 1, y: 0 }}
                                exit={{ opacity: 0, y: -10 }}
                                transition={{ delay: index * 0.02 }}
                                className={`flex gap-3 ${message.role === 'user' ? 'flex-row-reverse' : ''}`}
                            >
                                {/* Avatar */}
                                <div className={`flex-shrink-0 w-8 h-8 rounded-xl flex items-center justify-center ${message.role === 'user'
                                        ? 'bg-indigo-500/20 border border-indigo-500/30'
                                        : 'bg-white/5 border border-white/10'
                                    }`}>
                                    {message.role === 'user' ? (
                                        <User className="w-4 h-4 text-indigo-400" />
                                    ) : (
                                        <Bot className="w-4 h-4 text-white/60" />
                                    )}
                                </div>

                                {/* Message */}
                                <div className={`flex-1 max-w-[80%] ${message.role === 'user' ? 'text-right' : ''}`}>
                                    <div
                                        className={`inline-block p-3 rounded-2xl ${message.role === 'user'
                                                ? 'bg-indigo-500/20 border border-indigo-500/30 rounded-tr-md'
                                                : 'bg-white/5 border border-white/10 rounded-tl-md'
                                            }`}
                                    >
                                        {message.isThinking ? (
                                            <div className="flex items-center gap-2">
                                                <div className="flex gap-1">
                                                    {[0, 1, 2].map((i) => (
                                                        <motion.div
                                                            key={i}
                                                            className="w-2 h-2 rounded-full bg-white/40"
                                                            animate={{ y: [0, -4, 0] }}
                                                            transition={{
                                                                duration: 0.6,
                                                                repeat: Infinity,
                                                                delay: i * 0.1
                                                            }}
                                                        />
                                                    ))}
                                                </div>
                                                <span className="text-white/40 text-sm">Thinking...</span>
                                            </div>
                                        ) : (
                                            <p className="text-white/80 text-sm leading-relaxed">{message.content}</p>
                                        )}
                                    </div>

                                    {/* Meta */}
                                    <div className={`flex items-center gap-2 mt-1 ${message.role === 'user' ? 'justify-end' : ''
                                        }`}>
                                        <span className="text-white/30 text-xs">{formatTime(message.timestamp)}</span>
                                        {message.role === 'assistant' && !message.isThinking && (
                                            <button
                                                onClick={() => copyToClipboard(message.content, message.id)}
                                                className="p-1 rounded hover:bg-white/10 text-white/30 hover:text-white/60 transition-all"
                                            >
                                                {copiedId === message.id ? (
                                                    <Check className="w-3 h-3 text-green-400" />
                                                ) : (
                                                    <Copy className="w-3 h-3" />
                                                )}
                                            </button>
                                        )}
                                    </div>
                                </div>
                            </motion.div>
                        ))
                    )}
                </AnimatePresence>

                {/* Typing indicator */}
                {isAssistantSpeaking && messages[messages.length - 1]?.role !== 'assistant' && (
                    <motion.div
                        initial={{ opacity: 0, y: 10 }}
                        animate={{ opacity: 1, y: 0 }}
                        className="flex gap-3"
                    >
                        <div className="w-8 h-8 rounded-xl bg-white/5 border border-white/10 flex items-center justify-center">
                            <Bot className="w-4 h-4 text-white/60" />
                        </div>
                        <div className="p-3 rounded-2xl bg-white/5 border border-white/10 rounded-tl-md">
                            <div className="flex gap-1">
                                {[0, 1, 2].map((i) => (
                                    <motion.div
                                        key={i}
                                        className="w-2 h-2 rounded-full bg-white/40"
                                        animate={{ y: [0, -4, 0] }}
                                        transition={{
                                            duration: 0.6,
                                            repeat: Infinity,
                                            delay: i * 0.1
                                        }}
                                    />
                                ))}
                            </div>
                        </div>
                    </motion.div>
                )}
            </div>

            {/* Footer hint */}
            <div className="px-4 py-2 border-t border-white/5 bg-white/[0.02]">
                <p className="text-white/30 text-xs text-center">
                    Voice messages are automatically transcribed
                </p>
            </div>
        </motion.div>
    );
}
