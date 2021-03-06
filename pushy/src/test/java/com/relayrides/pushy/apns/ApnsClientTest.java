package com.relayrides.pushy.apns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.relayrides.pushy.apns.util.ApnsPayloadBuilder;
import com.relayrides.pushy.apns.util.SimpleApnsPushNotification;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class ApnsClientTest {

    private static NioEventLoopGroup EVENT_LOOP_GROUP;

    private static final String SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME = "/single-topic-client.p12";
    private static final String MULTI_TOPIC_CLIENT_KEYSTORE_FILENAME = "/multi-topic-client.p12";

    private static final String CA_CERTIFICATE_FILENAME = "/ca.pem";
    private static final String SERVER_KEYSTORE = "/server.p12";
    private static final String SERVER_KEYSTORE_PASSWORD = "pushy-test";

    private static final String TOKEN_AUTH_PRIVATE_KEY_FILENAME = "/token-auth-private-key.pem";

    private static File CA_CERTIFICATE;

    private static final String KEYSTORE_PASSWORD = "pushy-test";

    private static final String HOST = "localhost";
    private static final int PORT = 8443;

    private static final String DEFAULT_TEAM_ID = "team-id";
    private static final String DEFAULT_KEY_UD = "key-id";
    private static final String DEFAULT_TOPIC = "com.relayrides.pushy";

    private static final int TOKEN_LENGTH = 32; // bytes

    private MockApnsServer server;
    private ApnsClient tlsAuthenticationClient;
    private ApnsClient tokenAuthenticationClient;

    private static class TestMetricsListener implements ApnsClientMetricsListener {

        private final List<Long> writeFailures = new ArrayList<>();
        private final List<Long> sentNotifications = new ArrayList<>();
        private final List<Long> acceptedNotifications = new ArrayList<>();
        private final List<Long> rejectedNotifications = new ArrayList<>();

        private final AtomicInteger connectionAttemptsStarted = new AtomicInteger(0);
        private final AtomicInteger successfulConnectionAttempts = new AtomicInteger(0);
        private final AtomicInteger failedConnectionAttempts = new AtomicInteger(0);

        @Override
        public void handleWriteFailure(final ApnsClient apnsClient, final long notificationId) {
            synchronized (this.writeFailures) {
                this.writeFailures.add(notificationId);
                this.writeFailures.notifyAll();
            }
        }

        @Override
        public void handleNotificationSent(final ApnsClient apnsClient, final long notificationId) {
            this.sentNotifications.add(notificationId);
        }

        @Override
        public void handleNotificationAccepted(final ApnsClient apnsClient, final long notificationId) {
            synchronized (this.acceptedNotifications) {
                this.acceptedNotifications.add(notificationId);
                this.acceptedNotifications.notifyAll();
            }
        }

        @Override
        public void handleNotificationRejected(final ApnsClient apnsClient, final long notificationId) {
            synchronized (this.rejectedNotifications) {
                this.rejectedNotifications.add(notificationId);
                this.rejectedNotifications.notifyAll();
            }
        }

        @Override
        public void handleConnectionAttemptStarted(final ApnsClient apnsClient) {
            this.connectionAttemptsStarted.getAndIncrement();
        }

        @Override
        public void handleConnectionAttemptSucceeded(final ApnsClient apnsClient) {
            synchronized (this.successfulConnectionAttempts) {
                this.successfulConnectionAttempts.getAndIncrement();
                this.successfulConnectionAttempts.notifyAll();
            }
        }

        @Override
        public void handleConnectionAttemptFailed(final ApnsClient apnsClient) {
            synchronized (this.failedConnectionAttempts) {
                this.failedConnectionAttempts.getAndIncrement();
                this.failedConnectionAttempts.notifyAll();
            }
        }

        public void waitForNonZeroWriteFailures() throws InterruptedException {
            synchronized (this.writeFailures) {
                while (this.writeFailures.isEmpty()) {
                    this.writeFailures.wait();
                }
            }
        }

        public void waitForNonZeroAcceptedNotifications() throws InterruptedException {
            synchronized (this.acceptedNotifications) {
                while (this.acceptedNotifications.isEmpty()) {
                    this.acceptedNotifications.wait();
                }
            }
        }

        public void waitForNonZeroRejectedNotifications() throws InterruptedException {
            synchronized (this.rejectedNotifications) {
                while (this.rejectedNotifications.isEmpty()) {
                    this.rejectedNotifications.wait();
                }
            }
        }

        public void waitForNonZeroSuccessfulConnections() throws InterruptedException {
            synchronized (this.successfulConnectionAttempts) {
                while (this.successfulConnectionAttempts.get() == 0) {
                    this.successfulConnectionAttempts.wait();
                }
            }
        }

        public void waitForNonZeroFailedConnections() throws InterruptedException {
            synchronized (this.failedConnectionAttempts) {
                while (this.failedConnectionAttempts.get() == 0) {
                    this.failedConnectionAttempts.wait();
                }
            }
        }

        public List<Long> getWriteFailures() {
            return this.writeFailures;
        }

        public List<Long> getSentNotifications() {
            return this.sentNotifications;
        }

        public List<Long> getAcceptedNotifications() {
            return this.acceptedNotifications;
        }

        public List<Long> getRejectedNotifications() {
            return this.rejectedNotifications;
        }

        public AtomicInteger getConnectionAttemptsStarted() {
            return this.connectionAttemptsStarted;
        }

        public AtomicInteger getSuccessfulConnectionAttempts() {
            return this.successfulConnectionAttempts;
        }

        public AtomicInteger getFailedConnectionAttempts() {
            return this.failedConnectionAttempts;
        }
    }

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        // We want enough threads so we can be confident that the client and server are running on different threads and
        // we can accurately simulate race conditions. Two threads seems like an obvious choice, but there are some
        // tests where we have multiple clients and servers in play, and it's good to have some extra room in those
        // cases.
        ApnsClientTest.EVENT_LOOP_GROUP = new NioEventLoopGroup(4);

        CA_CERTIFICATE = new File(ApnsClientTest.class.getResource(CA_CERTIFICATE_FILENAME).toURI());
    }

    @Before
    public void setUp() throws Exception {
        {
            final PrivateKeyEntry privateKeyEntry = P12Util.getFirstPrivateKeyEntryFromP12InputStream(
                    MockApnsServer.class.getResourceAsStream(SERVER_KEYSTORE), SERVER_KEYSTORE_PASSWORD);

            this.server = new MockApnsServerBuilder()
                    .setServerCredentials(new X509Certificate[] { (X509Certificate) privateKeyEntry.getCertificate() }, privateKeyEntry.getPrivateKey(), null)
                    .setTrustedClientCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        this.server.start(PORT).await();

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            this.tlsAuthenticationClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        this.tokenAuthenticationClient = new ApnsClientBuilder()
                .setTrustedServerCertificateChain(CA_CERTIFICATE)
                .setEventLoopGroup(EVENT_LOOP_GROUP)
                .build();

        this.tlsAuthenticationClient.connect(HOST, PORT).await();
        this.tokenAuthenticationClient.connect(HOST, PORT).await();
    }

    @After
    public void tearDown() throws Exception {
        this.tlsAuthenticationClient.disconnect().await();
        this.tokenAuthenticationClient.disconnect().await();

        // Mild hack: there's a harmless race condition where we can try to write a `GOAWAY` from the server to the
        // client in the time between when the client closes the connection and the server notices the connection has
        // been closed. That results in a harmless but slightly alarming warning about failing to write a `GOAWAY` frame
        // and already-closed SSL engines. By sleeping here, we reduce the probability of that warning popping up in
        // test output and frightening users.
        Thread.sleep(10);

        this.server.shutdown().await();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        ApnsClientTest.EVENT_LOOP_GROUP.shutdownGracefully().await();
    }

    @Test
    public void testApnsClientWithManagedEventLoopGroup() throws Exception {
        final ApnsClient managedGroupClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            managedGroupClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .build();
        }

        assertTrue(managedGroupClient.connect(HOST, PORT).await().isSuccess());
        assertTrue(managedGroupClient.disconnect().await().isSuccess());
    }

    @Test
    public void testRestartApnsClientWithManagedEventLoopGroup() throws Exception {
        final ApnsClient managedGroupClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            managedGroupClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .build();
        }

        assertTrue(managedGroupClient.connect(HOST, PORT).await().isSuccess());
        assertTrue(managedGroupClient.disconnect().await().isSuccess());

        final Future<Void> reconnectFuture = managedGroupClient.connect(HOST, PORT);

        assertFalse(reconnectFuture.isSuccess());
        assertTrue(reconnectFuture.cause() instanceof IllegalStateException);
    }

    @Test
    public void testReconnectionAfterClose() throws Exception {
        assertTrue(this.tlsAuthenticationClient.isConnected());
        assertTrue(this.tlsAuthenticationClient.disconnect().await().isSuccess());

        assertFalse(this.tlsAuthenticationClient.isConnected());

        assertTrue(this.tlsAuthenticationClient.connect(HOST, PORT).await().isSuccess());
        assertTrue(this.tlsAuthenticationClient.isConnected());
    }

    @Test
    public void testAutomaticReconnection() throws Exception {
        assertTrue(this.tlsAuthenticationClient.isConnected());

        this.server.shutdown().await();

        // Wait for the client to notice the GOAWAY; if it doesn't, the test will time out and fail
        while (this.tlsAuthenticationClient.isConnected()) {
            Thread.sleep(100);
        }

        assertFalse(this.tlsAuthenticationClient.isConnected());

        this.server.start(PORT).await();

        // Wait for the client to reconnect automatically; if it doesn't, the test will time out and fail
        final Future<Void> reconnectionFuture = this.tlsAuthenticationClient.getReconnectionFuture();
        reconnectionFuture.await();

        assertTrue(reconnectionFuture.isSuccess());
        assertTrue(this.tlsAuthenticationClient.isConnected());
    }

    @Test
    public void testGetReconnectionFutureWhenConnected() throws Exception {
        final Future<Void> reconnectionFuture = this.tlsAuthenticationClient.getReconnectionFuture();
        reconnectionFuture.await();

        assertTrue(this.tlsAuthenticationClient.isConnected());
        assertTrue(reconnectionFuture.isSuccess());
    }

    @Test
    public void testGetReconnectionFutureWhenNotConnected() throws Exception {
        final ApnsClient unconnectedClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            unconnectedClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        final Future<Void> reconnectionFuture = unconnectedClient.getReconnectionFuture();

        reconnectionFuture.await();

        assertFalse(unconnectedClient.isConnected());
        assertFalse(reconnectionFuture.isSuccess());
    }

    @Test(expected = IllegalStateException.class)
    public void testRegisterSigningKeyWithTlsAuthentication() throws Exception {
        this.tlsAuthenticationClient.registerSigningKey((ECPrivateKey) KeyPairUtil.generateKeyPair().getPrivate(), "team-id", "key-id", "topic");
    }

    @Test
    public void testRegisterSigningKey() throws Exception {
        final String teamId = "team-id";
        final String keyId = "key-id";
        final String topic = "topic";
        final String differentTopic = "different-topic";

        this.tokenAuthenticationClient.registerSigningKey((ECPrivateKey) KeyPairUtil.generateKeyPair().getPrivate(), teamId, keyId, topic);
        assertNotNull(this.tokenAuthenticationClient.getAuthenticationTokenSupplierForTopic(topic));

        this.tokenAuthenticationClient.registerSigningKey((ECPrivateKey) KeyPairUtil.generateKeyPair().getPrivate(), teamId, keyId, differentTopic);

        try {
            this.tokenAuthenticationClient.getAuthenticationTokenSupplierForTopic(topic);
            fail("Registering new keys should clear old topics for the given team.");
        } catch (final NoKeyForTopicException e) {
            // This is actually the desired outcome
        }
    }

    @Test
    public void testRegisterSigningKeyFromInputStream() throws Exception {
        try (final InputStream privateKeyInputStream = ApnsClientTest.class.getResourceAsStream(TOKEN_AUTH_PRIVATE_KEY_FILENAME)) {
            // We're happy here as long as nothing explodes
            this.tokenAuthenticationClient.registerSigningKey(privateKeyInputStream, "team-id", "key-id", "topic");
        }
    }

    @Test
    public void testRegisterSigningKeyFromFile() throws Exception {
        final File privateKeyFile = new File(ApnsClientTest.class.getResource(TOKEN_AUTH_PRIVATE_KEY_FILENAME).getFile());

        // We're happy here as long as nothing explodes
        this.tokenAuthenticationClient.registerSigningKey(privateKeyFile, "team-id", "key-id", "topic");
    }

    @Test
    public void testGetAuthenticationTokenSupplierForTopic() throws Exception {
        final String topic = "topic";

        this.tokenAuthenticationClient.registerSigningKey((ECPrivateKey) KeyPairUtil.generateKeyPair().getPrivate(), "team-id", "key-id", topic);
        assertNotNull(this.tokenAuthenticationClient.getAuthenticationTokenSupplierForTopic(topic));
    }

    @Test(expected = NoKeyForTopicException.class)
    public void testGetAuthenticationTokenSupplierForTopicNoRegisteredKey() throws Exception {
        this.tokenAuthenticationClient.getAuthenticationTokenSupplierForTopic("Unregistered topic");
    }

    @Test
    public void testRemoveKeyForTeam() throws Exception {
        final String teamId = "team-id";
        final String topic = "topic";

        this.tokenAuthenticationClient.registerSigningKey((ECPrivateKey) KeyPairUtil.generateKeyPair().getPrivate(), teamId, "key-id", topic);
        assertNotNull(this.tokenAuthenticationClient.getAuthenticationTokenSupplierForTopic(topic));

        this.tokenAuthenticationClient.removeKeyForTeam(teamId);

        try {
            this.tokenAuthenticationClient.getAuthenticationTokenSupplierForTopic(topic);
            fail("No token suppliers should remain after removing keys for a team.");
        } catch (final NoKeyForTopicException e) {
            // This is the desired outcome
        }
    }

    @Test
    public void testSendNotificationBeforeConnected() throws Exception {
        final ApnsClient unconnectedClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            unconnectedClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, testToken, null);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, null, "test-payload");
        final Future<PushNotificationResponse<SimpleApnsPushNotification>> sendFuture =
                unconnectedClient.sendNotification(pushNotification).await();

        assertFalse(sendFuture.isSuccess());
        assertTrue(sendFuture.cause() instanceof IllegalStateException);
    }

    @Test
    public void testSendNotification() throws Exception {
        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, testToken, null);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, DEFAULT_TOPIC, "test-payload");
        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.tlsAuthenticationClient.sendNotification(pushNotification).get();

        assertTrue(response.isAccepted());
    }

    @Test
    public void testSendNotificationWithAuthToken() throws Exception {
        final String testToken = ApnsClientTest.generateRandomToken();
        final KeyPair keyPair = KeyPairUtil.generateKeyPair();

        this.tokenAuthenticationClient.registerSigningKey((ECPrivateKey) keyPair.getPrivate(), DEFAULT_TEAM_ID, DEFAULT_KEY_UD, DEFAULT_TOPIC);

        this.server.registerPublicKey((ECPublicKey) keyPair.getPublic(), DEFAULT_TEAM_ID, DEFAULT_KEY_UD, DEFAULT_TOPIC);
        this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, testToken, null);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, DEFAULT_TOPIC, "test-payload");

        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.tokenAuthenticationClient.sendNotification(pushNotification).get();

        assertTrue(response.isAccepted());
    }

    @Test
    public void testSendNotificationWithExpiredAuthenticationToken() throws Exception {
        final String testToken = ApnsClientTest.generateRandomToken();
        final KeyPair keyPair = KeyPairUtil.generateKeyPair();

        this.tokenAuthenticationClient.registerSigningKey((ECPrivateKey) keyPair.getPrivate(), DEFAULT_TEAM_ID, DEFAULT_KEY_UD, DEFAULT_TOPIC);

        this.server.registerPublicKey((ECPublicKey) keyPair.getPublic(), DEFAULT_TEAM_ID, DEFAULT_KEY_UD, DEFAULT_TOPIC);
        this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, testToken, null);

        final String expiredToken;
        {
            // This is a little roundabout, but it makes sure that we're going to be using an expired auth token for the
            // first shot at sending the notification.
            final AuthenticationTokenSupplier supplier = this.tokenAuthenticationClient.getAuthenticationTokenSupplierForTopic(DEFAULT_TOPIC);

            final String initialToken = supplier.getToken();
            supplier.invalidateToken(initialToken);

            expiredToken = supplier.getToken(new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)));

            assertNotEquals(initialToken, expiredToken);
            assertEquals(expiredToken, supplier.getToken());
        }

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, DEFAULT_TOPIC, "test-payload");
        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.tokenAuthenticationClient.sendNotification(pushNotification).get();

        assertTrue(response.isAccepted());
        assertNotEquals(expiredToken, this.tokenAuthenticationClient.getAuthenticationTokenSupplierForTopic(DEFAULT_TOPIC).getToken());
    }

    @Test
    public void testSendManyNotifications() throws Exception {
        final int notificationCount = 1000;

        final List<SimpleApnsPushNotification> pushNotifications = new ArrayList<>();

        for (int i = 0; i < notificationCount; i++) {
            final String token = ApnsClientTest.generateRandomToken();
            final String payload = ApnsClientTest.generateRandomPayload();

            this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, token, null);
            pushNotifications.add(new SimpleApnsPushNotification(token, DEFAULT_TOPIC, payload));
        }

        final List<Future<PushNotificationResponse<SimpleApnsPushNotification>>> futures = new ArrayList<>();

        for (final SimpleApnsPushNotification pushNotification : pushNotifications) {
            futures.add(this.tlsAuthenticationClient.sendNotification(pushNotification));
        }

        for (final Future<PushNotificationResponse<SimpleApnsPushNotification>> future : futures) {
            future.await();

            assertTrue(future.isSuccess());
            assertTrue(future.get().isAccepted());
        }
    }

    @Test
    public void testSendManyNotificationsWithListeners() throws Exception {
        final int notificationCount = 1000;

        final List<SimpleApnsPushNotification> pushNotifications = new ArrayList<>();

        for (int i = 0; i < notificationCount; i++) {
            final String token = ApnsClientTest.generateRandomToken();
            final String payload = ApnsClientTest.generateRandomPayload();

            this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, token, null);
            pushNotifications.add(new SimpleApnsPushNotification(token, DEFAULT_TOPIC, payload));
        }

        final CountDownLatch countDownLatch = new CountDownLatch(notificationCount);

        for (final SimpleApnsPushNotification pushNotification : pushNotifications) {
            final Future<PushNotificationResponse<SimpleApnsPushNotification>> future =
                    this.tlsAuthenticationClient.sendNotification(pushNotification);

            future.addListener(new GenericFutureListener<Future<PushNotificationResponse<SimpleApnsPushNotification>>>() {

                @Override
                public void operationComplete(final Future<PushNotificationResponse<SimpleApnsPushNotification>> future) throws Exception {
                    if (future.isSuccess()) {
                        final PushNotificationResponse<SimpleApnsPushNotification> pushNotificationResponse = future.get();

                        if (pushNotificationResponse.isAccepted()) {
                            countDownLatch.countDown();
                        }
                    }
                }
            });
        }

        countDownLatch.await();
    }

    // See https://github.com/relayrides/pushy/issues/256
    @Test
    public void testRepeatedlySendSameNotification() throws Exception {
        final int notificationCount = 1000;

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(
                ApnsClientTest.generateRandomToken(), DEFAULT_TOPIC, ApnsClientTest.generateRandomPayload());

        final CountDownLatch countDownLatch = new CountDownLatch(notificationCount);

        for (int i = 0; i < notificationCount; i++) {
            final Future<PushNotificationResponse<SimpleApnsPushNotification>> future =
                    this.tlsAuthenticationClient.sendNotification(pushNotification);

            future.addListener(new GenericFutureListener<Future<PushNotificationResponse<SimpleApnsPushNotification>>>() {

                @Override
                public void operationComplete(final Future<PushNotificationResponse<SimpleApnsPushNotification>> future) throws Exception {
                    // All we're concerned with here is that the client told us SOMETHING about what happened to the
                    // notification
                    countDownLatch.countDown();
                }
            });
        }

        countDownLatch.await();
    }

    @Test
    public void testSendNotificationWithBadTopic() throws Exception {
        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, testToken, null);

        final SimpleApnsPushNotification pushNotification =
                new SimpleApnsPushNotification(testToken, "Definitely not a real topic", "test-payload", null,
                        DeliveryPriority.IMMEDIATE);

        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.tlsAuthenticationClient.sendNotification(pushNotification).get();

        assertFalse(response.isAccepted());
        assertEquals("BadTopic", response.getRejectionReason());
        assertNull(response.getTokenInvalidationTimestamp());
    }

    @Test
    public void testSendNotificationWithMissingTopic() throws Exception {
        final ApnsClient multiTopicClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(MULTI_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            multiTopicClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        multiTopicClient.connect(HOST, PORT).await();

        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, testToken, null);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, null, "test-payload");

        final PushNotificationResponse<SimpleApnsPushNotification> response =
                multiTopicClient.sendNotification(pushNotification).get();

        multiTopicClient.disconnect().await();

        assertFalse(response.isAccepted());
        assertEquals("MissingTopic", response.getRejectionReason());
        assertNull(response.getTokenInvalidationTimestamp());
    }

    @Test
    public void testSendNotificationWithSpecifiedTopic() throws Exception {
        final ApnsClient multiTopicClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(MULTI_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            multiTopicClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        multiTopicClient.connect(HOST, PORT).await();

        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, testToken, null);

        final SimpleApnsPushNotification pushNotification =
                new SimpleApnsPushNotification(testToken, DEFAULT_TOPIC, "test-payload", null, DeliveryPriority.IMMEDIATE);

        final PushNotificationResponse<SimpleApnsPushNotification> response =
                multiTopicClient.sendNotification(pushNotification).get();

        multiTopicClient.disconnect().await();

        assertTrue(response.isAccepted());
    }

    @Test
    public void testSendNotificationWithUnregisteredToken() throws Exception {
        final SimpleApnsPushNotification pushNotification =
                new SimpleApnsPushNotification(ApnsClientTest.generateRandomToken(), DEFAULT_TOPIC, "test-payload");

        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.tlsAuthenticationClient.sendNotification(pushNotification).get();

        assertFalse(response.isAccepted());
        assertEquals("DeviceTokenNotForTopic", response.getRejectionReason());
        assertNull(response.getTokenInvalidationTimestamp());
    }

    @Test
    public void testSendNotificationWithExpiredToken() throws Exception {
        final String testToken = ApnsClientTest.generateRandomToken();

        final Date now = new Date();

        this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, testToken, now);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, DEFAULT_TOPIC, "test-payload");
        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.tlsAuthenticationClient.sendNotification(pushNotification).get();

        assertFalse(response.isAccepted());
        assertEquals("Unregistered", response.getRejectionReason());
        assertEquals(now, response.getTokenInvalidationTimestamp());
    }

    @Test
    public void testSendNotificationWithInternalServerError() throws Exception {
        // Shut down the "normal" server to free the port
        this.tearDown();

        final MockApnsServer terribleTerribleServer;
        {
            final PrivateKeyEntry privateKeyEntry = P12Util.getFirstPrivateKeyEntryFromP12InputStream(
                    MockApnsServer.class.getResourceAsStream(SERVER_KEYSTORE), SERVER_KEYSTORE_PASSWORD);

            terribleTerribleServer = new MockApnsServerBuilder()
                    .setServerCredentials(new X509Certificate[] { (X509Certificate) privateKeyEntry.getCertificate() }, privateKeyEntry.getPrivateKey(), null)
                    .setTrustedClientCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .setEmulateInternalErrors(true)
                    .build();
        }

        final ApnsClient unfortunateClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            unfortunateClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        terribleTerribleServer.start(PORT).await();
        unfortunateClient.connect(HOST, PORT).await();

        try {
            final SimpleApnsPushNotification pushNotification =
                    new SimpleApnsPushNotification(ApnsClientTest.generateRandomToken(), DEFAULT_TOPIC, "test-payload");

            final Future<PushNotificationResponse<SimpleApnsPushNotification>> future =
                    unfortunateClient.sendNotification(pushNotification).await();

            assertTrue(future.isDone());
            assertFalse(future.isSuccess());
            assertTrue(future.cause() instanceof ApnsServerException);
        } finally {
            unfortunateClient.disconnect().await();
            Thread.sleep(10);
            terribleTerribleServer.shutdown().await();
        }
    }

    @Test
    public void testSendNotificationWithTokenAuthMissingPrivateKey() throws Exception {
        final String testToken = ApnsClientTest.generateRandomToken();
        final KeyPair keyPair = KeyPairUtil.generateKeyPair();

        this.server.registerPublicKey((ECPublicKey) keyPair.getPublic(), DEFAULT_TEAM_ID, DEFAULT_KEY_UD, DEFAULT_TOPIC);
        this.server.registerDeviceTokenForTopic(DEFAULT_TOPIC, testToken, null);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, DEFAULT_TOPIC, "test-payload");
        final Future<PushNotificationResponse<SimpleApnsPushNotification>> sendFuture =
                this.tokenAuthenticationClient.sendNotification(pushNotification).await();

        assertFalse(sendFuture.isSuccess());
        assertTrue(sendFuture.cause() instanceof NoKeyForTopicException);
    }

    @Test
    public void testWriteFailureMetrics() throws Exception {
        final ApnsClient unconnectedClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            unconnectedClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        final TestMetricsListener metricsListener = new TestMetricsListener();
        unconnectedClient.setMetricsListener(metricsListener);

        final SimpleApnsPushNotification pushNotification =
                new SimpleApnsPushNotification(ApnsClientTest.generateRandomToken(), null, ApnsClientTest.generateRandomPayload());

        final Future<PushNotificationResponse<SimpleApnsPushNotification>> sendFuture =
                unconnectedClient.sendNotification(pushNotification);

        sendFuture.await();

        // Metrics listeners may be notified of write failures some time after the future actually fails
        metricsListener.waitForNonZeroWriteFailures();

        assertFalse(sendFuture.isSuccess());
        assertEquals(1, metricsListener.getWriteFailures().size());
    }

    @Test
    public void testAcceptedNotificationMetrics() throws Exception {
        final TestMetricsListener metricsListener = new TestMetricsListener();
        this.tlsAuthenticationClient.setMetricsListener(metricsListener);

        this.testSendNotification();
        metricsListener.waitForNonZeroAcceptedNotifications();

        assertEquals(1, metricsListener.getSentNotifications().size());
        assertEquals(metricsListener.getSentNotifications(), metricsListener.getAcceptedNotifications());
        assertTrue(metricsListener.getRejectedNotifications().isEmpty());
    }

    @Test
    public void testRejectedNotificationMetrics() throws Exception {
        final TestMetricsListener metricsListener = new TestMetricsListener();
        this.tlsAuthenticationClient.setMetricsListener(metricsListener);

        this.testSendNotificationWithBadTopic();
        metricsListener.waitForNonZeroRejectedNotifications();

        assertEquals(1, metricsListener.getSentNotifications().size());
        assertEquals(metricsListener.getSentNotifications(), metricsListener.getRejectedNotifications());
        assertTrue(metricsListener.getAcceptedNotifications().isEmpty());
    }

    @Test
    public void testSuccessfulConnectionMetrics() throws Exception {
        final ApnsClient unconnectedClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            unconnectedClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        final TestMetricsListener metricsListener = new TestMetricsListener();
        unconnectedClient.setMetricsListener(metricsListener);

        final Future<Void> connectionFuture = unconnectedClient.connect(HOST, PORT);
        connectionFuture.await();

        metricsListener.waitForNonZeroSuccessfulConnections();

        assertTrue(connectionFuture.isSuccess());
        assertEquals(1, metricsListener.getConnectionAttemptsStarted().get());
        assertEquals(1, metricsListener.getSuccessfulConnectionAttempts().get());
        assertEquals(0, metricsListener.getFailedConnectionAttempts().get());
    }

    @Test
    public void testFailedConnectionMetrics() throws Exception {
        final ApnsClient unconnectedClient;

        try (final InputStream p12InputStream = ApnsClientTest.class.getResourceAsStream(SINGLE_TOPIC_CLIENT_KEYSTORE_FILENAME)) {
            unconnectedClient = new ApnsClientBuilder()
                    .setClientCredentials(p12InputStream, KEYSTORE_PASSWORD)
                    .setTrustedServerCertificateChain(CA_CERTIFICATE)
                    .setEventLoopGroup(EVENT_LOOP_GROUP)
                    .build();
        }

        final TestMetricsListener metricsListener = new TestMetricsListener();
        unconnectedClient.setMetricsListener(metricsListener);

        this.server.shutdown().await();

        final Future<Void> connectionFuture = unconnectedClient.connect(HOST, PORT);
        connectionFuture.await();

        metricsListener.waitForNonZeroFailedConnections();

        assertFalse(connectionFuture.isSuccess());
        assertEquals(1, metricsListener.getConnectionAttemptsStarted().get());
        assertEquals(1, metricsListener.getFailedConnectionAttempts().get());
        assertEquals(0, metricsListener.getSuccessfulConnectionAttempts().get());
    }

    private static String generateRandomToken() {
        final byte[] tokenBytes = new byte[TOKEN_LENGTH];
        new Random().nextBytes(tokenBytes);

        final StringBuilder builder = new StringBuilder(TOKEN_LENGTH * 2);

        for (final byte b : tokenBytes) {
            builder.append(String.format("%02x", b));
        }

        return builder.toString();
    }

    private static String generateRandomPayload() {
        final ApnsPayloadBuilder payloadBuilder = new ApnsPayloadBuilder();
        payloadBuilder.setAlertBody(UUID.randomUUID().toString());

        return payloadBuilder.buildWithDefaultMaximumLength();
    }
}
