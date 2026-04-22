---
description: >
  Issue 생성 → Pull Request 생성 → Squash Merge까지 한 번에 처리한다.
  현재 브랜치의 변경 사항을 분석하여 GitHub Issue를 먼저 생성하고,
  Issue 번호가 포함된 PR을 생성한 뒤 main으로 자동 Squash Merge한다.
  추가 커밋은 절대 하지 않으며, git add/commit도 실행하지 않는다.
  TRIGGER: 사용자가 PR 생성을 요청하거나 /create-pr, /pr을 입력할 때.
---

# Issue → PR → Squash Merge → 로컬 정리

GitHub Issue를 먼저 생성하고, 현재 브랜치의 변경 사항으로 Pull Request를 생성한 뒤, main으로 자동 Squash Merge하고 로컬을 정리한다. **추가 커밋은 절대 하지 않는다.**

## 핵심 정책

- Issue를 먼저 만들고, PR 제목에 Issue 번호를 붙인다.
- Issue와 PR 모두 적절한 **label**과 **assignee**를 반드시 설정한다.
- 브랜치명에는 Issue 번호를 포함하지 않는다.
- Base 브랜치는 항상 `main`이다.
- 모든 내용은 한국어로 작성한다.
- Co-Authored-By 라인을 절대 포함하지 않는다.
- main과 충돌이 발생하면 rebase로 해결한다. 양쪽의 변경 사항을 모두 반영하고, 머지 커밋은 절대 남기지 않는다.

---

## 라벨 가이드

변경 사항의 성격에 따라 아래 라벨을 사용한다.

| 라벨 | 사용 기준 |
|------|----------|
| `enhancement` | 새 기능 구현 |
| `bug` | 버그 수정 |
| `documentation` | 문서 변경 |

## Assignee

- 항상 `kim-se-hee`를 assignee로 설정한다.

---

## 워크플로우

### 1. Git 상태 확인

```bash
git status
git branch --show-current
git log --oneline -5
git rev-list --count HEAD ^main 2>/dev/null || echo "0"
git diff main
```

### 2. 브랜치 규칙

- 브랜치명은 `feature/*` 형식을 따른다. Issue 번호를 포함하지 않는다.
- 예시: `feature/cex-order`, `feature/dex-swap`, `feature/wallet-transfer`
- 현재 `main`에 있으면 PR 생성 전에 `feature/*` 브랜치를 생성한다.

### 3. 변경 사항 분석 후 Issue 생성

**분석 대상:**
- `git diff main` 출력
- 변경된 파일 목록과 내용
- 현재 브랜치의 커밋 메시지들
- 브랜치명 (컨텍스트 힌트)

**Issue 유형 판단:**

1. **테스트 Issue** — 테스트 코드 변경 시
```markdown
## 테스트 목록

* [테스트 케이스 1]
* [테스트 케이스 2]
```

2. **버그 수정 Issue** — 버그/성능 문제 수정 시
```markdown
## 문제 분석

### 1. [문제 분류]
* [구체적 문제 설명]
* [영향 범위]
```

3. **기능 구현 Issue** — 새 기능 구현 시
```markdown
## 기능 설명

[간략한 설명]

## 요구사항

* [요구사항 1]
* [요구사항 2]
```

**Issue 생성:**
```bash
gh issue create --title "Issue 제목" --body "Issue 내용" --label "<라벨>" --assignee "kim-se-hee"
```

생성된 Issue 번호를 PR 제목에 사용한다.

### 4. main 동기화 (Rebase)

리모트 푸시 전에 main의 최신 변경 사항을 rebase로 반영한다. **머지 커밋은 절대 남기지 않는다.**

```bash
git fetch origin main
git rebase origin/main
```

충돌이 발생하면:
1. 충돌 파일을 열어 양쪽의 변경 사항을 모두 반영한다 (어느 쪽도 버리지 않는다)
2. 해결 후 `git add <충돌-파일>` → `git rebase --continue`
3. 모든 충돌이 해결될 때까지 반복한다

### 5. 문서 동기화

리모트 푸시 전에 브랜치의 변경 사항을 분석하여 관련 문서를 갱신한다. **변경이 없으면 이 단계를 건너뛴다.**

#### 5-1. 데이터 모델 · 스키마 갱신

`git diff main`에서 도메인 모델(`domain/model/`)이나 VO(`domain/vo/`), JPA 엔티티(`adapter/out/entity/`)가 **신규 생성되거나 필드가 변경**된 경우:

1. `docs/data-model.md`의 Aggregate 구조 테이블·소유 관계·모듈 간 의존을 현재 코드 기준으로 갱신한다.
2. `docs/schema.md`의 ERD를 현재 JPA 엔티티 기준으로 갱신한다.
3. 갱신한 문서를 별도 커밋한다.

```bash
git add docs/data-model.md docs/schema.md
git commit -m "docs: 데이터 모델 및 스키마 문서 동기화"
```

#### 5-2. 크로스 컨텍스트 인터페이스 동기화

`git diff main`에서 **다른 컨텍스트가 소비하는 UseCase(`application/port/in/`)나 Result/Command DTO가 신규 생성되거나 시그니처가 변경**된 경우:

1. 해당 컨텍스트의 `docs/ai-context/cross-context/{context}.md`를 현재 코드 기준으로 갱신한다.
   - UseCase 인터페이스의 메서드 시그니처를 동기화한다.
   - Command/Result DTO의 필드 목록을 동기화한다.
   - 신규 UseCase/DTO는 추가하고, 삭제된 것은 제거한다.
2. 갱신한 문서를 별도 커밋한다.

```bash
git add docs/ai-context/cross-context/<context>.md
git commit -m "docs: <context> 크로스 컨텍스트 인터페이스 문서 동기화"
```

**판단 기준:**
- `port/in/` 하위의 UseCase 인터페이스나 `port/in/dto/` 하위의 Command/Query/Result DTO가 diff에 포함되어야 한다.
- 서비스 내부 변경만 있고 포트 시그니처가 동일하면 동기화하지 않는다.

### 6. 리모트 푸시

```bash
git push origin <현재-브랜치명>
```

### 7. PR 생성 규칙

- **절대 `git add`나 `git commit`을 실행하지 않는다.**
- Base 브랜치는 반드시 `main`이다.
- 현재 브랜치 상태 그대로 PR을 생성한다.

### 8. PR 제목 컨벤션

```
[#ISSUE_NUMBER] 간결한 PR 설명
```

- 커밋 타입 접두사(`feat:`, `fix:` 등)는 PR 제목에 사용하지 않는다.
- Issue 번호만 `[#N]` 형식으로 앞에 붙인다.

예시:
```
[#12] CEX 시장가/지정가 주문 기능 구현
[#15] 지정가 매수 주문 시 수수료 미반영 수정
```

### 9. PR 본문

PR #2를 베스트 프랙티스로 참고한다. 아래 레이아웃을 기본 골격으로 사용하되, 변경 규모에 맞게 섹션을 조정한다.

```markdown
## Summary

[1~3줄 요약. 이 PR이 뭘 하는지.]

- **기능 A** — 설명
- **기능 B** — 설명

---

## 핵심 흐름

[주요 비즈니스 흐름을 텍스트 다이어그램이나 단계별 설명으로 표현한다. 복잡한 기능일 때만 포함.]

---

## 주요 변경 사항

### 도메인 계층

[도메인 모델, VO, 팩토리 메서드 등 변경 내용]

### 애플리케이션 계층

[UseCase, Service, Port, DTO 등 변경 내용]

### 어댑터 계층

[Controller, Persistence Adapter, 외부 API Adapter 등 변경 내용]

### 인프라 및 설정

[설정 파일, 에러 코드, 의존성 등 변경 내용. 해당 없으면 생략.]

---

## 설계 결정

| 결정 | 이유 |
|------|------|
| [설계 선택] | [왜 이렇게 했는지] |

---

## Test Plan

### 인수 테스트

- [x] [시나리오 1]
- [x] [시나리오 2]

### 도메인 단위 테스트

- [x] [테스트 1]
- [x] [테스트 2]

---

## 미반영 사항

[의존 모듈 미구현 등으로 이번에 포함하지 못한 항목. 해당 없으면 생략.]

Closes #N
```

**PR 생성 명령:**

```bash
gh pr create --title "[#N] PR 제목" --body "$(cat <<'EOF'
(위 레이아웃에 맞춘 본문)
EOF
)" --label "<라벨>" --assignee "kim-se-hee"
```

### 10. Squash Merge

PR 생성 후 즉시 main으로 Squash Merge한다.

**필수 규칙 — extended description은 반드시 비워둔다:**
- Squash 커밋의 extended description에 커밋 목록이 들어가면 안 된다. 무조건 비워야 한다.
- `--subject`와 `--body ""`를 **반드시 한 명령에** 함께 넘긴다. 
- `--subject`와 `--body`를 별도 명령으로 분리하지 않는다 (첫 명령에서 이미 머지될 수 있음).

**Squash Merge 커밋 제목에는 반드시 PR 번호를 포함한다:**
- 형식: `[#ISSUE_NUMBER] PR 제목 (#PR_NUMBER)`
- `gh pr create`의 출력에서 PR 번호를 파싱하여 사용한다.

```bash
gh pr merge --squash --subject "[#N] PR 제목 (#PR_NUMBER)" --body ""
```

결과 커밋 예시:
```
[#12] CEX 시장가/지정가 주문 기능 구현 (#13)
```

### 11. 로컬 정리

머지 완료 후 로컬을 최신 상태로 맞추고 브랜치를 삭제한다.

```bash
git checkout main
git pull origin main
git branch -D <머지된-브랜치명>
```

- Squash Merge 후에는 `git branch -d`가 "not fully merged" 경고를 내므로 `-D`를 사용한다.
