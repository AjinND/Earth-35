import type { SoundMix, SoundSource } from "./soundscape";
import type { ThunderSound } from "./storm";

type AudioConstructor = typeof AudioContext;
type AudioWindow = Window & { webkitAudioContext?: AudioConstructor };
type Bus = { gain: GainNode; pan: StereoPannerNode };
type Bed = Bus & { ceiling: number };
type Voice = { source: OscillatorNode; nodes: AudioNode[] };
type ThunderVoice = { sources: AudioBufferSourceNode[]; nodes: AudioNode[] };

const bounded = (value: number, min = 0, max = 1) =>
  Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : 0;

function audioConstructor(): AudioConstructor | undefined {
  if (typeof window === "undefined") return undefined;
  return window.AudioContext ?? (window as AudioWindow).webkitAudioContext;
}

/** A quiet synthesized first pass; recorded local wildlife can replace these voices later. */
export class WorldAudio {
  onStateChange: ((state: AudioContextState) => void) | null = null;
  private readonly context: AudioContext;
  private readonly master: GainNode;
  private readonly beds: Record<"wind" | "rain" | "surf" | "river" | "waterfall" | "insects", Bed>;
  private readonly birds: Bus;
  private readonly gulls: Bus;
  private readonly noise: AudioBuffer;
  private readonly thunderVoices = new Set<ThunderVoice>();
  private readonly nodes: AudioNode[] = [];
  private readonly sources: AudioScheduledSourceNode[] = [];
  private readonly voices = new Set<Voice>();
  private readonly targets = new WeakMap<AudioParam, number>();
  private timer: number | null = null;
  private schedulerActive = false;
  private wantsRunning = false;
  private disposed = false;
  private birdPresence = 0;
  private gullPresence = 0;
  private nextBird = 0;
  private nextGull = 0;
  private seed = 0x35eab127;

  static supported(): boolean {
    return audioConstructor() !== undefined;
  }

  /** Construct only inside the deliberate sound-enable gesture. Imports create no audio context. */
  constructor() {
    const Constructor = audioConstructor();
    if (!Constructor) throw new Error("This browser does not support world sound.");
    this.context = new Constructor({ latencyHint: "playback" });
    this.master = this.track(this.context.createGain());
    this.master.gain.value = 0;

    try {
      const compressor = this.track(this.context.createDynamicsCompressor());
      compressor.threshold.value = -18;
      compressor.knee.value = 8;
      compressor.ratio.value = 12;
      compressor.attack.value = 0.008;
      compressor.release.value = 0.35;
      this.master.connect(compressor);
      compressor.connect(this.context.destination);

      // One decorrelated stereo buffer serves the outdoor beds at independent offsets.
      // Low-rate native oscillators vary gain without JavaScript audio processing.
      const noise = this.noise = this.makeNoise(12);
      this.beds = {
        wind: this.makeBed(noise, 45, 750, 0.22, 0.061, 0.2),
        rain: this.makeBed(noise, 650, 7600, 0.12, 0.027, 0.045),
        surf: this.makeBed(noise, 80, 2300, 0.2, 0.097, 0.36),
        river: this.makeBed(noise, 230, 3500, 0.14, 0.143, 0.12),
        waterfall: this.makeBed(noise, 90, 6800, 0.17, 0.043, 0.055),
        insects: this.makeBed(this.makeNoise(12, true), 4400, 6600, 0.045),
      };
      this.birds = this.makeBus();
      this.gulls = this.makeBus();
      this.context.onstatechange = () => {
        if (this.disposed) return;
        if (this.context.state === "running" && this.wantsRunning) {
          this.startScheduler();
        } else {
          this.stopScheduler();
          this.clearVoices();
          this.cancelThunder();
        }
        this.onStateChange?.(this.context.state);
      };
    } catch (error) {
      this.dispose();
      throw error;
    }
  }

  get state(): AudioContextState {
    return this.disposed ? "closed" : this.context.state;
  }

  async resume(): Promise<void> {
    if (this.disposed) return;
    this.wantsRunning = true;
    await this.reconcileState();
  }

  async suspend(): Promise<void> {
    if (this.disposed) return;
    this.wantsRunning = false;
    this.stopScheduler();
    // Do not replay an unfinished wildlife call after returning from a hidden tab.
    this.clearVoices();
    this.cancelThunder();
    await this.reconcileState();
  }

  update(mix: SoundMix, volume: number): void {
    if (this.disposed || this.context.state === "closed") return;
    // The maximum bed levels leave headroom even when every source is present.
    this.smooth(this.master.gain, bounded(volume) * 0.6, 0.12);
    this.setBed(this.beds.wind, mix.wind);
    this.setBed(this.beds.rain, mix.rain);
    this.setBed(this.beds.surf, mix.surf.gain, mix.surf.pan);
    this.setBed(this.beds.river, mix.river.gain, mix.river.pan);
    this.setBed(this.beds.waterfall, mix.waterfall.gain, mix.waterfall.pan);
    this.setBed(this.beds.insects, mix.insects);
    this.birdPresence = bounded(mix.birds.gain);
    this.gullPresence = bounded(mix.gulls.gain);
    this.setWildlife(this.birds, mix.birds);
    this.setWildlife(this.gulls, mix.gulls);
  }

  get pendingThunder(): number { return this.thunderVoices.size; }

  /** A soft crack and rolling low-frequency tail, scheduled after the visible strike. */
  playThunder(sound: ThunderSound): boolean {
    if(this.disposed||!this.wantsRunning||this.context.state!=="running"||this.thunderVoices.size>=2)return false;
    const start=this.context.currentTime+bounded(sound.delay,0,9),gain=bounded(sound.gain,0,.55);
    if(gain<=0)return false;
    const pan=this.context.createStereoPanner();pan.pan.value=bounded(sound.pan,-1,1);pan.connect(this.master);
    const voice:ThunderVoice={sources:[],nodes:[pan]};
    for(const crack of [false,true]) {
      const source=this.context.createBufferSource(),filter=this.context.createBiquadFilter(),envelope=this.context.createGain();
      source.buffer=this.noise;source.loop=true;
      filter.type="lowpass";filter.frequency.value=crack?bounded(sound.cutoff,350,2150):270;
      filter.Q.value=.55;envelope.gain.value=0;
      source.connect(filter);filter.connect(envelope);envelope.connect(pan);
      voice.sources.push(source);voice.nodes.push(filter,envelope);
      const duration=crack?.7:5.6,level=gain*(crack?.09:.55);
      envelope.gain.setValueAtTime(0,start);
      envelope.gain.linearRampToValueAtTime(level,start+(crack?.035:.09));
      if(!crack) {
        envelope.gain.linearRampToValueAtTime(level*.38,start+.75);
        envelope.gain.linearRampToValueAtTime(level*.6,start+1.2);
      }
      envelope.gain.exponentialRampToValueAtTime(.0001,start+duration-.03);
      source.start(start,bounded(sound.seed)*10+(crack?.4:0));source.stop(start+duration);
    }
    this.thunderVoices.add(voice);
    voice.sources[0].onended=()=>this.releaseThunder(voice);
    return true;
  }

  cancelThunder(): void {
    for(const voice of this.thunderVoices) {
      for(const source of voice.sources)this.stopSource(source);
      this.releaseThunder(voice);
    }
  }

  private releaseThunder(voice:ThunderVoice): void {
    for(const source of voice.sources){source.onended=null;source.disconnect();}
    for(const node of voice.nodes)node.disconnect();
    this.thunderVoices.delete(voice);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.wantsRunning = false;
    this.stopScheduler();
    this.context.onstatechange = null;
    this.onStateChange = null;
    this.clearVoices();
    this.cancelThunder();
    for (const source of this.sources) this.stopSource(source);
    for (const node of this.nodes) node.disconnect();
    this.sources.length = 0;
    this.nodes.length = 0;
    // Closing can race a pending resume or a browser-initiated close. Disposal stays idempotent.
    if (this.context.state !== "closed") void this.context.close().catch(() => undefined);
  }

  private async reconcileState(): Promise<void> {
    try {
      while (!this.disposed && this.context.state !== "closed") {
        const running = this.wantsRunning;
        if (running && this.context.state !== "running") await this.context.resume();
        else if (!running && this.context.state !== "suspended") await this.context.suspend();
        if (this.disposed) return;
        // A later mute/resume request wins even if earlier browser promises finish out of order.
        if (running !== this.wantsRunning) continue;
        if (running && this.context.state === "running") this.startScheduler();
        else this.stopScheduler();
        return;
      }
    } catch (error) {
      // Closing during a pending transition is expected; other failures reach the sound UI.
      if (!this.disposed) throw error;
    }
  }

  private track<T extends AudioNode>(node: T): T {
    this.nodes.push(node);
    return node;
  }

  private random(): number {
    this.seed = (Math.imul(this.seed, 1664525) + 1013904223) >>> 0;
    return this.seed / 0x100000000;
  }

  private between(min: number, max: number): number {
    return min + (max - min) * this.random();
  }

  private makeNoise(seconds: number, insects = false): AudioBuffer {
    const sampleRate = Math.min(24000, this.context.sampleRate);
    const buffer = this.context.createBuffer(2, Math.floor(seconds * sampleRate), sampleRate);
    for (let channel = 0; channel < 2; channel++) {
      const data = buffer.getChannelData(channel);
      for (let index = 0; index < data.length; index++) {
        let envelope = 1;
        if (insects) {
          const time = index / sampleRate;
          // A narrow, noisy chirring chorus: unequal slow phrases and rapid wing pulses.
          // Whole-number cycles over the loop duration keep the envelope seam smooth.
          const phrase = Math.pow(0.5 + 0.5 * Math.sin(2 * Math.PI * time * (5 + channel * 2) / seconds), 3);
          const pulse = 0.5 + 0.5 * Math.sin(2 * Math.PI * time * (29 + channel * 4));
          envelope = (0.08 + phrase * 0.8) * (0.2 + pulse * 0.8);
        }
        data[index] = (this.random() * 2 - 1) * envelope;
      }
    }
    return buffer;
  }

  private makeBus(): Bus {
    const gain = this.track(this.context.createGain());
    const pan = this.track(this.context.createStereoPanner());
    gain.gain.value = 0;
    gain.connect(pan);
    pan.connect(this.master);
    return { gain, pan };
  }

  private makeBed(buffer: AudioBuffer, low: number, high: number, ceiling: number, rate = 0, depth = 0): Bed {
    const source = this.track(this.context.createBufferSource());
    source.buffer = buffer;
    source.loop = true;
    const highpass = this.track(this.context.createBiquadFilter());
    highpass.type = "highpass";
    highpass.frequency.value = low;
    highpass.Q.value = 0.55;
    const lowpass = this.track(this.context.createBiquadFilter());
    lowpass.type = "lowpass";
    lowpass.frequency.value = high;
    lowpass.Q.value = 0.55;
    const swell = this.track(this.context.createGain());
    swell.gain.value = 1 - depth;
    const bus = this.makeBus();
    source.connect(highpass);
    highpass.connect(lowpass);
    lowpass.connect(swell);
    swell.connect(bus.gain);
    this.sources.push(source);
    source.start(0, this.between(0, buffer.duration));
    if (rate > 0 && depth > 0) {
      const oscillator = this.track(this.context.createOscillator());
      const amount = this.track(this.context.createGain());
      oscillator.frequency.value = rate;
      amount.gain.value = depth;
      oscillator.connect(amount);
      amount.connect(swell.gain);
      this.sources.push(oscillator);
      oscillator.start();
    }
    return { ...bus, ceiling };
  }

  private smooth(param: AudioParam, value: number, seconds = 0.45): void {
    const previous = this.targets.get(param);
    if (previous !== undefined && Math.abs(previous - value) < 0.0001) return;
    const now = this.context.currentTime;
    param.cancelScheduledValues(now);
    param.setTargetAtTime(value, now, seconds);
    this.targets.set(param, value);
  }

  private setBed(bed: Bed, gain: number, pan = 0): void {
    this.smooth(bed.gain.gain, bounded(gain) * bed.ceiling);
    this.smooth(bed.pan.pan, bounded(pan, -1, 1), 0.25);
  }

  private setWildlife(bus: Bus, source: SoundSource): void {
    this.smooth(bus.gain.gain, bounded(source.gain));
    this.smooth(bus.pan.pan, bounded(source.pan, -1, 1), 0.25);
  }

  private startScheduler(): void {
    if (this.schedulerActive || this.disposed || !this.wantsRunning || this.context.state !== "running") return;
    this.schedulerActive = true;
    this.nextBird = this.context.currentTime + this.between(4, 10);
    this.nextGull = this.context.currentTime + this.between(10, 18);
    this.scheduleTick();
  }

  private scheduleTick(): void {
    this.timer = window.setTimeout(() => {
      this.timer = null;
      if (!this.schedulerActive || this.disposed || !this.wantsRunning || this.context.state !== "running") {
        this.stopScheduler();
        return;
      }
      const now = this.context.currentTime;
      if (now >= this.nextBird) {
        if (this.birdPresence > 0.08) this.songbird(now + 0.03);
        this.nextBird = now + this.between(8, 21);
      }
      if (now >= this.nextGull) {
        if (this.gullPresence > 0.1) this.gull(now + 0.03);
        this.nextGull = now + this.between(17, 34);
      }
      this.scheduleTick();
    }, 1600);
  }

  private stopScheduler(): void {
    this.schedulerActive = false;
    if (this.timer !== null) window.clearTimeout(this.timer);
    this.timer = null;
  }

  private songbird(start: number): void {
    const base = this.between(2600, 3900);
    const notes = Math.floor(this.between(2, 5));
    let time = start;
    for (let index = 0; index < notes; index++) {
      const duration = this.between(0.1, 0.19);
      const pitch = base * this.between(0.88, 1.18);
      const voice = this.makeVoice(this.birds, "sine");
      const envelope = voice.nodes[0] as GainNode;
      voice.source.frequency.setValueAtTime(pitch, time);
      voice.source.frequency.exponentialRampToValueAtTime(pitch * this.between(1.12, 1.32), time + duration * 0.32);
      voice.source.frequency.exponentialRampToValueAtTime(pitch * this.between(0.82, 0.99), time + duration);
      envelope.gain.setValueAtTime(0, time);
      envelope.gain.linearRampToValueAtTime(this.between(0.023, 0.038), time + 0.018);
      envelope.gain.exponentialRampToValueAtTime(0.0001, time + duration);
      voice.source.start(time);
      voice.source.stop(time + duration + 0.02);
      time += duration + this.between(0.055, 0.16);
    }
  }

  private gull(start: number): void {
    const voice = this.makeVoice(this.gulls, "triangle", 2300);
    const envelope = voice.nodes[0] as GainNode;
    const pitch = this.between(680, 900);
    const duration = this.between(0.7, 1.1);
    voice.source.frequency.setValueAtTime(pitch, start);
    voice.source.frequency.exponentialRampToValueAtTime(pitch * 1.45, start + 0.1);
    voice.source.frequency.exponentialRampToValueAtTime(pitch * 1.1, start + duration * 0.42);
    voice.source.frequency.exponentialRampToValueAtTime(pitch * 0.8, start + duration);
    envelope.gain.setValueAtTime(0, start);
    envelope.gain.linearRampToValueAtTime(this.between(0.018, 0.027), start + 0.06);
    envelope.gain.exponentialRampToValueAtTime(0.006, start + duration * 0.6);
    envelope.gain.exponentialRampToValueAtTime(0.0001, start + duration);
    voice.source.start(start);
    voice.source.stop(start + duration + 0.02);
  }

  private makeVoice(bus: Bus, type: OscillatorType, cutoff?: number): Voice {
    const source = this.context.createOscillator();
    source.type = type;
    const envelope = this.context.createGain();
    envelope.gain.value = 0;
    const voice: Voice = { source, nodes: [envelope] };
    if (cutoff) {
      const filter = this.context.createBiquadFilter();
      filter.type = "lowpass";
      filter.frequency.value = cutoff;
      filter.Q.value = 0.5;
      source.connect(filter);
      filter.connect(envelope);
      voice.nodes.push(filter);
    } else source.connect(envelope);
    envelope.connect(bus.gain);
    this.voices.add(voice);
    source.onended = () => this.releaseVoice(voice);
    return voice;
  }

  private releaseVoice(voice: Voice): void {
    voice.source.onended = null;
    voice.source.disconnect();
    for (const node of voice.nodes) node.disconnect();
    this.voices.delete(voice);
  }

  private clearVoices(): void {
    for (const voice of this.voices) {
      this.stopSource(voice.source);
      this.releaseVoice(voice);
    }
  }

  private stopSource(source: AudioScheduledSourceNode): void {
    try {
      source.stop();
    } catch (error) {
      // A partially constructed or already ended source has nothing left to stop.
      if (!(error instanceof DOMException && error.name === "InvalidStateError")) throw error;
    }
  }
}
