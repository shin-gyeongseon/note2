import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.utils.Serialization;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class RayJobOps {

  // === CRD Context (ray.io/v1) ===
  public static final CustomResourceDefinitionContext RAYJOB_V1 =
      new CustomResourceDefinitionContext.Builder()
          .withGroup("ray.io").withVersion("v1")
          .withPlural("rayjobs").withScope("Namespaced").build();

  private final KubernetesClient client;
  private final String namespace;

  public RayJobOps(KubernetesClient client, String namespace) {
    this.client = client;
    this.namespace = namespace;
  }

  // 1) suspend = true
  public void suspend(String jobName) {
    patchMerge(jobName, "{ \"spec\": { \"suspend\": true } }");
    waitObserved(jobName, Duration.ofMinutes(2));
  }

  // 2) spec 부분 업데이트(예: entrypoint 교체, rayClusterSpec 수정 등)
  public void patchMerge(String jobName, String jsonMergePatch) {
    client.genericKubernetesResources(RAYJOB_V1)
        .inNamespace(namespace).withName(jobName)
        .patch(PatchType.MERGE, jsonMergePatch);
  }

  // 필요 시 JSON Patch도 제공 (배열/경로 제어)
  public void patchJson(String jobName, String jsonPatchOps) {
    client.genericKubernetesResources(RAYJOB_V1)
        .inNamespace(namespace).withName(jobName)
        .patch(PatchType.JSON, jsonPatchOps);
  }

  // 3) suspend = false (재제출/재개)
  public void resume(String jobName) {
    patchMerge(jobName, "{ \"spec\": { \"suspend\": false } }");
    waitObserved(jobName, Duration.ofMinutes(2));
  }

  // 4) 완료/실패 대기 (jobStatus: SUCCEEDED/FAILED + jobDeploymentStatus: Complete)
  public boolean awaitTerminal(String jobName, Duration timeout) {
    long end = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < end) {
      GenericKubernetesResource g = get(jobName);
      if (g == null) return false;

      Map<String, Object> st = getMap(g, "status");
      if (st != null) {
        String jobStatus = str(st.get("jobStatus"));              // SUCCEEDED / FAILED / RUNNING ...
        String deploy = str(st.get("jobDeploymentStatus"));       // Complete / Failed / ...
        if ("SUCCEEDED".equals(jobStatus) && "Complete".equals(deploy)) return true;
        if ("FAILED".equals(jobStatus)) return false;
      }
      sleep(2000);
    }
    return false;
  }

  // 편의 함수: 한 번에 “중지→수정→재개→완료대기”
  public boolean updateSpecAndRerun(String jobName, String jsonMergePatchForSpec, Duration wait) {
    suspend(jobName);
    patchMerge(jobName, jsonMergePatchForSpec);
    resume(jobName);
    return awaitTerminal(jobName, wait);
  }

  // ===== 내부 유틸 =====
  private GenericKubernetesResource get(String name) {
    return client.genericKubernetesResources(RAYJOB_V1).inNamespace(namespace).withName(name).get();
  }

  // observedGeneration == metadata.generation 일치 대기(컨트롤러가 변경을 관측했는지 확인)
  private void waitObserved(String name, Duration timeout) {
    long end = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < end) {
      GenericKubernetesResource g = get(name);
      if (g == null) return;
      Long gen = g.getMetadata() != null ? g.getMetadata().getGeneration() : null;
      Map<String, Object> st = getMap(g, "status");
      Long obs = st != null ? asLong(st.get("observedGeneration")) : null;
      if (gen != null && obs != null && gen.longValue() == obs.longValue()) return;
      sleep(1200);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getMap(GenericKubernetesResource g, String key) {
    Object v = g != null ? g.getAdditionalProperties().get(key) : null;
    return (v instanceof Map) ? (Map<String, Object>) v : null;
  }

  private static String str(Object o) { return o == null ? null : String.valueOf(o); }
  private static Long asLong(Object o) {
    if (o instanceof Number) return ((Number) o).longValue();
    try { return o == null ? null : Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
  }
  private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

  // === 간단 실행 예시 ===
  public static void main(String[] args) {
    try (KubernetesClient client = new KubernetesClientBuilder().build()) {
      String ns = "default";
      RayJobOps ops = new RayJobOps(client, ns);

      String jobName = "train-job";

      // 예: entrypoint 교체 + 에포크 10으로 수정
      String mergePatch = """
        {
          "spec": {
            "entrypoint": "python -m train --epochs=10"
          }
        }
      """;

      boolean ok = ops.updateSpecAndRerun(jobName, mergePatch, Duration.ofMinutes(20));
      System.out.println("Terminal state reached (succeeded?): " + ok);
    }
  }
}
