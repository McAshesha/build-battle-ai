# Renderer Audit — Design

**Date:** 2026-05-20
**Scope:** `src/main/java/ru/ashesha/buildBattleAI/render/**` + `util/RendererUtils.java`
**Hard constraint:** pixel-equivalence preserving — итоговое PNG не должно меняться бит-в-бит.

## Контекст

Аудит CPU voxel рендера BuildBattleAI на предмет багов, конкурентности, перформанса и архитектурного долга. Источники находок: два независимых аудита (Claude Explore + codex), cross-verification обоих, плюс личная верификация ключевых утверждений в коде. Часть находок отброшена после проверки как false positives.

## Verification strategy

Прежде чем что-либо менять, фиксируем **baseline snapshot suite** — 5 детерминированных сцен рендерятся, SHA-256 PNG-байтов сохраняется в `src/test/resources/render/baselines/`. Любая правка должна оставлять все 5 хешей неизменными. Snapshot suite сам по себе — артефакт ценности (защищает от регрессий в будущем).

## Scope

### In scope (этапы 1–3)

**F1 — Удалить мёртвый код в `BlockShape`** (LOW impact, no risk)
`BlockShape.java:456-466` — методы `isPaneBlock`, `isFenceBlock`, `isWallBlock` нигде не вызываются. Логика мигрировала на `BlockPalette.BLOCK_FLAGS` bitmask. Удалить.

**F2 — try/catch `RejectedExecutionException` в `RenderService.render`** (MED impact, узкое окно)
`RenderService.java:117-120` — между `r = renderer` (volatile read) и `r.render(...)` (которое вызывает `pool.invoke`) другой поток может вызвать `shutdown()`, который вызовет `pool.shutdown()`. Pool откажет в новых задачах через `RejectedExecutionException`. Сейчас исключение пробросится наверх как RuntimeException без диагностики. Решение: ловить и перебрасывать как `IllegalStateException("RenderService is being shut down")` — симметрично текущему null-check.

**F3 — Buffer-reuse overload `render(..., byte[] outBuf)`** (MED impact)
`CpuRenderer.java` аллоцирует свежий `byte[150528]` на каждый `render()` вызов. На частых рендерах (ML inference loop) это GC pressure. Решение:
- Добавить overload `CpuRenderer.render(SceneData, double..., float, float, byte[] out)` — caller передаёт буфер; пустой длины валидация == 150528, иначе IAE.
- Соответствующий overload в `RenderService`.
- Старый API без буфера сохраняется (создаёт fresh buffer внутри).
- Не breaking change.

**F4 — Lazy materialization `blockStates[]` в `FlatScene.fromSnapshot`** (MED impact)
`FlatScene.java:98-132` — жадно вызывает `snapshot.getBlockState(...)` для **каждого** вокселя (~1M строк allocations на сцену 128×64×128). Большинство этих lookups никогда не запрашиваются рендером. Решение:
- Не заполнять `blockStates[]` сразу.
- В `FlatScene.getBlockState(wx,wy,wz)` — если `blockStates[index] == null` И есть оригинальный snapshot reference → запросить лениво. Но это нужно держать snapshot ссылку → меняет invariant "FlatScene self-contained".
- Альтернатива (проще, безопаснее): полностью пропустить `blockStates[]`-материализацию в `fromSnapshot`, оставить null array. `FlatScene.getBlockState()` тогда возвращает null для всех координат. Каллеры стейтфул-форм получат null → откатятся к default shapes. **Но это меняет пиксели** — F4 НЕ pixel-safe в таком виде.
- Правильный путь: материализовать blockStates лениво через **хранение snapshot reference внутри FlatScene** (как finallowed внутренний lazy provider) — снимок thread-safe, ленивый запрос корректен. Если snapshot null (DIRECT format) — массив остаётся как был.
- Сохраняем pixel-equivalence: same logical data, просто задержанная материализация.

**F5 — Устранить string concat `material.ordinal() + "|" + blockState`** (LOW-MED impact)
`BlockShape.java:406` в `getStatefulShape` — string concat в hot path для stateful блоков. Решение: nested `ConcurrentHashMap<XMaterial, ConcurrentHashMap<String, double[][]>>` или массив `ConcurrentHashMap<String, double[][]>[XMaterial.values().length]` — индекс по `ordinal`, value по blockState. Устраняет concat. Eviction политика та же: при превышении лимита заменить outer cache (CAS-guarded). Pixel-equivalence: тривиально preserved.

**F9 — Diagnostic FlatScene constructor message** (LOW impact)
`FlatScene.java:73-75` — текущее сообщение `"data length X != expected Y*Z*W"` — не показывает actual vs expected. Дополнить: показать `actual` и `expected` явно как числа + factors. Чистая обвязка для debugging.

**F10 — Устранить двойной `scene.getBlockState(x,y,z)`** (LOW impact, найдено в codex cross-check)
В `BlockShape.getStatefulShape:398` уже запрашивается `scene.getBlockState(x,y,z)`. Затем `BlockRenderState.of(scene, x, y, z):108` запрашивает его **повторно**. Решение: создать `BlockRenderState.parse(String)` публичным (или package-private) и вызывать его напрямую с уже полученной строкой из `getStatefulShape`. Микро-win + чище.

### Tests (новые)

**T1 — `RendererPixelEquivalenceTest` (Этап 0, baseline)**
5 детерминированных сцен, для каждой:
- Build FlatScene (фиксированный seed материалов)
- Фиксированная камера (camX/Y/Z, yaw, pitch)
- Render → `byte[]`
- SHA-256 хеш над `byte[]` сравнивается с baseline-файлом

Сцены: opaque cubes (различные материалы), sub-block (slab/stairs/trapdoor), connectivity (fence/wall/pane), translucent (glass/water), emissive.

Хеши baseline хранятся в `src/test/resources/render/baselines/*.sha256`. Файлы коммитятся ДО любых правок (Этап 0).

**T2 — `RendererConcurrentStressTest`** (Этап 2)
4 потока × 100 рендеров. Каждый рендер с уникальным камерным углом, чтобы проверить независимость буферов и thread-safety пула. Никаких exceptions, никаких corrupted outputs (валидация против baseline-хешей при детерминированной сцене).

**T3 — `RenderServiceShutdownTest`** (Этап 2)
Один поток рендерит в цикле в отдельном thread'е. Другой поток вызывает `RenderService.shutdown()`. Ожидание: либо рендер завершается до shutdown (returns valid byte[]), либо ловит `IllegalStateException` (из F2). Никаких `RejectedExecutionException` или corrupted state.

### Out of scope (опровергнуто или нерациональное)

**❌ `heightMap` "dead code"** (codex first pass) — heightMap **используется** как early-skip в DDA loop (`CpuRenderer.java:470-471`). Удаление сломало бы реальную оптимизацию empty-space skipping. NOT touching.

**❌ `legacy` field требует volatile** (Claude E) — Javadoc эксплицитно говорит `enable()` и `capture()` оба только с main thread (`RenderService.java:78`). Async-путь (`render`) поле не читает. False positive.

**❌ CAS race в `BlockRenderState`/`BlockShape` cache**
Несмотря на initial Claude finding и codex cross-check vote "REAL [LOW]", личная верификация показывает паттерн **корректен**. Код на `BlockRenderState.java:111-122`:
```java
Map<String, BlockRenderState> cache = CACHE_REF.get();  // (1) read snapshot
BlockRenderState cached = cache.get(blockState);
if (cached != null) return cached;
if (cache.size() > MAX_CACHE_SIZE)
    CACHE_REF.compareAndSet(cache, new ConcurrentHashMap<...>());
BlockRenderState parsed = parse(blockState);
CACHE_REF.get().put(blockState, parsed);                // (2) re-read — put в актуальный
```
Ключ — строка (2): `CACHE_REF.get().put(...)`. Это **re-read** актуальной ссылки на map. Даже если другой поток успешно сделал CAS между (1) и (2), `(2)` всё равно идёт в свежий (актуальный) map. Никаких "orphaned" записей нет. Codex в cross-check, видимо, смотрел на устаревшее предположение о `cache.put(...)` (с использованием локальной переменной `cache`), но в реальном коде такого нет. Same паттерн в `BlockShape.java:420`. **Не правим.**

**❌ `RenderService.shutdown()` без try/catch** (Claude A) — `CpuRenderer.shutdown()` уже ловит `InterruptedException`, восстанавливает interrupt flag, эскалирует к `shutdownNow()` (`CpuRenderer.java:936-945`). Worker threads — daemon. False positive.

**❌ String concat в `BlockShape.getConnectivityShape`** (Claude I) — этот hot path **уже** мигрирован на `AtomicReferenceArray<double[][]>(3*16)` без string ключа (`BlockShape.java:35`). Reduktion реального string concat — в F5 (другой метод, `getStatefulShape`).

**🟡 `computeAO` early-exit для изолированных блоков** (codex first pass) — реальный early-exit без проверки соседей сломал бы pixel-equivalence; `AO_TABLE[0]=1.0` уже implicit fast-path. Без дополнительного кеша "is block isolated" чистого выигрыша нет, а кеш — это новая структура и lifecycle. Skip.

## Implementation order

**Этап 0 — Baseline защита (БЛОКИРУЮЩИЙ — никаких правок до завершения):**
- T1: `RendererPixelEquivalenceTest` + baseline хеши скоммичены в репо.

**Этап 1 — Низкорисковые правки:**
- F1: удалить `isPaneBlock/isFenceBlock/isWallBlock`.
- F9: лучшая диагностика в `FlatScene` constructor.
- F2: try/catch в `RenderService.render`.

После Этапа 1 — прогон T1. Все 5 хешей должны совпасть.

**Этап 2 — Тесты:**
- T2: concurrent stress test.
- T3: shutdown-during-render test.

После Этапа 2 — прогон всех тестов модуля рендера, включая T1, T2, T3.

**Этап 3 — Перформанс правки (по одной + прогон T1 после каждой):**
- F5: nested map в `getStatefulShape` (устраняет string concat).
- F10: устранить двойной getBlockState lookup.
- F3: overload `render(..., byte[] outBuf)` с buffer reuse.
- F4: lazy materialization `blockStates[]` в `FlatScene.fromSnapshot`.

После каждой правки в Этапе 3 — обязательный прогон T1; любое расхождение хеша = откат правки и анализ.

## Risk assessment

| Правка | Risk | Mitigation |
|---|---|---|
| F1 | None | unused code removal |
| F2 | Low | catch только конкретный exception type |
| F3 | Low | overload, не меняет старый API |
| F4 | Medium | lazy + snapshot reference требует careful invariants |
| F5 | Low | nested map, eviction policy preserved |
| F9 | None | error message only |
| F10 | Low | требует синхронизации с `BlockRenderState` API |

## Success criteria

1. Все существующие тесты модуля рендера зелёные.
2. T1 baseline хеши совпадают до/после каждой правки.
3. T2 concurrent stress без exceptions / corruption.
4. T3 shutdown без `RejectedExecutionException`.
5. Снижение allocation pressure (F3, F4) — opportunistic, не критерий пройти/нет.

## Non-goals

- Изменения алгоритма рендера, шейдинга, цветов в палитре, геометрии shape-ов.
- Изменения формата выходного PNG.
- Расширение публичного API кроме F3 overload.
- Touching `BlockPalette` (массивный файл, риск нарушения шейдинга высок, и аудит не нашёл там реальных проблем).
