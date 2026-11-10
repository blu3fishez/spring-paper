# 온라인 신원 + 운영자 승인 예제

[공통 빌드/실행 방법](../README.md#빌드와-실행)을 따른다. Paper online-mode=true를 요구한다.
이 예제는 Paper가 Mojang 검증을 끝낸 AsyncPlayerPreLoginEvent의 신원을 사용한다.
UUID/이름 문자열 자체를 인증 증거로 신뢰하지 않는다.

콘솔에서 `paper-approval verify`를 실행하면 합성 연결에 대해
`APPROVAL_OK pending=observed authenticated=provider disconnect=clean`을 출력한다.
이 fixture는 Provider·필터·소비자 저장소·대기/결과 처리와 disconnect 정리의 연결을 검증한다.
Mojang 로그인 자체를 재현하는 테스트는 아니다.

실제 유저는 아직 승인되지 않았으면 입장을 보류하고 승인 안내로 접속을 거절한다.
서버 콘솔에서 `paper-approval approve <uuid>`를 실행한 뒤 다시 접속하면 승인 필터를 통과한다.
승인은 예제 메모리에만 보관한다. 운영에서는 소비자의 영속 저장소와 승인 정책을 제공한다.
어드민 서비스의 @PreAuthorize는 ROLE_CONSOLE을 요구한다. 게임 내 OP나 RCON을 같은 권한으로 가정하지 않는다.

소비자는 converter, AuthenticationProvider, PaperSecurityFilterChain, session repository,
AuthenticationEntryPoint, ResultHandler와 admission handler를 제공한다.
실제 Discord OAuth 서버·credential·콜백·길드/역할 규칙은 별도 소비자 구현이다.
Velocity offline backend에서는 온라인 모드 예제를 그대로 쓰지 않고, 신뢰할 수 있는 forwarding과
독립 검증한 인증 증거를 converter/provider로 연결한다.
