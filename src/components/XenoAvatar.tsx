import { useState, useEffect, useRef } from "react";
import { motion, AnimatePresence } from "framer-motion";

interface XenoAvatarProps {
    isSpeaking: boolean;
    isListening: boolean;
    isThinking: boolean;
    audioLevel?: number;
}

type MoodState = 'idle' | 'speaking' | 'listening' | 'thinking' | 'walking' | 'curious' | 'happy' | 'focused';

export function XenoAvatar({
    isSpeaking,
    isListening,
    isThinking,
    audioLevel = 0
}: XenoAvatarProps) {
    const [position, setPosition] = useState({ x: window.innerWidth - 200, y: window.innerHeight - 250 });
    const [isBlinking, setIsBlinking] = useState(false);
    const [mood, setMood] = useState<MoodState>('idle');
    const [facingDirection, setFacingDirection] = useState<'left' | 'right'>('left');
    const [isWaving, setIsWaving] = useState(false);
    const [headTilt, setHeadTilt] = useState(0);
    const [eyeLookDirection, setEyeLookDirection] = useState({ x: 0, y: 0 });
    const animationRef = useRef<number | undefined>(undefined);
    const lastActivityRef = useRef<number>(Date.now());

    // Determine mood from external state
    useEffect(() => {
        if (isSpeaking) {
            setMood('speaking');
            lastActivityRef.current = Date.now();
        } else if (isListening) {
            setMood('listening');
            lastActivityRef.current = Date.now();
        } else if (isThinking) {
            setMood('thinking');
        } else {
            // After a delay, go to idle behaviors
            const timeout = setTimeout(() => {
                if (Date.now() - lastActivityRef.current > 2000) {
                    const randomMood = Math.random();
                    if (randomMood > 0.5) {
                        setMood('walking'); // 50% chance to walk
                    } else if (randomMood > 0.3) {
                        setMood('curious');
                    } else {
                        setMood('idle');
                    }
                }
            }, 1000);
            return () => clearTimeout(timeout);
        }
    }, [isSpeaking, isListening, isThinking]);

    // Periodic random movement when idle
    useEffect(() => {
        const walkTimer = setInterval(() => {
            if (mood === 'idle' || mood === 'curious') {
                if (Math.random() > 0.4) { // 60% chance every 8 seconds
                    setMood('walking');
                }
            }
        }, 8000);

        return () => clearInterval(walkTimer);
    }, [mood]);

    // Natural blinking
    useEffect(() => {
        const blink = () => {
            setIsBlinking(true);
            setTimeout(() => setIsBlinking(false), 120);
        };

        const blinkInterval = setInterval(() => {
            blink();
            // Sometimes double blink
            if (Math.random() > 0.8) {
                setTimeout(blink, 200);
            }
        }, 2500 + Math.random() * 2000);

        return () => clearInterval(blinkInterval);
    }, []);

    // Eye movement - follows activity or looks around
    useEffect(() => {
        const moveEyes = () => {
            if (mood === 'listening') {
                // Look at where sound is coming from (user)
                setEyeLookDirection({ x: -2, y: 0 });
            } else if (mood === 'thinking') {
                // Look up when thinking
                setEyeLookDirection({ x: 0, y: -2 });
            } else if (mood === 'speaking') {
                // Look forward when speaking
                setEyeLookDirection({ x: Math.random() * 2 - 1, y: 0 });
            } else {
                // Random looking around
                setEyeLookDirection({
                    x: Math.random() * 4 - 2,
                    y: Math.random() * 3 - 1.5
                });
            }
        };

        const eyeInterval = setInterval(moveEyes, mood === 'idle' ? 2000 : 500);
        return () => clearInterval(eyeInterval);
    }, [mood]);

    // Head movement
    useEffect(() => {
        const moveHead = () => {
            if (mood === 'listening') {
                setHeadTilt(5);
            } else if (mood === 'thinking') {
                setHeadTilt(-8);
            } else if (mood === 'curious') {
                setHeadTilt(12);
            } else {
                setHeadTilt(Math.random() * 6 - 3);
            }
        };

        const headInterval = setInterval(moveHead, 3000);
        return () => clearInterval(headInterval);
    }, [mood]);

    // Autonomous walking
    useEffect(() => {
        if (mood !== 'walking') return;

        const walk = () => {
            const targetX = Math.random() * (window.innerWidth - 180) + 50;
            const targetY = window.innerHeight - 250 + (Math.random() * 50 - 25);

            setFacingDirection(targetX > position.x ? 'right' : 'left');

            // Animate to target
            const duration = 3000;
            const startX = position.x;
            const startY = position.y;
            const startTime = Date.now();

            const animate = () => {
                const elapsed = Date.now() - startTime;
                const progress = Math.min(elapsed / duration, 1);
                const eased = 1 - Math.pow(1 - progress, 3); // Ease out cubic

                setPosition({
                    x: startX + (targetX - startX) * eased,
                    y: startY + (targetY - startY) * eased
                });

                if (progress < 1) {
                    animationRef.current = requestAnimationFrame(animate);
                } else {
                    setMood('idle');
                }
            };

            animationRef.current = requestAnimationFrame(animate);
        };

        walk();
        return () => {
            if (animationRef.current) cancelAnimationFrame(animationRef.current);
        };
    }, [mood, position.x, position.y]);

    // Occasional wave on start
    useEffect(() => {
        const waveTimeout = setTimeout(() => {
            setIsWaving(true);
            setTimeout(() => setIsWaving(false), 2000);
        }, 1500);
        return () => clearTimeout(waveTimeout);
    }, []);

    const mouthOpen = isSpeaking ? 3 + audioLevel * 8 : 0;
    const isWalking = mood === 'walking';

    return (
        <motion.div
            className="fixed z-40 select-none pointer-events-none"
            animate={{
                x: position.x,
                y: position.y,
            }}
            transition={{
                type: "spring",
                stiffness: 50,
                damping: 20
            }}
        >
            {/* Character Container */}
            <div className="relative" style={{ width: 120, height: 180 }}>

                {/* Shadow */}
                <motion.div
                    className="absolute bottom-0 left-1/2 -translate-x-1/2 bg-black/15 rounded-full blur-md"
                    animate={{
                        width: isWalking ? [50, 40, 50] : 50,
                        height: 8,
                        scaleX: isWalking ? [1, 0.8, 1] : 1,
                    }}
                    transition={{ duration: 0.25, repeat: isWalking ? Infinity : 0 }}
                />

                {/* Full Body Character */}
                <motion.div
                    className="relative"
                    animate={{
                        scaleX: facingDirection === 'left' ? 1 : -1,
                        y: isWalking ? [0, -6, 0] : mood === 'speaking' ? [0, -2, 0] : 0,
                    }}
                    transition={{
                        duration: isWalking ? 0.25 : 0.3,
                        repeat: (isWalking || mood === 'speaking') ? Infinity : 0,
                    }}
                >
                    {/* HEAD */}
                    <motion.div
                        className="relative mx-auto"
                        style={{ width: 60, height: 65 }}
                        animate={{
                            rotate: headTilt,
                            y: mood === 'speaking' ? [0, -1, 0] : 0,
                        }}
                        transition={{ duration: 0.5, ease: "easeInOut" }}
                    >
                        {/* Hair Back */}
                        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-[58px] h-[42px] bg-gradient-to-b from-gray-800 via-gray-900 to-gray-800 rounded-t-[30px]" />

                        {/* Face */}
                        <div className="absolute top-[8px] left-1/2 -translate-x-1/2 w-[52px] h-[54px] bg-gradient-to-b from-[#ffe4d0] via-[#ffd4b8] to-[#ffc9a8] rounded-[26px] shadow-sm overflow-hidden">

                            {/* Blush */}
                            <div className="absolute bottom-[16px] left-[4px] w-[10px] h-[6px] bg-pink-300/40 rounded-full blur-[2px]" />
                            <div className="absolute bottom-[16px] right-[4px] w-[10px] h-[6px] bg-pink-300/40 rounded-full blur-[2px]" />

                            {/* Left Eye */}
                            <motion.div
                                className="absolute top-[20px] left-[8px] w-[14px] h-[12px] bg-white rounded-[50%] overflow-hidden shadow-inner"
                                animate={{ scaleY: isBlinking ? 0.1 : 1 }}
                                transition={{ duration: 0.08 }}
                            >
                                <motion.div
                                    className="absolute w-[10px] h-[10px] bg-gradient-to-br from-gray-700 via-gray-900 to-black rounded-full"
                                    animate={{
                                        x: 2 + eyeLookDirection.x,
                                        y: 1 + eyeLookDirection.y
                                    }}
                                    transition={{ duration: 0.2 }}
                                >
                                    {/* Eye shine */}
                                    <div className="absolute top-[1px] left-[2px] w-[3px] h-[3px] bg-white rounded-full opacity-80" />
                                    <div className="absolute bottom-[2px] right-[1px] w-[2px] h-[2px] bg-white/50 rounded-full" />
                                </motion.div>
                            </motion.div>

                            {/* Right Eye */}
                            <motion.div
                                className="absolute top-[20px] right-[8px] w-[14px] h-[12px] bg-white rounded-[50%] overflow-hidden shadow-inner"
                                animate={{ scaleY: isBlinking ? 0.1 : 1 }}
                                transition={{ duration: 0.08 }}
                            >
                                <motion.div
                                    className="absolute w-[10px] h-[10px] bg-gradient-to-br from-gray-700 via-gray-900 to-black rounded-full"
                                    animate={{
                                        x: 2 + eyeLookDirection.x,
                                        y: 1 + eyeLookDirection.y
                                    }}
                                    transition={{ duration: 0.2 }}
                                >
                                    <div className="absolute top-[1px] left-[2px] w-[3px] h-[3px] bg-white rounded-full opacity-80" />
                                    <div className="absolute bottom-[2px] right-[1px] w-[2px] h-[2px] bg-white/50 rounded-full" />
                                </motion.div>
                            </motion.div>

                            {/* Eyebrows */}
                            <motion.div
                                className="absolute top-[14px] left-[10px] w-[10px] h-[2px] bg-gray-800 rounded-full"
                                animate={{
                                    rotate: mood === 'thinking' ? -15 : mood === 'curious' ? -10 : 0,
                                    y: mood === 'listening' ? -2 : 0
                                }}
                            />
                            <motion.div
                                className="absolute top-[14px] right-[10px] w-[10px] h-[2px] bg-gray-800 rounded-full"
                                animate={{
                                    rotate: mood === 'thinking' ? 15 : mood === 'curious' ? 10 : 0,
                                    y: mood === 'listening' ? -2 : 0
                                }}
                            />

                            {/* Nose */}
                            <div className="absolute bottom-[18px] left-1/2 -translate-x-1/2 w-[4px] h-[6px] bg-[#e8c4a8] rounded-full" />

                            {/* Mouth */}
                            <motion.div
                                className="absolute bottom-[10px] left-1/2 -translate-x-1/2 bg-[#e57373] rounded-[50%]"
                                animate={{
                                    width: isSpeaking ? 12 + mouthOpen : 8,
                                    height: isSpeaking ? 6 + mouthOpen : 3,
                                }}
                                transition={{ duration: 0.08 }}
                            >
                                {isSpeaking && mouthOpen > 4 && (
                                    <div className="absolute inset-x-1 top-1/2 h-[3px] bg-[#c62828] rounded-full" />
                                )}
                            </motion.div>
                        </div>

                        {/* Hair Front */}
                        <div className="absolute top-[2px] left-1/2 -translate-x-1/2 w-[56px] h-[22px]">
                            {/* Bangs */}
                            <div className="absolute left-[2px] top-[8px] w-[12px] h-[18px] bg-gradient-to-b from-gray-800 to-gray-900 rounded-b-full skew-x-[-8deg]" />
                            <div className="absolute left-[14px] top-[5px] w-[14px] h-[22px] bg-gradient-to-b from-gray-900 to-gray-800 rounded-b-full skew-x-[-4deg]" />
                            <div className="absolute right-[14px] top-[5px] w-[14px] h-[22px] bg-gradient-to-b from-gray-900 to-gray-800 rounded-b-full skew-x-[4deg]" />
                            <div className="absolute right-[2px] top-[8px] w-[12px] h-[18px] bg-gradient-to-b from-gray-800 to-gray-900 rounded-b-full skew-x-[8deg]" />
                        </div>
                    </motion.div>

                    {/* NECK */}
                    <div className="mx-auto w-[16px] h-[8px] bg-gradient-to-b from-[#ffd4b8] to-[#ffc9a8]" />

                    {/* BODY */}
                    <div className="mx-auto relative" style={{ width: 55, height: 50 }}>
                        {/* Shirt/Torso */}
                        <div className="absolute inset-0 bg-gradient-to-b from-indigo-500 via-indigo-600 to-indigo-700 rounded-t-[10px] rounded-b-[8px] shadow-md">
                            {/* Collar */}
                            <div className="absolute top-0 left-1/2 -translate-x-1/2 w-[18px] h-[10px] bg-white rounded-b-lg" />
                            {/* Buttons */}
                            <div className="absolute top-[14px] left-1/2 -translate-x-1/2 flex flex-col gap-[6px]">
                                <div className="w-[4px] h-[4px] bg-indigo-300 rounded-full" />
                                <div className="w-[4px] h-[4px] bg-indigo-300 rounded-full" />
                            </div>
                        </div>

                        {/* Left Arm */}
                        <motion.div
                            className="absolute -left-[10px] top-[6px] origin-top"
                            animate={{
                                rotate: isWalking ? [20, -20, 20] : isWaving ? [0, -30, -60, -30, 0] : mood === 'speaking' ? [-8, 8, -8] : [0, 5, 0],
                            }}
                            transition={{
                                duration: isWalking ? 0.25 : isWaving ? 0.4 : 0.8,
                                repeat: isWaving ? 5 : Infinity,
                                ease: "easeInOut"
                            }}
                        >
                            <div className="w-[12px] h-[35px] bg-gradient-to-b from-indigo-500 to-indigo-600 rounded-full shadow-sm" />
                            <div className="absolute -bottom-[2px] left-1/2 -translate-x-1/2 w-[11px] h-[11px] bg-gradient-to-b from-[#ffe4d0] to-[#ffc9a8] rounded-full" />
                        </motion.div>

                        {/* Right Arm */}
                        <motion.div
                            className="absolute -right-[10px] top-[6px] origin-top"
                            animate={{
                                rotate: isWalking ? [-20, 20, -20] : mood === 'speaking' ? [8, -8, 8] : [0, -5, 0],
                            }}
                            transition={{
                                duration: isWalking ? 0.25 : 0.8,
                                repeat: Infinity,
                                ease: "easeInOut"
                            }}
                        >
                            <div className="w-[12px] h-[35px] bg-gradient-to-b from-indigo-500 to-indigo-600 rounded-full shadow-sm" />
                            <div className="absolute -bottom-[2px] left-1/2 -translate-x-1/2 w-[11px] h-[11px] bg-gradient-to-b from-[#ffe4d0] to-[#ffc9a8] rounded-full" />
                        </motion.div>
                    </div>

                    {/* LEGS */}
                    <div className="flex justify-center gap-[4px]">
                        {/* Left Leg */}
                        <motion.div
                            className="origin-top"
                            animate={{
                                rotate: isWalking ? [-25, 25, -25] : 0,
                            }}
                            transition={{ duration: 0.25, repeat: isWalking ? Infinity : 0 }}
                        >
                            <div className="w-[14px] h-[40px] bg-gradient-to-b from-gray-700 via-gray-800 to-gray-900 rounded-b-[6px]" />
                            <div className="w-[18px] h-[8px] bg-gray-900 rounded-[4px] -ml-[2px]" />
                        </motion.div>

                        {/* Right Leg */}
                        <motion.div
                            className="origin-top"
                            animate={{
                                rotate: isWalking ? [25, -25, 25] : 0,
                            }}
                            transition={{ duration: 0.25, repeat: isWalking ? Infinity : 0 }}
                        >
                            <div className="w-[14px] h-[40px] bg-gradient-to-b from-gray-700 via-gray-800 to-gray-900 rounded-b-[6px]" />
                            <div className="w-[18px] h-[8px] bg-gray-900 rounded-[4px] -ml-[2px]" />
                        </motion.div>
                    </div>
                </motion.div>

                {/* Thought/Speech Bubble */}
                <AnimatePresence>
                    {(mood === 'speaking' || mood === 'thinking' || mood === 'listening') && (
                        <motion.div
                            initial={{ opacity: 0, scale: 0.5, y: 10 }}
                            animate={{ opacity: 1, scale: 1, y: 0 }}
                            exit={{ opacity: 0, scale: 0.5, y: 10 }}
                            className="absolute -top-12 left-1/2 -translate-x-1/2 px-3 py-1.5 rounded-2xl text-sm font-medium shadow-lg"
                            style={{
                                background: mood === 'speaking' ? 'linear-gradient(135deg, #6366f1, #8b5cf6)'
                                    : mood === 'thinking' ? 'linear-gradient(135deg, #f59e0b, #d97706)'
                                        : 'linear-gradient(135deg, #10b981, #059669)',
                                color: 'white',
                            }}
                        >
                            {mood === 'speaking' && '💬'}
                            {mood === 'thinking' && '💭'}
                            {mood === 'listening' && '👂'}
                            {/* Speech bubble tail */}
                            <div
                                className="absolute -bottom-2 left-1/2 -translate-x-1/2 w-0 h-0 border-l-[8px] border-r-[8px] border-t-[8px] border-l-transparent border-r-transparent"
                                style={{
                                    borderTopColor: mood === 'speaking' ? '#8b5cf6'
                                        : mood === 'thinking' ? '#d97706'
                                            : '#059669',
                                }}
                            />
                        </motion.div>
                    )}
                </AnimatePresence>

                {/* Name Badge */}
                <motion.div
                    className="absolute -bottom-8 left-1/2 -translate-x-1/2 px-3 py-1 bg-gradient-to-r from-indigo-600 to-purple-600 rounded-full shadow-lg"
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                >
                    <span className="text-white text-xs font-bold tracking-wide">XENO</span>
                </motion.div>
            </div>
        </motion.div>
    );
}
