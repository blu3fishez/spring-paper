# 최종 Standards 검토 — 2026-11-12

검토 고정점은 Spring Paper `b8a9166` 및 SimpleExtraction
`17a4c88ab1bf477d87c53261e50fe048814ba04d...32b76853d4f4dd8163710e0ea1505f75bf378249`다.
소비자 변경은 SIMEXT-90 커밋 16개다. 이 기록은 그 고정점에서 발견한 네 결함과 수정 중 독립
재검토에서 확인한 worker 준비 경계 보완을 구분한다. 수정 후 commit 식별자는 상위 작업 기록에서 연결한다.

## 기준과 범위

소비자 AGENTS.md의 단일 책임·API/SPI 경계·기존 제공자 재사용·자원 단일 소유권·Paper 메인 스레드
규칙을 적용했다. 소비자의 `spring-paper-adoption.md`, `spring-paper-execution-contracts.md`,
`spring-paper-consumer-ordering.md`, `velocity-spring-adoption.md` 및 이 저장소 README와
[기본 빈](default-beans.md), [실행](execution-contracts.md), [예약](scheduling.md),
[보안](security.md), [Velocity](velocity.md) 계약을 확인했다.
두 저장소에서 별도 CONTRIBUTING.md/CODING_STANDARDS.md는 발견하지 못했다.

| 영역 | 직접 검토한 책임 |
|---|---|
| core | host singleton, bootstrap/classloader, bean catalog, 명령·이벤트 proxy 호출 및 순서, lifecycle 그래프, worker/main/tick 실행과 종료, chunk ticket 소유권 |
| config | YAML/Environment 설정, DependencyDescriptor 기반 기본 후보 판정 |
| cache | 기본 manager 선택, Spring Cache 확장, transaction-aware mutation |
| JDBC/JPA | 기존 DataSource·EMF·manager 재사용, FactoryBean 예측, alias/부모 자원, transaction 시작 스레드 |
| PDC | repository 제품과 proxy, holder 변경 경계, 타입/record 오류 구분, 쓰기 전 값 변환 |
| security-core | admission/operation/revision, CAS 공개와 철회, pending/create/cleanup ACK 경합, 전용 timeout, TCCL와 Spring 종료 |
| security | Paper 입장·종료, bounded snapshot, stale 권한 거절, 인가 proxy 및 이전 SecurityContext 복원 |
| Velocity | 외부 host 소유권, 정의/제품 singleton 검증, native 입력과 프록시, 부분 등록 실패 및 시작/종료 경합 |
| Gradle plugin | 클래스 로딩 없는 metadata 판독, plugin.yml 권한/명령 생성, Shadow/service merge, 개발 서버 준비 |
| 소비자 | 변경 목록과 구성 경계, 공통/역할 조합, UUID 직렬 executor, main/worker 호환 어댑터, resource-pack/potion 역할 분리, 입력 관계·Velocity 구성 |

이 Standards 검토의 소비자 범위는 전체 게임 SQL·정산 규칙의 독립 상세 감사와 같지 않다.
소비자 Spec 및 배포 검토는 별도 담당 결과와 함께 평가한다. 자동 정적 검사로 이미 잡히는 항목은
취향성 finding으로 다시 세지 않았다. 12개 smell 기준을 검토했으며 추가 확정 finding은 없었다.
`MainThreadExecutor`/`VirtualTaskExecutor`의 얇은 위임은 문서화된 기존 호출 호환 범위이므로
단순 Middle Man이라는 이유로 제거하지 않았다. 입력 어댑터의 서비스 위임도 명시된 역할 분리다.

## 고정점에서 확인한 네 결함

모두 P2로 분류했으며 다음 수정으로 해결했다. 아래의 계약 위반은 요구사항 누락을 새로 추정한
것이 아니라 문서화된 프레임워크 재사용·선택·소유권 경계와 실제 실행 결과를 대조한 것이다.

1. **FactoryBean 입력 제품과 main 훅 누락.** `PaperBeanCatalog`는 merged definition 후처리에서
   FactoryBean 자체 타입만 보았다. singleton 제품의 `@PaperEventListener`와 시작/종료 훅은
   Spring 컨텍스트가 성공해도 등록되지 않았다. 이는 Spring이 소유한 입력 하나를 재사용하는
   README/소비자 입력 계약을 깨뜨린다. refresh 뒤 예측 metadata와 실제 제품의 target metadata를
   결합하고, 실제 제품 singleton도 검사한다. 제품을 원본 target으로 꺼내 실행하지 않는다.
2. **FactoryBean scheduler 후보 누락.** `PaperCoreConfiguration.paperSchedulingDefaults`의
   singleton-only 타입 조회는 초기화 전 singleton 여부가 미확정인 제품을 제외했다.
   소비자 fallback scheduler 제품을 제공해도 library 기본이 남아 다중 fallback 선택으로 흐른다.
   [기본 빈 계약](default-beans.md)의 예측 가능한 FactoryBean 재사용·불필요한 기본 생성 금지와
   충돌한다. 제품을 조기 생성하지 않고 타입 조회에 포함하도록 수정했다.
3. **전용 timer가 일반 예약을 차지함.** library 기본 scheduler의 `@Fallback` 때문에
   Spring의 직접 타입 조회는 유일한 non-fallback 전용 timer를 defaultCandidate보다 먼저 골랐다.
   `defaultCandidate=false` 유지보수 timer가 일반 `@Scheduled` 업무를 실행할 수 있었다.
   기본/전용 실행 분리 계약 위반이며 library 기본의 Fallback만 제거했다. 소비자 기본이 있으면
   기존 후처리기가 library 정의를 제거하는 책임은 유지한다.
4. **캐시 aspect가 전용 manager를 선택함.** 같은 Fallback 문제로 `CacheAspectSupport`는
   `reportingCache`를 선택했지만 일반 생성자 주입은 기본 manager를 받았다.
   실제 `@Cacheable("notes")`가 `Cannot find cache named 'notes'`로 실패했다.
   기존 기본 후보 테스트는 생성자 주입만 검사해 이를 놓쳤다. library 기본 manager의 Fallback을
   제거하고 실제 proxy 호출과 기본 cache 저장 결과를 확인한다. CachingConfigurer/CacheResolver는 유지한다.

## 독립 재검토에서 보완한 제품 준비 스레드

첫 catalog 수정은 refresh 이후 main에서 제품을 처음 조회했다. 일반 FactoryBean은 refresh만으로
제품을 만들지 않으므로 `getObject`와 제품 BeanPostProcessor가 main에서 실행될 수 있었다.
이 문제는 원래 고정점의 별도 다섯 번째 finding으로 세지 않는다. 수정안의 worker 준비 계약을
독립 reviewer가 재검토해 확인한 후속 결함이다.

현재 worker는 context refresh와 관련 제품 준비·catalog 확정을 함께 끝낸 뒤 결과 Map을 반환한다.
main에서는 확정된 같은 제품의 훅과 native 입력 등록을 실행한다. 관련 없는 FactoryBean 제품은
탐색 때문에 만들지 않는다. FactoryBean 자체 훅을 제품 훅으로 옮기지 않으며 factory의 일반
Spring destruction 소유권은 그대로다. 준비 실패 시 factory는 한 번 닫고 아직 시작하지 않은
Paper 훅은 실행하지 않는다. 새 public API나 별도 dispatcher를 추가하지 않았다.

## RED·GREEN 및 변형 검증

| 문제 | RED/독립 결과 | 수정 후 GREEN 및 방어 변형 |
|---|---|---|
| FactoryBean 제품 발견 | 새 FactoryProductBootstrapTest에서 startup count 0 | singleton 입력·명령·시작/종료, raw factory method 의도, prototype 거절, 직접 singleton, class/JDK proxy advice 통과. FactoryBean 후보를 다시 건너뛰는 변형은 실패 |
| 제품 준비 스레드 | 독립 Java 25 probe 및 수정안을 main 제품 조회로 되돌린 변형에서 thread assertion 실패 | getObject와 제품 BPP의 worker 실행, Paper 훅 main 단계 및 부분 준비 실패 정리 통과 |
| scheduler 제품 후보 | 소비자 fallback FactoryBean 제공 후 library 기본이 남아 assertion 실패 | FactoryBean/Primary/defaultCandidate 조합 통과. singleton-only 조회 복원 변형은 실패 |
| 전용 scheduler 선택 | 첫 확대 회귀 123개 중 전용 후보 선택 1개 실패 | 실제 @Scheduled thread 확인. 기본 @Fallback 복원 변형은 실패 |
| 전용 cache 선택 | 실제 Cacheable 호출을 추가하자 cache lookup 예외 | 동일 key 두 번 호출 결과와 기본 notes 저장 확인. 기본 @Fallback 복원 변형은 실패 |

다섯 변형은 한 번에 한 파일만 바꾸고 `finally`에서 복원했다. 각 변형은 해당 테스트 1개가
실패했으며 컴파일 실패를 방어 성공으로 세지 않았다. 요약 스크립트의 `test/tests` 단복수 판독은
로그 원문을 확인해 보정했다. 최종 전체 build는 모든 변형을 복원한 소스로 실행했다.

대표 회귀 파일:

- [FactoryProductBootstrapTest](../spring-paper-core/src/test/java/org/blu3fishez/springpaper/bootstrap/FactoryProductBootstrapTest.java)
- [PaperMainThreadTaskSchedulerTest](../spring-paper-core/src/test/java/org/blu3fishez/springpaper/task/PaperMainThreadTaskSchedulerTest.java)
- [CacheDefaultCandidateTest](../spring-paper-cache/src/test/java/org/blu3fishez/springpaper/cache/CacheDefaultCandidateTest.java)
- [DefaultSchedulingIsolationTest](../spring-paper-security/src/test/java/org/blu3fishez/springpaper/security/DefaultSchedulingIsolationTest.java)

마지막 격리 테스트는 일반 유지보수 본문이 막혀 있는 동안에도 security 전용 timer에서 pending
만료가 발생하는 실제 결과를 확인한다. fixedDelay 완료 시점·main quiesce·worker drain·외부
executor 소유권·security 종료 경합은 기존 회귀를 함께 유지했다.

## 최종 검증 결과와 한계

명령: `./gradlew build --max-workers=2 --console=plain`.
14초에 성공했고 90개 actionable task 중 30개 실행, 60개 UP-TO-DATE였다.
`verifySourceSize`와 `git diff --check`도 성공했다. broad clean은 실행하지 않았다.

| 모듈 | 유효 테스트 | 마지막 build 처리 |
|---|---:|---|
| cache | 9 | 실행 |
| config | 4 | UP-TO-DATE |
| core | 125 | 실행 |
| data-jdbc | 11 | 실행 |
| data-jpa | 24 | 실행 |
| data-pdc | 10 | 실행 |
| gradle-plugin | 4 | UP-TO-DATE |
| security | 15 | 실행 |
| security-core | 50 | UP-TO-DATE |
| velocity | 19 | UP-TO-DATE |
| 합계 | 271 | 194개 실행, 77개 기존 최신 결과 재사용 |

최종 XML은 failure/error/skip 모두 0이다. 271개를 모두 새로 실행했다고 주장하지 않는다.
원본 RED·변형·최종 로그는 실행 환경의 `/tmp/simext90-final-review/`에 보존했다.
`factory-product-baseline.log`, `scheduler-product-baseline.log`, `cache-candidate-baseline.log`,
`mutation-*.log`, `standards-mutation-results.txt`, `library-final-build.log`가 해당 근거다.
임시 경로의 장기 보존은 보장하지 않으며 위 회귀 소스와 실행 명령이 저장소의 재현 근거다.

이 결과는 Spring 실제 컨텍스트/타이머·proxy와 Paper/Velocity 호스트 경계 테스트다.
실제 Minecraft 클라이언트, 운영 부하, 개발계 배포·재기동·merge는 수행하지 않았다.
소비자 gitlink 갱신과 최종 소비자 전체 검증은 상위 작업에서 별도로 수행한다.

## 소비자가 제공하는 Fallback 조합의 명시적 제한

소비자 자신의 `@Fallback` 기본과 non-fallback 전용 자원을 함께 제공한 경우에는 Spring 직접
타입 조회의 우선순위를 그대로 유지한다. library가 그 metadata를 바꾸거나 가짜 기본을 보태지 않는다.
그 경우 일반 예약은 명시적인 `scheduler`/SchedulingConfigurer, 캐시는
`cacheManager`/CachingConfigurer의 manager 또는 resolver로 대상을 정한다.
두 조합은 회귀에서 실제 예약·캐시 실행 결과로 확인했다. 자세한 표는 [기본 빈 계약](default-beans.md)이다.

## 확인한 Spring 7.0.10 공식 구현

Gradle 캐시의 공식 `spring-beans-7.0.10-sources.jar`와
`spring-context-7.0.10-sources.jar`를 직접 읽어 다음 동작을 확인했다.

- [AbstractAutowireCapableBeanFactory](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java): FactoryBean 제품은 postProcessObjectFromFactoryBean에서 after-initialization 후처리를 받음.
- [DefaultListableBeanFactory](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java): resolveNamedBean은 primary/유일 non-fallback, priority, defaultCandidate 순서로 선택하며 일반 DependencyDescriptor 주입과 경로가 다름.
- [TaskSchedulerRouter](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/scheduling/config/TaskSchedulerRouter.java): 일반 예약은 resolveNamedBean, 명시 scheduler는 qualifiedBeanOfType을 사용함.
- [CacheAspectSupport](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/cache/interceptor/CacheAspectSupport.java): resolver가 없으면 afterSingletonsInstantiated에서 getBean(CacheManager.class)를 사용함.
