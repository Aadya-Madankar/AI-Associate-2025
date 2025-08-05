import { useEffect, useRef } from "react";
import { motion } from "framer-motion";

interface AudioVisualizerProps {
  volume: number;
  isActive: boolean;
  size?: number;
  className?: string;
}

export function AudioVisualizer({ volume, isActive, size = 200, className = "" }: AudioVisualizerProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const centerX = size / 2;
    const centerY = size / 2;
    const baseRadius = size * 0.15;

    let animationId: number;

    const draw = () => {
      ctx.clearRect(0, 0, size, size);

      // Draw multiple rings
      for (let i = 0; i < 5; i++) {
        const radius = baseRadius + (i * 15) + (volume * 100 * (i + 1) * 0.3);
        const opacity = Math.max(0.1, 0.8 - (i * 0.15) - (volume * 2));
        
        ctx.beginPath();
        ctx.arc(centerX, centerY, radius, 0, 2 * Math.PI);
        ctx.strokeStyle = `rgba(147, 51, 234, ${opacity})`; // Purple
        ctx.lineWidth = 2;
        ctx.stroke();
      }

      // Draw center pulse
      if (isActive) {
        const pulseRadius = baseRadius + (volume * 50);
        ctx.beginPath();
        ctx.arc(centerX, centerY, pulseRadius, 0, 2 * Math.PI);
        ctx.fillStyle = `rgba(59, 130, 246, ${0.3 + volume * 0.7})`; // Blue
        ctx.fill();
      }

      animationId = requestAnimationFrame(draw);
    };

    draw();

    return () => {
      if (animationId) {
        cancelAnimationFrame(animationId);
      }
    };
  }, [volume, isActive, size]);

  return (
    <div className={`relative ${className}`}>
      <canvas
        ref={canvasRef}
        width={size}
        height={size}
        className="absolute inset-0"
      />
      <motion.div
        className="absolute inset-0 rounded-full bg-gradient-to-r from-purple-500/20 to-blue-500/20 backdrop-blur-sm"
        animate={{
          scale: isActive ? [1, 1.05, 1] : 1,
          opacity: isActive ? [0.5, 0.8, 0.5] : 0.3,
        }}
        transition={{
          duration: 2,
          repeat: isActive ? Infinity : 0,
          ease: "easeInOut",
        }}
      />
    </div>
  );
}
