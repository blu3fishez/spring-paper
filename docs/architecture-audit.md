# 라이브러리 책임·사용성 점검 — 2026-11-11

기준 커밋은 `70f25e4`다. 9개 모듈의 운영 Java 소스 117개와 독립 소비 예제 6개의
Java 소스 59개를 읽고, 테스트 목록·기존 실행/기본 빈/사용성 계약과 대조했다.
아래 판단은 이 소스와 재현 범위에 대한 것이다. 테스트 통과를 모든 조합의 안전성으로 해석하지 않는다.
Java 25, Paper 26.3, Spring 7.0.10, Data 2026.0.2, Security 7.1.2,
Hibernate 7.4.13, Lombok 1.18.48은 바꾸지 않았다.

## 책임별 조사 범위

| 모듈 | 운영 Java 파일 | 소유 책임과 검토 지점 | 대표 검증 |
|---|---:|---|---|
| config | 3 | YAML 안전 로딩·Spring Environment 연결, 모듈 공용 internal 기본 후보 판정 | 중복 키/객체 생성 거절, 환경 우선순위, 기본 후보 회귀 |
| core | 47 | Paper 호스트 bootstrap, 입력 등록, main lifecycle, 실행/예약/청크 lease | 실제 Spring 컨텍스트·프록시·timer와 제어 가능한 Paper 경계 |
| data-jdbc | 3 | 표준 JDBC facade 기본 구성, 소비자 DataSource/매니저 재사용 | H2 커밋/롤백·batch, 기본/전용·Primary/Fallback·FactoryBean |
| data-jpa | 4 | 선택 Repository scan, 기본 EMF/풀, 기존 자원 선택·worker-only 매니저 | 실제 Spring Data/Hibernate/H2, JDBC 공유 트랜잭션, 기존 EMF 재사용 |
| data-pdc | 17 | holder adapter·record mapping·명시적 repository scan | 타입/누락/변환 실패, staged write, item edit·다른 키 보존·thread 제한 |
| cache | 2 | 표준 Spring Cache 활성화·선택 Caffeine 기본 | 실제 cache advice, commit/rollback 반영, 사용자 resolver/key/manager |
| security-core | 20 | 호스트 중립 세션 전이·CAS·deadline·필터 연결·종료 ACK | 실패/경쟁·늦은 완료·TCCL·전용 timer·Spring 종료 순서 |
| security | 15 | Paper 입력 인증 문맥·접속 association·snapshot·선택 입장 adapter | 접속 identity·새 revision/철회·stale snapshot·인가·이전 거절 보존 |
| gradle-plugin | 6 | ASM 메타데이터·descriptor·패키징·개발 run-paper 구성 | 메타데이터 병합, 실제 Gradle TestKit의 입력/캐시/구성 캐시 |

테스트 Java 파일 62개에는 fixture가 포함된다. 현재 라이브러리 테스트는 206개다.
예제는 라이브러리 루트의 하위 프로젝트가 아니라 각각 included build를 사용하는 독립 소비자다.
예제 build와 native 서버 검증 결과를 루트의 206개에 합쳐 세지 않는다.

## 확인하여 수정한 결함

| 심각도 | 재현과 영향 | 최소 수정·독립 결과 검증 |
|---|---|---|
| P2 | `GeneratePluginDescriptor`가 ambient Spring 환경으로 이름/권한 등을 해석하면서 그 결과를 task 입력에 포함하지 않았다. 사용한 `-D` 값이 바뀌어도 `UP_TO_DATE`로 이전 descriptor가 남았다. | 공개 descriptor에 실제 기록하는 Map을 `@Input`으로 제공한다. 값 변경 시 재생성, 무관한 속성 변경 시 up-to-date, 출력 삭제 시 올바른 build cache 복원을 TestKit으로 검증했다. |
| P2 | `PaperConnectionSecurityService.shutdown()`이 `disconnect()` 반환값 대신 변경 중인 in-flight 집합만 읽었다. 즉시 실패한 삭제/terminal handler는 집합에서 빠져 shutdown이 성공했다. | 종료 전에 진행하던 cleanup과 종료가 직접 시작한 각 ACK를 보존한다. 원격 삭제 실패와 handler 실패를 각각 종료 실패로 관찰하며, 로컬 철회와 나머지 삭제 시도는 유지한다. |
| P3 | `PaperCommandRegistrar.register()`가 close 이후 새 native binding을 획득할 수 있었다. 이후 close는 멱등적 반환이라 이 연결을 해제하지 않았다. 정상 bootstrap의 일회 등록 밖에서 발생하는 공개 API 문제다. | listener registrar와 같은 닫힘 검사를 입력 전에 둔다. 거절 뒤 기존 executor/completer와 host 호출 부재를 확인한다. |

세 결함은 먼저 실패하는 회귀 테스트로 재현했다. descriptor fixture는 공유 Gradle 캐시의 이전 결과에
의존하지 않도록 자기 임시 cache directory를 사용한다. 환경 변수 전체나 시스템 속성 전체를
fingerprint하거나 출력하지 않으며, 기존 Spring placeholder 해석은 그대로 사용한다.

관련 구현은 [descriptor](../spring-paper-gradle-plugin/src/main/java/org/blu3fishez/springpaper/gradle/GeneratePluginDescriptor.java),
[보안 종료](../spring-paper-security-core/src/main/java/org/blu3fishez/springpaper/security/connection/service/PaperConnectionSecurityService.java),
[명령 등록](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/command/PaperCommandRegistrar.java)에 있다.

## SOLID와 확장 경계 판단

Bootstrap은 Paper가 만든 객체를 연결하고, Spring이 소비자 빈의 생성·DI·proxy·일반 lifecycle을 소유한다.
호스트 singleton을 다시 만들거나 IDE용 가짜 빈을 등록하지 않는다. `PaperBeanCatalog`는 발견,
event/lifecycle plan은 metadata 검증·순서, registrar/manager는 플랫폼 반영을 맡는다.
부분 시작에서 시도한 소유자만 정리하며, main 전용 shutdown과 Spring destroy의 이중 소유를 거절한다.

실행은 `PaperTasks`의 작은 public facade, main 완료 dispatcher, worker admission/완료 ledger,
typed Spring 구성으로 나뉜다. 스레드 생성·pool·일반 timer의 시간 계산과 lifecycle은 Spring 구현이다.
외부 Executor를 닫지 않는 소유권과 [관리형 Future 계약](execution-contracts.md)을 유지한다.
이미 있는 executor/decorator/싱글턴을 재사용할 경계가 있고, 내부 collaborator마다 SPI를 만들지는 않는다.
취소/종료 bookkeeping을 소비자가 다시 구현하게 하는 새 실행 framework도 추가하지 않았다.

입력 interceptor·module 제공자·보안 provider/repository/handler는 실제 변형이 있는 SPI다.
소비자가 호출하는 `PaperTasks`, `PdcRepository`, 접속 보안 서비스와 구별하여 방향을 문서화한다.
공용 모듈은 게임 도메인 구현을 알지 않는다. security-core에는 Bukkit/Velocity 의존성이 없다.
보안 service는 접속 state/operation의 동기화 소유자이며, 저장소·인증·transport 효과는 경계 밖이다.
이번 ACK 결함을 고치기 위해 두 번째 세션 map이나 별도 shutdown owner를 만들지 않았다.

JDBC/JPA/Cache의 메서드 advice와 트랜잭션은 Spring에 맡긴다. `DefaultBeans`는 Spring의 실제 후보
resolver를 호출하는 모듈 공용 internal 지원이며 소비자 확장점이 아니다. `defaultCandidate=false`
전용 빈이 일반 기본을 억제하지 않고, Primary/Fallback·FactoryBean 제품 선택은 Spring 규칙을 따른다.
JPA의 명시적인 다중 EMF/매니저 이름 선택과 외부 자원의 종료 소유권은 유지했다.
PDC는 실제 차이가 있는 Entity/Item/container adapter와 Paper `PersistentDataType`을 사용한다.
게임 검증·엔티티 검색·스키마 이관을 repository mapping에 넣지 않는다.

## 중앙 기능 목록 없이 이벤트 조립

`PaperEventOrderConfigurer`는 여러 Spring 빈으로 제공할 수 있다. 기능별 구성은 자신이 소유한
관계만 기술하고, 공통 입력 파이프라인 구성은 실제로 공유하는 공개 단계 계약만 선택하면 된다.
기존 `first(priority, types...)`는 Listener 하위 인터페이스를 구현한 그룹도 선택한다.
같은 그룹의 멤버 사이에는 새 edge를 만들지 않고, 뒤 그룹과 나머지 handler보다 앞에 둔다.

`first`의 여러 호출은 prefix를 이어 붙인다. 독립 기능마다 `first`를 선언하는 방식은 configurer의
발견 순서를 숨은 전역 순서로 만들 수 있다. 공유 prefix는 그 파이프라인의 구성 한 곳이 소유하고,
기능 내부 관계에는 event/priority로 좁힌 `before` 또는 메서드 관계를 사용한다.
모든 관계는 같은 graph에서 native priority·HandlerList·cycle 검증을 받는다.
현재 소비자 조립은 이 공개 API로 표현되므로 새 rank/phase DSL이나 instance selector는 추가하지 않았다.

`EventOrderingTest`에 공개 marker 하나가 여러 구현/인스턴스를 선택하는 회귀를 추가했다.
기존 테스트의 inactive role, 상속 이벤트의 공유 HandlerList, annotation/구성 관계 사이 cycle도 유지한다.
게임의 어떤 snapshot·취소·damage 단계가 필요한지는 라이브러리가 정하지 않는다.

## 개발자가 추가하는 것과 계속 소유하는 것

| 추가할 업무 | 보통 작성할 것 | 계속 소비자가 소유할 것 |
|---|---|---|
| 일반 DB 유지보수 | 기존 업무 메서드의 `@Scheduled`, 앱 루트의 `@EnableScheduling` 한 번 | SQL/보존 규칙·DB timeout·실패 정책 |
| DB 결과를 Paper에 반영 | `nextTick(source, handler)`와 결과 관찰 | 현재 Player/session/revision·UUID별 순서 |
| 입력 adapter | 기존 Listener/CommandExecutor와 stereotype | 입력 검증·게임 취소/순서의 실제 이유 |
| 선택 기능 | 소유 Configuration의 표준 Condition/Profile | 독립 scan/import/factory 경로의 동일 조건 |
| 일반 자원 | 표준 `@Bean`과 Spring destruction | 외부 자원의 원 소유자·명시적인 교체 설정 |
| Paper 준비/정리 | main 전용 훅, 필요한 경우 관계 bean | 실제 월드 준비 완료·최종 DB 저장·멱등성 |

[business-plugin](../examples/business-plugin/README.md)이 복사할 업무 경로다. 기본 worker가 충분하면
커스텀 executor/decorator 구성은 생략한다. 단순 주기마다 task/start/stop/busy를 추가하지 않는다.
선택 기능을 앱 안에 넣을 때 Module SPI까지 만들 필요가 없다.
다른 다섯 예제의 counter·준비 Future·smoke 파일과 lifecycle 훅은 API 검증 장치이며 최소 업무 코드가 아니다.
JDBC 예제는 별도 소비자 transaction manager와 proxy를 거치는 서비스 경계를 보여준다.
JPA/Cache/PDC 종합 예제와 합성 보안 fixture를 실제 클라이언트 인증 검증으로 부르지 않는다.

tick 기반 전투와 실제 시간 유지보수는 계속 구분한다. 일반 Spring lifecycle은 worker refresh 중
실행될 수 있으며 Paper 상태 변경 시점을 대신하지 않는다. 사용자가 배워야 할 이 플랫폼 경계와
명시적인 자원/세션 계약까지 없어진다는 생산성 주장은 하지 않는다.

## 사용한 표준 구현과 검증 한계

고정한 공식 source JAR에서 다음 구현을 직접 대조했다.

- Spring 7.0.10 `AbstractApplicationContext.doClose`: close event → lifecycle stop → singleton 파괴 순서.
- `ScheduledAnnotationBeanPostProcessor.destroy`: 예약 취소와 registrar 종료.
- `SimpleAsyncTaskExecutor.close`: 추적된 thread의 제한 시간 대기/남은 interrupt 정책.
- `TaskSchedulerRouter.determineDefaultScheduler`: 기본 타입 선택·이름 fallback·표준 timer adapter.
- `PropertySourcesPropertyResolver`: 우선하는 property source와 중첩 placeholder 해석.

공식 [lifecycle](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html),
[scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html),
[Gradle task 입력](https://docs.gradle.org/current/userguide/incremental_build.html) 계약을 사용한다.
보안의 Spring/Security 근거는 [보안 문서](security.md#종료와-spring-내부-구현-근거)에 있다.

라이브러리 전체 `build`는 Javadoc, Gradle plugin validation, 600줄 검사와 테스트를 포함한다.
H2/제어 가능한 Paper 경계의 통합 테스트는 실제 MariaDB/Redis 네트워크 장애나 Minecraft 클라이언트
접속을 대신하지 않는다. 이 점검에서 운영 개발 서버의 배포·재시작·데이터 변경은 하지 않았다.
IDE의 실제 편집 화면, 장시간 thread/메모리 부하, 모든 외부 FactoryBean/조합을 검증한 것은 아니다.
외부 I/O의 중단·분산 원자성, upstream commit 취소, raw Executor가 받지 않은 Future 완료도 보장하지 않는다.
