import { useMemo } from "react";
import { motion } from "framer-motion";

interface PolkaDotSphereProps {
  isActive: boolean;
  volume: number;
  isUserSpeaking?: boolean;
  isAssistantSpeaking?: boolean;
  size?: number;
}

export function PolkaDotSphere({
  isActive,
  volume,
  isUserSpeaking = false,
  isAssistantSpeaking = false,
  size = 280
}: PolkaDotSphereProps) {
  // Simplified - use fewer dots for performance
  const dots = useMemo(() => {
    const sphereDots = [];
    const numDots = 40; // Reduced from 80 for performance

    for (let i = 0; i < numDots; i++) {
      const phi = Math.acos(1 - 2 * i / numDots);
      const theta = Math.PI * (1 + Math.sqrt(5)) * i;

      const radius = size / 4;
      const baseX = radius * Math.sin(phi) * Math.cos(theta);
      const baseY = radius * Math.sin(phi) * Math.sin(theta);
      const baseZ = radius * Math.cos(phi);

      sphereDots.push({
        x: baseX + size / 2,
        y: baseY + size / 2,
        z: baseZ,
        baseX: baseX + size / 2,
        baseY: baseY + size / 2,
        baseZ: baseZ,
      });
    }
    return sphereDots;
  }, [size]);

  const isSpeaking = isUserSpeaking || isAssistantSpeaking;
  const intensity = Math.min(volume * 3, 1);

  // Color based on state
  const color = useMemo(() => {
    if (isAssistantSpeaking) return "#3b82f6"; // Blue for AI
    if (isUserSpeaking) return "#22c55e"; // Green for user
    return "#6366f1"; // Indigo for idle
  }, [isUserSpeaking, isAssistantSpeaking]);

  if (!isActive) return null;

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.8 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 0.8 }}
      transition={{ duration: 0.4 }}
      className="relative flex items-center justify-center"
      style={{ width: size, height: size }}
    >
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        className="absolute inset-0"
      >
        {/* Ambient glow */}
        <defs>
          <radialGradient id="ambientGlow" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor={color} stopOpacity={isSpeaking ? 0.15 : 0.05} />
            <stop offset="100%" stopColor={color} stopOpacity={0} />
          </radialGradient>
          <filter id="softBlur">
            <feGaussianBlur stdDeviation="2" />
          </filter>
        </defs>

        {/* Background glow */}
        <circle
          cx={size / 2}
          cy={size / 2}
          r={size / 2.5}
          fill="url(#ambientGlow)"
        />

        {/* Dots */}
        {dots.map((dot, index) => {
          const scale = (dot.baseZ + size / 4) / (size / 2);
          const opacity = Math.max(0.3, scale);
          const dotSize = Math.max(2, 6 * scale);
          const dynamicSize = isSpeaking
            ? dotSize * (1 + intensity * 0.5)
            : dotSize;

          return (
            <circle
              key={index}
              cx={dot.x}
              cy={dot.y}
              r={dynamicSize}
              fill={color}
              opacity={opacity * (isSpeaking ? 0.8 : 0.5)}
            />
          );
        })}

        {/* Center orb */}
        <motion.circle
          cx={size / 2}
          cy={size / 2}
          r={isSpeaking ? 8 + intensity * 4 : 6}
          fill={color}
          opacity={0.9}
          animate={{
            r: isSpeaking ? [8, 12 + intensity * 6, 8] : [6, 8, 6],
            opacity: [0.9, 0.6, 0.9],
          }}
          transition={{
            duration: isSpeaking ? 0.8 : 2,
            repeat: Infinity,
            ease: "easeInOut"
          }}
        />
      </svg>

      {/* Pulse rings when speaking */}
      {isSpeaking && (
        <>
          {[0, 1, 2].map((i) => (
            <motion.div
              key={i}
              className="absolute rounded-full border"
              style={{
                borderColor: color,
                width: size * 0.4,
                height: size * 0.4,
              }}
              initial={{ scale: 1, opacity: 0.4 }}
              animate={{
                scale: [1, 1.8 + i * 0.3],
                opacity: [0.4, 0],
              }}
              transition={{
                duration: 1.5,
                repeat: Infinity,
                delay: i * 0.3,
                ease: "easeOut"
              }}
            />
          ))}
        </>
      )}
    </motion.div>
  );
}
