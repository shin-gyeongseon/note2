아래는 fabric8 6.x로 RayCluster / RayService / RayJob가 “수정되었을 때 변경을 적용”하는 실전 플레북입니다.
(짧은 요약 → 공통 패턴 → 리소스별 적용법 → 적용 완료 판정/롤백까지)


---

한눈에 요약

가장 안전한 업데이트 순서:

1. 서버사이드 적용(SSA) 또는 MERGE/JSON Patch로 spec만 수정


2. 충돌 시 resourceVersion 갱신 후 재시도(낙관적 락)


3. 적용 완료 판정: status.conditions(Ready 등) 또는 observedGeneration==metadata.generation 확인



RayService: upgradeStrategy.type=NewCluster면 serveConfigV2/클러스터 스펙 변경 시 블루-그린 전환으로 반영. None이면 인플레이스(완전 보장 X) → 가급적 NewCluster 권장.

RayCluster: 스케일/이미지 변경은 PATCH로 반영. 크게 바뀌면 spec.suspend=true→false로 재조립이 가장 확실.

RayJob: 이미 제출된 잡의 entrypoint 등 변경은 그 잡 재시작이 아님. 보통 suspend→수정→suspend=false로 재제출(또는 새 이름으로 생성).

immutable/민감 필드는 recreate가 빠릅니다(예: 이름, 일부 템플릿의 깊은 필드).

finalizer로 삭제/재생성 막힐 때: 운영 승인하에 finalizer 제거 후 재생성.



---

공통: 패치/적용 4가지 패턴

1) 서버사이드 적용(SSA: Server-Side Apply, 추천)

장점: 선언적, 충돌에 강함, 필드 소유권 관리.


var yaml = """
apiVersion: ray.io/v1
kind: RayService
metadata:
  name: my-svc
  namespace: ns
  labels:
    owner: platform
spec:
  upgradeStrategy: { type: NewCluster }
  # ... (나머지 선언)
""";

client.genericKubernetesResources(RayCrd.RAYSVC_V1)
  .inNamespace("ns")
  .resource(Serialization.unmarshal(yaml, GenericKubernetesResource.class))
  .serverSideApply(b -> b.withForceConflict(true).withFieldManager("ops-tool"));

> ❗️withForceConflict(true)는 신중히. 기본은 false로 두고, 필요 시에만 사용.



2) JSON Merge Patch (RFC 7386) – 간단 변경

client.genericKubernetesResources(RayCrd.RAYCLUSTER_V1)
  .inNamespace(ns).withName("my-cluster")
  .patch(PatchType.MERGE, """
    {"spec":{"workerGroupSpecs":[{"groupName":"wg","replicas":3}]}}
  """);

3) JSON Patch (RFC 6902) – 배열/경로 제어 필요할 때

client.genericKubernetesResources(RayCrd.RAYSVC_V1)
  .inNamespace(ns).withName("my-svc")
  .patch(PatchType.JSON, """
    [
      {"op":"replace","path":"/spec/upgradeStrategy/type","value":"NewCluster"},
      {"op":"add","path":"/metadata/labels/changedAt","value":"2025-08-26"}
    ]
  """);

4) Get→Mutate→Replace(낙관적 락)

var gkr = client.genericKubernetesResources(RayCrd.RAYCLUSTER_V1)
                .inNamespace(ns).withName("my-cluster").get();
var rv = gkr.getMetadata().getResourceVersion();
// spec 수정
((Map)gkr.getAdditionalProperties().get("spec")).put("suspend", true);
// RV 유지한 채 replace
gkr.getMetadata().setResourceVersion(rv);
client.genericKubernetesResources(RayCrd.RAYCLUSTER_V1)
      .inNamespace(ns).resource(gkr).replace();

> 409 Conflict 시 최신 리소스로 재시도.




---

리소스별 적용법

A. RayService (추천: 무중단 갱신 경로)

상황별 권장 절차

1. Serve 그래프/코드 변경 (spec.serveConfigV2 수정):

upgradeStrategy.type=NewCluster로 설정 → JSON/Merge/SSA로 패치

상태 확인: 새 클러스터 Ready → 트래픽 전환 → 이전 클러스터 정리(오퍼레이터가 수행)



2. 클러스터 스펙 변경 (rayClusterConfig 이미지/리소스/워크로드 수 등):

동일하게 NewCluster 전략 권장



3. 인플레이스가 불가피(type=None):

변경 폭을 작게, 순차 패치 → Ready 컨디션 감시

리스크: 과거 버전 조합/워커 재배치가 매끄럽지 않을 수 있음



4. 적용 코드 예시(SSA로 serveConfigV2만 교체):



var patch = """
apiVersion: ray.io/v1
kind: RayService
metadata: { name: my-svc, namespace: ns }
spec:
  upgradeStrategy: { type: NewCluster }
  serveConfigV2: |
    applications:
      - name: app
        route_prefix: /
        import_path: example:app
        deployments:
          - name: D
            num_replicas: 3
""";
client.genericKubernetesResources(RayCrd.RAYSVC_V1)
  .inNamespace("ns")
  .resource(Serialization.unmarshal(patch, GenericKubernetesResource.class))
  .serverSideApply(b -> b.withFieldManager("ops-tool"));

B. RayCluster

상황별 권장 절차

1. 스케일 조정: workerGroupSpecs[].replicas MERGE 패치


2. 이미지/리소스/노드셀렉터 등 템플릿 변경: MERGE/JSON Patch → 오퍼레이터가 롤링/재생성


3. 대규모 변경(불안정/꼬임 방지):

spec.suspend=true → 기존 리소스/파드 정리

스펙 정리 후 spec.suspend=false → 깨끗한 재조립



4. 상태 확인: status.conditions(HeadPodRunningAndReady 등) 또는 legacy status.state 보조 확인



예시(스케일):

patch(client, ns, "my-cluster", RayCrd.RAYCLUSTER_V1,
  """
  {"spec":{"workerGroupSpecs":[{"groupName":"wg","replicas":5}]}}
  """);

예시(서스펜드 토글 재조립):

patch(client, ns, "my-cluster", RayCrd.RAYCLUSTER_V1, "{ \"spec\": { \"suspend\": true } }");
// 스펙 수정들…
patch(client, ns, "my-cluster", RayCrd.RAYCLUSTER_V1, "{ \"spec\": { \"suspend\": false } }");

C. RayJob

핵심 이해: 제출된 잡은 “실행 단위”라서 스펙만 바꾼다고 재시작되지 않음.
권장 절차

1. 실행 중/대기 중 잡 일시중지: spec.suspend=true


2. 수정: entrypoint, rayClusterSpec(에페메랄), clusterSelector 등


3. 재개/재제출: spec.suspend=false (혹은 새 metadata.name으로 새 잡 생성)


4. 완료 판정: status.jobStatus(SUCCEEDED/FAILED), status.jobDeploymentStatus(Complete 등)



예시(수정 후 재개):

// suspend
patch(client, ns, "my-job", RayCrd.RAYJOB_V1, "{ \"spec\": { \"suspend\": true } }");
// entrypoint 변경
patch(client, ns, "my-job", RayCrd.RAYJOB_V1,
  """
  {"spec":{"entrypoint":"python -m train --epochs=10"}}
  """);
// resume
patch(client, ns, "my-job", RayCrd.RAYJOB_V1, "{ \"spec\": { \"suspend\": false } }");


---

적용 완료를 “확실히” 확인하는 법

공통 확인 루틴

世代 일치: metadata.generation == status.observedGeneration

컨디션:

RayService: Ready(또는 serviceStatus=Running)

RayCluster: HeadPodRunningAndReady, AutoscalerHealthy 등

RayJob: status.jobStatus in {SUCCEEDED, FAILED} + jobDeploymentStatus=Complete


타임아웃/재시도: 인포머/워처로 이벤트 수집, 혹은 폴링(2~5초 간격)로 일정 시간 내 확인


샘플(간단 폴링):

boolean awaitReady(GenericKubernetesResource res, String readyType) {
  var st = (Map<?,?>) res.getAdditionalProperties().get("status");
  if (st == null) return false;
  var conds = (List<Map<String,Object>>) st.get("conditions");
  if (conds == null) return false;
  return conds.stream().anyMatch(c ->
      readyType.equals(c.get("type")) && "True".equals(c.get("status")));
}


---

충돌/롤백/재생성

409 Conflict: 최신 리소스 다시 get → RV 갱신 → 재적용

적용 후 장애:

RayService(NewCluster): 이전 Active로 자동 안전망. 새 클러스터 Ready 실패 시 자동 롤백(운영 버전에 따라 동작 다름)

RayCluster: 변경폭이 크면 suspend→false 재조립이 가장 깨끗함

RayJob: 실패 시 로그/아티팩트 확인 후 새 이름으로 재제출이 명확


immutable 충돌: 이름/일부 selector/템플릿의 깊은 필드 등은 삭제→재생성이 빠름

finalizer로 삭제 지연 시: 운영 승인 하에 JSON Patch로 /metadata/finalizers 제거(최후 수단)



---

운영 팁

No-op 라벨/어노테이션 추가로 강제 리컨실 트리거 가능(예: kubectl.kubernetes.io/restartedAt).

**멀티라인 serveConfigV2**는 YAML literal(|) 유지. 문자열 직렬화 시 공백/인덴트 깨지지 않게 관리(SSA/YAML apply 권장).

Watch 기반 타임라인: SharedInformer로 세 CR 모두 이벤트 기록(배포 히스토리/MTTR 분석에 유용).

PropagationPolicy: 리소스 삭제 시 Foreground 우선(자식 정리 보장).



---

필요하시면 위 내용을 **Spring @Service 유틸(SSA/패치/대기/워처 포함)**로 묶어 드릴게요. 현재 쓰는 fabric8 6.x + ray.io/v1 기준으로 바로 붙여 쓸 수 있게 샘플 코드도 만들어 드립니다.

