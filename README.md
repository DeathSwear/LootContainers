# LootContainers

**LootContainers** — это плагин для Paper/Bukkit (1.21+), который превращает выпадающий лут с мобов и боссов в физические интерактивные 3D-контейнеры добычи (стилизованные блоки голов с текстурами сундуков и парящими голограммами). Плагин имеет глубокую интеграцию с **MythicMobs** и решает проблему загромождения мира разбросанными на полу предметами.

---

## 🌟 Ключевые особенности

### 🎁 Физические контейнеры вместо дропа на полу
- При гибели обычного моба или MythicMob выпавший лут автоматически упаковывается в физический блок (голова с кастомным скином сундука).
- Контейнер автоматически ориентируется и поворачивается лицом к победителю (`face-killer: true`).
- Алгоритм умного поиска позиции (`findFreeBlock`) находит ближайший свободный блок на твердой поверхности, избегая застревания в стенах или лаве.

### 🛡 Временная защита добычи убийцы (`killer-only-seconds`)
- В течение заданного времени (например, 10 секунд) открыть сундук может **только игрок, убивший моба**.
- Другие игроки при попытке открыть получают уведомление о защите.
- По истечении таймера сундук становится общедоступным для всех игроков.

### 🧲 Умное объединение дропа (`merge-radius`)
- При массовом фарме мобов плагин ищет существующие контейнеры того же профиля в радиусе `merge-radius` (например, 5 блоков) и складывает новый лут в них.
- Это полностью исключает спам десятками голов и голограмм на одной точке, значительно снижая нагрузку на клиент и сервер.

### 🏷 Гибкая система профилей (`profiles`)
- Возможность настраивать разные типы сундуков (обычный, элитный, сундук босса и т.д.).
- Для каждого профиля задаются:
  - Уникальная текстура (Base64 скин головы);
  - Название инвентаря и описание;
  - Текст парящей голограммы над сундуком.
- Привязка профиля к мобам осуществляется несколькими способами:
  1. Переменная MythicMobs: `LootContainer: boss`
  2. Scoreboard-тег моба: `lootcontainer:boss` или `lc:elite`
  3. По внутреннему имени типа моба в MythicMobs
  4. Профиль по умолчанию (`container.default-profile: normal`)

### ⚡ Интеграция с MythicMobs Skills
- Плагин умеет вызывать любые скиллы MythicMobs при ключевых событиях контейнера:
  - `spawn` — появление сундука (партиклы, звуки приземления);
  - `open` — открытие игроком (`LootChestOpen`, `BossChestOpen`);
  - `expire` — исчезновение по истечении времени (`lifetime-seconds`);
  - `merge` — пополнение сундука новым дропом.

### 🔒 Защита от грифа и автоматическая очистка
- Игроки не могут случайно или намеренно сломать активный сундук (открытие строго по ПКМ).
- Взрывы криперов, динамита и взрывных снарядов (`EntityExplodeEvent`, `BlockExplodeEvent`) игнорируют лут-контейнеры.
- При остановке, перезагрузке сервера или вызове `/lc clear` все блоки голов и голограммы корректно удаляются из мира без фантомных сущностей.

---

## 🛠 Команды и права

| Команда | Описание | Право |
|---|---|---|
| `/lootcontainers reload` | Перезагрузить конфигурацию плагина | `lootcontainers.admin` (OP) |
| `/lootcontainers clear` | Удалить все активные лут-контейнеры и голограммы в мирах | `lootcontainers.admin` (OP) |

*Примечание:* Администраторы в режиме Creative могут сломать контейнер рукой для мгновенного удаления.

---

## ⚙️ Пример конфигурации (`config.yml`)

```yaml
access:
  killer-only-seconds: 10
  public-without-killer: true

container:
  size: 27
  lifetime-seconds: 300
  default-profile: normal
  face-killer: true
  merge-radius: 5.0

profiles:
  normal:
    name: "&6Сундук добычи"
    lore: ["&7Нажмите ПКМ, чтобы открыть"]
    hologram: ["&7Нажмите ПКМ, чтобы открыть"]
    texture: "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2MxYjJmNTkyY2ZjOGQzNzJkY2Y1ZmQ0NGVlZDY5ZGRkYzY0NjAxZDc4NDZkNzI2MTlmNzA1MTFkODA0M2E4OSJ9fX0="
  elite:
    name: "&eЭлитный сундук"
    lore: ["&7Элитная добыча"]
    hologram: ["&eЭлитный сундук", "&7Нажмите ПКМ, чтобы открыть"]
    texture: "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU0ZWJlZmVmYThjYjNjNTg2MGFjODQxMjY1OWUxMjNiYTE1NGQ0YjcwOTZjM2IxMjNjMGQxZmNhNjNjOTc5OSJ9fX0="
  boss:
    name: "&cСундук босса"
    lore: ["&7Награда за победу над боссом"]
    hologram: ["&cСундук босса", "&7Нажмите ПКМ, чтобы открыть"]
    texture: "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDQ2NzUxNThjMDc2N2VlNTA4YzUyZDUyNDI2Y2VmM2EyYzJiMjliN2U0ODdjOTI5NTNhMzgyM2Y1NjFkMDZhZiJ9fX0="

events:
  spawn: []
  open:
    - LootChestOpen
  expire:
    - LootChestExpire
  merge: []
```

---

## 🔨 Сборка проекта

Проект собирается с помощью Gradle и JDK 21:

```bash
# Windows
.\gradlew.bat build

# Linux / macOS
./gradlew build
```

Собранный плагин сохраняется в `build/LootContainers.jar`.
