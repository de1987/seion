#!/usr/bin/env bash
# Records a smooth demo clip from an emulator with screenrecord, and proves it is smooth.
#
#   tools/dev/record-clip.sh -s <serial> -d <seconds> -o <out.mp4> [options]
#
#   -s SERIAL     adb serial, e.g. emulator-5554 (required: never guess the device)
#   -d SECONDS    clip length, 1..180 (default 15)
#   -o OUT.mp4    output path (required); also writes OUT.fps.txt and OUT-{start,middle,end}.png
#   -b BITRATE    screenrecord bit rate in bit/s (default 12000000)
#   -z WxH        screenrecord size (default: the device's native size)
#   -w SECONDS    warm-up: playback runs this long before each take (default 4)
#   -r N          takes to try before giving up (default 3)
#   -f FPS        minimum frames in every full second (default 45)
#   -g SECONDS    maximum gap between two frames (default 0.1)
#   -t SECONDS    how long to wait for a quiet host before failing (default 900)
#   -m TEXT       the playing track's title/artist/album must contain TEXT before and after each take
#   -n            don't touch playback (no media key, no playing check)
#
# Why the checks: the emulator renders on the host CPU, so a Gradle/Kotlin compile on the same machine starves it and
# the clip stutters (a busy Gradle daemon can drop it to a few fps for seconds). Before each take the
# host must be quiet: Gradle/Kotlin/aapt2 processes together under 0.5 core (sampled over 2 s) and the 1-minute load
# average under 0.6 x nproc. After each take ffprobe counts the frames per second; a take with a slow second or a long
# gap is rejected and retried. screenrecord only emits a frame when the screen changes, so the checks assume the
# screen moves all the time (the spectrum band does while playing). The wait for a quiet host can outlast a short
# track: set the player to repeat one and pass -m so a take never runs on the wrong (or a silent) track.
set -euo pipefail

serial="" seconds=15 out="" bitrate=12000000 size="" warmup=4 tries=3 min_fps=45 max_gap=0.1 wait_max=900 play=1 match=""
gradle_cores_max=0.5 load_ratio_max=0.6

usage() {
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

while getopts "s:d:o:b:z:w:r:f:g:t:m:nh" opt; do
    case $opt in
        s) serial=$OPTARG ;;
        d) seconds=$OPTARG ;;
        o) out=$OPTARG ;;
        b) bitrate=$OPTARG ;;
        z) size=$OPTARG ;;
        w) warmup=$OPTARG ;;
        r) tries=$OPTARG ;;
        f) min_fps=$OPTARG ;;
        g) max_gap=$OPTARG ;;
        t) wait_max=$OPTARG ;;
        m) match=$OPTARG ;;
        n) play=0 ;;
        *) usage ;;
    esac
done
[[ -n $serial && -n $out ]] || usage
[[ $seconds =~ ^[0-9]+$ && $seconds -ge 1 && $seconds -le 180 ]] || {
    echo "record-clip: -d must be 1..180" >&2
    exit 2
}
[[ $out == *.mp4 ]] || {
    echo "record-clip: -o must end in .mp4" >&2
    exit 2
}

log() { echo "record-clip: $*" >&2; }
die() {
    log "$*"
    exit 1
}

# --- tools (also when they are not on PATH) ---
find_tool() {
    local name=$1 c
    shift
    if command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
        return
    fi
    for c in "$@"; do
        if [[ -x $c ]]; then
            echo "$c"
            return
        fi
    done
    die "$name not found (PATH or: $*)"
}
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
adb_bin=$(find_tool adb "$sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb")
ffmpeg_bin=$(find_tool ffmpeg)
ffprobe_bin=$(find_tool ffprobe)
adb() { "$adb_bin" -s "$serial" "$@"; }

[[ $(adb get-state 2>/dev/null) == device ]] || die "$serial is not connected (adb devices)"

# --- host preflight ---
clk_tck=$(getconf CLK_TCK)
ncpu=$(nproc)

# Total CPU ticks of the build tool processes (Gradle daemon and workers, Kotlin daemon, aapt2).
build_ticks() {
    local total=0 d pid cmd stat rest
    local -a f
    for d in /proc/[0-9]*; do
        pid=${d#/proc/}
        cmd=$(tr '\0' ' ' 2>/dev/null <"$d/cmdline") || continue
        case $cmd in
            *GradleDaemon* | *GradleWorkerMain* | *gradle-worker* | *KotlinCompileDaemon* | *kotlin-daemon* | \
                *kotlin-build-tools* | *aapt2*) ;;
            *) continue ;;
        esac
        stat=$(cat 2>/dev/null "/proc/$pid/stat") || continue
        rest=${stat##*) }
        read -r -a f <<<"$rest"
        total=$((total + f[11] + f[12]))
    done
    echo "$total"
}

# Prints why the host is busy, or nothing when it is quiet.
host_busy() {
    local t0 t1 cores load
    t0=$(build_ticks)
    sleep 2
    t1=$(build_ticks)
    cores=$(awk -v a="$t0" -v b="$t1" -v hz="$clk_tck" 'BEGIN { printf "%.2f", (b - a) / hz / 2 }')
    load=$(cut -d' ' -f1 /proc/loadavg)
    if awk -v c="$cores" -v m="$gradle_cores_max" 'BEGIN { exit !(c > m) }'; then
        echo "Gradle/Kotlin build processes are using $cores cores (limit $gradle_cores_max): a compile is running"
    elif awk -v l="$load" -v n="$ncpu" -v r="$load_ratio_max" 'BEGIN { exit !(l > n * r) }'; then
        echo "1-minute load average $load is above $load_ratio_max x $ncpu cores"
    fi
}

wait_for_quiet_host() {
    local start why
    start=$(date +%s)
    while why=$(host_busy) && [[ -n $why ]]; do
        if (($(date +%s) - start >= wait_max)); then
            die "host still busy after ${wait_max}s: $why"
        fi
        log "waiting: $why"
        sleep 15
    done
}

# --- device preparation ---
adb shell svc power stayon true >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true

is_playing() {
    local sessions
    sessions=$(adb shell dumpsys media_session)
    [[ $sessions == *'state=PlaybackState {state=PLAYING'* ]] || return 1
    [[ -z $match || $sessions == *"description="*"$match"* ]]
}

check_track() {
    ((play)) || return 0
    local what=""
    [[ -n $match ]] && what=" a track matching \"$match\""
    is_playing || die "not playing$what: open the right track (repeat one helps)"
}

warm_up() {
    ((play)) || return 0
    if ! is_playing; then
        log "nothing is playing: sending MEDIA_PLAY"
        adb shell input keyevent KEYCODE_MEDIA_PLAY
        sleep 1
    fi
    check_track
    sleep "$warmup"
}

# --- verification ---
# Prints "fps: n n n ... | frames=N maxgap=G at T" and exits non-zero when the take is not smooth.
check_take() {
    "$ffprobe_bin" -v error -select_streams v:0 -show_entries packet=pts_time -of csv=p=0 "$1" | sort -g | awk \
        -v full="$seconds" -v minfps="$min_fps" -v maxgap="$max_gap" '
        NF { t[n++] = $1 }
        END {
            if (n < 2) { print "fps: (no frames)"; exit 1 }
            t0 = t[0]; gap = 0; at = 0
            for (i = 0; i < n; i++) {
                s = int(t[i] - t0); c[s]++
                if (i && t[i] - t[i - 1] > gap) { gap = t[i] - t[i - 1]; at = t[i - 1] - t0 }
            }
            span = t[n - 1] - t0; whole = int(span); if (whole > full) whole = full
            bad = 0; line = "fps:"
            for (s = 0; s < whole; s++) { line = line " " (c[s] + 0); if (c[s] + 0 < minfps) bad = 1 }
            if (gap > maxgap) bad = 1
            if (span < full - 1) bad = 1
            printf "%s | frames=%d span=%.2fs maxgap=%.3fs at %.2fs\n", line, n, span, gap, at
            exit bad
        }'
}

# --- record ---
base=${out%.mp4}
take="$base.take.mp4"
report="$base.fps.txt"
remote=/sdcard/record-clip.mp4
mkdir -p "$(dirname "$out")"
: >"$report"
args=(--time-limit "$seconds" --bit-rate "$bitrate")
[[ -n $size ]] && args+=(--size "$size")

ok=0
for ((i = 1; i <= tries; i++)); do
    wait_for_quiet_host
    warm_up
    log "take $i/$tries: ${seconds}s at $bitrate bit/s"
    adb shell screenrecord "${args[@]}" "$remote"
    adb pull "$remote" "$take" >/dev/null
    check_track
    if result=$(check_take "$take"); then
        echo "take $i OK  $result" | tee -a "$report" >&2
        ok=1
        break
    fi
    echo "take $i BAD $result" | tee -a "$report" >&2
done
((ok)) || die "no smooth take in $tries tries (see $report; the last take is $take)"
mv -f "$take" "$out"

# --- check frames ---
dur=$("$ffprobe_bin" -v error -show_entries format=duration -of csv=p=0 "$out")
for pos in start middle end; do
    case $pos in
        start) ss=0.5 ;;
        middle) ss=$(awk -v d="$dur" 'BEGIN { printf "%.2f", d / 2 }') ;;
        end) ss=$(awk -v d="$dur" 'BEGIN { printf "%.2f", d - 0.5 }') ;;
    esac
    "$ffmpeg_bin" -hide_banner -loglevel error -y -ss "$ss" -i "$out" -frames:v 1 "$base-$pos.png"
done
log "wrote $out (${dur}s), $report, $base-{start,middle,end}.png"
