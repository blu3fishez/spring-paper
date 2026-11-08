# 검증 기록 — 2026-11-08

라이브러리와 소비 예제의 빌드·자동 검증 및 실제 Paper JAR 다운로드까지 확인했다. 실제 Paper 서버 기동 검증은 명시적 EULA 수락 답변을 기다리고 있으며 아직 실행하지 않았다. 전체 목표를 완료로 표시하지 않았다.

## 수행한 검사

- `./gradlew clean build`: 전체 fresh build 성공. 이후 최종 코드의 `./gradlew build`도 성공했다.
- `./gradlew -p examples/guide-plugin build --configuration-cache`: included build 소비 예제 성공, configuration cache 저장·재사용 확인.
- `python3 scripts/verify-published-consumer.py`: 격리된 Maven 배포물만 사용하는 별도 소비 프로젝트 빌드와 설정 캐시 재사용 성공.
- Gradle 플러그인 validatePlugins와 작성 파일 600줄 검사 통과.
- `preparePaperServer`: loopback 개발 서버 속성과 EULA false 생성 확인.
- `runServer --dry-run --configuration-cache`: task graph 구성 성공. 서버 실행 결과는 아니다.
- run-paper의 실제 DownloadsAPIService만 호출해 YAML 대상 Paper 26.3 build 244를 다운로드하고 SHA-256 검증. 서버 기동과 EULA 값 변경은 없었다.

## 자동 검증

총 30개, 실패·오류·스킵 0.

- spring-paper-cache: 2개
- spring-paper-config: 4개
- spring-paper-core: 18개
- spring-paper-data-jpa: 3개
- spring-paper-gradle-plugin: 1개
- spring-paper-security: 2개

실제 H2 어댑터에서 JPA 저장·재조회·revision·rollback과 기존 DataSource 재사용을 확인했다. 메인 스레드 트랜잭션 시작을 거절하는 검사도 포함한다. Cache는 실제 Spring proxy와 commit/rollback에 따른 변경을 확인했다. Security는 실제 메서드 인가 proxy를 호출해 허용·거절·문맥 복원을 확인했다.

Paper 입력·생명주기 검사는 Paper API 타입과 mock host를 사용한다. 실제 Bukkit 명령 객체에 실행기/탭 완성을 연결·복구하고, 이벤트 등록 옵션과 종료 후 콜백 차단을 확인했다. 상속된 이벤트·시작·종료 메서드의 재정의 규칙을 확인했고, 워커 새 제출 차단·진행 작업 대기가 인프라 destruction보다 먼저 이루어지는지도 검증했다. 이는 실제 서버·플레이어·클라이언트 검증을 대신하지 않는다.

## 배포물 소비 검증

`build/verification-repository`의 Maven 플러그인 marker와 모듈을 사용했다. 소비 프로젝트는 includeBuild를 사용하지 않는다. changing SNAPSHOT을 refresh한 첫 빌드와 설정 캐시를 재사용한 두 번째 빌드가 모두 성공했다.

- 현재 runtime 라이브러리 클래스 37개의 bytecode가 소비 JAR과 정확히 일치한다. 이전 SNAPSHOT을 잘못 검사하는 경우도 거절한다.
- plugin.yml 진입점·명령 메타데이터와 JPA/Cache/Security SPI 제공자 병합을 보존했다.
- 호스트 API·Boot 클래스 및 ZIP 중복 entry가 없다.
- ~/.m2나 외부 Maven 저장소에 배포하지 않았다.

## 최종 included-build 예제 JAR

- SHA-256: `ab84d91860d885e25e480c7713a38f92b000d3bec4aac6ed2e5528719c92cba8`
- 크기: 39917259 bytes
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

## 실제 개발 서버 JAR 다운로드

- run-paper 3.0.2, Paper 26.3 build 244, 55279049 bytes
- SHA-256: `9601f9a26a2a52a0f2c807cf1abf691f96ee4fb287878313b601cebc2c51eabf`
- run-paper의 공식 Fill v3 조회·다운로드·checksum 검증 경로를 재사용했다. 별도 downloader를 구현하지 않았다.
- 다운로드만 수행했으며 Minecraft 서버 프로세스는 시작하지 않았다. eula=false를 유지했다.

## 남은 확인

- 격리된 실제 Paper 26.3 build 244에서 native event·콘솔 command·JPA/cache/security smoke 확인.
- 정상 stop과 Hikari/EntityManagerFactory 종료, 커스텀 stop marker 확인.
- 실제 Minecraft 플레이어 접속·클라이언트 UI, MariaDB·Redis·다중 플러그인 버전 공존은 이번 자동 검증 범위 밖이다.
- 기존 SimpleExtraction의 Guice 전환·검증계 배포는 후속 도입 단계다. 기존 저장소는 작업 시작과 같은 dirty 상태이며 파일을 수정하지 않았다.

기계 판독 요약은 [verification-summary.json](verification-summary.json)에 있다.
