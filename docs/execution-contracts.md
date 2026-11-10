# 실행 완료·취소·소유권

복사할 업무 코드는 [business-plugin](../examples/business-plugin/README.md)부터 본다.
일반 주기는 [@Scheduled](scheduling.md), Paper의 결과 반영은 관리형 Future를 사용한다.

| 경로 | 시간·스레드 | 반환 값과 종료 계약 |
|---|---|---|
| `PaperTasks.async/run/submitCompletable` | 선택된 worker, 기본 virtual-per-task | 라이브러리 소유 Future. 미실행 작업은 차단하고 실패 완료, 진행 작업은 제한 시간 drain |
| `PaperTasks.main(Runnable)` | 이미 main이면 즉시, 아니면 Paper 예약 | 라이브러리 소유 Future. 종료·예약 거절은 실패 완료 |
| `PaperTasks.nextTick(Runnable/Callable)` | 호출 위치와 무관하게 다음 tick | 같은 완료/취소 계약. Callable은 Paper에서 만든 값을 반환 |
| `PaperTasks.nextTick(source, handler)` | source 완료 뒤 다음 tick | source 대기부터 관리. 종료 시 source가 미완료여도 결과 실패 완료 |
| `Executor.execute`, `PaperTickScheduler.execute` | 각각 worker / 다음 tick | fire-and-forget. 호출자가 따로 만든 CompletableFuture 완료는 보장할 수 없음 |
| `@Scheduled` / `TaskScheduler` | 실제 시간. main은 명시한 scheduler 선택 | Spring 예약·예외·취소 규칙. main adapter는 실제 본문 완료까지 fixedDelay에 반영 |
| `PaperTickScheduler.runTaskLater/Timer` | 서버 tick | BukkitTask 취소와 native tick 의미 유지. 완료를 기다릴 작업은 관리형 경로 선택 |

## DB 결과를 안전하게 반영

```java
var read = playerTasks.supply(playerId, () -> repository.load(playerId));
var applied = execution.nextTick(read, (state, error) -> {
    if (closed || sessions.get(playerId) != session || !player.isOnline()
            || server.getPlayer(playerId) != player) return;
    if (error != null) throw new CompletionException(error);
    apply(player, state);
});
applied.whenComplete((ignored, error) -> { if (error != null) log(error); });
return applied;
```

PlayerTaskExecutor는 소비자의 플레이어별 직렬화 소유자이며 Spring Paper의 새 공개 타입이 아니다.
UI 취소는 upstream read/commit을 취소하지 않는다. session·revision·접속 객체 검사는 업무의 소유자에게 남는다.
에러를 handler에서 처리하고 정상 반환하면 결과는 성공이다. handler가 던지면 결과는 실패한다.
취소한 결과는 CancellationException, 종료/거절 결과는 TaskRejectedException으로 완료된다.
실행 전 취소는 native 작업을 취소하고 늦은 전달도 차단한다. 실행 중인 main은 interrupt하지 않는다.
이미 실행에 들어간 동작이나 이미 커밋한 I/O를 되돌리는 계약은 아니다.

취소를 소비자에게 노출하려면 **관리형 Future 자체**를 반환한다. `whenComplete` 등으로 새로 만든
종속 Future의 cancel은 원본 취소를 자동 전파하지 않는다. 로깅 observer를 붙이고 원본을 반환하는
패턴을 business-plugin이 사용한다. 플러그인보다 먼저 닫히는 기능은 TraceConversionService처럼
자기가 기다리는 관리형 Future를 취소한다. 플러그인 전체 종료를 위해 기능마다 pending 집합과
종료 훅·flush 선행 관계를 다시 만들 필요는 없다. 아래 main 종료 barrier가 공통 대기를 먼저 해제한다.

다음 코드는 callback을 막을 수 있어도 callerOwned의 완료를 보장하지 못한다.

```java
var callerOwned = read.thenAcceptAsync(this::apply, ticks);
```

Executor가 받는 것은 Runnable뿐이며 callerOwned는 전달되지 않는다. 종료 중 업무 Runnable을 실행해
억지로 Future를 깨우거나 JDK 내부를 reflection으로 조작하지 않는다. 이 한계는 회귀 테스트의 예상 결과다.
`handleAsync(..., ticks)`와 `thenRunAsync`도 같다. 완료를 기다릴 흐름은 위 관리형 continuation으로 전환한다.

## 소비자 worker 선택

기본은 기존과 같은 가상 스레드 per task, plugin TCCL, 5초 종료 예산이다.
풀/스레드 생성·대기·남은 작업 interrupt는 표준 SimpleAsyncTaskExecutor가 담당한다.
`spring.paper.lifecycle.shutdown-timeout-seconds`가 예산이며 0은 즉시 중단 정책이다.
Spring의 task tracking을 켜기 위해 0일 때 내부 대기 값은 1ms, cancelRemainingTasksOnClose=true다.

기존 실행기를 사용할 때 역할 qualifier를 선언한다. main/scheduler/security timer를 자동으로 worker로
추측하지 않는다. 이름 `paperWorkerExecutor`로 제공해도 같은 표준 qualifier 선택을 따른다.

```java
@Bean(destroyMethod = "close")
@Qualifier(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME)
SimpleAsyncTaskExecutor applicationWorker(TaskDecorator decorator) {
    var worker = new SimpleAsyncTaskExecutor("my-worker-");
    worker.setVirtualThreads(true);
    worker.setTaskTerminationTimeout(5000);
    worker.setTaskDecorator(decorator);
    return worker;
}
```

일반 Executor·AsyncTaskExecutor·FactoryBean 제품도 역할에 제공할 수 있다. 복수 후보의 @Primary·@Fallback은
Spring이 선택한다. TaskDecorator 빈은 라이브러리 기본 backend에 순서대로 합성한다. 공급한 backend의
decorator는 소비자가 설정하며 두 번 적용하지 않는다. 트랜잭션·인증 ThreadLocal을 임의로 전파하지 않는다.
Spring 프록시를 거치는 `@Async("paperTasks")`도 같은 worker·관리형 submitCompletable을 쓴다.

PaperTasks는 외부 backend를 닫거나 interrupt하지 않는다. 자기에게 들어온 진행 작업만 예산 내 기다린다.
외부 singleton 재사용이면 원 소유자가 종료한다. 컨텍스트의 일반 @Bean이면 Spring의 그 빈 정의가 종료한다.
기본 backend는 PaperTasks가 main 대기 해제 뒤 먼저 drain하며 Spring 파괴 시 재호출은 멱등적이다.
2인자 생성자는 단독 사용 호환용으로만 남기고 Spring에서는 주입한다. 3인자 생성자는 backend를 빌린다.

## 종료·남는 한계

입력 차단 → 예약 생산 중단·native tick 취소 → main 대기 실패 완료 → 사용자 main 종료 훅·DB flush →
worker drain → Spring 파괴 순서다. 동기 `onDisable` 중에는 다음 tick을 실행할 수 없다. main 결과를
사용자 flush보다 먼저 실패시켜, 같은 플레이어 큐의 앞에서 main을 기다리던 worker가 최종 DB 저장을
막지 않게 한다. source가 미완료인 관리형 continuation도 이때 실패하지만 source I/O는 취소하지 않는다.

Bootstrap이 `PaperTasks.quiesceMainThread()`를 Paper main에서 자동 호출한다. 일반 소비자는 새 종료
단계를 등록하지 않는다. 이 인프라 barrier 뒤에는 `nextTick`과 worker→main 예약이 실패한다.
이미 main인 종료 훅의 `main(Runnable)`은 즉시 정리할 수 있고, worker는 최종 DB-only 저장을 계속
받는다. 최종 `close()`가 모든 main 실행을 거절하고 worker를 drain한다. 단독 호스트가 bootstrap을
대신할 때만 같은 barrier→최종 저장→close 순서를 소유하며, off-main barrier 호출은 거절한다.
이 순서는 부분 startup 실패의 정리에도 같다. 종료 중 업무 callback을 대신 실행하지 않는다.

Spring `ContextClosedEvent`는 worker drain 뒤 실제 context 파괴 단계에 남긴다. 이 event를 미리
발행하면 일반 Spring executor가 새 작업을 거절하여 최종 DB flush가 제출되지 않을 수 있다.
업무 종료의 최종 저장은 DB-only completion이어야 한다. Paper main에서 worker→main 결과를 join하면
실행 중에도 교착하므로 금지한다. 세션·revision·이미 수락한 commit의 복구 여부는 도메인 소유자가 판단한다.
공유 executor에 무한 대기하는 I/O를 안전하게 강제 중단할 수 없다. 예산을 넘으면 관리형 결과는 실패해도
실제 I/O는 나중에 커밋할 수 있다. DB timeout·트랜잭션·멱등성/outbox는 소비자 저장 계약으로 유지한다.

Spring 소스 7.0.10의 SimpleAsyncTaskExecutor.close, TaskExecutorAdapter, AsyncTaskExecutor,
AsyncExecutionInterceptor를 확인했다. [공식 실행 문서](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)와
[SimpleAsyncTaskExecutor 계약](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/core/task/SimpleAsyncTaskExecutor.html)을 따른다.
