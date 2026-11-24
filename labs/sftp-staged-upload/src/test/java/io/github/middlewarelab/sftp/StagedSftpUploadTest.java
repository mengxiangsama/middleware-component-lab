package io.github.middlewarelab.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.RequiredServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StagedSftpUploadTest {
    private static final String HOST = "127.0.0.1";
    private static final String USER = "partner";
    private static final String STAGING = "/outbound/.orders-20261124.csv.uploading";
    private static final String REPORT = "/outbound/orders-20261124.csv";
    private static final byte[] CSV = "order_id,amount_cents\nA101,1200\nA102,2500\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final Duration SSH_TIMEOUT = Duration.ofSeconds(5);

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void publishesCompleteCsvOnlyAfterCloseAndChecksHostKey(@TempDir Path remoteRoot) throws Exception {
        Files.createDirectory(remoteRoot.resolve("outbound"));
        KeyPair serverKey = generateHostKey();
        KeyPair wrongKey = generateHostKey();
        String password = UUID.randomUUID().toString();
        AtomicInteger authenticationAttempts = new AtomicInteger();

        try (SshServer server = SshServer.setUpDefaultServer()) {
            server.setHost(HOST);
            server.setPort(0);
            server.setKeyPairProvider(KeyPairProvider.wrap(serverKey));
            server.setPasswordAuthenticator((user, suppliedPassword, session) -> {
                authenticationAttempts.incrementAndGet();
                return USER.equals(user) && password.equals(suppliedPassword);
            });
            server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
            server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
            server.start();

            try (SshClient client = startClient(wrongKey.getPublic())) {
                SshException rejected = assertThrows(SshException.class,
                        () -> publishAndVerify(client, server.getPort(), password));
                assertEquals(SshConstants.SSH2_DISCONNECT_HOST_KEY_NOT_VERIFIABLE, rejected.getDisconnectCode());
                assertEquals(0, authenticationAttempts.get());
                System.out.println("mismatched host key rejected before password authentication (SSH disconnect 9)");
            }

            try (SshClient client = startClient(serverKey.getPublic())) {
                publishAndVerify(client, server.getPort(), password);
                assertEquals(2, authenticationAttempts.get());
            }
        }
    }

    private static void publishAndVerify(SshClient client, int port, String password) throws IOException {
        SftpClientFactory factory = SftpClientFactory.instance();
        try (ClientSession senderSession = connect(client, port, password);
             ClientSession readerSession = connect(client, port, password);
             SftpClient sender = factory.createSftpClient(senderSession);
             SftpClient reader = factory.createSftpClient(readerSession)) {
            assertEquals(6, sender.getVersion());
            assertEquals(6, reader.getVersion());

            assertMissing(reader, REPORT);
            try (OutputStream file = sender.write(STAGING, SftpClient.OpenMode.Write,
                    SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive)) {
                file.write(CSV);
                file.flush();
                assertMissing(reader, REPORT);
            }
            assertMissing(reader, REPORT);
            assertEquals(CSV.length, reader.stat(STAGING).getSize());
            System.out.printf("SFTP v%d staging visible (%d bytes), final name absent before rename%n",
                    sender.getVersion(), CSV.length);

            sender.rename(STAGING, REPORT, SftpClient.CopyMode.Atomic);

            try (InputStream file = reader.read(REPORT)) {
                assertArrayEquals(CSV, file.readAllBytes());
            }
            assertMissing(reader, STAGING);
            System.out.printf("Atomic rename acknowledged, final CSV matches %d bytes, staging absent%n", CSV.length);
        }
    }

    private static ClientSession connect(SshClient client, int port, String password) throws IOException {
        ClientSession session = client.connect(USER, HOST, port).verify(SSH_TIMEOUT).getSession();
        try {
            session.addPasswordIdentity(password);
            session.auth().verify(SSH_TIMEOUT);
            return session;
        } catch (IOException | RuntimeException failure) {
            try {
                session.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static SshClient startClient(PublicKey expectedHostKey) {
        SshClient client = SshClient.setUpDefaultClient();
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
        client.setServerKeyVerifier(new RequiredServerKeyVerifier(expectedHostKey));
        SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(client, SSH_TIMEOUT);
        CoreModuleProperties.IDLE_TIMEOUT.set(client, SSH_TIMEOUT);
        client.start();
        return client;
    }

    private static void assertMissing(SftpClient client, String path) {
        SftpException missing = assertThrows(SftpException.class, () -> client.stat(path));
        assertEquals(SftpConstants.SSH_FX_NO_SUCH_FILE, missing.getStatus());
    }

    private static KeyPair generateHostKey() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
