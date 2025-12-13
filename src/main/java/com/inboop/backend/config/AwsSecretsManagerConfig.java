
package com.inboop.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.sql.DataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;

@Configuration
@Profile("aws")
public class AwsSecretsManagerConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSecretsManagerConfig.class);

    @Value("${aws.secretsmanager.secret-name}")
    private String secretName;

    @Value("${aws.region:us-east-2}")
    private String awsRegion;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Bean
    public DataSource dataSource() {
        log.info("Fetching database credentials from AWS Secrets Manager: {}", secretName);

        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);

            String username = secretJson.get("username").asText();
            String password = secretJson.get("password").asText();

            // Some RDS secrets include host/port/dbname
            String host = secretJson.has("host") ? secretJson.get("host").asText() : null;
            String port = secretJson.has("port") ? secretJson.get("port").asText() : "5432";
            String dbname = secretJson.has("dbname") ? secretJson.get("dbname").asText() : null;

            String finalJdbcUrl = jdbcUrl;
            if (host != null && dbname != null) {
                finalJdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, dbname);
                log.info("Using database URL from secret: {}:{}/{}", host, port, dbname);
            }

            log.info("Successfully retrieved credentials for user: {}", username);

            return DataSourceBuilder.create()
                    .driverClassName("org.postgresql.Driver")
                    .url(finalJdbcUrl)
                    .username(username)
                    .password(password)
                    .build();

        } catch (Exception e) {
            log.error("Failed to retrieve secret from AWS Secrets Manager: {}", e.getMessage());
            throw new RuntimeException("Unable to retrieve database credentials from Secrets Manager", e);
        }
    }
}
