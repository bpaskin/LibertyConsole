package com.ibm.liberty.admin.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ibm.liberty.admin.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollectiveServiceTest {
    private MBeanServer mbeans;
    private CollectiveService service;
    private String serverPath;
    private final Map<String, Object> commandResult = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        mbeans = MBeanServerFactory.newMBeanServer();
        service = new CollectiveService(mbeans);
        serverPath = CollectiveService.ROOT + "/hosts/example/userdirs/C%3A%2Fwlp%2Fusr/servers/member";
        commandResult.put("returnCode", 0);
    }

    private interface Operation { Object run(String operation, Object[] args); }

    private void register(String name, Operation operation) throws Exception {
        mbeans.registerMBean(new DynamicMBean() {
            public Object invoke(String op, Object[] args, String[] signature) { return operation.run(op, args); }
            public MBeanInfo getMBeanInfo() { return new MBeanInfo("CollectiveTest", "Test", null, null, null, null); }
            public Object getAttribute(String name) { return null; }
            public void setAttribute(Attribute attribute) { }
            public AttributeList getAttributes(String[] names) { return new AttributeList(); }
            public AttributeList setAttributes(AttributeList values) { return new AttributeList(); }
        }, new ObjectName(name));
    }

    private void collective(String state) throws Exception {
        String hosts = CollectiveService.ROOT + "/hosts";
        String userDir = hosts + "/example/userdirs/C%3A%2Fwlp%2Fusr";
        String bean = "WebSphere:service=com.ibm.websphere.application.ApplicationMBean,name=\"hello,world/attributes\"";
        register(CollectiveService.REPOSITORY, (op, args) -> {
            String path = args[0].toString();
            if (op.equals("getChildren")) {
                if (path.equals(hosts)) return List.of(hosts + "/example");
                if (path.equals(hosts + "/example/userdirs")) return List.of(userDir);
                if (path.equals(userDir + "/servers")) return List.of(serverPath);
                return List.of();
            }
            if (op.equals("getData")) return path.endsWith("sys.status") ? state : "Test collective";
            if (op.equals("getDescendantData")) return Map.of(
                serverPath + "/sys.mbeans/" + bean, "",
                serverPath + "/sys.mbeans/" + bean + "/attributes/State", "STARTED",
                serverPath + "/sys.mbeans/WebSphere:type=ApplicationRoutingInfoMBean,name=\"hello,world/attributes\"/attributes/RoutingInfo",
                    "{\"name\":\"hello,world/attributes\",\"webModules\":[{\"attributes\":{\"ContextRoot\":\"/hello\"}}]}",
                serverPath + "/sys.mbeans/unrelated/attributes/Password", "must-not-be-returned");
            throw new AssertionError(op);
        });
        register(CollectiveService.PATHS, (op, args) -> switch (op) {
            case "getServerTuple" -> "example,C:/wlp/usr,member";
            case "splitServerTuple" -> new String[] {"example", "C:/wlp/usr", "member"};
            case "buildServerRepositoryPath" -> serverPath + "/";
            default -> throw new AssertionError(op);
        });
        register(CollectiveService.CLUSTERS, (op, args) -> switch (op) {
            case "listClusterNames" -> List.of("cluster");
            case "listMembers" -> List.of("example,C:/wlp/usr,member");
            case "getStatus" -> "PARTIALLY STARTED";
            case "getClusterName" -> "cluster";
            case "startCluster", "stopCluster" -> Map.of("member", commandResult);
            case "generateClusterPluginConfig" -> "/output/plugin-cfg.xml";
            default -> throw new AssertionError(op);
        });
        register(CollectiveService.COMMANDS, (op, args) -> commandResult);
    }

    @Test
    void standaloneHasNoCollectiveAndRequiresNoOptionalFeatures() {
        assertEquals(false, service.capabilities().get("available"));
        assertThrows(IllegalStateException.class, service::inventory);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsIdentitiesClustersAndQuotedApplicationNamesWithoutExposingRepositoryData() throws Exception {
        collective("STARTED");
        Map<String, Object> inventory = service.inventory();
        assertEquals("Test collective", inventory.get("name"));
        Map<String, Object> server = ((List<Map<String, Object>>) inventory.get("servers")).get(0);
        assertEquals("C:/wlp/usr", server.get("userDir"));
        assertEquals("cluster", server.get("cluster"));
        assertEquals("hello,world/attributes", ((List<Map<String, Object>>) inventory.get("applications")).get(0).get("name"));
        assertEquals(List.of("/hello"), ((List<Map<String, Object>>) inventory.get("applications")).get(0).get("contextRoots"));
        assertEquals(List.of(server.get("id")), ((List<Map<String, Object>>) inventory.get("clusters")).get(0).get("members"));
        assertFalse(Json.write(inventory).contains("must-not-be-returned"));
    }

    @Test
    void stoppedServerDoesNotPresentCachedApplicationsAsRunning() throws Exception {
        collective("STOPPED");
        assertTrue(Json.write(service.inventory()).contains("UNAVAILABLE"));
    }

    @Test
    void reportsFailedAndPartialClusterCommands() throws Exception {
        collective("STARTED");
        commandResult.put("returnCode", 1);
        commandResult.put("stderr", "SSH connection refused");
        Map<String, Object> result = service.operate("cluster", "cluster", "start");
        assertEquals(false, result.get("success"));
        assertTrue(Json.write(result).contains("SSH connection refused"));
        assertEquals(false, CollectiveService.operationResult(Map.of("one", Map.of("returnCode", 0),
            "two", Map.of("ExceptionMessage", "Host unavailable"))).get("success"));
    }

    @Test
    void rejectsUnknownTargetsAndUnapprovedOperations() throws Exception {
        collective("STARTED");
        assertThrows(IllegalArgumentException.class, () -> service.operate("cluster", "missing", "start"));
        assertThrows(IllegalArgumentException.class, () -> service.operate("cluster", "cluster", "delete"));
        assertThrows(IllegalArgumentException.class, () -> service.operate("server", "untrusted", "stop"));
    }

    @Test
    void successfulCommandsAndPluginGenerationReturnTheirResults() throws Exception {
        collective("STARTED");
        assertEquals(true, service.operate("cluster", "cluster", "start").get("success"));
        assertEquals("/output/plugin-cfg.xml", service.operate("cluster", "cluster", "plugin").get("path"));
    }

    @Test
    void repositoryFailuresRemainErrors() throws Exception {
        register(CollectiveService.REPOSITORY, (op, args) -> { throw new IllegalStateException("Repository unavailable"); });
        assertTrue(assertThrows(IllegalStateException.class, service::inventory).getMessage().contains("Repository unavailable"));
    }

    @Test
    void emptyCollectiveIsAvailableWithEmptyInventory() throws Exception {
        register(CollectiveService.REPOSITORY, (op, args) -> op.equals("getChildren") ? List.of() : "Empty collective");
        assertEquals(true, service.capabilities().get("available"));
        Map<String, Object> inventory = service.inventory();
        assertEquals(List.of(), inventory.get("servers"));
        assertEquals(List.of(), inventory.get("applications"));
        assertEquals(List.of(), inventory.get("clusters"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void monitoringMetadataIsLimitedToOneRegisteredServer() throws Exception {
        collective("STARTED");
        Map<String, Object> server = ((List<Map<String, Object>>) service.inventory().get("servers")).get(0);
        Map<String, Object> target = service.monitoringTarget(server.get("id").toString());
        assertEquals(server.get("id"), ((Map<String, Object>) target.get("server")).get("id"));
        assertEquals("cluster", ((Map<String, Object>) target.get("server")).get("cluster"));
        assertEquals(1, ((List<?>) target.get("applications")).size());
        assertFalse(Json.write(target).contains("must-not-be-returned"));
        assertThrows(IllegalArgumentException.class, () -> service.monitoringTarget("arbitrary-path"));
    }
}
