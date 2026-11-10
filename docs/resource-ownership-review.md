# JPA·보안 리소스 소유권 후속 검토 — 2026-11-11

기준은 `4725765`의 JPA 명시 자원 선택과 `1a25632`의 보안 생성 소유권 수정이다.
현재 공개 계약, 관련 운영 코드와 테스트, Paper 어댑터·예제의 실제 disconnect 호출을 독립적으로 읽었다.
동시에 진행한 core 종료 변경은 별도 검토자가 소유하며 이 검토에서 수정하지 않았다.
Java 25·Paper 26.3·Spring 7.0.10을 유지했고 운영 서버·레지스트리·소비자 데이터는 변경하지 않았다.

## 확인하여 수정한 결함

| 심각도 | 재현과 영향 | 수정과 독립 결과 |
|---|---|---|
| P2 | 첫 disconnect는 미응답 create의 소유권을 보류했지만 Managed를 제거했다. 두 번째 disconnect는 revoked가 null이라 저장소를 무효화했다. create=false 또는 응답 실패 뒤 실제 Paper preLogin 어댑터가 release하는 경우에도 다른 컨텍스트의 기존 row를 삭제했다. 반복 disconnect는 원래 terminal handler의 pending/실패 ACK도 공유하지 않았다. | service disconnect는 로컬 소유 접속만 철회한다. 정확한 접속 식별자의 진행 중 ACK를 map에서 재사용하고 완료 때 조건부 제거한다. 미관리/실패한 접속은 no-op이며 명시적 원격 철회는 기존 repository.invalidate SPI를 사용한다. 늦은 true는 캡처한 생성 소유자로 row만 정리하며 handler를 다시 호출하지 않는다. |
| P2 | JPA가 부모의 raw FactoryBean을 명시적으로 고르면 getType(name, false)가 Spring 7.0.10 내부에서 부모의 getType(name)으로 위임됐다. 제품 타입을 알아내려고 지연 factory가 BFPP 중 초기화되어 기존 no-eager-init 계약을 깨뜨렸다. | 공개 HierarchicalBeanFactory와 SimpleAliasRegistry metadata로 자원의 실제 소유 factory를 찾은 뒤 초기화 없는 타입 조회를 한다. 부모 이름·부모 alias·자식에서 부모로 향한 alias 모두 factory 생성 0회를 확인했다. 예측 가능한 잘못된 타입은 생성 전 거절하고, 미확정 제품은 실제 typed lookup에서 검증한다. |

보안의 새 core 테스트 6개와 실제 Paper 어댑터 테스트 1개는 수정 전에 모두 실패했다.
첫 RED는 원격 row 조회의 NoSuchElementException과, terminal handler가 미완료인데 반복 disconnect가
완료된 상태였다. JPA 테스트 4개 중 3개가 수정 전에 실패했고, 기존의 예측 가능한 잘못된 타입 거절은
이미 통과했다. 별도 부모 FactoryBean probe도 선택 중 생성 횟수 1회를 기록했다.
수정 뒤 추가한 보안 테스트 1개는 늦은 생성 삭제가 남은 동안 앞선 handler 완료가 새 ACK를 제거하지 않는지
확인하며, terminal 통지 1회와 저장소의 실제 삭제 결과도 검사한다.

RED 로그는 `build/resource-ownership-security-red.log`, `build/resource-ownership-admission-red.log`,
`build/resource-ownership-jpa-red.log`, `build/resource-ownership-jpa-parent-red.log`에 있다.
기존 `build/usability-audit`와 데이터 검토 probe는 보존했고 `clean`은 실행하지 않았다.

## 계약과 책임 판단

JPA의 기존 명시 이름·qualifier 전용 빈·defaultCandidate=false·표준 이름 점유·alias 처리는
단일 컨텍스트 회귀에서 유지된다. 기존 실제 H2 commit/rollback과 Spring 소유 FactoryBean의 한 번 종료,
외부 수동 singleton EMF의 컨텍스트 종료 뒤 생존도 다시 통과했다. 제공한 EMF를 선택하면 사용하지 않을
기본 풀이나 URL을 강제하지 않는다. 다중 일반 EMF/매니저의 명시 선택과 DataSource의 Spring 후보 선택을
새 알고리즘으로 대체하지 않았다. 알려지지 않은 FactoryBean 제품의 자동 발견에는 여전히 타입 metadata가 필요하다.

보안의 service는 로컬 접속 전이와 정리 ACK를 소유하며, repository SPI는 정확한 접속의 원자적 원격
삭제를 소유한다. 실제 어댑터와 예제는 disconnect를 실패/종료 정리에 사용했고, 미관리 접속에 대한 명시적
원격 철회 호출 계약은 찾지 못했다. 따라서 미관리 disconnect의 no-op 변경을 Javadoc과
[보안 계약](security.md)에 명시했다. 원격 철회·완료된 삭제 실패의 재시도는 repository.invalidate로 가능하다.
기존 API/SPI를 재사용하며 완료된 연결 식별자의 영구 tombstone이나 별도 저장소는 추가하지 않았다.

진행 중 ACK map은 완전한 접속 식별자를 key로 쓰고 admission lock 안에서만 접근한다.
앞선 ACK 완료가 늦은 생성의 새 ACK를 제거하지 않도록 value도 비교해 제거한다.
shutdown은 map의 ACK와 같은 admission 경계에서 예약한 소유 접속 정리를 함께 확인한다.
외부 이벤트·handler·저장소 호출은 락 밖에 둔다. 미응답 create나 실제 commit 뒤 응답 실패의 불확정 상태를
강제로 해결하지 않으며, shutdown의 bounded wait와 저장소 TTL·멱등성·조정 책임은 유지한다.

고정 버전의 공식 source JAR와
[AbstractBeanFactory.getType 구현](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java),
[BeanFactory 타입·alias 계약](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/beans/factory/BeanFactory.html)을 대조했다.
일반 부모가 소유 metadata를 제공하지 않으면 이를 추측해 생성하지 않고 실제 typed lookup까지 검증을 미룬다.

## 검증 범위와 한계

Java 25에서 다음을 `--max-workers=2`로 통과했다.

```text
./gradlew :spring-paper-data-jpa:test :spring-paper-security-core:test :spring-paper-security:test verifySourceSize
./gradlew :spring-paper-data-jpa:build :spring-paper-security-core:build :spring-paper-security:build verifySourceSize
```

XML 결과는 JPA 24개, security-core 48개, security 14개로 총 86개이며 실패·오류·skip은 0개다.
모듈 build의 Javadoc·sources JAR·assemble도 통과했다. 두 번째 build는 앞선 통과 테스트의 up-to-date
결과를 재사용했다. GREEN 로그는 `build/resource-ownership-focused-green.log`와
`build/resource-ownership-build-green.log`에 있다.
검증은 실제 Spring 컨텍스트·H2 저장 상태·core 가상 시간과 mock Paper host/transport를 사용한다.
실제 Minecraft 클라이언트·MariaDB/Redis 장애·장시간 부하·모든 외부 FactoryBean 구현을 검증한 것은 아니다.
전체 라이브러리와 소비자 산출물 검증은 상위 작업에서 별도로 집계한다. 이 검토는 해당 소유권 경계와
재현한 실패에 한정하며 라이브러리 전체의 SOLID·동시성 준수를 인증하지 않는다.
