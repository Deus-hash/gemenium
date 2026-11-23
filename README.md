# 🛡️ Gemini DNS & Proxy Switcher (Android)

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="128" height="128" alt="Gemini DNS Logo" />
</p>

<p align="center">
  <b>Легковесный Android VPN-клиент с поддержкой DNS-свитчера, сплит-туннелирования и персональных SOCKS5/HTTP прокси (с авторизацией) для свободного доступа к Gemini и AI-сервисам.</b>
</p>

<p align="center">
  <a href="https://github.com/Deus-hash/gemenium/releases/latest"><img src="https://img.shields.io/github/v/release/Deus-hash/gemenium?color=blue&label=Скачать%20APK" alt="Release" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-brightgreen" alt="Android Version" />
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Architecture-MVVM%20%2B%20Coroutines-orange" alt="Architecture" />
  <img src="https://img.shields.io/badge/Proxy-SOCKS5%20%2F%20HTTP%20CONNECT-blue" alt="Proxy" />
</p>

---

## 🚀 Установка

Скачайте готовый APK-файл на смартфон и установите в один клик:

📥 **[Скачать последнюю версию GeminiDNS.apk](https://github.com/Deus-hash/gemenium/releases/latest/download/GeminiDNS.apk)**

---

## ✨ Ключевые возможности

### 1. 🛡️ Режим Прокси (Обход для Gemini)
* **Простой формат ввода**:
  Введите прокси в виде `логин:пароль@адрес:порт` (или `адрес:порт`, `socks5://...`, `http://...`).
* **Автоопределение протокола**:
  Приложение автоматически тестирует удаленный сервер и определяет, работает ли он по протоколу **SOCKS5** или **HTTP CONNECT**.
* **Встроенный мост (Local Proxy Bridge)**:
  Шифрованный туннель пересылает запросы к Gemini и браузеру через ваш персональный сервер с авторизацией по логину и паролю.
* **Тест подключения и замер пинга**:
  Кнопка «Проверить» замеряет скорость отклика прокси в миллисекундах до включения VPN.

### 2. 🌐 Режим DNS-свитчера
* **Сплит-туннелирование (DNS-Only Tunnel)**:
  Через виртуальный туннель перенаправляются **исключительно DNS-запросы на 53 порт**. Весь основной трафик передается напрямую через вашу сеть без потери скорости (`allowBypass()`).
* **Поддержка любых серверов (IP и домены)**:
  Позволяет использовать как стандартные числовые IP-адреса, так и буквенные доменные имена.
* **Smart Traffic Guard (Интеллектуальный Failover)**:
  Автоматическое переключение на следующий сервер из списка при потере связи. Если пользователь активно пользуется интернетом, ложные переключения блокируются.
* **Локальный кэш (0 ms latency)**:
  Повторные запросы моментально отдаются из памяти устройства.
* **Drag-and-Drop сортировка**:
  Пользователь настраивает приоритет автопереключения простым перетаскиванием строк.

### 3. 📱 Виджет для рабочего стола
* Включение и выключение защиты в 1 тап прямо с домашнего экрана устройства.

---

## 🛠️ Стек технологий

* **Язык**: Kotlin
* **Минимальная версия Android**: Android 8.0 (API 26+)
* **Сетевой уровень**: Android `VpnService`, сырые UDP-сокеты с `protect()`, локальный мост `LocalProxyBridge` с авторизацией RFC 1929 (SOCKS5) и Basic (HTTP).
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
