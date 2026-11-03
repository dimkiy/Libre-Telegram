# Libre

Libre — неофициальный клиент Telegram для Android. Это форк [официального Telegram for Android](https://github.com/DrKLO/Telegram) (сейчас на основе версии 13.1.1) со своими функциями поверх него.

Libre не связан с Telegram FZ-LLC. Он работает через обычный Telegram API, аккаунт и чаты остаются в Telegram.

## Что добавлено

- **Музыка:** большой плеер, плейлисты с обложками, эквалайзер, тексты песен, таймер сна, «Слушать вместе», музыкальный статус.
- **Профиль:** карточка Libre, достижения, «в Telegram с» / «в Libre с», баннер, стена, статусы как в Discord.
- **Кланы, витрина подарков и передача Libre-NFT** через сервер Libre Cloud.
- **Приватность:** антиудаление, скрытие онлайна, «Стереть следы», замок на архив, удаление геоданных из фото, режим Tor (через Orbot).
- **Уведомления из MAX** приходят в бота Libre в Telegram.
- **Оформление:** свои иконки приложения, живые обои, цветные метки чатов, свой шрифт.

Где в коде лежит каждая функция, описано в [LIBRE_CODE.md](LIBRE_CODE.md).

## Структура

| Папка | Что там |
|---|---|
| `TMessagesProj/` | Основной код приложения (Java). Наши файлы называются `Prizma*.java` |
| `TMessagesProj_App/` | Модуль сборки APK. Вариант для публикации — `armLibre` |
| `server/` | Libre Cloud: сервер на Python (aiohttp + SQLite) и скрипты установки |
| `TMessagesProj/jni/` | Нативный код Telegram и сторонние библиотеки (подмодули git) |

## Сборка

Нужны Android Studio, Android SDK 36 и NDK 27.2.12479018.

1. Склонировать вместе с подмодулями:
   ```bash
   git clone --recursive https://github.com/dimkiy/Libre-Telegram.git
   ```
2. Получить свои `api_id` и `api_hash` на [my.telegram.org](https://my.telegram.org) и дописать в `local.properties` (этот файл не попадает в git):
   ```properties
   TELEGRAM_APP_ID=12345
   TELEGRAM_APP_HASH=0123456789abcdef0123456789abcdef
   ```
   Без них сборка использует тестовые ключи из исходников Telegram.
3. Ключ подписи хранится вне проекта, в `~/libre-key/` (`libre.jks` + `libre.properties`). Без него `armLibre` собирается неподписанным. Ключ из репозитория Telegram (`config/release.keystore`) общедоступный, им нельзя подписывать публикуемые сборки.
4. Собрать:
   ```bash
   ./gradlew :TMessagesProj_App:assembleArmLibre
   ```
   APK появится в `TMessagesProj_App/build/outputs/apk/arm/libre/`.

## Сервер

Как поставить сервер на чистый VPS (Ubuntu), написано в комментариях в начале `server/install.sh`. Токен бота и прочие настройки хранятся на сервере в `/etc/libre.env` и в репозиторий не попадают.

## Лицензия

GNU GPL v2, как и у оригинального Telegram for Android (см. [LICENSE](LICENSE)).
