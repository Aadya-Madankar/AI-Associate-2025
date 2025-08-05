import { useEffect, useRef } from "react";
import { motion } from "framer-motion";

interface AudioVisualizerProps {
  volume: number;
  isActive: boolean;
  size?: number;
  className?: string;
}

export function AudioVisualizer({ volume, isActive, size = 320, className = "" }: AudioVisualizerProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const centerX = size / 2;
    const centerY = size / 2;
    const baseRadius = size * 0.1;

    let animationId: number;

    const draw = () => {
      ctx.clearRect(0, 0, size, size);

      // Draw multiple rings with smooth gradients
      for (let i = 0; i < 4; i++) {
        const radius = baseRadius + (i * 25) + (volume * 80 * (i + 1) * 0.5);
        const opacity = Math.max(0.05, 0.4 - (i * 0.08) - (volume * 1.5));
        
        // Create gradient
        const gradient = ctx.createRadialGradient(centerX, centerY, radius - 10, centerX, centerY, radius + 10);
        gradient.addColorStop(0, `rgba(147, 51, 234, ${opacity})`);
        gradient.addColorStop(1, `rgba(59, 130, 246, ${opacity * 0.3})`);
        
        ctx.beginPath();
        ctx.arc(centerX, centerY, radius, 0, 2 * Math.PI);
        ctx.strokeStyle = gradient;
        ctx.lineWidth = 2;
        ctx.stroke();
      }

      // Draw center pulse with gradient
      if (isActive) {
        const pulseRadius = baseRadius + (volume * 40);
        const gradient = ctx.createRadialGradient(centerX, centerY, 0, centerX, centerY, pulseRadius);
        gradient.addColorStop(0, `rgba(59, 130, 246, ${0.6 + volume * 0.4})`);
        gradient.addColorStop(1, `rgba(147, 51, 234, ${0.2 + volume * 0.3})`);
        
        ctx.beginPath();
        ctx.arc(centerX, centerY, pulseRadius, 0, 2 * Math.PI);
        ctx.fillStyle = gradient;
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
        className="absolute inset-0 rounded-full bg-gradient-to-br from-purple-500/10 to-blue-500/10 backdrop-blur-sm"
        animate={{
          scale: isActive ? [1, 1.02, 1] : 1,
          opacity: isActive ? [0.3, 0.5, 0.3] : 0.2,
        }}
        transition={{
          duration: 3,
          repeat: isActive ? Infinity : 0,
          ease: "easeInOut",
        }}
      />
    </div>
  );
}
