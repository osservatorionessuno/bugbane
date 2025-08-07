package org.osservatorionessuno.bugbane.utils;

import android.content.pm.PackageManager;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

public class AdbConnectionManager extends AbsAdbConnectionManager {
    private static AbsAdbConnectionManager INSTANCE;
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String ENCRYPTION_KEY_ALIAS = "bugbane_encryption_key";
    private static final String CERT_FILE_NAME = "cert.pem";
    private static final String PRIVATE_KEY_FILE = "private.key.encrypted";

    public static AbsAdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        if (INSTANCE == null) {
            INSTANCE = new AdbConnectionManager(context);
        }
        return INSTANCE;
    }

    private PrivateKey mPrivateKey;
    private PublicKey mPublicKey;
    private Certificate mCertificate;
    private static boolean hasStrongBox;

    private AdbConnectionManager(@NonNull Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hasStrongBox = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
        } else {
            hasStrongBox = false;
        }
        mPrivateKey = readPrivateKeyFromFile(context);
        mCertificate = readCertificateFromFile(context);
        if (mPrivateKey == null) {
            // Generate a new key pair in Android Keystore
            generateKeyPair();
            writePrivateKeyToFile(context, mPrivateKey);
            // Generate a new certificate
            mCertificate = generateCertificate(context);
            // Write certificate to file
            writeCertificateToFile(context, mCertificate);
        }
    }

    private void generateKeyPair() throws Exception {
        int keySize = 2048;
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"));
        KeyPair generateKeyPair = keyPairGenerator.generateKeyPair();
        mPrivateKey = generateKeyPair.getPrivate();
        mPublicKey = generateKeyPair.getPublic();
    }

    private Certificate generateCertificate(@NonNull Context context) throws Exception {
        String subject = "CN=Bugbane";
        String algorithmName = "SHA512withRSA";
        long expiryDate = System.currentTimeMillis() + 86400000;
        CertificateExtensions certificateExtensions = new CertificateExtensions();
        certificateExtensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(mPublicKey).getIdentifier()));
        X500Name x500Name = new X500Name(subject);
        Date notBefore = new Date();
        Date notAfter = new Date(expiryDate);
        certificateExtensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        CertificateValidity certificateValidity = new CertificateValidity(notBefore, notAfter);
        X509CertInfo x509CertInfo = new X509CertInfo();
        x509CertInfo.set("version", new CertificateVersion(2));
        x509CertInfo.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        x509CertInfo.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
        x509CertInfo.set("subject", new CertificateSubjectName(x500Name));
        x509CertInfo.set("key", new CertificateX509Key(mPublicKey));
        x509CertInfo.set("validity", certificateValidity);
        x509CertInfo.set("issuer", new CertificateIssuerName(x500Name));
        x509CertInfo.set("extensions", certificateExtensions);
        X509CertImpl x509CertImpl = new X509CertImpl(x509CertInfo);
        x509CertImpl.sign(mPrivateKey, algorithmName);
        return x509CertImpl;
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "Bugbane";
    }

    @Nullable
    private static Certificate readCertificateFromFile(@NonNull Context context)
            throws IOException, CertificateException {
        File certFile = new File(context.getFilesDir(), CERT_FILE_NAME);
        if (!certFile.exists()) return null;
        try (InputStream cert = new FileInputStream(certFile)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(cert);
        }
    }

    private static void writeCertificateToFile(@NonNull Context context, @NonNull Certificate certificate)
            throws CertificateEncodingException, IOException {
        File certFile = new File(context.getFilesDir(), CERT_FILE_NAME);
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream os = new FileOutputStream(certFile)) {
            os.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            os.write('\n');
            encoder.encode(certificate.getEncoded(), os);
            os.write('\n');
            os.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private static PrivateKey readPrivateKeyFromFile(@NonNull Context context)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        File privateKeyFile = new File(context.getFilesDir(), PRIVATE_KEY_FILE);
        if (!privateKeyFile.exists()) return null;
        
        try {
            // Read encrypted data
            byte[] encryptedData = new byte[(int) privateKeyFile.length()];
            try (InputStream is = new FileInputStream(privateKeyFile)) {
                is.read(encryptedData);
            }
            
            // Decrypt the private key
            byte[] decryptedKeyData = decryptData(context, encryptedData);
            
            // Convert to PrivateKey
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(decryptedKeyData);
            return keyFactory.generatePrivate(privateKeySpec);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void writePrivateKeyToFile(@NonNull Context context, @NonNull PrivateKey privateKey)
            throws IOException {
        try {
            // Encrypt the private key data
            byte[] keyData = privateKey.getEncoded();
            byte[] encryptedData = encryptData(context, keyData);
            
            // Write encrypted data to file
            File privateKeyFile = new File(context.getFilesDir(), PRIVATE_KEY_FILE);
            try (OutputStream os = new FileOutputStream(privateKeyFile)) {
                os.write(encryptedData);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException("Failed to encrypt and write private key", e);
        }
    }

    private static byte[] encryptData(@NonNull Context context, @NonNull byte[] data) throws Exception {
        SecretKey secretKey = getOrGenerateEncryptionKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        
        byte[] iv = cipher.getIV();
        byte[] encryptedData = cipher.doFinal(data);
        byte[] result = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encryptedData, 0, result, iv.length, encryptedData.length);
        
        return result;
    }

    private static byte[] decryptData(@NonNull Context context, @NonNull byte[] encryptedData) throws Exception {
        SecretKey secretKey = getOrGenerateEncryptionKey();
        byte[] iv = new byte[12];
        byte[] data = new byte[encryptedData.length - 12];
        System.arraycopy(encryptedData, 0, iv, 0, 12);
        System.arraycopy(encryptedData, 12, data, 0, data.length);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

        return cipher.doFinal(data);
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private static SecretKey getOrGenerateEncryptionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        
        if (keyStore.containsAlias(ENCRYPTION_KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(ENCRYPTION_KEY_ALIAS, null);
            return entry.getSecretKey();
        } else {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    ENCRYPTION_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256);

            if (hasStrongBox) {
                builder.setUserConfirmationRequired(true)
                        .setIsStrongBoxBacked(true);
            }

            keyGenerator.init(builder.build());
            return keyGenerator.generateKey();
        }
    }
}
