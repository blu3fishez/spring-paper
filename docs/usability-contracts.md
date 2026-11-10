# 실행·빈 구성 사용성 추가 검증 — 2026-11-11

기준은 Spring Paper `40e8b8b`, 소비자 `feat/SIMEXT-90`의 `290226c1`이다.
실제 작업공간은 spring-paper와 SimpleExtraction-SIMEXT-90이며 원래 SimpleExtraction/develop는 변경하지 않았다.
기존 DungeonConnectionListener의 미커밋 편집과 build/usability-audit의 두 재현 자료는 보존했다.
운영 중인 개발계는 배포하거나 재기동하지 않았다. 고정 버전도 바꾸지 않았다.

## 해결한 재현과 지원 경계

| 재현 | 결과 |
|---|---|
| thenAcceptAsync(..., PaperTickScheduler) 종료 뒤 종속 Future 미완료 | raw Executor의 한계로 명시. 라이브러리가 소유하는 nextTick(source, handler)를 권장하고 소비자에 적용 |
| PaperTasks.main과 raw execute의 완료 계약 불일치 | main/nextTick/async/submitCompletable은 성공·실패·취소·종료 결과 관리. 일반 execute는 fire-and-forget |
| qualifier-only JdbcOperations가 일반 기본을 억제 | 실제 Spring 주입 후보 판정으로 수정. 일반/전용 공존·Primary·Fallback·FactoryBean 재사용 검증 |
| 소비자 JdbcClient만 있는데 기본 template가 DataSource 요구 | typed @Bean/@Lazy 기본으로 미사용 공급자 생성/설정 평가 방지 |
| Cache·Security·JPA의 동일 후보 문제 | 각 재현을 실패 테스트로 확인한 범위만 수정. 소비자 소유 자원 재사용 |
| PaperTasks가 worker를 직접 생성 | typed 실행 구성에서 표준 SimpleAsyncTaskExecutor 기본. 역할 qualifier의 소비자 Executor/AsyncTaskExecutor와 decorator 재사용 |

Executor.execute는 업무 Runnable만 받으며 호출자가 만든 CompletableFuture를 알 수 없다.
임의 Runnable을 종료 중 실행하거나 JDK 내부 reflection으로 Future를 완료하지 않았다.
관리형 continuation은 upstream이 미완료여도 종료 시 실패하며 upstream의 DB commit은 취소하지 않는다.
`main`은 이미 main이면 즉시, `nextTick`은 항상 다음 tick이라는 차이를 유지했다.
세부 결과·취소·타임아웃과 소유권은 [실행 계약](execution-contracts.md)을 따른다.

## 실제 소비자 조사·적용

경로는 SimpleExtraction 루트 기준이다. 전체 프로젝트의 기계적 치환은 하지 않았다.

| 우선순위·파일 | 전 | 후·남는 책임 |
|---|---|---|
| equipmentrecovery/service/TraceRecoveryService | JavaPlugin·BukkitTask·start 예약·close 취소 | background @Scheduled가 공유 outbox pass 완료를 기다림. archive policy 초기화와 pipeline close 유지 |
| gameguide/service/GuideSynchronizationService | timer 필드·초 카운터·mirror/refresh/retry 혼합 | main mirror 1초/refresh 30초와 background DB retry 30초. 정의 등록·session·silent baseline·관찰 commit·종료 flush 유지 |
| recipebook/service/RecipeBookSynchronizationService | handleAsync(raw main executor) | nextTick(source, handler)로 source 대기부터 관리. UUID 직렬화·접속 검사·quit/final save 유지 |
| equipmentrecovery/service/TraceConversionService | 수동 Promise + main.execute, 종료 시 timeout까지 대기 가능 | typed nextTick 값. 기능 close/timeout 시 취소, worker 해제와 늦은 변환 차단. hash/version 검증 유지 |
| equipmentrecovery/service/TraceDeliveryService | CAS busy로 중복만 억제 | 완료 Future 공유, caller에는 copy. 한 caller의 cancel이 durable pass를 중단하지 않음. cursor/SQL/outbox 처리 유지 |

앞 세 서비스는 common/paper/src/main/java 아래의 기존 기능별 패키지, 변환/배달은
common/infrastructure/equipmentrecovery/src/main/java 아래에 있다.
TraceDelivery는 PaperTasks·Logger 직접 주입과 Lombok 생성자를 사용해 JavaPlugin/호환 wrapper를 제거했다.
PlayerTaskExecutor도 PaperTasks를 직접 쓰지만 기존 UUID별 tails/실패 뒤 진행 규칙은 유지한다.
MainThreadExecutor/VirtualTaskExecutor는 실제 잔존 소비처가 있어 deprecated 호환 경로로 남겼다.
MainThreadExecutor의 다음 tick 의미도 유지한다. 기다릴 결과는 이 void 경로로 새로 작성하지 않는다.

### 대표 코드 전후

```java
// 전: caller-created 종속 결과는 executor 종료 때 미완료로 남을 수 있음
read.handleAsync((known, error) -> { /* current session 확인, 반영 */ return null; }, ticks);

// 후: source 대기부터 명시적인 종료 결과를 받음
var applied = execution.nextTick(read, (known, error) -> {
    if (!current(session)) return;
    if (error != null) throw new CompletionException(error);
    apply(known);
});
return applied;
```

```java
// 전: outbox pass가 끝나기 전에 scheduler가 반환하고 별도 busy/예약을 관리
pollTask = scheduler.runTaskTimer(plugin, delivery::poll, 20L, 100L);
// 후: 실제 완료 이후 5초 지연. init/close는 pipeline의 실제 책임만 남김
@Scheduled(initialDelayString = "PT1S", fixedDelayString = "PT5S")
public void poll() {
    if (started && !delivery.isClosed()) delivery.poll().exceptionally(error -> null).join();
}
```

이 join은 background에서만 실행하고 conversion close가 main 대기를 해제한다. main에서 worker→main
결과를 join하는 패턴이 아니다. DB 전용 최종 저장과 outbox drain은 기존 순서대로 수행한다.
Trace/Guide는 실시간 유지보수 정책이므로 기존 tick 주기를 elapsed-time 주기로 명시했다.
서버가 느릴 때 100tick과 5초는 다르다. 전투/애니메이션 tick을 바꾸지 않았다.
DungeonWorldStartupService의 world recovery → expiry 시작 순서와 조건부 retry는 이전 전환을 유지한다.

## 개발자가 쓰는 파일·설정·필드

| 기능 추가 | 작성할 것 | 생략할 것 |
|---|---|---|
| 단순 유지보수 | 기존 서비스 업무 메서드 + @Scheduled, 필요하면 주기 속성 | 전용 task 필드·start/close·worker 제출·busy·cancel. EnableScheduling은 루트 한 번 |
| DB→Paper 반영 | 기존 저장소/서비스, nextTick와 session/revision 검사 | 수동 main Promise·완료/종료 bookkeeping |
| 이벤트/명령 | 입력 adapter + 기존 stereotype | 수동 registerEvents/setExecutor/해제, descriptor 중복. 이번 단계에서 새 어노테이션 없음 |
| worker 교체 | 필요할 때 역할 qualifier @Bean 한 개, 선택 TaskDecorator | 새 PaperTasks 풀·별도 종료 소유자. 기본이 충분하면 구성 0개 |
| 선택 기능 | 소유 Configuration + 표준 Condition/Profile | 앱 기능별 Module SPI·새 toggle framework |
| 복구·최종 저장 | 실제 복구/flush lifecycle | 보존 계약은 제거 대상이 아님 |

대표 소비자 기능의 운영 파일 수는 줄지 않았다. 단순 유지보수의 일반 Spring 지식은 재사용하지만
Paper main/다음 tick/서버 tick의 의미, 관리형 Future와 raw execute의 지원 범위를 알아야 한다.
TaskDecorator와 커스텀 executor는 필요할 때만 구성한다. 조건부 기능은 등록 경로별 책임을 배워야 한다.

## 이동한 복잡성과 비용

실제로 사라진 반복은 native 예약/취소·주기 카운터·수동 완료 Promise다.
완료 추적·실행 전 취소·종료 fencing·borrowed worker drain은 라이브러리 내부로 이동했다.
외부 executor의 queue/자원 소유권이나 실제 I/O를 강제 취소하는 새로운 프레임워크는 만들지 않았다.
Spring의 스케줄러·시간 계산·프록시·풀/가상 스레드 생성·기본 lifecycle을 그대로 사용한다.

추가한 운영 타입 네 개는 내부 dispatcher/worker ledger, typed 실행 구성, 모듈 공용 internal 기본 후보 도우미다.
공개 확장은 기존 PaperTasks의 관리형 nextTick overload와 worker 역할 qualifier다. 새 업무 어노테이션은 없다.
기본 공급자는 typed @Bean/@Fallback/@Lazy와 실제 Spring 후보 판정으로 처리한다.
기존 JPA programmatic 구성과 조건부 연결 어댑터의 IDE 추론 한계까지 숨기지는 않는다.

| Java 소스 순증감 | 소비자 | 라이브러리 |
|---|---:|---:|
| 운영 코드 | -5줄, 운영 파일 추가 없음 | +321줄, 타입 4개 추가 |
| 자동 테스트 | +291줄 | +618줄 |
| 예제 운영/검증 | 해당 없음 | 운영 +203줄 / 예제 테스트 +202줄 |

40e8b8b/290226c1 대비 주석·공백·import 포함 Java만 계산했고 기존 사용자 편집은 제외했다.
한 기능당 파일/설정을 억지로 합치지 않았다. 개발 시간 단축률은 측정하지 않았다.
가독성과 생산성 개선의 근거는 줄 수보다 새 주기 작업에서 task·예약·취소 소유권을 생략할 수 있고,
새 main 반영 작업에서 source 대기/종료 완료를 직접 구현하지 않아도 된다는 점이다.

## 검증 결과·제약

[기계 판독 결과](usability-verification.json)에 수치·산출물 해시를 기록했다.

- 라이브러리 build/Javadoc/600줄 검사 및 201개 테스트, 예제 10개 테스트와 독립 예제 6개 build 성공.
- 소비자 전체 build·모듈 경계·가이드 구성 검사 및 538개 테스트 성공. MariaDB/Redis 실제 어댑터 포함.
- 실제 Spring 컨텍스트·타이머·@Async 프록시·H2로 취소/종료/실행 위치/TCCL·빈 선택·소유권 검증.
- 종료 전 main 취소 방어, qualifier-only 필터를 각각 제거한 두 변형을 assertion 실패로 검출. 원본 복원 뒤 build 성공.
- includeBuild 없는 Maven 소비와 설정 캐시 재사용 성공. 현재 자체 클래스 153개 bytecode 일치, ZIP 중복/Boot/호스트 API/SLF4J/Lombok 패키지 혼입 0.
- host Java25/Paper26.3 build244에서 예제 6개 함께 기동. module/inbox true와 false 모두 명령·예약·JDBC/JPA/Cache/PDC/Security fixture·정상 종료 성공. 비활성 업무 실행기와 정적 도움말 차이 확인.

native 검증 스크립트가 처음에 runPaper 자동 JAR을 plugins에 중복 복사해 멈췄다. 중복을 제거했다.
도움말은 색상/표제 형식으로 출력하므로 색을 제거하고 실제 `Help: /paper-inbox` 표제를 검증했다.
배포 예제 EULA 기본값 false를 유지하고 허용된 ignored 로컬 설정에서만 true를 사용했다.

Spring 소스 확인 중 @Lazy가 LoadTimeWeaverAware/SmartLifecycle의 표준 초기화를 막지 않는 것을 재현했다.
JPA/Security 구성이 활성화된 경우 초기화한다. 비활성 기능은 소유 import/Module 조건도 함께 끈다.
[선택 기능](optional-features.md)과 [기본 빈 계약](default-beans.md)에 직접 scan/import/독립 repository registrar의 범위를 기록했다.

운영 개발계 배포/재기동, Docker 이미지 재빌드, 최신 소비자 본체의 native 기동은 이번 검증에 포함하지 않았다.
실제 Minecraft 클라이언트 접속/발전과제 UI·서버 이동·Discord OAuth·장기 성능도 보장하지 않는다.
원본 DB 작업이 timeout 이후 커밋할 수 있는 한계, 세션/revision·멱등성·최종 저장 책임은 남는다.

최종 소비자 빌드 전에 이벤트 순서 검사 한 번에서 호출 목록이 비었다. 단독 역할, 역할 구성 7개 전체,
최종 전체 build는 이후 통과했다. 원인은 확정하지 못했으며 검사 비활성화 없이 후보/등록/호출 진단을 추가했다.
소비자 JAR의 실제 도입 모듈 6개, 자체 클래스 108개도 현재 라이브러리와 bytecode가 일치했다.
