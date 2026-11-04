# Карта кода Libre

В проекте около 14 000 файлов Telegram, а наших из них немного. Все новые классы называются `Prizma*` (так раньше назывался форк). Наши вставки в код Telegram легко найти поиском по проекту (Ctrl+Shift+F) по слову `Prizma`.

Пути ниже даны от `TMessagesProj/src/main/java/org/telegram/`.

## Логика (`messenger/`)

| Файл | Что делает |
|---|---|
| `PrizmaConfig` | Все переключатели Libre (приватность, оформление, звонки, MAX) |
| `PrizmaCloud` | Клиент сервера Libre Cloud: вход через бота, профили, загрузки |
| `PrizmaSocial` | Достижения, «в Telegram с» / «в Libre с», статусы, команда Libre |
| `PrizmaPresence` | Цвет кружка «в сети» по статусу, Libre-подарок у ника, анонимный номер +888 |
| `PrizmaClans` | Кланы: создание, вступление, роли |
| `PrizmaWall` | Стены профилей |
| `PrizmaAntiDelete` | Антиудаление: какие удаления оставить на экране |
| `PrizmaMaxListener` | Уведомления из MAX → бот Libre |
| `PrizmaPlaylist` | Плейлисты |
| `PrizmaEqualizer` | Эквалайзер, бас, объём |
| `PrizmaLyrics` | Тексты песен: теги файла, сервер Libre, lrclib.net |
| `PrizmaClips` | Клипы к трекам: открытый iTunes Search API через сервер Libre, свои клипы, кэш |
| `PrizmaWaveform` | Настоящая волна трека для перемотки |
| `PrizmaInputCalc` | Подсказки в поле ввода: примеры, валюты, единицы, часовые пояса |
| `PrizmaSleepTimer` | Таймер сна |
| `PrizmaMusicStatus` | Трек в «О себе», пока играет музыка |
| `PrizmaNet` | HTTP-запросы через прокси Telegram |
| `PrizmaTor` | Режим Tor через Orbot |
| `PrizmaExif` | Удаление геоданных из отправляемых фото |
| `PrizmaOnline` | «Всегда в сети» |
| `PrizmaFonts` | Свой шрифт |
| `PrizmaSounds` | Свой звук уведомлений |
| `PrizmaNfcService` | Обмен контактом касанием телефонов (NFC) |

## Экраны (`ui/Components/`)

| Файл | Что делает |
|---|---|
| `PrizmaStealthSheet` | Окна выбора из настроек: иконка, номер +888, размеры |
| `PrizmaProfileCard` | Блок Libre в профиле: даты, достижения, статус |
| `PrizmaStatusSheet` | Выбор статуса (не беспокоить, нет на месте…) |
| `PrizmaClanSheet` | Экран клана |
| `PrizmaFullPlayer` | Большой плеер: обложка, текст, очередь, меню |
| `PrizmaMiniPlayer` | Мини-плеер в списке чатов и в чате |
| `PrizmaSeekBars` | Перемотка: настоящая волна, волна от бита, лупа под пальцем |
| `PrizmaPlayerBackdrop` | Фон плеера, кольца и искры под бит |
| `PrizmaBeat` | Определение ударов по данным визуализатора |
| `PrizmaPalette` | Цвета плеера из обложки |
| `PrizmaClipView` | Видео клипа вместо обложки |
| `PrizmaLyricsView` | Текст песни с заливкой строки |
| `PrizmaQueuePanel` | Очередь: поиск, порядок, перетаскивание |
| `PrizmaPlayerSettings` | Настройки плеера |
| `PrizmaPopup`, `PrizmaSwitch`, `PrizmaSpring` | Меню «зажать и потянуть», переключатель, пружины |
| `PrizmaLibrary`, `PrizmaPlaylistSheet`, `PrizmaPublicPlaylistSheet` | Экран «Моя музыка», добавление в плейлист, чужие плейлисты |
| `PrizmaPdfViewer` | Просмотр PDF внутри приложения |
| `PrizmaCalcChip` | Подсказка над полем ввода |
| `PrizmaEqualizerSheet` | Экран эквалайзера |
| `PrizmaLyricsSheet` | Экран текста песни |
| `PrizmaMusicSearchSheet` | Поиск музыки по Telegram |
| `PrizmaTogether`, `PrizmaTogetherBar` | «Слушать вместе» |
| `PrizmaNowPlayingIsland` | Плашка «сейчас играет» |
| `PrizmaGifts`, `PrizmaGiftsSheet`, `PrizmaGiftCard`, `PrizmaGiftTransfer`, `PrizmaCraftPicker` | Витрина подарков, Libre-NFT, передача, крафт |
| `PrizmaBanner` | Баннер профиля |
| `PrizmaAppIcon` | Своя картинка на иконку приложения |
| `PrizmaMaxSetup` | Включение уведомлений из MAX |
| `PrizmaUpdater` | Обновления Libre внутри приложения |
| `PrizmaBackup` | Облачная копия настроек и «что сервер знает обо мне» |
| `PrizmaCloudSetup` | Адрес сервера и бота (для тестов) |
| `PrizmaArchiveLock` | PIN на архив |
| `PrizmaTraces` | «Стереть следы» |
| `PrizmaScreenBlur` | Размытие экрана от посторонних глаз |
| `PrizmaNfcExchange` | Экран обмена контактом |
| `PrizmaWallpaper`, `PrizmaVideoWallpaper`, `PrizmaChatWallpaper` | Обои чатов |
| `PrizmaDialogColors` | Цветные метки чатов |
| `PrizmaGlass`, `PrizmaSlider`, `PrizmaEntrance`, `PrizmaWaveBars`, `PrizmaVisualizerData` | Общие элементы интерфейса и анимации |

Стена профиля открывается отдельным экраном: `ui/PrizmaWallActivity.java`. Настройки Libre: `ui/PrizmaSettingsActivity.java`.

## Правки в коде Telegram

Наши вставки есть, например, в этих файлах (искать по `Prizma`, полный список даёт история git):
`ChatActivity`, `ProfileActivity`, `DialogsActivity`, `LaunchActivity`, `SettingsActivity`, `MainTabsLayout`, `GlassTabView`, `FragmentContextView`, `VideoPlayerSeekBar`, `MessagesController`, `MediaController`, `MediaDataController`, `NotificationsController`, `ChatMessageCell`, `DialogCell`, `StarGiftSheet`, `VoIPHelper`, `LauncherIconController`, `AndroidManifest.xml`, `strings.xml`.

`BuildVars.java` берёт `api_id`/`api_hash` из `local.properties`, а настройки сборки `armLibre` лежат в `TMessagesProj_App/build.gradle`.

## Сервер (`server/`)

| Файл | Что делает |
|---|---|
| `server.py` | Весь сервер Libre Cloud (aiohttp + SQLite) |
| `install.sh` | Первая установка на VPS |
| `update-server.sh` | Обновить код сервера |
| `publish-update.sh` | Выложить новый APK для встроенного обновления |
| `setup-music-channel.sh` | Подключить публичный канал, через который бот раздаёт треки из открытых плейлистов |
| `bridge.py`, `install-bridge.sh` | Двусторонний мост с MAX (пока не используется) |
