import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// === 프로젝트 내 Ray CR 클래스 경로에 맞게 교체 ===
import your.pkg.ray.v1.RayService;
import your.pkg.ray.v1.RayCluster;

public class RayDeletionOps {

    /**
     * RayService를 삭제하고, 해당 RayService가 소유(or 라벨 관리)하는 RayCluster가 모두 제거될 때까지 기다린 뒤,
     * nextStep을 실행한다.
     *
     * @param client          fabric8 KubernetesClient (v6.3.1)
     * @param namespace       대상 네임스페이스
     * @param rayServiceName  삭제할 RayService 이름
     * @param timeout         전체 대기 타임아웃
     * @param heartbeatSec    하트비트 로그 주기(초)
     * @param nextStep        정리 완료 후 실행할 후속 작업(Runnable)
     * @param optionalLabelKV (선택) RayService가 RayCluster에 부여한 라벨 키/값 (ex: "ray.io/managed-by" -> rayServiceName)
     *                        null 또는 빈 맵이면 라벨 조건을 사용하지 않고 OwnerReference만으로 추적
     * @return true = 정리 완료 후 nextStep까지 실행, false = 타임아웃 등으로 미완료
     */
    public static boolean deleteRayServiceAndAwaitClusterCleanup(
            KubernetesClient client,
            String namespace,
            String rayServiceName,
            Duration timeout,
            int heartbeatSec,
            Runnable nextStep,
            Map<String, String> optionalLabelKV
    ) throws InterruptedException {

        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(rayServiceName, "rayServiceName");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(nextStep, "nextStep");

        MixedOperation<RayService, ?, Resource<RayService>> svcClient = client.resources(RayService.class);
        MixedOperation<RayCluster, ?, Resource<RayCluster>> rcClient = client.resources(RayCluster.class);

        // 0) RayService 사전 조회(Uid 확보). 이미 없으면 UID는 null
        String svcUid = null;
        RayService before = null;
        try {
            before = svcClient.inNamespace(namespace).withName(rayServiceName).get();
            if (before != null && before.getMetadata() != null) {
                svcUid = before.getMetadata().getUid();
            }
        } catch (KubernetesClientException e) {
            // 404 등은 무시하고 진행 (이미 없는 경우로 간주)
            if (e.getCode() != 404) throw e;
        }

        // 1) RayService 삭제(없어도 OK)
        try {
            svcClient.inNamespace(namespace).withName(rayServiceName).delete();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 404) throw e;
        }

        // 2) 추적 대상 필터(OwnerReference + 옵션 라벨)
        final String uidForMatch = svcUid; // effectively final
        Predicate<RayCluster> clusterMatcher = rc -> {
            boolean ownerHit = false;
            if (uidForMatch != null && rc != null && rc.getMetadata() != null && rc.getMetadata().getOwnerReferences() != null) {
                for (OwnerReference or : rc.getMetadata().getOwnerReferences()) {
                    if (or != null && uidForMatch.equals(or.getUid())) {
                        ownerHit = true;
                        break;
                    }
                }
            }
            boolean labelHit = true;
            if (optionalLabelKV != null && !optionalLabelKV.isEmpty()) {
                Map<String, String> labels = rc.getMetadata() != null ? rc.getMetadata().getLabels() : null;
                for (Map.Entry<String, String> e : optionalLabelKV.entrySet()) {
                    String val = labels != null ? labels.get(e.getKey()) : null;
                    if (!Objects.equals(val, e.getValue())) {
                        labelHit = false;
                        break;
                    }
                }
            }
            // 라벨 조건이 주어졌다면: (ownerHit || labelHit)
            // 라벨 조건이 비어있다면: ownerHit만
            return (optionalLabelKV != null && !optionalLabelKV.isEmpty()) ? (ownerHit || labelHit) : ownerHit;
        };

        // 3) 초기 대상 집합 수집
        Set<String> targetNames = new ConcurrentSkipListSet<>();
        rcClient.inNamespace(namespace).list().getItems().stream()
                .filter(clusterMatcher)
                .map(rc -> rc.getMetadata().getName())
                .forEach(targetNames::add);

        // 만약 RayService UID를 못 잡았고 라벨도 없다면, 삭제 직후엔 대상이 0일 수 있다.
        // 이 경우 RayService가 만든 RayCluster를 못찾을 수 있으니 라벨 제공을 권장.
        System.out.printf("[RayCleanup] Initial RayClusters tracked by %s: %s%n",
                rayServiceName, targetNames.isEmpty() ? "[]" : targetNames);

        // 이미 대상이 없으면 바로 nextStep 실행
        if (targetNames.isEmpty()) {
            nextStep.run();
            return true;
        }

        Instant start = Instant.now();
        CountDownLatch done = new CountDownLatch(1);

        // 4) 하트비트 & 보강 폴링(누락 이벤트/410 대비)
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            long sec = Duration.between(start, Instant.now()).toSeconds();
            System.out.printf("[RayCleanup] waiting… %ds elapsed, remaining clusters=%s%n", sec, targetNames);
        }, heartbeatSec, heartbeatSec, TimeUnit.SECONDS);

        ScheduledFuture<?> repoll = scheduler.scheduleAtFixedRate(() -> {
            try {
                // 현재 남아있는(필터에 맞는) 클러스터 이름 즉시 재수집
                Set<String> live = rcClient.inNamespace(namespace).list().getItems().stream()
                        .filter(clusterMatcher)
                        .map(rc -> rc.getMetadata().getName())
                        .collect(Collectors.toSet());
                // 추적 집합과 동기화
                targetNames.retainAll(live);
                if (targetNames.isEmpty()) {
                    done.countDown();
                }
            } catch (Throwable t) {
                System.out.printf("[RayCleanup] repoll error: %s%n", t.getMessage());
            }
        }, 3, 3, TimeUnit.SECONDS);

        // 5) 이벤트 워치(네임스페이스 전체 → 필터는 메모리에서 적용)
        Watch watch = rcClient.inNamespace(namespace).watch(new Watcher<RayCluster>() {
            @Override
            public void eventReceived(Action action, RayCluster obj) {
                String name = (obj != null && obj.getMetadata() != null) ? obj.getMetadata().getName() : "null";
                if (obj != null && clusterMatcher.test(obj)) {
                    switch (action) {
                        case ADDED:
                        case MODIFIED:
                            // 아직 남아있는 대상이면 유지 (혹시 누락된 이름이 새로 잡히면 추가)
                            targetNames.add(name);
                            break;
                        case DELETED:
                            targetNames.remove(name);
                            break;
                        default:
                            break;
                    }
                    System.out.printf("[RayCleanup] EVENT action=%s name=%s remaining=%s%n", action, name, targetNames);
                    if (targetNames.isEmpty()) {
                        done.countDown();
                    }
                }
            }
            @Override
            public void onClose(WatcherException cause) {
                // 스트림 종료(410 포함). 폴링이 계속 동작하므로 즉시 재시도 안 하고 완료 신호만 체크
                System.out.printf("[RayCleanup] watch closed: %s%n", cause != null ? cause.getMessage() : "normal");
                if (targetNames.isEmpty()) {
                    done.countDown();
                }
            }
        });

        boolean completed;
        try {
            completed = done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            try { watch.close(); } catch (Exception ignore) {}
            heartbeat.cancel(true);
            repoll.cancel(true);
            scheduler.shutdownNow();
        }

        if (completed) {
            System.out.printf("[RayCleanup] all related RayClusters removed. proceeding next step…%n");
            nextStep.run();
            return true;
        } else {
            System.out.printf("[RayCleanup] timeout. remaining RayClusters=%s%n", targetNames);
            return false;
        }
    }

    // 편의 오버로드(라벨 미지정)
    public static boolean deleteRayServiceAndAwaitClusterCleanup(
            KubernetesClient client,
            String namespace,
            String rayServiceName,
            Duration timeout,
            int heartbeatSec,
            Runnable nextStep
    ) throws InterruptedException {
        return deleteRayServiceAndAwaitClusterCleanup(
                client, namespace, rayServiceName, timeout, heartbeatSec, nextStep, Collections.emptyMap()
        );
    }

    // 편의 오버로드(대표적 라벨 키 사용 예시)
    public static boolean deleteRayServiceAndAwaitClusterCleanupWithManagedByLabel(
            KubernetesClient client,
            String namespace,
            String rayServiceName,
            Duration timeout,
            int heartbeatSec,
            Runnable nextStep
    ) throws InterruptedException {
        Map<String, String> label = Map.of("ray.io/managed-by", rayServiceName); // 환경에 맞게 키 조정
        return deleteRayServiceAndAwaitClusterCleanup(
                client, namespace, rayServiceName, timeout, heartbeatSec, nextStep, label
        );
    }
}
