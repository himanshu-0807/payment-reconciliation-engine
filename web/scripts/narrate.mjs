// Generates the voice-over clips for the walkthrough and measures them.
//   node scripts/narrate.mjs            (VOICE=Samantha RATE=178 by default)
// Each scene in narration.json gets docs/video/raw/audio/<id>.wav. A clip that already exists as
// .wav/.mp3/.m4a is kept as is, so clips made with another TTS engine (e.g. ElevenLabs) can be dropped
// in and the rest of the pipeline reuses them. Missing clips are generated with macOS `say`.
// Writes docs/video/raw/durations.json, which demo-video.mjs uses to hold each scene long enough.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const RAW = fileURLToPath(new URL('../../docs/video/raw/', import.meta.url));
const AUDIO = `${RAW}audio/`;
const VOICE = process.env.VOICE ?? 'Samantha';
const RATE = process.env.RATE ?? '178';
const scenes = JSON.parse(readFileSync(new URL('./narration.json', import.meta.url)));
mkdirSync(AUDIO, { recursive: true });

const durations = {};
for (const s of scenes) {
  const existing = ['wav', 'mp3', 'm4a'].map((e) => `${AUDIO}${s.id}.${e}`).find((f) => existsSync(f));
  let file = existing;
  if (!file || process.env.REGENERATE === '1') {
    const aiff = `${AUDIO}${s.id}.aiff`;
    execFileSync('say', ['-v', VOICE, '-r', RATE, '-o', aiff, s.tts ?? s.text]);
    file = `${AUDIO}${s.id}.wav`;
    execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', aiff, '-ar', '48000', '-ac', '1', file]);
    rmSync(aiff);
  }
  const sec = Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file])
    .toString().trim());
  durations[s.id] = { file: file.slice(RAW.length), seconds: Math.round(sec * 1000) / 1000 };
  console.log(`${s.id.padEnd(15)} ${sec.toFixed(2)} s`);
}
writeFileSync(`${RAW}durations.json`, JSON.stringify(durations, null, 2));
const total = Object.values(durations).reduce((a, d) => a + d.seconds, 0);
console.log(`total narration ${total.toFixed(1)} s, voice ${VOICE} at ${RATE} wpm`);
