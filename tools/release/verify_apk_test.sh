#!/bin/sh
# Tests for verify_apk.sh and the release signing rule (`make release-test`):
#  1. the debug APK must be REJECTED (debug key, debuggable, .debug package);
#  2. the signed release APK must be ACCEPTED;
#  3. a STALE release APK (its versionCode or versionName differs from app/build.gradle.kts) must be REJECTED;
#  4. assembleRelease without a signing config must FAIL (never a debug-signed release).
# Usage: tools/release/verify_apk_test.sh [<release apk>]   (cases 2 and 3 need the APK)
set -u
cd "$(dirname "$0")/../.."
fail=0
tmp=$(mktemp -d "${TMPDIR:-/tmp}/seion-verify-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
if tools/release/verify_apk.sh app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1; then
    echo "FAIL: the debug APK passed the release verification"; fail=1
else
    echo "ok: the debug APK is rejected"
fi
if [ -n "${1:-}" ]; then
    if tools/release/verify_apk.sh "$1" >/dev/null 2>&1; then echo "ok: $1 is accepted"; else echo "FAIL: $1 rejected"; fail=1; fi
fi
if [ -n "${1:-}" ] && [ $fail -eq 0 ]; then
    # the same (accepted) APK against a build file one version ahead: what `make release` would see with a stale APK
    code=$(sed -n 's/^ *versionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
    sed "s/^\( *versionCode = \)[0-9][0-9]*/\1$((code + 1))/" app/build.gradle.kts >"$tmp/code.gradle.kts"
    if VERIFY_APK_GRADLE="$tmp/code.gradle.kts" tools/release/verify_apk.sh "$1" >"$tmp/code.log" 2>&1; then
        echo "FAIL: a stale APK (versionCode $code, build file $((code + 1))) passed"; fail=1
    else
        grep -q "stale build" "$tmp/code.log" && echo "ok: a stale versionCode is rejected" || { echo "FAIL: no stale-build message"; fail=1; }
    fi
    sed 's/^\( *versionName = "\)[^"]*"/\1stale-test"/' app/build.gradle.kts >"$tmp/name.gradle.kts"
    if VERIFY_APK_GRADLE="$tmp/name.gradle.kts" tools/release/verify_apk.sh "$1" >"$tmp/name.log" 2>&1; then
        echo "FAIL: an APK whose versionName differs from the build file passed"; fail=1
    else
        grep -q "stale build" "$tmp/name.log" && echo "ok: a stale versionName is rejected" || { echo "FAIL: no stale-build message"; fail=1; }
    fi
fi
# env -u: exported SEION_RELEASE_* values would otherwise win over the redirected properties file
if env -u SEION_RELEASE_STORE_FILE -u SEION_RELEASE_STORE_PASSWORD -u SEION_RELEASE_KEY_ALIAS \
    -u SEION_RELEASE_KEY_PASSWORD SEION_KEYSTORE_PROPERTIES=/nonexistent/keystore.properties \
    ./gradlew -q assembleRelease >"$tmp/nokey.log" 2>&1; then
    echo "FAIL: assembleRelease succeeded without a release key"; fail=1
else
    grep -q "refusing to build a release APK" "$tmp/nokey.log" && echo "ok: assembleRelease fails without a release key" || { echo "FAIL: no clear message"; fail=1; }
fi
exit $fail
