# DroidForge Mobile

Отдельный Android-клиент: тёмная палитра и cyan-акценты в стиле desktop DroidForge.

## Архитектура

- Android `UsbManager` перечисляет OTG-устройства и показывает штатный диалог разрешения. Root и доступ к `/dev/bus/usb` не используются.
- Android `UsbManager` предоставляет USB-разрешение; ADB Host и Fastboot transport работают через bulk endpoints `UsbDeviceConnection`.
- ADB поддерживает RSA-авторизацию через ключ Android Keystore, shell-команды, sync push/pull, установку APK и sideload ZIP. При первом подключении разблокируйте подключённый телефон и подтвердите ключ DroidForge в его диалоге USB-отладки.
- Fastboot поддерживает getvar, flash/boot образов, erase разделов, unlock/lock загрузчика и reboot. Разрушительные операции требуют подтверждения в приложении.
- Скриншоты и logcat сохраняются в приватный каталог приложения; pull сохраняет файл в app-specific external files. OTG-устройство должно поддерживать режим USB host.
- Для USB-отладки подключаемый Android должен быть в режиме ADB и иметь включённую USB-отладку. Для Fastboot его нужно отдельно перевести в bootloader/Fastboot mode.

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