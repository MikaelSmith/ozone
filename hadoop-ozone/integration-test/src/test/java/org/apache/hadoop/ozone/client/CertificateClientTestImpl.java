/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.security.ssl.KeyStoresFactory;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificate.authority.DefaultApprover;
import org.apache.hadoop.hdds.security.x509.certificate.authority.PKIProfiles.DefaultProfile;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClient;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateNotification;
import org.apache.hadoop.hdds.security.x509.certificates.utils.CertificateSignRequest;
import org.apache.hadoop.hdds.security.x509.certificates.utils.SelfSignedCertificate;
import org.apache.hadoop.hdds.security.x509.crl.CRLInfo;
import org.apache.hadoop.hdds.security.x509.exceptions.CertificateException;
import org.apache.hadoop.hdds.security.x509.keys.HDDSKeyGenerator;

import org.apache.hadoop.hdds.security.x509.keys.SecurityUtil;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_X509_DEFAULT_DURATION;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_X509_DEFAULT_DURATION_DEFAULT;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_X509_MAX_DURATION;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_X509_MAX_DURATION_DEFAULT;
import static org.apache.hadoop.hdds.security.x509.exceptions.CertificateException.ErrorCode.CRYPTO_SIGNATURE_VERIFICATION_ERROR;

/**
 * Test implementation for CertificateClient. To be used only for test
 * purposes.
 */

public class CertificateClientTestImpl implements CertificateClient {

  private final OzoneConfiguration config;
  private final SecurityConfig securityConfig;
  private KeyPair keyPair;
  private X509Certificate x509Certificate;
  private final KeyPair rootKeyPair;
  private final X509Certificate rootCert;
  private HDDSKeyGenerator keyGen;
  private DefaultApprover approver;
  private KeyStoresFactory serverKeyStoresFactory;
  private KeyStoresFactory clientKeyStoresFactory;
  private Map<String, X509Certificate> certificateMap;
  private ScheduledExecutorService executorService;
  private Set<CertificateNotification> notificationReceivers;

  public CertificateClientTestImpl(OzoneConfiguration conf)
      throws Exception {
    this(conf, false);
  }

  public CertificateClientTestImpl(OzoneConfiguration conf, boolean autoRenew)
      throws Exception {
    certificateMap = new ConcurrentHashMap<>();
    securityConfig = new SecurityConfig(conf);
    keyGen = new HDDSKeyGenerator(securityConfig.getConfiguration());
    keyPair = keyGen.generateKey();
    rootKeyPair = keyGen.generateKey();
    config = conf;
    LocalDateTime start = LocalDateTime.now();
    String rootCACertDuration = conf.get(HDDS_X509_MAX_DURATION,
        HDDS_X509_MAX_DURATION_DEFAULT);
    LocalDateTime end = start.plus(Duration.parse(rootCACertDuration));

    // Generate RootCA certificate
    SelfSignedCertificate.Builder builder =
        SelfSignedCertificate.newBuilder()
            .setBeginDate(start)
            .setEndDate(end)
            .setClusterID("cluster1")
            .setKey(rootKeyPair)
            .setSubject("rootCA@localhost")
            .setConfiguration(config)
            .setScmID("scm1")
            .makeCA();
    rootCert = new JcaX509CertificateConverter().getCertificate(
        builder.build());
    certificateMap.put(rootCert.getSerialNumber().toString(), rootCert);

    // Generate normal certificate, signed by RootCA certificate
    approver = new DefaultApprover(new DefaultProfile(), securityConfig);

    CertificateSignRequest.Builder csrBuilder = getCSRBuilder();
    // Get host name.
    csrBuilder.setKey(keyPair)
        .setConfiguration(config)
        .setScmID("scm1")
        .setClusterID("cluster1")
        .setSubject("localhost")
        .setDigitalSignature(true)
        .setDigitalEncryption(true);

    start = LocalDateTime.now();
    String certDuration = conf.get(HDDS_X509_DEFAULT_DURATION,
        HDDS_X509_DEFAULT_DURATION_DEFAULT);
    X509CertificateHolder certificateHolder =
        approver.sign(securityConfig, rootKeyPair.getPrivate(),
            new X509CertificateHolder(rootCert.getEncoded()),
            Date.from(start.atZone(ZoneId.systemDefault()).toInstant()),
            Date.from(start.plus(Duration.parse(certDuration))
                .atZone(ZoneId.systemDefault()).toInstant()),
            csrBuilder.build(), "scm1", "cluster1");
    x509Certificate =
        new JcaX509CertificateConverter().getCertificate(certificateHolder);
    certificateMap.put(x509Certificate.getSerialNumber().toString(),
        x509Certificate);

    serverKeyStoresFactory = SecurityUtil.getServerKeyStoresFactory(
        securityConfig, this, true);
    clientKeyStoresFactory = SecurityUtil.getClientKeyStoresFactory(
        securityConfig, this, true);

    if (autoRenew) {
      Duration gracePeriod = securityConfig.getRenewalGracePeriod();
      Date expireDate = x509Certificate.getNotAfter();
      LocalDateTime gracePeriodStart = expireDate.toInstant()
          .atZone(ZoneId.systemDefault()).toLocalDateTime().minus(gracePeriod);
      LocalDateTime currentTime = LocalDateTime.now();
      Duration delay = gracePeriodStart.isBefore(currentTime) ? Duration.ZERO :
          Duration.between(currentTime, gracePeriodStart);

      executorService = Executors.newScheduledThreadPool(1,
          new ThreadFactoryBuilder().setNameFormat("CertificateLifetimeMonitor")
              .setDaemon(true).build());
      this.executorService.schedule(new RenewCertTask(),
          delay.toMillis(), TimeUnit.MILLISECONDS);
    }
    notificationReceivers = new HashSet<>();
  }

  @Override
  public PrivateKey getPrivateKey() {
    return keyPair.getPrivate();
  }

  @Override
  public PublicKey getPublicKey() {
    return keyPair.getPublic();
  }

  /**
   * Returns the certificate  of the specified component if it exists on the
   * local system.
   *
   * @return certificate or Null if there is no data.
   */
  @Override
  public X509Certificate getCertificate(String certSerialId)
      throws CertificateException {
    return certificateMap.get(certSerialId);
  }

  @Override
  public X509Certificate getCertificate() {
    return x509Certificate;
  }

  @Override
  public X509Certificate getCACertificate() {
    return rootCert;
  }

  @Override
  public boolean verifyCertificate(X509Certificate certificate) {
    return true;
  }

  @Override
  public void setCertificateId(String certSerialId) {
  }

  @Override
  public byte[] signDataStream(InputStream stream)
      throws CertificateException {
    return new byte[0];
  }

  @Override
  public byte[] signData(byte[] data) throws CertificateException {
    return new byte[0];
  }

  @Override
  public boolean verifySignature(InputStream stream, byte[] signature,
      X509Certificate cert) throws CertificateException {
    return true;
  }

  @Override
  public boolean verifySignature(byte[] data, byte[] signature,
      X509Certificate cert) throws CertificateException {
    try {
      Signature sign = Signature.getInstance(getSignatureAlgorithm(),
          getSecurityProvider());
      sign.initVerify(cert);
      sign.update(data);
      return sign.verify(signature);
    } catch (NoSuchAlgorithmException | NoSuchProviderException
             | InvalidKeyException | SignatureException e) {
      System.out.println("Error while signing the stream " + e.getMessage());
      throw new CertificateException("Error while signing the stream", e,
          CRYPTO_SIGNATURE_VERIFICATION_ERROR);
    }
  }

  @Override
  public CertificateSignRequest.Builder getCSRBuilder(KeyPair key)
      throws CertificateException {
    return null;
  }

  @Override
  public CertificateSignRequest.Builder getCSRBuilder() {
    return new CertificateSignRequest.Builder();
  }

  @Override
  public String signAndStoreCertificate(PKCS10CertificationRequest request,
      Path certPath) throws CertificateException {
    return null;
  }

  @Override
  public String signAndStoreCertificate(PKCS10CertificationRequest request)
      throws CertificateException {
    return null;
  }

  @Override
  public X509Certificate queryCertificate(String query) {
    return null;
  }

  @Override
  public void storeCertificate(String cert, boolean force)
      throws CertificateException {
  }

  @Override
  public void storeCertificate(String cert, boolean force, boolean caCert)
      throws CertificateException {
  }

  /**
   * Stores the trusted chain of certificates for a specific component.
   *
   * @param keyStore - Cert Store.
   * @throws CertificateException - on Error.
   */
  @Override
  public void storeTrustChain(CertStore keyStore) throws CertificateException {

  }

  @Override
  public void storeTrustChain(List<X509Certificate> certificates)
      throws CertificateException {

  }

  @Override
  public InitResponse init() throws CertificateException {
    return null;
  }

  @Override
  public String getSignatureAlgorithm() {
    return securityConfig.getSignatureAlgo();
  }

  @Override
  public String getSecurityProvider() {
    return securityConfig.getProvider();
  }

  @Override
  public String getComponentName() {
    return null;
  }

  @Override
  public X509Certificate getRootCACertificate() {
    return x509Certificate;
  }

  @Override
  public void storeRootCACertificate(String pemEncodedCert, boolean force) {

  }

  @Override
  public List<String> getCAList() {
    return null;
  }
  @Override
  public List<String> listCA() throws IOException  {
    return null;
  }

  @Override
  public List<String> updateCAList() throws IOException  {
    return null;
  }

  @Override
  public List<CRLInfo> getCrls(List<Long> crlIds) throws IOException {
    return Collections.emptyList();
  }

  @Override
  public long getLatestCrlId() throws IOException {
    return 0;
  }

  @Override
  public long getLocalCrlId() {
    return 0;
  }

  @Override
  public void setLocalCrlId(long crlId) {
  }

  @Override
  public boolean processCrl(CRLInfo crl) {
    return false;
  }

  public void renewKey() throws Exception {
    KeyPair newKeyPair = keyGen.generateKey();
    CertificateSignRequest.Builder csrBuilder = getCSRBuilder();
    // Get host name.
    csrBuilder.setKey(newKeyPair)
        .setConfiguration(config)
        .setScmID("scm1")
        .setClusterID("cluster1")
        .setSubject("localhost")
        .setDigitalSignature(true);

    String certDuration = config.get(HDDS_X509_DEFAULT_DURATION,
        HDDS_X509_DEFAULT_DURATION_DEFAULT);
    Date start = new Date();
    X509CertificateHolder certificateHolder =
        approver.sign(securityConfig, rootKeyPair.getPrivate(),
            new X509CertificateHolder(rootCert.getEncoded()), start,
            new Date(start.getTime() + Duration.parse(certDuration).toMillis()),
            csrBuilder.build(), "scm1", "cluster1");
    X509Certificate newX509Certificate =
        new JcaX509CertificateConverter().getCertificate(certificateHolder);

    // Save the new private key and certificate to file
    // Save certificate and private key to keyStore
    X509Certificate oldCert = x509Certificate;
    keyPair = newKeyPair;
    x509Certificate = newX509Certificate;
    certificateMap.put(x509Certificate.getSerialNumber().toString(),
        x509Certificate);
    System.out.println(new Date() + " certificated is renewed");

    // notify notification receivers
    notificationReceivers.forEach(r -> r.notifyCertificateRenewed(
        oldCert.getSerialNumber().toString(),
        x509Certificate.getSerialNumber().toString()));
  }

  /**
   * Task to renew certificate.
   */
  public class RenewCertTask implements Runnable {
    @Override
    public void run() {
      try {
        renewKey();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public KeyStoresFactory getServerKeyStoresFactory() {
    return serverKeyStoresFactory;
  }

  @Override
  public KeyStoresFactory getClientKeyStoresFactory() {
    return clientKeyStoresFactory;
  }

  @Override
  public void registerNotificationReceiver(CertificateNotification receiver) {
    synchronized (notificationReceivers) {
      notificationReceivers.add(receiver);
    }
  }

  @Override
  public void close() throws IOException {
    if (serverKeyStoresFactory != null) {
      serverKeyStoresFactory.destroy();
    }

    if (clientKeyStoresFactory != null) {
      clientKeyStoresFactory.destroy();
    }

    if (executorService != null) {
      executorService.shutdown();
    }
  }
}
