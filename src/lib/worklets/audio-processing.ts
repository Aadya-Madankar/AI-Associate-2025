export default `
class AudioRecordingWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
    this.bufferSize = 4096;
    this.buffer = new Float32Array(this.bufferSize);
    this.bufferIndex = 0;
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    if (input.length > 0) {
      const samples = input[0];
      
      for (let i = 0; i < samples.length; i++) {
        this.buffer[this.bufferIndex] = samples[i];
        this.bufferIndex++;
        
        if (this.bufferIndex >= this.bufferSize) {
          // Convert to Int16
          const int16Buffer = new Int16Array(this.bufferSize);
          for (let j = 0; j < this.bufferSize; j++) {
            int16Buffer[j] = Math.max(-32768, Math.min(32767, this.buffer[j] * 32768));
          }
          
          this.port.postMessage({
            data: {
              int16arrayBuffer: int16Buffer.buffer
            }
          });
          
          this.bufferIndex = 0;
        }
      }
    }
    return true;
  }
}
`;
