# 실행·등록·보안 독립 검토 — 2026-11-11

독립 검토 기준은 `786735c`다. 마지막 diff뿐 아니라 core 47개, security-core 20개,
security 15개의 운영 Java 소스 82개를 읽고 공개 계약·내부 상태·Spring 경계와 대조했다.
데이터 모듈·Gradle 플러그인·소비자 게임 규칙의 독립 감사는 이 문서의 범위가 아니다.
버전·설치·실행 방식·배포를 바꾸지 않았으며 개발 서버 재시작과 운영 데이터 변경도 하지 않았다.

## 확인하여 수정한 결함

| 심각도 | 재현과 영향 | 최소 수정과 관찰한 결과 |
|---|---|---|
| P2 | `shutdown()`이 연결/cleanup 스냅샷을 만든 뒤 연결마다 `disconnect()`를 호출했다. 첫 invalidation 관찰자가 다른 연결을 끊으면 그 terminal handler의 pending/실패 ACK가 종료 스냅샷에 없고, 후속 owner 없는 disconnect는 즉시 끝나 종료 성공으로 누락됐다. | 기존 invalidate를 admission 준비와 외부 효과 실행으로 나눴다. 종료는 모든 로컬 철회와 ACK를 같은 lock 경계에서 예약하고 효과를 락 밖에서 실행한다. 재진입으로 시작한 다른 연결의 terminal handler가 끝날 때까지 종료가 대기하고 그 실패를 반환한다. |
| P2 | 미완료 `create()`를 기다리는 연결을 끊거나 만료/종료하면 `create=false` 확인 전에 `invalidate()`가 전송됐다. 다른 컨텍스트가 이미 가진 정확히 같은 접속 row가 삭제되어 기존의 실패한 생성 소유권 계약을 위반했다. | Managed에 `create=true`를 관찰한 소유권만 기록한다. 미확정 소유 연결의 자동 정리는 row를 삭제하지 않는다. 늦은 true는 기존 discard 경로로 정리하고 false는 기존 row를 보존한다. |

두 결함 모두 구현을 바꾸기 전에 실패하는 테스트로 재현했다.
첫 RED는 `shutdownRetainsCleanupStartedByAnInvalidationObserverAfterItsAdmissionSnapshot`의
`shutdown.isDone()`이 true였고 다른 연결의 terminal handler는 미완료였다.
둘째 RED는 `disconnectBeforeRejectedCreateAcknowledgementNeverDeletesAnotherContextsRecord`에서
기존 row 조회가 `NoSuchElementException`으로 실패했다.

추가한 6개 테스트는 callback 재진입, 다른 스레드의 반복 shutdown, 동기 handler 실패 뒤 나머지 정리,
disconnect/shutdown/timeout 전에 도착하지 않은 false, 저장 완료 후 true 응답 전의 disconnect를 다룬다.
성공 경로만 확인하지 않고 저장소의 독립 상태·인증 이벤트 부재·pending ACK·실패 원인과 재호출 결과를 확인한다.

구현은 [PaperConnectionSecurityService](../spring-paper-security-core/src/main/java/org/blu3fishez/springpaper/security/connection/service/PaperConnectionSecurityService.java),
재현은 [종료 경쟁](../spring-paper-security-core/src/test/java/org/blu3fishez/springpaper/security/connection/service/ConnectionShutdownRaceTest.java)과
[생성/실패 소유권](../spring-paper-security-core/src/test/java/org/blu3fishez/springpaper/security/connection/service/ConnectionFailureTest.java)에 있다.

## 조사한 경계와 SOLID 판단

| 경계 | 검토 내용과 판단 |
|---|---|
| 호스트와 Spring | Paper가 만든 JavaPlugin/Server를 재생성하지 않고 singleton으로 연결한다. context 생성과 refresh의 plugin TCCL, 서버 resource fallback, main 시작/종료 제한을 확인했다. Spring bean 생성·proxy·일반 destroy를 별도 DI 컨테이너로 대체하지 않는다. |
| 발견·계획·등록 | PaperBeanCatalog는 metadata 발견, lifecycle/event plan은 유효한 그래프, manager/registrar는 실행·native token을 소유한다. eager singleton 제약과 JDK proxy의 invocable method 검증, 부분 시작 정리, command 기존 binding 복구를 대조했다. |
| main 실행과 worker | main dispatcher가 소유 Future·source 대기·native handle publication을 관리한다. worker는 admission·완료 ledger만 소유하고 실제 실행·대기·interrupt는 Spring backend 또는 원 소유자에게 남긴다. 종료 시 main을 먼저 실패시키고 worker를 drain하는 방향을 확인했다. |
| 실제 시간과 tick | PaperMainThreadTaskScheduler가 표준 timer와 main 완료를 연결하여 fixedDelay가 본문 완료 뒤 시작된다. tick scheduler는 native BukkitTask identity와 delay/period를 유지한다. 예약 생산자 취소와 main Future 종료 뒤 Spring lifecycle/destruction으로 넘어간다. |
| native 이벤트 관계 | 같은 HandlerList/priority와 inactive-role 필터를 기준으로 하나의 DAG를 검증한다. marker `first` 그룹 내부에 새 edge를 만들지 않는다. 독립 기능별 `first` 호출은 숨은 prefix 순서가 되므로 공통 pipeline이 그 prefix를 소유해야 한다. 새 phase/rank 프레임워크가 필요하지 않았다. |
| 보안 결정과 효과 | Spring AuthenticationManager/ProviderManager와 AuthorizationManager를 재사용한다. 필터/저장소/handler SPI와 service 상태 전이 소유권이 분리된다. 인증을 ThreadLocal 세션 저장소로 쓰지 않고 CAS 뒤 공개하며, 늦은 완료·local fence·snapshot TTL을 확인했다. 이번 두 수정도 기존 Managed/cleanup 소유권 안에 한정했다. |
| Paper 인증 adapter | transport 주소와 Player identity, 기존 입장 거절 보존, 로컬 철회/새 revision의 snapshot 차단, async resolver의 Paper 권한 조회 회피를 대조했다. 게임 제한·외부 OAuth 증거·계정 정책을 라이브러리에 넣지 않는다. |

모든 내부 collaborator에 인터페이스/SPI를 추가하거나, executor·session map·Spring lifecycle owner를
중복 생성할 근거는 찾지 못했다. 이 판단은 검토한 책임과 실패 재현에 대한 것이며 모든 조합의 SOLID
준수나 thread 안전성을 테스트 통과만으로 선언하는 것이 아니다.

## 확인한 표준 구현

고정 버전의 공식 source JAR를 직접 읽어 다음을 대조했다.

- Spring 7.0.10 `AbstractApplicationContext.doClose`: close event, lifecycle 정지, singleton 파괴 순서.
- Spring 7.0.10 `ExecutorConfigurationSupport.onApplicationEvent`: 기본 executor의 조기 admission 중지.
- Spring 7.0.10 `SimpleAsyncTaskExecutor.close`: task tracking·대기 예산·남은 interrupt.

공식 [context 종료 소스](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java),
[executor 종료 소스](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/scheduling/concurrent/ExecutorConfigurationSupport.java),
[worker 종료 소스](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-core/src/main/java/org/springframework/core/task/SimpleAsyncTaskExecutor.java)에 해당한다.

## 검증과 남은 한계

`Java 25`에서 다음을 `--max-workers=2`로 통과했다.

```text
./gradlew :spring-paper-core:test :spring-paper-security-core:test :spring-paper-security:test verifySourceSize
./gradlew :spring-paper-security-core:build verifySourceSize
```

관련 모듈의 XML 결과는 core 109개, security-core 41개, security 13개, 총 163개이며 실패·오류·skip은 0개다.
변경 없는 core 테스트는 Gradle의 up-to-date 검증 결과를 재사용했다. 보안 두 모듈 테스트와 source-size 검사는 실행됐다.
변경 모듈 build의 Javadoc·sources JAR·assemble도 통과했다.
라이브러리 전체 build·독립 소비자 산출물 검증은 상위 작업에서 별도로 집계한다.

raw Executor에 전달되지 않은 caller-owned Future의 완료 보장, 이미 진행한 I/O의 중단/rollback,
main에서 worker→main 결과를 join하는 사용자의 교착은 해결 계약에 넣지 않았다.
create가 미응답이거나 실제 커밋 뒤 실패한 경우에는 소유권을 추측해 다른 row를 삭제할 수 없다.
늦은 성공 응답의 정리는 시도하지만, 응답 없이 프로세스가 끝나는 경우까지 완료 ACK가 의미하지 않는다.
외부 저장소의 TTL·멱등성·불확정 커밋 조정은 여전히 adapter 책임이다.

이 검토의 제어 가능한 Paper 경계와 Spring 통합 테스트는 실제 Minecraft 클라이언트,
MariaDB/Redis 네트워크 장애, 장시간 메모리·thread 부하, 모든 외부 executor/FactoryBean 조합을 대신하지 않는다.

## 소비자 flush에서 발견한 main 종료 순서 보완

이후 실제 SimpleExtraction UUID 큐와 recipe quit save를 대조하여 기존 설명의 빈틈을 재현했다.
`PaperTasks.close()` 자체는 main을 먼저 실패시키지만 bootstrap이 이를 사용자 종료 훅 뒤에 호출했다.
앞선 worker가 다음 tick의 projection을 기다리면 사용자 recipe flush가 같은 큐 뒤에서 교착했다.
기능마다 관리형 main Future를 추적하고 flush보다 먼저 닫는 패치는 같은 공통 책임을 반복한다.

기존 dispatcher의 pending ledger와 facade를 유지하고 `quiesceMainThread()` barrier 한 개를 추가했다.
Bootstrap은 입력과 예약 생산자·native tick을 중단한 뒤 barrier를 호출하고 사용자 종료 훅을 실행한다.
barrier는 대기 중인 관리형 결과를 실패시키며 source와 worker admission은 유지한다. main 종료 훅의
즉시 정리도 유지하고, 사용자 DB flush 뒤 기존 close가 worker를 drain한다. 새 executor·Future 집합·
Spring lifecycle 대체 계층·phase 표는 없다. `Lifecycle.stop/isRunning`에 main-only 의미를 넣으면
worker는 여전히 작업을 받으므로 그 표준 계약으로 위장하지 않았다.

일반 소비자는 기존 `nextTick`을 사용하면 된다. 기능별 UI pending 집합·전체 종료용 close·recipe/guide
선행 관계를 제거할 수 있는 만큼 복잡도가 줄며, 그 코드를 다른 기능별 설정으로 옮긴 것이 아니다.
플러그인보다 먼저 닫히는 기능의 취소·세션/revision fence·내구성 복구는 실제 도메인 책임으로 남는다.
Spring 7.0.10 `doClose`와 `ExecutorConfigurationSupport.onApplicationEvent`를 다시 읽어, 표준 close
event를 조기 발행하면 consumer executor가 최종 DB 제출을 거절할 수 있음을 확인했다.

`MainThreadShutdownBarrierTest` 3개를 수정 전에 실행하여 모두 실패시켰다. 실제 종료 훅에서 같은
큐를 잇는 최종 저장이 timeout됐고, 부분 startup 정리도 실패했으며, 미완료 source의 UI 결과가
종료 훅에 열린 채 도착했다. 수정 후 세 경로와 이미 수락한 DB commit·뒤따르는 저장·자원 파괴,
stale native callback 차단·source 비취소·inline main 정리를 확인했다. off-main 거절과 재진입 중인
main 정리도 별도 검증했다. Java 25에서 `:spring-paper-core:test verifySourceSize --max-workers=2`가
통과했으며 core 테스트 114개는 실패·오류·skip이 없다. 실제 게임 서버나 네트워크 검증은 추가하지 않았다.
