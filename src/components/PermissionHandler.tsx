import { useState, useEffect } from "react";
import { AlertTriangle, Check } from "lucide-react";
import { Card } from "./ui/card";
import { Button } from "./ui/button";

interface PermissionStatus {
  camera: boolean;
  microphone: boolean;
  screen: boolean;
}

export function PermissionHandler() {
  const [permissions, setPermissions] = useState<PermissionStatus>({
    camera: false,
    microphone: false,
    screen: false
  });
  const [showPermissions, setShowPermissions] = useState(false);

  const checkPermissions = async () => {
    try {
      // Check microphone
      const micPermission = await navigator.permissions.query({ name: 'microphone' as PermissionName });
      
      // Check camera
      const cameraPermission = await navigator.permissions.query({ name: 'camera' as PermissionName });
      
      setPermissions({
        microphone: micPermission.state === 'granted',
        camera: cameraPermission.state === 'granted',
        screen: true // Screen share permission is requested on-demand
      });

      if (micPermission.state !== 'granted' || cameraPermission.state !== 'granted') {
        setShowPermissions(true);
      }
    } catch (error) {
      console.log("Permission check not supported");
    }
  };

  const requestPermissions = async () => {
    try {
      await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
      setPermissions({ camera: true, microphone: true, screen: true });
      setShowPermissions(false);
    } catch (error) {
      console.error("Permission denied:", error);
    }
  };

  useEffect(() => {
    checkPermissions();
  }, []);

  if (!showPermissions) return null;

  return (
    <div className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <Card className="bg-white/10 backdrop-blur-xl border-white/20 p-6 max-w-md w-full">
        <div className="text-center space-y-4">
          <div className="w-16 h-16 bg-orange-500/20 rounded-full flex items-center justify-center mx-auto">
            <AlertTriangle className="w-8 h-8 text-orange-400" />
          </div>
          
          <h3 className="text-xl font-bold text-white">Permissions Required</h3>
          <p className="text-white/70">XENO needs access to your camera and microphone for the best experience.</p>
          
          <div className="space-y-2 text-left">
            <div className="flex items-center space-x-3">
              {permissions.microphone ? (
                <Check className="w-5 h-5 text-green-400" />
              ) : (
                <AlertTriangle className="w-5 h-5 text-orange-400" />
              )}
              <span className="text-white/80">Microphone Access</span>
            </div>
            <div className="flex items-center space-x-3">
              {permissions.camera ? (
                <Check className="w-5 h-5 text-green-400" />
              ) : (
                <AlertTriangle className="w-5 h-5 text-orange-400" />
              )}
              <span className="text-white/80">Camera Access</span>
            </div>
          </div>

          <div className="flex space-x-3">
            <Button
              variant="outline"
              onClick={() => setShowPermissions(false)}
              className="flex-1 bg-white/10 border-white/20 text-white hover:bg-white/20"
            >
              Skip
            </Button>
            <Button
              onClick={requestPermissions}
              className="flex-1 bg-purple-600 hover:bg-purple-700"
            >
              Allow Access
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
