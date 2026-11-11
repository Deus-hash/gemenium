# 🛡️ Gemini DNS Switcher (Android)

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="128" height="128" alt="Gemini DNS Logo" />
</p>

<p align="center">
  <b>Легковесный и производительный Android DNS-клиент на базе VpnService с поддержкой сплит-туннелирования, локального кэширования и автоматического переключения (Failover).</b>
</p>

<p align="center">
  <a href="https://github.com/Deus-hash/gemenium/releases/latest"><img src="https://img.shields.io/github/v/release/Deus-hash/gemenium?color=blue&label=Скачать%20APK" alt="Release" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-brightgreen" alt="Android Version" />
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Architecture-MVVM%20%2B%20Coroutines-orange" alt="Architecture" />
  <img src="https://img.shields.io/badge/Traffic-DNS%20Only%20(Split%20Tunnel)-success" alt="Split Tunnel" />
</p>

---

## 🚀 Установка

Скачайте готовый APK-файл на смартфон и установите в один клик:

📥 **[Скачать последнюю версию GeminiDNS.apk](https://github.com/Deus-hash/gemenium/releases/latest/download/GeminiDNS.apk)**

---

## ✨ Ключевые возможности

* ⚡ **Сплит-туннелирование (DNS-Only Tunnel)**:
  Через виртуальный туннель перенаправляются **исключительно DNS-запросы на 53 порт**. Весь основной трафик (браузеры, медиа, мессенджеры, игры) передается напрямую через вашу сеть без просадки скорости (`allowBypass()`).
* 🌐 **Поддержка любых серверов (IP и домены)**:
  Позволяет использовать как стандартные числовые IPv4/IPv6-адреса, так и буквенные доменные имена серверов. Приложение автоматически разрешает имя хоста в фоне перед подключением.
* 🛡️ **Smart Traffic Guard (Интеллектуальный Failover)**:
  Автоматическое переключение на следующий сервер из списка при гарантированной потере связи с текущим. Если в данный момент через туннель успешно проходят пользовательские запросы, ложные переключения блокируются.
* 🏎️ **Локальный LRU-кэш ответов (0 ms latency)**:
  Повторные запросы к часто используемым адресам отдаются мгновенно из оперативной памяти смартфона.
* 📊 **Мониторинг задержки (Ping)**:
  Отображение актуального пинга до активного DNS-сервера в миллисекундах.
* 🎛️ **Управление серверами**:
  * Пользователь сам формирует собственный список DNS-серверов.
  * Ручной выбор активного сервера в один клик без разрыва туннеля.
  * Drag-and-Drop сортировка элементов для настройки приоритета автоматического переключения.
* 📱 **Виджет для рабочего стола**:
  Быстрое включение и отключение туннеля прямо с домашнего экрана устройства.

---

## 🛠️ Стек технологий

* **Язык**: Kotlin
* **Минимальная версия Android**: Android 8.0 (API 26+)
* **Сетевой уровень**: Android `VpnService`, сырые UDP-сокеты с `protect()`, высокопроизводительный `DnsPacketRelay`
* **Асинхронность**: Coroutines & Kotlin StateFlow
* **UI**: Material Components, ViewBinding, ItemTouchHelper (Drag & Drop)
* **CI/CD**: GitHub Actions (автоматическая сборка APK при пуше)

---

## 💻 Сборка из исходников

```bash
git clone https://github.com/Deus-hash/gemenium.git
cd gemenium
./gradlew assembleDebug
```
Собранный файл появится в каталоге `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 Лицензия

Распространяется под лицензией [MIT](LICENSE).
