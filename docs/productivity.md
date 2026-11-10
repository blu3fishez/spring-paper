# 소비자 개발 흐름 개선 — 2026-11-10

이 문서는 첫 개선 단계의 기록이다. 후속 실행/빈 선택 계약은 [추가 검증](usability-contracts.md)을 따른다.

기준은 Spring Paper `2b90f06`, SimpleExtraction `feat/SIMEXT-90`의 `d1d56da1`이다.
라이브러리와 고정 submodule 소비 빌드에서 구현·검증했다. 운영 중인 개발계 배포·재기동은 하지 않았다.
초기에 넓게 작성했던 JavaPlugin 치환 초안은 소비자의 ignored build/migration에 보존하고,
대표 세 흐름과 같은 구현을 공유하는 청크 lease 소비자 네 곳으로 적용 범위를 좁혔다.
기존 미커밋 DungeonConnectionListener의 수정과 원래 develop 작업공간의 변경은 보존했다.

## 실제 소비자 조사와 우선순위

아래 경로는 SimpleExtraction 저장소 루트 기준이다.

| 우선순위 | 근거 파일 | 기존 소비자가 소유하던 반복 처리 | 결정 |
|---|---|---|---|
| 1 | skyblock/infrastructure/mailbox/.../mailbox/infrastructure/paper/PaperMailboxMaintenance.java | BukkitTask·start/close·초→tick 변환·worker 제출·AtomicBoolean 중복 방지·완료/실패 처리 | @Scheduled의 background fixedDelay에서 DB 작업을 직접 완료 |
| 2 | dungeon/infrastructure/worldcycle/.../worldcycle/DungeonWorldCollapseCoordinator.java | 1초 scanTask·시작 여부·100tick 재시도/종료 예약·취소·중첩 worker/main callback | scan은 @Scheduled, 재시도/종료는 같은 TaskScheduler의 일회성 Future |
| 3 | common/paper/.../recipebook/service/RecipeBookSynchronizationService.java | polling task·worker 완료 후 main.execute 중첩·접속 session·UUID 직렬화·quit 저장 flush | poll은 @Scheduled, 결과는 관리형 nextTick (후속 실행 계약); 데이터 보장 유지 |
| 확인 | common/paper/.../recipebook/listener/RecipeBookListener.java | 이벤트 등록/해제 | 이미 PaperEventListener가 소유. 중복 등록 코드 추가 없음 |
| 확인 | common/paper/.../gameguide/command/GuideCommand.java | 실행기/탭 완성과 descriptor 등록 | 이미 PaperCommandExecutor/Gradle metadata가 소유. 예제로 재검증 |
| 유지 | common/infrastructure/platform/.../paper/PlayerTaskExecutor.java | 플레이어별 순서와 앞선 실패 이후 진행 | 게임별 직렬화 계약. @Async로 대체하지 않음 |
| 유지 | common/infrastructure/platform/.../paper/VirtualTaskExecutor.java | 기존 run/supply 호환 API | 이미 PaperTasks에 위임하며 별도 풀 없음. 전역 import 치환은 생략 |

월드 복구 완료 후 개인 만료 감시를 시작하는 연결은
`dungeon/paper/.../worldcycle/DungeonWorldStartupService.start → worlds.start(expiry::start)`에 남긴다.
start를 제거하는 것 자체를 목표로 삼지 않는다. 컨텍스트 refresh 완료는 월드 복구 완료를 뜻하지 않는다.

## 전후 비교

수치는 전체 파일의 물리적 줄 수이며 import·주석·공백을 포함한다.

| 대표 서비스 | 이전 → 이후 줄 수 | task 필드 | 수동 예약 호출 위치 | 수동 cancel 호출 위치 |
|---|---:|---:|---:|---:|
| PaperMailboxMaintenance | 55 → 35 | 1 → 0 | 1 → 0 | 1 → 0 |
| DungeonWorldCollapseCoordinator | 276 → 289 | 2 → 2 | 4 → 2 | 3 → 3 |
| RecipeBookSynchronizationService | 117 → 123 | 1 → 0 | 1 → 0 | 1 → 0 |

우편함은 타이머 수명주기 메서드 두 개와 중복 실행 AtomicBoolean까지 제거했다.
월드 조정기는 scanTask를 없앴지만 예약된 shutdown의 Future를 추가로 소유한다.
종료 뒤 도착한 조회/귀환 콜백과 이미 예약한 shutdown을 차단하므로 파일은 늘었다.
레시피는 task 필드/예약/취소와 중첩 main.execute를 제거했지만 Server·Logger 주입, late-callback 검사와
완료 stage의 오류 처리를 보강해 파일이 늘었다. 이 안전성 증가를 코드 감소로 계산하지 않았다.

### 유지보수 기능 하나를 추가할 때

이전과 이후 모두 업무 서비스 파일 한 개와 기존 repository가 필요하다. 설정 클래스의
@EnableScheduling은 앱에서 한 번만 선언한다. 기능마다 Configuration·별도 라이브러리 어노테이션을 만들지 않는다.
SimpleExtraction은 기존 config.yml 키를 initializer에서 Spring Environment의 낮은 우선순위 소스로
한 번 연결한다. 시스템 속성·환경 변수·application.yml이 우선하며 mailbox의 기존 검증 범위도 유지한다.

```java
// 이전: 서비스 필드에 plugin, tasks, timer, running을 두고 start/close를 작성
// timer = scheduler.runTaskTimer(plugin, this::submitCleanup, 1, seconds * 20);
// submitCleanup: CAS → tasks.supply(repository::cleanup) → CAS 해제 + 로그

// 이후: 실제 DB 작업이 완료된 뒤부터 다음 delay를 계산
@Scheduled(initialDelayString = "PT0.05S", fixedDelayString = "${mailbox.cleanup-interval-seconds:300}",
        timeUnit = TimeUnit.SECONDS)
public void cleanup() {
    try { /* repository.cleanup(policy); 업무 결과 로그 */ }
    catch (RuntimeException error) { /* 보존 후 다음 주기에 재시도한다는 업무 로그 */ }
}
```

### 비동기 결과 반영

```java
// 이전
worker.whenComplete((known, error) -> {
    if (closed) return;
    mainThread.execute(() -> { /* session 검사와 반영 */ });
});

// 이후: 동일한 UUID 직렬화 executor를 사용하고 main에서 다시 유효성을 검사
var applied = execution.nextTick(worker, (known, error) -> {
    if (closed || sessions.get(id) != session || server.getPlayer(id) != player) return;
    if (error != null) throw new CompletionException(error);
    apply(player, known);
});
// 반환하는 applied는 source 대기부터 종료/취소 결과를 관리한다.
```

세션 검사·멱등적 merge·트랜잭션·미완료 quit 저장 대기는 업무 계약이므로 소비자에 남는다.
@Async를 쓰면 별도 non-final proxy 서비스·@EnableAsync가 필요하고 이 직렬화도 그대로 유지해야 한다.
실제 Spring proxy로 외부 호출은 worker, self-invocation은 caller라는 차이를 검증한 뒤 이 흐름에는 도입하지 않았다.
표준 @Async 사용 예제는 독립 scheduling-plugin에서 제공한다.

## 실행 의미와 학습 비용

| 선택 | 개발자가 알아야 할 계약 |
|---|---|
| 무지정 @Scheduled | background 실제 시간. 순수 DB 작업은 이 메서드에서 완료해야 함 |
| @Scheduled(scheduler=PaperMainThreadTaskScheduler.BEAN_NAME) | 실제 시간, Paper main 실행. 짧은 작업만 실행 |
| TaskScheduler.schedule + ScheduledFuture | 일회성/조건부 retry. 대체 예약 취소와 게임별 closing 검사는 소비자 책임 |
| PaperTickScheduler | 서버 tick 단위. execute는 항상 다음 tick. 게임 애니메이션/전투에 사용 |
| PaperTasks / AsyncTaskExecutor | 기존 worker/TCCL·종료 소유자 재사용. @Async 프록시 제약은 그대로 |
| Spring lifecycle / Paper main 훅 | 일반 빈 준비와 Paper 상태 변경 시점을 구분 |

월드 주기는 Instant 기반 일일 일정, 우편함 보존은 실시간 정책이므로 실제 시간 예약을 사용한다.
레시피 poll은 저장소 합류 복구 지연이며 전투 cooldown이 아니다. 100tick에서 PT5S로 의미를 명시했다.
전투·애니메이션의 tick을 기계적으로 시간으로 바꾸지 않았다.
전체 사용법과 종료 순서는 [scheduling.md](scheduling.md)를 따른다.

## 라이브러리에 이동한 책임과 비용

Spring의 어노테이션 검색·Trigger·시간 계산·예외 정책·일반 scheduler lifecycle을 재구현하지 않는다.
직접 만든 부분은 Paper 메인 전달을 완료 Future와 연결하는 adapter, native 작업의 플러그인 소유권,
예약 취소와 종료 fencing, 기본 후보 구성이다. 메인 전달을 기다리는 timer thread와 별도 보안 timer의 비용이 생긴다.
이 timer들은 플러그인별로 소유하며 메인 스레드 종료에서 완료를 기다리지 않는다.

Spring 7의 타입 탐색은 단 하나의 qualifier 전용 scheduler도 선택할 수 있다.
그래서 지연 생성되는 표준 background 기본 빈을 제공하고 소비자의 기본 빈이 있으면 back off한다.
Security는 별도 이름으로만 교체한다. 유지보수 scheduler를 막아도 인증 대기가 EXPIRED로 전이되는 테스트를 추가했다.

| 공개 타입/기능 | 반복 제거 근거 | 남는 비용 |
|---|---|---|
| PaperMainThreadTaskScheduler | main 주기마다 runTask/cancel/완료 관리 반복 제거 | timer·Future 취소·완료/TCCL 회귀 테스트 유지 |
| PaperTickScheduler | plugin 참조 없이 native tick 작업, 종료 일괄 취소 | tick/시간과 즉시/다음tick 실행을 구별해야 함 |
| PaperKeys | 키 namespace와 Locale.ROOT 정규화 한 곳 소유; guide PDC 예제 사용 | 생성자 인자가 하나 늘 수 있음 |
| PaperPluginInfo | 파일 경로·리소스 loader·namespace를 host에서 한 번 캡처 | 일반 서비스가 metadata가 필요할 때만 주입 |
| PaperBukkitConfiguration | 기존 config save/get의 plugin 의존 제거 | legacy FileConfiguration의 공유/스레드 제약은 남음; 새 코드는 Environment 사용 |
| PaperChunkTickets | 실제 DungeonChunkTickets의 map/refcount/Lease 구현을 라이브러리로 이동, 네 소비자 공유 | native 소유권·off-thread/실패/종료 회귀 테스트 유지 |
| PaperTasks의 AsyncTaskExecutor 구현 | 표준 @Async/submitCompletable이 기존 worker 재사용 | 세션·트랜잭션·인증 문맥을 자동 전파하지 않음 |

JavaPlugin 하나를 Server+Logger+utility들로 바꾸면 필드와 import가 늘 수 있다.
기능을 하나의 큰 utility로 감싸지 않고 필요한 책임만 주입한다. 새 utility를 모든 파일에 배포하는 것을
생산성 개선으로 계산하지 않았다. 숫자로 된 소스 증감은 [검증 데이터](productivity-verification.json)에 분리한다.

| Java 변경량 | 운영 코드 | 테스트 코드 | 예제 코드 |
|---|---:|---:|---:|
| SimpleExtraction | -56줄 | +344줄 | 해당 없음 |
| Spring Paper | +472줄 | +860줄 | +676줄 |

청크 lease의 기존 운영 59줄·테스트 99줄은 이동한 부분이다. 라이브러리 증가는 예약 adapter·종료/빈 선택과
호스트 유틸을 제공하는 유지보수 비용이다. 테스트는 완료·취소·thread/loader·후처리·소유권·실패를 검증한다.
기능별 예제 네 개를 추가해 기존 종합 예제와 합쳐 다섯 개를 제공한다.

런타임과 IDE는 PaperCoreConfiguration의 동일한 typed @Bean과 일반 @ComponentScan/@Import 정의를 사용한다.
개별 소비자 타입에 맞춘 경고 억제나 가짜 빈을 추가하지 않았다. IDE가 임의의 initializer·ServiceLoader·
후처리기의 조건부 제외 결과까지 정적으로 전부 추론한다고 보장하지 않는다.

## 검증과 판단

라이브러리 전체 build/Javadoc/소스 크기 검사와 자동 테스트 171개가 통과했다.
실제 Spring 컨텍스트·타이머·프록시와 제어 가능한 Paper 경계로 thread·완료·취소·startup failure·shutdown·빈 선택을 확인했다.
실제 Paper에서는 다섯 플러그인을 함께 올려 명령·메인/worker·tick·@Async·JDBC commit/rollback·JPA/cache/PDC·인가·종료를 확인했다.
독립 Maven 소비 빌드와 고정 submodule 소비 빌드도 통과했다. 소비자 전체 529개 검사가 통과했다. 최신 실행 결과/해시/제약은 검증 데이터에 기록한다.

fixedDelay의 main 완료 대기를 제거한 mutant와 취소 Future의 callback fence를 제거한 mutant를
각각 해당 테스트가 실패로 검출했고 원본 복원 뒤 전체 라이브러리 build가 통과했다.
소비자에서는 DB 완료 전 Paper 반영 없음·재접속 stale 차단·lookup 재시도·quit 저장 flush·플레이어 직렬화·
월드 귀환 준비 완료 전 개인 만료 시작 금지를 확인한다. 기존 SQL/트랜잭션 구현은 변경하지 않았다.

이 개선은 기능별 task 필드·예약·취소·중복 실행 방지를 생략할 수 있다는 점에서 생산성을 높인다.
기능당 파일 수는 동일하며 복잡한 복구/세션 코드가 모두 줄지는 않았다.
개발 시간을 실측하지 않았으므로 단축률을 제시하지 않는다.
이번 단계에서 최신 소비자 본체의 Paper/Velocity native 기동은 다시 수행하지 않았다.
실제 Minecraft 클라이언트 로그인·인게임 메뉴/발전과제 화면·Discord OAuth·Velocity forwarding과
장기간 MSPT/메모리 성능은 이번 콘솔/API 검증으로 보장하지 않는다. 운영 개발계는 변경하지 않았다.
