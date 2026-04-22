# `OutboxRelay` 스케줄러 스레드

아웃박스 테이블을 주기적으로 폴링하여 미발행 레코드를 RabbitMQ fanout exchange로 발행하는 스케줄러 스레드. 아웃박스 패턴의 "relay" 역할이다.


---

## 1. 폴링 루프

- `@Scheduled(fixedDelay = ${engine.publisher.relay-fixed-delay-ms:500})` 기본 500ms 주기
- 매 틱마다 `sent_at IS NULL`인 레코드를 최대 `${engine.publisher.relay-batch-size:256}`건 읽는다
- 각 레코드를 fanout exchange로 `basicPublish(exchange, "", payload)` 호출
- 발행 성공 레코드만 `UPDATE sent_at = now()`

---

## 2. 발행 보장 특성

- 아웃박스 INSERT는 트랜잭션 안에서 일어나므로, 커밋된 체결은 반드시 언젠가 발행된다 ("DB 반영 성공 == 이벤트 발행 책임 이관")
- 발행 실패(RabbitMQ 일시 장애 등)는 `sent_at`이 갱신되지 않아 다음 폴링에서 재시도된다
- 따라서 at-least-once 발행이다. 소비자는 `orderId` 기준으로 멱등 처리해야 한다
- 발행 순서는 `outbox` 테이블의 PK 오름차순, 즉 INSERT된 순서다.

---

## 3. 멱등성

| 경로 | 중복 유입 시 | 순서 보장 | 판정 키 | 보장 위치 |
|------|--------------|-----------|---------|-----------|
| 아웃바운드 `OrderFilledEvent` | 소비자 측 멱등 처리 전제 | Outbox 폴링 순서 | `orderId` | `OutboxRelay` |

- RabbitMQ 일시 장애 등으로 발행 실패 시 `sent_at`이 갱신되지 않아 다음 폴링에서 재시도 → at-least-once 발행
- 소비자(`trypto-api`)는 `orderId` 기준 멱등 처리 전제. 같은 이벤트가 두 번 도달할 수 있다

---

## 4. 설정 파라미터

| 키 | 기본값 | 바인딩 | 영향 |
|----|--------|--------|------|
| `engine.publisher.fanout-exchange` | `order.filled.notification` | `RabbitConfig` / `OutboxRelay` | 아웃바운드 fanout exchange 이름 |
| `engine.publisher.relay-fixed-delay-ms` | 500 | `OutboxRelay` (`@Scheduled`) | 아웃박스 폴링 주기 |
| `engine.publisher.relay-batch-size` | 256 | `OutboxRelay` | 한 번에 읽어 발행하는 `sent_at IS NULL` 레코드 수 |
