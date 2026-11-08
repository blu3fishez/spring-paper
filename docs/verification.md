# 검증 기록 — 2026-11-08

현재 단계는 라이브러리와 소비 예제의 빌드·자동 검증 완료다. 실제 Paper 서버 기동 검증은 명시적 EULA 수락 답변을 기다리고 있으며 아직 실행하지 않았다. 전체 목표를 완료로 표시하지 않았다.

## 수행한 검사

- `./gradlew clean build`: 전체 fresh build 성공. 이후 최종 코드 변경의 `./gradlew build`도 성공했다.
- `./gradlew -p examples/guide-plugin build --configuration-cache`: 실제 소비 빌드 성공, configuration cache 저장·재사용 확인.
- Gradle 플러그인의 validatePlugins와 작성 파일 600줄 검사 통과.
- `preparePaperServer`: loopback 개발 서버 속성과 EULA false 생성 확인.
- `runServer --dry-run --configuration-cache`: task graph 구성 성공. 서버 실행 결과는 아니다.

## 자동 검증

총 23개, 실패·오류·스킵 0.

- spring-paper-cache: 2개
- spring-paper-config: 4개
- spring-paper-core: 11개
- spring-paper-data-jpa: 3개
- spring-paper-gradle-plugin: 1개
- spring-paper-security: 2개

실제 H2 어댑터에서 JPA 저장·재조회·revision·rollback과 기존 DataSource 재사용을 확인했다. 메인 스레드 트랜잭션 시작을 거절하는 검사도 포함한다. Cache는 실제 Spring proxy와 commit/rollback에 따른 변경을 확인했다. Security는 실제 메서드 인가 proxy를 호출해 허용·거절·문맥 복원을 확인했다.

Paper 입력·생명주기 검사는 Paper API 타입과 mock host를 사용한다. 실제 Bukkit 명령 객체에 실행기/탭 완성을 연결·복구하고, 이벤트 등록 옵션과 종료 후 콜백 차단을 확인했다. 이는 실제 서버·플레이어·클라이언트 검증을 대신하지 않는다.

## 최종 예제 JAR

- SHA-256: `1bd441de2874af65928ece952ede1f80579cdff8603adfe22580a998a4f4894f`
- 크기: 39916718 bytes
- ZIP 중복 entry: 0
- Spring Boot / Bukkit / SLF4J 포함 클래스: 각각 0
- 프로젝트 클래스 major: 69 (Java 25)
- JPA·Cache·Security SPI 제공자 3개 병합 보존

생성된 plugin.yml:

```yaml
name: SpringPaperExample
main: org.blu3fishez.springpaper.example.ExamplePlugin
version: 0.1.0-SNAPSHOT
api-version: '26.3'
load: POSTWORLD
description: Spring Paper integration verification
authors:
- blu3fishez
commands:
  springpaper:
    description: Verify Spring Paper integrations
    usage: /springpaper
    aliases:
    - sp
    permission: springpaper.verify
permissions:
  springpaper.verify:
    default: op
```

## 남은 확인

- 격리된 실제 Paper 26.3 build 244에서 native event·콘솔 command·JPA/cache/security smoke 확인.
- 정상 stop과 Hikari/EntityManagerFactory 종료, 커스텀 stop marker 확인.
- 실제 Minecraft 플레이어 접속·클라이언트 UI, MariaDB·Redis·다중 플러그인 버전 공존은 이번 자동 검증 범위 밖이다.
- 기존 SimpleExtraction의 Guice 전환·검증계 배포는 후속 도입 단계다. 기존 저장소는 작업 시작과 같은 dirty 상태이며 파일을 수정하지 않았다.

기계 판독 요약은 [verification-summary.json](verification-summary.json)에 있다.
