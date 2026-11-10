# 설정·데이터·Gradle·예제 독립 검토 — 2026-11-11

기준은 `1a25632`다. 마지막 diff에 한정하지 않고 config 3개, JDBC 3개, JPA 4개,
PDC 17개, Cache 2개, Gradle plugin 6개, 총 운영 Java 35개와 독립 예제 6개의 운영 Java
59개를 읽었다. 앞선 [실행·보안 검토](execution-independent-review.md)의 82개 소스와는 별도 범위다.
소비자 저장소의 역할별 구성과 PersistenceConfiguration은 읽기만 했다.
Java 25·Paper 26.3·Spring 7.0.10·Data 2026.0.2·Security 7.1.2·Hibernate 7.4.13·
Lombok 1.18.48을 변경하지 않았다. 서버 배포·기동·데이터 변경과 `clean`은 실행하지 않았다.

## 확인한 결함과 최소 수정

| 심각도 | 재현과 영향 | 수정과 독립 결과 |
|---|---|---|
| P2 | `entity-manager-factory-bean`과 `transaction-manager-bean`을 명시해도 일반 주입 후보를 먼저 걸렀다. `defaultCandidate=false` 전용 EMF를 이름으로 고르면 선택을 무시하고 쓰지 않을 기본 풀을 생성해 URL을 요구했다. alias도 기존 후보 이름 목록에 없어 거절됐다. 없는 이름이나 잘못된 타입도 후보가 없으면 무시됐다. | 명시 이름은 일반 후보 탐색과 구분한다. 존재 여부와 예측 가능한 타입을 기본 자원 등록 전에 검증하고, alias와 전용 자원을 실제 선택한다. H2의 커밋·롤백, 선택한 EMF/매니저의 identity, 기본 풀 부재를 확인했다. |
| P2 | `entityManagerFactory`라는 이름의 전용 빈은 일반 후보가 없어도 기본 생성을 막고 그대로 사용됐다. 전용 `transactionManager`는 재정의 허용 여부에 따라 기본 정의로 교체되거나 등록 충돌로 실패했다. | 표준 참조 이름의 기존 소유자를 먼저 확인한다. 전용 소유자를 명시적으로 선택하면 재사용하고, 다른 일반 기본을 연결할 수 없으면 설정 키를 포함해 분명하게 거절한다. 재정의 허용/금지 양쪽에서 원래 정의 보존과 생성 부재를 확인했다. |

처음 추가한 네 테스트는 구현을 바꾸기 전에 모두 실패했다. 두 실제 컨텍스트는 잘못 생성한
`springPaperDataSource`가 URL을 요구했고, 나머지는 잘못된 이름과 전용 표준 이름을 거절하지 않았다.
초기 재현 로그는 `build/data-build-independent-review/jpa-selection-tests-red.log`에 있다.
기존 `build/usability-audit/CancellationProbe`와 `DefaultBeanProbe` 소스·로그는 보존했다.

수정은 [JpaConfiguration](../spring-paper-data-jpa/src/main/java/org/blu3fishez/springpaper/jpa/JpaConfiguration.java)
안의 기존 자원 선택 책임에 한정했다. 새 provider SPI·컨테이너·가짜 타입 빈은 만들지 않았다.
일반 후보가 여럿이면 EMF/매니저 이름을 명시하는 기존 계약을 유지했다.
DataSource의 일반 주입은 계속 Spring의 Primary/Fallback 규칙을 사용한다.

[JpaResourceSelectionTest](../spring-paper-data-jpa/src/test/java/org/blu3fishez/springpaper/jpa/JpaResourceSelectionTest.java)의
8개 테스트는 두 설정의 이름/alias, 명시한 표준 이름, 누락/잘못된 타입, 정의 처리 중 FactoryBean
생성 부재, 늦게 타입을 공개하는 raw FactoryBean, 실제 H2 상태와 종료 소유권을 검증한다.
Spring 소유 FactoryBean은 자신이 만든 EMF를 한 번 닫고, 외부 수동 singleton EMF는 컨텍스트가
닫힌 뒤에도 열려 있으며 외부 소유자가 닫는다.

## Spring 선택과 lifecycle 계약

자동 후보 탐색은 `DefaultBeans`가 Spring의 `isAutowireCandidate`에 위임한다.
전용 기본 후보 제외, 일반 후보와 Primary/Fallback, 예측 가능한 FactoryBean 제품을 별도 선택
알고리즘으로 복제하지 않는다. JDBC facade와 Cache 기본은 typed lazy fallback 빈이며,
소비자 기본 후보가 있으면 사용하지 않을 제공자 정의를 제거한다.

JPA의 명시 이름은 자동 탐색과 다르다. `getType(name, false)`로 예측 가능한 잘못된 타입을 일찍
거절하되, 아직 제품 타입이 없는 명시적 FactoryBean을 조사하려고 생성하지 않는다.
이 경우 실제 사용 때 Spring의 typed lookup이 검증한다. 자동 발견에는 타입 metadata가 필요하다.
raw FactoryBean의 늦은 `getObjectType`도 정상 생성 시점에는 실제 제품 타입을 공개해야 Spring Data의
일반 metamodel 발견이 가능하다. 항상 null을 반환하는 제공자를 가짜 metadata로 통과시키지 않는다.

Spring 7.0.10의 공식 source JAR에서 `AbstractBeanFactory.getType`의 초기화 플래그,
`AbstractAutowireCapableBeanFactory`의 FactoryBean 제품 타입 예측,
`DefaultListableBeanFactory`의 alias 소유권 검사를 직접 대조했다.
공식 [타입 조회 계약](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/beans/factory/BeanFactory.html#getType(java.lang.String,boolean))과
[Spring Data JPA 구성](https://docs.spring.io/spring-data/jpa/reference/repositories/create-instances.html)에 따른다.

JDBC/JPA의 트랜잭션 advice와 repository proxy는 Spring이 소유한다. `workerOnly`는 표준
트랜잭션 매니저의 `beforeBegin`에서 main 시작을 거절한다. 원본
`AbstractPlatformTransactionManager.startTransaction`에서 이 callback이 연결 획득 전임을 확인했다.
기존 JDBC 회귀는 거절 시 연결 횟수가 늘지 않는 것과 독립 SQL 결과를 검사한다.
소비자가 직접 준 매니저의 정책을 라이브러리가 임의 교체하지 않는다.

Cache의 resolver/key/advice와 커밋 뒤 변경은 표준 Spring 구현을 사용한다. 소비자 resolver가 있으면
사용하지 않을 Caffeine names/spec을 요구하지 않는다. PDC는 게임 규칙 대신 명시적 key/type과 holder
반영을 소유한다. mapper가 먼저 encode/검증하고, ItemStack은 Paper의 edit API로 반영하며 다른
키를 보존한다. worker 접근은 holder를 읽기 전에 거절한다. PDC에 DB 원자성이나 디스크 flush를 약속하지 않는다.

## 선택 기능과 개발자가 복사하는 경로

앱 내부 기능에는 표준 Configuration·Condition·Profile을 사용한다. business 예제는 기능 소유
구성이 DB·업무·예약·입력을 묶고, 별도 module SPI를 만들지 않는다. 직접 scan되는 입력 adapter도
같은 조건을 가진다. minimal의 별도 module 제공자는 SPI 로더와 직접 import/scan 경로 모두를
자기 Condition으로 보호한다. 독립 무조건 registrar의 JPA scan은 다른 등록 경로라는 계약을 유지한다.

단순 DB 정리는 `@Scheduled` 업무 메서드와 앱의 `@EnableScheduling`으로 충분하다.
DB→Paper 반영은 기존 `PaperTasks`를 재사용하고 소비자가 실제 Player/session/revision을 검사한다.
검증용 scheduling/guide 예제의 counter·ready Future·start/stop을 새 업무의 필수 구조로 요구하지 않는다.
JDBC 예제의 이체는 별도 서비스 proxy를 통과하며 H2 롤백을 독립 상태로 검사한다.
보안 예제의 합성 fixture를 실제 Mojang/OAuth/클라이언트 인증 검증으로 세지 않는다.

Gradle plugin은 ASM으로 metadata를 읽어 Paper 호스트 클래스를 Gradle에서 로드하지 않는다.
descriptor의 실제 공개 출력 Map이 `@Input`이며, 클래스/JAR은 `@Classpath`, YAML은 상대 경로 입력이다.
전 환경을 fingerprint하지 않는다. 기존 TestKit 회귀는 사용한 속성 변경 시 재생성, 무관한 값 변경 시
up-to-date, 출력 삭제 뒤 올바른 build-cache 복원을 검증한다. 서비스 파일은 Shadow의 표준 병합을 사용한다.
공식 [Gradle 입력 계약](https://docs.gradle.org/current/userguide/incremental_build.html)을 대조했다.

## 검증 범위

Java 25에서 `--max-workers=2`로 다음을 검증했다.

```text
./gradlew :spring-paper-config:test :spring-paper-data-jdbc:test :spring-paper-data-jpa:build \
  :spring-paper-data-pdc:test :spring-paper-cache:test :spring-paper-gradle-plugin:test \
  verifySourceSize --rerun-tasks --max-workers=2
```

이 범위의 테스트는 config 4개, JDBC 11개, JPA 20개, PDC 10개, Cache 8개, Gradle plugin 4개로
총 57개다. 예제/native/소비자 저장소의 테스트 수를 여기에 합치지 않는다.
기존 standalone Maven 검증 스크립트는 guide 예제의 src/build를 복사해 included build 없이 소비하고,
현재 라이브러리 bytecode·SPI·금지 패키지·ZIP 중복과 configuration-cache 재사용을 검사한다.
이번 독립 복사 검증은 최종 산출물 게시 뒤 같은 격리 저장소로 business 예제를 소비한다.

IDE 실제 편집 화면, 모든 외부 FactoryBean 구현, MariaDB/Redis 장애·장시간 부하·클라이언트 접속은
이 검증의 범위가 아니다. 이 검토 범위에서 추가 framework나 다른 모듈의 구현을 참조할 필요는 찾지 못했다.
