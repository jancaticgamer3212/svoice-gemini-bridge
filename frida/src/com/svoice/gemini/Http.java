package com.svoice.gemini;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class Http {
    private static SSLSocketFactory factory;

    private Http() {}

    static synchronized SSLSocketFactory factory() throws Exception {
        if (factory != null) return factory;

        final X509TrustManager system = systemTrustManager();
        final X509TrustManager pinned = pinnedTrustManager();

        X509TrustManager composite = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    pinned.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] a = system.getAcceptedIssuers();
                X509Certificate[] b = pinned.getAcceptedIssuers();
                X509Certificate[] all = new X509Certificate[a.length + b.length];
                System.arraycopy(a, 0, all, 0, a.length);
                System.arraycopy(b, 0, all, a.length, b.length);
                return all;
            }
        };

        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLSv1.2");
        } catch (Throwable t) {
            ctx = SSLContext.getInstance("TLS");
        }
        ctx.init(null, new TrustManager[]{composite}, new SecureRandom());
        factory = ctx.getSocketFactory();
        return factory;
    }

    private static X509TrustManager systemTrustManager() throws Exception {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
            tmf.init((KeyStore) null);
            return firstX509(tmf.getTrustManagers());
        } catch (Throwable ignored) {
        }
        KeyStore store = KeyStore.getInstance("AndroidCAStore");
        store.load(null, null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        return firstX509(tmf.getTrustManagers());
    }

    private static X509TrustManager pinnedTrustManager() throws Exception {
        KeyStore store = newEmptyStore();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        String[] pems = new String[]{Certificates.GTS_R1, Certificates.GTS_R4};
        for (int i = 0; i < pems.length; i++) {
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(pems[i].getBytes(StandardCharsets.US_ASCII)));
            store.setCertificateEntry("gts-" + i, cert);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
        tmf.init(store);
        return firstX509(tmf.getTrustManagers());
    }

    private static KeyStore newEmptyStore() throws Exception {
        String[] types = new String[]{"PKCS12", "BKS", KeyStore.getDefaultType()};
        Exception last = null;
        for (String type : types) {
            try {
                KeyStore store = KeyStore.getInstance(type);
                store.load(null, null);
                return store;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("No usable keystore type");
    }

    private static X509TrustManager firstX509(TrustManager[] managers) {
        for (TrustManager m : managers) {
            if (m instanceof X509TrustManager) return (X509TrustManager) m;
        }
        throw new IllegalStateException("No X509 trust manager available");
    }
}
