# 복사해서 쓰는 업무 예제

우편함 개수 조회·만료 정리라는 작은 업무를 사용한다. 검증용 counter·준비 Future·start/stop 없이
Spring의 @Scheduled, JDBC, 조건부 구성과 관리형 main 반영을 보여준다.
[공통 빌드/실행 방법](../README.md#빌드와-실행)대로 빌드한 뒤 콘솔/플레이어가 `paper-inbox`를 실행한다.
콘솔은 예약된 UUID 0의 개수를 조회하며 기본 결과는 `Pending mail: 0`이다.

## 먼저 복사할 부분

기존 Repository가 있다면 업무 서비스와 주기 메서드만 추가하면 된다.

```java
@Scheduled(fixedDelayString = "${example.inbox.cleanup-delay:PT1H}")
public void deleteExpired() { repository.deleteExpired(); }
```

기본 실행 위치는 background다. DB 완료 뒤 다음 지연을 계산한다. 별도 task 필드·start/close·취소나
worker 제출이 필요 없다. Paper 작업은 `scheduler = PaperMainThreadTaskScheduler.BEAN_NAME`을 명시한다.
실제 시간의 PT1H와 서버 tick은 다른 단위다.

```java
var result = execution.nextTick(execution.async(() -> repository.pending(owner)), (count, error) -> {
    if (sender instanceof Player player && (!player.isOnline() || server.getPlayer(owner) != player)) return;
    if (error != null) throw new CompletionException(error);
    sender.sendPlainMessage("Pending mail: " + count);
});
result.whenComplete((ignored, error) -> { if (error != null) log(error); });
return result;
```

PaperTasks는 source 대기부터 반영까지 결과를 관리한다. 종료/예약 거절은 실패 완료하고 실행 전 취소는
UI 콜백을 차단한다. 원본 DB 작업은 취소하지 않는다. 기능별 session/revision/직렬화 규칙은 업무에서 유지한다.
취소가 원본으로 전달되도록 `whenComplete`가 만든 종속 결과 대신 관리형 result를 반환한다.
[일반 Executor 경로의 한계와 종료 계약](../../docs/execution-contracts.md)을 확인한다.

## 필요한 경우에만 복사할 구성

- BusinessWorkerConfiguration: 표준 SimpleAsyncTaskExecutor와 TaskDecorator로 locale을 전파한다.
  `@Qualifier(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME)`로 worker 역할을 지정한다.
  이 import를 생략하면 라이브러리의 기본 가상 스레드 실행기를 사용한다. Spring이 공급한 빈의 close를 소유한다.
- InboxConfiguration: 소비자 소유 H2 DataSource와 plain 업무 빈. DB 교체는 이 구성에서 한다.
- maintenanceJdbc: `defaultCandidate=false`로 전용 query timeout을 정한다. cleanup만 qualifier로 사용하고
  일반 JdbcOperations/JdbcClient는 라이브러리 기본을 사용한다. 전용 빈이 기본 빈을 가로막지 않는다.
- InboxEnabled: `example.inbox.enabled`, 누락 false / 예제 YAML true. app 내부 기능에는 Module SPI를 만들지 않는다.
- InboxCommand/Listener: 수동 등록 코드가 없으며 직접 scan에도 같은 조건을 적용한다.

`example.inbox.enabled=false`이면 DB·업무·예약·입력 빈이 없다. 빌드 시 `plugin.yml`의 paper-inbox 정의는
남아 있다. 활성 빈이 없어 실제 실행기는 연결되지 않는다. 설정을 실행 중 바꿔도 동적으로 제거하지 않는다.
[조건의 범위와 다른 등록 경로](../../docs/optional-features.md)를 참고한다.

이 예제의 파일 수는 업무 구현/입력/구성을 명시하기 때문에 한 파일짜리가 아니다. 기존 앱에 단순 주기 하나를
추가할 때 이 모든 구성을 매번 복사할 필요는 없다. 별도 검증 장치는
[scheduling-plugin](../scheduling-plugin/README.md), 트랜잭션 커밋/롤백은 [jdbc-plugin](../jdbc-plugin/README.md)을 본다.

BusinessExampleTest는 실제 Spring·H2·타이머·소비자 executor와 제어 가능한 Paper 전달 경계를 사용한다.
DB→main의 완료·오래된 접속·종료·소비자 decorator·기본/전용 JDBC 공존·기능 비활성의 빈 부재를 검증한다.
실제 클라이언트 접속 검증은 별도다.
