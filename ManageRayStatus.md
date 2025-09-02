# Ray 리소스별 배포 상태 확인 정리 (KubeRay 1.3+)

Ray 리소스 배포 상태를 확인할 때는 **1차적으로 Conditions**, **2차적으로 Kubernetes Events**를 활용하는 것이 가장 효과적이다.  
아래는 RayCluster, RayService, RayJob 각각의 정상/비정상 판별 기준과 확인 방법을 정리한 문서이다.

---

## ✅ RayCluster
- **정상 (OK)**
  - `status.conditions`:
    - `HeadPodReady=True`
    - `RayClusterProvisioned=True`
  - (보조) `status.state == "ready"` → 비권장
- **비정상 (NOK)**
  - `ReplicaFailure=True`
  - `HeadPodReady=False`가 지속됨
  - `RayClusterSuspending=True` → 중단 중  
    `RayClusterSuspended=True` → 중지됨
- **확인 방법**
  1. **Conditions**  
     ```bash
     kubectl get raycluster <name> -o jsonpath='{.status.conditions}'
     ```
  2. **Events**  
     ```bash
     kubectl describe raycluster <name> | sed -n '/Events:/,$p'
     ```

---

## ✅ RayService (Serve 앱)
- **정상 (OK)**
  - `status.conditions` → `RayServiceReady=True`
  - `status.activeServiceStatus.rayClusterStatus` 정상
  - `status.numServeEndpoints > 0`
- **비정상 (NOK)**
  - `RayServiceReady=False/Unknown`
  - `pendingServiceStatus`가 오래 유지됨
  - 워커 프로브 실패 (`/-/healthz`)
- **확인 방법**
  1. **Conditions**  
     ```bash
     kubectl get rayservice <name> -o jsonpath='{.status.conditions}'
     ```
  2. **Events**  
     ```bash
     kubectl describe rayservice <name> | sed -n '/Events:/,$p'
     ```

---

## ✅ RayJob
- **정상 (OK)**
  - `status.jobDeploymentStatus == "Complete"`
  - `status.jobStatus == "SUCCEEDED"`
- **비정상 (NOK)**
  - `jobDeploymentStatus == "Failed"`
  - `jobStatus == "FAILED"`
  - (둘 다 확인 필수, 불일치 이슈 사례 존재)
- **확인 방법**
  1. **Conditions**  
     ```bash
     kubectl get rayjob <name> -o jsonpath='{.status.conditions}'
     ```
  2. **Events**  
     ```bash
     kubectl describe rayjob <name> | sed -n '/Events:/,$p'
     ```

---

# 📌 공통 체크리스트

### 1) Conditions 요약 보기
```bash
# Cluster
kubectl get raycluster <name> -o jsonpath='{range .status.conditions[*]}{.type}={.status} reason={.reason} msg="{.message}"\n{end}'

# Service
kubectl get rayservice <name> -o jsonpath='{range .status.conditions[*]}{.type}={.status} reason={.reason} msg="{.message}"\n{end}'

# Job
kubectl get rayjob <name> -o jsonpath='{range .status.conditions[*]}{.type}={.status} reason={.reason} msg="{.message}"\n{end}'

2) OK 판정 기대값

RayCluster → HeadPodReady=True & RayClusterProvisioned=True

RayService → RayServiceReady=True & numServeEndpoints > 0

RayJob → "Complete SUCCEEDED"


3) Events 확인 (구체적 실패 사유 파악)

kubectl describe raycluster/<name> | sed -n '/Events:/,$p'
kubectl describe rayservice/<name> | sed -n '/Events:/,$p'
kubectl describe rayjob/<name>     | sed -n '/Events:/,$p'


---

요약 원칙

Conditions로 정상/비정상을 1차 판별한다.

Events를 통해 상세 원인을 확인한다.

레거시 필드(state, serviceStatus)는 참고용으로만 활용한다.




