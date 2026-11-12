#!/usr/bin/env bash
# Generates a royalty-free test beat for demo clips of the spectrum band: 120 bpm, a kick with a pitch drop,
# noise hi-hats and snare, a four-note sine bassline and a slowly swelling arpeggio, so every part of the spectrum
# moves. Tagged with fictional names (title "Bars in Motion", artist "Seion Test Signal", album "Spectrum Demo").
#
#   tools/dev/make-test-beat.sh <out-dir> [seconds]     (default 60 s)
#   adb push "<out-dir>/Seion Demo Beat" /sdcard/Music/
#
# Writes <out-dir>/Seion Demo Beat/01 Bars in Motion.flac. Needs ffmpeg on PATH; keep the output out of the repo.
set -euo pipefail

usage() {
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage
out_dir=$1
seconds=${2:-60}
[[ $seconds =~ ^[0-9]+$ ]] || usage

ffmpeg=${FFMPEG:-$(command -v ffmpeg || true)}
[[ -n $ffmpeg ]] || {
    echo "make-test-beat: ffmpeg not found (put it on PATH or set FFMPEG)" >&2
    exit 1
}

kick='sin(2*PI*50*mod(t,0.5)*(1+3*exp(-25*mod(t,0.5))))*exp(-7*mod(t,0.5))*0.55'
hat='(2*random(0)-1)*exp(-45*mod(t+0.25,0.5))*0.22'
snare='((2*random(1)-1)*0.35+sin(2*PI*190*t)*0.2)*exp(-14*mod(t+0.5,1))'
root='55*pow(2,if(lt(mod(t,8),2),0,if(lt(mod(t,8),4),3/12,if(lt(mod(t,8),6),8/12,7/12))))'
bass="sin(2*PI*$root*t)*exp(-7*mod(t,0.25))*0.35"
lead="sin(2*PI*$root*8*pow(2,mod(floor(t*8),4)*4/12)*t)*exp(-12*mod(t,0.125))*0.12*(0.5+0.5*sin(2*PI*t/16))"
expr="($kick+$hat+$snare+$bass+$lead)*0.8"

dir="$out_dir/Seion Demo Beat"
mkdir -p "$dir"
file="$dir/01 Bars in Motion.flac"
"$ffmpeg" -hide_banner -loglevel error -y -f lavfi -i "aevalsrc=exprs='$expr':s=44100:d=$seconds" -ac 2 -c:a flac \
    -metadata title="Bars in Motion" -metadata artist="Seion Test Signal" -metadata album="Spectrum Demo" \
    -metadata track=1 "$file"
echo "$file"
