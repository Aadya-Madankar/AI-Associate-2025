import { useEffect, useState } from "react";
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
  size = 400 
}: PolkaDotSphereProps) {
  const [dots, setDots] = useState<Array<{ 
    x: number; 
    y: number; 
    z: number; 
    delay: number; 
    baseX: number; 
    baseY: number;
    baseZ: number;
  }>>([]);

  // Speech state for animations
  const isSpeaking = isUserSpeaking || isAssistantSpeaking;
  const intensity = Math.min(volume * 4, 1);

  // Generate sphere dots on mount
  useEffect(() => {
    const sphereDots = [];
    const numDots = 80;
    
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
        delay: Math.random() * 1.5
      });
    }
    
    setDots(sphereDots);
  }, [size]);

  // Dynamic color based on speaking state
  const getDotColor = () => {
    if (isAssistantSpeaking) return "#fbbf24"; // Golden for assistant
    if (isUserSpeaking) return "#3b82f6"; // Blue for user  
    return "#8b5cf6"; // Purple for idle
  };

  const dotColor = getDotColor();

  // Dynamic position morphing when speaking
  const getMorphedPosition = (dot: any, index: number) => {
    if (!isSpeaking) {
      return { x: dot.baseX, y: dot.baseY };
    }

    const time = Date.now() * 0.001;
    const waveX = Math.sin(time * 2 + index * 0.3) * intensity * 20;
    const waveY = Math.cos(time * 1.5 + index * 0.2) * intensity * 15;
    
    return {
      x: dot.baseX + waveX,
      y: dot.baseY + waveY
    };
  };

  if (!isActive) return null;

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.5 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 0.5 }}
      transition={{ duration: 0.5 }}
      className="relative flex items-center justify-center"
      style={{ width: size, height: size }}
    >
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        className="absolute inset-0"
      >
        <defs>
          <radialGradient id="sphereGlow" cx="50%" cy="50%" r="60%">
            <stop offset="0%" stopColor={dotColor} stopOpacity={isSpeaking ? 0.3 : 0.1} />
            <stop offset="100%" stopColor={dotColor} stopOpacity={0} />
          </radialGradient>
          
          <filter id="dotGlow" x="-100%" y="-100%" width="300%" height="300%">
            <feGaussianBlur stdDeviation={isSpeaking ? "4" : "2"} result="coloredBlur"/>
            <feMerge> 
              <feMergeNode in="coloredBlur"/>
              <feMergeNode in="SourceGraphic"/>
            </feMerge>
          </filter>

          <filter id="assistantGlow" x="-100%" y="-100%" width="300%" height="300%">
            <feGaussianBlur stdDeviation="6" result="coloredBlur"/>
            <feColorMatrix type="matrix" values="1 0.8 0 0 0  0 0.8 0.6 0 0  0 0 0.2 0 0  0 0 0 1 0"/>
            <feMerge> 
              <feMergeNode in="coloredBlur"/>
              <feMergeNode in="SourceGraphic"/>
            </feMerge>
          </filter>
        </defs>

        <motion.circle
          cx={size / 2}
          cy={size / 2}
          r={size / 3}
          fill="url(#sphereGlow)"
          animate={{
            r: isSpeaking ? [size / 3, size / 2.5, size / 3] : size / 3,
            opacity: isSpeaking ? [0.3, 0.6, 0.3] : 0.2
          }}
          transition={{
            duration: isSpeaking ? 1.5 : 2,
            repeat: isSpeaking ? Infinity : 0,
            ease: "easeInOut"
          }}
        />

        {dots.map((dot, index) => {
          const morphedPos = getMorphedPosition(dot, index);
          const scale = (dot.baseZ + size / 4) / (size / 2);
          const opacity = Math.max(0.4, scale);
          const baseSize = Math.max(3, 10 * scale);
          
          const dynamicSize = isSpeaking 
            ? baseSize * (1 + intensity * 0.8 + Math.sin(Date.now() * 0.005 + index) * 0.3)
            : baseSize;

          return (
            <motion.circle
              key={index}
              cx={morphedPos.x}
              cy={morphedPos.y}
              r={dynamicSize}
              fill={dotColor}
              opacity={opacity}
              filter={isAssistantSpeaking ? "url(#assistantGlow)" : "url(#dotGlow)"}
              animate={{
                cx: morphedPos.x,
                cy: morphedPos.y,
                r: dynamicSize,
                fill: dotColor,
                opacity: isSpeaking ? opacity * (0.7 + intensity * 0.3) : opacity * 0.6
              }}
              transition={{
                duration: 0.1,
                ease: "easeOut"
              }}
            />
          );
        })}

        <motion.circle
          cx={size / 2}
          cy={size / 2}
          r={8}
          fill={dotColor}
          opacity={0.9}
          filter="url(#dotGlow)"
          animate={{
            r: isSpeaking ? [8, 12 + intensity * 8, 8] : [8, 10, 8],
            opacity: isSpeaking ? [0.9, 0.5, 0.9] : [0.6, 0.4, 0.6],
            fill: dotColor
          }}
          transition={{
            duration: isSpeaking ? 1 : 3,
            repeat: Infinity,
            ease: "easeInOut"
          }}
        />

        {isSpeaking && (
          <>
            {[...Array(3)].map((_, i) => (
              <motion.circle
                key={`wave-${i}`}
                cx={size / 2}
                cy={size / 2}
                r={30}
                fill="none"
                stroke={dotColor}
                strokeWidth="2"
                opacity={0.3}
                initial={{ r: 30, opacity: 0.3 }}
                animate={{
                  r: [30, 80 + i * 20, 120],
                  opacity: [0.3, 0.1, 0],
                }}
                transition={{
                  duration: 2,
                  repeat: Infinity,
                  delay: i * 0.4,
                  ease: "easeOut"
                }}
              />
            ))}
          </>
        )}
      </svg>

      <div className="absolute inset-0 pointer-events-none">
        {[...Array(isSpeaking ? 12 : 6)].map((_, i) => (
          <motion.div
            key={i}
            className="absolute w-1 h-1 rounded-full"
            style={{
              backgroundColor: dotColor,
              left: '50%',
              top: '50%',
            }}
            animate={{
              x: [0, Math.cos(i * Math.PI / 6) * (60 + intensity * 60 + (isSpeaking ? 40 : 0))],
              y: [0, Math.sin(i * Math.PI / 6) * (60 + intensity * 60 + (isSpeaking ? 40 : 0))],
              opacity: [0, isSpeaking ? 0.8 : 0.4, 0],
              scale: [0, 1 + intensity * 1.5, 0],
            }}
            transition={{
              duration: isSpeaking ? 2 : 4,
              repeat: Infinity,
              delay: i * 0.3,
              ease: "easeOut"
            }}
          />
        ))}
      </div>
    </motion.div>
  );
}
