# 예약과 비동기 작업

`@EnableScheduling`을 애플리케이션 Configuration 한 곳에 둔다. Spring의
[Task Execution and Scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)을 그대로 사용한다.

## 단순 유지보수

```java
@Service
@RequiredArgsConstructor
public final class CleanupService {
    private final CleanupRepository repository;

    @Scheduled(fixedDelayString = "${mail.cleanup-delay:PT5M}")
    public void cleanup() { repository.cleanup(); }
}
```

Task 필드·start·cancel·close·worker 제출·중복 실행 플래그가 필요 없다. 업무 메서드는 백그라운드에서
동기적으로 완료한다. Future를 제출만 하고 반환하면 fixedDelay가 DB 완료를 뜻하지 않게 된다.
일반 Spring 예외 로깅/반복 정책을 따른다. 보존·재시도 안내 등 업무별 로그는 메서드 안에서 처리한다.

무지정 작업은 소비자가 제공한 기본 TaskScheduler를 우선 선택한다. qualifier 전용 스케줄러는
`@Bean(defaultCandidate = false)`로 선언한다. 기본이 없으면 Spring Paper의 지연 생성된
ThreadPoolTaskScheduler가 사용된다. 소비자가 제공한 ScheduledExecutorService가 있으면
ConcurrentTaskScheduler로 재사용하며 backend 종료는 그 빈의 소유자에게 맡긴다.
다중 기본 후보는 Spring 표준 @Primary/이름 `taskScheduler`로 선택한다. 임의로 하나를 고르지 않는다.
소비자 `@Fallback` 기본과 non-fallback 전용 timer를 함께 제공할 때의 직접 타입 조회 순서와
명시 선택 방법은 [기본 빈 계약](default-beans.md)을 따른다.

## Paper 메인 스레드의 실제 시간 작업

```java
@Scheduled(scheduler = PaperMainThreadTaskScheduler.BEAN_NAME,
        initialDelayString = "PT1S", fixedDelayString = "PT1S")
public void checkWorldCycle() { /* 빠른 Paper 상태 검사 */ }
```

이 스케줄러는 실제 시간 기준이다. 타이머 작업자는 Paper 콜백의 완료를 기다리지만 Paper 스레드는
타이머를 기다리지 않는다. fixedDelay·Trigger의 lastCompletion은 실제 Paper 실행 완료를 반영한다.
타이머 하나가 직렬로 전달하므로 메인 스레드가 지연되어도 주기마다 미실행 콜백을 쌓지 않는다.
서로 다른 주기 메서드도 같은 타이머를 공유한다. 긴 Paper 작업은 나머지 작업도 지연시키므로 금지한다.
fixedRate는 지연 후 따라잡기가 가능하다. 보통 상태 확인에는 fixedDelay를 사용한다.

```java
public RetryService(@Qualifier(PaperMainThreadTaskScheduler.BEAN_NAME) TaskScheduler scheduler) {
    this.scheduler = scheduler;
}
// 조건부 재시도는 업무가 소유한다. 대체 예약을 만들기 전에 이전 Future를 취소한다.
retry = scheduler.schedule(this::retry,
        scheduler.getClock().instant().plus(Duration.ofSeconds(5)));
```

메인 예약의 cancel은 대기 콜백을 취소하고 늦게 도착한 전달도 차단한다. 실행 중인 Paper 스레드를
interrupt하지 않는다. 자체 취소·조건부 재시도에는 Future 필드가 여전히 필요하다.

## 서버 tick으로 진행하는 게임 동작

전투·이동·애니메이션은 PaperTickScheduler의 runTaskLater/runTaskTimer를 쓴다. 100 ticks는
서버가 느려지면 5초보다 길어진다. @Scheduled의 PT5S와 서로 치환할 수 있는 단위가 아니다.
BukkitRunnable overload는 native task identity와 자체 cancel을 보존한다. 일반 Runnable/Consumer와
같이 플러그인 종료 때 취소되지만 native Bukkit 예외/호출 문맥을 유지한다.

## DB 조회 후 플레이어 갱신

```java
var read = playerTasks.supply(playerId, () -> repository.load(playerId));
var applied = execution.nextTick(read, (state, failure) -> {
    if (closed || sessions.get(playerId) != session || !player.isOnline()
            || server.getPlayer(playerId) != player) return;
    if (failure != null) throw new CompletionException(failure);
    apply(player, state);
});
applied.whenComplete((ignored, error) -> { if (error != null) log(error); });
return applied;
```

playerTasks는 소비자의 PlayerTaskExecutor이며 플레이어별 직렬화를 유지한다. execution은 PaperTasks다.
이 세션/접속 검사는 소비자 책임이다. nextTick(source, handler)는 source 대기부터 결과를 관리하므로
종료 시 source가 끝나지 않아도 결과를 실패 완료한다. 취소는 원본 DB 작업으로 전파하지 않는다.

`thenAcceptAsync/handleAsync(..., PaperTickScheduler)`는 callback을 차단해도 호출자가 만든 종속 Future를
완료할 수 없다. 완료를 기다릴 흐름에는 사용하지 않는다. raw execute와 관리형 경로의 차이,
외부 Executor/TaskDecorator 제공과 종료 소유권은 [실행 계약](execution-contracts.md)을 따른다.
main(Runnable)은 이미 main이면 즉시 실행하고 nextTick은 항상 다음 tick이다. 이 차이를 유지한다.

`@Async("paperTasks")`는 @EnableAsync와 프록시를 거치는 다른 빈 호출이 필요하다. final 클래스/메서드,
private 메서드, self-invocation 제약은 [Spring AOP](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)와 같다.
예제 scheduling-plugin의 AsyncProbeService는 이 방식을 보여준다. 레시피 동기화에는 추가 프록시 빈과
직렬화 우회 비용이 생겨 적용하지 않았다. 모든 비동기 코드를 어노테이션으로 바꾸지 않는다.

## 시작·종료와 소유권

일반 빈 초기화·종료는 Spring의 @Bean·@PostConstruct·@PreDestroy·SmartLifecycle을 사용한다.
Paper 상태 초기화/정리는 OnPaperMainThreadStartup/Shutdown을 사용한다. 이 훅은 worker refresh 뒤의
Paper 전용 경계다. 단순 주기 작업만을 위해 빈 훅을 만들지 않는다.

종료는 입력 해제 → 사용자 flush → ScheduledAnnotationBeanPostProcessor.destroy로 주기 생산 중단 →
메인 예약·native tick·청크 lease 취소 → PaperTasks의 대기 main Future 실패·worker drain →
Spring lifecycle 정지와 빈 파괴 순서다. Spring이 관리하는 background scheduler는 SmartLifecycle이
실행 중인 작업의 완료를 기다린 후 저장소 빈을 파괴한다. 새로운 종료 프레임워크는 만들지 않았다.
부분 시작 실패도 이미 생성된 singleton만 정리한다. 정리를 위해 새 빈을 생성하지 않는다.

Paper 스레드에서 worker가 main 실행을 기다리는 Future를 join하지 않는다. 종료 훅의 DB-only flush는
별도 경계이며 메인 콜백을 기다리지 않아야 한다. DB/네트워크 자체의 제한 시간도 소비자가 설정한다.
임의로 멈추지 않는 외부 I/O를 강제로 안전하게 완료한다고 보장하지 않는다.

## 구현 근거와 회귀 검증

Spring 7.0.10 source JAR에서 ScheduledAnnotationBeanPostProcessor, TaskSchedulerRouter,
ThreadPoolTaskScheduler, ExecutorConfigurationSupport, ExecutorLifecycleDelegate, DefaultListableBeanFactory를 확인했다.
시간 계산·Trigger·예외 정책·작업자 lifecycle은 Spring 구현을 사용한다. Paper 전달·취소·소유권만 연결한다.
특히 단 하나의 qualifier 전용 빈도 resolveNamedBean으로 선택될 수 있어, 기본 background bean이 필요했다.
Security는 이름으로 분리해 소비자 유지보수의 지연이 접속 타임아웃을 막지 않게 한다.

PaperMainThreadTaskSchedulerTest는 실제 타이머와 제어 가능한 Paper 경계로 완료 시간·대기량·취소·로더를
검증한다. ScheduledShutdownTest는 실제 Spring 컨텍스트에서 메인 대기 worker와 부분 시작 실패를 검증한다.
StandardAsyncTest는 실제 Spring 프록시의 외부 호출과 self-invocation 차이를 검증한다.
