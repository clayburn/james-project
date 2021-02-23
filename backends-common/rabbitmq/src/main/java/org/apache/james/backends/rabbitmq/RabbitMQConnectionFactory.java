/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.backends.rabbitmq;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Optional;

import javax.inject.Inject;
import javax.net.ssl.SSLContext;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.TrustStrategy;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import static org.apache.james.backends.rabbitmq.RabbitMQConfiguration.SSLConfiguration.*;

public class RabbitMQConnectionFactory {

    private static final TrustStrategy TRUST_ALL = (x509Certificates, authType) -> true;

    private final ConnectionFactory connectionFactory;

    private final RabbitMQConfiguration configuration;

    @Inject
    public RabbitMQConnectionFactory(RabbitMQConfiguration rabbitMQConfiguration) {
        this.configuration = rabbitMQConfiguration;
        this.connectionFactory = from(rabbitMQConfiguration);
    }

    private ConnectionFactory from(RabbitMQConfiguration rabbitMQConfiguration) {
        try {
            ConnectionFactory connectionFactory = new ConnectionFactory();
            connectionFactory.setUri(rabbitMQConfiguration.getUri());
            connectionFactory.setHandshakeTimeout(rabbitMQConfiguration.getHandshakeTimeoutInMs());
            connectionFactory.setShutdownTimeout(rabbitMQConfiguration.getShutdownTimeoutInMs());
            connectionFactory.setChannelRpcTimeout(rabbitMQConfiguration.getChannelRpcTimeoutInMs());
            connectionFactory.setConnectionTimeout(rabbitMQConfiguration.getConnectionTimeoutInMs());
            connectionFactory.setNetworkRecoveryInterval(rabbitMQConfiguration.getNetworkRecoveryIntervalInMs());

            connectionFactory.setUsername(rabbitMQConfiguration.getManagementCredentials().getUser());
            connectionFactory.setPassword(String.valueOf(rabbitMQConfiguration.getManagementCredentials().getPassword()));

            if (configuration.useSsl()) setupSslConfiguration(connectionFactory);

            return connectionFactory;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setupSslConfiguration(ConnectionFactory connectionFactory) {
        try {
            connectionFactory.useSslProtocol(sslContext(configuration));
            setupHostNameVerification(connectionFactory);
        } catch (KeyManagementException | NoSuchAlgorithmException | CertificateException | KeyStoreException | IOException | UnrecoverableKeyException e) {
            throw new RuntimeException("Cannot set SSL options to the connection factory", e);
        }
    }

    private SSLContext sslContext(RabbitMQConfiguration configuration) throws KeyManagementException, NoSuchAlgorithmException, CertificateException, KeyStoreException, IOException, UnrecoverableKeyException {
        SSLContextBuilder sslContextBuilder = new SSLContextBuilder();

        RabbitMQConfiguration.SSLConfiguration sslConfiguration = configuration.getSslConfiguration();

        setupSslValidationStrategy(sslContextBuilder, sslConfiguration);

        setupClientCertificateAuthentication(sslContextBuilder, sslConfiguration);

        return sslContextBuilder.build();

    }

    private void setupClientCertificateAuthentication(SSLContextBuilder sslContextBuilder, RabbitMQConfiguration.SSLConfiguration sslConfiguration) throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException, CertificateException, IOException {
        Optional<SSLKeyStore> keyStore = sslConfiguration.getKeyStore();

        if (keyStore.isPresent()) {
            SSLKeyStore sslKeyStore = keyStore.get();

            sslContextBuilder.loadKeyMaterial(sslKeyStore.getFile(), sslKeyStore.getPassword(), null);
        }
    }

    private void setupSslValidationStrategy(SSLContextBuilder sslContextBuilder, RabbitMQConfiguration.SSLConfiguration sslConfiguration) throws NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException {
        SSLValidationStrategy strategy = sslConfiguration
                .getStrategy();

        switch (strategy) {
            case DEFAULT:
                break;
            case IGNORE:
                sslContextBuilder.loadTrustMaterial(TRUST_ALL);
                break;
            case OVERRIDE:
                applyTrustStore(sslContextBuilder);
                break;
            default:
                throw new NotImplementedException(
                        String.format("unrecognized strategy '%s'", strategy.name()));
        }
    }

    private SSLContextBuilder applyTrustStore(SSLContextBuilder sslContextBuilder) throws CertificateException, NoSuchAlgorithmException,
            KeyStoreException, IOException {

        SSLTrustStore trustStore = configuration.getSslConfiguration()
                .getTrustStore()
                .orElseThrow(() -> new IllegalStateException("SSLTrustStore cannot to be empty"));

        return sslContextBuilder
                .loadTrustMaterial(trustStore.getFile(), trustStore.getPassword());
    }

    private void setupHostNameVerification(ConnectionFactory connectionFactory) {
        HostNameVerifier hostNameVerifier = configuration.getSslConfiguration()
                .getHostNameVerifier();

        if (hostNameVerifier == HostNameVerifier.DEFAULT) connectionFactory.enableHostnameVerification();
    }

    Connection create() {
        return connectionMono().block();
    }

    Mono<Connection> connectionMono() {
        return Mono.fromCallable(connectionFactory::newConnection)
                .retryWhen(Retry.backoff(configuration.getMaxRetries(), Duration.ofMillis(configuration.getMinDelayInMs())).scheduler(Schedulers.elastic()));
    }
}
