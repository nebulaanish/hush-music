VERSION := $(shell grep versionName app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
APK_DIR := app/build/outputs/apk/release
APK     := $(APK_DIR)/hush-music_$(VERSION).apk

build:
	./gradlew assembleRelease
	@mv -f $(APK_DIR)/app-release.apk $(APK) 2>/dev/null || true
	adb install -r $(APK)

.PHONY: build
