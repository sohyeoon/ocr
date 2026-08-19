package com.filesearch.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;

@Configuration
public class OpenSearchConfig {

    @Value("${opensearch.host}")
    private String host;

    @Value("${opensearch.port}")
    private int port;

    @Value("${opensearch.scheme}")
    private String scheme;

    @Value("${opensearch.username}")
    private String username;

    @Value("${opensearch.password}")
    private String password;

    @Bean
    public RestHighLevelClient openSearchClient() throws Exception {

        CredentialsProvider credentialsProvider =
                new BasicCredentialsProvider();

        credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(
                        username,
                        password
                )
        );

        SSLContext sslContext = SSLContexts.custom()
                .loadTrustMaterial((certificate, authType) -> true)
                .build();

        return new RestHighLevelClient(
                RestClient.builder(
                                new HttpHost(host, port, scheme)
                        )
                        .setHttpClientConfigCallback(httpClientBuilder ->
                                httpClientBuilder
                                        .setSSLContext(sslContext)
                                        .setSSLHostnameVerifier(
                                                NoopHostnameVerifier.INSTANCE
                                        )
                                        .setDefaultCredentialsProvider(
                                                credentialsProvider
                                        )
                        )
        );
    }
}