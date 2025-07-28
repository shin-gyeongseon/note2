| Field Path | Type | Description | Default | Required | Expose in UI | Reason |
|------------|------|-------------|---------|----------|--------------|--------|
| enableIngress | boolean | 오퍼레이터가 헤드 서비스를 위한 ingress 객체를 생성할지 여부를 나타냅니다. | false | No | Yes | 관리 서비스 UI에서 ingress를 통해 Ray 대시보드와 서비스에 대한 외부 액세스를 활성화할 수 있게 합니다. |
| serviceType | string | 헤드 서비스의 Kubernetes 서비스 유형 (예: ClusterIP, NodePort, LoadBalancer). 워커가 헤드 포드에 연결하는 데 사용됩니다. | ClusterIP | No | Yes | 사용자가 네트워킹 요구사항에 따라 헤드 서비스의 노출 유형을 선택할 수 있습니다. |
| rayStartParams.dashboard-host | string | 대시보드 서버를 바인딩할 호스트. 포드 외부로 노출하려면 '0.0.0.0'으로 설정합니다. | localhost | No | No | 관리 서비스에서 대시보드 노출이 필요할 경우 자동으로 '0.0.0.0'으로 설정할 수 있습니다; 사용자 입력이 필요 없습니다. |
| rayStartParams.num-cpus | integer | 노드가 가진 CPU 수. | Auto-detected | No | Yes | Ray 스케줄링을 위해 CPU 수를 오버라이드할 수 있게 합니다; 포드 리소스와 일치하지 않을 때 유용합니다. |
| rayStartParams.num-gpus | integer | 노드가 가진 GPU 수. | Auto-detected | No | Yes | num-cpus와 유사하게, GPU 지원 헤드에 대한 것입니다. |
| rayStartParams.memory | integer | Ray가 사용할 수 있는 메모리 양 (바이트 단위). | Auto-detected | No | No | 일반적으로 포드 메모리 제한에서 유도됩니다; 관리 서비스에서 자동 처리합니다. |
| rayStartParams.object-store-memory | integer | 객체 저장소에 사용할 메모리 양 (바이트 단위). | Auto-detected | No | No | 총 메모리를 기반으로 내부적으로 관리됩니다. |
| rayStartParams.resources | string | 노드의 사용자 지정 리소스, 예: '{"AcceleratorType:P4": 1}'. | {} | No | Yes | 고급 사용자가 UI에서 사용자 지정 리소스를 지정할 수 있게 합니다. |
| template.metadata.labels | object | 헤드 포드의 레이블. | {} | No | No | 내부 사용; 기본 UI에서 사용자 지정 레이블을 설정할 필요가 없습니다. |
| template.metadata.annotations | object | 헤드 포드의 어노테이션. | {} | No | No | 레이블과 유사하게, 고급 구성에만 해당합니다. |
| template.spec.containers[0].name | string | 주요 Ray 컨테이너의 이름. | ray-head | Yes | No | 관리 서비스에서 'ray-head'로 고정됩니다; 사용자 선택이 필요 없습니다. |
| template.spec.containers[0].image | string | Ray 헤드 컨테이너의 Docker 이미지. | Determined by rayVersion | No | No | spec.rayVersion을 사용하여 서비스에서 관리합니다; 사용자는 최상위 수준에서 버전을 지정합니다. |
| template.spec.containers[0].resources.limits.cpu | string | 헤드 컨테이너의 CPU 제한. | None | No | Yes | 헤드 노드 크기 조정에 중요합니다; 사용자가 설정할 수 있도록 UI에 노출합니다. |
| template.spec.containers[0].resources.limits.memory | string | 헤드 컨테이너의 메모리 제한. | None | No | Yes | 리소스 할당에 필수적입니다; 사용자가 구성해야 합니다. |
| template.spec.containers[0].resources.limits.nvidia.com/gpu | integer | 헤드 컨테이너의 GPU 제한. | 0 | No | Yes | GPU 지원이 필요할 경우 선택을 위해 노출합니다. |
| template.spec.containers[0].resources.requests.cpu | string | 헤드 컨테이너의 CPU 요청. | None | No | Yes | 제한과 쌍을 이룹니다; 적절한 스케줄링을 보장하기 위해 노출합니다. |
| template.spec.containers[0].resources.requests.memory | string | 헤드 컨테이너의 메모리 요청. | None | No | Yes | 제한과 쌍을 이룹니다. |
| template.spec.containers[0].resources.requests.nvidia.com/gpu | integer | 헤드 컨테이너의 GPU 요청. | 0 | No | Yes | GPU에 대한 제한과 쌍을 이룹니다. |
| template.spec.containers[0].env | array | 헤드 컨테이너의 환경 변수. | [] | No | Yes | 사용자가 사용자 지정 환경 변수를 설정할 수 있게 합니다, 예: Ray 구성이나 앱 특정. |
| template.spec.containers[0].ports | array | 헤드 컨테이너가 노출하는 포트 (예: GCS:6379, Dashboard:8265). | Standard Ray ports | No | No | 표준 포트는 고정됩니다; 고급이 아닌 한 노출할 필요가 없습니다. |
| template.spec.containers[0].volumeMounts | array | 헤드 컨테이너의 볼륨 마운트. | [] | No | No | 고급 저장소 구성에 대한 것입니다; 기본 UI에 노출되지 않습니다. |
| template.spec.volumes | array | 헤드 포드의 볼륨. | [] | No | No | 고급; 관리 서비스에서 영구 저장소를 별도로 처리할 수 있습니다. |
| template.spec.nodeSelector | object | 헤드 포드 스케줄링을 위한 노드 선택기. | {} | No | Yes | 특정 노드에 헤드를 배치하는 데 유용합니다, 예: 특정 레이블이 있는 경우. |
| template.spec.affinity | object | 포드 스케줄링을 위한 어피니티 규칙. | {} | No | No | 고급 스케줄링; 기본 관리 UI에서 필요하지 않습니다. |
| template.spec.tolerations | array | 오염된 노드에 대한 허용. | [] | No | No | 고급; 기본 사용을 위해 클러스터가 taint 없이 구성되었다고 가정합니다. |
| template.spec.securityContext | object | 포드의 보안 컨텍스트. | {} | No | No | 보안 설정; 서비스 제공자가 관리합니다. 
