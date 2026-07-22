# TypstWriter

Нативное Android-приложение для редактирования и компиляции Typst-документов. Оптимизировано для E-ink устройств (Boox, reMarkable).

## Что это

TypstWriter — полностью автономный редактор Typst-файлов для Android. Приложение компилирует Typst-код в PDF прямо на устройстве, без подключения к серверу. Ядро компилятора написано на Rust и подключается к Java-интерфейсу через JNI.

## Возможности

- **Редактирование** .typ файлов в текстовом редакторе с моноширинным шрифтом
- **Экспорт в PDF / HTML** — полная компиляция Typst на устройстве
- **Именование файлов** — редактируемое поле с именем файла в верхней панели редактора, суффикс `.typ` добавляется автоматически
- **Save / Save As** — сохранение с именем из поля ввода; при изменении имени Save автоматически переименовывает файл
- **Автонумерация** — при конфликте имён к файлу добавляется ` (1)`, ` (2)` и т.д.
- **Список недавних** — до 10 последних файлов с отображением полного пути (`Documents/myfile.typ`)
- **Персистентный доступ** — однажды открытые файлы остаются доступными после перезапуска приложения
- **Нативная интеграция Typst** — использует библиотеку typst 0.15 без обёрток и CLI

## Архитектура

```
typst-core/          — Rust: ядро компилятора typst (cdylib)
android/             — Java: Android UI и JNI-мост
```

- **Rust ядро** — компиляция Typst → PDF через `typst`, `typst-pdf`, `typst-layout`
- **JNI** — прямой вызов нативных функций из Java
- **Встроенные шрифты** — Libertinus Serif, New Computer Modern, DejaVu Sans Mono (через `typst-assets`)

## Требования

- JDK 21
- Android SDK с NDK 26.1.10909125
- Rust + `cargo-ndk`
- Gradle 8.5

## Сборка

### 1. Собрать Rust ядро

```bash
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/26.1.10909125

cd typst-core
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs/ build --release
```

### 2. Собрать APK

```bash
cd android
gradle assembleRelease
```

APK будет в `android/app/build/outputs/apk/release/app-release.apk`.

### Быстрая сборка (одна команда)

```bash
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/26.1.10909125
export JAVA_HOME=/opt/jdk-21.0.2
export PATH="$JAVA_HOME/bin:$PATH"

cd typst-core && cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs/ build --release && \
cd ../android && gradle assembleRelease
```

## Использование

1. Нажмите **New file** или **Open file** на стартовом экране
2. Введите Typst-код в редактор
3. При желании измените имя файла в верхней панели (по умолчанию `Untitled`)
4. Нажмите **Save** (Ctrl+S) — для нового файла откроется диалог выбора папки
5. Нажмите **Export PDF** или **Export HTML** для компиляции
6. Откройте PDF в любом просмотрщике

### Пример Typst-кода

```typst
#set page(width: 10cm, height: auto)
#set text(font: "Libertinus Serif", size: 12pt)

= Заголовок

Это тестовый документ, скомпилированный на Android-устройстве.

== Подзаголовок

- Пункт списка
- Ещё пункт
```

## Оптимизации для E-ink

- Чёрно-белая цветовая схема (без градиентов)
- Минимум анимаций
- Ручной триггер компиляции (без автосборки)
- Оптимизированный размер бинарника (LTO, strip)
