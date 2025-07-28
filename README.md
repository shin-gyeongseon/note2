| Field Path | Type | Description | Default | Required | Expose in UI | Reason |
|------------|------|-------------|---------|----------|--------------|--------|
| spec.gcsFaultToleranceOptions.redisAddress | string | GCS 장애 내성을 위한 Redis 인스턴스의 주소. | None | Yes | Yes | 장애 내성 활성화 시 external Redis 주소를 사용자가 지정할 수 있게 합니다. |
| spec.gcsFaultToleranceOptions.redisUsername | RedisCredential | Redis 사용자 이름 또는 사용자 이름을 포함하는 소스 참조. | None | No | No | 보안 요구사항에 따라 Redis 인증을 설정; 기본 UI에서 선택적으로 노출. |
| spec.gcsFaultToleranceOptions.redisPassword | RedisCredential | Redis 비밀번호 또는 비밀번호를 포함하는 소스 참조. | None | No | Yes | Redis 인증을 위해 필요; UI에서 비밀번호 입력이나 시크릿 참조를 허용합니다. |
| spec.gcsFaultToleranceOptions.externalStorageNamespace | string | GCS에서 사용하는 external 저장소의 네임스페이스. | None | No | No | 고급 구성; 기본적으로 클러스터 네임스페이스 사용으로 가정. |
| headGroupSpec.rayStartParams.redis-port | string | Redis 서버가 수신할 포트. 싱글 노드 클러스터 시작 시 사용. | 6379 | No | No | 내부 Redis 포트 변경이 필요할 경우; 기본값으로 충분해 UI 노출 불필요. |
| headGroupSpec.rayStartParams.redis-password | string | Redis 연결을 위한 비밀번호. | None | No | Yes | 보안을 위해 Redis 비밀번호 설정; UI에서 입력 가능. |
| headGroupSpec.rayStartParams.redis-shard-ports | string | Redis 샤드 포트 목록 (콤마로 구분). | None | No | No | 샤딩 구성 시 사용; 고급 기능으로 기본 UI에서 노출하지 않음. |
| headGroupSpec.rayStartParams.redis-max-clients | string | Redis 최대 클라이언트 수. | None | No | No | 성능 튜닝; 고급 사용자 대상. |
| headGroupSpec.rayStartParams.redis-max-memory | string | Redis 최대 메모리 사용량. | None | No | No | 메모리 관리; 자동 처리 추천. |
| workerGroupSpec.rayStartParams.redis-address | string | 워커가 연결할 Redis 주소. | Auto-set | Yes | No | 관리 서비스에서 자동 설정; 사용자 입력 불필요. |
| workerGroupSpec.rayStartParams.redis-password | string | Redis 연결을 위한 비밀번호. | None | No | Yes | 헤드와 일치하는 비밀번호; UI에서 공유 설정 가능. 
