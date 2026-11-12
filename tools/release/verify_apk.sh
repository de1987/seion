#!/bin/sh
# Checks a release APK before it is shared. Fails unless
#  - apksigner verifies the signature (and, when an expected certificate SHA-256 is given, the signer matches it);
#  - the manifest has no android:debuggable="true" and no android:testOnly="true";
#  - the package is io.github.de1987.seion (no .debug suffix);
#  - versionCode and versionName equal the ones in app/build.gradle.kts (a stale APK from an older build fails).
# Usage: tools/release/verify_apk.sh <apk> [<expected certificate SHA-256>]
# The expected digest can also come from SEION_RELEASE_CERT_SHA256; without one the signer is not pinned.
# VERIFY_APK_GRADLE overrides the build file the versions are read from (used by verify_apk_test.sh).
set -u
apk=${1:?usage: verify_apk.sh <apk> [<expected certificate SHA-256>]}
expected_sha=$(echo "${2:-${SEION_RELEASE_CERT_SHA256:-}}" | tr -d ': ' | tr 'A-F' 'a-f')
gradle=${VERIFY_APK_GRADLE:-$(dirname "$0")/../../app/build.gradle.kts}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
BT=$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)
err=0
certs=$("$BT/apksigner" verify --print-certs "$apk" 2>&1) || { echo "verify_apk: apksigner verification failed"; err=1; }
if [ -n "$expected_sha" ]; then
    echo "$certs" | grep -q "certificate SHA-256 digest: $expected_sha\$" ||
        { echo "verify_apk: the signer's certificate SHA-256 is not $expected_sha"; err=1; }
fi
manifest=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$apk" 2>&1) || { echo "verify_apk: cannot read the manifest"; err=1; }
echo "$manifest" | grep -qE 'android:debuggable\([^)]*\)=(true|-1)' && { echo "verify_apk: the APK is debuggable"; err=1; }
echo "$manifest" | grep -qE 'android:testOnly\([^)]*\)=(true|-1)' && { echo "verify_apk: the APK is testOnly"; err=1; }
echo "$manifest" | grep -q 'package="io.github.de1987.seion"' || { echo "verify_apk: package is not io.github.de1987.seion"; err=1; }
want_code=$(sed -n 's/^ *versionCode = \([0-9][0-9]*\).*/\1/p' "$gradle" 2>/dev/null | head -1)
want_name=$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' "$gradle" 2>/dev/null | head -1)
badging=$("$BT/aapt2" dump badging "$apk" 2>/dev/null | head -1)
got_code=$(echo "$badging" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")
got_name=$(echo "$badging" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")
if [ -z "$want_code" ] || [ -z "$want_name" ]; then
    echo "verify_apk: cannot read versionCode/versionName from $gradle"; err=1
elif [ "$got_code" != "$want_code" ] || [ "$got_name" != "$want_name" ]; then
    echo "verify_apk: the APK is versionCode $got_code / $got_name, $gradle says $want_code / $want_name (stale build?)"
    err=1
fi
signer=$(echo "$certs" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
[ $err -eq 0 ] && echo "verify_apk: OK ($apk: io.github.de1987.seion $got_name ($got_code), signer SHA-256 $signer)"
exit $err
