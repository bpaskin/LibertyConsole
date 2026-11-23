package com.ibm.liberty.admin.service;

import com.ibm.liberty.admin.json.Json;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/** Optional ND management. No collective feature or IBM implementation package is required. */
public final class CollectiveService {
    static final String ROOT = "/sys.was.collectives/local";
    static final String PREFIX = "WebSphere:feature=collectiveController,type=";
    static final String REPOSITORY = PREFIX + "CollectiveRepository,name=CollectiveRepository";
    static final String CLUSTERS = PREFIX + "ClusterManager,name=ClusterManager";
    static final String COMMANDS = PREFIX + "ServerCommands,name=ServerCommands";
    static final String PATHS = PREFIX + "RepositoryPathUtility,name=RepositoryPathUtility";
    private final MBeanServer mbeans;

    public CollectiveService() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    CollectiveService(MBeanServer mbeans) {
        this.mbeans = mbeans;
    }

    public Map<String, Object> capabilities() {
        return Json.object("available", registered(REPOSITORY), "clusters", registered(CLUSTERS),
            "serverCommands", registered(COMMANDS));
    }

    /** Fresh identity and application metadata for one registered monitoring target. */
    public Map<String, Object> monitoringTarget(String target) {
        requireController();
        for (String host : children(ROOT + "/hosts")) {
            for (String userDir : children(host + "/userdirs")) {
                for (String path : children(userDir + "/servers")) {
                    if (!id(path).equals(target)) {
                        continue;
                    }
                    List<Map<String, Object>> applications = new ArrayList<>();
                    Map<String, Object> server = server(path, applications);
                    if (registered(CLUSTERS)) {
                        server.put("cluster", value(invoke(CLUSTERS, "getClusterName", server.get("host"),
                            server.get("userDir"), server.get("name")), ""));
                    }
                    return Json.object("server", server, "applications", applications);
                }
            }
        }
        throw new IllegalArgumentException("The monitoring target is no longer registered with this collective");
    }

    public Map<String, Object> inventory() {
        requireController();
        List<Map<String, Object>> servers = new ArrayList<>();
        List<Map<String, Object>> applications = new ArrayList<>();
        List<Map<String, Object>> hosts = new ArrayList<>();
        for (String hostPath : children(ROOT + "/hosts")) {
            String host = hostPath.substring(hostPath.lastIndexOf('/') + 1);
            int count = 0;
            for (String userDir : children(hostPath + "/userdirs")) {
                for (String serverPath : children(userDir + "/servers")) {
                    Map<String, Object> server = server(serverPath, applications);
                    servers.add(server);
                    count++;
                }
            }
            hosts.add(Json.object("name", host, "serverCount", count));
        }
        servers.sort(Comparator.comparing(s -> s.get("host") + "/" + s.get("name") + "/" + s.get("userDir")));
        applications.sort(Comparator.comparing(a -> a.get("name") + "/" + a.get("serverId")));
        List<Map<String, Object>> clusters = new ArrayList<>();
        if (registered(CLUSTERS)) {
            for (String name : strings(invoke(CLUSTERS, "listClusterNames"))) {
                List<String> members = strings(invoke(CLUSTERS, "listMembers", name));
                List<String> ids = new ArrayList<>();
                for (String tuple : members) {
                    String path = String.valueOf(invoke(PATHS, "buildServerRepositoryPath", tuple));
                    String id = id(trimSlash(path));
                    ids.add(id);
                    servers.stream().filter(s -> id.equals(s.get("id"))).forEach(s -> s.put("cluster", name));
                }
                clusters.add(Json.object("name", name, "state", value(invoke(CLUSTERS, "getStatus", name), "UNKNOWN"),
                    "members", ids, "memberCount", members.size()));
            }
        }
        clusters.sort(Comparator.comparing(c -> c.get("name").toString()));
        Object name = invoke(REPOSITORY, "getData", ROOT + "/collective.name");
        return Json.object("name", value(name, "Liberty collective"), "servers", servers,
            "clusters", clusters, "applications", applications, "hosts", hosts);
    }

    private Map<String, Object> server(String path, List<Map<String, Object>> apps) {
        String tuple = String.valueOf(invoke(PATHS, "getServerTuple", path));
        Object parts = invoke(PATHS, "splitServerTuple", tuple);
        if (!(parts instanceof String[]) || ((String[]) parts).length != 3) {
            throw new IllegalStateException("Invalid server identity in the collective repository");
        }
        String[] identity = (String[]) parts;
        Map<String, Object> data = map(invoke(REPOSITORY, "getDescendantData", path + "/sys.mbeans"));
        String state = value(invoke(REPOSITORY, "getData", path + "/sys.status"), "UNKNOWN");
        String serverId = id(path);
        Map<String, List<String>> contextRoots = contextRoots(path, data);
        boolean controller = false;
        for (String key : data.keySet()) {
            String bean = beanName(path, key);
            if (COMMANDS.equals(bean)) {
                controller = true;
            }
            if (bean == null || (!data.containsKey(key + "/attributes") && !data.containsKey(key + "/attributes/State"))) {
                continue;
            }
            try {
                ObjectName objectName = new ObjectName(bean);
                if (!"com.ibm.websphere.application.ApplicationMBean".equals(objectName.getKeyProperty("service"))) {
                    continue;
                }
                String name = objectName.getKeyProperty("name");
                if (name == null) {
                    continue;
                }
                if (name.startsWith("\"")) {
                    name = ObjectName.unquote(name);
                }
                apps.add(Json.object("name", name, "objectName", bean, "serverId", serverId,
                    "server", identity[2], "host", identity[0], "contextRoots", contextRoots.getOrDefault(name, List.of()),
                    "state", "STARTED".equals(state)
                        ? value(data.get(key + "/attributes/State"), "UNKNOWN") : "UNAVAILABLE"));
            } catch (javax.management.MalformedObjectNameException e) {
                // An unrelated publisher may use a non-ObjectName node under sys.mbeans.
            }
        }
        // Return an explicit allowlist. Repository authentication data must never reach the browser.
        return Json.object("id", serverId, "name", identity[2], "host", identity[0], "userDir", identity[1],
            "state", state, "controller", controller, "cluster", "");
    }

    private static Map<String, List<String>> contextRoots(String path, Map<String, Object> data) {
        Map<String, List<String>> roots = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!entry.getKey().endsWith("/attributes/RoutingInfo") || !(entry.getValue() instanceof String)) {
                continue;
            }
            String bean = beanName(path, entry.getKey());
            if (bean == null) {
                continue;
            }
            try {
                bean = bean.substring(0, bean.length() - "/attributes/RoutingInfo".length());
                ObjectName objectName = new ObjectName(bean);
                if (!"ApplicationRoutingInfoMBean".equals(objectName.getKeyProperty("type"))) {
                    continue;
                }
                Map<String, Object> routing = Json.parseObject(entry.getValue().toString());
                List<String> contexts = new ArrayList<>();
                Object modules = routing.get("webModules");
                if (modules instanceof Collection) {
                    for (Object module : (Collection<?>) modules) {
                        Object root = map(map(module).get("attributes")).get("ContextRoot");
                        if (root instanceof String) {
                            contexts.add(root.toString().replace("\\/", "/"));
                        }
                    }
                }
                roots.put(value(routing.get("name"), ""), contexts);
            } catch (javax.management.MalformedObjectNameException | Json.JsonException e) {
                // Optional routing metadata must not hide an otherwise manageable application.
            }
        }
        return roots;
    }

    private static String beanName(String path, String key) {
        String prefix = path + "/sys.mbeans/";
        if (!key.startsWith(prefix)) {
            return null;
        }
        return key.substring(prefix.length());
    }

    /** Only known inventory targets and a small operation allowlist can be invoked. */
    public Map<String, Object> operate(String kind, String target, String action) {
        requireController();
        Map<String, Object> inventory = inventory();
        if ("server".equals(kind)) {
            Map<String, Object> server = find(inventory.get("servers"), "id", target);
            String operation = switch (action) {
                case "start" -> "startServer";
                case "stop" -> "stopServer";
                case "dump" -> "javadumpServer";
                default -> throw new IllegalArgumentException("Unsupported server action");
            };
            Object result = invoke(COMMANDS, operation, server.get("host"), server.get("userDir"), server.get("name"), "");
            return operationResult(Map.of(server.get("name").toString(), result));
        }
        if ("cluster".equals(kind)) {
            find(inventory.get("clusters"), "name", target);
            if ("plugin".equals(action)) {
                return Json.object("success", true, "path", invoke(CLUSTERS, "generateClusterPluginConfig", target));
            }
            String operation = switch (action) {
                case "start" -> "startCluster";
                case "stop" -> "stopCluster";
                default -> throw new IllegalArgumentException("Unsupported cluster action");
            };
            return operationResult(map(invoke(CLUSTERS, operation, target, "")));
        }
        throw new IllegalArgumentException("Unsupported collective target");
    }

    static Map<String, Object> operationResult(Map<String, ?> results) {
        List<Map<String, Object>> details = new ArrayList<>();
        boolean success = !results.isEmpty();
        for (Map.Entry<String, ?> entry : results.entrySet()) {
            Map<String, Object> result = map(entry.getValue());
            Object code = result.get("returnCode");
            boolean ok = code instanceof Number && ((Number) code).intValue() == 0
                && !result.containsKey("Exception") && !result.containsKey("ExceptionMessage");
            success &= ok;
            details.add(Json.object("target", entry.getKey(), "success", ok, "returnCode", code,
                "stdout", value(result.get("stdout"), ""), "stderr", value(result.get("stderr"), ""),
                "error", value(result.get("ExceptionMessage"), ok ? "" : "Liberty did not report success")));
        }
        return Json.object("success", success, "results", details);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> find(Object rows, String property, String target) {
        return ((List<Map<String, Object>>) rows).stream().filter(row -> target.equals(row.get(property)))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("The collective target no longer exists"));
    }

    private List<String> children(String path) {
        try {
            return strings(mbeans.invoke(new ObjectName(REPOSITORY), "getChildren", new Object[] {path, true},
                new String[] {"java.lang.String", "boolean"}));
        } catch (Exception e) {
            throw failure("read collective members", e);
        }
    }

    private Object invoke(String bean, String operation, Object... args) {
        String[] signature = new String[args.length];
        java.util.Arrays.fill(signature, "java.lang.String");
        try {
            return mbeans.invoke(new ObjectName(bean), operation, args, signature);
        } catch (Exception e) {
            throw failure(operation, e);
        }
    }

    private boolean registered(String bean) {
        try {
            return mbeans.isRegistered(new ObjectName(bean));
        } catch (javax.management.MalformedObjectNameException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireController() {
        if (!registered(REPOSITORY)) {
            throw new IllegalStateException("Collective management requires the console on a collective controller");
        }
    }

    private static IllegalStateException failure(String operation, Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return new IllegalStateException("Unable to " + operation + ": " + cause.getMessage(), e);
    }

    private static String value(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static List<String> strings(Object value) {
        if (value instanceof Collection) {
            return ((Collection<?>) value).stream().map(Object::toString).sorted().toList();
        }
        if (value instanceof String[]) {
            return java.util.Arrays.asList((String[]) value);
        }
        return List.of();
    }

    private static String id(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    private static String trimSlash(String path) {
        return path.replaceAll("/+$", "");
    }
}
