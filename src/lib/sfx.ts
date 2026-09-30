import { SOUND_CUES, type Tone } from "./sfxTones";
import { useSettingsStore } from "@store/settingsStore";

// Tiny WebAudio synth for UI cues. No binary audio assets: every sound here is
// a short oscillator blip built at play time. Browsers block audio until a
// user gesture, so the context is created lazily on first call (armAudio
// should be wired to the first pointerdown/keydown in the app root).

let ctx: AudioContext | null = null;

function getCtx(): AudioContext | null {
  if (typeof window === "undefined") return null;
  const AC = window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!AC) return null;
  if (!ctx) ctx = new AC();
  if (ctx.state === "suspended") void ctx.resume();
  return ctx;
}

/** Call once on the first user gesture to satisfy the browser autoplay policy. */
export function armAudio(): void {
  getCtx();
}

function playTones(tones: Tone[]): void {
  const { soundOn, volume } = useSettingsStore.getState();
  if (!soundOn || volume <= 0) return;
  const audio = getCtx();
  if (!audio) return;

  const now = audio.currentTime;
  for (const t of tones) {
    const osc = audio.createOscillator();
    const gainNode = audio.createGain();
    osc.type = t.type ?? "sine";
    osc.frequency.value = t.freq;

    const start = now + (t.delay ?? 0);
    const peak = (t.gain ?? 0.2) * volume;
    gainNode.gain.setValueAtTime(0, start);
    gainNode.gain.linearRampToValueAtTime(peak, start + 0.01);
    gainNode.gain.exponentialRampToValueAtTime(0.0001, start + t.duration);

    osc.connect(gainNode);
    gainNode.connect(audio.destination);
    osc.start(start);
    osc.stop(start + t.duration + 0.02);
  }
}

export const sfx = {
  turnAdvance(): void { playTones(SOUND_CUES.turnAdvance); }
  pollUp(): void { playTones(SOUND_CUES.pollUp); }
  pollDown(): void { playTones(SOUND_CUES.pollDown); }
  eventPopup(): void { playTones(SOUND_CUES.eventPopup); }
  win(): void { playTones(SOUND_CUES.win); }
  lose(): void { playTones(SOUND_CUES.lose); }
};
