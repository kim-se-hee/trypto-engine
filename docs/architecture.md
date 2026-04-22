# Architecture

`trypto-engine`의 스레드 경계, 이벤트 흐름, 외부 경계 계약, 멱등성 계약을 정리한 오케스트레이션 문서.

각 스레드가 소유한 로직 상세는 [`threads/`](threads/) 하위 문서를 참조한다.

---

## 1. 개요

`trypto-engine`은 단일 쓰기 스레드 위에서 지정가 주문 장부를 유지하고, 외부 시세 틱에 따라 체결 가능한 주문을 모두 체결하는 매칭 엔진이다. 
모든 인바운드 이벤트는 처리 전에 WAL에 append되고, 주기적 스냅샷과 WAL 리플레이로 장애 복구를 보장한다. 체결 결과는 DB에 반영된 뒤 아웃박스 패턴을 통해 trypto-api으로 재발행된다.

---

## 2. 패키지 구성

| 패키지 | 책임 |
|--------|------|
| `ingress` | RabbitMQ에서 엔진 인바운드 이벤트 수신 후 `EngineThread.submit` 호출 |
| `engine` | 단일 쓰기 스레드(`EngineThread`), `OrderBook`/`OrderBookRegistry`, 도메인 타입(`Side`, `TradingPair`, `OrderDetail`, `ExchangeCoinResolver`) |
| `event` | 엔진 경계 이벤트/커맨드 (record) |
| `wal` | `WalWriter`, `SnapshotWriter`, `WalRecovery`, `WalRecord`, `WalCommand` |
| `dbwriter` | `DbWriterThread`, `FillTransactionExecutor` — 체결 결과 DB 영속화 |
| `holding` | 홀딩 평단 증분 업데이트(`HoldingIncrementalUpdater`) 및 전체 재계산(`HoldingRecalculator`) |
| `publisher` | `OutboxRelay` — 아웃박스 테이블을 폴링해 아웃바운드 이벤트 발행 |
| `metrics` | `EngineMetrics` — Micrometer 지표 등록/노출 |
| `config` | `RabbitConfig`, `SchedulingConfig` 등 스프링 설정 |

---

## 3. 스레드 구성

### 3.1 스레드 목록

| 스레드 이름 | 상세 문서 |
|-------------|-----------|
| `engine-core` | [engine-core](threads/engine-core.md) |
| `engine-wal` | [engine-wal](threads/engine-wal.md) |
| `engine-dbwriter` | [engine-dbwriter](threads/engine-dbwriter.md) |
| `OutboxRelay` (스케줄러) | [outbox-relay](threads/outbox-relay.md) |
| RabbitMQ listener | §4.1 인바운드 계약 참조 (상태 없음) |

### 3.2 스레드 간 통신

단일 쓰기 스레드 소유, cross-thread 동기 대기 금지, thread-safe 자료구조 사용 범위 등 저장소 전체에 적용되는 동시성 규칙은 [`CLAUDE.md` 동시성 규칙](../CLAUDE.md#동시성-규칙) 참조.

### 3.3 Graceful shutdown

- `@PostConstruct init()`에서 `dbWriter.start()` → `walRecovery.recover()` → `walWriter.start()` → `engine-core` 스레드 시작 순
- `@PreDestroy stop()`에서 `running = false` 후 `thread.interrupt()`
- 각 스레드 루프는 `InterruptedException`을 catch하면 반드시 `Thread.currentThread().interrupt()`로 인터럽트 상태를 복구하고 종료한다

---

## 4. 외부 경계 계약

엔진이 소통하는 외부 서비스는 두 곳이다

| 상대 | 방향 | 교환 이벤트 | 역할 |
|------|------|-------------|------|
| `trypto-api` | 인바운드 / 아웃바운드 | `OrderPlaced`, `OrderCanceled` / `OrderFilledEvent` | 주문을 발행하는 업스트림, 체결 결과의 유일한 소비자 |
| `trypto-collector` | 인바운드만 | `TickReceived` | 외부 거래소 시세를 수집해 틱으로 주입하는 업스트림 |

엔진은 `trypto-api`/`trypto-collector` 메세지 큐를 통해 소통한다.

### 4.1 인바운드 — RabbitMQ `engine.inbox`

- 큐 이름: `${engine.inbox.queue}` (환경 설정)
- 리스너 동시성: 1 (단일 컨슈머, `@RabbitListener(concurrency = "1")`)
- 리스너 prefetch: `${spring.rabbitmq.listener.simple.prefetch:64}` — concurrency=1 고정이므로 이 값만이 인바운드 처리량 상한에 영향
- 메시지 헤더: `event_type` (필수)

**이벤트 스키마 (JSON payload)**

| event_type | 필드 |
|------------|------|
| `OrderPlaced` | `orderId:Long, userId:Long, walletId:Long, side:"BUY"|"SELL", exchangeCoinId:Long, coinId:Long, baseCoinId:Long, price:BigDecimal, quantity:BigDecimal, lockedAmount:BigDecimal, lockedCoinId:Long, placedAt:LocalDateTime` |
| `OrderCanceled` | `orderId:Long, exchangeCoinId:Long` |
| `TickReceived` | `exchange:String, displayName:String, tradePrice:BigDecimal, tickAt:LocalDateTime` |

- `event_type` 헤더가 없거나 미지의 값이면 WARN 로깅 후 drop (at-least-once 전제)
- 같은 이벤트의 재전송은 멱등 처리된다 (engine-core 문서, engine-wal 문서 참조)

**샘플 페이로드**

`OrderPlaced` (header `event_type=OrderPlaced`)

```json
{
  "orderId": 42,
  "userId": 7,
  "walletId": 2,
  "side": "BUY",
  "exchangeCoinId": 11,
  "coinId": 3,
  "baseCoinId": 1,
  "price": 100000000,
  "quantity": 0.005,
  "lockedAmount": 500000,
  "lockedCoinId": 1,
  "placedAt": "2026-02-21T14:30:00"
}
```

`OrderCanceled` (header `event_type=OrderCanceled`)

```json
{ "orderId": 42, "exchangeCoinId": 11 }
```

`TickReceived` (header `event_type=TickReceived`)

```json
{
  "exchange": "BITHUMB",
  "displayName": "BTC",
  "tradePrice": 100274000,
  "tickAt": "2026-02-21T14:30:00"
}
```

### 4.2 아웃바운드 — RabbitMQ fanout

- Exchange: `${engine.publisher.fanout-exchange}`
- 발행 경로: `FillTransactionExecutor`가 `outbox` 테이블에 INSERT → `OutboxRelay`가 500ms 주기로 폴링/발행

**`OrderFilledEvent` 스키마**

| 필드 | 타입 |
|------|------|
| `orderId` | Long |
| `userId` | Long |
| `executedPrice` | BigDecimal |
| `quantity` | BigDecimal |
| `executedAt` | LocalDateTime |

샘플 (fanout routing key `""`)

```json
{
  "orderId": 42,
  "userId": 7,
  "executedPrice": 100274000,
  "quantity": 0.005,
  "executedAt": "2026-02-21T14:30:01"
}
```

### 4.3 스키마 변경 정책

- 인바운드 이벤트는 `trypto-api`(주문) 또는 `trypto-collector`(틱)가 발행자, 엔진이 소비자. 필드 추가는 하위 호환(엔진이 무시)이지만 **필드 제거·타입 변경은 호환 불가** — 양쪽 동시 릴리스 필요
- `event_type`은 enum-like 키. 새 타입 추가는 엔진이 먼저 알아들을 수 있어야 하므로 엔진 릴리스 → 발행자 릴리스 순으로 배포
- 아웃바운드 `OrderFilledEvent`는 엔진이 발행자, `trypto-api`가 소비자. 동일 규칙을 반대 방향으로 적용
- WAL/스냅샷 포맷은 엔진 내부 규약이지만 **in-flight 이벤트를 보존한 채 무중단 업그레이드를 원하면** 기존 WAL을 전부 소진하고 체크포인트를 찍은 뒤 새 포맷으로 롤아웃한다


