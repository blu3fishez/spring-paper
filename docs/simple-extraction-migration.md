# SimpleExtraction 적용 계획

Spring Paper의 첫 소비 프로젝트를 위한 이관 지도다. 현재 라이브러리 제작 작업에서 기존 저장소를 변경하거나 검증계 서버를 재배포하지 않았다. 실제 도입은 SIMEXT-90 작업에서 진행한다.

## 보존할 조건

- Spring Boot 없이 프로젝트 소유 Guice 컨테이너를 제거하고 애플리케이션별 하나의 Spring 컨텍스트를 사용한다.
- 기존 JavaPlugin·설정·Hikari·Redis·캐시·싱글턴 상태를 중복 생성하지 않는다.
- common / skyblock / dungeon의 확정 Gradle 역할 구조와 공개 API/SPI 방향을 보존한다.
- PREMIUM_ONLY/TEST_ONLY와 SKYBLOCK/DUNGEON의 비활성 구현은 구성 자체에서 제외한다. Lazy를 모드 격리로 해석하지 않는다.
- 기존 SQL·코덱·UUID·PDC·소유권·revision·동일 업무 트랜잭션과 서버 이동 내구성 계약을 보존한다.
- proxy-sdk는 Spring에 의존하지 않는다. Velocity 호스트의 DI와 프로젝트 소유 컨테이너를 구분한다.

## 변경 위치

| 현재 | 적용 |
|---|---|
| SimpleExtraction.onEnable/onDisable 자원 나열 | 얇은 SpringPaperPlugin 진입점과 기능별 lifecycle 훅 |
| Guice Module의 내부 생성자 주입 등록 | 기능별 제한 스캔과 @Service/@Repository |
| 외부 자원 @Provides | 기존 factory를 호출하는 명시적 @Bean, 하나의 종료 소유자 |
| 역할별 bind 구현 선택 | 기존 검증된 PluginConfig로 조건부 구성·@Import 선택 |
| Multibinder GuideHistorySource 집합 | 같은 제공자 빈의 컬렉션 주입. 순서·중복·자기 자신 재주입 확인 |
| META-INF/services/com.google.inject.Module | 조립 구성을 Spring으로 이전. 프레임워크 확장은 SpringPaperModule SPI 사용 |
| registerEvents와 setExecutor/setTabCompleter 나열 | PaperEventListener / PaperCommandExecutor |
| 운영 중 DB 작업자·메인 스레드 전환 | 기존 executor 유지 또는 PaperTasks로 명시적 연결. 세션 검증 보존 |

## GuideRuntime 예시

- 접속·퇴장·나침반·리소스 재로딩 이벤트는 GuideListener 입력 어댑터로 둔다.
- sessions/current/refresh/mirror와 발전과제 표시 책임은 기능의 표시 동기화 서비스가 소유한다.
- 발전과제 정의 준비, 온라인 플레이어 초기 복원, 반복 작업 시작은 사용자 PaperStart에 연결한다.
- 반복 작업 취소와 미확정 기록 flush는 사용자 PaperStop이 수행한다. 도메인의 retry/멱등성 계약은 라이브러리가 가져가지 않는다.
- 명령은 기존 실행 코드에 PaperCommandExecutor 메타데이터를 추가하고 기존 plugin.yml 수동 정의의 관리 위치를 하나로 옮긴다.

## 선택적 생태계 도입

DI 전환과 Jdbi → JPA 전환을 한 번에 묶지 않는다. 기존 Hikari/Jdbi bean으로 먼저 기능을 보존할 수 있다. JPA는 대표 업무의 기본 CRUD와 소유권/revision 조건부 갱신을 함께 비교한 뒤 업무 트랜잭션 단위로 결정한다.

현재 JPA 모듈은 Spring Data fragment와 native/JPQL 수정 쿼리의 사용을 막지 않는다. 기존 Handle 노출 계약, JPA/JDBC/Jdbi의 같은 Connection 참여, 예상 행 수·flush/clear·잠금 순서는 별도 이관 설계가 필요하다.

CacheManager와 Security는 명확한 조회·인가 사용 사례부터 연결한다. 기존 인벤토리 세션 캐시를 복제하거나 FastLogin/테스트 인증을 자동 대체하지 않는다.

## 검증 단계

1. 기존 동작·기능 등록·플랫폼/인증별 빈 그래프·자원 소유자 목록을 기록한다.
2. 기능별 도입 후 관련 빌드·기존 검사와 자동 테스트를 확인한다. 기존 저장소 테스트 본문 변경은 별도 요청 범위를 따른다.
3. 최종 Paper/Velocity JAR의 클래스·SPI·SQL·설정 리소스와 배포 빌드를 확인한다.
4. 검증계에서 역할·인증 모드별 기동, 명령·이벤트·반복 작업, DB/Redis 장애·오래된 세션 callback·부분 시작 실패·종료 flush를 확인한다.
5. 실제 클라이언트 동작·MSPT·쿼리/풀/잠금 대기 측정과 rollback 절차를 기록한다.

Velocity에는 Paper bootstrap/core를 적용하지 않는다. Spring Paper Security의 호스트 중립 security-core는 소비자의 Velocity 어댑터에서 선택적으로 호출할 수 있다. 기존 auth의 FastLogin·테스트 계정 규칙과 저장소는 소비자 구현으로 연결하며 프레임워크에 고정하지 않는다. 실제 SimpleExtraction 적용, Java 25 runtime 확인과 프록시 접속 검증은 SIMEXT-90의 후속 도입 단계다. 새 프로세스·플러그인 분리나 배포를 이 문서에서 확정하지 않는다.
