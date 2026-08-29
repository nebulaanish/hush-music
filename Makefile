APK := app/build/outputs/apk/release/app-release.apk

build:
	./gradlew assembleRelease
	adb install -r $(APK)

.PHONY: build
