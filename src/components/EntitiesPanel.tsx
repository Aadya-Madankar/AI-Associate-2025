import { Card } from "./ui/card";
import { Users, User, Bot } from "lucide-react";

export function EntitiesPanel() {
  return (
    <Card className="w-full max-w-2xl mx-auto bg-black/20 backdrop-blur-xl border-white/10 p-6">
      <h2 className="text-2xl font-bold text-white mb-6 flex items-center">
        <Users className="mr-3 h-6 w-6" />
        Entities
      </h2>
      
      <div className="grid grid-cols-2 gap-4">
        <div className="p-4 bg-white/5 rounded-lg border border-white/10 text-center">
          <User className="h-8 w-8 text-blue-400 mx-auto mb-2" />
          <h3 className="text-white font-medium">You</h3>
          <p className="text-white/60 text-sm">Primary User</p>
        </div>
        
        <div className="p-4 bg-white/5 rounded-lg border border-white/10 text-center">
          <Bot className="h-8 w-8 text-purple-400 mx-auto mb-2" />
          <h3 className="text-white font-medium">XENO</h3>
          <p className="text-white/60 text-sm">AI Assistant</p>
        </div>
      </div>
    </Card>
  );
}
