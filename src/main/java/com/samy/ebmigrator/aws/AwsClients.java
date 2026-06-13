package com.samy.ebmigrator.aws;

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticbeanstalk.ElasticBeanstalkClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Fábrica de clientes del AWS SDK v2.
 *
 * Usa el mismo modelo de seguridad del proyecto: credenciales tomadas de un
 * perfil local del CLI (por defecto {@code eb-manager}, de mínimo privilegio).
 * Nunca se incrustan claves en el código.
 */
public final class AwsClients implements AutoCloseable {

    private final ProfileCredentialsProvider credentials;
    private final Region region;

    private ElasticBeanstalkClient eb;
    private S3Client s3;
    private CodePipelineClient codePipeline;
    private AutoScalingClient autoScaling;
    private Ec2Client ec2;

    public AwsClients(String profile, String region) {
        this.credentials = ProfileCredentialsProvider.create(profile);
        this.region = Region.of(region);
    }

    public synchronized ElasticBeanstalkClient eb() {
        if (eb == null) {
            eb = ElasticBeanstalkClient.builder()
                    .credentialsProvider(credentials)
                    .region(region)
                    .build();
        }
        return eb;
    }

    public synchronized S3Client s3() {
        if (s3 == null) {
            s3 = S3Client.builder()
                    .credentialsProvider(credentials)
                    .region(region)
                    .build();
        }
        return s3;
    }

    public synchronized CodePipelineClient codePipeline() {
        if (codePipeline == null) {
            codePipeline = CodePipelineClient.builder()
                    .credentialsProvider(credentials)
                    .region(region)
                    .build();
        }
        return codePipeline;
    }

    public synchronized AutoScalingClient autoScaling() {
        if (autoScaling == null) {
            autoScaling = AutoScalingClient.builder()
                    .credentialsProvider(credentials)
                    .region(region)
                    .build();
        }
        return autoScaling;
    }

    public synchronized Ec2Client ec2() {
        if (ec2 == null) {
            ec2 = Ec2Client.builder()
                    .credentialsProvider(credentials)
                    .region(region)
                    .build();
        }
        return ec2;
    }

    @Override
    public void close() {
        if (eb != null) eb.close();
        if (s3 != null) s3.close();
        if (codePipeline != null) codePipeline.close();
        if (autoScaling != null) autoScaling.close();
        if (ec2 != null) ec2.close();
    }
}
