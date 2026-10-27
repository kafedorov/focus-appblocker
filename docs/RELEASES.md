# Автоматические APK-релизы FocusGuard

Сценарий: `.github/workflows/release.yml`, название в Actions — **Build and release APK**.

## Однократное включение

### 1. Добавь существующий ключ подписи в GitHub Secrets

В PowerShell:

```powershell
cd 'C:\Users\gorba\Desktop\projects\FocusGuard'
.\scripts\prepare-signing-secret.ps1 -CopyToClipboard
```

Команда проверяет сертификат существующего ключа и копирует его Base64-представление в буфер обмена. Содержимое не выводится и не записывается в файл. Без `-CopyToClipboard` скрипт только проверяет сертификат.

Открой настройки своего репозитория:

https://github.com/kafedorov/focus-appblocker/settings/secrets/actions

Выбери **New repository secret**:

- **Name:** `FOCUSGUARD_KEYSTORE_BASE64`
- **Secret:** вставь содержимое буфера обмена.
- Нажми **Add secret**.

После сохранения можно очистить буфер:

```powershell
Set-Clipboard -Value ''
```

Это значение предназначено для поля Secret. Ключ не добавляется в Git или Assets релиза. Используется прежний debug-ключ приложения; имя alias и стандартные пароли debug-сборки уже учитываются сценарием.

### 2. Отправь сценарий на GitHub

Из папки FocusGuard:

```powershell
git add .github/workflows/release.yml scripts/ci_release.py scripts/test_ci_release.py scripts/prepare-signing-secret.ps1 docs/RELEASES.md README.md .gitignore
git commit -m "Configure automated APK releases"
git push origin main
```

### 3. Проверь сборку без публикации

Открой:

https://github.com/kafedorov/focus-appblocker/actions

Выбери **Build and release APK → Run workflow**, ветку **main**, затем **Run workflow**. Ручной запуск собирает и проверяет APK и сохраняет его в **Artifacts** на странице запуска на 7 дней. Он не создаёт Release. Так можно проверить текущую 0.1.5, не изменяя первый ручной релиз.

Если появится ошибка отсутствующего секрета, проверь имя из шага 1. Если ошибка связана с SDK, сетью или Gradle, открой упавший шаг Actions — эти сообщения не означают, что файл уже опубликован.

## Как выпускать следующие версии

1. Обнови `versionName` и увеличь `versionCode` в `app/build.gradle`. Например, для следующей после 0.1.5 версии: `versionName '0.1.6'`, `versionCode 7`.
2. Обнови описание изменений в CHANGELOG.md, сохрани код коммитом и отправь его на GitHub.
3. Создай тег, точно совпадающий с версией:

```powershell
git push origin main
git tag v0.1.6
git push origin v0.1.6
```

Команды выше применяются после подготовки версии 0.1.6, а не сразу после установки сценария. Само добавление CI не меняет текущую версию приложения.

После отправки тега GitHub:

- проверяет совпадение тега и версии исходников;
- устанавливает JDK 17, SDK 36 и Build Tools 35.0.0;
- восстанавливает прежний ключ и проверяет его сертификат;
- выполняет `assembleDebug`, `lintDebug`, `coreCheck`;
- проверяет подпись, package `ru.focusguard`, versionCode/versionName и встроенную базу в готовом APK;
- сохраняет APK и контрольную сумму;
- создаёт Release с названием «Фокус 0.1.6» и файлами `FocusGuard-0.1.6-debug.apk`, `FocusGuard-0.1.6-debug.apk.sha256`.

Если проверки не пройдены, Release не публикуется. Если Release с таким тегом уже существует, сценарий останавливается и не изменяет его файлы. Нельзя выпускать новые изменения под прежним опубликованным номером: для новой версии нужны новый versionCode и новый тег.

## Подпись и обновления

Ожидаемый SHA-256 сертификата:

```text
ce10316317e5a582e6a80e8af2d08e82dffb20a41ed44de6b1853cdc575734c4
```

Автоматизация сохраняет нынешний вариант debug и прежнюю подпись. Такой APK предназначен для текущего личного использования и установки поверх уже выданных сборок. Случайный новый ключ остановит сценарий, чтобы не выпустить несовместимое обновление.

Ключ записывается только на временный GitHub runner, затем удаляется; в кэш Gradle и загружаемые файлы он не попадает. Права записи в Releases есть только у отдельного задания публикации. Сценарий не запускается на pull request.

## Что проверено локально

Проверка YAML через actionlint, тесты помощника релиза, сборка приложения, lint и coreCheck выполняются перед передачей изменений. Сам запуск на GitHub требует, чтобы ты добавил Secret и отправил сценарий в репозиторий. Локальные проверки не означают успешного запуска Actions или испытания APK на Samsung.

## Документация

- GitHub Secrets: https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets
- GitHub Releases: https://cli.github.com/manual/gh_release_create
- Подпись Android: https://developer.android.com/studio/publish/app-signing
