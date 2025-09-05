아래는 “작은 작업 vs 큰 작업” 기준으로 Head / Worker 그룹 자원 설정 전략을 어떻게 도출했는지를 설명까지 포함해 문서로 정리한 버전이야.
사전 지식이 없는 사람도 이해할 수 있도록 기본 개념부터 순차적으로 풀었어.


---

Ray Cluster 자원 설정 전략 문서

1. 배경

Ray Cluster를 쿠버네티스에서 운영할 때, 각 Pod의 CPU와 메모리 자원을 requests와 limits로 지정해야 한다.

Requests: 최소 보장치, 스케줄러가 이 값 기준으로 노드에 할당.

Limits: 최대 사용치, CPU는 이를 초과하면 스로틀링 발생, 메모리는 초과 시 OOM(Out of Memory)으로 종료.


이 두 값을 어떻게 설정하느냐에 따라 **안정성(서비스 죽지 않음)**과 **효율성(자원 낭비 없음)**의 균형이 결정된다.


---

2. 주요 참고 자료

쿠버네티스 공식 문서: request는 스케줄링 기준, memory limit 초과 시 OOM.

운영 사례 블로그/커뮤니티:

CPU limit를 낮게 잡으면 스로틀링으로 지연 발생 → 지연 민감 워크로드에 불리.

Memory는 request=limit로 맞춰야 예측 가능성이 높음.


KubeRay 문서: Ray는 request 값만 있어도 이를 기반으로 태스크/액터를 스케줄 가능.

Ray 성능 가이드: 오브젝트 스토어 메모리는 워커 메모리의 30~40% 비율이 일반적.



---

3. 기본 원칙

1. CPU

request: limit 대비 일정 퍼센트로 설정 (작업 크기에 따라 70~90%).

limit: 필요 시만 지정. Serve(지연 민감)는 limit 제거 가능.



2. Memory

request = limit 동일. 초과 시 OOMKill이므로, 예측 가능하게 동일하게 맞춤.



3. Object Store Memory

worker 메모리 limit의 30~40% 할당. 대규모 처리나 Serve형 워크로드에서는 상향.





---

4. 작업 크기별 전략

구분	CPU request	Memory request	Memory limit	Object Store

작은 작업 (실험/경량 추론)	70% × CPU limit	= Memory limit	지정값	30%
중간 작업 (일반 추론/배치)	80% × CPU limit	= Memory limit	지정값	30~35%
큰 작업 (Serve/중대형 배치)	90% × CPU limit	= Memory limit	지정값	35~40%



---

5. Head / Worker 그룹 분리

Head Group

작은 작업:

limits: cpu=1, mem=2Gi

requests: cpu=0.7, mem=2Gi

용도: GCS, 대시보드, 간단한 관리 기능


큰 작업:

limits: cpu=2, mem=4Gi

requests: cpu=1.8, mem=4Gi

용도: Serve 컨트롤, 오토스케일링, 안정성 중시



Worker Group

작은 작업:

limits: cpu=2, mem=8Gi

requests: cpu=1.4, mem=8Gi

object-store-memory: 2.4Gi (30%)

minReplicas: 0 / maxReplicas: 3


큰 작업:

limits: cpu=8, mem=32Gi

requests: cpu=7.2, mem=32Gi

object-store-memory: 1140%)

minReplicas: 1 / maxReplicas: 10




---

6. 최종 결론

작은 작업은 경량·탄력성을 우선 → CPU request를 낮춰(70%) 자원 낭비 줄이고 필요 시 빠른 스케일링.

큰 작업은 안정성·지연 최소화를 우선 → CPU request를 높여(90%) 스로틀링 방지, 메모리 여유 있게 확보.

Head와 Worker를 분리하면 관리와 실행 자원의 성격을 나눌 수 있어, 장애가 발생해도 전체 클러스터 안정성 확보 가능.



---

👉 이 문서는 처음 보는 사람도 “왜 CPU request를 퍼센트로 나눴는지”, “왜 메모리는 request=limit인지”, **“왜 object-store-memory를 비율로 잡는지”**까지 논리적으로 이해할 수 있도록 작성된 가이드라인이다.


---

혹시 이 문서를 회사 내부 위키/노션 템플릿으로 정리할 용도야, 아니면 **외부 공유용(가이드 문서)**으로 쓸 계획이야?

