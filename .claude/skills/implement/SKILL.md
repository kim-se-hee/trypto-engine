---
description: >
  기능 문서 기반 코드 구현. 플랜 모드에서 생성된 docs/{domain}/{feature}.md를 읽고
  CLAUDE.md 컨벤션에 따라 전체 스택을 구현한다.
  TRIGGER: 사용자가 기능 구현을 요청하거나 /implement를 입력할 때.
---

# 기능 문서 기반 코드 구현

기능 문서(`docs/{domain}/{feature}.md`)를 읽고 코드를 구현한다. 에이전트 없이 메인 컨텍스트에서 직접 실행한다.

## 입력

`$ARGUMENTS` = 기능 문서 경로 (예: `docs/wallet/transfer.md`)

---

## 페이즈 추적

각 Phase 시작 시 아래 명령어로 현재 페이즈를 기록한다. `{PHASE}` 부분만 해당 Phase 값으로 교체한다:

```bash
echo '{"phase":"{PHASE}","feature":"$ARGUMENTS"}' > "$HOME/.claude/implement-phase.json"
```

워크플로우 종료 시 반드시 페이즈 파일을 삭제한다:

```bash
rm -f "$HOME/.claude/implement-phase.json"
```

---

## Phase 1: 탐색

**페이즈 마커: `explore`**

이 단계에서는 반드시 읽기만 수행한다.

### 사전 읽기

구현 시작 전 아래 파일을 Read로 읽는다:

1. `docs/ai-context/cross-context/{컨텍스트명}.md` — 기능 문서에서 연동하는 컨텍스트의 파일만 선택적으로 읽는다 (예: wallet, marketdata 연동이면 `wallet.md`, `marketdata.md`만)
2. `docs/data-model.md` — 자기 컨텍스트의 엔티티·VO 구조 파악

단일 컨텍스트 작업(크로스 컨텍스트 연동 없음)이면 1번은 생략한다.

### 크로스 컨텍스트 경계 규칙

- **구현 범위는 기능 문서의 대상 컨텍스트에 한정한다.** 다른 컨텍스트의 UseCase 구현체를 만들지 않는다
- **다른 컨텍스트의 소스 코드를 읽지 않는다.** 시그니처와 DTO 구조는 `docs/ai-context/cross-context/` 파일만 참조한다
- **크로스 컨텍스트 파일에 필요한 UseCase/DTO가 없으면 구현을 중단한다.** 어떤 선행 기능을 먼저 구현해야 하는지 사용자에게 알린다

---

## Phase 2: 구현

**페이즈 마커: `implement`**

CLAUDE.md의 코딩 컨벤션과 Git 컨벤션을 따라 구현한다.

- 컴파일 의존 순서대로 구현한다 (domain → application → adapter)
- 논리 단위마다 `./gradlew compileJava` 로 검증 후 커밋한다
- 커밋 단위와 메시지는 CLAUDE.md의 Git 컨벤션을 따른다

구현 완료 후 ArchUnit 테스트를 실행한다.

```bash
./gradlew test --tests "*ArchUnit*"
```

ArchUnit 테스트가 전부 통과할 때까지 코드를 수정하고 재실행한다.

페이즈 추적을 종료한다:

```bash
rm -f "$HOME/.claude/implement-phase.json"
```

통과하면 Phase 3으로 넘어간다.

---

## Phase 3: 테스트

test-automator 서브에이전트에 위임한다.

프롬프트에 아래 정보를 포함한다:
- 기능 문서 경로
- Phase 2에서 생성/수정한 파일 목록

## Phase 4: 코드 리뷰

아래 4개의 리뷰어 서브에이전트를 병렬로 실행한다.

- architecture-reviewer
- code-quality-reviewer
- performance-reviewer
- concurrency-reviewer

리뷰 결과를 종합하여 사용자에게 보고한다.
