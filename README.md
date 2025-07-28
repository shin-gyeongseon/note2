| Field Path | Type | Description | Default | Required | Expose in UI | Reason |
|------------|------|-------------|---------|----------|--------------|--------|
| groupName | string | 여러 워커 그룹을 가질 수 있으며, 이름으로 구분합니다. | - | No | Yes | 워커 그룹을 식별하고 관리하기 위해 UI에서 필수적으로 노출합니다. |
| replicas | integer | 이 워커 그룹에 대한 원하는 포드 수입니다. | 0 | No | Yes | 클러스터 크기를 결정하는 핵심 필드; 사용자가 직접 설정할 수 있게 합니다. |
| minReplicas | integer | 이 워커 그룹에 대한 최소 원하는 포드 수입니다. | 0 | No | Yes | 스케일링 범위를 설정하기 위해 UI에 노출합니다. |
| maxReplicas | integer | 이 워커 그룹에 대한 최대 원하는 포드 수입니다. | 2147483647 | No | Yes | 스케일링 상한을 설정하기 위해 UI에 노출합니다. |
| idleTimeoutSeconds | integer | v2 오토스케일러가 이 유형의 유휴 워커 포드를 종료하기 전에 기다리는 초 단위 시간입니다. Ray Autoscaler가 활성화된 경우에만 사용됩니다. | - | No | Yes | 오토스케일링이 활성화된 관리 서비스에서 유휴 시간 설정을 허용합니다. |
| rayStartParams.address | string | 워커가 연결할 헤드의 주소 (예: ray://head-svc:10001). | - | Yes | No | 관리 서비스에서 자동으로 설정됩니다; 사용자 입력이 필요 없습니다. |
| rayStartParams.num-cpus | integer | 노드가 가진 CPU 수. | Auto-detected | No | Yes | Ray 스케줄링을 위해 CPU 수를 오버라이드할 수 있게 합니다; 포드 리소스와 일치하지 않을 때 유용합니다. |
| rayStartParams.num-gpus | integer | 노드가 가진 GPU 수. | Auto-detected | No | Yes | num-cpus와 유사하게, GPU 지원 워커에 대한 것입니다. |
| rayStartParams.memory | integer | Ray가 사용할 수 있는 메모리 양 (바이트 단위). | Auto-detected | No | No | 일반적으로 포드 메모리 제한에서 유도됩니다; 관리 서비스에서 자동 처리합니다. |
| rayStartParams.object-store-memory | integer | 객체 저장소에 사용할 메모리 양 (바이트 단위). | Auto-detected | No | No | 총 메모리를 기반으로 내부적으로 관리됩니다. |
| rayStartParams.resources | string | 노드의 사용자 지정 리소스, 예: '{"AcceleratorType:P4": 1}'. | {} | No | Yes | 고급 사용자가 UI에서 사용자 지정 리소스를 지정할 수 있게 합니다. |
| template.metadata.labels | object | 워커 포드의 레이블. | {} | No | No | 내부 사용; 기본 UI에서 사용자 지정 레이블을 설정할 필요가 없습니다. |
| template.metadata.annotations | object | 워커 포드의 어노테이션. | {} | No | No | 레이블과 유사하게, 고급 구성에만 해당합니다. |
| template.spec.containers[0].name | string | 주요 Ray 컨테이너의 이름. | ray-worker | Yes | No | 관리 서비스에서 'ray-worker'로 고정됩니다; 사용자 선택이 필요 없습니다. |
| template.spec.containers[0].image | string | Ray 워커 컨테이너의 Docker 이미지. | Determined by rayVersion | No | No | spec.rayVersion을 사용하여 서비스에서 관리합니다; 사용자는 최상위 수준에서 버전을 지정합니다. |
| template.spec.containers[0].resources.limits.cpu | string | 워커 컨테이너의 CPU 제한. | None | No | Yes | 워커 노드 크기 조정에 중요합니다; 사용자가 설정할 수 있도록 UI에 노출합니다. |
| template.spec.containers[0].resources.limits.memory | string | 워커 컨테이너의 메모리 제한. | None | No | Yes | 리소스 할당에 필수적입니다; 사용자가 구성해야 합니다. |
| template.spec.containers[0].resources.limits.nvidia.com/gpu | integer | 워커 컨테이너의 GPU 제한. | 0 | No | Yes | GPU 지원이 필요할 경우 선택을 위해 노출합니다. |
| template.spec.containers[0].resources.requests.cpu | string | 워커 컨테이너의 CPU 요청. | None | No | Yes | 제한과 쌍을 이룹니다; 적절한 스케줄링을 보장하기 위해 노출합니다. |
| template.spec.containers[0].resources.requests.memory | string | 워커 컨테이너의 메모리 요청. | None | No | Yes | 제한과 쌍을 이룹니다. |
| template.spec.containers[0].resources.requests.nvidia.com/gpu | integer | 워커 컨테이너의 GPU 요청. | 0 | No | Yes | GPU에 대한 제한과 쌍을 이룹니다. |
| template.spec.containers[0].env | array | 워커 컨테이너의 환경 변수. | [] | No | Yes | 사용자가 사용자 지정 환경 변수를 설정할 수 있게 합니다, 예: Ray 구성이나 앱 특정. |
| template.spec.containers[0].ports | array | 워커 컨테이너가 노출하는 포트. | Standard Ray ports | No | No | 표준 포트는 고정됩니다; 고급이 아닌 한 노출할 필요가 없습니다. |
| template.spec.containers[0].volumeMounts | array | 워커 컨테이너의 볼륨 마운트. | [] | No | No | 고급 저장소 구성에 대한 것입니다; 기본 UI에 노출되지 않습니다. |
| template.spec.volumes | array | 워커 포드의 볼륨. | [] | No | No | 고급; 관리 서비스에서 영구 저장소를 별도로 처리할 수 있습니다. |
| template.spec.nodeSelector | object | 워커 포드 스케줄링을 위한 노드 선택기. | {} | No | Yes | 특정 노드에 워커를 배치하는 데 유용합니다, 예: 특정 레이블이 있는 경우. |
| template.spec.affinity | object | 포드 스케줄링을 위한 어피니티 규칙. | {} | No | No | 고급 스케줄링; 기본 관리 UI에서 필요하지 않습니다. |
| template.spec.tolerations | array | 오염된 노드에 대한 허용. | [] | No | No | 고급; 기본 사용을 위해 클러스터가 taint 없이 구성되었다고 가정합니다. |
| template.spec.securityContext | object | 포드의 보안 컨텍스트. | {} | No | No | 보안 설정; 서비스 제공자가 관리합니다. |
| scaleStrategy.workersToDelete | array | 삭제할 워커 목록. | [] | No | No | 스케일 다운 전략; 고급 기능으로 기본 UI에서 노출하지 않습니다. |
| numOfHosts | integer | 복제본당 생성할 호스트 수입니다. | 1 | No | No | 고급 구성; 기본적으로 1로 충분하며 UI 노출 필요 없음. |
| suspend | boolean | 워커 그룹을 일시 중지할지 여부입니다. 일시 중지된 워커 그룹은 모든 포드가 삭제됩니다. | - | No | No | RayJob DeletionStrategy에 사용되는 내부 API; 사용자에게 노출되지 않습니다. 
