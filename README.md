# DroidForge Mobile

Отдельный Android-клиент: тёмная палитра и cyan-акценты в стиле desktop DroidForge.

## Архитектура

- Android `UsbManager` перечисляет OTG-устройства и показывает штатный диалог разрешения. Root и доступ к `/dev/bus/usb` не используются.
- Rust JNI библиотека классифицирует USB interface descriptors.
- Desktop backend запускает внешние adb/fastboot процессы и на Android host непригоден. Этот каркас пока только обнаруживает USB устройства и запрашивает доступ; он не реализует ADB/Fastboot transport и не выполняет команды. Само USB разрешение не делает телефон ADB host.
- Следующий этап для команд: реализовать ADB host protocol и Fastboot USB protocol поверх bulk endpoints через Android `UsbDeviceConnection` с системным USB разрешением.

## Сборка на Arch Linux

Поставьте Rust Android target и cargo-ndk в пользовательское окружение:

    rustup target add aarch64-linux-android
    cargo install cargo-ndk

Android SDK/NDK установите через Android Studio SDK Manager. Затем задайте пути и соберите Rust библиотеку:

    export ANDROID_HOME="$HOME/Android/Sdk"
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<installed-version>"
    cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --manifest-path Cargo.toml --release

Откройте `mobile` в Android Studio для APK сборки. Нужны Android SDK Platform 35 и Android Gradle Plugin 8.7.3. На компьютере без NDK/target `cargo check --target aarch64-linux-android` завершится ошибкой отсутствующего Android std или NDK linker.

## Ограничения OTG

Нужны поддержка USB host mode у телефона, OTG адаптер и подходящий кабель; некоторым устройствам нужно внешнее питание. USB descriptors отличаются у производителей.