export default `
class VolMeterWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    if (input.length > 0) {
      const samples = input[0];
      let sum = 0;
      for (let i = 0; i < samples.length; i++) {
        sum += Math.abs(samples[i]);
      }
      const volume = sum / samples.length;
      this.port.postMessage({ volume });
    }
    return true;
  }
}
`;
