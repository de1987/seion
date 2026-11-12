# Build shortcuts. Needs JDK 17 and the Android SDK (JAVA_HOME and ANDROID_HOME, or local.properties).
GRADLE := ./gradlew
ANDROID_HOME ?= $(HOME)/Android/Sdk
ADB := $(ANDROID_HOME)/platform-tools/adb $(if $(SERIAL),-s $(SERIAL))

.PHONY: check test format release verify-release release-test install install-release stop

## Everything that should pass before a change goes in: build, unit tests, ktlint, Android lint.
check:
	$(GRADLE) assembleDebug testDebugUnitTest ktlintCheck lintDebug

test:
	$(GRADLE) testDebugUnitTest

format:
	$(GRADLE) ktlintFormat

## Signed release APK, then verified. Signing is set up in keystore.properties or SEION_RELEASE_* (see DEVELOPMENT.md).
release:
	$(GRADLE) assembleRelease
	$(MAKE) verify-release

## Not debuggable or testOnly, package io.github.de1987.seion, versions match app/build.gradle.kts. Set SEION_RELEASE_CERT_SHA256
## to also pin the signing certificate.
verify-release:
	tools/release/verify_apk.sh app/build/outputs/apk/release/app-release.apk

## The verifier's own test. The no-key case removes the built release APK, so point RELEASE_APK at a copy.
RELEASE_APK ?= app/build/outputs/apk/release/app-release.apk
release-test:
	tools/release/verify_apk_test.sh $(RELEASE_APK)

## Installs on the only connected device, or the one named by SERIAL=<adb serial>.
install:
	$(ADB) install -r app/build/outputs/apk/debug/app-debug.apk

install-release:
	$(ADB) install -r app/build/outputs/apk/release/app-release.apk

stop:
	$(GRADLE) --stop
