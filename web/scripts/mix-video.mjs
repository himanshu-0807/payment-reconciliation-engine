// Mixes the voice-over into the recording: each clip starts exactly when its scene started
// (timeline.json from demo-video.mjs). Output: docs/video/walkthrough.mp4 (H.264 + AAC, with a soft
// English subtitle track) and docs/video/walkthrough.srt.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const RAW = fileURLToPath(new URL('../../docs/video/raw/', import.meta.url));
const OUT = fileURLToPath(new URL('../../docs/video/', import.meta.url));
const timeline = JSON.parse(readFileSync(`${RAW}timeline.json`));
const durations = JSON.parse(readFileSync(`${RAW}durations.json`));
const texts = Object.fromEntries(JSON.parse(readFileSync(new URL('./narration.json', import.meta.url))).map((n) => [n.id, n.text]));
const video = `${RAW}walkthrough.webm`;
const length = Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', video]).toString());
// Playwright starts the recording slightly before the page is ready; shift audio by this much (ms).
const OFFSET = Number(process.env.AUDIO_OFFSET_MS ?? 0);

// ---- subtitles: one cue per sentence group, split so no cue is longer than ~2 lines
const ts = (ms) => {
  const t = Math.max(0, Math.round(ms));
  const h = String(Math.floor(t / 3600000)).padStart(2, '0');
  const m = String(Math.floor(t / 60000) % 60).padStart(2, '0');
  const s = String(Math.floor(t / 1000) % 60).padStart(2, '0');
  return `${h}:${m}:${s},${String(t % 1000).padStart(3, '0')}`;
};
let srt = '';
let n = 0;
for (const sc of timeline) {
  const sentences = texts[sc.id].match(/[^.!?]+[.!?]+/g) ?? [texts[sc.id]];
  const chunks = [];
  for (const sen of sentences) {
    const last = chunks[chunks.length - 1];
    if (last && (last + sen).length < 110) chunks[chunks.length - 1] = last + sen; else chunks.push(sen.trim());
  }
  const total = chunks.reduce((a, c) => a + c.length, 0);
  let at = sc.startMs + OFFSET;
  for (const c of chunks) {
    const dur = sc.narrationMs * (c.length / total);
    srt += `${++n}\n${ts(at)} --> ${ts(at + dur - 40)}\n${c.trim()}\n\n`;
    at += dur;
  }
}
writeFileSync(`${OUT}walkthrough.srt`, srt);

// ---- audio mix
const args = ['-v', 'error', '-y', '-i', video];
timeline.forEach((sc) => args.push('-i', `${RAW}${durations[sc.id].file}`));
const parts = timeline.map((sc, i) => {
  const d = Math.max(0, sc.startMs + OFFSET);
  return `[${i + 1}:a]aformat=sample_rates=48000:channel_layouts=mono,adelay=${d}:all=1[a${i}]`;
});
const mixIn = timeline.map((_, i) => `[a${i}]`).join('');
const fadeOut = Math.max(0, length - 1.2).toFixed(2);
const filter = [
  ...parts,
  `${mixIn}amix=inputs=${timeline.length}:normalize=0:duration=longest,loudnorm=I=-16:TP=-1.5:LRA=11,`
    + `apad,atrim=0:${length.toFixed(2)},afade=t=in:d=0.4,afade=t=out:st=${fadeOut}:d=1.2[aout]`,
  `[0:v]fade=t=in:d=0.6,fade=t=out:st=${fadeOut}:d=1.2,format=yuv420p[vout]`,
].join(';');
args.push('-i', `${OUT}walkthrough.srt`, '-filter_complex', filter, '-map', '[vout]', '-map', '[aout]',
  '-map', `${timeline.length + 1}:s`, '-c:v', 'libx264', '-preset', 'slow', '-crf', '24', '-r', '25',
  '-c:a', 'aac', '-b:a', '160k', '-ar', '48000', '-c:s', 'mov_text', '-metadata:s:s:0', 'language=eng',
  '-metadata', 'title=Payment Reconciliation Engine walkthrough', '-movflags', '+faststart',
  `${OUT}walkthrough.mp4`);
execFileSync('ffmpeg', args, { stdio: 'inherit' });
console.log(`wrote ${OUT}walkthrough.mp4 (${length.toFixed(1)} s, ${timeline.length} narrated scenes) and walkthrough.srt`);
