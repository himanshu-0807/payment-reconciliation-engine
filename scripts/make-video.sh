#!/bin/sh
# Builds the README video from the Playwright recording:
#   1. node web/scripts/narrate.mjs     voice-over clips (macOS `say`, or drop-in clips from another TTS)
#   2. node web/scripts/demo-video.mjs  records the walkthrough, holding each scene for its narration
#   3. scripts/make-video.sh            (this script) mixes and encodes:
#   docs/video/walkthrough.mp4  narrated walkthrough, H.264 + AAC, soft English subtitles
#   docs/video/walkthrough.srt  the subtitles
#   docs/video/walkthrough.gif  silent preview at 6x speed, 5 fps, 720 px (GitHub plays GIFs inline)
# Needs ffmpeg with libx264.
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
IN="$ROOT_DIR/docs/video/raw/walkthrough.webm"
OUT="$ROOT_DIR/docs/video"
[ -f "$IN" ] || { echo "record first: cd web && node scripts/demo-video.mjs" >&2; exit 1; }
node "$ROOT_DIR/web/scripts/mix-video.mjs"
ffmpeg -v error -y -i "$IN" -vf "setpts=PTS/${GIF_SPEED:-6},fps=${GIF_FPS:-5},scale=${GIF_WIDTH:-720}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" "$OUT/walkthrough.gif"
ls -l "$OUT"/walkthrough.mp4 "$OUT"/walkthrough.gif
