#!/bin/sh
# Converts the Playwright recording (web/scripts/demo-video.mjs) into the files used by the README:
#   docs/video/walkthrough.mp4  full walkthrough, H.264
#   docs/video/walkthrough.gif  silent preview at 4x speed, 5 fps, 720 px (GitHub plays GIFs inline)
# Needs ffmpeg with libx264.
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
IN="$ROOT_DIR/docs/video/raw/walkthrough.webm"
OUT="$ROOT_DIR/docs/video"
[ -f "$IN" ] || { echo "record first: cd web && node scripts/demo-video.mjs" >&2; exit 1; }
ffmpeg -v error -y -i "$IN" -c:v libx264 -preset slow -crf 24 -pix_fmt yuv420p -r 25 -movflags +faststart "$OUT/walkthrough.mp4"
ffmpeg -v error -y -i "$IN" -vf "setpts=PTS/${GIF_SPEED:-4},fps=${GIF_FPS:-5},scale=${GIF_WIDTH:-720}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" "$OUT/walkthrough.gif"
ls -l "$OUT"/walkthrough.mp4 "$OUT"/walkthrough.gif
