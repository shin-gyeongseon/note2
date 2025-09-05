KubeRay에서 RayCluster로 Ray Serve 앱 노출하다가 502/111 맞은 썰 (해결 가이드)

TL;DR

증상: RayService는 잘 되는데 RayCluster는 Ingress에서 502 / 컨트롤러 로그에 111: Connection refused. 포트포워딩(127.0.0.1:8000)은 정상.

핵심 원인: Serve HTTP 프록시가 기본값으로 127.0.0.1 에 바인딩돼 있어 Pod 내부에서만 수신 → Ingress/Service가 붙지 못함. serve.start()는 클러스터 전역(한 번 떠면 옵션 변경 불가). 

해결:

1. 처음 시작할 때 serve.start(http_options=HTTPOptions(host="0.0.0.0", port=<원하는 포트>))


2. RayCluster head 컨테이너/Service/Ingress 포트를 같게 맞추기


3. 필요 시 NetworkPolicy로 ingress-nginx → 대상 네임스페이스:포트 허용


4. 운영은 Serve config(YAML) 로 http_options 고정 운용 권장. 





---

배경 — 내가 겪은 상황

KubeRay 1.3+ 환경에서 RayCluster와 RayService를 각각 배포.

둘 다 Serve 앱을 containerPort 8000 기준으로 노출하려 했고, Service/Ingress도 8000으로 구성.

RayService는 정상, RayCluster는 Ingress 502. Ingress 컨트롤러 로그에 connect() failed (111: Connection refused)가 반복. 포트포워딩으로 127.0.0.1:8000 접근은 정상.
→ 즉, 업스트림 연결 자체가 거부된 전형적 케이스. 



---

원인 분석 — 왜 RayService는 되고 RayCluster는 실패했나

RayService는 컨트롤러가 Serve 앱 배포와 HTTP 노출을 자동으로 챙겨줘서, Ingress만 붙여도 되는 경우가 많다.

RayCluster는 말 그대로 클러스터만 만든다. Serve는 직접 시작해야 하고, HTTP 바인딩(호스트/포트) 도 사용자가 명시해야 한다.

디폴트 값: Serve의 HTTPOptions는 port=8000, host=0.0.0.0(or 127.0.0.1 버전/컨텍스트에 따라 다름). Kubernetes에선 0.0.0.0로 명시해야 클러스터 외부/다른 Pod에서 접근 가능하다. 포트는 필요 시 변경. 

중요한 점: serve.start()는 클러스터 스코프 설정(HTTP 프록시) 이라 이미 떠 있으면 새 http_options가 무시된다 → 로그에 “New http options will not be applied”. 이때는 shutdown 후 재시작 필요. 



---

내가 본 로그 & 의미

Connecting to existing serve app in namespace "serve". New http options will not be applied
→ 이미 Serve HTTP 서버가 떠 있음. 새 host/port 인자는 무시.

Application 'example-serve' is ready at http://127.0.0.1:8000/sample
→ 루프백에만 열려 있어 Service/Ingress가 붙을 수 없음.



---

해결 절차 — 단계별 가이드

1) Serve HTTP 바인딩을 외부 수신으로 시작

처음 시작할 때 단 한 번 아래처럼 설정:


import ray
from ray import serve
from ray.serve.config import HTTPOptions

ray.init(address="auto")
serve.start(http_options=HTTPOptions(host="0.0.0.0", port=8000))  # ← 핵심

이후 앱 라우팅:


@serve.deployment
class Handler:
    def __call__(self, request):
        return "ok"

app = Handler.bind()
serve.run(app, route_prefix="/sample")

이미 떠 있는 상태라면:


from ray import serve
serve.shutdown()  # 내려서
# 다시 위의 serve.start(...)로 시작

Serve HTTP 설정은 serve.start에서만; serve.run은 라우팅을 담당. 

2) RayCluster(head) 컨테이너/Service/Ingress 포트 정합성 맞추기

RayCluster head 컨테이너에 containerPort: 8000:


spec:
  headGroupSpec:
    template:
      spec:
        containers:
        - name: ray-head
          ports:
            - name: dashboard
              containerPort: 8265
            - name: serve
              containerPort: 8000

전용 Service:


apiVersion: v1
kind: Service
metadata:
  name: <cluster>-serve-svc
  namespace: <ns>
spec:
  selector:
    ray.io/cluster: <cluster>
    ray.io/node-type: head
  ports:
    - name: serve
      port: 8000        # Service 포트
      targetPort: 8000  # 컨테이너 포트
  type: ClusterIP

Ingress는 위 Service:8000으로:


apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: serve-app
  namespace: <ns>
spec:
  rules:
  - host: <host>
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: <cluster>-serve-svc
            port:
              number: 8000

RayCluster CR의 필드/예시는 공식 문서 흐름과 합치. 

3) NetworkPolicy 사용하는 클러스터라면

ingress-nginx(또는 다른 컨트롤러) 네임스페이스 → 대상 네임스페이스:8000/TCP 허용 규칙 필요.

Ingress의 111: Connection refused는 업스트림 포트가 수신 안 하거나 막혔을 때 흔히 관찰된다. 


4) 운영 레벨: Serve config(YAML) 로 고정

코드 대신 YAML로 http_options와 앱 라우팅을 고정:


# serve-config.yaml
http_options:
  host: 0.0.0.0
  port: 8000
applications:
  - name: example-serve
    route_prefix: /sample
    import_path: your_module:app  # app = Handler.bind()

serve deploy serve-config.yaml 또는 REST로 적용. Kubernetes에선 host를 0.0.0.0로 두라고 공식 가이드가 명시. 



---

왜 포트포워딩은 됐나?

kubectl port-forward는 API 서버 터널을 통해 Pod 내부 루프백(127.0.0.1) 로 직접 들어간다.

그래서 Serve가 127.0.0.1:8000에만 열려 있어도 포워딩은 정상, Ingress는 실패.



---

빠른 점검 체크리스트 (복붙용)

# 1) Serve HTTP 리슨 상태
kubectl -n <ns> exec -it <head-pod> -- sh -lc 'ss -lntp | grep ":8000" || netstat -lntp | grep ":8000"'
# → 0.0.0.0:8000 이면 OK, 127.0.0.1:8000 이면 외부 노출 불가

# 2) Serve 앱이 떠 있는지 (REST는 대시보드: 8265)
kubectl -n <ns> port-forward svc/<head-svc> 8265:8265 &
curl -s http://127.0.0.1:8265/api/serve/applications/ | jq
# (Serve REST는 대시보드 포트에서 동작) 
# 참고: Ray Serve API 문서
# 확인 후 포워딩 종료

# 3) Service/Endpoints 정합성
kubectl -n <ns> get svc <cluster>-serve-svc -o wide
kubectl -n <ns> get endpoints <cluster>-serve-svc -o yaml | yq '.subsets'

# 4) Ingress → 업스트림 연결 확인(111 발생 여부)
kubectl -n ingress-nginx logs deploy/ingress-nginx-controller | grep "connect() failed (111"

REST 포트(8265)와 Serve HTTP 포트(8000)는 서로 다르다는 점도 기억. 


---

레시피 모음 (테스트/데모용)

A) 7777 포트로 테스트

import ray
from ray import serve
from ray.serve.config import HTTPOptions

ray.init(address="auto")
serve.start(http_options=HTTPOptions(host="0.0.0.0", port=7777))

@serve.deployment
class TestHandler:
    def __call__(self, request):
        return "Hello from 7777!"

app = TestHandler.bind()
serve.run(app, route_prefix="/test")

→ RayCluster head 컨테이너/Service/Ingress도 모두 7777로 맞추기.

B) 운영 고정 — Serve config

http_options:
  host: 0.0.0.0
  port: 8000
applications:
  - name: demo
    route_prefix: /
    import_path: demo_app:app

→ serve deploy serve-config.yaml 로 적용. 


---

참고 자료

HTTPOptions (Ray Serve) — host/port 기본·권장값, Kubernetes에서 host=0.0.0.0 필요. 

serve.start API — 클러스터 스코프 설정(이미 떠 있으면 새 옵션 미적용). 

RayCluster CR 구성 가이드 — 컨테이너 포트/템플릿 예시. 

Nginx Ingress 111 의미 — 업스트림에 접속 자체가 거부되는 전형적 상황. 

Serve 프로덕션 가이드/Config — YAML로 http_options 고정 및 Kubernetes 배포 권장. 



---

마무리

원인은 단순하지만 흔히 놓친다: Serve HTTP를 0.0.0.0로 바인딩하지 않으면 Ingress/Service는 절대 접근 못 한다.

해결은 일관성: serve.start(http_options) → 컨테이너포트 → Service → Ingress 를 같은 포트로 맞추고, 처음부터 YAML(Serve config) 로 고정 운용하면 재발을 막을 수 있다.



---

Q1

우리 팀 표준으로 Serve를 항상 YAML(config)로 배포하고 host=0.0.0.0을 강제할까요, 아니면 코드 기반 시작을 유지할까요?

Q2

Ingress path/rewrite와 route_prefix 충돌을 방지하는 네이밍/라우팅 룰을 정해둘까요?

Q3

**NetworkPolicy를 쓰는 클러스터라면 ingress-nginx → 대상 네임스페이스:포트(예: 8000) 허용 규칙을 기본 템플릿에 포함할까요?**

