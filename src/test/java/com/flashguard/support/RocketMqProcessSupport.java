package com.flashguard.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Real Apache NameServer/Broker processes, using official Maven artifacts and isolated stores. */
public final class RocketMqProcessSupport implements AutoCloseable {
    private final Path directory;
    private final int nameserverPort;
    private final int brokerPort;
    private final int transactionTimeoutMs;
    private final int transactionCheckIntervalMs;
    private final List<Process> processes = new ArrayList<>();
    private org.testcontainers.containers.GenericContainer<?> brokerContainer;

    public RocketMqProcessSupport() throws IOException {
        this(2000, 1000);
    }

    public RocketMqProcessSupport(int transactionTimeoutMs, int transactionCheckIntervalMs) throws IOException {
        if (transactionTimeoutMs <= 0 || transactionCheckIntervalMs <= 0) throw new IllegalArgumentException("MQ timing must be positive");
        this.transactionTimeoutMs = transactionTimeoutMs;
        this.transactionCheckIntervalMs = transactionCheckIntervalMs;
        directory = Files.createDirectories(Path.of("target", "rocketmq-it", java.util.UUID.randomUUID().toString()))
            .toAbsolutePath();
        nameserverPort = freePort();
        brokerPort = freeBrokerPort();
    }

    public String nameserverAddress() { return "127.0.0.1:" + nameserverPort; }
    public String brokerAddress() { return "127.0.0.1:" + brokerPort; }
    public Path directory() { return directory; }
    public String brokerContainerId() { return brokerContainer == null ? null : brokerContainer.getContainerId(); }
    public java.util.Map<String, Object> runtimeMetadata() throws Exception {
        if (brokerContainer == null) return java.util.Map.of("runtime", "native", "java", System.getProperty("java.runtime.version"));
        var info = brokerContainer.getContainerInfo();
        return java.util.Map.of("runtime", "docker", "image", brokerContainer.getDockerImageName(),
            "imageId", info.getImageId(), "memoryBytes", 1536L * 1024 * 1024, "cpuLimit", 2,
            "java", brokerContainer.execInContainer("sh", "-c", "java -version 2>&1").getStdout(),
            "storeFilesystem", brokerContainer.execInContainer("df", "-h", "/data").getStdout());
    }

    public void start() throws Exception {
        try {
            if (System.getProperty("flashguard.test.mq.runtime", "docker").equals("docker")) {
                startContainer();
                return;
            }
            Files.createDirectories(directory.resolve("config"));
            Path nameserverConfig = directory.resolve("nameserver.conf");
            Files.writeString(nameserverConfig, "listenPort=" + nameserverPort + "\nkvConfigPath="
                + slash(directory.resolve("kvConfig.json")) + "\n");
            Process nameserver = launch("nameserver", "org.apache.rocketmq.namesrv.NamesrvStartup",
                List.of("-c", nameserverConfig.toString()));
            awaitPort(nameserver, nameserverPort, Duration.ofSeconds(45));
            Path brokerConfig = directory.resolve("broker.conf");
            Files.writeString(brokerConfig, """
                brokerClusterName=FlashGuardTestCluster
                brokerName=flashguard-test-broker
                brokerId=0
                brokerRole=ASYNC_MASTER
                brokerIP1=127.0.0.1
                autoCreateTopicEnable=false
                autoCreateSubscriptionGroup=true
                diskMaxUsedSpaceRatio=99
                flushDiskType=SYNC_FLUSH
                transactionCheckMax=60
                mappedFileSizeCommitLog=16777216
                mappedFileSizeConsumeQueue=600000
                enableControllerMode=false
                """ + "transactionTimeOut=" + transactionTimeoutMs + "\ntransactionCheckInterval=" + transactionCheckIntervalMs
                + "\nlistenPort=" + brokerPort + "\nstorePathRootDir=" + slash(directory.resolve("store"))
                + "\nstorePathCommitLog=" + slash(directory.resolve("store/commitlog")) + "\n");
            Process broker = launch("broker", "org.apache.rocketmq.broker.BrokerStartup",
                List.of("-n", nameserverAddress(), "-c", brokerConfig.toString()));
            awaitPort(broker, brokerPort, Duration.ofSeconds(60));
            System.out.println("FLASHGUARD_REAL_ROCKETMQ=5.3.2 nameserver=" + nameserverAddress()
                + " broker=" + brokerAddress() + " logs=" + directory);
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    private void startContainer() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(classpath.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        Path libraries = Files.createDirectories(Path.of("target", "rocketmq-runtime-libs", fingerprint)).toAbsolutePath();
        for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path source = Path.of(entry);
            if (!Files.isRegularFile(source) || !entry.endsWith(".jar")) continue;
            Path destination = libraries.resolve(source.getFileName());
            if (!Files.exists(destination) || Files.size(destination) != Files.size(source)) {
                Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(directory.resolve("nameserver.conf"), "listenPort=" + nameserverPort
            + "\nkvConfigPath=/data/kvConfig.json\n");
        Files.writeString(directory.resolve("broker.conf"), """
            brokerClusterName=FlashGuardTestCluster
            brokerName=flashguard-test-broker
            brokerId=0
            brokerRole=ASYNC_MASTER
            brokerIP1=127.0.0.1
            autoCreateTopicEnable=false
            autoCreateSubscriptionGroup=true
            flushDiskType=SYNC_FLUSH
            transactionCheckMax=60
            mappedFileSizeCommitLog=16777216
            mappedFileSizeConsumeQueue=600000
            enableControllerMode=false
            storePathRootDir=/data/store
            storePathCommitLog=/data/store/commitlog
            """ + "transactionTimeOut=" + transactionTimeoutMs + "\ntransactionCheckInterval=" + transactionCheckIntervalMs
            + "\nlistenPort=" + brokerPort + "\n");
        String java = "java -Xms128m -Xmx384m --add-opens=java.base/java.nio=ALL-UNNAMED "
            + "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED "
            + "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED -Drocketmq.home.dir=/data "
            + "-Duser.home=/data -cp '/runtime/*' ";
        Files.writeString(directory.resolve("start.sh"), "#!/bin/sh\nset -eu\nmkdir -p /data/config\n"
            + java + "org.apache.rocketmq.namesrv.NamesrvStartup -c /fixture/nameserver.conf > /data/nameserver.log 2>&1 &\n"
            + "sleep 3\nexec " + java + "org.apache.rocketmq.broker.BrokerStartup -n 127.0.0.1:"
            + nameserverPort + " -c /fixture/broker.conf\n");
        brokerContainer = new org.testcontainers.containers.GenericContainer<>(
            org.testcontainers.utility.DockerImageName.parse(System.getProperty("flashguard.test.mq.image",
                "flashguard-mq-test-runtime:local")))
            .withFileSystemBind(libraries.toString(), "/runtime", org.testcontainers.containers.BindMode.READ_ONLY)
            .withFileSystemBind(directory.toString(), "/fixture", org.testcontainers.containers.BindMode.READ_ONLY)
            .withExposedPorts(nameserverPort, brokerPort)
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                .withMemory(1536L * 1024 * 1024).withNanoCPUs(2_000_000_000L)
                .withPortBindings(com.github.dockerjava.api.model.PortBinding.parse(nameserverPort + ":" + nameserverPort),
                    com.github.dockerjava.api.model.PortBinding.parse(brokerPort + ":" + brokerPort)))
            .withCommand("sh", "/fixture/start.sh")
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(90));
        brokerContainer.start();
        System.out.println("FLASHGUARD_REAL_ROCKETMQ=5.3.2 runtime=docker image=" + brokerContainer.getDockerImageName()
            + " nameserver=" + nameserverAddress() + " broker=" + brokerAddress() + " logs=" + directory);
    }

    public Process launch(String name, String mainClass, List<String> arguments) throws IOException {
        List<String> javaArguments = new ArrayList<>(List.of("-Xms128m", "-Xmx384m",
            "--add-opens=java.base/java.nio=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
            "-Drocketmq.home.dir=" + slash(directory), "-Duser.home=" + slash(directory),
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), mainClass));
        javaArguments.addAll(arguments);
        Path argumentFile = directory.resolve(name + ".args");
        Files.writeString(argumentFile, javaArguments.stream().map(RocketMqProcessSupport::quote)
            .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
        String executable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "@" + argumentFile)
            .redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile()).start();
        processes.add(process);
        return process;
    }

    private static void awaitPort(Process process, int port, Duration timeout) throws Exception {
        org.awaitility.Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(200)).until(() -> {
            if (!process.isAlive()) throw new IllegalStateException("RocketMQ exited " + process.exitValue() + "; inspect target/rocketmq-it logs");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (IOException notReady) { return false; }
        });
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static int freeBrokerPort() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int candidate = java.util.concurrent.ThreadLocalRandom.current().nextInt(20000, 40000);
            try (ServerSocket main = new ServerSocket(candidate);
                 ServerSocket fast = new ServerSocket(candidate - 2);
                 ServerSocket ha = new ServerSocket(candidate + 1)) { return candidate; }
            catch (IOException collision) { /* choose another triple */ }
        }
        throw new IOException("No free broker port triple");
    }

    private static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }
    private static String slash(Path path) { return path.toString().replace('\\', '/'); }
    private static String quote(String value) { return "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\""; }

    @Override public void close() {
        for (int i = processes.size() - 1; i >= 0; i--) {
            Process process = processes.get(i);
            process.destroy();
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        if (brokerContainer != null) {
            try {
                Files.writeString(directory.resolve("broker.log"), brokerContainer.getLogs());
                brokerContainer.copyFileFromContainer("/data/nameserver.log", directory.resolve("nameserver.log").toString());
                Files.writeString(directory.resolve("runtime.txt"), brokerContainer.execInContainer("sh", "-c",
                    "java -version 2>&1; df -h /data").getStdout());
            } catch (Exception diagnosticFailure) {
                System.err.println("Unable to copy all broker diagnostics: " + diagnosticFailure.getMessage());
            } finally { brokerContainer.stop(); }
        }
    }
}
