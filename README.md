# 🛡️ Gemini DNS Switcher (Android)

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="128" height="128" alt="Gemini DNS Logo" />
</p>

<p align="center">
  <b>Легковесный Android DNS-переключатель на базе VpnService со сплит-маршрутизацией, локальным кэшем и защитой от региональных блокировок (Gemini, Xbox, Comss).</b>
</p>

<p align="center">
  <a href="https://github.com/Deus-hash/gemenium/releases/latest"><img src="https://img.shields.io/github/v/release/Deus-hash/gemenium?color=blue&label=Скачать%20APK" alt="Release" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-brightgreen" alt="Android Version" />
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Architecture-MVVM%20%2B%20Coroutines-orange" alt="Architecture" />
  <img src="https://img.shields.io/badge/Traffic-DNS%20Only%20(Split%20Tunnel)-success" alt="Split Tunnel" />
</p>

---

## 🚀 Быстрый старт (Установка)

Скачайте готовый APK-файл на смартфон и установите в один клик:

📥 **[Скачать последнюю версию GeminiDNS.apk](https://github.com/Deus-hash/gemenium/releases/latest/download/GeminiDNS.apk)**

---

## ✨ Ключевые возможности

* ⚡ **Сплит-туннелирование (DNS-Only Tunnel)**:
  Через виртуальный VPN направляются **только DNS-запросы на 53 порт**. Весь остальной трафик (браузеры, YouTube, Telegram, игры) идет напрямую через вашу обычную сеть без падения скорости (`allowBypass()`).
* 🌐 **Поддержка буквенных DNS-доменов**:
  Возможность вводить не только прямые IP (`1.1.1.1`), но и доменные имена (`xbox-dns.ru`, `dns.comss.one`). Автоматический резолвинг в фоне.
* 🛡️ **Обход блокировок Gemini / Google AI**:
  Позволяет использовать Smart DNS (Нидерланды / Германия), благодаря чему Gemini не выдает ошибку «Сервис недоступен в вашей стране».
* 🔄 **Smart Traffic Guard (Интеллектуальный Failover)**:
  Сервис автоматически переключается на следующий DNS из списка, если текущий сервер перестал отвечать. Если же пользователь прямо сейчас активно использует интернет — ложные переключения блокируются.
* 🏎️ **Сверхбыстрый локальный LRU-кэш (0 ms latency)**:
  Повторные DNS-запросы к частым адресам отдаются моментально из оперативной памяти смартфона.
* 📊 **Мониторинг пинга**:
  Отображение задержки до активного DNS в миллисекундах в реальном времени.
* 🎛️ **Удобное управление списком**:
  * Ручное переключение в один клик без разрыва туннеля.
  * Drag-and-Drop сортировка серверов (приоритет автопереключения).
  * Добавление, редактирование и удаление серверов.
* 📱 **Интерактивный виджет для рабочего стола**:
  Включение и выключение DNS прямо с домашнего экрана телефона.

---

## 📋 Предустановленные серверы

| Название | Адрес | Назначение |
| :--- | :--- | :--- |
| **Xbox DNS RU** | `xbox-dns.ru` | Доступ к сервисам Xbox, Microsoft и обход региональных блокировок AI |
| **Comss.one DNS** | `dns.comss.one` | Smart DNS для стабильной работы Gemini, ChatGPT и зарубежных ресурсов |
| **Cloudflare DNS** | `1.1.1.1` | Быстрый общедоступный защищенный DNS |
| **Google Public DNS** | `8.8.8.8` | Надежный мировой DNS-сервер |

---

## 🛠️ Стек технологий

* **Язык**: Kotlin
* **Минимальная версия Android**: Android 8.0 (API 26+)
* **Сетевой уровень**: Android `VpnService`, сырые UDP-сокеты с `protect()`, самописный `DnsPacketRelay`
* **Асинхронность**: Coroutines & Kotlin StateFlow
* **UI**: Material Components, ViewBinding, ItemTouchHelper (Drag & Drop)
* **CI/CD**: GitHub Actions (автосборка APK при пуше)

---

## 💻 Сборка из исходников

```bash
git clone https://github.com/Deus-hash/gemenium.git
cd gemenium
./gradlew assembleDebug
```
Собранный файл появится в `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 Лицензия

Распространяется под лицензией [MIT](LICENSE).
