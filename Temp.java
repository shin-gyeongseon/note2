package com.example.rayops.service;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.kubernetes.client.utils.Utils;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.api.model.DeleteOptionsBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionVersionBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionSpecBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class RayServiceDeletionService {

  private static final Logger log = LoggerFactory.getLogger(RayServiceDeletionService.class);

  /**
   * 최소-프로세스 삭제 실행:
   *  1) RayService Foreground 삭제 + 대기
   *  2) 안 지워지면 자식 리소스(Pod/Service/PVC, RayCluster) 정리 후 재시도
   *  3) 그래도 안 되면(옵션) Finalizer 우회 패치
   *
   * @param access            클러스터 접근 정보 (server/token/ca)
   * @param namespace         네임스페이스
   * @param rayServiceName    RayService 이름
   * @param maxWait           1차 대기 타임아웃(권장 2~5분)
   * @param allowFinalizerBypass 최후수단 허용 여부(true 시 finalizers 제거)
   * @return 최종 삭제 성공 여부
   */
  public boolean deleteRayService(ClusterAccess access,
                                  String namespace,
                                  String rayServiceName,
                                  Duration maxWait,
                                  boolean allowFinalizerBypass) {
    try (KubernetesClient client = buildClient(access)) {
      // 0) CRD 버전 동적으로 탐지
      String rsVersion = getServedVersion(client, "rayservices.ray.io");
      String rcVersion = getServedVersion(client, "rayclusters.ray.io");

      CustomResourceDefinitionContext rsCtx = crdCtx("ray.io", rsVersion, "rayservices");
      CustomResourceDefinitionContext rcCtx = crdCtx("ray.io", rcVersion, "rayclusters");

      // 1) RayService Foreground 삭제 + 대기
      log.info("[RayDelete] Foreground delete RayService/{}/{} ...", namespace, rayServiceName);
      var deleted = foregroundDeleteAndWait(client, rsCtx, namespace, rayServiceName, maxWait);
      if (deleted) return true;

      log.warn("[RayDelete] RayService still present after wait. Starting soft cleanup...");

      // 2) RayService → 소유 RayCluster 찾기(OwnerReferences로 역추적)
      Set<String> clusterNames = findOwnedRayClusters(client, rcCtx, namespace, rayServiceName);
      log.info("[RayDelete] Owned RayClusters: {}", clusterNames);

      // 자식 리소스 정리 (Pod → Service → PVC → RayCluster)
      for (String cluster : clusterNames) {
        softCleanupChildren(client, namespace, cluster);
        foregroundDeleteRayCluster(client, rcCtx, namespace, cluster);
      }

      // RayService 재삭제 + 짧은 대기(예: 2분)
      log.info("[RayDelete] Retry delete RayService/{}/{}", namespace, rayServiceName);
      deleted = foregroundDeleteAndWait(client, rsCtx, namespace, rayServiceName, Duration.ofMinutes(2));
      if (deleted) return true;

      // 3) 최후수단: Finalizer 우회
      if (allowFinalizerBypass) {
        log.error("[RayDelete] Still stuck. Applying finalizer bypass (last resort) ...");
        // 남은 RayCluster에도 우회 적용
        for (String cluster : clusterNames) {
          try { dropFinalizers(client, rcCtx, namespace, cluster); } catch (Exception ignore) {}
        }
        dropFinalizers(client, rsCtx, namespace, rayServiceName);
        // 우회 후 즉시 확인
        return getGeneric(client, rsCtx, namespace, rayServiceName) == null;
      }

      return false;
    }
  }

  // ------------------------
  // 내부 구현 유틸
  // ------------------------

  private KubernetesClient buildClient(ClusterAccess access) {
    ConfigBuilder cb = new ConfigBuilder()
        .withMasterUrl(access.server)
        .withRequestTimeout(60_000)
        .withConnectionTimeout(30_000)
        .withOauthToken(access.token);

    if (access.caCertData != null && !access.caCertData.isBlank()) {
      cb.withCaCertData(access.caCertData);
    } else {
      cb.withTrustCerts(true);
    }

    return new KubernetesClientBuilder().withConfig(cb.build()).build();
  }

  private String getServedVersion(KubernetesClient client, String crdName) {
    CustomResourceDefinition crd =
        client.apiextensions().v1().customResourceDefinitions().withName(crdName).get();
    if (crd == null || crd.getSpec() == null || crd.getSpec().getVersions() == null)
      throw new IllegalStateException("CRD not found or invalid: " + crdName);

    // served=true 이면서 storage=true 인 버전 우선, 없으면 served=true 중 첫번째
    return crd.getSpec().getVersions().stream()
        .filter(v -> Boolean.TRUE.equals(v.getServed()))
        .sorted((a, b) -> Boolean.TRUE.equals(a.getStorage()) ? -1 : 1)
        .findFirst()
        .map(v -> v.getName())
        .orElseThrow(() -> new IllegalStateException("No served version for CRD: " + crdName));
  }

  private CustomResourceDefinitionContext crdCtx(String group, String version, String plural) {
    return new CustomResourceDefinitionContext.Builder()
        .withGroup(group)
        .withVersion(version)
        .withPlural(plural)
        .withScope("Namespaced")
        .build();
  }

  private boolean foregroundDeleteAndWait(KubernetesClient client,
                                          CustomResourceDefinitionContext ctx,
                                          String ns, String name,
                                          Duration wait) {
    var opts = new DeleteOptionsBuilder()
        .withPropagationPolicy(DeletionPropagation.FOREGROUND.toString())
        .build();

    try {
      client.genericKubernetesResources(ctx).inNamespace(ns).withName(name).delete(opts);
    } catch (KubernetesClientException e) {
      if (e.getCode() != 404) throw e; // 이미 없음 → 계속 진행
    }

    long deadline = System.currentTimeMillis() + wait.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (getGeneric(client, ctx, ns, name) == null) return true;
      Utils.sleep(2000);
    }
    return false;
  }

  private GenericKubernetesResource getGeneric(KubernetesClient client,
                                               CustomResourceDefinitionContext ctx,
                                               String ns, String name) {
    try {
      return client.genericKubernetesResources(ctx).inNamespace(ns).withName(name).get();
    } catch (KubernetesClientException e) {
      if (e.getCode() == 404) return null;
      throw e;
    }
  }

  private Set<String> findOwnedRayClusters(KubernetesClient client,
                                           CustomResourceDefinitionContext rcCtx,
                                           String ns,
                                           String ownerRayServiceName) {
    GenericKubernetesResourceList list =
        client.genericKubernetesResources(rcCtx).inNamespace(ns).list();

    if (list == null || list.getItems() == null) return Collections.emptySet();

    return list.getItems().stream()
        .filter(rc -> hasOwner(rc, "RayService", ownerRayServiceName))
        .map(rc -> rc.getMetadata().getName())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private boolean hasOwner(GenericKubernetesResource res, String kind, String name) {
    List<OwnerReference> owners = res.getMetadata() != null ? res.getMetadata().getOwnerReferences() : null;
    if (owners == null) return false;
    return owners.stream().anyMatch(o ->
        kind.equals(o.getKind()) && name.equals(o.getName()));
  }

  private void softCleanupChildren(KubernetesClient client, String ns, String clusterName) {
    // Pod (grace 0 강제)
    List<Pod> pods = client.pods().inNamespace(ns)
        .withLabel("ray.io/cluster", clusterName).list().getItems();
    pods.forEach(p -> {
      try {
        client.pods().inNamespace(ns).resource(p).withGracePeriod(0L).delete();
        log.info("[RayDelete] Deleted Pod {}", p.getMetadata().getName());
      } catch (Exception e) {
        log.warn("[RayDelete] Pod delete failed: {}", p.getMetadata().getName(), e);
      }
    });

    // Service
    List<Service> svcs = client.services().inNamespace(ns)
        .withLabel("ray.io/cluster", clusterName).list().getItems();
    svcs.forEach(s -> {
      try {
        client.services().inNamespace(ns).resource(s).delete();
        log.info("[RayDelete] Deleted Service {}", s.getMetadata().getName());
      } catch (Exception e) {
        log.warn("[RayDelete] Service delete failed: {}", s.getMetadata().getName(), e);
      }
    });

    // PVC
    List<PersistentVolumeClaim> pvcs = client.persistentVolumeClaims().inNamespace(ns)
        .withLabel("ray.io/cluster", clusterName).list().getItems();
    pvcs.forEach(pvc -> {
      try {
        client.persistentVolumeClaims().inNamespace(ns).resource(pvc).delete();
        log.info("[RayDelete] Deleted PVC {}", pvc.getMetadata().getName());
      } catch (Exception e) {
        log.warn("[RayDelete] PVC delete failed: {}", pvc.getMetadata().getName(), e);
      }
    });
  }

  private void foregroundDeleteRayCluster(KubernetesClient client,
                                          CustomResourceDefinitionContext rcCtx,
                                          String ns, String clusterName) {
    var opts = new DeleteOptionsBuilder()
        .withPropagationPolicy(DeletionPropagation.FOREGROUND.toString())
        .build();
    try {
      client.genericKubernetesResources(rcCtx).inNamespace(ns).withName(clusterName).delete(opts);
      log.info("[RayDelete] Foreground delete RayCluster {}", clusterName);
    } catch (KubernetesClientException e) {
      if (e.getCode() != 404) throw e;
    }
  }

  private void dropFinalizers(KubernetesClient client,
                              CustomResourceDefinitionContext ctx,
                              String ns, String name) {
    String patch = "{\"metadata\":{\"finalizers\":[]}}";
    client.genericKubernetesResources(ctx)
        .inNamespace(ns)
        .withName(name)
        .patch(PatchContext.of(PatchType.MERGE), patch);
    log.warn("[RayDelete] Dropped finalizers for {}/{}", ctx.getPlural(), name);
  }

  // ------------------------
  // DTO
  // ------------------------
  public static class ClusterAccess {
    public final String server;     // https://<api-server>
    public final String token;      // Bearer token
    public final String caCertData; // (옵션) Base64가 아니라 PEM 문자열 그대로

    public ClusterAccess(String server, String token, String caCertData) {
      this.server = server;
      this.token = token;
      this.caCertData = caCertData;
    }

    public static ClusterAccess insecure(String server, String token) {
      return new ClusterAccess(server, token, null);
    }
  }
             }
